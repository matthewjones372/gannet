package io.github.matthewjones372.gannet.runtime

/**
 * The size a chunk size line in [b] from [from] to its line feed at [lf] declares, or -1 when the line is not RFC 9112
 * `chunk-size [ chunk-ext ] CRLF`. Leniency here is what request smuggling feeds on, so the line must end in CR LF, the
 * extensions must parse, and a bare CR, a sign or Unicode whitespace is refused rather than skipped.
 */
internal fun chunkSize(b: ByteArray, from: Int, lf: Int): Long {
    val end = lf - 1
    if (end < from || b[end] != CR) return -1
    var at = from
    var size = 0L
    while (at < end) {
        val digit = hexValue(b[at])
        if (digit < 0) break
        if (size > MAX_CHUNK shr HEX_BITS) return -1
        size = size shl HEX_BITS or digit.toLong()
        at++
    }
    return if (at > from && isChunkExtensions(b, at, end)) size else -1
}

/** RFC 9112 `*( BWS ";" BWS chunk-ext-name [ BWS "=" BWS chunk-ext-val ] )`, from [from] to [end] exactly. */
private fun isChunkExtensions(b: ByteArray, from: Int, end: Int): Boolean {
    var at = from
    while (at < end) {
        at = blanks(b, at, end)
        if (at == end || b[at] != SEMICOLON) return false
        at = blanks(b, at + 1, end)
        val nameEnd = tokenEnd(b, at, end)
        if (nameEnd == at) return false
        val equals = blanks(b, nameEnd, end)
        at = if (equals < end && b[equals] == EQUALS) {
            val value = blanks(b, equals + 1, end)
            when {
                value < end && b[value] == QUOTE -> quotedEnd(b, value, end)
                else -> tokenEnd(b, value, end).takeIf { it > value } ?: -1
            }
        } else {
            nameEnd
        }
        if (at < 0) return false
    }
    return true
}

private fun blanks(b: ByteArray, from: Int, end: Int): Int {
    var at = from
    while (at < end && (b[at] == SP || b[at] == HTAB)) at++
    return at
}

private fun tokenEnd(b: ByteArray, from: Int, end: Int): Int {
    var at = from
    while (at < end && isTokenByte(b[at])) at++
    return at
}

/** The index after the quoted string opening at [from], or -1 if it does not close before [end]. */
private fun quotedEnd(b: ByteArray, from: Int, end: Int): Int {
    var at = from + 1
    while (at < end) {
        val c = b[at]
        when {
            c == QUOTE -> return at + 1
            c == BACKSLASH -> if (at + 1 < end && isQuotable(b[at + 1])) at += 2 else return -1
            isQuotable(c) -> at++
            else -> return -1
        }
    }
    return -1
}

/** HTAB, SP, a visible character or obs-text: what `qdtext` and `quoted-pair` hold, quote and backslash aside. */
private fun isQuotable(c: Byte): Boolean = c == HTAB || c == SP || c.toInt() in VISIBLE || c < 0

private fun hexValue(c: Byte): Int = when (val i = c.toInt()) {
    in '0'.code..'9'.code -> i - '0'.code
    in 'a'.code..'f'.code -> i - 'a'.code + TEN
    in 'A'.code..'F'.code -> i - 'A'.code + TEN
    else -> -1
}

/** A chunk no larger than this, which no body reaches and whose sum with others cannot overflow. */
private const val MAX_CHUNK = 1L shl 60
private const val HEX_BITS = 4
private const val TEN = 10
private const val SP: Byte = 32
private const val HTAB: Byte = 9
private const val SEMICOLON = ';'.code.toByte()
private const val EQUALS = '='.code.toByte()
private const val QUOTE = '"'.code.toByte()
private const val BACKSLASH = '\\'.code.toByte()
private val VISIBLE = 0x21..0x7E
