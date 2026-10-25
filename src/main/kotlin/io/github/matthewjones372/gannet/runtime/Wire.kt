package io.github.matthewjones372.gannet.runtime

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** Bytes on their way to a connection: replies for a whole batch of requests, written to the socket at once. */
internal class Out(capacity: Int) {
    var bytes = ByteArray(capacity)
    var size = 0

    private fun ensure(n: Int) {
        if (size + n > bytes.size) bytes = bytes.copyOf(maxOf(bytes.size * 2, size + n))
    }

    fun put(b: ByteArray, offset: Int = 0, length: Int = b.size) {
        ensure(length)
        System.arraycopy(b, offset, bytes, size, length)
        size += length
    }

    /** [s] as ISO-8859-1, one byte a character, which is what a header line is made of. */
    fun put(s: String) {
        ensure(s.length)
        for (c in s) bytes[size++] = c.code.toByte()
    }

    fun putDecimal(value: Long) = put(value.toString())

    fun putHex(value: Int) = put(Integer.toHexString(value))

    fun crlf() {
        ensure(2)
        bytes[size++] = CR
        bytes[size++] = LF
    }
}

/** `Date: <IMF-fixdate>` and its CR LF, rendered at most once a second, whichever thread asks first. */
internal object Dates {
    private class Rendered(val second: Long, val bytes: ByteArray)

    @Volatile
    private var last = render(System.currentTimeMillis() / MILLIS)

    private fun render(second: Long): Rendered {
        val date = DateTimeFormatter.RFC_1123_DATE_TIME.format(Instant.ofEpochSecond(second).atOffset(ZoneOffset.UTC))
        return Rendered(second, "Date: $date\r\n".toByteArray(Charsets.US_ASCII))
    }

    fun current(): ByteArray {
        val now = System.currentTimeMillis() / MILLIS
        val seen = last
        return if (seen.second == now) seen.bytes else render(now).also { last = it }.bytes
    }

    private const val MILLIS = 1000L
}

/** A reply's header fields, refused if any would break the head they are written into. */
internal fun checkFields(headers: List<Pair<String, String>>) {
    for ((name, value) in headers) {
        require(name.isNotEmpty() && name.all { it.code < BYTE_LIMIT && isTokenByte(it.code.toByte()) }) {
            "Bad header field name: $name"
        }
        require(value.none { it == '\r' || it == '\n' || it == '\u0000' }) { "Bad value for header field $name" }
    }
}

/** Whether a reply with [status] may carry a body at all. */
internal fun bodyAllowed(status: Int): Boolean = status >= OK && status != NO_CONTENT && status != NOT_MODIFIED

internal fun reason(status: Int): String = REASONS[status].orEmpty()

private val REASONS = mapOf(
    100 to "Continue", 101 to "Switching Protocols",
    200 to "OK", 201 to "Created", 202 to "Accepted", 203 to "Non-Authoritative Information", 204 to "No Content",
    205 to "Reset Content", 206 to "Partial Content",
    300 to "Multiple Choices", 301 to "Moved Permanently", 302 to "Found", 303 to "See Other", 304 to "Not Modified",
    307 to "Temporary Redirect", 308 to "Permanent Redirect",
    400 to "Bad Request", 401 to "Unauthorized", 402 to "Payment Required", 403 to "Forbidden", 404 to "Not Found",
    405 to "Method Not Allowed", 406 to "Not Acceptable", 408 to "Request Timeout", 409 to "Conflict", 410 to "Gone",
    411 to "Length Required", 412 to "Precondition Failed", 413 to "Content Too Large", 414 to "URI Too Long",
    415 to "Unsupported Media Type", 416 to "Range Not Satisfiable", 417 to "Expectation Failed",
    421 to "Misdirected Request", 422 to "Unprocessable Content", 425 to "Too Early", 426 to "Upgrade Required",
    428 to "Precondition Required", 429 to "Too Many Requests", 431 to "Request Header Fields Too Large",
    451 to "Unavailable For Legal Reasons",
    500 to "Internal Server Error", 501 to "Not Implemented", 502 to "Bad Gateway", 503 to "Service Unavailable",
    504 to "Gateway Timeout", 505 to "HTTP Version Not Supported",
)

internal val CONTINUE = "HTTP/1.1 100 Continue\r\n\r\n".toByteArray(Charsets.US_ASCII)
internal val LAST_CHUNK = "0\r\n\r\n".toByteArray(Charsets.US_ASCII)

private const val BYTE_LIMIT = 0x80
internal const val OK = 200
internal const val NO_CONTENT = 204
internal const val NOT_MODIFIED = 304
internal const val CONTENT_TOO_LARGE = 413
internal const val SERVER_ERROR = 500
