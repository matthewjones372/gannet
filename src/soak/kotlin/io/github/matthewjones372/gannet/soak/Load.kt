package io.github.matthewjones372.gannet.soak

import java.io.IOException
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.util.SplittableRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.CRC32
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket

/** Where the load goes, and the server's limits it trips on purpose. */
internal class Target(
    val main: Int,
    val tls: Int,
    val cap: Int,
    val capLimit: Int,
    val limitSeconds: Long,
    val idleSeconds: Long,
    val client: SSLContext,
)

/** What one scenario has done: completed iterations, failures that are the server's fault, 5xx replies, latency. */
internal class ScenarioStats(val name: String) {
    val ok = AtomicLong()
    val unexpected = AtomicLong()
    val serverErrors = AtomicLong()
    val latency = Histogram()
    val errors: MutableList<String> = CopyOnWriteArrayList()

    fun fail(message: String) {
        unexpected.incrementAndGet()
        if (errors.size < KEPT_ERRORS) errors += message
    }

    private companion object {
        const val KEPT_ERRORS = 20
    }
}

/**
 * One kind of client: [workers] threads, each running [iteration] over and over while the load runs. Virtual threads,
 * except for the clients whose checks are timed ([timed]): those are platform threads, which the kernel schedules
 * fairly, so a load generator short of cpu delays them by milliseconds rather than queueing them behind every busy
 * virtual thread for seconds.
 */
internal class Scenario(
    val name: String,
    val workers: Int,
    val iteration: (Worker) -> Unit,
    val timed: Boolean = false,
) {
    val stats = ScenarioStats(name)
}

/** A worker's view: its random numbers, its scenario's stats, and connections the load can force shut on stop. */
internal class Worker(val load: Load, val stats: ScenarioStats, seed: Long) {
    val random = SplittableRandom(seed)
    val running: Boolean get() = load.running.get()

    fun open(port: Int, timeoutMillis: Int = TIMEOUT_MILLIS, receiveBuffer: Int = 0): Conn =
        Conn.open(port, timeoutMillis, receiveBuffer).also { load.track(it) }

    /** A TLS connection to [port], handshaken. */
    fun tls(port: Int, context: SSLContext = load.target.client): Conn {
        val socket = context.socketFactory.createSocket() as SSLSocket
        socket.tcpNoDelay = true
        socket.connect(InetSocketAddress("127.0.0.1", port), TIMEOUT_MILLIS)
        socket.soTimeout = TIMEOUT_MILLIS
        val conn = Conn(socket).also { load.track(it) }
        socket.startHandshake()
        return conn
    }

    fun done(conn: Conn, reset: Boolean = false) {
        if (reset) conn.reset() else conn.close()
        load.untrack(conn)
    }

    /** Runs [block] with [conn], closing it after whatever happens. */
    inline fun <T> using(conn: Conn, block: (Conn) -> T): T = try {
        block(conn)
    } finally {
        done(conn)
    }

    /** Times [block] into this scenario's latency and counts it done. */
    inline fun <T> timed(block: () -> T): T {
        val start = System.nanoTime()
        val result = block()
        stats.latency.record(System.nanoTime() - start)
        stats.ok.incrementAndGet()
        return result
    }

    /** A reply with [status], or a failure: a 5xx is also counted as a server error. */
    fun expect(reply: Reply, status: Int, what: String) {
        if (reply.status >= SERVER_ERROR && reply.status != status) stats.serverErrors.incrementAndGet()
        if (reply.status != status) throw WrongReply("$what: expected $status, got ${reply.status}")
    }

    fun between(from: Int, until: Int) = random.nextInt(from, until)

    fun chance(percent: Int) = random.nextInt(PERCENT) < percent

    companion object {
        const val TIMEOUT_MILLIS = 20_000
        const val SERVER_ERROR = 500
        private const val PERCENT = 100
    }
}

/**
 * The soak's load: every scenario's workers on virtual threads, started together and stopped together. [stop] asks
 * the workers to finish what they are doing, and closes whatever connections they still hold after a while.
 */
