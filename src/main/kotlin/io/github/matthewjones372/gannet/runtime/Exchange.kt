package io.github.matthewjones372.gannet.runtime

import io.github.matthewjones372.gannet.BodyTooLargeException
import io.github.matthewjones372.gannet.HttpExchange
import io.github.matthewjones372.gannet.HttpRequest
import io.github.matthewjones372.gannet.HttpVersion
import io.github.matthewjones372.gannet.MalformedBodyException
import io.github.matthewjones372.gannet.RequestBody
import io.github.matthewjones372.gannet.ResponseStream
import java.io.IOException
import java.net.SocketTimeoutException
import java.time.Duration
import java.util.Objects
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

internal enum class Phase { OPEN, STREAMING, DONE }

/**
 * One request's exchange. Its public calls run anywhere; everything below them that touches the connection runs in
 * the connection's fiber, called directly when the caller is that fiber and through a [Mail] when it is not.
 */
internal class UringExchange(val conn: Connection, val head: Head) : HttpExchange {
    private val answered = AtomicBoolean()

    @Volatile
    var detached = false
        private set

    // From here down, the fiber's state.
    var phase = Phase.OPEN
        private set
    var closeAfter = !head.keepAlive
        private set
    private var chunked = false
    private var bodyless = false
    private val isHead = head.method == "HEAD"

    val reader = BodyReader(conn, head)

    override val request = HttpRequest(
        head.method, head.target, head.path, head.query, head.version, head.headers, Body(this),
    )

    override val onCarrier: Boolean get() = conn.onFiber()

    override fun respond(status: Int, headers: List<Pair<String, String>>, body: ByteArray) {
        checkReply(status, headers)
        claim()
        if (onCarrier) writeStrict(status, headers, body) else conn.post(Mail.Strict(this, status, headers, body))
    }

    override fun stream(status: Int, headers: List<Pair<String, String>>): ResponseStream {
        checkReply(status, headers)
        claim()
        if (onCarrier) startStream(status, headers) else conn.post(Mail.Start(this, status, headers))
        return Stream(this)
    }

    override fun detach() {
        detached = true
    }

    override fun offload(executor: Executor, work: (HttpExchange) -> Unit) {
        detached = true
        conn.offload(this, executor, work)
    }

    private fun checkReply(status: Int, headers: List<Pair<String, String>>) {
        require(status in OK..MAX_STATUS) { "A reply's status is 200 to 999, not $status" }
        checkFields(headers)
    }

    private fun claim() = check(answered.compareAndSet(false, true)) { "This exchange has already been answered" }

    fun writeStrict(status: Int, headers: List<Pair<String, String>>, body: ByteArray) {
        val keepLength = body.isEmpty() && (isHead || status == NOT_MODIFIED) &&
            headers.any { it.first.equals("content-length", ignoreCase = true) }
        startHead(status, headers, keepLength)
        if (bodyAllowed(status) && !keepLength) conn.out.put("Content-Length: ${body.size}\r\n")
        endHead()
        if (!isHead && bodyAllowed(status)) conn.emit(body, 0, body.size)
        phase = Phase.DONE
    }

    fun startStream(status: Int, headers: List<Pair<String, String>>) {
        bodyless = isHead || !bodyAllowed(status)
        chunked = !bodyless && head.version == HttpVersion.HTTP_1_1
        // An HTTP/1.0 client knows the body has ended only when the connection does.
        if (!bodyless && !chunked) closeAfter = true
        startHead(status, headers, keepLength = false)
        if (chunked) conn.out.put("Transfer-Encoding: chunked\r\n")
        endHead()
        phase = Phase.STREAMING
        conn.flush()
    }

    private fun startHead(status: Int, headers: List<Pair<String, String>>, keepLength: Boolean) {
        if (closeWanted(headers) || conn.closeDecided(this)) closeAfter = true
        val out = conn.out
        out.put("HTTP/1.1 $status ${reason(status)}\r\n")
        for ((name, value) in headers) {
            val lower = name.lowercase()
            if (lower in REPLACED || (lower == "content-length" && !keepLength)) continue
            out.put("$name: ")
            out.put(value.toByteArray())
            out.crlf()
        }
        out.put(Dates.current())
    }

    private fun endHead() {
        val out = conn.out
        if (closeAfter) out.put("Connection: close\r\n")
        else if (head.version == HttpVersion.HTTP_1_0) out.put("Connection: keep-alive\r\n")
        out.crlf()
    }

    private fun closeWanted(headers: List<Pair<String, String>>): Boolean =
        "close" in tokens(headers.filter { it.first.equals("connection", ignoreCase = true) }.map { it.second })

