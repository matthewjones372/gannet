package io.github.matthewjones372.gannet

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.ints.shouldBeLessThan
import io.kotest.matchers.longs.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.io.IOException
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

private fun ms(millis: Long): Duration = Duration.ofMillis(millis)

private val ok = HttpHandler { it.respond(200, body = "ok".toByteArray()) }

/**
 * Each limit over real sockets: the misbehaving client is cut off, and the server goes on serving. The limits are
 * set small, and every wait for a close is bounded generously rather than timed, so a busy machine only makes a test
 * slower, never wrong.
 */
class LimitsTest {
    private val threads = Executors.newVirtualThreadPerTaskExecutor()

    @AfterEach
    fun close() = threads.close()

    /** Reads [socket] until the server ends it; a read that waits longer than its timeout fails the test. */
    private fun closes(socket: Socket) {
        socket.soTimeout = BOUND
        val input = socket.getInputStream()
        val sink = ByteArray(PIECE)
        try {
            while (input.read(sink) >= 0) continue
        } catch (_: SocketException) {
            // Reset: closed with our bytes unread, which is closed all the same.
        }
    }

    /** Writes [bytes] to [socket] one at a time, [every] apart, until done or the server cuts it off. */
    private fun drip(socket: Socket, bytes: ByteArray, every: Duration) = threads.submit {
        try {
            for (b in bytes) {
                socket.getOutputStream().write(b.toInt())
                socket.getOutputStream().flush()
                Thread.sleep(every)
            }
        } catch (_: IOException) {
            // Cut off, as it should be.
        }
    }

    private fun serves(server: GannetServer) =
        Raw(server.port).use { it.send(get("/after")).read().status shouldBe 200 }

    // ------------------------------------------------------------------ timeouts

    @Test
    fun `a kept-alive connection that sends no next request is closed after the idle timeout`() =
        serving(testConfig.copy(idleTimeout = ms(300)), ok) { server ->
            Raw(server.port).use { raw ->
                raw.send(get("/")).read().status shouldBe 200
                closes(raw.socket)
            }
            serves(server)
        }

    @Test
    fun `a head sent a byte at a time is cut off by the head timeout, though each byte beats the idle timeout`() =
        serving(testConfig.copy(headTimeout = ms(400)), ok) { server ->
            Raw(server.port).use { raw ->
                val head = get("/", "X-Slow: " + "s".repeat(LONG_FIELD)).toByteArray()
                val dripping = drip(raw.socket, head, ms(DRIP))
                closes(raw.socket)
                raw.socket.close()
                dripping.get(BOUND.toLong(), TimeUnit.MILLISECONDS)
            }
            serves(server)
        }

    @Test
    fun `a body that trickles in slower than the minimum rate is cut off`() {
        val config = testConfig.copy(bodyTimeout = ms(400), minBodyRate = 100)
        serving(config, { ex -> ex.respond(200, body = ex.request.body.readAll()) }) { server ->
            Raw(server.port).use { raw ->
                raw.send("POST / HTTP/1.1\r\nHost: x\r\nContent-Length: 100000\r\n\r\n")
                // A byte every 50 ms earns 10 ms at 100 bytes a second, so the allowance runs out.
                val dripping = drip(raw.socket, ByteArray(LONG_FIELD) { 'b'.code.toByte() }, ms(DRIP))
                closes(raw.socket)
                raw.socket.close()
                dripping.get(BOUND.toLong(), TimeUnit.MILLISECONDS)
            }
            serves(server)
        }
    }

    @Test
    fun `a body that arrives at a steady rate outlasts the body timeout`() {
        val config = testConfig.copy(bodyTimeout = ms(300), minBodyRate = 1000)
        serving(config, { ex -> ex.respond(200, body = ex.request.body.readAll()) }) { server ->
            Raw(server.port).use { raw ->
                val piece = ByteArray(STEADY_PIECE) { 'b'.code.toByte() }
                raw.send("POST / HTTP/1.1\r\nHost: x\r\nContent-Length: ${STEADY_PIECE * STEADY_PIECES}\r\n\r\n")
                // Each piece earns 200 ms and costs 50, so the allowance only grows.
                repeat(STEADY_PIECES) {
                    raw.send(piece)
                    Thread.sleep(DRIP)
                }
                raw.read().body.size shouldBe STEADY_PIECE * STEADY_PIECES
            }
        }
    }

