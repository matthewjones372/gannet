package io.github.matthewjones372.gannet

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.Executors

/** Answers with the body's length and its bytes, read whole within [limit] on the carrier. */
private fun lengthOf(limit: Long = Long.MAX_VALUE) = HttpHandler { ex ->
    val body = ex.request.body.readAll(limit)
    ex.respond(200, listOf("X-Length" to body.size.toString(), "X-Declared" to "${ex.request.body.length}"), body)
}

/** Answers without reading the body at all. */
private val ignoring = HttpHandler { ex -> ex.respond(200, body = "ignored".toByteArray()) }

private fun post(path: String, body: String, vararg headers: String): String =
    "POST $path HTTP/1.1\r\nHost: x\r\nContent-Length: ${body.toByteArray().size}\r\n" +
        headers.joinToString("") { "$it\r\n" } + "\r\n" + body

private fun chunkedPost(path: String, vararg chunks: String, trailer: String = ""): String =
    "POST $path HTTP/1.1\r\nHost: x\r\nTransfer-Encoding: chunked\r\n\r\n" +
        chunks.joinToString("") { "${Integer.toHexString(it.length)}\r\n$it\r\n" } + "0\r\n$trailer\r\n"

class BodiesTest {
    @Test
    fun `a Content-Length body is read whole`() = serving(handler = lengthOf()) { server ->
        Raw(server.port).use { raw ->
            val reply = raw.send(post("/", "hello, body")).read()
            reply.text shouldBe "hello, body"
            reply["X-Declared"] shouldBe "11"
        }
    }

    @Test
    fun `a body larger than every buffer is read whole`() = serving(handler = lengthOf()) { server ->
        val big = (0 until 3_000_000).map { 'a' + it % 26 }.joinToString("")
        Raw(server.port).use { raw ->
            val reply = raw.send(post("/", big)).read()
            reply["X-Length"] shouldBe "3000000"
            reply.text shouldBe big
        }
    }

    @Test
    fun `a chunked body is decoded, with extensions and trailers`() = serving(handler = lengthOf()) { server ->
        Raw(server.port).use { raw ->
            val request = "POST / HTTP/1.1\r\nHost: x\r\nTransfer-Encoding: chunked\r\n\r\n" +
                "5;name=value\r\nhello\r\n1\r\n,\r\n6\r\n world\r\n0\r\nX-Trailer: t\r\n\r\n"
            val reply = raw.send(request).read()
            reply.text shouldBe "hello, world"
            reply["X-Declared"] shouldBe "null"
            raw.send(chunkedPost("/", "again")).read().text shouldBe "again"
        }
    }

    @Test
    fun `a chunked body sent a byte at a time is decoded`() = serving(handler = lengthOf()) { server ->
        Raw(server.port).use { raw ->
            chunkedPost("/", "abc", "defgh", trailer = "T: 1\r\n").forEach { raw.send(it.toString()) }
            raw.read().text shouldBe "abcdefgh"
        }
    }

    @Test
    fun `a bad chunk size is a 400 that closes`() = serving(handler = lengthOf()) { server ->
        Raw(server.port).use { raw ->
            val reply = raw.send("POST / HTTP/1.1\r\nHost: x\r\nTransfer-Encoding: chunked\r\n\r\nzz\r\n").read()
            reply.status shouldBe 400
            reply["Connection"] shouldBe "close"
            raw.closedByServer() shouldBe true
        }
    }

    @Test
    fun `a chunk without its CR LF is a 400`() = serving(handler = lengthOf()) { server ->
        Raw(server.port).use { raw ->
            raw.send("POST / HTTP/1.1\r\nHost: x\r\nTransfer-Encoding: chunked\r\n\r\n3\r\nabcX\r\n0\r\n\r\n")
                .read().status shouldBe 400
        }
    }

