package io.github.matthewjones372.gannet.runtime

import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory

/**
 * RFC 9112's framing and request-smuggling rules, case by case: what the head parser makes of each request, and what
 * the chunked decoder makes of each body. Every refusal closes the connection, which the socket tests check.
 */
class ConformanceTest {
    /** A head and what it must come to: a status it is refused with, or how its body is framed. */
    private class Case(val name: String, val head: String, val expected: String)

    private fun case(name: String, head: String, expected: Any) = Case(name, head, expected.toString())

    private fun outcome(head: String, maxLine: Int = 8192, maxHead: Int = 16384, maxFields: Int = 100): String {
        val bytes = head.toByteArray(Charsets.UTF_8)
        return when (val parsed = parseHead(bytes, 0, bytes.size, maxLine, maxHead, maxFields)) {
            is Parse.Refused -> parsed.status.toString()

            is Parse.Complete -> when (val f = parsed.head.framing) {
                Framing.None -> "none"
                Framing.Chunked -> "chunked"
                is Framing.Fixed -> "fixed ${f.length}"
            }

            Parse.Incomplete -> "incomplete"
        }
    }

    private fun post(vararg fields: String) =
        "POST / HTTP/1.1\r\nHost: x\r\n" + fields.joinToString("") { "$it\r\n" } + "\r\n"

    private fun table(cases: List<Case>) = cases.map { c ->
        DynamicTest.dynamicTest(c.name) { withClue(c.head) { outcome(c.head) shouldBe c.expected } }
    }

    @TestFactory
    fun `Content-Length with Transfer-Encoding`() = table(
        listOf(
            case("length then chunked", post("Content-Length: 4", "Transfer-Encoding: chunked"), 400),
            case("chunked then length", post("Transfer-Encoding: chunked", "Content-Length: 4"), 400),
            case("zero length and chunked", post("Content-Length: 0", "Transfer-Encoding: chunked"), 400),
            case("both on 1.0", "POST / HTTP/1.0\r\nContent-Length: 4\r\nTransfer-Encoding: chunked\r\n\r\n", 400),
        ),
    )

    @TestFactory
    fun `Content-Length values`() = table(
        listOf(
            case("plain", post("Content-Length: 5"), "fixed 5"),
            case("the same twice", post("Content-Length: 5", "Content-Length: 5"), "fixed 5"),
            case("the same twice in a list", post("Content-Length: 5, 5"), "fixed 5"),
            case("two different", post("Content-Length: 5", "Content-Length: 6"), 400),
            case("two different in a list", post("Content-Length: 5, 6"), 400),
            case("the same spelled differently", post("Content-Length: 5", "Content-Length: 05"), 400),
            case("an empty list element", post("Content-Length: 5,"), 400),
            case("empty", post("Content-Length:"), 400),
            case("a plus sign", post("Content-Length: +5"), 400),
            case("a minus sign", post("Content-Length: -5"), 400),
            case("optional whitespace", post("Content-Length: \t5 "), "fixed 5"),
            case("a space inside", post("Content-Length: 5 5"), 400),
            case("leading zeros", post("Content-Length: 0005"), "fixed 5"),
            case("hexadecimal", post("Content-Length: 0x5"), 400),
            case("an exponent", post("Content-Length: 1e3"), 400),
            case("18 digits", post("Content-Length: 100000000000000000"), "fixed 100000000000000000"),
            case("19 digits", post("Content-Length: 1000000000000000000"), 400),
            case("past a long", post("Content-Length: 18446744073709551616"), 400),
            case("a non-breaking space after", post("Content-Length: 5\u00A0"), 400),
            case("an Arabic-Indic digit", post("Content-Length: \u0665"), 400),
            case("a fullwidth digit", post("Content-Length: \uFF15"), 400),
        ),
    )

