package io.github.matthewjones372.gannet.runtime

import io.github.matthewjones372.gannet.GannetTls
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLEngineResult.HandshakeStatus
import javax.net.ssl.SSLEngineResult.Status
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException

/**
 * The bytes of one connection as its fiber reads and writes them: [Io]'s calls on a plain connection, or the same
 * calls with TLS between them and the socket. Only [awaitData], [awaitSend] and [awaitSendBelow] park, as with [Io],
 * and a connection calls [beforePark] before any of them.
 */
internal interface Transport {
    /** Runs the handshake as far as it can without waiting; true once it is done, false when it needs [awaitData]. */
    fun handshake(): Boolean

    /** Bytes read into [into] at [at], at most [max], without waiting: zero when none are ready, -1 at the end. */
    fun readNow(into: ByteArray, at: Int, max: Int): Int

    /** Parks until [readNow] or [handshake] may make progress. */
    fun awaitData()

    /** Queues [length] bytes of [bytes] from [offset] without waiting; [bytes] is free again once this returns. */
    fun write(bytes: ByteArray, offset: Int, length: Int)

    /** Parks until everything written has been taken by the kernel. */
    fun awaitSend() = Io.awaitSend()

    /** Parks until no more than [limit] written bytes are still waiting for the kernel. */
    fun awaitSendBelow(limit: Int) = Io.awaitSendBelow(limit)

    /** The fiber is about to park: nothing of this connection may stay in a buffer the carrier lends out. */
    fun beforePark()

    /** Says the connection is ending, without waiting: TLS queues its close_notify. */
    fun close()

    /** The server is forcing the connection closed: whatever the fiber waits for, it should stop waiting. */
    fun abandon()
}

/** A plain TCP connection: [Io] as it is. */
internal object Plain : Transport {
    override fun handshake() = true

    override fun readNow(into: ByteArray, at: Int, max: Int) = Io.readNow(into, at, max)

    override fun awaitData() = Io.awaitData()

    override fun write(bytes: ByteArray, offset: Int, length: Int) = Io.write(bytes, offset, length)

    override fun beforePark() = Unit

    override fun close() = Unit

    override fun abandon() = Unit
}

/**
 * What every TLS connection on one carrier shares: buffers used only between two parks, so whichever connection is
 * running may use them. [netIn] is the exception, lent to one connection ([holder]) until it parks or another takes
 * it, because unread ciphertext must outlive a single call.
 */
internal class TlsShared(val tls: GannetTls) {
    private val sizes = tls.engine().session
    val packetSize: Int = sizes.packetBufferSize
    val appSize: Int = sizes.applicationBufferSize

    var netIn: ByteBuffer = ByteBuffer.allocate(packetSize).flip()
    var holder: TlsTransport? = null

    /** Ciphertext on its way to [Io.write], which copies it; room for several records, written once full. */
    var netOut: ByteBuffer = ByteBuffer.allocate(packetSize * OUT_RECORDS)

    /** Plaintext unwrapped when the reader's own room is too small for a whole record. */
    var appIn: ByteBuffer = ByteBuffer.allocate(appSize)

    private companion object {
        const val OUT_RECORDS = 4
    }
}

private val EMPTY: ByteBuffer = ByteBuffer.allocate(0)

/** [TlsTransport]'s unwrap answers besides a length: the handshake's state changed, so the caller looks again. */
private const val AGAIN = Int.MIN_VALUE

/** Within an unwrap: go round again. */
private const val NEXT = Int.MIN_VALUE + 1

/** Within an unwrap: the record is incomplete, so read more ciphertext. */
private const val UNDERFLOW = Int.MIN_VALUE + 2

/**
 * One TLS connection over [Io], in its fiber. Ciphertext comes from [Io.readNow] into the carrier's lent
 * [TlsShared.netIn] and is unwrapped straight into the reader's array when a whole record fits there; plaintext
 * writes are wrapped into [TlsShared.netOut] and handed to [Io.write], so writing never parks and [awaitSend] is
 * still the backpressure.
 *
 * The handshake runs inside [handshake] and [readNow]: what the engine wants written is written at once, and its
 * delegated tasks run inline, or, with [GannetTls.handshakeExecutor], on that executor while [awaitData] parks.
 */
