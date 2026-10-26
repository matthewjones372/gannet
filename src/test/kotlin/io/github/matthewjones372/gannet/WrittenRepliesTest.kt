package io.github.matthewjones372.gannet

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue

/** A reply whose body a writer puts into the carrier's own buffer. */
class WrittenRepliesTest {
    private val threads = Executors.newVirtualThreadPerTaskExecutor()

    @AfterEach
    fun close() = threads.close()

    private fun HttpExchange.writeText(text: String) =
        respond(200, listOf("Content-Type" to "text/plain")) { it.write(text.toByteArray()) }

    @Test
    fun `a written body arrives with its length, from the carrier and from another thread`() = serving(
        handler = { ex ->
            if (ex.request.path == "/carrier") ex.writeText("from the carrier")
            else ex.offload(threads) { it.writeText("from ${Thread.currentThread().isVirtual}") }
        },
    ) { server ->
        Raw(server.port).use { raw ->
            val onCarrier = raw.send(get("/carrier")).read()
            onCarrier.text shouldBe "from the carrier"
            onCarrier["Content-Length"] shouldBe "16"
            onCarrier["Content-Type"] shouldBe "text/plain"
            raw.send(get("/away")).read().text shouldBe "from true"
        }
    }

    @Test
    fun `pipelined written replies stay whole and in order`() = serving(
        handler = { ex -> ex.writeText("reply to ${ex.request.path}") },
    ) { server ->
        Raw(server.port).use { raw ->
            raw.send(get("/a") + get("/b") + get("/c"))
            listOf(raw.read().text, raw.read().text, raw.read().text) shouldBe
                listOf("reply to /a", "reply to /b", "reply to /c")
        }
    }

    @Test
    fun `a body larger than any buffer is written whole, and a small one after it`() {
        val big = ByteArray(5_000_000) { (it % 251).toByte() }
        serving(
            handler = { ex ->
                if (ex.request.path == "/big") {
                    ex.respond(200, emptyList()) { out ->
                        for (at in big.indices step PIECE) out.write(big, at, minOf(PIECE, big.size - at))
                    }
                } else {
                    ex.writeText("small")
                }
            },
        ) { server ->
            Raw(server.port).use { raw ->
                raw.send(get("/big")).read().body.contentEquals(big) shouldBe true
                raw.send(get("/small")).read().text shouldBe "small"
            }
        }
    }

    @Test
    fun `a HEAD reply has the written body's length and no body`() = serving(
        handler = { ex -> ex.writeText("twelve bytes") },
    ) { server ->
        Raw(server.port).use { raw ->
            raw.send("HEAD / HTTP/1.1\r\nHost: x\r\n\r\n").read(head = true)["Content-Length"] shouldBe "12"
            raw.send(get("/")).read().text shouldBe "twelve bytes"
        }
    }

    @Test
    fun `a writer that throws on the carrier leaves the exchange for the caller to answer`() {
        val caught = LinkedBlockingQueue<String>()
        serving(
            handler = { ex ->
                try {
                    ex.respond(200, emptyList()) { out ->
                        out.write("half".toByteArray())
                        error("the codec broke")
                    }
                } catch (e: IllegalStateException) {
                    caught.add(e.message!!)
                    ex.respond(500, body = "answered by the caller".toByteArray())
                }
            },
        ) { server ->
            Raw(server.port).use { raw ->
                val reply = raw.send(get("/")).read()
                reply.status shouldBe 500
                reply.text shouldBe "answered by the caller"
                raw.send(get("/")).read().status shouldBe 500
            }
            caught.take() shouldBe "the codec broke"
        }
    }

    @Test
    fun `a writer that throws on the carrier, left to the handler, is a 500 and the connection lives`() = serving(
        handler = { ex ->
            if (ex.request.path == "/boom") ex.respond(200, emptyList()) { error("boom") } else ex.writeText("fine")
        },
    ) { server ->
        Raw(server.port).use { raw ->
            raw.send(get("/boom")).read().status shouldBe 500
            raw.send(get("/fine")).read().text shouldBe "fine"
        }
    }

    @Test
    fun `a writer that throws after the exchange was answered from another thread closes the connection`() = serving(
        handler = { ex ->
            if (ex.request.path == "/boom") {
                ex.offload(threads) {
                    it.respond(200, emptyList()) { out ->
                        out.write("half".toByteArray())
                        error("the codec broke on the carrier")
                    }
                }
            } else {
                ex.writeText("fine")
            }
        },
    ) { server ->
        Raw(server.port).use { raw ->
            raw.send(get("/boom"))
            raw.closedByServer() shouldBe true
        }
        Raw(server.port).use { raw -> raw.send(get("/fine")).read().text shouldBe "fine" }
    }

    private companion object {
        const val PIECE = 7_919
    }
}
