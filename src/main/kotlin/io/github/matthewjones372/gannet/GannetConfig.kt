package io.github.matthewjones372.gannet

import java.time.Duration

/**
 * How a [GannetServer] binds and how much it holds. The defaults suit a service; tests shrink the timeouts.
 *
 * @property host an IPv4 address or a name resolving to one.
 * @property port zero lets the kernel choose; every carrier then shares the port the first was given.
 * @property carriers platform threads, each with its own io_uring (or epoll instance) and listening socket.
 * @property maxRequestLineBytes past this a request line is answered 414.
 * @property maxHeaderBytes the request line and header fields together; past this, 431.
 * @property maxBodyBytes what [RequestBody.readAll] without a limit, and any streamed read, accepts.
 * @property drainBytes how much of a body nobody read is read and thrown away to keep the connection.
 * @property idleTimeout how long a connection may wait on its peer, for a request or to take a reply, before it is
 *   closed.
 * @property stopGrace how long [GannetServer.stop] lets exchanges in progress finish before closing them.
 * @property bufferCount receive buffers per carrier, a power of two; [bufferSize] bytes each.
 * @property fixedFiles direct descriptors per carrier, zero for ordinary ones.
 * @property heldBuffers receive buffers one connection may hold unread before its receive is paused.
 * @property tls TLS for every connection, or null for plain TCP.
 * @property engine what the carriers do their I/O through: io_uring where it can, epoll where it cannot, by default.
 *   [ringEntries], [completionEntries] and [fixedFiles] size io_uring only.
 */
data class GannetConfig(
    val host: String = "127.0.0.1",
    val port: Int = 0,
    val carriers: Int = Runtime.getRuntime().availableProcessors(),
    val maxRequestLineBytes: Int = REQUEST_LINE,
    val maxHeaderBytes: Int = HEADER_BYTES,
    val maxBodyBytes: Long = Long.MAX_VALUE,
    val drainBytes: Long = DRAIN_BYTES,
    val idleTimeout: Duration = Duration.ofSeconds(THIRTY),
    val stopGrace: Duration = Duration.ofSeconds(THIRTY),
    val backlog: Int = 4096,
    val ringEntries: Int = 4096,
    val completionEntries: Int = 16384,
    val bufferCount: Int = 4096,
    val bufferSize: Int = 4096,
    val fixedFiles: Int = 16384,
    val heldBuffers: Int = 16,
    val tick: Duration = Duration.ofMillis(TICK_MILLIS),
    val tls: GannetTls? = null,
    val engine: GannetEngine = GannetEngine.AUTO,
) {
    init {
        require(port in 0..MAX_PORT) { "port must be in 0..$MAX_PORT, not $port" }
        require(carriers > 0) { "carriers must be positive, not $carriers" }
        require(bufferCount > 0 && bufferCount and (bufferCount - 1) == 0 && bufferCount <= MAX_BUFFERS) {
            "bufferCount must be a power of two up to $MAX_BUFFERS, not $bufferCount"
        }
        require(bufferSize > 0) { "bufferSize must be positive, not $bufferSize" }
        require(maxRequestLineBytes in 1..maxHeaderBytes) { "maxRequestLineBytes must be in 1..maxHeaderBytes" }
        require(heldBuffers > 0) { "heldBuffers must be positive, not $heldBuffers" }
        require(!tick.isNegative && !tick.isZero) { "tick must be positive, not $tick" }
    }
}

private const val MAX_PORT = 65_535
private const val MAX_BUFFERS = 32_768

private const val REQUEST_LINE = 8 * 1024
private const val HEADER_BYTES = 16 * 1024
private const val DRAIN_BYTES = 64 * 1024L
private const val THIRTY = 30L
private const val TICK_MILLIS = 250L