internal class TlsTransport(private val engine: SSLEngine, private val shared: TlsShared) : Transport {
    private val carrier = Carrier.current()
    private val fiber = Io.fiber()
    private val executor: Executor? = shared.tls.handshakeExecutor

    /** Ciphertext kept across a park, in read mode; used in place of the lent buffer while it holds any. */
    private var own: ByteBuffer? = null

    /** Plaintext unwrapped but not yet read, in read mode. */
    private var pending: ByteBuffer? = null
    private var inboundDone = false
    private var netEof = false

    // Set while the delegated tasks run on [executor]; cleared on the carrier when they are done.
    private var tasksRunning = false
    private var abandoned = false

    init {
        engine.beginHandshake()
    }

    override fun handshake(): Boolean = failing { driveHandshake() }

    private fun driveHandshake(): Boolean {
        while (true) {
            when (engine.handshakeStatus) {
                HandshakeStatus.NOT_HANDSHAKING, HandshakeStatus.FINISHED -> return true

                HandshakeStatus.NEED_WRAP -> wrapHandshake()

                HandshakeStatus.NEED_TASK -> if (!runTasksInline()) return false

                else -> {
                    val n = unwrapInto(null, 0, 0)
                    if (n == 0) return false
                    if (n == -1) throw SSLHandshakeException("The peer closed the connection during the handshake")
                }
            }
        }
    }

    override fun readNow(into: ByteArray, at: Int, max: Int): Int {
        if (pending?.hasRemaining() == true) return takePending(into, at, max)
        if (inboundDone) return -1
        return try {
            failing { driveRead(into, at, max) }
        } catch (e: SSLException) {
            // A broken record or a fatal alert from the peer: the connection is over, as if it had closed.
            log(e)
            inboundDone = true
            -1
        }
    }

    private fun driveRead(into: ByteArray, at: Int, max: Int): Int {
        while (true) {
            when (engine.handshakeStatus) {
                HandshakeStatus.NEED_WRAP -> wrapHandshake()

                HandshakeStatus.NEED_TASK -> if (!runTasksInline()) return 0

                else -> {
                    val n = unwrapInto(into, at, max)
                    if (n != AGAIN) return n
                }
            }
        }
    }

    /**
     * Unwraps records until one yields plaintext, which goes into [into] when it is given and has room for a whole
     * record, else into [pending]; reads more ciphertext whenever it runs out. The plaintext's length (in [into], or
     * held), zero when no more ciphertext has arrived, -1 at the end of the stream, or [AGAIN] when the handshake
     * moved on.
     */
    private fun unwrapInto(into: ByteArray?, at: Int, max: Int): Int {
        var step = NEXT
        while (step == NEXT) {
            val net = input()
            step = if (net.hasRemaining()) unwrapRecord(net, into, at, max) else UNDERFLOW
            if (step == UNDERFLOW) {
                step = when (receive()) {
                    0 -> 0
                    -1 -> endOfStream()
                    else -> NEXT
                }
            }
        }
        return step
    }

    /** One unwrap from [net]: [unwrapInto]'s answer, or [NEXT] to go round again, or [UNDERFLOW] to read more. */
    private fun unwrapRecord(net: ByteBuffer, into: ByteArray?, at: Int, max: Int): Int {
        val direct = into != null && max > 0
        val dst = if (direct) ByteBuffer.wrap(into, at, max) else shared.appIn.clear()
        val result = engine.unwrap(net, dst)
        return when (result.status) {
            Status.OK -> when {
                result.bytesProduced() > 0 -> if (direct) result.bytesProduced() else hold()
                engine.handshakeStatus != HandshakeStatus.NOT_HANDSHAKING -> AGAIN
                result.bytesConsumed() > 0 -> NEXT
                else -> UNDERFLOW
            }

            Status.CLOSED -> -1.also { inboundDone = true }

            Status.BUFFER_OVERFLOW -> if (into != null && direct) {
                // The reader's room is smaller than a record: unwrap aside, and hand over what fits.
                unwrapInto(null, 0, 0).let { if (it > 0) takePending(into, at, max) else it }
            } else {
                shared.appIn = ByteBuffer.allocate(maxOf(engine.session.applicationBufferSize, dst.capacity() * 2))
                NEXT
            }

            Status.BUFFER_UNDERFLOW -> UNDERFLOW
        }
    }