    @Test
    fun `a body over the limit is a 413, and a refused body is drained so the connection lives`() =
        serving(handler = lengthOf(limit = 10)) { server ->
            Raw(server.port).use { raw ->
                raw.send(post("/", "x".repeat(1000))).read().status shouldBe 413
                raw.send(post("/", "small")).read().text shouldBe "small"
                raw.send(chunkedPost("/", "x".repeat(8), "y".repeat(8))).read().status shouldBe 413
                raw.send(post("/", "again")).read().text shouldBe "again"
            }
        }

    @Test
    fun `a declared length no array can hold is refused before reading`() = serving(handler = lengthOf()) { server ->
        Raw(server.port).use { raw ->
            val reply = raw.send("POST / HTTP/1.1\r\nHost: x\r\nContent-Length: 5000000000\r\n\r\n").read()
            reply.status shouldBe 413
            reply["Connection"] shouldBe "close"
        }
    }

    @Test
    fun `a refused body larger than the drain closes the connection`() =
        serving(testConfig.copy(drainBytes = 100), lengthOf(limit = 10)) { server ->
            Raw(server.port).use { raw ->
                val body = "x".repeat(5000)
                val reply = raw.send("POST / HTTP/1.1\r\nHost: x\r\nContent-Length: ${body.length}\r\n\r\n").read()
                reply.status shouldBe 413
                reply["Connection"] shouldBe "close"
            }
        }

    @Test
    fun `an unread body is drained after the reply and the next request is answered`() =
        serving(handler = ignoring) { server ->
            Raw(server.port).use { raw ->
                raw.send(post("/", "unread body") + post("/", "and another"))
                raw.read().text shouldBe "ignored"
                raw.read().text shouldBe "ignored"
                raw.send(chunkedPost("/", "chunks", "too") + get("/")).read().text shouldBe "ignored"
                raw.read().text shouldBe "ignored"
            }
        }

    @Test
    fun `an unread chunked body longer than the drain closes the connection after the reply`() =
        serving(testConfig.copy(drainBytes = 10), ignoring) { server ->
            Raw(server.port).use { raw ->
                raw.send(chunkedPost("/", "x".repeat(100))).read().status shouldBe 200
                raw.closedByServer() shouldBe true
            }
        }

    @Test
    fun `100 Continue is sent when the handler reads the body`() = serving(handler = lengthOf()) { server ->
        Raw(server.port).use { raw ->
            raw.send("POST / HTTP/1.1\r\nHost: x\r\nContent-Length: 4\r\nExpect: 100-continue\r\n\r\n")
            raw.read().status shouldBe 100
            raw.send("body").read().text shouldBe "body"
        }
    }

    @Test
    fun `no 100 Continue is sent for a body refused unread, and the connection closes`() =
        serving(handler = lengthOf(limit = 3)) { server ->
            Raw(server.port).use { raw ->
                val request = "POST / HTTP/1.1\r\nHost: x\r\nContent-Length: 4\r\nExpect: 100-continue\r\n\r\n"
                val reply = raw.send(request).read()
                reply.status shouldBe 413
                reply["Connection"] shouldBe "close"
                raw.closedByServer() shouldBe true
            }
        }

    @Test
    fun `a body read in pieces on the carrier comes whole`() = serving(
        handler = { ex ->
            val piece = ByteArray(7)
            val all = generateSequence { ex.request.body.read(piece, 0, piece.size).takeIf { it >= 0 } }
                .map { String(piece, 0, it) }.joinToString("|")
            ex.respond(200, body = all.toByteArray())
        },
    ) { server ->
        Raw(server.port).use { raw ->
            raw.send(post("/", "abcdefghijklmnop")).read().text shouldBe "abcdefg|hijklmn|op"
        }
    }

    @Test
    fun `a connection that ends inside a body breaks it`() {
        val failures = java.util.concurrent.LinkedBlockingQueue<Throwable>()
        serving(
            handler = { ex ->
                try {
                    ex.request.body.readAll()
                } catch (e: MalformedBodyException) {
                    failures.add(e)
                }
                ex.respond(200)
            },
        ) { server ->
            Raw(server.port).use { raw -> raw.send("POST / HTTP/1.1\r\nHost: x\r\nContent-Length: 100\r\n\r\nshort") }
            (failures.poll(10, java.util.concurrent.TimeUnit.SECONDS) is MalformedBodyException) shouldBe true
        }
    }

