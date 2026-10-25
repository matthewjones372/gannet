package io.github.matthewjones372.gannet

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.concurrent.Executors

class RepliesTest {
    @Test
    fun `a large strict reply arrives whole`() {
        val big = ByteArray(5_000_000) { (it % 251).toByte() }
        serving(handler = { it.respond(200, body = big) }) { server ->
            Raw(server.port).use { raw ->
                raw.send(get("/")).read().body.contentEquals(big) shouldBe true
                raw.send(get("/")).read().body.size shouldBe big.size
            }
        }
    }

    @Test
    fun `the reply's own framing fields are replaced and its other fields kept`() = serving(
        handler = {
            it.respond(
                201,
                listOf(
                    "Content-Length" to "999", "Transfer-Encoding" to "gzip", "Date" to "yesterday",
                    "X-Kept" to "yes", "X-Kept" to "twice",
                ),
                "abc".toByteArray(),
            )
        },
    ) { server ->
        Raw(server.port).use { raw ->
            val reply = raw.send(get("/")).read()
            reply.status shouldBe 201
            reply.reason shouldBe "Created"
            reply["Content-Length"] shouldBe "3"
            reply["Transfer-Encoding"].shouldBeNull()
            (reply["Date"] == "yesterday") shouldBe false
            reply.headers.all("x-kept") shouldBe listOf("yes", "twice")
        }
    }

    @Test
    fun `204 and 304 carry no Content-Length or body`() = serving(
        handler = { ex -> ex.respond(if (ex.request.path == "/204") 204 else 304, body = "dropped".toByteArray()) },
    ) { server ->
        Raw(server.port).use { raw ->
            val noContent = raw.send(get("/204")).read()
            noContent.status shouldBe 204
            noContent["Content-Length"].shouldBeNull()
            raw.send(get("/304")).read()["Content-Length"].shouldBeNull()
            raw.send(get("/204")).read().status shouldBe 204
        }
    }

    @Test
    fun `an unknown status is written without a reason`() = serving(handler = { it.respond(299) }) { server ->
        Raw(server.port).use { raw ->
            val reply = raw.send(get("/")).read()
            reply.status shouldBe 299
            reply.reason shouldBe ""
        }
    }

    @Test
    fun `a HEAD reply keeps the Content-Length it is given when it has no body`() = serving(
        handler = { it.respond(200, listOf("Content-Length" to "1234")) },
    ) { server ->
        Raw(server.port).use { raw ->
            raw.send("HEAD / HTTP/1.1\r\nHost: x\r\n\r\n").read(head = true)["Content-Length"] shouldBe "1234"
            raw.send(get("/")).read()["Content-Length"] shouldBe "0"
        }
    }

    @Test
    fun `Connection close from the handler closes after the reply`() = serving(
        handler = { it.respond(200, listOf("Connection" to "close")) },
    ) { server ->
        Raw(server.port).use { raw ->
            raw.send(get("/")).read()["Connection"] shouldBe "close"
            raw.closedByServer() shouldBe true
        }
    }

    @Test
    fun `a streamed reply is chunked and ends with the last chunk`() = serving(
        handler = { ex ->
            ex.stream(200, listOf("Content-Type" to "text/plain")).use { out ->
                repeat(5) { out.write("piece $it\n".toByteArray()) }
                out.write(ByteArray(0))
            }
        },
    ) { server ->
        Raw(server.port).use { raw ->
            val reply = raw.send(get("/")).read()
            reply["Transfer-Encoding"] shouldBe "chunked"
            reply.text shouldBe (0 until 5).joinToString("") { "piece $it\n" }
            raw.send(get("/")).read().status shouldBe 200
        }
    }

    @Test
    fun `a stream left open when the handler returns is ended for it`() = serving(
        handler = { ex -> ex.stream(200).write("unclosed".toByteArray()) },
    ) { server ->
        Raw(server.port).use { raw -> raw.send(get("/")).read().text shouldBe "unclosed" }
    }

    @Test
    fun `a streamed reply to HTTP 1 0 is delimited by closing the connection`() = serving(
        handler = { ex -> ex.stream(200).use { it.write("old ".toByteArray()); it.write("client".toByteArray()) } },
    ) { server ->
        Raw(server.port).use { raw ->
            val reply = raw.send("GET / HTTP/1.0\r\nConnection: keep-alive\r\n\r\n").read()
            reply["Transfer-Encoding"].shouldBeNull()
            reply["Connection"] shouldBe "close"
            reply.text shouldBe "old client"
        }
    }

