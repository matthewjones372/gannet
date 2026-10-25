package io.github.matthewjones372.gannet

import java.util.concurrent.Executor

/**
 * What a server does with each request. [handle] runs on a carrier, inside the connection's fiber.
 *
 * It must not block the thread: no locks held across waits, no sleeping, no blocking I/O of its own. The exchange's
 * own calls (reading the body, writing a stream) are safe there, since they park the fiber and not the carrier. Work
 * that may block goes to another thread through [HttpExchange.offload] or [HttpExchange.detach], and answers from
 * there through the same exchange. Returning without answering and without detaching is answered with a 500.
 */
fun interface HttpHandler {
    fun handle(exchange: HttpExchange)
}

/**
 * One request and its reply. Held by one thread at a time: the carrier while [HttpHandler.handle] runs, then, once
 * detached, whichever thread the handler gave it to. Every call works from either side; off the carrier each one is
 * handed to the carrier and, where it has a result, waits for it.
 */
interface HttpExchange {
    val request: HttpRequest

    /** Whether the calling thread is the carrier running this exchange's fiber. */
    val onCarrier: Boolean

    /**
     * The whole reply. `Content-Length` and `Date` are added; a HEAD request gets the header fields and no body. Any
     * `Content-Length`, `Transfer-Encoding` or `Date` in [headers] is replaced, apart from a HEAD or 304 reply's
     * `Content-Length`, which is kept when [body] is empty. `Connection: close` closes the connection afterwards.
     */
    fun respond(status: Int, headers: List<Pair<String, String>> = emptyList(), body: ByteArray = EMPTY_BODY)

    /**
     * Starts a reply whose body is written a piece at a time, chunked (or delimited by closing the connection, to an
     * HTTP/1.0 client). Each [ResponseStream.write] returns once the kernel has taken the piece, which is the
     * backpressure: a slow reader holds the writer.
     */
    fun stream(status: Int, headers: List<Pair<String, String>> = emptyList()): ResponseStream

    /** Says the reply will come from another thread, so returning from [HttpHandler.handle] does not end it. */
    fun detach()

    /**
     * Detaches and runs [work] on [executor], where it may block. A throw from [work] answers 413 for
     * [BodyTooLargeException], 400 for [MalformedBodyException] and 500 for anything else, or ends a started stream
     * unterminated.
     */
    fun offload(executor: Executor, work: (HttpExchange) -> Unit)

    companion object {
        val EMPTY_BODY: ByteArray = ByteArray(0)
    }
}

/**
 * A reply body being written. [close] ends it properly; [abort] ends it so the client sees it fail, and closes the
 * connection. Returning from [HttpHandler.handle] without detaching closes a stream left open.
 */
interface ResponseStream : AutoCloseable {
    /** Writes [length] bytes of [bytes] from [offset]; returns once they are sent, throws if the connection is gone. */
    fun write(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size)

    override fun close()

    fun abort()
}