    @TestFactory
    fun `Transfer-Encoding values`() = table(
        listOf(
            case("chunked", post("Transfer-Encoding: chunked"), "chunked"),
            case("upper case", post("Transfer-Encoding: CHUNKED"), "chunked"),
            case("optional whitespace", post("Transfer-Encoding: \tchunked "), "chunked"),
            case("an empty list element", post("Transfer-Encoding: , chunked,"), "chunked"),
            case("chunked not last", post("Transfer-Encoding: chunked, identity"), 400),
            case("chunked not last, across lines", post("Transfer-Encoding: chunked", "Transfer-Encoding: gzip"), 400),
            case("another coding first", post("Transfer-Encoding: identity, chunked"), 501),
            case("another first, across lines", post("Transfer-Encoding: gzip", "Transfer-Encoding: chunked"), 501),
            case("chunked twice", post("Transfer-Encoding: chunked", "Transfer-Encoding: chunked"), 501),
            case("xchunked", post("Transfer-Encoding: xchunked"), 400),
            case("chunked with a parameter", post("Transfer-Encoding: chunked;q=1"), 400),
            case("chunked inside a token", post("Transfer-Encoding: chunked-x"), 400),
            case("empty", post("Transfer-Encoding:"), 400),
            case("a Kelvin sign for the k", post("Transfer-Encoding: chun\u212Aed"), 400),
            case("a non-breaking space after", post("Transfer-Encoding: chunked\u00A0"), 400),
            case("a vertical tab before", post("Transfer-Encoding:\u000Bchunked"), 400),
            case("a space before the colon", post("Transfer-Encoding : chunked"), 400),
            case("a tab before the colon", post("Transfer-Encoding\t: chunked"), 400),
            case("folded onto a second line", post("Transfer-Encoding: identity", " chunked"), 400),
            case("an underscore in the name", post("Transfer_Encoding: chunked"), "none"),
            case("on HTTP/1.0", "POST / HTTP/1.0\r\nTransfer-Encoding: chunked\r\n\r\n", 400),
        ),
    )

    @TestFactory
    fun `line endings and field syntax`() = table(
        listOf(
            case("bare LF line endings", "GET / HTTP/1.1\nHost: x\n\n", "none"),
            case("a bare CR ending the request line", "GET / HTTP/1.1\rHost: x\r\n\r\n", 400),
            case("a bare CR in a value", post("X: a\rb"), 400),
            case("a bare CR before CR LF", post("X: a\r"), 400),
            case("a bare CR as a line of its own", "GET / HTTP/1.1\r\nHost: x\r\n\r\r\n\r\n", 400),
            case("obs-fold", post("X: a", " b"), 400),
            case("obs-fold with a tab", post("X: a", "\tb"), 400),
            case("whitespace before the first field", "GET / HTTP/1.1\r\n Host: x\r\n\r\n", 400),
            case("a space before the colon", post("Content-Length : 5"), 400),
            case("no colon", post("NoColon"), 400),
            case("no name", post(": value"), 400),
            case("NUL in a value", post("X: a\u0000b"), 400),
            case("NUL in a name", post("X\u0000Y: a"), 400),
            case("DEL in a value", post("X: a\u007Fb"), 400),
            case("non-ASCII in a name", post("X-\u00E9: a"), 400),
            case("non-ASCII in a value", post("X: caf\u00E9"), "none"),
            case("NUL in the target", "GET /a\u0000b HTTP/1.1\r\nHost: x\r\n\r\n", 400),
            case("non-ASCII in the method", "G\u00C9T / HTTP/1.1\r\nHost: x\r\n\r\n", 400),
            case("a tab between method and target", "GET\t/ HTTP/1.1\r\nHost: x\r\n\r\n", 400),
            case("two spaces between method and target", "GET  / HTTP/1.1\r\nHost: x\r\n\r\n", 400),
        ),
    )

    @TestFactory
    fun `Host`() = table(
        listOf(
            case("missing on 1.1", "GET / HTTP/1.1\r\n\r\n", 400),
            case("missing on 1.0", "GET / HTTP/1.0\r\n\r\n", "none"),
            case("twice", "GET / HTTP/1.1\r\nHost: a\r\nHost: a\r\n\r\n", 400),
            case("twice on 1.0", "GET / HTTP/1.0\r\nHost: a\r\nHost: b\r\n\r\n", 400),
            case("empty", "GET / HTTP/1.1\r\nHost:\r\n\r\n", "none"),
            case("a name and port", "GET / HTTP/1.1\r\nHost: example.com:8080\r\n\r\n", "none"),
            case("an IPv6 literal", "GET / HTTP/1.1\r\nHost: [::1]:8080\r\n\r\n", "none"),
            case("an empty port", "GET / HTTP/1.1\r\nHost: example.com:\r\n\r\n", "none"),
            case("a bad port", "GET / HTTP/1.1\r\nHost: example.com:80x\r\n\r\n", 400),
            case("a space inside", "GET / HTTP/1.1\r\nHost: a b\r\n\r\n", 400),
            case("a slash", "GET / HTTP/1.1\r\nHost: a/b\r\n\r\n", 400),
            case("userinfo", "GET / HTTP/1.1\r\nHost: user@a\r\n\r\n", 400),
            case("non-ASCII", "GET / HTTP/1.1\r\nHost: b\u00FCcher.de\r\n\r\n", 400),
            case("an unclosed IPv6 literal", "GET / HTTP/1.1\r\nHost: [::1\r\n\r\n", 400),
        ),
    )