internal class Load(val target: Target, only: Set<String>? = null) {
    val running = AtomicBoolean()
    private val open = ConcurrentHashMap.newKeySet<Conn>()
    private val threads = CopyOnWriteArrayList<Thread>()

    /** Every scenario, or those named in [only]; the rest have no workers. */
    val scenarios: List<Scenario> = Scenarios(target).all.map {
        if (only == null || it.name in only) it else Scenario(it.name, 0, it.iteration, it.timed)
    }

    fun track(conn: Conn) {
        open += conn
    }

    fun untrack(conn: Conn) {
        open -= conn
    }

    fun start() {
        check(running.compareAndSet(false, true)) { "The load is already running" }
        var seed = System.nanoTime()
        scenarios.forEach { scenario ->
            repeat(scenario.workers) { i ->
                val worker = Worker(this, scenario.stats, seed++)
                val builder = if (scenario.timed) Thread.ofPlatform().daemon() else Thread.ofVirtual()
                threads += builder.name("soak-${scenario.name}-$i").start { loop(scenario, worker) }
            }
        }
    }

    private fun loop(scenario: Scenario, worker: Worker) {
        while (running.get()) {
            try {
                scenario.iteration(worker)
            } catch (e: Exception) {
                // A failure because the load is stopping, and its connections were closed under it, is not one.
                if (running.get()) scenario.stats.fail("${e.javaClass.simpleName}: ${e.message}")
            }
        }
    }

    /** Stops the load: waits up to [waitMillis] for the workers, then closes what they hold. Workers left over. */
    fun stop(waitMillis: Long): Int {
        running.set(false)
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(waitMillis)
        threads.forEach { it.join(maxOf(1L, TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()))) }
        open.forEach { it.close() }
        open.clear()
        threads.forEach { it.join(FORCED_JOIN_MILLIS) }
        val left = threads.count { it.isAlive }
        threads.clear()
        return left
    }

    private companion object {
        const val FORCED_JOIN_MILLIS = 5_000L
    }
}

/** The scenarios, one function each. */
@Suppress("MagicNumber")
private class Scenarios(private val t: Target) {
    val all = listOf(
        Scenario("get", 12, ::keepAlive),
        Scenario("offload", 2, ::offloaded),
        Scenario("post", 4, ::posts),
        Scenario("pipeline", 2, ::pipeline),
        Scenario("stream", 3, ::streams),
        Scenario("slow-head", 2, ::slowHead, timed = true),
        Scenario("slow-body", 2, ::slowBody, timed = true),
        Scenario("slow-reader", 2, ::slowReader, timed = true),
        Scenario("idle", 1, ::idle, timed = true),
        Scenario("drop", 2, ::drops),
        Scenario("malformed", 1, ::malformed),
        Scenario("tls", 4, ::tls),
        Scenario("tls-handshake", 1, ::tlsHandshakes),
        Scenario("tls-abandon", 1, ::tlsAbandon, timed = true),
        Scenario("churn", 3, ::churn),
        Scenario("cap", 1, ::cap, timed = true),
    )

    private val limitMillis = t.limitSeconds * 1000
    private val idleMillis = t.idleSeconds * 1000

    // ------------------------------------------------------------------ well-behaved clients

    /** Keep-alive GETs on one connection, which is replaced every few hundred. */
    private fun keepAlive(w: Worker) = w.using(w.open(t.main)) { conn ->
        repeat(w.between(50, 500)) {
            if (!w.running) return@using
            w.timed { hello(w, conn) }
        }
    }

    private fun hello(w: Worker, conn: Conn) {
        val reply = conn.send(GET_HELLO).read()
        w.expect(reply, 200, "GET /hello")
        if (!reply.body.contentEquals(HELLO)) throw WrongReply("GET /hello answered ${String(reply.body)}")
    }

    /** Keep-alive GETs answered from another thread, through the exchange's mail. */
    private fun offloaded(w: Worker) = w.using(w.open(t.main)) { conn ->
        repeat(w.between(20, 200)) {
            if (!w.running) return@using
            w.timed {
                val reply = conn.send("GET /offload HTTP/1.1\r\nHost: soak\r\n\r\n").read()
                w.expect(reply, 200, "GET /offload")
                if (String(reply.body) != "offloaded\n") throw WrongReply("GET /offload answered ${String(reply.body)}")
            }
        }
    }