    @Test
    fun `a body read on a virtual thread comes whole and the carrier keeps serving`() {
        val threads = Executors.newVirtualThreadPerTaskExecutor()
        serving(
            handler = { ex ->
                ex.offload(threads) {
                    val body = it.request.body.inputStream().readAllBytes()
                    it.respond(200, body = body)
                }
            },
        ) { server ->
            Raw(server.port).use { raw ->
                val big = "v".repeat(200_000)
                raw.send(post("/", big)).read().text shouldBe big
                raw.send(chunkedPost("/", "a", "bc")).read().text shouldBe "abc"
            }
        }
        threads.close()
    }

    @Test
    fun `readAll on a virtual thread honours its limit and the configured one`() {
        val threads = Executors.newVirtualThreadPerTaskExecutor()
        serving(
            testConfig.copy(maxBodyBytes = 20),
            { ex -> ex.offload(threads) { it.respond(200, body = it.request.body.readAll()) } },
        ) { server ->
            Raw(server.port).use { raw ->
                raw.send(post("/", "under")).read().text shouldBe "under"
                raw.send(post("/", "x".repeat(30))).read().status shouldBe 413
                raw.send(chunkedPost("/", "y".repeat(15), "y".repeat(15))).read().status shouldBe 413
            }
        }
        threads.close()
    }

    @Test
    fun `a timed read on a virtual thread gives up and fails the body`() {
        val threads = Executors.newVirtualThreadPerTaskExecutor()
        val seen = java.util.concurrent.LinkedBlockingQueue<String>()
        serving(
            handler = { ex ->
                ex.offload(threads) {
                    val buffer = ByteArray(10)
                    val first = it.request.body.read(buffer, 0, 10, Duration.ofSeconds(5))
                    seen.add("first=$first")
                    try {
                        it.request.body.read(buffer, 0, 10, Duration.ofMillis(200))
                    } catch (e: java.net.SocketTimeoutException) {
                        seen.add("timeout")
                    }
                    try {
                        it.request.body.read(buffer, 0, 10)
                    } catch (e: java.io.IOException) {
                        seen.add("failed")
                    }
                    it.respond(200)
                }
            },
        ) { server ->
            Raw(server.port).use { raw ->
                val reply = raw.send("POST / HTTP/1.1\r\nHost: x\r\nContent-Length: 10\r\n\r\nabc").read()
                reply.status shouldBe 200
                reply["Connection"] shouldBe "close"
            }
            listOf(seen.take(), seen.take(), seen.take()) shouldBe listOf("first=3", "timeout", "failed")
        }
        threads.close()
    }

    @Test
    fun `failing the body wakes a read blocked on another thread`() {
        val threads = Executors.newVirtualThreadPerTaskExecutor()
        val blocked = java.util.concurrent.CountDownLatch(1)
        val outcome = java.util.concurrent.LinkedBlockingQueue<String>()
        serving(
            handler = { ex ->
                ex.offload(threads) {
                    threads.execute {
                        blocked.await()
                        Thread.sleep(100)
                        it.request.body.fail(java.io.IOException("woken"))
                    }
                    blocked.countDown()
                    try {
                        it.request.body.read(ByteArray(10), 0, 10)
                    } catch (e: java.io.IOException) {
                        outcome.add(e.message!!)
                    }
                    it.respond(204)
                }
            },
        ) { server ->
            Raw(server.port).use { raw ->
                raw.send("POST / HTTP/1.1\r\nHost: x\r\nContent-Length: 10\r\n\r\n").read().status shouldBe 204
            }
            outcome.take() shouldBe "woken"
        }
        threads.close()
    }
}
