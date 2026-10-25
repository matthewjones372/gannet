package io.github.matthewjones372.gannet

import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

private const val PIECE = 64 * 1024
private const val PIECES = 2048 // 128 MiB, far more than the kernel's socket buffers hold

class StreamingTest {
    private val threads = Executors.newVirtualThreadPerTaskExecutor()

    @AfterEach
    fun close() = threads.close()

    /** Streams [PIECES] pieces, counting the bytes each write got past, and says when it is done. */
    private class Producer {
        val written = AtomicLong()
        val finished = CountDownLatch(1)

        fun produce(exchange: HttpExchange) {
            val piece = ByteArray(PIECE) { 'p'.code.toByte() }
            exchange.stream(200).use { out ->
                repeat(PIECES) {
                    out.write(piece)
                    written.addAndGet(PIECE.toLong())
                }
            }
            finished.countDown()
        }
    }

    /** Reads a reply's head, leaving its body unread on [input]. */
    private fun readHead(input: InputStream) = generateSequence { line(input) }.takeWhile { it.isNotEmpty() }.toList()

    /** Reads a chunked body off [input] and counts its bytes. */
    private fun countChunked(input: InputStream): Long {
        var total = 0L
        while (true) {
            val size = line(input)!!.toInt(16)
            if (size == 0) return total.also { line(input) }
            total += input.readNBytes(size).size
            line(input)
        }
    }

    @Test
    fun `a slow reader holds a writer on a virtual thread`() {
        val producer = Producer()
        serving(handler = { ex -> ex.offload(threads) { producer.produce(it) } }) { server ->
            Raw(server.port).use { raw ->
                raw.send(get("/"))
                val input = raw.socket.getInputStream()
                readHead(input).first() shouldBe "HTTP/1.1 200 OK"
                // Nothing more is read: the writer must stop well short of the end, at what the kernel will buffer.
                producer.finished.await(1, TimeUnit.SECONDS) shouldBe false
                producer.written.get() shouldBeLessThan PIECE.toLong() * PIECES / 2
                countChunked(input) shouldBe PIECE.toLong() * PIECES
                producer.finished.await(30, TimeUnit.SECONDS) shouldBe true
            }
        }
    }

    @Test
    fun `a slow reader holds a writer on the carrier, and the carrier serves other connections meanwhile`() {
        val producer = Producer()
        serving(
            testConfig.copy(carriers = 1),
            { ex ->
                if (ex.request.path ==
                    "/stream"
                ) producer.produce(ex) else ex.respond(200, body = "other".toByteArray())
            },
        ) { server ->
            Raw(server.port).use { slow ->
                slow.send(get("/stream"))
                val input = slow.socket.getInputStream()
                readHead(input)
                producer.finished.await(1, TimeUnit.SECONDS) shouldBe false
                Raw(server.port).use { other -> other.send(get("/other")).read().text shouldBe "other" }
                countChunked(input) shouldBe PIECE.toLong() * PIECES
            }
        }
    }

    @Test
    fun `a client that goes away fails the writer instead of holding it`() {
        val failed = CountDownLatch(1)
        serving(
            handler = { ex ->
                ex.offload(threads) {
                    try {
                        Producer().produce(it)
                    } catch (e: java.io.IOException) {
                        failed.countDown()
                    }
                }
            },
        ) { server ->
            Raw(server.port).use { raw ->
                raw.send(get("/"))
                readHead(raw.socket.getInputStream())
            }
            failed.await(30, TimeUnit.SECONDS) shouldBe true
        }
    }

    @Test
    fun `an upload is held by a slow reader and read whole once it reads`() {
        val gate = CountDownLatch(1)
        val total = 64L * 1024 * 1024
        serving(
            handler = { ex ->
                ex.offload(threads) {
                    val body = it.request.body
                    val buffer = ByteArray(PIECE)
                    var read = body.read(buffer, 0, 1).toLong()
                    gate.await()
                    while (true) {
                        val n = body.read(buffer, 0, buffer.size)
                        if (n < 0) break
                        read += n
                    }
                    it.respond(200, body = read.toString().toByteArray())
                }
            },
        ) { server ->
            Raw(server.port).use { raw ->
                val sent = AtomicLong()
                val sender = Thread.ofVirtual().start {
                    val out = raw.socket.getOutputStream()
                    out.write("POST / HTTP/1.1\r\nHost: x\r\nContent-Length: $total\r\n\r\n".toByteArray())
                    val piece = ByteArray(PIECE) { 'u'.code.toByte() }
                    while (sent.get() < total) {
                        out.write(piece)
                        sent.addAndGet(PIECE.toLong())
                    }
                }
                // The handler has read one byte and stopped: the sender must stall at what the buffers hold.
                sender.join(1000)
                sender.isAlive shouldBe true
                sent.get() shouldBeLessThan total / 2
                gate.countDown()
                raw.read().text shouldBe total.toString()
                sender.join()
            }
        }
    }

    @Test
    fun `a streamed upload reaches the handler before it has all been sent`() {
        val firstSeen = CountDownLatch(1)
        serving(
            handler = { ex ->
                ex.offload(threads) {
                    val input = it.request.body.inputStream()
                    val first = String(input.readNBytes(5))
                    firstSeen.countDown()
                    val rest = String(input.readAllBytes())
                    it.respond(200, body = "$first|$rest".toByteArray())
                }
            },
        ) { server ->
            Raw(server.port).use { raw ->
                raw.send("POST / HTTP/1.1\r\nHost: x\r\nTransfer-Encoding: chunked\r\n\r\n5\r\nfirst\r\n")
                firstSeen.await(10, TimeUnit.SECONDS) shouldBe true
                raw.send("4\r\nlast\r\n0\r\n\r\n").read().text shouldBe "first|last"
            }
        }
    }
}