    /** POSTs with Content-Length and chunked, mostly small, some up to 256 KiB echoed, a few of 1-4 MiB summed. */
    private fun posts(w: Worker) = w.using(w.open(t.main)) { conn ->
        repeat(w.between(10, 100)) {
            if (!w.running) return@using
            w.timed { post(w, conn) }
        }
    }

    private fun post(w: Worker, conn: Conn) {
        val chunked = w.chance(40)
        val roll = w.between(0, 100)
        val size = when {
            roll < 70 -> w.between(1, 2048)
            roll < 95 -> w.between(2048, 256 * 1024)
            else -> w.between(1 shl 20, 4 shl 20)
        }
        val at = w.between(0, NOISE.size - size)
        val large = size > SoakServer.ECHO_LIMIT
        val path = if (large) "/sink" else "/echo"
        writeBody(w, conn, path, at, size, chunked)
        val reply = conn.read()
        w.expect(reply, 200, "POST $path of $size bytes${if (chunked) " chunked" else ""}")
        if (large) {
            val crc = CRC32().apply { update(NOISE, at, size) }.value
            if (String(reply.body) !=
                "$size $crc"
            ) throw WrongReply("POST /sink: $size $crc, got ${String(reply.body)}")
        } else if (!reply.body.contentEquals(NOISE.copyOfRange(at, at + size))) {
            throw WrongReply("POST /echo of $size bytes came back different")
        }
    }

    private fun writeBody(w: Worker, conn: Conn, path: String, at: Int, size: Int, chunked: Boolean) {
        if (!chunked) {
            conn.send("POST $path HTTP/1.1\r\nHost: soak\r\nContent-Length: $size\r\n\r\n")
            conn.send(NOISE, at, size)
            return
        }
        conn.send("POST $path HTTP/1.1\r\nHost: soak\r\nTransfer-Encoding: chunked\r\n\r\n")
        var sent = 0
        while (sent < size) {
            val n = minOf(size - sent, w.between(1, 16 * 1024))
            conn.send("${Integer.toHexString(n)}\r\n")
            conn.send(NOISE, at + sent, n)
            conn.send("\r\n")
            sent += n
        }
        conn.send("0\r\n\r\n")
    }

    /** Batches of GETs and small POSTs written in one go, the replies read back in order. */
    private fun pipeline(w: Worker) = w.using(w.open(t.main)) { conn ->
        repeat(w.between(10, 100)) {
            if (!w.running) return@using
            val batch = w.between(2, 17)
            val kinds = List(batch) { w.chance(70) }
            val sizes = List(batch) { w.between(1, 512) }
            val out = StringBuilder()
            kinds.forEachIndexed { i, get ->
                if (get) {
                    out.append(GET_HELLO)
                } else {
                    out.append("POST /echo HTTP/1.1\r\nHost: soak\r\nContent-Length: ${sizes[i]}\r\n\r\n")
                    out.append("p".repeat(sizes[i]))
                }
            }
            w.timed {
                conn.send(out.toString())
                kinds.forEachIndexed { i, get ->
                    val reply = conn.read()
                    w.expect(reply, 200, "pipelined request $i of $batch")
                    val expected = if (get) 6 else sizes[i]
                    if (reply.body.size != expected) throw WrongReply("pipelined reply $i: ${reply.body.size} bytes")
                }
            }
        }
    }

