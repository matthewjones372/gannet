package io.github.matthewjones372.gannet.runtime

import io.github.matthewjones372.gannet.GannetConfig
import io.github.matthewjones372.gannet.HttpExchange
import io.github.matthewjones372.gannet.HttpHandler
import java.io.IOException
import java.util.concurrent.Executor
import javax.net.ssl.SSLException

/**
 * What every connection on one carrier shares. [read] is lent to whichever connection is reading, and [out] collects
 * whichever connection's replies until it flushes; both are safe because a connection hands them back before it
 * parks, and only one fiber runs on a carrier at a time. [tls] holds the TLS buffers lent the same way.
 */
internal class Shared(val config: GannetConfig, val handler: HttpHandler) {
    val read = ByteArray(READ_BUFFER)
    val out = Out(OUT_BUFFER)
    val idleNanos = config.idleTimeout.toNanos()
    val headNanos = config.headTimeout.toNanos()
    val bodyNanos = config.bodyTimeout.toNanos()
    val writeNanos = config.writeTimeout.toNanos()
    val handshakeNanos = config.handshakeTimeout.toNanos()

    /** Nanoseconds of extra body allowance each byte read earns; zero earns none. */
    val nanosPerBodyByte = if (config.minBodyRate == 0L) 0.0 else NANOS / config.minBodyRate
    val tls = config.tls?.let { TlsShared(it) }

    /** The transport for a connection starting in the calling fiber. */
    fun transport(): Transport = tls?.let { TlsTransport(it.tls.engine(), it) } ?: Plain

    private companion object {
        const val READ_BUFFER = 16 * 1024
        const val OUT_BUFFER = 16 * 1024
        const val NANOS = 1e9
    }
}

private val logger = System.getLogger("io.github.matthewjones372.gannet")

/**
 * One connection, run in its own fiber: read a head, hand the exchange to the handler, write what it answers, and
 * again while the connection stays open. Requests are answered in the order they arrived, so pipelining needs nothing
 * more; replies to requests already read are batched into one write.
 */
