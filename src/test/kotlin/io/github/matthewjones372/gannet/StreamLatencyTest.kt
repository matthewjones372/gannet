package io.github.matthewjones372.gannet

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.longs.shouldBeGreaterThan
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

private const val FRAMES = 400
private const val FRAME = 64
private const val BURST = 4000
private const val WATERMARK = 1 shl 20
private const val PIECE = 16 * 1024

/** How a streamed reply's writes are buffered under [GannetConfig.streamHighWatermark], and held back above it. */
class StreamLatencyTest {
    private val threads = Executors.newVirtualThreadPerTaskExecutor()

    @AfterEach
    fun close() = threads.close()

    /** One chunk's data off [input], or null at the last chunk. */
    private fun chunk(input: InputStream): ByteArray? {
        val size = line(input)!!.toInt(16)
        if (size == 0) return null.also { line(input) }
        return input.readNBytes(size).also { line(input) }
    }

    /** Asks [raw]'s server for a stream and reads the reply's head, leaving its body on the returned input. */
    private fun open(raw: Raw): InputStream {
        raw.send(get("/"))
        val input = raw.socket.getInputStream().buffered()
        generateSequence { line(input) }.takeWhile { it.isNotEmpty() }.toList().first() shouldBe "HTTP/1.1 200 OK"
        return input
    }

    @Test
    fun `each frame written off the carrier reaches the client while the writer waits on the client`() {
        val received = Semaphore(0)
        val times = LongArray(FRAMES)
        val handler = HttpHandler { ex ->
            ex.offload(threads) {
                it.stream(200).use { out ->
                    val frame = ByteArray(FRAME) { 'f'.code.toByte() }
                    repeat(FRAMES + 1) {
                        out.write(frame)
                        received.tryAcquire(10, TimeUnit.SECONDS) shouldBe true
                    }
                }
            }
        }
        serving(handler = handler) { server ->
            Raw(server.port).use { raw ->
                val input = open(raw)
                chunk(input)!!.size shouldBe FRAME
                repeat(FRAMES) { i ->
                    val released = System.nanoTime()
                    received.release()
                    chunk(input)!!.size shouldBe FRAME
                    times[i] = System.nanoTime() - released
                }
                received.release()
                chunk(input) shouldBe null
            }
        }
        val p50 = times.sorted()[FRAMES / 2]
        println("Per-frame p50, client release to frame, written off the carrier: ${p50 / 1000} us")
        // A round trip per frame took milliseconds; a frame that goes out as it is written takes a thread hand-off.
        p50 shouldBeLessThan TimeUnit.MILLISECONDS.toNanos(1)
    }

    @Test
    fun `a frame written on the carrier goes out before the handler parks`() {
        val seen = AtomicLong()
        val stuck = AtomicBoolean()
        val handler = HttpHandler { ex ->
            ex.stream(200).use { out ->
                (1..3).forEach { n ->
                    out.write("$n".toByteArray())
                    // Spins on the carrier without parking: only a frame already handed to the kernel can arrive.
                    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                    while (seen.get() < n && !stuck.get()) {
                        if (System.nanoTime() > deadline) stuck.set(true)
                        Thread.onSpinWait()
                    }
                }
            }
        }
        serving(handler = handler) { server ->
            Raw(server.port).use { raw ->
                val input = open(raw)
                (1..3).forEach { n ->
                    String(chunk(input)!!) shouldBe "$n"
                    seen.set(n.toLong())
                }
                chunk(input) shouldBe null
            }
        }
        stuck.get() shouldBe false
    }

    /** Milliseconds to stream [BURST] small frames from another thread to a client reading as fast as it can. */
    private fun burst(watermark: Int): Long {
        var elapsed = Long.MAX_VALUE
        val handler = HttpHandler { ex ->
            ex.offload(threads) {
                it.stream(200).use { out ->
                    val frame = ByteArray(FRAME) { 'b'.code.toByte() }
                    repeat(BURST) { out.write(frame) }
                }
            }
        }
        serving(testConfig.copy(streamHighWatermark = watermark), handler) { server ->
            repeat(3) {
                Raw(server.port).use { raw ->
                    val start = System.nanoTime()
                    val input = open(raw)
                    var frames = 0
                    while (chunk(input) != null) frames++
                    frames shouldBe BURST
                    elapsed = minOf(elapsed, System.nanoTime() - start)
                }
            }
        }
        return TimeUnit.NANOSECONDS.toMillis(elapsed)
    }

