package io.github.matthewjones372.gannet.runtime

import io.github.matthewjones372.gannet.Headers
import io.github.matthewjones372.gannet.HttpVersion

/** A request line and header fields, parsed and checked, and how its body is framed. */
internal class Head(
    val method: String,
    val target: String,
    val path: String,
    val query: String?,
    val version: HttpVersion,
    val headers: Headers,
    val framing: Framing,
    val keepAlive: Boolean,
    val expectContinue: Boolean,
)

internal sealed interface Framing {
    data object None : Framing

    class Fixed(val length: Long) : Framing

    data object Chunked : Framing
}

internal sealed interface Parse {
    /** More bytes are needed before anything can be said. */
    data object Incomplete : Parse

    /** Not a request this server will read: answer [status] and close. */
    class Refused(val status: Int, val reason: String) : Parse

    /** The head, and the index of the first byte after it. */
    class Complete(val head: Head, val end: Int) : Parse
}

/** A head that cannot be served, as the status it is answered with. Thrown within this file only. */
private class Refusal(val status: Int, reason: String) : RuntimeException(reason, null, false, false)

private fun refuse(status: Int, reason: String): Nothing = throw Refusal(status, reason)

/**
 * A request head from [b] between [from] and [to], within [maxLine] for the request line, [maxHead] in all and
 * [maxFields] header fields.
 */
internal fun parseHead(b: ByteArray, from: Int, to: Int, maxLine: Int, maxHead: Int, maxFields: Int): Parse = try {
    readHead(b, from, to, maxLine, maxHead, maxFields)
} catch (r: Refusal) {
    Parse.Refused(r.status, r.message.orEmpty())
}

/** How many bytes of empty lines start the input at [from]; a client may send one or two before a request. */
internal fun blankLines(b: ByteArray, from: Int, to: Int): Int {
    var at = from
    while (true) {
        at += lineEndingAt(b, at, to).takeIf { it > 0 } ?: break
    }
    return at - from
}

/** The length of the line ending at [at]: 1 for LF, 2 for CR LF, else 0. */
private fun lineEndingAt(b: ByteArray, at: Int, to: Int): Int = when {
    at >= to -> 0
    b[at] == LF -> 1
    b[at] == CR && at + 1 < to && b[at + 1] == LF -> 2
    else -> 0
}

private fun readHead(b: ByteArray, from: Int, to: Int, maxLine: Int, maxHead: Int, maxFields: Int): Parse {
    val lineEnd = lineEnd(b, from, to, maxLine, URI_TOO_LONG) ?: return Parse.Incomplete
    val line = requestLine(b, from, contentEnd(b, from, lineEnd))
    // Saturated, so a limit near Int.MAX_VALUE cannot wrap around to a negative index.
    val headLimit = if (maxHead >= Int.MAX_VALUE - 1 - from) Int.MAX_VALUE - 1 else from + maxHead
    var at = lineEnd + 1
    var bodyStart = -1
    val fields = buildList {
        while (bodyStart < 0) {
            val end = lineEnd(b, at, minOf(to, headLimit + 1), headLimit - at, HEADERS_TOO_LARGE) ?: break
            val content = contentEnd(b, at, end)
            if (content == at) {
                bodyStart = end + 1
            } else {
                if (size == maxFields) refuse(HEADERS_TOO_LARGE, "More than $maxFields header fields")
                add(field(b, at, content))
            }
            at = end + 1
        }
    }
    if (bodyStart >= 0) return Parse.Complete(frame(line, Headers(fields)), bodyStart)
    if (to > headLimit) refuse(HEADERS_TOO_LARGE, "The request head is longer than $maxHead bytes")
    return Parse.Incomplete
}

/** The index of the line feed ending the line at [from], or null if it has not arrived; refuses past [max] bytes. */
private fun lineEnd(b: ByteArray, from: Int, to: Int, max: Int, status: Int): Int? {
    val limit = if (max >= to - from - 2) to else from + max + 2
    val lf = Scan.indexOf(b, LF.toInt(), from, maxOf(from, limit))
    if (lf >= 0 && contentEnd(b, from, lf) - from <= max) return lf
    if (lf >= 0 || to - from > max + 1) refuse(status, "A line of the request head is longer than $max bytes")
    return null
}

