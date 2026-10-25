package io.github.matthewjones372.gannet.runtime

import io.github.matthewjones372.gannet.HttpVersion
import io.kotest.assertions.withClue
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

class HeadTest {
    private fun parse(text: String, maxLine: Int = 8192, maxHead: Int = 16384): Parse {
        val bytes = text.toByteArray(Charsets.ISO_8859_1)
        return parseHead(bytes, 0, bytes.size, maxLine, maxHead)
    }

    private fun head(text: String): Head = parse(text).shouldBeInstanceOf<Parse.Complete>().head

    private fun refused(text: String, maxLine: Int = 8192, maxHead: Int = 16384): Int =
        parse(text, maxLine, maxHead).shouldBeInstanceOf<Parse.Refused>().status

    @Test
    fun `a complete head says where the body starts`() {
        val text = "POST /a HTTP/1.1\r\nHost: x\r\nContent-Length: 3\r\n\r\nabc"
        val parsed = parse(text).shouldBeInstanceOf<Parse.Complete>()
        parsed.end shouldBe text.length - 3
        parsed.head.framing.shouldBeInstanceOf<Framing.Fixed>().length shouldBe 3L
    }

    @Test
    fun `every prefix of a head is incomplete rather than refused`() {
        val text = "GET /a/b?c=d HTTP/1.1\r\nHost: x\r\nAccept: */*\r\n\r\n"
        for (cut in 0 until text.length) {
            withClue("cut at $cut") { parse(text.substring(0, cut)) shouldBe Parse.Incomplete }
        }
    }

    @Test
    fun `the raw path keeps its escapes and the query is split off raw`() {
        val head = head("GET /files/a%2Fb/%20x?q=%41&r HTTP/1.1\r\nHost: x\r\n\r\n")
        head.path shouldBe "/files/a%2Fb/%20x"
        head.query shouldBe "q=%41&r"
        head.target shouldBe "/files/a%2Fb/%20x?q=%41&r"
    }

    @Test
    fun `no question mark is no query, and an empty one is an empty query`() {
        head("GET /a HTTP/1.1\r\nHost: x\r\n\r\n").query.shouldBeNull()
        head("GET /a? HTTP/1.1\r\nHost: x\r\n\r\n").query shouldBe ""
    }

    @Test
    fun `an absolute-form target is reduced to its path and query`() {
        head("GET http://example.com:8080/a/b?c HTTP/1.1\r\nHost: x\r\n\r\n").let {
            it.path shouldBe "/a/b"
            it.query shouldBe "c"
        }
        head("GET https://example.com HTTP/1.1\r\nHost: x\r\n\r\n").path shouldBe "/"
        head("GET http://example.com?x=1 HTTP/1.1\r\nHost: x\r\n\r\n").let {
            it.path shouldBe "/"
            it.query shouldBe "x=1"
        }
    }

    @Test
    fun `an asterisk is a target for OPTIONS only`() {
        head("OPTIONS * HTTP/1.1\r\nHost: x\r\n\r\n").path shouldBe "*"
        refused("GET * HTTP/1.1\r\nHost: x\r\n\r\n") shouldBe 400
    }

    @Test
    fun `header names are matched without regard to case and values are trimmed`() {
        val head = head("GET / HTTP/1.1\r\nhOsT: x\r\nX-A:   one  \r\nx-a:two\r\n\r\n")
        head.headers["X-a"] shouldBe "one"
        head.headers.all("X-A") shouldBe listOf("one", "two")
        head.headers.names shouldBe setOf("host", "x-a")
        ("x-b" in head.headers) shouldBe false
    }

    @Test
    fun `bare line feeds end lines as well as CR LF`() {
        head("GET / HTTP/1.1\nHost: x\n\n").headers["host"] shouldBe "x"
    }

    @Test
    fun `HTTP 1 0 is read, closes by default, and keeps alive when asked`() {
        head("GET / HTTP/1.0\r\n\r\n").let {
            it.version shouldBe HttpVersion.HTTP_1_0
            it.keepAlive shouldBe false
        }
        head("GET / HTTP/1.0\r\nConnection: Keep-Alive\r\n\r\n").keepAlive shouldBe true
        head("GET / HTTP/1.1\r\nHost: x\r\nConnection: foo, close\r\n\r\n").keepAlive shouldBe false
    }

    @Test
    fun `a later minor version is read as 1 1`() {
        head("GET / HTTP/1.7\r\nHost: x\r\n\r\n").version shouldBe HttpVersion.HTTP_1_1
    }

    @Test
    fun `a chunked body is framed as chunked and a zero length as none`() {
        head("POST / HTTP/1.1\r\nHost: x\r\nTransfer-Encoding: Chunked\r\n\r\n").framing shouldBe Framing.Chunked
        head("POST / HTTP/1.1\r\nHost: x\r\nContent-Length: 0\r\n\r\n").framing shouldBe Framing.None
        head("POST / HTTP/1.1\r\nHost: x\r\nContent-Length: 5, 5\r\n\r\n").framing
            .shouldBeInstanceOf<Framing.Fixed>().length shouldBe 5L
    }