    @Test
    fun `a burst written off the carrier does not wait for each frame's send`() {
        // A watermark of zero is a write that waits for its own send, a round trip through the carrier per frame.
        burst(0)
        val waiting = burst(0)
        val buffered = burst(GannetConfig().streamHighWatermark)
        println("$BURST frames off the carrier: $waiting ms waiting for each send, $buffered ms buffered")
        buffered * 3 shouldBeLessThan waiting
    }

    /** How many bytes [watermark] lets a writer get past a client that reads nothing, once the writer stalls. */
    private fun stallPoint(watermark: Int, offload: Boolean): Long {
        val written = AtomicLong()
        val produce = { ex: HttpExchange ->
            try {
                ex.stream(200).use { out ->
                    val piece = ByteArray(PIECE)
                    while (true) {
                        out.write(piece)
                        written.addAndGet(PIECE.toLong())
                    }
                }
            } catch (_: IOException) {
                // The client went away at the end of the test.
            }
        }
        var stalled = 0L
        serving(testConfig.copy(streamHighWatermark = watermark), { ex ->
            if (offload) ex.offload(threads) { produce(it) } else produce(ex)
        }) { server ->
            Raw(server.port).use { raw ->
                raw.send(get("/"))
                // Stalled once it has written something and then nothing more for a while.
                var last = -1L
                while (stalled == 0L || stalled != last) {
                    last = stalled
                    Thread.sleep(300)
                    stalled = written.get()
                }
            }
        }
        return stalled
    }

    private fun boundedBy(offload: Boolean) {
        // What the kernel's socket buffers hold, which no watermark changes.
        val kernel = stallPoint(0, offload)
        val held = stallPoint(WATERMARK, offload)
        println("A client reading nothing, offload=$offload: ${kernel / 1024} KiB at zero, ${held / 1024} KiB at 1 MiB")
        held shouldBeGreaterThan 0L
        // On the carrier's side of the socket: up to a watermark unsent, and as much again posted from another thread.
        held - kernel shouldBeLessThan 2L * WATERMARK + 2 * PIECE + (256 shl 10)
    }

    @Test
    fun `a client that reads nothing holds a writer on another thread at the watermark`() = boundedBy(offload = true)

    @Test
    fun `a client that reads nothing holds a writer on the carrier at the watermark`() = boundedBy(offload = false)

    @Test
    fun `pieces written without waiting arrive whole and in order, ahead of the last chunk`() {
        val expected = ByteArrayOutputStream()
        val pieces = (0 until 5000).map { i -> "$i:".padEnd(1 + i % 300, '.').toByteArray() }
        pieces.forEach { expected.write(it) }
        val handler = HttpHandler { ex ->
            ex.offload(threads) {
                it.stream(200).use { out ->
                    // Each piece from one reused array, so a piece not copied before write returns would show.
                    val scratch = ByteArray(512)
                    pieces.forEach { piece ->
                        piece.copyInto(scratch)
                        out.write(scratch, 0, piece.size)
                    }
                }
            }
        }
        serving(handler = handler) { server ->
            Raw(server.port).use { raw ->
                raw.send(get("/")).read().body.contentEquals(expected.toByteArray()) shouldBe
                    true
            }
        }
    }

    @Test
    fun `flush waits for the kernel from either side, and fails once the stream has ended`() {
        val afterClose = LinkedBlockingQueue<Throwable>()
        val handler = HttpHandler { ex ->
            val write = { exchange: HttpExchange ->
                val out = exchange.stream(200)
                out.write("one ".toByteArray())
                out.flush()
                out.write("two".toByteArray())
                out.flush()
                out.close()
                afterClose.add(shouldThrow<IllegalStateException> { out.flush() })
            }
            if (ex.request.path == "/carrier") write(ex) else ex.offload(threads) { write(it) }
        }
        serving(handler = handler) { server ->
            listOf("/carrier", "/offload").forEach { path ->
                Raw(server.port).use { raw -> raw.send(get(path)).read().text shouldBe "one two" }
                afterClose.poll(10, TimeUnit.SECONDS)!!.message shouldBe "The reply stream has ended"
            }
        }
    }

    @Test
    fun `a writer that did not wait learns on a later call that the client went away`() {
        val failed = CountDownLatch(1)
        val handler = HttpHandler { ex ->
            ex.offload(threads) {
                val out = it.stream(200)
                try {
                    val frame = ByteArray(FRAME)
                    while (true) out.write(frame)
                } catch (_: IOException) {
                    failed.countDown()
                }
            }
        }
        serving(handler = handler) { server ->
            Raw(server.port).use { raw -> open(raw) }
            failed.await(30, TimeUnit.SECONDS) shouldBe true
        }
    }
}