/** The end of a line's content: before its CR LF, or before a bare LF, which a recipient may accept. */
private fun contentEnd(b: ByteArray, from: Int, lf: Int): Int = if (lf > from && b[lf - 1] == CR) lf - 1 else lf

private class Line(val method: String, val target: String, val version: HttpVersion)

private fun requestLine(b: ByteArray, from: Int, end: Int): Line {
    val methodEnd = Scan.indexOf(b, SP.toInt(), from, end)
    if (methodEnd <= from || !(from until methodEnd).all { isTokenByte(b[it]) }) refuse(BAD_REQUEST, "Bad method")
    val targetEnd = Scan.indexOf(b, SP.toInt(), methodEnd + 1, end)
    if (targetEnd <= methodEnd + 1) refuse(BAD_REQUEST, "Bad request target")
    if (!(methodEnd + 1 until targetEnd).all { isTargetByte(b[it]) }) refuse(BAD_REQUEST, "Bad request target")
    return Line(
        String(b, from, methodEnd - from, Charsets.US_ASCII),
        String(b, methodEnd + 1, targetEnd - methodEnd - 1, Charsets.UTF_8),
        version(b, targetEnd + 1, end),
    )
}

private fun version(b: ByteArray, from: Int, end: Int): HttpVersion {
    val shaped = end - from == VERSION_LENGTH && (0 until HTTP_SLASH.size).all { b[from + it] == HTTP_SLASH[it] } &&
        isDigit(b[from + MAJOR]) && b[from + MAJOR + 1] == '.'.code.toByte() && isDigit(b[from + MINOR])
    if (!shaped) refuse(BAD_REQUEST, "Bad HTTP version")
    if (b[from + MAJOR] != '1'.code.toByte()) refuse(VERSION_NOT_SUPPORTED, "Only HTTP/1.x is served")
    return if (b[from + MINOR] == '0'.code.toByte()) HttpVersion.HTTP_1_0 else HttpVersion.HTTP_1_1
}

/** Whether [b] from [from] to [end] is a well-formed field line, as a trailer field must be. */
internal fun isFieldLine(b: ByteArray, from: Int, end: Int): Boolean {
    val colon = Scan.indexOf(b, ':'.code, from, end)
    if (colon <= from) return false
    for (i in from until colon) if (!isTokenByte(b[i])) return false
    for (i in colon + 1 until end) if (!isValueByte(b[i])) return false
    return true
}

private fun field(b: ByteArray, from: Int, end: Int): Pair<String, String> {
    if (isBlank(b[from])) refuse(BAD_REQUEST, "Obsolete line folding")
    val colon = Scan.indexOf(b, ':'.code, from, end)
    if (colon <= from || !(from until colon).all { isTokenByte(b[it]) }) refuse(BAD_REQUEST, "Bad header field name")
    return String(b, from, colon - from, Charsets.US_ASCII) to value(b, colon + 1, end)
}

/** A field value without the optional whitespace around it. */
private fun value(b: ByteArray, from: Int, end: Int): String {
    var start = from
    var stop = end
    while (start < stop && isBlank(b[start])) start++
    while (stop > start && isBlank(b[stop - 1])) stop--
    if (!(start until stop).all { isValueByte(b[it]) }) refuse(BAD_REQUEST, "Bad header field value")
    return String(b, start, stop - start, Charsets.UTF_8)
}

private fun isBlank(c: Byte): Boolean = c == SP || c == HTAB

private fun frame(line: Line, headers: Headers): Head {
    val hosts = headers.all("host")
    if (hosts.size > 1) refuse(BAD_REQUEST, "More than one Host")
    if (hosts.isEmpty() && line.version == HttpVersion.HTTP_1_1) refuse(BAD_REQUEST, "No Host")
    if (hosts.isNotEmpty() && !isHost(hosts[0], allowEmpty = true)) refuse(BAD_REQUEST, "Bad Host")
    val (path, query) = split(line.method, line.target)
    val connection = tokens(headers.all("connection"))
    val keepAlive = "close" !in connection &&
        (line.version == HttpVersion.HTTP_1_1 || "keep-alive" in connection)
    return Head(
        line.method, line.target, path, query, line.version, headers,
        framing(line.version, headers), keepAlive, expectsContinue(line.version, headers),
    )
}