    /** Streamed replies, some from another thread, read and checked; a fifth walked away from midway. */
    private fun streams(w: Worker) {
        val conn = w.open(t.main)
        var reset = false
        try {
            repeat(w.between(1, 20)) {
                if (!w.running) return
                val size = PIECES[w.between(0, PIECES.size)]
                val pieces = w.between(1, minOf(256, (4 shl 20) / size) + 1)
                val total = size.toLong() * pieces
                val off = if (w.chance(25)) "&off=1" else ""
                val abandon = w.chance(20)
                val limit = if (abandon) w.random.nextLong(0, total) else Long.MAX_VALUE
                var at = 0L
                w.timed {
                    conn.send("GET /stream?pieces=$pieces&size=$size$off HTTP/1.1\r\nHost: soak\r\n\r\n")
                    val reply = conn.read(limit = limit) { bytes, offset, n ->
                        // A run at a time against the pattern each piece repeats from its start.
                        var i = 0
                        while (i < n) {
                            val inPiece = (at % size).toInt()
                            val run = minOf(n - i, size - inPiece)
                            if (!java.util.Arrays.equals(
                                    bytes, offset + i, offset + i + run, PATTERN, inPiece,
                                    inPiece + run,
                                )
                            ) {
                                throw WrongReply("streamed bytes from $at of $total are wrong")
                            }
                            i += run
                            at += run
                        }
                    }
                    w.expect(reply, 200, "GET /stream")
                    if (!abandon && reply.length != total) throw WrongReply("stream: ${reply.length} of $total bytes")
                }
                if (abandon) {
                    reset = w.chance(50)
                    return
                }
            }
        } finally {
            w.done(conn, reset)
        }
    }

    // ------------------------------------------------------------------ clients the limits are for

    /** A head sent a byte at a time, each well inside the idle timeout: the head timeout must cut it off. */
    private fun slowHead(w: Worker) = w.using(w.open(t.main)) { conn ->
        conn.socket.soTimeout = 1
        conn.send("GET /hello HTTP/1.1\r\nHost: soak\r\nX-Slow: ")
        val start = System.nanoTime()
        var open = true
        while (open) {
            Thread.sleep(DRIP_MILLIS)
            val closed = cutOff(conn)
            val elapsed = millisSince(start)
            if (!closed && elapsed > limitMillis + SLACK_MILLIS) {
                throw WrongReply("A dripped head was not cut off after $elapsed ms")
            }
            if (!w.running) return@using
            open = !closed && drip(conn)
        }
        w.stats.latency.record(System.nanoTime() - start)
        w.stats.ok.incrementAndGet()
    }

    /** Sends one more byte of the head; false once the connection refuses it. */
    private fun drip(conn: Conn): Boolean = try {
        conn.send("s")
        true
    } catch (_: IOException) {
        false
    }

    /** True once the server has ended [conn]; a reply instead is an error. */
    private fun cutOff(conn: Conn): Boolean = try {
        conn.closedByServer()
    } catch (_: SocketTimeoutException) {
        false
    }

    /** A body that stops arriving: the body timeout must end it. */
    private fun slowBody(w: Worker) = w.using(w.open(t.main, timeoutMillis = (limitMillis + SLACK_MILLIS).toInt())) {
        val path = if (w.chance(50)) "/echo" else "/sink"
        val chunked = w.chance(50)
        if (chunked) {
            it.send("POST $path HTTP/1.1\r\nHost: soak\r\nTransfer-Encoding: chunked\r\n\r\n10\r\n0123456789")
        } else {
            it.send("POST $path HTTP/1.1\r\nHost: soak\r\nContent-Length: 100000\r\n\r\n0123456789")
        }
        val start = System.nanoTime()
        endsWithin(w, it, "A stalled body")
        w.stats.latency.record(System.nanoTime() - start)
        w.stats.ok.incrementAndGet()
    }

    /** The server ends [conn], perhaps after a 4xx: a reply other than that, or none in time, is an error. */
    private fun endsWithin(w: Worker, conn: Conn, what: String) {
        try {
            val reply = conn.read()
            if (reply.status >= Worker.SERVER_ERROR) w.stats.serverErrors.incrementAndGet()
            if (reply.status !in 400..499) throw WrongReply("$what was answered ${reply.status}")
            if (!conn.closedByServer()) throw WrongReply("$what was not closed")
        } catch (e: WrongReply) {
            // The connection closed with no reply, which is how a limit ends it.
            if (e.message?.startsWith("The connection closed") != true) throw e
        } catch (_: java.net.SocketException) {
            // Reset.
        } catch (_: SocketTimeoutException) {
            throw WrongReply("$what was not ended within ${limitMillis + SLACK_MILLIS} ms")
        }
    }