    /** The peer closed TCP without a close_notify. */
    private fun endOfStream(): Int {
        inboundDone = true
        try {
            engine.closeInbound()
        } catch (_: SSLException) {
            // The engine's complaint that no close_notify came, which a truncation would also look like. A body cut
            // short is caught by its framing; a peer simply hanging up is ordinary.
        }
        return -1
    }

    /** Appends what [TlsShared.appIn] holds to [pending]; how much that now holds. */
    private fun hold(): Int {
        val app = shared.appIn.flip()
        val old = pending
        val held = if (old != null && old.capacity() - old.remaining() >= app.remaining()) {
            old.compact()
        } else {
            ByteBuffer.allocate(maxOf(app.capacity(), (old?.remaining() ?: 0) + app.remaining())).also { fresh ->
                old?.let { fresh.put(it) }
            }
        }
        held.put(app).flip()
        pending = held
        return held.remaining()
    }

    private fun takePending(into: ByteArray, at: Int, max: Int): Int {
        val held = pending ?: return 0
        val n = minOf(max, held.remaining())
        held.get(into, at, n)
        return n
    }

    /** The ciphertext not yet unwrapped, in read mode: this connection's own while it has some, else the lent one. */
    private fun input(): ByteBuffer {
        own?.let { if (it.hasRemaining()) return it }
        if (shared.holder !== this) {
            shared.netIn.clear().flip()
            shared.holder = this
        }
        return shared.netIn
    }

    /** Reads what has arrived into [input]: bytes read, zero when nothing has, -1 at the end of the stream. */
    private fun receive(): Int {
        if (netEof) return -1
        var net = input()
        net.compact()
        if (!net.hasRemaining()) net = grow(net)
        var total = 0
        while (net.hasRemaining()) {
            val n = Io.readNow(net.array(), net.arrayOffset() + net.position(), net.remaining())
            if (n < 0) netEof = true
            if (n <= 0) break
            net.position(net.position() + n)
            total += n
        }
        net.flip()
        return if (total == 0 && netEof) -1 else total
    }

    /** [net], in write mode and full, replaced by one twice its size holding the same bytes. */
    private fun grow(net: ByteBuffer): ByteBuffer {
        val bigger = ByteBuffer.allocate(maxOf(net.capacity() * 2, engine.session.packetBufferSize))
        bigger.put(net.flip())
        if (net === own) own = bigger else shared.netIn = bigger
        return bigger
    }

    override fun beforePark() {
        if (shared.holder !== this) return
        shared.holder = null
        val lent = shared.netIn
        if (!lent.hasRemaining()) return
        val keep = own?.takeIf { it.capacity() >= lent.remaining() } ?: ByteBuffer.allocate(lent.capacity())
        keep.clear()
        keep.put(lent).flip()
        lent.clear().flip()
        own = keep
    }

    override fun awaitData() {
        if (engine.handshakeStatus == HandshakeStatus.NEED_TASK && executor != null) offloadTasks(executor)
        else Io.awaitData()
    }

