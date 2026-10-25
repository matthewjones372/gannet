package io.github.matthewjones372.gannet.runtime

import java.io.IOException
import java.net.SocketTimeoutException
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * What a thread holding a detached exchange asks of its connection's fiber, in the order it asked. [deliver] runs in
 * the fiber; [fail] runs wherever the mail is found undeliverable, because the exchange or the server has ended.
 */
internal sealed class Mail(val exchange: UringExchange) {
    abstract fun deliver()

    open fun fail(e: IOException) = Unit

    /** Mail whose sender waits on [answer]. */
    sealed class Asking<T>(exchange: UringExchange, val answer: Answer<T>) : Mail(exchange) {
        override fun fail(e: IOException) {
            answer.completeExceptionally(e)
        }
    }

    class Strict(
        exchange: UringExchange,
        val status: Int,
        val headers: List<Pair<String, String>>,
        val body: ByteArray,
    ) : Mail(exchange) {
        override fun deliver() = exchange.writeStrict(status, headers, body)
    }

    class Start(exchange: UringExchange, val status: Int, val headers: List<Pair<String, String>>) : Mail(exchange) {
        override fun deliver() = exchange.startStream(status, headers)
    }

    class Chunk(exchange: UringExchange, val bytes: ByteArray, val offset: Int, val length: Int, answer: Answer<Unit>) :
        Asking<Unit>(exchange, answer) {
        override fun deliver() = answering(answer) { exchange.writeChunk(bytes, offset, length) }
    }

    class End(exchange: UringExchange, answer: Answer<Unit>) : Asking<Unit>(exchange, answer) {
        override fun deliver() = answering(answer) { exchange.endStream() }
    }

    class Abort(exchange: UringExchange) : Mail(exchange) {
        override fun deliver() = exchange.abortStream()
    }

    class Read(exchange: UringExchange, val max: Int, answer: Answer<ByteArray?>) :
        Asking<ByteArray?>(exchange, answer) {
        override fun deliver() = reading(answer) { exchange.reader.readPiece(max, exchange.conn.maxBodyBytes) }
    }

    class ReadAll(exchange: UringExchange, val limit: Long, answer: Answer<ByteArray>) :
        Asking<ByteArray>(exchange, answer) {
        override fun deliver() = reading(answer) { exchange.reader.readAll(limit) }
    }

    /** The offloaded work threw [cause]. */
    class Failed(exchange: UringExchange, val cause: Throwable) : Mail(exchange) {
        override fun deliver() = exchange.fail(cause)
    }

    /** The offloaded work returned. */
    class Settle(exchange: UringExchange) : Mail(exchange) {
        override fun deliver() = exchange.settle()
    }
}

/**
 * Completes [answer] with what [write] does to the connection. A write that fails has lost the connection, so the
 * failure goes on to end the fiber too; a write refused for the stream's state does not.
 */
private inline fun answering(answer: Answer<Unit>, write: () -> Unit) {
    try {
        write()
        answer.complete(Unit)
    } catch (e: IOException) {
        answer.completeExceptionally(e)
        throw e
    } catch (e: IllegalStateException) {
        answer.completeExceptionally(e)
    }
}

/** Completes [answer] with what [read] returns. A failed body read is the handler's to answer, not the connection's. */
private inline fun <T> reading(answer: Answer<T>, read: () -> T) {
    try {
        answer.complete(read())
    } catch (e: IOException) {
        answer.completeExceptionally(e)
    }
}

/** A result the fiber hands back to a waiting thread. */
internal class Answer<T> : CompletableFuture<T>() {
    /** The result, waiting at most [timeout] (forever when null); a failure is rethrown as the fiber raised it. */
    // The ExecutionException is only the envelope; what the handler is owed is the exception inside it.
    @Suppress("SwallowedException")
    fun await(timeout: Duration?): T = try {
        if (timeout != null) orTimeout(timeout.toNanos(), TimeUnit.NANOSECONDS)
        get()
    } catch (e: ExecutionException) {
        throw when (val cause = e.cause) {
            is IOException -> cause
            is RuntimeException -> cause
            is TimeoutException -> SocketTimeoutException("Timeout on waiting for new data")
            else -> IOException(cause)
        }
    } catch (e: InterruptedException) {
        Thread.currentThread().interrupt()
        throw IOException("Interrupted while waiting for the connection", e)
    }
}
