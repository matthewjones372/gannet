package io.github.matthewjones372.gannet

import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.ints.shouldBeInRange
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldMatch
import org.junit.jupiter.api.Test
import kotlin.random.Random

/** What a handler is told about the request: method, raw target, path, query and fields, one line each. */
private val echo = HttpHandler { ex ->
    val r = ex.request
    val text = buildString {
        appendLine("${r.method} ${r.target} ${r.version}")
        appendLine("path=${r.path}")
        appendLine("query=${r.query}")
        r.headers.fields.forEach { appendLine("${it.first}=${it.second}") }
    }
    ex.respond(200, listOf("Content-Type" to "text/plain"), text.toByteArray())
}

class RequestsTest {
    @Test
    fun `a request sent a byte at a time is answered`() = serving(handler = echo) { server ->
        Raw(server.port).use { raw ->
            get("/slow?x=1", "X-Thing: yes").forEach {
                raw.send(it.toString())
                Thread.sleep(1)
            }
            val reply = raw.read()
            reply.status shouldBe 200
            reply.text.lines().take(3) shouldContainExactly listOf("GET /slow?x=1 HTTP_1_1", "path=/slow", "query=x=1")
        }
    }

    @Test
    fun `pipelined requests are answered in order on one connection`() = serving(handler = echo) { server ->
        Raw(server.port).use { raw ->
            raw.send((1..50).joinToString("") { get("/p/$it") })
            (1..50).forEach { raw.read().text.lines()[1] shouldBe "path=/p/$it" }
        }
    }

    @Test
    fun `a pipelined batch split mid-request is answered whole`() = serving(handler = echo) { server ->
        Raw(server.port).use { raw ->
            val batch = (1..20).joinToString("") { get("/b/$it", "X-Pad: " + "z".repeat(it * 37)) }
            batch.chunked(97).forEach { raw.send(it) }
            (1..20).forEach { raw.read().text.lines()[1] shouldBe "path=/b/$it" }
        }
    }

    @Test
    fun `the raw path reaches the handler with its escapes`() = serving(handler = echo) { server ->
        Raw(server.port).use { raw ->
            val text = raw.send(get("/files/a%2Fb/%2E%2E/c%25?q=a%26b")).read().text.lines()
            text[1] shouldBe "path=/files/a%2Fb/%2E%2E/c%25"
            text[2] shouldBe "query=q=a%26b"
        }
    }

    @Test
    fun `empty lines before a request are skipped`() = serving(handler = echo) { server ->
        Raw(server.port).use { raw ->
            raw.send("\r\n\r\n" + get("/after")).read().text.lines()[1] shouldBe "path=/after"
        }
    }

    @Test
    fun `every reply carries Date and Content-Length`() = serving(handler = echo) { server ->
        Raw(server.port).use { raw ->
            val reply = raw.send(get("/")).read()
            reply["Date"].shouldNotBeNull() shouldMatch Regex("""\w{3}, \d{1,2} \w{3} \d{4} \d{2}:\d{2}:\d{2} GMT""")
            reply["Content-Length"] shouldBe reply.body.size.toString()
        }
    }

    @Test
    fun `a connection is kept alive by default and closed when the request says close`() =
        serving(handler = echo) { server ->
            Raw(server.port).use { raw ->
                raw.send(get("/1")).read()["Connection"].shouldBeNull()
                raw.send(get("/2")).read().status shouldBe 200
                raw.send(get("/3", "Connection: close")).read()["Connection"] shouldBe "close"
                raw.closedByServer() shouldBe true
            }
        }

    @Test
    fun `HTTP 1 0 closes after the reply unless it asks to keep alive`() = serving(handler = echo) { server ->
        Raw(server.port).use { raw ->
            val reply = raw.send("GET /old HTTP/1.0\r\nConnection: keep-alive\r\n\r\n").read()
            reply["Connection"] shouldBe "keep-alive"
            reply.text.lines()[0] shouldBe "GET /old HTTP_1_0"
            raw.send("GET /old HTTP/1.0\r\n\r\n").read()["Connection"] shouldBe "close"
            raw.closedByServer() shouldBe true
        }
    }

