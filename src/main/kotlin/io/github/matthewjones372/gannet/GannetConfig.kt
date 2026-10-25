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
 * @property idleTimeout how long a connection may sit with nothing of a request read, before its first request and
 *   between requests (keep-alive), before it is closed.
 * @property stopGrace how long [GannetServer.stop] lets exchanges in progress finish before closing them.
 * @property bufferCount receive buffers per carrier, a power of two; [bufferSize] bytes each.
 * @property fixedFiles direct descriptors per carrier, zero for ordinary ones.
 * @property heldBuffers receive buffers one connection may hold unread before its receive is paused.
 * @property streamHighWatermark bytes of a streamed reply that may wait unsent before [ResponseStream.write] holds the
 *   writer back; below it a write only queues its bytes. Zero makes every write wait until the kernel has taken it.
 * @property tls TLS for every connection, or null for plain TCP.
 * @property headTimeout how long a request head may take to arrive in full, from its first byte; past it the connection
 *   is closed. It is what stops a client sending its head a byte at a time (slow loris).
 * @property bodyTimeout how long reading a request body may wait on the client in all, before [minBodyRate] extends
 *   it; only time spent waiting for the client counts, not time the handler takes between reads.
 * @property minBodyRate bytes a second a body must arrive at once [bodyTimeout] is spent: each byte read extends the
 *   allowance by `1 / minBodyRate` seconds. Zero gives no extension, so [bodyTimeout] bounds the whole body.
 * @property writeTimeout how long a reply may wait for the client to take any of it; past it the connection is closed,
 *   and a handler writing to it gets an `IOException`. Any progress starts it again.
 * @property handshakeTimeout how long a TLS handshake may take, from the connection being accepted.
 * @property maxConnections connections served at once across the whole server. At the cap the carriers stop
 *   accepting: the connection each accepted last waits, unserved, and the rest wait in the listen backlog, until one
 *   closes. Nothing is refused.
 * @property maxHandshakes TLS handshakes in progress at once on each carrier. At the cap that carrier stops accepting
 *   until one finishes, as at [maxConnections].
 * @property maxBufferedBytes what one connection may hold: replies queued while a send is in flight, past which its
 *   writer waits for the client to take them, and unread request bytes, past which the connection is closed.
 * @property maxHeaderCount header field lines a request may have; past this, 431.
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
    val streamHighWatermark: Int = STREAM_HIGH_WATERMARK,
    val tick: Duration = Duration.ofMillis(TICK_MILLIS),
    val tls: GannetTls? = null,
    val headTimeout: Duration = Duration.ofSeconds(TWENTY),
    val bodyTimeout: Duration = Duration.ofSeconds(THIRTY),
    val minBodyRate: Long = MIN_BODY_RATE,
    val writeTimeout: Duration = Duration.ofSeconds(THIRTY),
    val handshakeTimeout: Duration = Duration.ofSeconds(TEN),
    val maxConnections: Int = MAX_CONNECTIONS,
    val maxHandshakes: Int = MAX_HANDSHAKES,
    val maxBufferedBytes: Int = MAX_BUFFERED,
    val maxHeaderCount: Int = HEADER_COUNT,
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
        require(maxHeaderCount > 0) { "maxHeaderCount must be positive, not $maxHeaderCount" }
        require(heldBuffers > 0) { "heldBuffers must be positive, not $heldBuffers" }
        require(streamHighWatermark >= 0) { "streamHighWatermark must not be negative, not $streamHighWatermark" }
        require(!tick.isNegative && !tick.isZero) { "tick must be positive, not $tick" }
        listOf(
            "idleTimeout" to idleTimeout,
            "headTimeout" to headTimeout,
            "bodyTimeout" to bodyTimeout,
            "writeTimeout" to writeTimeout,
            "handshakeTimeout" to handshakeTimeout,
        ).forEach { (name, timeout) -> require(!timeout.isNegative && !timeout.isZero) { "$name must be positive" } }
        require(minBodyRate >= 0) { "minBodyRate must not be negative, not $minBodyRate" }
        require(maxConnections > 0) { "maxConnections must be positive, not $maxConnections" }
        require(maxHandshakes > 0) { "maxHandshakes must be positive, not $maxHandshakes" }
        require(maxBufferedBytes > 0) { "maxBufferedBytes must be positive, not $maxBufferedBytes" }
    }
}

private const val MAX_PORT = 65_535
private const val MAX_BUFFERS = 32_768

private const val REQUEST_LINE = 8 * 1024
private const val HEADER_BYTES = 16 * 1024
private const val HEADER_COUNT = 100
private const val DRAIN_BYTES = 64 * 1024L
private const val THIRTY = 30L
private const val TWENTY = 20L
private const val TEN = 10L
private const val MIN_BODY_RATE = 1024L
private const val MAX_CONNECTIONS = 10_000
private const val MAX_HANDSHAKES = 64
private const val MAX_BUFFERED = 1 shl 20
private const val TICK_MILLIS = 250L
private const val STREAM_HIGH_WATERMARK = 64 * 1024