    fun writeChunk(bytes: ByteArray, offset: Int, length: Int) {
        check(phase == Phase.STREAMING) { "The reply stream has ended" }
        if (length == 0 || bodyless) return
        val out = conn.out
        if (chunked) out.putHex(length).also { out.crlf() }
        conn.emit(bytes, offset, length)
        if (chunked) out.crlf()
        conn.sendStreamed()
    }

    /** Parks until the kernel has taken everything the stream has written so far. */
    fun flushStream() {
        check(phase == Phase.STREAMING) { "The reply stream has ended" }
        conn.flush()
        conn.awaitSent()
    }

    fun endStream() {
        if (phase != Phase.STREAMING) return
        if (chunked) conn.out.put(LAST_CHUNK)
        phase = Phase.DONE
        conn.flush()
    }

    /** Ends a started reply short, so the client sees it fail: no last chunk, and the connection closes. */
    fun abortStream() {
        if (phase == Phase.DONE) return
        phase = Phase.DONE
        closeAfter = true
    }

    /** The handler failed with [t]: answered with a status if nothing has been sent, else the reply cut short. */
    fun fail(t: Throwable) {
        when (phase) {
            Phase.OPEN -> if (answered.compareAndSet(false, true)) writeFailure(t)
            Phase.STREAMING -> abortStream()
            Phase.DONE -> Unit
        }
    }

    /** The handler is finished with the exchange: a stream left open is ended, and no answer at all is a 500. */
    fun settle() {
        when (phase) {
            Phase.OPEN ->
                if (answered.compareAndSet(false, true)) {
                    writeFailure(IllegalStateException("The handler returned without answering ${head.target}"))
                }

            Phase.STREAMING -> endStream()

            Phase.DONE -> Unit
        }
    }

    private fun writeFailure(t: Throwable) {
        val status = when (t) {
            is BodyTooLargeException -> CONTENT_TOO_LARGE
            is MalformedBodyException -> BAD_REQUEST
            else -> SERVER_ERROR.also { conn.log(t) }
        }
        if (status == BAD_REQUEST) closeAfter = true
        writeStrict(status, emptyList(), HttpExchange.EMPTY_BODY)
    }

    private companion object {
        const val MAX_STATUS = 999
        val REPLACED = setOf("transfer-encoding", "date", "connection")
    }
}

/** The body as the fiber reads it off the connection: framing, limits, `100-continue` and the drain. */
internal class BodyReader(private val input: Inbound, private val head: Head) {
    private enum class Chunk { SIZE, DATA, DATA_END, TRAILERS }

    private val framing = head.framing

    /** Set from any thread by [RequestBody.fail]. */
    @Volatile
    var failure: IOException? = null

    var done = framing == Framing.None
        private set
    private var broken = false
    private var remaining = (framing as? Framing.Fixed)?.length ?: 0L
    private var state = Chunk.SIZE
    private var total = 0L
    private var continueSent = false

    /** Up to [length] bytes into [into]; -1 at the end. Throws past [limit] bytes in all, and once the body failed. */
    fun read(into: ByteArray, offset: Int, length: Int, limit: Long): Int {
        failure?.let { throw it }
        return next(into, offset, length, limit)
    }

    private fun next(into: ByteArray, offset: Int, length: Int, limit: Long): Int {
        if (done) return -1
        if (length == 0) return 0
        try {
            askToContinue()
            val n = if (framing is Framing.Chunked) readChunked(into, offset, length)
            else readFixed(into, offset, length)
            if (n > 0) total += n
            if (total > limit) throw BodyTooLargeException(limit)
            return n
        } catch (e: BodyTooLargeException) {
            failure = e
            throw e
        } catch (e: IOException) {
            broken = true
            failure = e
            throw e
        }
    }

    /**
     * What is left of the body, read straight from the connection's input into the array returned, with no buffer in
     * between: of exactly the size left when the length is known, else grown as the bytes arrive and trimmed once.
     */
    fun readAll(limit: Long): ByteArray {
        failure?.let { throw it }
        if (done) return EMPTY
        // An array holds no more than this, whatever the caller allows.
        val most = minOf(limit, MAX_ARRAY)
        return if (framing is Framing.Fixed) readAllFixed(most) else readAllGrowing(most)
    }

    private fun readAllFixed(most: Long): ByteArray {
        if (total + remaining > most) throw BodyTooLargeException(most)
        // A declared length is trusted only so far before its bytes arrive; past that the array grows as they do,
        // never past what is left, so it ends exactly full.
        var all = ByteArray(minOf(remaining, FIRST_ARRAY).toInt())
        var at = 0
        while (!done) {
            if (at == all.size) all = all.copyOf(minOf(all.size * 2L, at + remaining).toInt())
            at += read(all, at, all.size - at, most)
        }
        return all
    }

