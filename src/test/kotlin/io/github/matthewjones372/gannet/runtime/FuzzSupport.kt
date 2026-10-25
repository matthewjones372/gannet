package io.github.matthewjones372.gannet.runtime

import io.github.matthewjones372.gannet.BodyTooLargeException
import io.github.matthewjones372.gannet.Headers
import io.github.matthewjones372.gannet.HttpVersion
import io.github.matthewjones372.gannet.MalformedBodyException
import java.math.BigInteger
import kotlin.random.Random

/**
 * The limits and read pattern a fuzz input asks for, from its first [SIZE] bytes, and the input itself after them. A
 * zero byte keeps a setting at the server's default, so a seed starting with [SIZE] zeros is a request as sent.
 */
internal class Knobs(private val data: ByteArray) {
    private fun at(i: Int): Int = if (i < data.size) data[i].toInt() and 0xFF else 0

    val maxLine: Int = at(0).let { if (it == 0) 8192 else it * 4 }
    val maxHead: Int = at(1).let { if (it == 0) 16384 else maxOf(maxLine, it * 16) }
    val maxFields: Int = at(2).let { if (it == 0) 100 else it }
    val maxBody: Long = at(3).let { if (it == 0) Long.MAX_VALUE else it * 8L }

    /** Zero hands the whole input over in one read; anything else seeds reads of 1 to 64 bytes. */
    val cuts: IntArray = at(4).let { seed ->
        val random = Random(seed)
        if (seed == 0) intArrayOf(Int.MAX_VALUE) else IntArray(32) { 1 + random.nextInt(64) }
    }

    /** The carrier's lent buffer: small, so reads compact it and outgrow it. */
    val lent: Int = at(5).let { if (it == 0) 16 * 1024 else 8 + it % 64 }

    val input: ByteArray = if (data.size > SIZE) data.copyOfRange(SIZE, data.size) else ByteArray(0)

    companion object {
        const val SIZE = 6

        /** A seed for [input] at the defaults. */
        fun seed(input: ByteArray): ByteArray = ByteArray(SIZE) + input
    }
}

/**
 * An [Inbound] over [input] in memory, handed over in reads of the sizes [cuts] gives in turn, into a [lent] buffer.
 * An odd-sized read parks first, so what is held moves out of the lent buffer as it does before a real park.
 */
internal class MemoryInbound(
    private val input: ByteArray,
    private val cuts: IntArray = intArrayOf(Int.MAX_VALUE),
    lent: Int = 16 * 1024,
    override val maxHeaderBytes: Int = 16 * 1024,
) : Inbound(ByteArray(lent)) {
    private var delivered = 0
    private var reads = 0
    var continues = 0
        private set

    /** Bytes of [input] parsed so far: handed over and no longer buffered. */
    val consumed: Int get() = delivered - buffered()

    override val ending: Boolean get() = delivered == input.size

    override fun fillHead(idle: Boolean): Boolean = fill()

    override fun fillMore(): Boolean = fill()

    override fun sendContinue() {
        continues++
    }

    private fun fill(): Boolean {
        if (delivered == input.size) return false
        val piece = cuts[reads++ % cuts.size]
        if (piece % 2 == 1) keepRest()
        makeRoom()
        val n = minOf(piece, buf.size - end, input.size - delivered)
        check(n > 0) { "makeRoom left no room" }
        System.arraycopy(input, delivered, buf, end, n)
        end += n
        delivered += n
        return true
    }
}

/** A chunked body read to its end through [BodyReader], or how it failed, and how much of the input it took. */
internal data class Decoded(val body: List<Byte>, val failure: String?, val consumed: Int)

/** Reads a body framed as [framing] off [inbound], in reads sized by [cuts], within [limit]. */
internal fun readBody(inbound: MemoryInbound, framing: Framing, limit: Long, cuts: IntArray): Decoded =
    readBody(BodyReader(inbound, head(framing)), inbound, limit, cuts)

/** Reads [reader]'s body off [inbound], in reads sized by [cuts], within [limit]. */
internal fun readBody(reader: BodyReader, inbound: MemoryInbound, limit: Long, cuts: IntArray): Decoded {
    val body = ArrayList<Byte>()
    val into = ByteArray(64)
    var reads = 0
    val failure = try {
        while (true) {
            // Every read takes input or ends the body, so the input bounds how many there can be.
            check(reads++ <= 4L * inbound.consumed + 64) { "The body reader is not making progress" }
            val want = minOf(into.size, cuts[reads % cuts.size].coerceAtLeast(1))
            val n = reader.read(into, 0, want, limit)
            if (n < 0) break
            check(n in 1..want) { "A read returned $n of $want" }
            for (i in 0 until n) body += into[i]
        }
        null
    } catch (e: MalformedBodyException) {
        "malformed"
    } catch (e: BodyTooLargeException) {
        "too large"
    }
    check(body.size <= inbound.consumed) { "More body than input" }
    if (failure == null) {
        check(reader.done) { "The body ended without the reader knowing" }
        check(!reader.mustClose(0)) { "A body read whole still closes the connection" }
    }
    return Decoded(body, failure, inbound.consumed)
}

