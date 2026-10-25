package io.github.matthewjones372.gannet

import java.io.IOException
import java.io.InputStream
import java.time.Duration

/** The two versions a request line may name; a reply is always written as HTTP/1.1. */
enum class HttpVersion { HTTP_1_0, HTTP_1_1 }

/**
 * A request's line and header fields as they arrived. [target] and [path] are not percent-decoded, so `%2F` stays
 * inside its segment; [query] is everything after the first `?`, also raw, or null when there is no `?`.
 */
class HttpRequest internal constructor(
    val method: String,
    val target: String,
    val path: String,
    val query: String?,
    val version: HttpVersion,
    val headers: Headers,
    val body: RequestBody,
)

/** Header fields in arrival order, looked up by name without regard to case. */
class Headers(val fields: List<Pair<String, String>>) {
    /** The first value under [name], or null. */
    operator fun get(name: String): String? = fields.firstOrNull { it.first.equals(name, ignoreCase = true) }?.second

    /** Every value under [name], one per field line, in arrival order. */
    fun all(name: String): List<String> = fields.filter { it.first.equals(name, ignoreCase = true) }.map { it.second }

    operator fun contains(name: String): Boolean = fields.any { it.first.equals(name, ignoreCase = true) }

    /** The names present, lower-cased. */
    val names: Set<String> get() = fields.map { it.first.lowercase() }.toSet()

    override fun toString(): String = fields.joinToString(", ", "Headers(", ")") { "${it.first}: ${it.second}" }
}

/**
 * A request body, read at most once, from whichever thread holds the exchange.
 *
 * On the carrier a read parks only the connection's fiber; on another thread it blocks that thread while the carrier
 * reads for it. Either way the carrier itself never waits. A body nobody reads is drained after the reply, up to
 * `GannetConfig.drainBytes`, and the connection closed past that.
 */
interface RequestBody {
    /** `Content-Length`, zero when the request has no body, or null for a chunked one. */
    val length: Long?

    /** The whole body; throws [BodyTooLargeException] past [limit] bytes, before reading when the length says so. */
    fun readAll(limit: Long): ByteArray

    /** The whole body, limited by `GannetConfig.maxBodyBytes`. */
    fun readAll(): ByteArray

    /** Up to [length] bytes into [into] at [offset]; -1 at the end. */
    fun read(into: ByteArray, offset: Int, length: Int): Int

    /**
     * [read], giving up with [java.net.SocketTimeoutException] after [timeout] with nothing arriving. Timing out fails
     * the body, as [fail] does, so the rest is left unread and the connection closes after the reply.
     */
    fun read(into: ByteArray, offset: Int, length: Int, timeout: Duration): Int

    /** Fails the body, from any thread: a read blocked on it throws [cause], and so does every later one. */
    fun fail(cause: IOException)

    /** The body as a stream over [read]. */
    fun inputStream(): InputStream = object : InputStream() {
        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and BYTE_MASK
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int = if (len ==
            0
        ) 0 else this@RequestBody.read(b, off, len)
    }
}

private const val BYTE_MASK = 0xFF

/** The body is longer than the limit it was read with; [limit] is that limit. */
class BodyTooLargeException(val limit: Long) : IOException("The request body is longer than $limit bytes")

/** The body's framing broke: a bad chunk, or the connection ended before the body did. */
class MalformedBodyException(message: String) : IOException(message)
