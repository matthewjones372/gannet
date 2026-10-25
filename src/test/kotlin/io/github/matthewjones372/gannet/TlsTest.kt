package io.github.matthewjones372.gannet

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.io.InputStream
import java.net.Socket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException

/** Echoes the body, and says how the request came. */
private val echo = HttpHandler { ex ->
    val body = ex.request.body.readAll()
    ex.respond(200, listOf("X-Method" to ex.request.method, "X-Path" to ex.request.path), body)
}

private fun post(path: String, body: String): String =
    "POST $path HTTP/1.1\r\nHost: x\r\nContent-Length: ${body.toByteArray().size}\r\n\r\n$body"

/** Reads a reply's head, leaving its body unread on [input]. */
private fun readHead(input: InputStream) = generateSequence { line(input) }.takeWhile { it.isNotEmpty() }.toList()

/** Reads a chunked body off [input] and counts its bytes. */
private fun countChunked(input: InputStream): Long {
    var total = 0L
    while (true) {
        val size = line(input)!!.toInt(16)
        if (size == 0) return total.also { line(input) }
        total += input.readNBytes(size).size
        line(input)
    }
}

class TlsTest {
    private val threads = Executors.newVirtualThreadPerTaskExecutor()

    @AfterEach
    fun close() = threads.close()

    private fun https(server: GannetServer, path: String) = URI.create("https://127.0.0.1:${server.port}$path")

    private fun HttpClient.get(server: GannetServer, path: String): HttpResponse<String> =
        send(HttpRequest.newBuilder(https(server, path)).build(), HttpResponse.BodyHandlers.ofString())

    /** The server still answers a well-behaved TLS client. */
    private fun stillServes(server: GannetServer) {
        tlsRaw(server.port).use { it.send(get("/after")).read().status shouldBe 200 }
    }

    // ------------------------------------------------------------------ handshakes

    @Test
    fun `the JDK client gets a reply over TLS 1·3, with HTTP 1·1 chosen by ALPN`() =
        serving(tlsConfig, echo) { server ->
            httpsClient().use { client ->
                val reply = client.get(server, "/hello")
                reply.statusCode() shouldBe 200
                reply.headers().firstValue("X-Path").orElseThrow() shouldBe "/hello"
                reply.version() shouldBe HttpClient.Version.HTTP_1_1
                reply.sslSession().orElseThrow().protocol shouldBe "TLSv1.3"
            }
        }

    @Test
    fun `the JDK client gets a reply over TLS 1·2 when it insists`() = serving(tlsConfig, echo) { server ->
        httpsClient(listOf("TLSv1.2")).use { client ->
            val reply = client.get(server, "/old")
            reply.statusCode() shouldBe 200
            reply.sslSession().orElseThrow().protocol shouldBe "TLSv1.2"
        }
    }

    @Test
    fun `ALPN settles on http 1·1, offers nothing to a client that asks nothing, and refuses only h2`() =
        serving(tlsConfig, echo) { server ->
            tlsSocket(server.port, alpn = listOf("h2", "http/1.1")).use { it.applicationProtocol shouldBe "http/1.1" }
            tlsSocket(server.port).use { socket ->
                socket.applicationProtocol shouldBe ""
                Raw(socket).send(get("/plain")).read().status shouldBe 200
            }
            shouldThrow<SSLException> { tlsSocket(server.port, alpn = listOf("h2")).close() }
            stillServes(server)
        }

    @Test
    fun `a TLS 1·2 connection carries requests too`() = serving(tlsConfig, echo) { server ->
        tlsRaw(server.port, listOf("TLSv1.2")).use { raw ->
            (raw.socket as javax.net.ssl.SSLSocket).session.protocol shouldBe "TLSv1.2"
            repeat(20) { raw.send(post("/r$it", "body-$it")).read().text shouldBe "body-$it" }
        }
    }

    @Test
    fun `a client that sends plain HTTP instead of a ClientHello is closed, and nobody else is`() =
        serving(tlsConfig, echo) { server ->
            Raw(server.port).use { raw ->
                raw.send(get("/"))
                // At most an alert, then the end: never an HTTP reply.
                val answer = raw.socket.getInputStream().readAllBytes()
                String(answer, Charsets.ISO_8859_1).startsWith("HTTP/") shouldBe false
            }
            stillServes(server)
        }