private fun split(method: String, target: String): Pair<String, String?> {
    if ('#' in target) refuse(BAD_REQUEST, "A request target has no fragment")
    return when {
        target.startsWith("/") -> pathAndQuery(target)

        target == "*" && method == "OPTIONS" -> "*" to null

        target.startsWith("http://", ignoreCase = true) || target.startsWith("https://", ignoreCase = true) -> {
            val authority = target.indexOf("://") + SCHEME_SEPARATOR
            val rest = target.indexOfAny(charArrayOf('/', '?'), authority)
            if (!isHost(target, allowEmpty = false, authority, if (rest < 0) target.length else rest)) {
                refuse(BAD_REQUEST, "Bad authority in the request target")
            }
            if (rest < 0) "/" to null else pathAndQuery(target.substring(rest).let { if (it[0] == '?') "/$it" else it })
        }

        else -> refuse(BAD_REQUEST, "Bad request target")
    }
}

private fun pathAndQuery(target: String): Pair<String, String?> {
    val q = target.indexOf('?')
    return if (q < 0) target to null else target.substring(0, q) to target.substring(q + 1)
}

private fun framing(version: HttpVersion, headers: Headers): Framing {
    val codings = tokens(headers.all("transfer-encoding"))
    val lengths = headers.all("content-length").flatMap { it.split(',') }.map { it.trimOws() }.distinct()
    if (codings.isNotEmpty() || "transfer-encoding" in headers) {
        if (version == HttpVersion.HTTP_1_0) refuse(BAD_REQUEST, "Transfer-Encoding in an HTTP/1.0 request")
        if (lengths.isNotEmpty()) refuse(BAD_REQUEST, "Both Content-Length and Transfer-Encoding")
        if (codings.lastOrNull() != "chunked") refuse(BAD_REQUEST, "The last transfer coding is not chunked")
        if (codings.size > 1) refuse(NOT_IMPLEMENTED, "Only the chunked transfer coding is understood")
        return Framing.Chunked
    }
    if (lengths.isEmpty()) return Framing.None
    val length = lengths.singleOrNull()?.takeIf { it.length in 1..MAX_LENGTH_DIGITS && it.all(Char::isAsciiDigit) }
        ?.toLong() ?: refuse(BAD_REQUEST, "Bad Content-Length")
    return if (length == 0L) Framing.None else Framing.Fixed(length)
}

private fun Char.isAsciiDigit(): Boolean = this in '0'..'9'

private fun expectsContinue(version: HttpVersion, headers: Headers): Boolean {
    val expect = headers.all("expect")
    if (expect.isEmpty() || version == HttpVersion.HTTP_1_0) return false
    if (expect.singleOrNull()?.equals("100-continue", ignoreCase = true) != true) {
        refuse(EXPECTATION_FAILED, "Only 100-continue is expected")
    }
    return true
}

/**
 * The comma-separated tokens of [values], trimmed of spaces and tabs and lower-cased as ASCII. Only ASCII is folded: a
 * Unicode case mapping turns the Kelvin sign into `k`, and so `chun\u212Aed` into `chunked`.
 */
internal fun tokens(values: List<String>): List<String> =
    values.flatMap { it.split(',') }.map { it.trimOws().asciiLowercase() }.filter { it.isNotEmpty() }

/** Without the spaces and tabs around it: Kotlin's `trim` also takes Unicode spaces, which no field value has. */
private fun String.trimOws(): String {
    var start = 0
    var stop = length
    while (start < stop && (this[start] == ' ' || this[start] == '\t')) start++
    while (stop > start && (this[stop - 1] == ' ' || this[stop - 1] == '\t')) stop--
    return if (start == 0 && stop == length) this else substring(start, stop)
}

private fun String.asciiLowercase(): String = if (none { it in 'A'..'Z' }) {
    this
} else {
    String(CharArray(length) { this[it].let { c -> if (c in 'A'..'Z') c + ('a' - 'A') else c } })
}