    @TestFactory
    fun `request targets and versions`() = table(
        listOf(
            case("absolute-form", "GET http://example.com/a?b HTTP/1.1\r\nHost: example.com\r\n\r\n", "none"),
            case("absolute-form in capitals", "GET HTTPS://EXAMPLE.COM HTTP/1.1\r\nHost: x\r\n\r\n", "none"),
            case("absolute-form without a host", "GET http:///a HTTP/1.1\r\nHost: x\r\n\r\n", 400),
            case("absolute-form with userinfo", "GET http://user@example.com/ HTTP/1.1\r\nHost: x\r\n\r\n", 400),
            case("another scheme", "GET ftp://example.com/ HTTP/1.1\r\nHost: x\r\n\r\n", 400),
            case("asterisk-form for OPTIONS", "OPTIONS * HTTP/1.1\r\nHost: x\r\n\r\n", "none"),
            case("asterisk-form for GET", "GET * HTTP/1.1\r\nHost: x\r\n\r\n", 400),
            case("authority-form", "CONNECT example.com:443 HTTP/1.1\r\nHost: example.com:443\r\n\r\n", 400),
            case("a fragment", "GET /a#b HTTP/1.1\r\nHost: x\r\n\r\n", 400),
            case("HTTP/2.0", "GET / HTTP/2.0\r\nHost: x\r\n\r\n", 505),
            case("HTTP/0.9 style", "GET /\r\n\r\n", 400),
            case("a lower-case version", "GET / http/1.1\r\nHost: x\r\n\r\n", 400),
            case("a two-digit minor version", "GET / HTTP/1.10\r\nHost: x\r\n\r\n", 400),
        ),
    )

    @Test
    fun `over-long lines and too many fields are refused with the status for each`() {
        val fields = (1..100).map { "X-$it: v" }
        outcome(post(*fields.toTypedArray()), maxFields = 101) shouldBe "none"
        outcome(post(*fields.toTypedArray()), maxFields = 100) shouldBe "431"
        outcome("GET /" + "a".repeat(100) + " HTTP/1.1\r\nHost: x\r\n\r\n", maxLine = 64) shouldBe "414"
        outcome(post("X: " + "a".repeat(200)), maxHead = 128) shouldBe "431"
        outcome(post(*(1..40).map { "X-$it: v" }.toTypedArray()), maxHead = 128) shouldBe "431"
    }

    @Test
    fun `a limit near the largest int neither wraps around nor refuses`() {
        val head = "GET / HTTP/1.1\r\nHost: x\r\n\r\n".toByteArray()
        val padded = ByteArray(100) + head
        parseHead(padded, 100, padded.size, Int.MAX_VALUE, Int.MAX_VALUE, Int.MAX_VALUE)
            .shouldBeInstanceOf<Parse.Complete>().end shouldBe padded.size
        parseHead(padded, 100, padded.size - 2, Int.MAX_VALUE, Int.MAX_VALUE, Int.MAX_VALUE) shouldBe Parse.Incomplete
    }

    @Test
    fun `empty lines before a request are skipped, up to a limit`() {
        fun first(input: String) = MemoryInbound(input.toByteArray()).nextHead(8192, 16384, 100)
        first("\r\n".repeat(128) + "GET / HTTP/1.1\r\nHost: x\r\n\r\n").shouldBeInstanceOf<Parse.Complete>()
        first("\r\n".repeat(129) + "GET / HTTP/1.1\r\nHost: x\r\n\r\n").shouldBeInstanceOf<Parse.Refused>()
            .status shouldBe 400
        first("\r\n".repeat(10)) shouldBe null
    }

    // ------------------------------------------------------------------ chunked bodies

    private fun decode(body: String, limit: Long = Long.MAX_VALUE, maxTrailer: Int = 16384): String {
        val bytes = body.toByteArray(Charsets.ISO_8859_1)
        val inbound = MemoryInbound(bytes, maxHeaderBytes = maxTrailer)
        val decoded = readBody(inbound, Framing.Chunked, limit, intArrayOf(64))
        return decoded.failure ?: (decoded.body.toByteArray().toString(Charsets.ISO_8859_1) + "|${decoded.consumed}")
    }

    private fun chunks(cases: List<Pair<String, String>>) = cases.map { (body, expected) ->
        DynamicTest.dynamicTest(body.replace("\r", "\\r").replace("\n", "\\n")) { decode(body) shouldBe expected }
    }