    @Test
    fun `a client that hangs up mid-handshake ends only its own connection`() = serving(tlsConfig, echo) { server ->
        // A record header promising a ClientHello, a few bytes of it, and gone.
        Socket("127.0.0.1", server.port).use { it.getOutputStream().write(byteArrayOf(0x16, 3, 1, 0, 100, 1, 0, 0)) }
        // And one that connects and says nothing at all.
        Socket("127.0.0.1", server.port).close()
        stillServes(server)
    }

    @Test
    fun `a client that never finishes its handshake meets the idle timeout`() =
        serving(tlsConfig.copy(idleTimeout = Duration.ofMillis(300)), echo) { server ->
            Raw(server.port).use { raw ->
                raw.send(byteArrayOf(0x16, 3, 1, 0, 100))
                raw.closedByServer() shouldBe true
            }
            stillServes(server)
        }

    @Test
    fun `stop closes a connection still waiting for its handshake`() {
        assumeServer()
        val server = GannetServer.start(tlsConfig, echo)
        Raw(server.port).use { raw ->
            tlsRaw(server.port).use { it.send(get("/")).read().status shouldBe 200 }
            val started = System.nanoTime()
            server.stop()
            raw.closedByServer() shouldBe true
            (System.nanoTime() - started) shouldBeLessThan Duration.ofSeconds(4).toNanos()
        }
    }

    @Test
    fun `a client that needs a certificate it lacks fails the handshake, and one that only wants it does not`() {
        val needed = tlsConfig.copy(tls = GannetTls(testTls.context, GannetTls.ClientAuth.NEED))
        serving(needed, echo) { server ->
            shouldThrow<SSLException> {
                tlsRaw(server.port).use { it.send(get("/")).read() }
            }
        }
        val wanted = tlsConfig.copy(tls = GannetTls(testTls.context, GannetTls.ClientAuth.WANT))
        serving(wanted, echo) { server -> stillServes(server) }
    }

    @Test
    fun `the handshake's heavy steps can run on an executor while the carrier serves others`() {
        Executors.newFixedThreadPool(2).use { pool ->
            val offloaded = tlsConfig.copy(carriers = 1, tls = GannetTls(testTls.context, handshakeExecutor = pool))
            serving(offloaded, echo) { server ->
                httpsClient(listOf("TLSv1.2")).use { it.get(server, "/twelve").statusCode() shouldBe 200 }
                val failures = ConcurrentLinkedQueue<String>()
                Executors.newVirtualThreadPerTaskExecutor().use { clients ->
                    repeat(40) { c ->
                        clients.execute {
                            tlsRaw(server.port).use { raw ->
                                val reply = raw.send(post("/c$c", "hello $c")).read()
                                if (reply.text != "hello $c") failures.add("$c: ${reply.status}")
                            }
                        }
                    }
                }
                failures.toList().shouldBeEmpty()
            }
        }
    }

    @Test
    fun `an executor that refuses the handshake's tasks leaves them to the carrier`() {
        val refusing = java.util.concurrent.Executor { throw java.util.concurrent.RejectedExecutionException("no") }
        val config = tlsConfig.copy(tls = GannetTls(testTls.context, handshakeExecutor = refusing))
        serving(config, echo) { server -> stillServes(server) }
    }

    // ------------------------------------------------------------------ bodies

    @Test
    fun `large request and reply bodies cross TLS whole`() {
        val big = ByteArray(6_000_000) { (it % 251).toByte() }
        serving(tlsConfig, echo) { server ->
            httpsClient().use { client ->
                val request = HttpRequest.newBuilder(https(server, "/big"))
                    .POST(HttpRequest.BodyPublishers.ofByteArray(big)).build()
                val reply = client.send(request, HttpResponse.BodyHandlers.ofByteArray())
                reply.body().contentEquals(big) shouldBe true
            }
            tlsRaw(server.port).use { raw ->
                val text = (0 until 3_000_000).map { 'a' + it % 26 }.joinToString("")
                raw.send(post("/raw", text)).read().text shouldBe text
            }
        }
    }