    @Test
    fun `100-continue is noticed on 1 1 and ignored on 1 0`() {
        head("POST / HTTP/1.1\r\nHost: x\r\nExpect: 100-Continue\r\n\r\n").expectContinue shouldBe true
        head("POST / HTTP/1.0\r\nExpect: 100-continue\r\n\r\n").expectContinue shouldBe false
    }

    @Test
    fun `leading empty lines are counted so they can be skipped`() {
        val bytes = "\r\n\nGET".toByteArray()
        blankLines(bytes, 0, bytes.size) shouldBe 3
        blankLines(bytes, 3, bytes.size) shouldBe 0
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(
        delimiter = '|',
        textBlock = """
        no method                     | ' / HTTP/1.1'                                              | 400
        bad method character          | 'G(T / HTTP/1.1'                                           | 400
        no target                     | 'GET  HTTP/1.1'                                            | 400
        no version                    | 'GET /'                                                    | 400
        lower-case version            | 'GET / http/1.1'                                           | 400
        version without minor         | 'GET / HTTP/1'                                             | 400
        trailing junk                 | 'GET / HTTP/1.1 x'                                         | 400
        HTTP 2                        | 'GET / HTTP/2.0'                                           | 505
        HTTP 0 9                      | 'GET / HTTP/0.9'                                           | 505
        a relative target             | 'GET a/b HTTP/1.1'                                         | 400
        a fragment                    | 'GET /a#b HTTP/1.1'                                        | 400
        a control in the target       | 'GET /a\u0001 HTTP/1.1'                                    | 400
        a quote in the target         | 'GET /a"b HTTP/1.1'                                        | 400
        a brace in the target         | 'GET /a{b} HTTP/1.1'                                       | 400""",
    )
    fun `a bad request line is refused`(case: String, line: String, status: Int) {
        refused(line.replace("\\u0001", "\u0001") + "\r\nHost: x\r\n\r\n") shouldBe status
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(
        delimiter = '|',
        textBlock = """
        no Host                       | 'Accept: x'                                                | 400
        two Hosts                     | 'Host: a\r\nHost: b'                                       | 400
        folded line                   | 'Host: a\r\nX: a\r\n  b'                                   | 400
        no colon                      | 'Host: a\r\nNoColon'                                       | 400
        space before colon            | 'Host: a\r\nX : b'                                         | 400
        empty name                    | 'Host: a\r\n: b'                                           | 400
        a control in a value          | 'Host: a\r\nX: a\u0001b'                                   | 400
        a bare CR in a value          | 'Host: a\r\nX: a\rb'                                       | 400
        both lengths                  | 'Host: a\r\nContent-Length: 1\r\nTransfer-Encoding: chunked' | 400
        differing lengths             | 'Host: a\r\nContent-Length: 1\r\nContent-Length: 2'        | 400
        negative length               | 'Host: a\r\nContent-Length: -1'                            | 400
        non-numeric length            | 'Host: a\r\nContent-Length: 1a'                            | 400
        overlong length               | 'Host: a\r\nContent-Length: 1234567890123456789'           | 400
        empty transfer coding         | 'Host: a\r\nTransfer-Encoding: '                           | 400
        chunked not last              | 'Host: a\r\nTransfer-Encoding: chunked, gzip'              | 400
        chunked twice                 | 'Host: a\r\nTransfer-Encoding: chunked, chunked'           | 501
        gzip then chunked             | 'Host: a\r\nTransfer-Encoding: gzip, chunked'              | 501
        an unknown expectation        | 'Host: a\r\nExpect: 200-ok'                                | 417""",
    )
    fun `a bad header section is refused`(case: String, fields: String, status: Int) {
        val text = "POST / HTTP/1.1\r\n" + fields.replace("\\r", "\r").replace("\\n", "\n")
            .replace("\\u0001", "\u0001") + "\r\n\r\n"
        refused(text) shouldBe status
    }

    @Test
    fun `transfer coding on 1 0 is refused`() {
        refused("POST / HTTP/1.0\r\nTransfer-Encoding: chunked\r\n\r\n") shouldBe 400
    }

    @Test
    fun `a request line past its limit is 414, whether or not its end has arrived`() {
        val line = "GET /" + "a".repeat(100) + " HTTP/1.1\r\n"
        refused(line + "Host: x\r\n\r\n", maxLine = 50) shouldBe 414
        refused(line.substring(0, 80), maxLine = 50) shouldBe 414
        parse(line.substring(0, 40), maxLine = 50) shouldBe Parse.Incomplete
    }

    @Test
    fun `a header section past its limit is 431, whether or not its end has arrived`() {
        val start = "GET / HTTP/1.1\r\nHost: x\r\n"
        val big = "X: " + "b".repeat(200) + "\r\n"
        refused(start + big + "\r\n", maxHead = 100) shouldBe 431
        refused(start + big, maxHead = 100) shouldBe 431
        refused(start + "Y: 1\r\n".repeat(30), maxHead = 100) shouldBe 431
        parse(start + "Y: 1\r\n", maxHead = 100) shouldBe Parse.Incomplete
    }

    @Test
    fun `tokens are split on commas, trimmed and lower-cased`() {
        tokens(listOf("A, b ,", " C")) shouldBe listOf("a", "b", "c")
    }
}