internal class Connection(private val fd: Int, private val shared: Shared) :
    Inbound(shared.read),
    Session {
    private val carrier = Carrier.current()
    private val fiber = Io.fiber()
    private val config = shared.config
    private val transport = shared.transport()
    val out = shared.out
    override val maxHeaderBytes = config.maxHeaderBytes
    val maxBodyBytes = config.maxBodyBytes
    val watermark = config.streamHighWatermark
    override val maxBuffered = config.maxBufferedBytes

    private var eof = false

    /** When the fiber started waiting on its peer, or zero when it is not. */
    private var waitingSince = 0L

    /** What the fiber waits on its peer for, which picks the timeout the carrier's timer holds it to. */
    private var waitingFor = Wait.IDLE

    /** Waiting for a request with nothing of one read yet, so stopping may close it at once. */
    private var idle = false

    /** When the connection was accepted, which the handshake's timeout counts from. */
    private val accepted = System.nanoTime()

    /** When the first byte of the head being read was seen, or zero between heads. */
    private var headSince = 0L

    /** The current body: time spent waiting for it, and bytes of it read, which earn more time. */
    private var bodyWaited = 0L
    private var bodyBytes = 0L

    /** While waiting to send: the fiber's sent bytes when last looked at, and since when they have not moved. */
    private var sentSeen = 0L
    private var sendStalledSince = 0L
    private var stopping = false
    private var aborted = false
    private var closed = false
    private val mailbox = ArrayDeque<Mail>()
    private var exchange: UringExchange? = null

    fun run() {
        Io.attach(this, fd)
        try {
            val shaken = try {
                handshake()
            } finally {
                carrier.endHandshake(fiber)
            }
            if (shaken) serve()
            // The replies collected so far go before the close_notify.
            flush()
            transport.close()
            // Waited for here rather than in the close, so a peer that stops reading meets the write timeout.
            awaitSent()
        } catch (_: IOException) {
            // The peer went away, a limit was hit or the server stopped: nothing is left to tell anyone, and nothing
            // queued is worth waiting for, so a send still in flight is failed rather than waited out.
            out.size = 0
            Io.shutdownAndWait()
        } finally {
            closed = true
            exchange = null
            failQueued()
        }
    }

    /**
     * Runs the TLS handshake, if there is one, waiting on the peer as for an idle connection; false when it failed,
     * which ends only this connection.
     */
    private fun handshake(): Boolean = try {
        while (!transport.handshake()) awaitPeer(Wait.HANDSHAKE) { transport.awaitData() }
        true
    } catch (e: SSLException) {
        // Scanners, old clients and mistrusted certificates all end here; none is the server's fault.
        logger.log(System.Logger.Level.DEBUG, "A TLS handshake failed: {0}", e.message)
        false
    }

    private fun serve() {
        while (true) {
            val head = nextHead() ?: return
            bodyWaited = 0L
            bodyBytes = 0L
            val ex = UringExchange(this, head)
            exchange = ex
            invoke(ex)
            if (!ex.detached) ex.settle()
            while (ex.phase != Phase.DONE) deliverNext()
            exchange = null
            failQueued()
            if (!ex.reader.finish(config.drainBytes) || ex.closeAfter || stopping) return
        }
    }

    private fun invoke(ex: UringExchange) {
        try {
            shared.handler.handle(ex)
        } catch (t: Throwable) {
            ex.fail(t)
        }
    }

    /** Runs [work] for [ex] on [executor], and tells the fiber how it ended. */
    fun offload(ex: UringExchange, executor: Executor, work: (HttpExchange) -> Unit) {
        executor.execute {
            val outcome = try {
                work(ex)
                Mail.Settle(ex)
            } catch (t: Throwable) {
                Mail.Failed(ex, t)
            }
            post(outcome)
        }
    }

    fun log(t: Throwable) = logger.log(System.Logger.Level.WARNING, "A handler failed; answered 500", t)

    private fun nextHead(): Head? =
        when (val parsed = nextHead(config.maxRequestLineBytes, config.maxHeaderBytes, config.maxHeaderCount)) {
            is Parse.Complete -> parsed.head.also { headSince = 0L }
            is Parse.Refused -> null.also { refuse(parsed.status, parsed.reason) }
            else -> null
        }

    private fun refuse(status: Int, reason: String) {
        val body = reason.toByteArray()
        out.put("HTTP/1.1 $status ${reason(status)}\r\n")
        out.put(Dates.current())
        out.put("Content-Type: text/plain; charset=utf-8\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n")
        out.put(body)
    }

    // ------------------------------------------------------------------ mail

    fun onFiber(): Boolean = Io.fiber() === fiber

    /** Hands [mail] to this connection's fiber from any thread; failed instead if its exchange has ended. */
    fun post(mail: Mail) = carrier.execute(
        object : Task {
            override fun run() {
                if (closed || mail.exchange !== exchange) {
                    mail.fail(IOException("The exchange has ended"))
                } else {
                    mailbox.addLast(mail)
                    carrier.wake(fiber)
                }
            }

            override fun reject() = mail.fail(IOException("The server has stopped"))
        },
    )

    /** Wakes the fiber if it is parked reading [ex]'s body, which another thread has just failed. */
    fun interruptRead(ex: UringExchange) = carrier.execute(
        object : Task {
            override fun run() {
                if (!closed && exchange === ex) carrier.wakeReader(fiber)
            }

            override fun reject() = Unit
        },
    )

    private fun deliverNext() {
        val mail = mailbox.removeFirstOrNull()
        if (mail != null) return mail.deliver()
        if (aborted) throw IOException("The server has stopped")
        beforePark()
        Io.parkForWake()
    }

    private fun failQueued() {
        while (mailbox.isNotEmpty()) mailbox.removeFirst().fail(IOException("The exchange has ended"))
    }

    // ------------------------------------------------------------------ reading

    override val ending: Boolean get() = eof || stopping

    /** Reads more of a head, timed as idle while none of it has come, and from its first byte once some has. */
    override fun fillHead(idle: Boolean): Boolean {
        if (!idle && headSince == 0L) headSince = System.nanoTime()
        return fill(if (idle) Wait.IDLE else Wait.HEAD)
    }

    override fun fillMore(): Boolean = fill(Wait.BODY, exchange?.reader)

    override fun sendContinue() {
        if (exchange?.phase != Phase.OPEN) throw IOException("The reply was sent before the body was asked for")
        out.put(CONTINUE)
    }

    /**
     * Reads at least one more byte, parking until it arrives; false at the end of the stream. A body being read for
     * another thread ([reading]) can be failed from there, which wakes this read and ends it with that failure.
     */
    private fun fill(wait: Int, reading: BodyReader? = null): Boolean {
        if (eof) return false
        // Replies the client is not taking stop the reading of more requests, whatever asks for the replies.
        relieve()
        makeRoom()
        var n = transport.readNow(buf, end, buf.size - end)
        while (n == 0) {
            awaitPeer(wait) { transport.awaitData() }
            reading?.failure?.let { throw it }
            makeRoom()
            n = transport.readNow(buf, end, buf.size - end)
        }
        if (n < 0) {
            eof = true
            return false
        }
        val before = end
        end += n
        // Whatever else has already arrived, so pipelined requests are answered in one pass.
        while (end < buf.size) {
            val more = transport.readNow(buf, end, buf.size - end)
            if (more < 0) eof = true
            if (more <= 0) break
            end += more
        }
        if (wait == Wait.BODY) bodyBytes += end - before
        return true
    }

    // ------------------------------------------------------------------ writing

    /** Queues [length] bytes of [bytes] behind the replies already collected. */
    fun emit(bytes: ByteArray, offset: Int, length: Int) {
        if (length < LARGE) {
            out.put(bytes, offset, length)
            if (out.size >= maxBuffered) flush()
            return
        }
        flush()
        transport.write(bytes, offset, length)
        relieve()
    }

    /**
     * Hands what has been collected to the ring, waiting only if this connection's queued replies now pass
     * [GannetConfig.maxBufferedBytes].
     */
    fun flush() {
        val n = out.size
        if (n == 0) return
        // Emptied first: if the write throws, the next connection on this carrier must not send these bytes.
        out.size = 0
        transport.write(out.bytes, 0, n)
        relieve()
    }

    /** Waits for the client to take what is queued, if that has grown past the cap: the backpressure on writes. */
    private fun relieve() {
        if (Io.unsent() > maxBuffered) awaitSent()
    }

    /** Parks until the kernel has taken everything written: a slow reader holds the writer here. */
    fun awaitSent() = awaitPeer(Wait.SEND) { transport.awaitSend() }

    /**
     * Hands a streamed piece on, parking only while more than [watermark] bytes are still unsent: below it the piece
     * is queued behind the send in flight, or, with none in flight, given to the kernel at once rather than when the
     * fiber next parks.
     */
    fun sendStreamed() {
        flush()
        if (Io.unsent() > watermark) awaitPeer(Wait.SEND) { transport.awaitSendBelow(watermark) }
        else Io.submitNow()
    }

    /** Whether the reply about to be written should close the connection after it. */
    fun closeDecided(ex: UringExchange): Boolean = stopping || ex.reader.mustClose(config.drainBytes)

    private inline fun awaitPeer(kind: Int, wait: () -> Unit) {
        if (aborted) throw IOException("The server has stopped")
        beforePark()
        idle = kind == Wait.IDLE || kind == Wait.HANDSHAKE
        waitingFor = kind
        val since = System.nanoTime()
        if (kind == Wait.SEND) {
            sentSeen = fiber.sentBytes
            sendStalledSince = since
        }
        waitingSince = since
        try {
            wait()
        } finally {
            waitingSince = 0L
            idle = false
            if (kind == Wait.BODY) bodyWaited += System.nanoTime() - since
        }
        if (aborted) throw IOException("The server has stopped")
    }

    private fun beforePark() {
        flush()
        keepRest()
        transport.beforePark()
    }

    // ------------------------------------------------------------------ the carrier's view

    // Both ask whether the fiber is still parked: one already made ready by an arrival is about to use the connection.
    override fun tick(now: Long) {
        val parked = fiber.waitingRecv || fiber.waitingSend
        if (parked && waitingSince != 0L && expired(now)) {
            waitingSince = 0L
            Io.shutdown(carrier, fiber)
        }
    }

    /** Whether the wait in progress has run past its timeout at [now]. */
    private fun expired(now: Long): Boolean = when (waitingFor) {
        Wait.IDLE -> now - waitingSince > shared.idleNanos
        Wait.HEAD -> now - headSince > shared.headNanos
        Wait.HANDSHAKE -> now - accepted > shared.handshakeNanos
        Wait.BODY -> bodyWaited + (now - waitingSince) > shared.bodyNanos + bodyBytes * shared.nanosPerBodyByte
        else -> stalled(now)
    }

    /** Whether a send has taken nothing for longer than the write timeout. */
    private fun stalled(now: Long): Boolean {
        val sent = fiber.sentBytes
        if (sent != sentSeen) {
            sentSeen = sent
            sendStalledSince = now
        }
        return now - sendStalledSince > shared.writeNanos
    }

    override fun stop(force: Boolean) {
        stopping = true
        if (force) {
            aborted = true
            transport.abandon()
            Io.shutdown(carrier, fiber)
            carrier.wake(fiber)
        } else if (idle && fiber.waitingRecv) {
            Io.shutdown(carrier, fiber)
        }
    }

    private companion object {
        const val LARGE = 32 * 1024
    }
}

/** What a connection waits on its peer for; each has its own timeout in [GannetConfig]. */
private object Wait {
    const val IDLE = 0
    const val HEAD = 1
    const val BODY = 2
    const val SEND = 3
    const val HANDSHAKE = 4
}