/**
 * RFC 9110 `uri-host [ ":" port ]`: a registered name or IPv4 address of unreserved, percent-encoded and sub-delimiter
 * characters, or a bracketed IP literal. A Host field may be empty; an `http` URI's authority may not.
 */
internal fun isHost(s: String, allowEmpty: Boolean, from: Int = 0, to: Int = s.length): Boolean {
    if (from == to) return allowEmpty
    val nameEnd = hostNameEnd(s, from, to)
    return nameEnd in from + 1..to && (nameEnd == to || (s[nameEnd] == ':' && s.allIn(nameEnd + 1, to, DIGITS)))
}

/** Where the name or IP literal starting a host at [from] ends, or -1 if it is neither. */
private fun hostNameEnd(s: String, from: Int, to: Int): Int {
    if (s[from] == '[') {
        val close = s.indexOf(']', from)
        return if (close in from + 2 until to && s.allIn(from + 1, close, IP_LITERAL)) close + 1 else -1
    }
    val colon = s.lastIndexOf(':', to - 1)
    val end = if (colon < from) to else colon
    return if (s.allIn(from, end, REG_NAME)) end else -1
}

private fun String.allIn(from: Int, to: Int, set: BooleanArray): Boolean {
    for (i in from until to) if (!this[i].isIn(set)) return false
    return true
}

/** RFC 9110 `tchar`: what a method or field name is spelled with. */
internal fun isTokenByte(c: Byte): Boolean {
    val i = c.toInt()
    return i in 'a'.code..'z'.code || i in 'A'.code..'Z'.code || i in '0'.code..'9'.code || i.toChar() in TOKEN_MARKS
}

/**
 * What RFC 3986 lets a URI hold unescaped, or a byte of a UTF-8 sequence, which some clients send raw. `"`, `<`, `>`,
 * `\`, `^`, `` ` ``, `{`, `|` and `}` are refused, as Pekko's strict parser refuses them.
 */
private fun isTargetByte(c: Byte): Boolean =
    c < 0 || (c.toInt() in VISIBLE_START..VISIBLE_END && c.toInt().toChar() !in NOT_IN_URI)

/** A field value byte: visible, space, tab, or obs-text; never a control, CR or LF. */
private fun isValueByte(c: Byte): Boolean = c == HTAB || c.toInt() and BYTE_MASK in SP.toInt()..VISIBLE_END || c < 0

private fun isDigit(c: Byte): Boolean = c in '0'.code.toByte()..'9'.code.toByte()

internal const val CR: Byte = 13
internal const val LF: Byte = 10
private const val SP: Byte = 32
private const val HTAB: Byte = 9
private const val BYTE_MASK = 0xFF
private const val ASCII = 128
private const val VISIBLE_START = 0x21
private const val VISIBLE_END = 0x7E
private const val VERSION_LENGTH = 8
private const val MAJOR = 5
private const val MINOR = 7
private const val SCHEME_SEPARATOR = 3
private const val MAX_LENGTH_DIGITS = 18
private const val TOKEN_MARKS = "!#$%&'*+-.^_`|~"
private const val NOT_IN_URI = "\"<>\\^`{|}"
private val REG_NAME = asciiSet("abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-._~%!$&'()*+,;=")
private val IP_LITERAL = asciiSet("0123456789abcdefABCDEFvV:.-_~!$&'()*+,;=")
private val DIGITS = asciiSet("0123456789")

private fun asciiSet(chars: String): BooleanArray = BooleanArray(ASCII).also { set ->
    chars.forEach {
        set[it.code] =
            true
    }
}

private fun Char.isIn(set: BooleanArray): Boolean = code < ASCII && set[code]
private val HTTP_SLASH = "HTTP/".toByteArray(Charsets.US_ASCII)

internal const val BAD_REQUEST = 400
internal const val URI_TOO_LONG = 414
internal const val EXPECTATION_FAILED = 417
internal const val HEADERS_TOO_LARGE = 431
internal const val NOT_IMPLEMENTED = 501
internal const val VERSION_NOT_SUPPORTED = 505