    private fun readAllGrowing(most: Long): ByteArray {
        var all = ByteArray(minOf(FIRST_GROWING, most).toInt())
        var at = 0
        while (true) {
            if (at == all.size) {
                // Full at the limit: the body may end here, but [read] refuses one more byte as past the limit.
                if (at.toLong() == most) {
                    read(ByteArray(1), 0, 1, most)
                    return all
                }
                all = all.copyOf(minOf(all.size * 2L, most).toInt())
            }
            val n = read(all, at, all.size - at, most)
            if (n < 0) return if (at == all.size) all else all.copyOf(at)
            at += n
        }
    }

    /** At most [max] bytes of the body as a new array, sized to what a known length has left; null at the end. */
    fun readPiece(max: Int, limit: Long): ByteArray? {
        val size = minOf(max, PIECE)
        val piece = ByteArray(if (framing is Framing.Fixed) minOf(size.toLong(), remaining).toInt() else size)
        val n = read(piece, 0, piece.size, limit)
        return if (n < 0) null else if (n == piece.size) piece else piece.copyOf(n)
    }

    private fun askToContinue() {
        if (!head.expectContinue || continueSent) return
        input.sendContinue()
        continueSent = true
    }

    private fun readFixed(into: ByteArray, offset: Int, length: Int): Int {
        if (input.buffered() == 0) input.fillBody()
        val n = minOf(length.toLong(), remaining, input.buffered().toLong()).toInt()
        input.take(into, offset, n)
        remaining -= n
        if (remaining == 0L) done = true
        return n
    }

    private fun readChunked(into: ByteArray, offset: Int, length: Int): Int {
        while (true) {
            when (state) {
                Chunk.SIZE -> chunkSize()
                Chunk.DATA -> return readFixed(into, offset, length).also { if (remaining == 0L) dataEnded() }
                Chunk.DATA_END -> chunkEnd()
                Chunk.TRAILERS -> if (trailers()) return -1
            }
        }
    }

    private fun dataEnded() {
        done = false
        state = Chunk.DATA_END
    }

    private fun chunkSize() {
        val lf = input.lineEnd(MAX_CHUNK_LINE)
        if (lf < 0) throw MalformedBodyException("A chunk size line is too long")
        val size = chunkSize(input.bytes, input.position, lf)
        if (size < 0) throw MalformedBodyException("Bad chunk size line")
        input.skipTo(lf + 1)
        remaining = size
        state = if (size == 0L) Chunk.TRAILERS else Chunk.DATA
    }

    private fun chunkEnd() {
        val lf = input.lineEnd(1)
        if (lf != input.position + 1 || input.bytes[input.position] != CR) {
            throw MalformedBodyException("A chunk does not end with CR LF")
        }
        input.skipTo(lf + 1)
        state = Chunk.SIZE
    }

    /** Reads trailer fields up to the empty line, checks them, and throws them away; true at that line. */
    private fun trailers(): Boolean {
        val max = input.maxHeaderBytes
        var read = 0
        while (true) {
            val lf = input.lineEnd(max - read)
            if (lf < 0) throw MalformedBodyException("The trailer fields are too long")
            val from = input.position
            val b = input.bytes
            if (lf == from || b[lf - 1] != CR) throw MalformedBodyException("A trailer line does not end with CR LF")
            input.skipTo(lf + 1)
            if (lf - 1 == from) break
            if (!isFieldLine(b, from, lf - 1)) throw MalformedBodyException("Bad trailer field")
            read += lf + 1 - from
        }
        done = true
        return true
    }

    /**
     * Reads and throws away what is left of the body, up to [budget] bytes; true when the connection can carry
     * another request afterwards.
     */
    fun finish(budget: Long): Boolean {
        if (done) return true
        if (mustClose(budget)) return false
        val scratch = ByteArray(if (framing is Framing.Fixed) minOf(remaining, PIECE.toLong()).toInt() else PIECE)
        val start = total
        return try {
            // Past a refusal for size, which leaves the framing intact, so the rest can still be read and dropped.
            while (next(scratch, 0, PIECE, Long.MAX_VALUE) >= 0) if (total - start > budget) return false
            true
        } catch (_: IOException) {
            false
        }
    }

    /** Whether the body's state already rules out keeping the connection, before anything else is read. */
    fun mustClose(budget: Long): Boolean = when {
        done -> false
        broken || (failure != null && failure !is BodyTooLargeException) -> true
        head.expectContinue && !continueSent -> true
        else -> framing is Framing.Fixed && remaining > budget
    }

    private companion object {
        const val PIECE = 16 * 1024
        const val MAX_ARRAY = Int.MAX_VALUE - 16L
        const val FIRST_ARRAY = 1L shl 20

        /** Where a body of unknown length starts, doubling from there: most bodies a handler reads whole are small. */
        const val FIRST_GROWING = 64L
        const val MAX_CHUNK_LINE = 1024
        val EMPTY = ByteArray(0)
    }
}