    /** Runs the delegated tasks on [executor], parked until they are done; inline if it refuses them. */
    private fun offloadTasks(executor: Executor) {
        val tasks = generateSequence { engine.delegatedTask }.toList()
        tasksRunning = true
        try {
            executor.execute {
                try {
                    // A task's own failure is kept by the engine and thrown by the next wrap or unwrap.
                    tasks.forEach { it.run() }
                } finally {
                    carrier.execute(
                        object : Task {
                            override fun run() {
                                tasksRunning = false
                                carrier.wake(fiber)
                            }

                            override fun reject() = Unit
                        },
                    )
                }
            }
        } catch (_: RejectedExecutionException) {
            tasksRunning = false
            tasks.forEach { it.run() }
        }
        // Other wakes (mail, a stop) may come first; only the tasks' end, or being abandoned, ends the wait.
        while (tasksRunning && !abandoned) Io.parkForWake()
    }

    override fun abandon() {
        abandoned = true
    }

    /** Runs the delegated tasks on the carrier, unless they belong on the executor; true when they have run. */
    private fun runTasksInline(): Boolean {
        if (executor != null) return false
        runTasks()
        return true
    }

    private fun runTasks() {
        while (true) (engine.delegatedTask ?: return).run()
    }

    override fun write(bytes: ByteArray, offset: Int, length: Int) {
        if (length == 0) return Io.write(bytes, offset, 0)
        val src = ByteBuffer.wrap(bytes, offset, length)
        var out = shared.netOut.clear()
        while (src.hasRemaining()) {
            if (out.remaining() < shared.packetSize) send(out)
            val result = try {
                engine.wrap(src, out)
            } catch (e: SSLException) {
                throw IOException("TLS failed while writing", e)
            }
            when (result.status) {
                Status.OK -> if (result.bytesConsumed() == 0 && result.bytesProduced() == 0) stalled()
                Status.CLOSED -> throw IOException("The TLS connection is closed")
                Status.BUFFER_OVERFLOW -> if (out.position() == 0) out = grown(out) else send(out)
                Status.BUFFER_UNDERFLOW -> error("A wrap cannot underflow")
            }
        }
        send(out)
    }

    /** A wrap that moved nothing: the engine wants a task run, or a renegotiation the server cannot wait for. */
    private fun stalled() {
        if (engine.handshakeStatus == HandshakeStatus.NEED_TASK) runTasks()
        else throw IOException("TLS renegotiation is not supported")
    }

    private fun grown(out: ByteBuffer): ByteBuffer =
        ByteBuffer.allocate(maxOf(out.capacity() * 2, engine.session.packetBufferSize)).also { shared.netOut = it }

    /** What the engine produces with no application data (handshake messages, alerts, close_notify), written. */
    private fun wrapHandshake() {
        var out = shared.netOut.clear()
        repeat(MAX_ROUNDS) {
            val result = engine.wrap(EMPTY, out)
            if (result.status == Status.BUFFER_OVERFLOW) {
                if (out.position() == 0) out = grown(out) else send(out)
            } else if (result.status == Status.CLOSED || engine.handshakeStatus != HandshakeStatus.NEED_WRAP) {
                return send(out)
            }
        }
        send(out)
    }

    /** Hands the ciphertext in [out] to [Io.write], which copies it, and empties [out] for the next records. */
    private fun send(out: ByteBuffer) {
        if (out.position() > 0) Io.write(out.array(), out.arrayOffset(), out.position())
        out.clear()
    }

    override fun close() {
        if (engine.isOutboundDone) return
        engine.closeOutbound()
        try {
            wrapHandshake()
        } catch (e: SSLException) {
            log(e)
        }
    }

    /** Runs [step]; if the engine fails, writes the alert it has queued for the peer before throwing on. */
    private inline fun <T> failing(step: () -> T): T = try {
        step()
    } catch (e: SSLException) {
        try {
            wrapHandshake()
        } catch (_: IOException) {
            // The engine has nothing more to say, or no connection left to say it on. SSLException is one of these.
        }
        throw e
    }

    private fun log(e: SSLException) = tlsLogger.log(System.Logger.Level.DEBUG, "TLS connection failed: {0}", e.message)

    private companion object {
        const val MAX_ROUNDS = 16
    }
}

private val tlsLogger = System.getLogger("io.github.matthewjones372.gannet")