    /** A client that asks for 32 MiB and reads none of it: the write timeout must end it. */
    private fun slowReader(w: Worker) = w.using(w.open(t.main, receiveBuffer = 4096)) { conn ->
        val total = 2048L * 16384
        val off = if (w.chance(25)) "&off=1" else ""
        conn.send("GET /stream?pieces=2048&size=16384$off HTTP/1.1\r\nHost: soak\r\n\r\n")
        val start = System.nanoTime()
        Thread.sleep(limitMillis + SLACK_MILLIS)
        conn.socket.soTimeout = (limitMillis + SLACK_MILLIS).toInt()
        val read = try {
            conn.drain()
        } catch (_: SocketTimeoutException) {
            throw WrongReply("A reader that stopped was not cut off")
        }
        if (read >= total) throw WrongReply("A reader that stopped was sent the whole reply")
        w.stats.latency.record(System.nanoTime() - start)
        w.stats.ok.incrementAndGet()
    }

    /** A connection that sends nothing, or nothing after one request: the idle timeout must close it. */
    private fun idle(w: Worker) =
        w.using(w.open(t.main, timeoutMillis = (idleMillis + SLACK_MILLIS).toInt())) { conn ->
            if (w.chance(50)) hello(w, conn)
            val start = System.nanoTime()
            try {
                conn.closedByServer()
            } catch (_: SocketTimeoutException) {
                throw WrongReply("An idle connection was not closed within ${idleMillis + SLACK_MILLIS} ms")
            }
            val elapsed = millisSince(start)
            if (elapsed < idleMillis - EARLY_MILLIS) throw WrongReply("An idle connection was closed after $elapsed ms")
            w.stats.latency.record(System.nanoTime() - start)
            w.stats.ok.incrementAndGet()
        }

    /** Connections that go away mid-request (head, body, chunked body, or before the reply), some with a reset. */
    private fun drops(w: Worker) {
        val conn = w.open(t.main)
        val reset = w.chance(50)
        try {
            when (w.between(0, 6)) {
                0 -> conn.send("GET /hello HTTP/1.1\r\nHost: so")

                1 -> conn.send("POST /echo HTTP/1.1\r\nHost: soak\r\nContent-Length: 5000\r\n\r\n").send(NOISE, 0, 1000)

                2 -> conn.send("POST /sink HTTP/1.1\r\nHost: soak\r\nTransfer-Encoding: chunked\r\n\r\n400\r\n")
                    .send(NOISE, 0, 300)

                3 -> conn.send("POST /sink HTTP/1.1\r\nHost: soak\r\nContent-Length: 3000000\r\n\r\n")
                    .send(NOISE, 0, w.between(1, 2_000_000))

                4 -> conn.send(GET_HELLO + GET_HELLO + GET_HELLO)

                else -> conn.send("GET /stream?pieces=256&size=65536 HTTP/1.1\r\nHost: soak\r\n\r\n")
            }
            if (w.chance(50)) Thread.sleep(w.between(1, 50).toLong())
            w.timed { }
        } finally {
            w.done(conn, reset)
        }
    }

    /** One malformed request: the status it must get, and the connection must close after it. */
    private fun malformed(w: Worker) {
        val (request, status) = MALFORMED[w.between(0, MALFORMED.size)]
        w.using(w.open(t.main)) { conn ->
            w.timed {
                conn.send(request)
                val reply = conn.read()
                w.expect(reply, status, "malformed ${request.take(40).replace("\r\n", "\\r\\n")}")
                if (!conn.closedByServer()) throw WrongReply("A refused request's connection stayed open")
            }
        }
        Thread.sleep(MALFORMED_PAUSE_MILLIS)
    }

    // ------------------------------------------------------------------ TLS