    @Test
    fun `a client that stops reading fails a streaming handler's write, on the carrier or off it, and is closed`() {
        val failures = mapOf("/carrier" to CompletableFuture<Throwable>(), "/offloaded" to CompletableFuture())

        fun streamForever(ex: HttpExchange) {
            val piece = ByteArray(PIECE)
            try {
                ex.stream(200).use { out -> while (true) out.write(piece) }
            } catch (e: IOException) {
                failures.getValue(ex.request.path).complete(e)
                throw e
            }
        }
        val handler = HttpHandler { ex ->
            when (ex.request.path) {
                "/carrier" -> streamForever(ex)
                "/offloaded" -> ex.offload(threads) { streamForever(it) }
                else -> ok.handle(ex)
            }
        }
        serving(testConfig.copy(writeTimeout = ms(300)), handler) { server ->
            for ((path, failure) in failures) {
                Raw(server.port).use { raw ->
                    raw.send(get(path))
                    failure.get(BOUND.toLong(), TimeUnit.MILLISECONDS).shouldBeInstanceOf<IOException>()
                    closes(raw.socket)
                }
            }
            serves(server)
        }
    }

    @Test
    fun `a TLS handshake dribbled a byte at a time is cut off by the handshake timeout`() =
        // Long enough for a first handshake in a cold JVM, short of the 20 s the drip would take.
        serving(tlsConfig.copy(handshakeTimeout = ms(HANDSHAKE * 2)), ok) { server ->
            Raw(server.port).use { raw ->
                // A record header promising a ClientHello, then its body a byte at a time.
                raw.send(byteArrayOf(0x16, 3, 1, 1, 0))
                val dripping = drip(raw.socket, ByteArray(LONG_FIELD), ms(DRIP))
                closes(raw.socket)
                raw.socket.close()
                dripping.get(BOUND.toLong(), TimeUnit.MILLISECONDS)
            }
            tlsRaw(server.port).use { it.send(get("/after")).read().status shouldBe 200 }
        }

    // ------------------------------------------------------------------ connection caps

    @Test
    fun `past the connection cap new connections wait unserved until one closes`() =
        serving(testConfig.copy(maxConnections = 2), ok) { server ->
            val first = Raw(server.port)
            Raw(server.port).use { second ->
                first.send(get("/")).read().status shouldBe 200
                second.send(get("/")).read().status shouldBe 200
                Raw(server.port).use { third ->
                    third.send(get("/"))
                    third.socket.soTimeout = UNSERVED
                    shouldThrow<SocketTimeoutException> { third.read() }
                    first.close()
                    third.socket.soTimeout = BOUND
                    third.read().status shouldBe 200
                }
            }
            serves(server)
        }

    @Test
    fun `past the handshake cap a carrier accepts no more until a handshake ends`() {
        val config = tlsConfig.copy(carriers = 1, maxHandshakes = 1, handshakeTimeout = ms(HANDSHAKE))
        serving(config, ok) { server ->
            val started = System.nanoTime()
            // Connects and says nothing, holding the carrier's only handshake place until its timeout.
            Raw(server.port).use { staller ->
                tlsRaw(server.port).use { client ->
                    (System.nanoTime() - started) shouldBeGreaterThan ms(HANDSHAKE * 8 / 10).toNanos()
                    client.send(get("/")).read().status shouldBe 200
                }
                closes(staller.socket)
            }
        }
    }

    // ------------------------------------------------------------------ memory caps

    @Test
    fun `replies a client is not reading stop the server reading its pipelined requests`() {
        val handled = AtomicInteger()
        val big = ByteArray(BIG_REPLY)
        val handler = HttpHandler { ex ->
            handled.incrementAndGet()
            ex.respond(200, body = big)
        }
        serving(testConfig.copy(maxBufferedBytes = PIECE), handler) { server ->
            Raw(server.port).use { raw ->
                raw.send(get("/").repeat(PIPELINED))
                // Uncapped, every request would be answered into memory by now; capped, the server waits on the
                // kernel's socket buffers, which hold a fraction of the replies.
                Thread.sleep(RUN_AHEAD)
                handled.get() shouldBeLessThan PIPELINED
                repeat(PIPELINED) { raw.read().body.size shouldBe BIG_REPLY }
            }
        }
    }

    @Test
    fun `a request holding more unread bytes than the cap closes its connection`() {
        val config = testConfig.copy(maxHeaderBytes = 4 * PIECE, maxBufferedBytes = PIECE / 4)
        serving(config, ok) { server ->
            Raw(server.port).use { raw ->
                raw.send(get("/", "X-Big: " + "b".repeat(PIECE)))
                closes(raw.socket)
            }
            serves(server)
        }
    }

    private companion object {
        const val BOUND = 15_000
        const val UNSERVED = 500
        const val DRIP = 50L
        const val LONG_FIELD = 400
        const val PIECE = 64 * 1024
        const val STEADY_PIECE = 200
        const val STEADY_PIECES = 10
        const val HANDSHAKE = 1000L
        const val BIG_REPLY = 256 * 1024
        const val PIPELINED = 200
        const val RUN_AHEAD = 1000L
    }
}