    @Test
    fun `a streamed reply arrives whole, chunked`() {
        val piece = ByteArray(64 * 1024) { 's'.code.toByte() }
        serving(tlsConfig, { ex -> ex.stream(200).use { out -> repeat(200) { out.write(piece) } } }) { server ->
            tlsRaw(server.port).use { raw ->
                raw.send(get("/"))
                val input = raw.socket.getInputStream()
                readHead(input).first() shouldBe "HTTP/1.1 200 OK"
                countChunked(input) shouldBe 200L * piece.size
                raw.send(get("/again")).read().body.size shouldBe 200 * piece.size
            }
        }
    }

    @Test
    fun `a slow reader holds a streaming writer over TLS`() {
        val piece = 64 * 1024
        val pieces = 1024
        val written = AtomicLong()
        val finished = CountDownLatch(1)
        val producing = HttpHandler { ex ->
            ex.offload(threads) {
                it.stream(200).use { out ->
                    val bytes = ByteArray(piece)
                    repeat(pieces) {
                        out.write(bytes)
                        written.addAndGet(piece.toLong())
                    }
                }
                finished.countDown()
            }
        }
        serving(tlsConfig, producing) { server ->
            tlsRaw(server.port).use { raw ->
                raw.send(get("/"))
                val input = raw.socket.getInputStream()
                readHead(input)
                finished.await(1, TimeUnit.SECONDS) shouldBe false
                written.get() shouldBeLessThan piece.toLong() * pieces / 2
                countChunked(input) shouldBe piece.toLong() * pieces
                finished.await(30, TimeUnit.SECONDS) shouldBe true
            }
        }
    }

    @Test
    fun `chunked uploads are decoded, even a byte at a time`() = serving(tlsConfig, echo) { server ->
        tlsRaw(server.port).use { raw ->
            val chunks = (1..50).joinToString("") { "${Integer.toHexString(it * 100)}\r\n${"c".repeat(it * 100)}\r\n" }
            val request = "POST / HTTP/1.1\r\nHost: x\r\nTransfer-Encoding: chunked\r\n\r\n${chunks}0\r\nT: 1\r\n\r\n"
            raw.send(request).read().body.size shouldBe (1..50).sumOf { it * 100 }
            "POST / HTTP/1.1\r\nHost: x\r\nTransfer-Encoding: chunked\r\n\r\n3\r\nabc\r\n2\r\nde\r\n0\r\n\r\n"
                .forEach { raw.send(it.toString()) }
            raw.read().text shouldBe "abcde"
        }
    }

    @Test
    fun `100-continue and HEAD behave as over plain TCP`() = serving(tlsConfig, echo) { server ->
        tlsRaw(server.port).use { raw ->
            raw.send("POST / HTTP/1.1\r\nHost: x\r\nContent-Length: 5\r\nExpect: 100-continue\r\n\r\n")
            raw.read().status shouldBe 100
            raw.send("hello").read().text shouldBe "hello"
            val head = raw.send("HEAD /h HTTP/1.1\r\nHost: x\r\n\r\n").read(head = true)
            head.status shouldBe 200
            head["Content-Length"] shouldBe "0"
            raw.send(get("/next")).read()["X-Path"] shouldBe "/next"
        }
    }

    @Test
    fun `a stream to a HEAD request sends no body`() =
        serving(tlsConfig, { ex -> ex.stream(200).use { it.write("unseen".toByteArray()) } }) { server ->
            tlsRaw(server.port).use { raw ->
                raw.send("HEAD / HTTP/1.1\r\nHost: x\r\n\r\n").read(head = true).status shouldBe 200
                raw.send(get("/")).read().text shouldBe "unseen"
            }
        }

    // ------------------------------------------------------------------ connections

    @Test
    fun `one connection carries many requests`() = serving(tlsConfig, echo) { server ->
        tlsRaw(server.port).use { raw ->
            repeat(500) { raw.send(post("/k$it", "keep-$it")).read().text shouldBe "keep-$it" }
        }
        httpsClient().use { client -> repeat(50) { client.get(server, "/c$it").body() shouldBe "" } }
    }