    @Test
    fun `a streamed reply to HEAD sends the head and nothing else`() = serving(
        handler = { ex -> ex.stream(200).use { it.write("never sent".toByteArray()) } },
    ) { server ->
        Raw(server.port).use { raw ->
            val reply = raw.send("HEAD / HTTP/1.1\r\nHost: x\r\n\r\n").read(head = true)
            reply.status shouldBe 200
            reply["Transfer-Encoding"].shouldBeNull()
            raw.send(get("/")).read().text shouldBe "never sent"
        }
    }

    @Test
    fun `an aborted stream is left unterminated and the connection closes`() = serving(
        handler = { ex -> ex.stream(200).let { it.write("partial".toByteArray()); it.abort() } },
    ) { server ->
        Raw(server.port).use { raw ->
            raw.send(get("/"))
            val head = generateSequence { line(raw.socket.getInputStream()) }.takeWhile { it.isNotEmpty() }.toList()
            head.first() shouldBe "HTTP/1.1 200 OK"
            val rest = raw.socket.getInputStream().readAllBytes().toString(Charsets.US_ASCII)
            rest shouldBe "7\r\npartial\r\n"
        }
    }

    @Test
    fun `a handler that throws before answering gets a 500, and the connection lives`() = serving(
        handler = { ex -> if (ex.request.path == "/boom") error("boom") else ex.respond(200) },
    ) { server ->
        Raw(server.port).use { raw ->
            raw.send(get("/boom")).read().status shouldBe 500
            raw.send(get("/fine")).read().status shouldBe 200
        }
    }

    @Test
    fun `a handler that throws mid-stream cuts the reply short`() = serving(
        handler = { ex -> ex.stream(200).write("before".toByteArray()).also { error("mid-stream") } },
    ) { server ->
        Raw(server.port).use { raw ->
            raw.send(get("/"))
            val input = raw.socket.getInputStream()
            generateSequence { line(input) }.takeWhile { it.isNotEmpty() }.toList()
            input.readAllBytes().toString(Charsets.US_ASCII) shouldBe "6\r\nbefore\r\n"
        }
    }

    @Test
    fun `a handler that returns without answering gets a 500`() = serving(handler = {}) { server ->
        Raw(server.port).use { raw -> raw.send(get("/")).read().status shouldBe 500 }
    }

    @Test
    fun `answering twice, a bad status or a bad header is refused to the handler`() {
        val refusals = java.util.concurrent.LinkedBlockingQueue<String>()
        serving(
            handler = { ex ->
                shouldThrow<IllegalArgumentException> { ex.respond(199) }
                shouldThrow<IllegalArgumentException> { ex.respond(200, listOf("X-Bad" to "a\r\nInjected: yes")) }
                shouldThrow<IllegalArgumentException> { ex.respond(200, listOf("Bad Name" to "v")) }
                ex.respond(200)
                refusals.add(shouldThrow<IllegalStateException> { ex.respond(200) }.message!!)
                shouldThrow<IllegalStateException> { ex.stream(200) }
            },
        ) { server ->
            Raw(server.port).use { raw -> raw.send(get("/")).read().status shouldBe 200 }
            refusals.take() shouldBe "This exchange has already been answered"
        }
    }

    @Test
    fun `writing to a closed stream is refused`() {
        val threads = Executors.newVirtualThreadPerTaskExecutor()
        val refused = java.util.concurrent.LinkedBlockingQueue<Boolean>()
        serving(
            handler = { ex ->
                if (ex.request.path == "/carrier") {
                    val stream = ex.stream(200)
                    stream.close()
                    refused.add(shouldThrow<IllegalStateException> { stream.write(byteArrayOf(1)) }.message != null)
                } else {
                    ex.offload(threads) {
                        val stream = it.stream(200)
                        stream.close()
                        refused.add(shouldThrow<IllegalStateException> { stream.write(byteArrayOf(1)) }.message != null)
                    }
                }
            },
        ) { server ->
            Raw(server.port).use { raw ->
                raw.send(get("/carrier")).read().status shouldBe 200
                raw.send(get("/thread")).read().status shouldBe 200
            }
            refused.take() shouldBe true
            refused.take() shouldBe true
        }
        threads.close()
    }
}
