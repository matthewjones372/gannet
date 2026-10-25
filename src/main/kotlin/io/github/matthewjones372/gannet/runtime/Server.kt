package io.github.matthewjones372.gannet.runtime

import io.github.matthewjones372.gannet.GannetConfig
import io.github.matthewjones372.gannet.HttpExchange
import io.github.matthewjones372.gannet.HttpHandler
import io.github.matthewjones372.gannet.MalformedBodyException
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
    val tls = config.tls?.let { TlsShared(it) }

    /** The transport for a connection starting in the calling fiber. */
    fun transport(): Transport = tls?.let { TlsTransport(it.tls.engine(), it) } ?: Plain

    private companion object {
        const val READ_BUFFER = 16 * 1024
        const val OUT_BUFFER = 16 * 1024
    }
}

private val logger = System.getLogger("io.github.matthewjones372.gannet")

/**
 * One connection, run in its own fiber: read a head, hand the exchange to the handler, write what it answers, and
 * again while the connection stays open. Requests are answered in the order they arrived, so pipelining needs nothing
 * more; replies to requests already read are batched into one write.
 */
internal class Connection(private val fd: Int, private val shared: Shared) : Session {
    private val carrier = Carrier.current()
    private val fiber = Io.fiber()
    private val config = shared.config
    private val transport = shared.transport()
    val out = shared.out
    val maxHeaderBytes = config.maxHeaderBytes
    val maxBodyBytes = config.maxBodyBytes

    private var own: ByteArray? = null
    private var buf = shared.read
    private var start = 0
    private var end = 0
    private var eof = false

    /** When the fiber started waiting on its peer, or zero when it is not. */
    private var waitingSince = 0L

    /** Waiting for a request with nothing of one read yet, so stopping may close it at once. */
    private var idle = false
    private var stopping = false
    private var aborted = false
    private var closed = false
    private val mailbox = ArrayDeque<Mail>()
    private var exchange: UringExchange? = null

    fun run() {
        Io.attach(this, fd)
        try {
            if (handshake()) serve()
            // The replies collected so far go before the close_notify.
            flush()
            transport.close()
            // Waited for here rather than in the close, so a peer that stops reading meets the idle timeout.
            awaitSent()
        } catch (_: IOException) {
            // The peer went away or the server stopped: nothing is left to tell anyone.
            out.size = 0
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
        while (!transport.handshake()) awaitPeer(idle = true) { transport.awaitData() }
        true
    } catch (e: SSLException) {
        // Scanners, old clients and mistrusted certificates all end here; none is the server's fault.
        logger.log(System.Logger.Level.DEBUG, "A TLS handshake failed: {0}", e.message)
        false
    }

    private fun serve() {
        while (true) {
            val head = nextHead() ?: return
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

    private fun nextHead(): Head? {
        while (true) {
            start += blankLines(buf, start, end)
            if (start == end && (eof || stopping)) return null
            when (val parsed = parseHead(buf, start, end, config.maxRequestLineBytes, config.maxHeaderBytes)) {
                is Parse.Complete -> return parsed.head.also { start = parsed.end }
                is Parse.Refused -> return null.also { refuse(parsed.status, parsed.reason) }
                Parse.Incomplete -> if (!fill(idle = start == end)) return null
            }
        }
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

    fun buffered(): Int = end - start

    fun take(into: ByteArray, offset: Int, length: Int) {
        System.arraycopy(buf, start, into, offset, length)
        start += length
    }

    /** Reads more of a body, which has to be there: the connection ending first breaks the body. */
    fun fillBody() {
        if (!fill(reading = exchange?.reader)) throw MalformedBodyException("The connection ended before the body did")
    }

    /**
     * The next line, without its line ending, reading more as needed; null if its content runs past [max] bytes. An
     * ended connection breaks the body the line belongs to.
     */
    fun line(max: Int): String? {
        while (true) {
            val lf = Scan.indexOf(buf, LF.toInt(), start, minOf(end, start + max + 2))
            if (lf >= 0) {
                val content = if (lf > start && buf[lf - 1] == CR) lf - 1 else lf
                if (content - start > max) return null
                return String(buf, start, content - start, Charsets.ISO_8859_1).also { start = lf + 1 }
            }
            if (end - start > max + 1) return null
            fillBody()
        }
    }

    /**
     * Reads at least one more byte, parking until it arrives; false at the end of the stream. A body being read for
     * another thread ([reading]) can be failed from there, which wakes this read and ends it with that failure.
     */
    private fun fill(idle: Boolean = false, reading: BodyReader? = null): Boolean {
        if (eof) return false
        makeRoom()
        var n = transport.readNow(buf, end, buf.size - end)
        while (n == 0) {
            awaitPeer(idle) { transport.awaitData() }
            reading?.failure?.let { throw it }
            makeRoom()
            n = transport.readNow(buf, end, buf.size - end)
        }
        if (n < 0) {
            eof = true
            return false
        }
        end += n
        // Whatever else has already arrived, so pipelined requests are answered in one pass.
        while (end < buf.size) {
            val more = transport.readNow(buf, end, buf.size - end)
            if (more < 0) eof = true
            if (more <= 0) break
            end += more
        }
        return true
    }

    /** Room after [end]: the lent buffer again when nothing is held, else compacted, else grown into one of its own. */
    private fun makeRoom() {
        if (start == end) {
            start = 0
            end = 0
            buf = shared.read
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
    private fun keepRest() {
        if (buf !== shared.read || start == end) return
        val rest = end - start
        val mine = own?.takeIf { it.size >= rest } ?: ByteArray(maxOf(MIN_OWN, Integer.highestOneBit(rest - 1) shl 1))
        System.arraycopy(buf, start, mine, 0, rest)
        own = mine
        buf = mine
        start = 0
        end = rest
    }

    // ------------------------------------------------------------------ writing

    /** Queues [length] bytes of [bytes] behind the replies already collected. */
    fun emit(bytes: ByteArray, offset: Int, length: Int) {
        if (length < LARGE) return out.put(bytes, offset, length)
        flush()
        transport.write(bytes, offset, length)
    }

    /** Hands what has been collected to the ring, without waiting. */
    fun flush() {
        val n = out.size
        if (n == 0) return
        // Emptied first: if the write throws, the next connection on this carrier must not send these bytes.
        out.size = 0
        transport.write(out.bytes, 0, n)
    }

    /** Parks until the kernel has taken everything written: a slow reader holds the writer here. */
    fun awaitSent() = awaitPeer(idle = false) { transport.awaitSend() }

    /** Whether the reply about to be written should close the connection after it. */
    fun closeDecided(ex: UringExchange): Boolean = stopping || ex.reader.mustClose(config.drainBytes)

    private inline fun awaitPeer(idle: Boolean, wait: () -> Unit) {
        if (aborted) throw IOException("The server has stopped")
        beforePark()
        this.idle = idle
        waitingSince = System.nanoTime()
        try {
            wait()
        } finally {
            waitingSince = 0L
            this.idle = false
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
        if (parked && waitingSince != 0L && now - waitingSince > shared.idleNanos) {
            waitingSince = 0L
            Io.shutdown(carrier, fiber)
        }
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
        const val MIN_OWN = 4 * 1024
    }
}