/** The request body as handlers see it: read in the fiber when called there, through mail from anywhere else. */
internal class Body(private val ex: UringExchange) : RequestBody {
    private var leftover: ByteArray? = null
    private var leftoverAt = 0

    @Volatile
    private var pending: Answer<*>? = null

    override val length: Long? = when (val framing = ex.head.framing) {
        Framing.None -> 0L
        is Framing.Fixed -> framing.length
        Framing.Chunked -> null
    }

    override fun readAll(): ByteArray = readAll(ex.conn.maxBodyBytes)

    override fun readAll(limit: Long): ByteArray {
        val rest = if (ex.onCarrier) ex.reader.readAll(limit) else ask(Mail.ReadAll(ex, limit, Answer()), null)
        val held = leftover ?: return rest
        leftover = null
        val kept = held.size - leftoverAt
        val all = ByteArray(kept + rest.size)
        System.arraycopy(held, leftoverAt, all, 0, kept)
        System.arraycopy(rest, 0, all, kept, rest.size)
        return all
    }

    override fun read(into: ByteArray, offset: Int, length: Int): Int = readWithin(into, offset, length, null)

    override fun read(into: ByteArray, offset: Int, length: Int, timeout: Duration): Int =
        readWithin(into, offset, length, timeout)

    private fun readWithin(into: ByteArray, offset: Int, length: Int, timeout: Duration?): Int {
        Objects.checkFromIndexSize(offset, length, into.size)
        if (ex.onCarrier) return ex.reader.read(into, offset, length, ex.conn.maxBodyBytes)
        if (length == 0) return 0
        ex.reader.failure?.let { throw it }
        val piece = leftover ?: ask(Mail.Read(ex, length, Answer()), timeout) ?: return -1
        val at = if (piece === leftover) leftoverAt else 0
        val n = minOf(length, piece.size - at)
        System.arraycopy(piece, at, into, offset, n)
        if (at + n == piece.size) leftover = null else leftover = piece.also { leftoverAt = at + n }
        return n
    }

    private fun <T> ask(mail: Mail.Asking<T>, timeout: Duration?): T {
        pending = mail.answer
        ex.conn.post(mail)
        return try {
            mail.answer.await(timeout)
        } catch (e: SocketTimeoutException) {
            fail(e)
            throw e
        } finally {
            pending = null
        }
    }

    override fun fail(cause: IOException) {
        ex.reader.failure = cause
        pending?.completeExceptionally(cause)
        if (!ex.onCarrier) ex.conn.interruptRead(ex)
    }
}

/**
 * A reply body being written, from the fiber directly or from another thread through mail.
 *
 * From another thread, a write below the watermark posts a copy of its bytes and returns without waiting for the
 * fiber; [posted] counts those bytes until the fiber has taken them. Past the watermark a write waits for the fiber,
 * which by then has taken everything posted before it and holds the write until the connection's unsent bytes are
 * back under the watermark, so a slow reader costs at most about twice the watermark. A posted write that fails is
 * kept in [failure] and thrown by the next call.
 */
internal class Stream(private val ex: UringExchange) : ResponseStream {
    @Volatile
    private var ended = false

    private val posted = AtomicLong()

    @Volatile
    private var failure: Exception? = null

    override fun write(bytes: ByteArray, offset: Int, length: Int) {
        Objects.checkFromIndexSize(offset, length, bytes.size)
        check(!ended) { "The reply stream has ended" }
        if (ex.onCarrier) return ex.writeChunk(bytes, offset, length)
        failure?.let { throw it }
        if (length == 0) return
        if (posted.get() + length <= ex.conn.watermark) {
            posted.addAndGet(length.toLong())
            ex.conn.post(Mail.Piece(ex, this, bytes.copyOfRange(offset, offset + length)))
        } else {
            ask(Mail.Chunk(ex, bytes, offset, length, Answer()))
        }
    }

    override fun flush() {
        check(!ended) { "The reply stream has ended" }
        if (ex.onCarrier) return ex.flushStream()
        ask(Mail.Flush(ex, Answer()))
    }

    override fun close() {
        if (ended) return
        ended = true
        if (ex.onCarrier) ex.endStream() else ask(Mail.End(ex, Answer()))
    }

    override fun abort() {
        if (ended) return
        ended = true
        if (ex.onCarrier) ex.abortStream() else ex.conn.post(Mail.Abort(ex))
    }

    /** The fiber has taken a posted piece of [length] bytes; [failed] is what writing it threw, if anything. */
    fun taken(length: Int, failed: Exception?) {
        posted.addAndGet(-length.toLong())
        if (failed != null && failure == null) failure = failed
    }

    private fun ask(mail: Mail.Asking<Unit>) {
        ex.conn.post(mail)
        mail.answer.await(null)
        failure?.let { throw it }
    }
}