    @TestFactory
    fun `chunk sizes`() = chunks(
        listOf(
            "5\r\nhello\r\n0\r\n\r\n" to "hello|15",
            "A\r\n0123456789\r\n0\r\n\r\n" to "0123456789|20",
            "a\r\n0123456789\r\n0\r\n\r\n" to "0123456789|20",
            "0005\r\nhello\r\n000\r\n\r\n" to "hello|20",
            "\r\nhello\r\n0\r\n\r\n" to "malformed",
            "+5\r\nhello\r\n0\r\n\r\n" to "malformed",
            "-5\r\nhello\r\n0\r\n\r\n" to "malformed",
            "0x5\r\nhello\r\n0\r\n\r\n" to "malformed",
            " 5\r\nhello\r\n0\r\n\r\n" to "malformed",
            "5 \r\nhello\r\n0\r\n\r\n" to "malformed",
            "5\u00A0\r\nhello\r\n0\r\n\r\n" to "malformed",
            "g\r\nhello\r\n0\r\n\r\n" to "malformed",
            "FFFFFFFFFFFFFFFFF\r\nhello\r\n0\r\n\r\n" to "malformed",
            "10000000000000000\r\nhello\r\n0\r\n\r\n" to "malformed",
            "3\r\nhello\r\n0\r\n\r\n" to "malformed",
            "5\r\nhel" to "malformed",
            "5\r\nhello\r\n" to "malformed",
        ),
    )

    @TestFactory
    fun `chunk extensions`() = chunks(
        listOf(
            "5;name\r\nhello\r\n0\r\n\r\n" to "hello|20",
            "5;name=value\r\nhello\r\n0\r\n\r\n" to "hello|26",
            "5 ; a = b ; c\r\nhello\r\n0\r\n\r\n" to "hello|27",
            "5;a=\"q \\\" ;x\"\r\nhello\r\n0;last\r\n\r\n" to "hello|32",
            "5;\r\nhello\r\n0\r\n\r\n" to "malformed",
            "5;=b\r\nhello\r\n0\r\n\r\n" to "malformed",
            "5;a=\r\nhello\r\n0\r\n\r\n" to "malformed",
            "5;a=b \r\nhello\r\n0\r\n\r\n" to "malformed",
            "5;a=\"open\r\nhello\r\n0\r\n\r\n" to "malformed",
            "5;a\rb\r\nhello\r\n0\r\n\r\n" to "malformed",
            "5;a=b\u0000\r\nhello\r\n0\r\n\r\n" to "malformed",
            "5;a=\"\u0001\"\r\nhello\r\n0\r\n\r\n" to "malformed",
            "5;" + "a".repeat(1100) + "\r\nhello\r\n0\r\n\r\n" to "malformed",
        ),
    )

    @TestFactory
    fun `chunk line endings`() = chunks(
        listOf(
            "5\nhello\r\n0\r\n\r\n" to "malformed",
            "5\r\nhello\n0\r\n\r\n" to "malformed",
            "5\r\nhello\r\n0\n\r\n" to "malformed",
            "5\r\nhello\r\n0\r\n\n" to "malformed",
            "5\rhello\r\n0\r\n\r\n" to "malformed",
            "5\r\nhello\r0\r\n\r\n" to "malformed",
            "5\r\nhelloX\r\n0\r\n\r\n" to "malformed",
        ),
    )

    @TestFactory
    fun `trailer fields`() = chunks(
        listOf(
            "0\r\nX-Trailer: t\r\n\r\n" to "|19",
            "0\r\nA: 1\r\nB:\r\n\r\n" to "|15",
            "0\r\nX : t\r\n\r\n" to "malformed",
            "0\r\nX: t\r\n folded\r\n\r\n" to "malformed",
            "0\r\nX: t\n\r\n" to "malformed",
            "0\r\nX: a\u0000b\r\n\r\n" to "malformed",
            "0\r\nNoColon\r\n\r\n" to "malformed",
            "0\r\nX: t\r\n" to "malformed",
        ),
    )

    @Test
    fun `trailers past the head limit, and a body past its limit, are refused`() {
        decode("0\r\nX: " + "t".repeat(100) + "\r\n\r\n", maxTrailer = 64) shouldBe "malformed"
        decode("0\r\n" + "X: t\r\n".repeat(20) + "\r\n", maxTrailer = 64) shouldBe "malformed"
        decode("5\r\nhello\r\n5\r\nworld\r\n0\r\n\r\n", limit = 8) shouldBe "too large"
        decode("5\r\nhello\r\n5\r\nworld\r\n0\r\n\r\n", limit = 10) shouldBe "helloworld|25"
    }

    @Test
    fun `a chunked body ends where the next request starts, however it arrives`() {
        val input = "3\r\nabc\r\n0\r\nT: 1\r\n\r\nGET / HTTP/1.1\r\n".toByteArray()
        for (cut in 1..9) {
            val inbound = MemoryInbound(input, intArrayOf(cut), lent = 8)
            val decoded = readBody(inbound, Framing.Chunked, Long.MAX_VALUE, intArrayOf(cut))
            decoded.failure shouldBe null
            decoded.consumed shouldBe 19
        }
    }
}
