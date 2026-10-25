package io.github.matthewjones372.gannet

import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** Reads the body whole within 64 bytes, and answers with its length. */
private val reading = HttpHandler { ex ->
    val body = ex.request.body.readAll(64)
    ex.respond(200, listOf("X-Length" to body.size.toString()), body)
}

/**
 * Malformed and smuggling requests over a real socket, each followed by a well-formed request on the same connection.
 * A refused request is answered with its status and `Connection: close`, and the request behind it is never read:
 * reading on would mean guessing where it starts, which is what request smuggling exploits.
 */
@RequiresUring
class SmugglingTest {
    private val config = testConfig.copy(maxRequestLineBytes = 256, maxHeaderBytes = 1024, maxHeaderCount = 20)

    private val cases = listOf(
        "Content-Length and Transfer-Encoding" to
            post("Content-Length: 6", "Transfer-Encoding: chunked") + "0\r\n\r\nG" to 400,
        "differing Content-Lengths" to post("Content-Length: 1", "Content-Length: 2") + "ab" to 400,
        "a signed Content-Length" to post("Content-Length: +1") + "a" to 400,
        "chunked not last" to post("Transfer-Encoding: chunked, identity") + "0\r\n\r\n" to 400,
        "an unknown coding before chunked" to post("Transfer-Encoding: gzip, chunked") + "0\r\n\r\n" to 501,
        "a Kelvin sign in chunked" to post("Transfer-Encoding: chunKed") + "0\r\n\r\n" to 400,
        "chunked on HTTP/1.0" to "POST / HTTP/1.0\r\nTransfer-Encoding: chunked\r\n\r\n0\r\n\r\n" to 400,
        "a chunk size past a long" to chunked("FFFFFFFFFFFFFFFFFF\r\na\r\n0\r\n\r\n") to 400,
        "a bare LF after a chunk size" to chunked("1\na\r\n0\r\n\r\n") to 400,
        "a bare LF after chunk data" to chunked("1\r\na\n0\r\n\r\n") to 400,
        "a bare CR in a chunk extension" to chunked("1;a\rb\r\na\r\n0\r\n\r\n") to 400,
        "a malformed trailer" to chunked("1\r\na\r\n0\r\nX : y\r\n\r\n") to 400,
        "obs-fold" to post("X: a", " b", "Content-Length: 0") to 400,
        "a space before the colon" to post("Content-Length : 0") to 400,
        "NUL in a value" to post("X: a\u0000b", "Content-Length: 0") to 400,
        "no Host" to "GET / HTTP/1.1\r\n\r\n" to 400,
        "two Hosts" to "GET / HTTP/1.1\r\nHost: a\r\nHost: b\r\n\r\n" to 400,
        "a bad Host" to "GET / HTTP/1.1\r\nHost: a b\r\n\r\n" to 400,
        "absolute-form without a host" to "GET http:///a HTTP/1.1\r\nHost: x\r\n\r\n" to 400,
        "asterisk-form for GET" to "GET * HTTP/1.1\r\nHost: x\r\n\r\n" to 400,
        "a request line past the limit" to "GET /${"a".repeat(300)} HTTP/1.1\r\nHost: x\r\n\r\n" to 414,
        "a header line past the limit" to "GET / HTTP/1.1\r\nHost: x\r\nX: ${"a".repeat(1100)}\r\n\r\n" to 431,
        "too many header fields" to "GET / HTTP/1.1\r\nHost: x\r\n" + "X: y\r\n".repeat(20) + "\r\n" to 431,
        "HTTP/2.0" to "GET / HTTP/2.0\r\nHost: x\r\n\r\n" to 505,
    )

    @Test
    fun `each malformed request is refused with its status, and the request behind it is never read`() =
        serving(config, reading) { server ->
            for ((case, status) in cases) {
                val (name, request) = case
                withClue(name) {
                    Raw(server.port).use { raw ->
                        val reply = raw.send(request + NEXT).read()
                        reply.status shouldBe status
                        reply["Connection"] shouldBe "close"
                        raw.closedByServer() shouldBe true
                    }
                }
            }
        }

    @Test
    fun `a body past the limit is 413, and the connection carries on once it is drained`() =
        serving(config, reading) { server ->
            Raw(server.port).use { raw ->
                val chunks = "40\r\n${"a".repeat(64)}\r\n10\r\n${"b".repeat(16)}\r\n0\r\n\r\n"
                raw.send(post("Transfer-Encoding: chunked") + chunks + NEXT)
                raw.read().status shouldBe 413
                raw.read()["X-Length"] shouldBe "0"
            }
        }

    @Test
    fun `well-formed requests in the same shapes are served, and keep the connection`() =
        serving(config, reading) { server ->
            Raw(server.port).use { raw ->
                raw.send(post("Content-Length: 5, 5") + "hello" + NEXT)
                raw.read()["X-Length"] shouldBe "5"
                raw.read()["X-Length"] shouldBe "0"
                raw.send(post("Transfer-Encoding: CHUNKED") + "3;ext=\"v\"\r\nabc\r\n0\r\nT: 1\r\n\r\n")
                raw.read()["X-Length"] shouldBe "3"
                raw.send("GET http://example.com/a HTTP/1.1\r\nHost: example.com\r\n\r\n").read().status shouldBe 200
                raw.send("OPTIONS * HTTP/1.1\r\nHost: [::1]:8080\r\n\r\n").read().status shouldBe 200
                raw.send("GET / HTTP/1.1\nHost: x\n\n").read().status shouldBe 200
            }
        }

    private fun chunked(body: String) = post("Transfer-Encoding: chunked") + body

    private fun post(vararg fields: String) =
        "POST / HTTP/1.1\r\nHost: x\r\n" + fields.joinToString("") { "$it\r\n" } + "\r\n"

    private companion object {
        /** A request that must never be answered when it follows a refused one. */
        const val NEXT = "GET /next HTTP/1.1\r\nHost: x\r\nContent-Length: 0\r\n\r\n"
    }
}