    /** Keep-alive requests over TLS: GETs, POSTs and the odd streamed reply, reconnecting now and then. */
    private fun tls(w: Worker) = w.using(w.tls(t.tls)) { conn ->
        repeat(w.between(1, 100)) {
            if (!w.running) return@using
            w.timed {
                when (w.between(0, 20)) {
                    in 0..15 -> hello(w, conn)

                    in 16..18 -> post(w, conn)

                    else -> {
                        val reply = conn.send("GET /stream?pieces=16&size=16384 HTTP/1.1\r\nHost: soak\r\n\r\n").read()
                        w.expect(reply, 200, "GET /stream over TLS")
                        if (reply.body.size != 16 * 16384) throw WrongReply("TLS stream: ${reply.body.size} bytes")
                    }
                }
            }
        }
    }

    /** A full handshake for every request: the session is thrown away so none is resumed. */
    private fun tlsHandshakes(w: Worker) {
        val start = System.nanoTime()
        w.using(w.tls(t.tls)) { conn ->
            (conn.socket as SSLSocket).session.invalidate()
            val reply = conn.send("GET /hello HTTP/1.1\r\nHost: soak\r\nConnection: close\r\n\r\n").read()
            w.expect(reply, 200, "GET /hello after a full handshake")
            if (!conn.closedByServer()) throw WrongReply("Connection: close left the connection open")
        }
        w.stats.latency.record(System.nanoTime() - start)
        w.stats.ok.incrementAndGet()
    }

    /** Handshakes given up on: before a byte, after garbage, after a ClientHello, or left to the handshake timeout. */
    private fun tlsAbandon(w: Worker) {
        val kind = w.between(0, 4)
        val conn = w.open(t.tls, timeoutMillis = (limitMillis + SLACK_MILLIS).toInt())
        var reset = w.chance(50)
        try {
            w.timed {
                when (kind) {
                    0 -> Unit

                    1 -> {
                        conn.send(NOISE, w.between(0, 1000), w.between(1, 600))
                        if (w.chance(50)) conn.drain()
                    }

                    2 -> clientHello(w, conn)

                    else -> {
                        reset = false
                        try {
                            conn.closedByServer()
                        } catch (_: SocketTimeoutException) {
                            throw WrongReply("A silent TLS client outlived the handshake timeout")
                        }
                    }
                }
            }
        } finally {
            w.done(conn, reset)
        }
    }

    /** Sends a real ClientHello and reads the start of the server's answer, then gives up. */
    private fun clientHello(w: Worker, conn: Conn) {
        val engine = t.client.createSSLEngine("127.0.0.1", t.tls).apply { useClientMode = true }
        engine.beginHandshake()
        val net = ByteBuffer.allocate(engine.session.packetBufferSize)
        engine.wrap(ByteBuffer.allocate(0), net)
        net.flip()
        conn.send(net.array(), 0, net.limit())
        val answer = ByteArray(w.between(1, 200))
        if (conn.socket.getInputStream().read(answer) <= 0) throw WrongReply("No answer to a ClientHello")
        engine.closeOutbound()
    }

    // ------------------------------------------------------------------ connections

    /** A new connection for one request, closed by the server; or connected and dropped at once. */
    private fun churn(w: Worker) {
        if (w.chance(10)) {
            w.done(w.open(t.main), reset = w.chance(50))
            return
        }
        w.timed {
            w.using(w.open(t.main)) { conn ->
                val reply = conn.send("GET /hello HTTP/1.1\r\nHost: soak\r\nConnection: close\r\n\r\n").read()
                w.expect(reply, 200, "GET /hello, Connection: close")
                if (!conn.closedByServer()) throw WrongReply("Connection: close left the connection open")
            }
        }
    }