    @Test
    fun `HEAD gets the fields of the reply and no body`() = serving(handler = echo) { server ->
        Raw(server.port).use { raw ->
            val reply = raw.send("HEAD /h HTTP/1.1\r\nHost: x\r\n\r\n").read(head = true)
            reply.status shouldBe 200
            reply["Content-Length"]!!.toInt() shouldBeInRange 10..1000
            // Were a body sent, it would be read here as the next reply's status line.
            raw.send(get("/next")).read().text.lines()[1] shouldBe "path=/next"
        }
    }

    @Test
    fun `a request line past the limit is 414 and closes`() =
        serving(testConfig.copy(maxRequestLineBytes = 100), echo) { server ->
            Raw(server.port).use { raw ->
                val reply = raw.send(get("/" + "a".repeat(200))).read()
                reply.status shouldBe 414
                reply["Connection"] shouldBe "close"
                raw.closedByServer() shouldBe true
            }
        }

    @Test
    fun `header fields past the limit are 431`() =
        serving(testConfig.copy(maxRequestLineBytes = 512, maxHeaderBytes = 1024), echo) { server ->
            Raw(server.port).use { raw ->
                raw.send(get("/", *Array(40) { "X-Field-$it: " + "v".repeat(30) })).read().status shouldBe 431
            }
        }

    @Test
    fun `a head larger than the lent buffer is read whole`() =
        serving(testConfig.copy(maxHeaderBytes = 64 * 1024), echo) { server ->
            Raw(server.port).use { raw ->
                val reply = raw.send(get("/big", "X-Big: " + "q".repeat(40_000))).read()
                reply.status shouldBe 200
                reply.text.lines().first { it.startsWith("X-Big=") }.length shouldBe 40_006
            }
        }

    @Test
    fun `an unsupported version is 505`() = serving(handler = echo) { server ->
        Raw(server.port).use { raw -> raw.send("GET / HTTP/2.0\r\nHost: x\r\n\r\n").read().status shouldBe 505 }
    }

    @Test
    fun `200 generated bad request lines are each answered with a 4xx`() = serving(handler = echo) { server ->
        val random = Random(87)
        val lines = List(200) { badLine(random) }
        for (line in lines) {
            Raw(server.port).use { raw ->
                val reply = raw.send(line + "\r\nHost: x\r\n\r\n").read()
                val shown = line.map { if (it.code < 32) "\\x%02x".format(it.code) else "$it" }.joinToString("")
                withClue("request line $shown") {
                    reply.status shouldBeInRange 400..499
                }
            }
        }
    }

    /** A request line broken in one of the ways a parser has to notice. */
    private fun badLine(random: Random): String {
        val good = "GET /path?q=1 HTTP/1.1"
        val controls = (0..31).map { it.toChar() }.filter { it != '\r' && it != '\n' }
        val junk = controls + listOf(' ', '"', '(', ')', '\u007f')
        return when (random.nextInt(6)) {
            0 -> good.replaceRange(0, 1, junk.random(random).toString())

            // a bad method character
            1 -> good.replace("/path", "/pa${(junk - listOf(' ', '(', ')')).random(random)}th")

            // not allowed in a URI
            2 -> good.replace("HTTP/1.1", listOf("HTTP/1.", "HTTP/11", "HTTX/1.1", "HTTP/1.1 ", "").random(random))

            3 -> good.replace(" ", listOf("  ", "\t").random(random))

            4 -> good.take(random.nextInt(1, good.length - 1)).trimEnd() + " x"

            else -> good.replace("/path", listOf("path", "*", "#f", "/a#b").random(random))
        }
    }
}
