package io.github.matthewjones372.gannet

import io.github.matthewjones372.gannet.runtime.ServerStats
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.net.Socket
import java.time.Duration

/**
 * The server's internal counts, which the soak reads: once its clients are gone, by whatever means, every connection
 * is closed, every fiber but the acceptors has ended, and every receive buffer is back. A short form of the soak's
 * end-of-run check, on each engine with every `check`.
 */
class StatsTest {
    private val echo = HttpHandler { ex ->
        when (ex.request.path) {
            "/echo" -> ex.respond(200, body = ex.request.body.readAll(1L shl 20))
            "/stream" -> ex.stream(200).use { out -> repeat(PIECES) { out.write(ByteArray(PIECE)) } }
            else -> ex.respond(200, body = "ok".toByteArray())
        }
    }

    /** Waits for the counts to settle back to an idle server's, failing with the last ones if they do not. */
    private fun settles(server: GannetServer, carriers: Int) {
        val deadline = System.nanoTime() + Duration.ofSeconds(SETTLE_SECONDS).toNanos()
        var last: ServerStats
        while (true) {
            last = server.stats()
            val idle = last.open == 0 && last.sessions == 0 && last.zombies == 0 && last.handshakes == 0 &&
                last.freeBuffers == last.buffers && last.fibers == carriers
            if (idle || System.nanoTime() > deadline) break
            Thread.sleep(POLL_MILLIS)
        }
        last.open shouldBe 0
        last.sessions shouldBe 0
        last.zombies shouldBe 0
        last.handshakes shouldBe 0
        last.freeBuffers shouldBe last.buffers
        last.fibers shouldBe carriers
        // Each idle epoll carrier holds its acceptor's accept, its eventfd read and its tick; io_uring says -1.
        last.engineWaiting shouldBe if (server.engine == GannetEngine.EPOLL) carriers * IDLE_OPS else -carriers
    }

    @Test
    fun `an idle server holds one fiber per carrier and every buffer`() = serving(handler = echo) { server ->
        val stats = server.stats()
        stats.carriers.size shouldBe testConfig.carriers
        stats.fibers shouldBe testConfig.carriers
        stats.freeBuffers shouldBe testConfig.bufferCount * testConfig.carriers
        stats.open shouldBe 0
    }

    @Test
    fun `connections that end every which way give everything back`() = serving(handler = echo) { server ->
        // Kept alive and pipelined, then closed by the client.
        Raw(server.port).use { raw ->
            raw.send(get("/") + get("/") + get("/"))
            repeat(3) { raw.read().status shouldBe 200 }
        }
        // Closed by the server after Connection: close.
        Raw(server.port).use { it.send(get("/", "Connection: close")).read().status shouldBe 200 }
        // Gone mid-head, mid-body, and with a reset while a reply streams out.
        Socket("127.0.0.1", server.port).use { it.getOutputStream().write("GET / HTTP/1.1\r\nHo".toByteArray()) }
        Socket("127.0.0.1", server.port).use {
            val head = "POST /echo HTTP/1.1\r\nHost: t\r\nContent-Length: 9000\r\n\r\nabc"
            it.getOutputStream().write(head.toByteArray())
        }
        Socket("127.0.0.1", server.port).use {
            it.getOutputStream().write(get("/stream").toByteArray())
            it.getInputStream().readNBytes(PIECE)
            it.setSoLinger(true, 0)
        }
        // Refused as malformed.
        Raw(server.port).use { it.send("GET / HTTP/1.1\r\n\r\n").read().status shouldBe 400 }
        // A body larger than many receive buffers, echoed.
        Raw(server.port).use { raw ->
            val body = "x".repeat(BIG_BODY)
            raw.send("POST /echo HTTP/1.1\r\nHost: t\r\nContent-Length: ${body.length}\r\n\r\n$body")
            raw.read().body.size shouldBe BIG_BODY
        }
        settles(server, testConfig.carriers)
    }

    @Test
    fun `TLS connections, finished or abandoned mid-handshake, give everything back`() =
        serving(tlsConfig, echo) { server ->
            tlsRaw(server.port).use { it.send(get("/")).read().status shouldBe 200 }
            Socket("127.0.0.1", server.port).use { it.getOutputStream().write(ByteArray(JUNK) { 0x16 }) }
            Socket("127.0.0.1", server.port).close()
            settles(server, tlsConfig.carriers)
        }

    private companion object {
        const val SETTLE_SECONDS = 20L
        const val POLL_MILLIS = 50L
        const val PIECES = 64
        const val PIECE = 16 * 1024
        const val BIG_BODY = 200_000
        const val JUNK = 300
        const val IDLE_OPS = 3
    }
}
