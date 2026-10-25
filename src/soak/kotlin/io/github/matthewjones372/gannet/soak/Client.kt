package io.github.matthewjones372.gannet.soak

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException

/** A reply as read off the wire; [body] is empty when it went to a sink instead. */
internal class Reply(val status: Int, val headers: Map<String, String>, val body: ByteArray, val length: Long) {
    val closes: Boolean get() = headers["connection"]?.equals("close", ignoreCase = true) == true
}

/** The server broke the protocol, or answered something the soak did not ask for. */
internal class WrongReply(message: String) : IOException(message)

/** A blocking HTTP/1.1 connection over [socket], plain or TLS: raw bytes out, replies framed as RFC 9112 says. */
internal class Conn(val socket: Socket) : AutoCloseable {
    private val input = BufferedInputStream(socket.getInputStream(), BUFFER)
    private val output = socket.getOutputStream()

    fun send(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size): Conn = apply {
        output.write(bytes, offset, length)
        output.flush()
    }

    fun send(text: String): Conn = send(text.toByteArray(Charsets.ISO_8859_1))

    /**
     * The next reply. Its body goes to [sink] a piece at a time when one is given (so a large one is never held),
     * else is collected; reading stops after [limit] body bytes, for a client that walks away midway.
     */
    fun read(
        head: Boolean = false,
        limit: Long = Long.MAX_VALUE,
        sink: ((ByteArray, Int, Int) -> Unit)? = null,
    ): Reply {
        val status = statusLine()
        val headers = fields()
        val collect = ByteArrayOutputStream()
        val to = sink ?: { b, o, n -> collect.write(b, o, n) }
        val length = when {
            head || status < OK || status == NO_CONTENT || status == NOT_MODIFIED -> 0L
            headers["transfer-encoding"] != null -> chunked(limit, to)
            headers["content-length"] != null -> exactly(headers.getValue("content-length").toLong(), limit, to)
            else -> exactly(Long.MAX_VALUE, limit, to)
        }
        return Reply(status, headers, if (sink == null) collect.toByteArray() else EMPTY, length)
    }

    private fun statusLine(): Int {
        val statusLine = line() ?: throw WrongReply("The connection closed before a reply")
        val parts = statusLine.split(' ', limit = 3)
        if (parts.size < 2 || !parts[0].startsWith("HTTP/1.")) throw WrongReply("Not a status line: $statusLine")
        return parts[1].toIntOrNull() ?: throw WrongReply("Not a status line: $statusLine")
    }

    /** A reply's header fields, by lower-cased name. */
    private fun fields(): Map<String, String> {
        val headers = HashMap<String, String>()
        while (true) {
            val field = line() ?: throw WrongReply("The connection closed in a reply's head")
            if (field.isEmpty()) return headers
            headers[field.substringBefore(':').trim().lowercase()] = field.substringAfter(':').trim()
        }
    }

    /**
     * Whether the server has ended the connection within the socket's timeout: the end of the stream, or a reset.
     * Bytes arriving instead are an error; so is the timeout, which is let through as [SocketTimeoutException].
     */
    fun closedByServer(): Boolean = try {
        val b = input.read()
        if (b >= 0) throw WrongReply("Expected the connection to close, but got more bytes")
        true
    } catch (_: SocketException) {
        true
    }

    /** Reads and throws away everything until the server closes; how many bytes that was. */
    fun drain(): Long {
        val sink = ByteArray(BUFFER)
        var total = 0L
        try {
            while (true) {
                val n = input.read(sink)
                if (n < 0) break
                total += n
            }
        } catch (_: SocketException) {
            // Reset: closed all the same.
        }
        return total
    }

    /** Closes with a reset rather than a FIN: what a client that crashes or is killed looks like to the server. */
    fun reset() {
        try {
            socket.setSoLinger(true, 0)
        } catch (_: IOException) {
            // Already gone.
        }
        close()
    }

    override fun close() = try {
        socket.close()
    } catch (_: IOException) {
        // Nothing left to do.
    }

    private fun exactly(length: Long, limit: Long, sink: (ByteArray, Int, Int) -> Unit): Long {
        val piece = ByteArray(BUFFER)
        var left = minOf(length, limit)
        var read = 0L
        while (left > 0) {
            val n = input.read(piece, 0, minOf(left, piece.size.toLong()).toInt())
            if (n < 0) {
                if (length == Long.MAX_VALUE) break
                throw WrongReply("The connection closed $read bytes into a $length-byte body")
            }
            sink(piece, 0, n)
            left -= n
            read += n
        }
        return read
    }

    private fun chunked(limit: Long, sink: (ByteArray, Int, Int) -> Unit): Long {
        var total = 0L
        while (total < limit) {
            val sizeLine = line() ?: throw WrongReply("The connection closed in a chunked body")
            val size = sizeLine.substringBefore(';').trim().toLongOrNull(HEX)
                ?: throw WrongReply("Not a chunk size: $sizeLine")
            if (size == 0L) {
                while (line()?.isNotEmpty() ?: throw WrongReply("The connection closed in the trailers")) continue
                return total
            }
            total += exactly(size, limit - total, sink)
            if (total < limit && line() != "") throw WrongReply("A chunk did not end in CRLF")
        }
        return total
    }

    private fun line(): String? = line(input)

    companion object {
        const val BUFFER = 64 * 1024
        private const val OK = 200
        private const val NO_CONTENT = 204
        private const val NOT_MODIFIED = 304
        private const val HEX = 16
        private val EMPTY = ByteArray(0)

        /** A plain connection to [port], with reads timing out after [timeoutMillis]. */
        fun open(port: Int, timeoutMillis: Int, receiveBuffer: Int = 0): Conn {
            val socket = Socket()
            if (receiveBuffer > 0) socket.receiveBufferSize = receiveBuffer
            socket.tcpNoDelay = true
            socket.connect(InetSocketAddress("127.0.0.1", port), timeoutMillis)
            socket.soTimeout = timeoutMillis
            return Conn(socket)
        }

        fun line(input: InputStream): String? {
            val bytes = ByteArrayOutputStream()
            while (true) {
                val c = input.read()
                if (c < 0) return if (bytes.size() == 0) null else bytes.toString(Charsets.ISO_8859_1)
                if (c == '\n'.code) return bytes.toString(Charsets.ISO_8859_1).removeSuffix("\r")
                bytes.write(c)
                if (bytes.size() > MAX_LINE) throw WrongReply("A line longer than $MAX_LINE bytes")
            }
        }

        private const val MAX_LINE = 64 * 1024
    }
}

/** `SocketTimeoutException` is what an unanswered wait looks like; the soak counts it as the server's failure. */
internal fun isTimeout(e: Throwable) = e is SocketTimeoutException
