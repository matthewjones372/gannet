package io.github.matthewjones372.gannet

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.Socket
import java.time.Duration

val testConfig = GannetConfig(
    carriers = 2,
    bufferCount = 256,
    idleTimeout = Duration.ofSeconds(20),
    stopGrace = Duration.ofSeconds(5),
    tick = Duration.ofMillis(50),
)

/** Starts a server for [handler], runs [block] against it, and stops it. Unit, so a test can be its expression. */
fun serving(config: GannetConfig = testConfig, handler: HttpHandler, block: (GannetServer) -> Unit) {
    assumeUring()
    val server = GannetServer.start(config, handler)
    try {
        block(server)
    } finally {
        server.stop()
    }
}

/** A reply as it came off the wire. */
class WireReply(val status: Int, val reason: String, val headers: Headers, val body: ByteArray) {
    val text: String get() = String(body)

    operator fun get(name: String): String? = headers[name]

    override fun toString(): String = "WireReply($status $reason, $headers, ${text.take(200)})"
}

/**
 * A client that writes raw bytes and reads replies off [socket], framing them as RFC 9112 says. The socket may be a
 * TLS one, for the same requests over TLS.
 */
class Raw(val socket: Socket, timeout: Duration = Duration.ofSeconds(20)) : AutoCloseable {
    constructor(port: Int, timeout: Duration = Duration.ofSeconds(20)) : this(Socket("127.0.0.1", port), timeout)

    init {
        socket.soTimeout = timeout.toMillis().toInt()
    }

    private val input = socket.getInputStream().buffered()

    fun send(text: String): Raw = send(text.toByteArray())

    fun send(bytes: ByteArray): Raw = apply {
        socket.getOutputStream().write(bytes)
        socket.getOutputStream().flush()
    }

    /** The next reply; [head] says it answers a HEAD, so it has no body whatever its fields say. */
    fun read(head: Boolean = false): WireReply {
        val statusLine = line(input) ?: error("The connection closed before a reply")
        val parts = statusLine.split(' ', limit = 3)
        val fields = generateSequence { line(input)?.takeIf { it.isNotEmpty() } }
            .map { it.substringBefore(':') to it.substringAfter(':').trim() }
            .toList()
        val headers = Headers(fields)
        val status = parts[1].toInt()
        val body = when {
            head || status < 200 || status == 204 || status == 304 -> ByteArray(0)
            headers["transfer-encoding"] != null -> chunked(input)
            headers["content-length"] != null -> input.readNBytes(headers["content-length"]!!.toInt())
            else -> input.readAllBytes()
        }
        return WireReply(status, parts.getOrElse(2) { "" }, headers, body)
    }

    /** True once the server has closed its end: a read gets the end of the stream. */
    fun closedByServer(): Boolean = try {
        input.read() == -1
    } catch (_: java.net.SocketException) {
        true
    }

    override fun close() = socket.close()
}

fun line(input: InputStream): String? {
    val bytes = ByteArrayOutputStream()
    while (true) {
        val c = input.read()
        if (c < 0) return if (bytes.size() == 0) null else bytes.toString(Charsets.ISO_8859_1)
        if (c == '\n'.code) return bytes.toString(Charsets.ISO_8859_1).removeSuffix("\r")
        bytes.write(c)
    }
}

private fun chunked(input: InputStream): ByteArray {
    val body = ByteArrayOutputStream()
    while (true) {
        val size = line(input)!!.substringBefore(';').trim().toInt(16)
        if (size == 0) {
            while (line(input)!!.isNotEmpty()) continue
            return body.toByteArray()
        }
        body.write(input.readNBytes(size))
        line(input)
    }
}

/** A request with a Host, as a client would send it. */
fun get(path: String, vararg headers: String): String =
    "GET $path HTTP/1.1\r\nHost: test\r\n" + headers.joinToString("") { "$it\r\n" } + "\r\n"