    @Test
    fun `pipelined requests are answered in order`() = serving(tlsConfig, echo) { server ->
        tlsRaw(server.port).use { raw ->
            raw.send((0 until 64).joinToString("") { post("/p$it", "pipelined-$it") })
            (0 until 64).forEach { raw.read().text shouldBe "pipelined-$it" }
        }
    }

    @Test
    fun `a client's close_notify is answered with the server's`() = serving(tlsConfig, echo) { server ->
        tlsSocket(server.port).use { socket ->
            val raw = Raw(socket)
            raw.send(get("/")).read().status shouldBe 200
            socket.shutdownOutput()
            socket.getInputStream().read() shouldBe -1
        }
    }

    @Test
    fun `Connection close ends with the server's close_notify`() = serving(tlsConfig, echo) { server ->
        tlsSocket(server.port, listOf("TLSv1.2")).use { socket ->
            val raw = Raw(socket)
            raw.send(get("/", "Connection: close")).read()["Connection"] shouldBe "close"
            socket.getInputStream().read() shouldBe -1
        }
    }

    @Test
    fun `a client that hangs up without close_notify after a request is let go`() =
        serving(tlsConfig, echo) { server ->
            tlsSocket(server.port).use { socket ->
                Raw(socket).send(post("/", "x".repeat(100)).dropLast(50))
                // Under the TLS layer: the server sees TCP end without a close_notify, part of the way into a body.
                socket.setSoLinger(true, 0)
            }
            stillServes(server)
        }

    @Test
    fun `garbage after the handshake ends the connection`() = serving(tlsConfig, echo) { server ->
        val plain = Socket("127.0.0.1", server.port)
        val socket = TestCerts.client.socketFactory.createSocket(plain, "127.0.0.1", server.port, true)
            as javax.net.ssl.SSLSocket
        socket.use {
            it.startHandshake()
            Raw(it).send(get("/")).read().status shouldBe 200
            // A record that claims to be application data and is not: the server cannot decrypt it.
            plain.getOutputStream().write(byteArrayOf(0x17, 3, 3, 0, 32) + ByteArray(32))
            plain.getOutputStream().flush()
            shouldThrow<SSLException> { Raw(it).read() }
        }
        stillServes(server)
    }

    @Test
    fun `many TLS connections at once are each answered`() {
        val failures = ConcurrentLinkedQueue<String>()
        serving(tlsConfig.copy(carriers = 4), echo) { server ->
            Executors.newVirtualThreadPerTaskExecutor().use { clients ->
                repeat(200) { c ->
                    clients.execute {
                        try {
                            tlsRaw(server.port).use { raw ->
                                repeat(10) { r ->
                                    val body = "c$c-r$r-" + "x".repeat(r * 997 % 20_000)
                                    if (raw.send(post("/$c", body)).read().text != body) failures.add("$c/$r")
                                }
                            }
                        } catch (e: java.io.IOException) {
                            failures.add("$c: $e")
                        }
                    }
                }
            }
        }
        failures.toList().shouldBeEmpty()
    }

    @Test
    fun `replies from another thread cross TLS too`() {
        val detached = HttpHandler { ex ->
            ex.offload(threads) { it.respond(200, body = it.request.body.readAll()) }
        }
        serving(tlsConfig, detached) { server ->
            tlsRaw(server.port).use { raw ->
                repeat(20) { raw.send(post("/", "off-$it")).read().text shouldBe "off-$it" }
            }
        }
    }

    @Test
    fun `small receive buffers still carry whole records`() =
        serving(tlsConfig.copy(bufferCount = 4, bufferSize = 512, heldBuffers = 2), echo) { server ->
            tlsRaw(server.port).use { raw ->
                val body = "b".repeat(200_000)
                repeat(3) { raw.send(post("/", body)).read().text shouldBe body }
            }
        }

    @Test
    fun `a handshake failure is the client's alone`() {
        // A client that trusts nothing the server has rejects it mid-handshake; the server carries on.
        serving(tlsConfig, echo) { server ->
            val untrusting = javax.net.ssl.SSLContext.getInstance("TLS").apply { init(null, null, null) }
            shouldThrow<SSLHandshakeException> {
                (untrusting.socketFactory.createSocket("127.0.0.1", server.port) as javax.net.ssl.SSLSocket)
                    .use { it.startHandshake() }
            }
            stillServes(server)
        }
    }
}
