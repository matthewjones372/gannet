package io.github.matthewjones372.gannet.runtime

import io.github.matthewjones372.gannet.MalformedBodyException

/**
 * A connection's input: what has been read and not yet consumed, in [buf] from [start] to [end], and the reads that
 * fetch more. [buf] is the carrier's [lent] buffer while nothing is held across a park, else one of its own. A
 * [Connection] reads from its socket; the tests and fuzz targets read from memory through the same buffering and
 * parsing.
 */
internal abstract class Inbound(private val lent: ByteArray) {
    protected var buf = lent
    protected var start = 0
    protected var end = 0
    private var own: ByteArray? = null

    /** The trailer fields of a chunked body are held to this, as a head is. */
    abstract val maxHeaderBytes: Int

    /** True when nothing more will be read for a new request: the stream ended, or the server is stopping. */
    protected abstract val ending: Boolean

    /** Reads at least one more byte of a head, [idle] when nothing of it has arrived yet; false at the end. */
    protected abstract fun fillHead(idle: Boolean): Boolean

    /** Reads at least one more byte of a body; false at the end of the stream. */
    protected abstract fun fillMore(): Boolean

    /** Asks the client for a body it is holding back for `100-continue`. */
    abstract fun sendContinue()

    /** The buffer [position] indexes into; read it again after anything that may read more. */
    val bytes: ByteArray get() = buf

    val position: Int get() = start

    fun buffered(): Int = end - start

    fun skipTo(at: Int) {
        start = at
    }

    fun take(into: ByteArray, offset: Int, length: Int) {
        System.arraycopy(buf, start, into, offset, length)
        start += length
    }

    /** Reads more of a body, which has to be there: the connection ending first breaks the body. */
    fun fillBody() {
        if (!fillMore()) throw MalformedBodyException("The connection ended before the body did")
    }

    /**
     * The index of the line feed ending the line at [position], reading more as needed; -1 when more than [max] bytes
     * come before it. An ended connection breaks the body the line belongs to.
     */
    fun lineEnd(max: Int): Int {
        while (true) {
            val window = if (max >= end - start) end else start + max + 1
            val lf = Scan.indexOf(buf, LF.toInt(), start, window)
            if (lf >= 0) return lf
            if (end - start > max) return -1
            fillBody()
        }
    }

    /**
     * The next request head, or a refusal to answer before closing; null when the connection ends between requests.
     * At most [MAX_BLANK] bytes of empty lines may come before a request.
     */
    fun nextHead(maxLine: Int, maxHead: Int, maxFields: Int): Parse? {
        var blank = 0
        while (true) {
            val skip = blankLines(buf, start, end)
            start += skip
            blank += skip
            val parsed = when {
                blank > MAX_BLANK -> Parse.Refused(BAD_REQUEST, "Too many empty lines before the request")
                start == end && ending -> return null
                else -> parseHead(buf, start, end, maxLine, maxHead, maxFields)
            }
            if (parsed is Parse.Complete) start = parsed.end
            if (parsed != Parse.Incomplete) return parsed
            if (!fillHead(idle = start == end)) return null
        }
    }

    /** Room after [end]: the lent buffer again when nothing is held, else compacted, else grown into one of its own. */
    protected fun makeRoom() {
        if (start == end) {
            start = 0
            end = 0
            buf = lent
        } else if (end == buf.size) {
            if (start > 0) {
                System.arraycopy(buf, start, buf, 0, end - start)
                end -= start
                start = 0
            } else {
                buf = buf.copyOf(buf.size * 2).also { own = it }
            }
        }
    }

    /** Moves bytes still unread out of the lent buffer before another connection reads into it. */
    protected fun keepRest() {
        if (buf !== lent || start == end) return
        val rest = end - start
        val mine = own?.takeIf { it.size >= rest } ?: ByteArray(maxOf(MIN_OWN, Integer.highestOneBit(rest - 1) shl 1))
        System.arraycopy(buf, start, mine, 0, rest)
        own = mine
        buf = mine
        start = 0
        end = rest
    }

    private companion object {
        const val MAX_BLANK = 256
        const val MIN_OWN = 4 * 1024
    }
}