    /**
     * Fills the capped server to its cap, then opens more: those must wait unserved while the cap is full, and all be
     * served once the first ones close. Timed from the close to the last of the rest being served.
     */
    private fun cap(w: Worker) {
        val held = List(t.capLimit) { w.open(t.cap) }
        val extra = mutableListOf<Conn>()
        try {
            val filling = System.nanoTime()
            held.forEach { hello(w, it) }
            val filled = millisSince(filling)
            val full = System.nanoTime()
            repeat(EXTRA) { extra += w.open(t.cap) }
            extra.forEach { it.send(GET_HELLO) }
            Thread.sleep(CAP_WAIT_MILLIS)
            val served = extra.indices.filter { i ->
                val conn = extra[i]
                conn.socket.soTimeout = 1
                val answered = try {
                    conn.socket.getInputStream().read() >= 0
                } catch (_: SocketTimeoutException) {
                    false
                }
                conn.socket.soTimeout = Worker.TIMEOUT_MILLIS
                answered
            }
            if (served.isNotEmpty()) {
                throw WrongReply(
                    "${served.size} of $EXTRA connections past maxConnections were served while the cap was full " +
                        "(filling it took $filled ms, checked ${millisSince(full)} ms after it was full)",
                )
            }
            val start = System.nanoTime()
            held.forEach { w.done(it) }
            extra.forEach { hello(w, it) }
            w.stats.latency.record(System.nanoTime() - start)
            w.stats.ok.incrementAndGet()
        } finally {
            held.forEach { w.done(it) }
            extra.forEach { w.done(it) }
        }
        Thread.sleep(CAP_PAUSE_MILLIS)
    }

    private fun millisSince(start: Long) = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)

    companion object {
        const val SLACK_MILLIS = 3_000L
        const val EARLY_MILLIS = 1_000L
        const val DRIP_MILLIS = 400L
        const val EXTRA = 16
        const val CAP_WAIT_MILLIS = 300L
        const val CAP_PAUSE_MILLIS = 200L
        const val MALFORMED_PAUSE_MILLIS = 5L
        const val GET_HELLO = "GET /hello HTTP/1.1\r\nHost: soak\r\n\r\n"
        val HELLO = "hello\n".toByteArray()
        val PIECES = intArrayOf(64, 1024, 4096, 16384, 65536)
        val PATTERN = ByteArray(SoakServer.MAX_PIECE) { (it % 251).toByte() }
        val NOISE = ByteArray(5 shl 20).also { SplittableRandom(SEED).nextBytes(it) }
        private const val SEED = 372L

        val MALFORMED: List<Pair<String, Int>> = listOf(
            "GET / HTTP/1.1\r\nHost: soak\r\nContent-Length: 5\r\nTransfer-Encoding: chunked\r\n\r\n" to 400,
            "GET / HTTP/1.1\r\nHost: soak\r\nX-Fold: a\r\n folded\r\n\r\n" to 400,
            "GET / HTTP/9.9\r\nHost: soak\r\n\r\n" to 505,
            "GET /${"a".repeat(9000)} HTTP/1.1\r\nHost: soak\r\n\r\n" to 414,
            "GET / HTTP/1.1\r\nHost: soak\r\nX-Big: ${"b".repeat(20_000)}\r\n\r\n" to 431,
            "GET / HTTP/1.1\r\nHost: soak\r\n${(1..101).joinToString("") { "X-$it: v\r\n" }}\r\n" to 431,
            "POST /echo HTTP/1.1\r\nHost: soak\r\nTransfer-Encoding: gzip, chunked\r\n\r\n" to 501,
            "POST /echo HTTP/1.1\r\nHost: soak\r\nTransfer-Encoding: gzip\r\n\r\n" to 400,
            "POST /echo HTTP/1.1\r\nHost: soak\r\nContent-Length: abc\r\n\r\n" to 400,
            "GET / HTTP/1.1\r\nHost: soak\r\nX-Nul: a\u0000b\r\n\r\n" to 400,
            "GET / HTTP/1.1\r\nUser-Agent: soak\r\n\r\n" to 400,
            "GET / HTTP/1.1\r\nHost: so ak\r\n\r\n" to 400,
            "\u0016\u0003\u0001\u0002\u0000\u0001\u0000\u0001ü\u0003\u0003\r\n\r\n" to 400,
            "POST /echo HTTP/1.1\r\nHost: soak\r\nTransfer-Encoding: chunked\r\n\r\nzz\r\nabc\r\n0\r\n\r\n" to 400,
            "POST /echo HTTP/1.1\r\nHost: soak\r\nContent-Length: 2097152\r\n\r\n" to 413,
        )
    }
}
