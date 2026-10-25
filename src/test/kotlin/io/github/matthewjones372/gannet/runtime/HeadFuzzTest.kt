package io.github.matthewjones372.gannet.runtime

import com.code_intelligence.jazzer.junit.FuzzTest
import io.github.matthewjones372.gannet.HttpVersion

/**
 * The request-head parser fed arbitrary bytes. It never throws, stays within its limits, gives the same answer
 * wherever in a buffer the head sits and however much of it has arrived, and a head it accepts reads back the same.
 */
class HeadFuzzTest {
    @FuzzTest(maxDuration = "60s")
    fun head(data: ByteArray) {
        val knobs = Knobs(data)
        checkHead(knobs.input, knobs.maxLine, knobs.maxHead, knobs.maxFields, pad = data.size % 7)
    }
}

internal fun checkHead(input: ByteArray, maxLine: Int, maxHead: Int, maxFields: Int, pad: Int) {
    fun parse(to: Int) = parseHead(input, 0, to, maxLine, maxHead, maxFields)
    val whole = parse(input.size)

    // The same bytes behind [pad] bytes of junk, and followed by more: indexes are relative to where the head starts.
    val padded = ByteArray(pad) { 'G'.code.toByte() } + input + "\r\n\r\n".toByteArray()
    val shifted = parseHead(padded, pad, pad + input.size, maxLine, maxHead, maxFields)
    check(same(whole, shifted, pad)) { "Moved by $pad: ${show(whole)} became ${show(shifted)}" }

    when (whole) {
        is Parse.Complete -> checkComplete(input, whole, maxLine, maxHead, maxFields)
        is Parse.Refused -> check(whole.status in REFUSALS) { "Refused with ${whole.status}" }
        Parse.Incomplete -> check(input.size <= maxHead + 1) { "${input.size} bytes and still incomplete" }
    }

    // Arriving in parts changes nothing: before a head is whole it is incomplete, and a refusal never becomes another.
    for (cut in cuts(input.size)) {
        val part = parse(cut)
        val agrees = when (whole) {
            is Parse.Complete -> if (cut < whole.end) part == Parse.Incomplete else same(whole, part, 0)
            is Parse.Refused -> part == Parse.Incomplete || (part is Parse.Refused && part.status == whole.status)
            Parse.Incomplete -> part == Parse.Incomplete
        }
        check(agrees) { "The first $cut bytes gave ${show(part)}, all ${input.size} gave ${show(whole)}" }
    }
}

private fun checkComplete(input: ByteArray, parsed: Parse.Complete, maxLine: Int, maxHead: Int, maxFields: Int) {
    val head = parsed.head
    check(parsed.end in 1..input.size) { "The head ends at ${parsed.end} of ${input.size}" }
    check(parsed.end <= maxHead + 2) { "A head of ${parsed.end} bytes passed a limit of $maxHead" }
    val lineLength = input.indexOf(LF) - if (input[input.indexOf(LF) - 1] == CR) 1 else 0
    check(lineLength <= maxLine) { "A request line of $lineLength bytes passed a limit of $maxLine" }
    check(head.headers.fields.size <= maxFields) { "${head.headers.fields.size} fields passed a limit of $maxFields" }
    check(head.method.isNotEmpty() && head.method.all { it.code < 128 && isTokenByte(it.code.toByte()) })
    check(head.path.startsWith("/") || head.path == "*") { "Path ${head.path}" }
    checkFraming(head)

    val again = serialize(head)
    val reparsed = parseHead(again, 0, again.size, Int.MAX_VALUE, Int.MAX_VALUE, Int.MAX_VALUE)
    check(reparsed is Parse.Complete && reparsed.end == again.size && describe(reparsed.head) == describe(head)) {
        "${describe(head)} wrote out as ${String(again)} and read back as ${show(reparsed)}"
    }
}

/** The framing and Host of an accepted head, held to RFC 9112 as [referenceFraming] reads it. */
private fun checkFraming(head: Head) {
    when (val framing = head.framing) {
        is Framing.Fixed -> check(framing.length > 0) { "A fixed length of ${framing.length}" }
        Framing.Chunked -> check(head.version == HttpVersion.HTTP_1_1 && "content-length" !in head.headers)
        Framing.None -> check("transfer-encoding" !in head.headers)
    }
    if (head.version == HttpVersion.HTTP_1_1) {
        check(head.headers.all("host").size == 1) { "Host ${head.headers.all("host")}" }
    }
    head.headers["host"]?.let { check(it.isEmpty() || HOST.matches(it)) { "Host $it" } }
    val expected = referenceFraming(head)
    check(expected == describeFraming(head.framing)) { "Framed as ${describeFraming(head.framing)}, not $expected" }
}

private val REFUSALS =
    setOf(BAD_REQUEST, URI_TOO_LONG, EXPECTATION_FAILED, HEADERS_TOO_LARGE, NOT_IMPLEMENTED, VERSION_NOT_SUPPORTED)

/** Every cut of a short input, and a spread of them through a long one. */
private fun cuts(size: Int): IntProgression = if (size <= 256) 0 until size else 0 until size step (size / 64)

private fun same(a: Parse, b: Parse, shift: Int): Boolean = when (a) {
    is Parse.Complete -> b is Parse.Complete && b.end == a.end + shift && describe(a.head) == describe(b.head)
    is Parse.Refused -> b is Parse.Refused && b.status == a.status
    Parse.Incomplete -> b == Parse.Incomplete
}

private fun show(p: Parse): String = when (p) {
    is Parse.Complete -> "Complete(${p.end}, ${describe(p.head)})"
    is Parse.Refused -> "Refused(${p.status}, ${p.reason})"
    Parse.Incomplete -> "Incomplete"
}

private fun ByteArray.indexOf(b: Byte): Int = indexOfFirst { it == b }

/** Framing decided from the fields again, with ASCII-only case folding and whitespace, as RFC 9112 reads them. */
private fun referenceFraming(head: Head): String {
    fun elements(name: String) =
        head.headers.all(name).flatMap { it.split(',') }.map { it.trim(' ', '\t') }.filter { it.isNotEmpty() }
    val codings = elements("transfer-encoding")
    val lengths = head.headers.all("content-length").flatMap { it.split(',') }.map { it.trim(' ', '\t') }.distinct()
    if ("transfer-encoding" in head.headers) {
        val chunkedOnly = codings.size == 1 && CHUNKED.matches(codings[0])
        return if (chunkedOnly && lengths.isEmpty() && head.version == HttpVersion.HTTP_1_1) "chunked" else "refused"
    }
    if (lengths.isEmpty()) return "none"
    val length = lengths.singleOrNull()?.takeIf { DIGITS.matches(it) }?.toLong() ?: return "refused"
    return if (length == 0L) "none" else "fixed $length"
}

private fun describeFraming(framing: Framing): String = when (framing) {
    Framing.None -> "none"
    Framing.Chunked -> "chunked"
    is Framing.Fixed -> "fixed ${framing.length}"
}

// Java's (?i) folds ASCII only, which is the point.
private val CHUNKED = Regex("(?i)chunked")
private val DIGITS = Regex("[0-9]{1,18}")
private val HOST = Regex(
    "(?:\\[[0-9A-Fa-fvV:.\\-_~!$&'()*+,;=]+]|[A-Za-z0-9\\-._~%!$&'()*+,;=]+)(?::[0-9]*)?",
)