private fun head(framing: Framing) =
    Head("POST", "/", "/", null, HttpVersion.HTTP_1_1, Headers(emptyList()), framing, true, false)

/**
 * RFC 9112's chunked coding decoded from [b] at once, written independently of [BodyReader] and held to the same
 * limits: a chunk size line of at most [maxSizeLine] bytes before its LF, trailers within [maxTrailer], a body within
 * [limit], and no chunk declared past what the reader can count.
 */
internal fun referenceChunked(b: ByteArray, limit: Long, maxTrailer: Int, maxSizeLine: Int = 1024): Decoded {
    val reference = ReferenceChunked(b, limit, maxTrailer, maxSizeLine)
    return try {
        reference.decode()
    } catch (stop: Stop) {
        Decoded(reference.body, stop.failure, 0)
    }
}

private class Stop(val failure: String) : RuntimeException(failure, null, false, false)

private class ReferenceChunked(val b: ByteArray, val limit: Long, val maxTrailer: Int, val maxSizeLine: Int) {
    val body = ArrayList<Byte>()
    private val text = String(b, Charsets.ISO_8859_1)
    private var at = 0

    fun decode(): Decoded {
        while (chunk()) continue
        var read = 0
        while (true) {
            val line = line(maxTrailer - read)
            read += line.length + 2
            if (line.isEmpty()) return Decoded(body, null, at)
            ensure(FIELD_LINE.matches(line))
        }
    }

    /** One chunk; false at the last. */
    private fun chunk(): Boolean {
        val match = SIZE_LINE.matchEntire(line(maxSizeLine))
        ensure(match != null)
        val size = BigInteger(match!!.groupValues[1], 16)
        ensure(size.shiftRight(4) <= BigInteger.ONE.shiftLeft(56))
        if (size.signum() == 0) return false
        val available = minOf(size.toLong(), (b.size - at).toLong()).toInt()
        ensure(body.size + available <= limit, "too large")
        ensure(available.toLong() == size.toLong())
        for (i in 0 until available) body += b[at + i]
        at += available
        ensure(at + 1 < b.size && b[at] == CR && b[at + 1] == LF)
        at += 2
        return true
    }

    private fun ensure(ok: Boolean, failure: String = "malformed") {
        if (!ok) throw Stop(failure)
    }

    /** The next line without its CR LF, which it must end with, within [max] bytes before the LF. */
    private fun line(max: Int): String {
        val lf = text.indexOf('\n', at)
        ensure(lf >= 0 && lf - at <= max)
        ensure(lf > at && b[lf - 1] == CR)
        return text.substring(at, lf - 1).also { at = lf + 1 }
    }
}

private const val TOKEN = "[!#$%&'*+\\-.^_`|~0-9A-Za-z]+"
private const val QUOTED = "\"(?:[\\t \\x21\\x23-\\x5B\\x5D-\\x7E\\x80-\\xFF]|\\\\[\\t \\x21-\\x7E\\x80-\\xFF])*\""
private val SIZE_LINE = Regex("([0-9A-Fa-f]+)(?:[ \\t]*;[ \\t]*$TOKEN(?:[ \\t]*=[ \\t]*(?:$TOKEN|$QUOTED))?)*")
private val FIELD_LINE = Regex("$TOKEN:[\\t\\x20-\\x7E\\x80-\\xFF]*")

/** [head] written back out as a request head, which must parse to the same head. */
internal fun serialize(head: Head): ByteArray = buildString {
    append(head.method).append(' ').append(head.target).append(' ')
    append(if (head.version == HttpVersion.HTTP_1_0) "HTTP/1.0" else "HTTP/1.1").append("\r\n")
    for ((name, value) in head.headers.fields) append(name).append(": ").append(value).append("\r\n")
    append("\r\n")
}.toByteArray(Charsets.UTF_8)

/** What a head says, compared field by field; a [Head] has no equality of its own. */
internal fun describe(head: Head): String {
    val framing = when (val f = head.framing) {
        Framing.None -> "none"
        Framing.Chunked -> "chunked"
        is Framing.Fixed -> "fixed ${f.length}"
    }
    return "${head.method} ${head.target} path=${head.path} query=${head.query} ${head.version} " +
        "${head.headers.fields} $framing keepAlive=${head.keepAlive} continue=${head.expectContinue}"
}
