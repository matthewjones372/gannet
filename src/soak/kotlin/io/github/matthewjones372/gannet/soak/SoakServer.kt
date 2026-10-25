package io.github.matthewjones372.gannet.soak

import io.github.matthewjones372.gannet.GannetConfig
import io.github.matthewjones372.gannet.GannetEngine
import io.github.matthewjones372.gannet.GannetServer
import io.github.matthewjones372.gannet.GannetTls
import io.github.matthewjones372.gannet.HttpExchange
import io.github.matthewjones372.gannet.HttpHandler
import java.io.IOException
import java.lang.management.BufferPoolMXBean
import java.lang.management.ManagementFactory
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import java.util.logging.Handler
import java.util.logging.Level
import java.util.logging.LogRecord
import java.util.logging.Logger
import java.util.zip.CRC32
import javax.management.ObjectName

/**
 * The server side of the soak, run in a JVM of its own so it can be pinned to cores apart from the load and so what
 * it holds is all the server's. It starts three servers (plain, TLS, and a plain one with a small connection cap),
 * prints `READY`, and then answers the driver's commands on stdin, one line each:
 *
 * - `sample`: runs a full GC and prints one `SAMPLE` line of `key=value` pairs: heap after GC, direct and native
 *   memory, threads, descriptors, mappings, each server's connections, fibers and buffers, and what the handlers and
 *   the server's log have counted.
 * - `warnings`: prints the server's warnings collected so far, one `WARNING` line each, then `END`.
 * - `stop`: stops the servers, samples again, prints it as `STOPPED`, and exits.
 *
 * Arguments are `key=value`: `engine`, `carriers`, `keystore`.
 */
fun main(args: Array<String>) {
    val options = args.associate { it.substringBefore('=') to it.substringAfter('=') }
    val engine = GannetEngine.valueOf(options["engine"] ?: "AUTO")
    val carriers = options["carriers"]?.toInt() ?: 2
    val keyStore = Path.of(requireNotNull(options["keystore"]) { "keystore=<path> is required" })
    SoakServer(engine, carriers, keyStore).run()
}

/** The servers, their handler, and the counting. */
internal class SoakServer(engine: GannetEngine, carriers: Int, keyStore: Path) {
    private val warnings = WarningLog()
    private val counts = ConcurrentHashMap<String, AtomicLong>()
    private val offload = Executors.newVirtualThreadPerTaskExecutor()
    private val pattern = ByteArray(MAX_PIECE) { (it % PATTERN).toByte() }
    private val fdsBeforeStart: Int
    private val fdTargetsBeforeStart: Map<String, String>
    private val threadsBeforeStart: Int

    private val base = GannetConfig(
        carriers = carriers,
        engine = engine,
        maxBodyBytes = MAX_BODY,
        idleTimeout = Duration.ofSeconds(IDLE_SECONDS),
        headTimeout = Duration.ofSeconds(LIMIT_SECONDS),
        bodyTimeout = Duration.ofSeconds(LIMIT_SECONDS),
        writeTimeout = Duration.ofSeconds(LIMIT_SECONDS),
        handshakeTimeout = Duration.ofSeconds(LIMIT_SECONDS),
        stopGrace = Duration.ofSeconds(STOP_GRACE_SECONDS),
        tick = Duration.ofMillis(TICK_MILLIS),
        maxConnections = MAIN_CAP,
        bufferCount = BUFFERS,
    )
    private val servers: Map<String, GannetServer>

    init {
        Logger.getLogger(LOGGER).addHandler(warnings)
        val tls = GannetTls(keyStore, Certs.password)
        // The JDK opens /dev/random and /dev/urandom on the first secure random number, and keeps them for good.
        java.security.SecureRandom().nextBytes(ByteArray(1))
        fdsBeforeStart = Proc.fds()
        fdTargetsBeforeStart = Proc.fdTargets()
        threadsBeforeStart = ManagementFactory.getThreadMXBean().threadCount
        val handler = HttpHandler { handle(it) }
        servers = linkedMapOf(
            "main" to GannetServer.start(base, handler),
            "tls" to GannetServer.start(
                base.copy(tls = tls, maxHandshakes = TLS_HANDSHAKES),
                handler,
            ),
            // Idle connections held at the cap must outlast a load generator starved of cpu for a few seconds.
            "cap" to GannetServer.start(
                base.copy(maxConnections = CAP, idleTimeout = Duration.ofSeconds(CAP_IDLE_SECONDS)),
                handler,
            ),
        )
    }

    fun run() {
        val engine = servers.getValue("main").engine
        val ports = servers.entries.joinToString(" ") { (name, server) -> "$name=${server.port}" }
        println("READY engine=$engine cap=$CAP head=$LIMIT_SECONDS idle=$IDLE_SECONDS $ports")
        while (true) {
            when (readlnOrNull()?.trim()) {
                null, "stop" -> return stop()

                "sample" -> println("SAMPLE " + format(sample(gc = true) + serverStats()))

                "histogram" -> {
                    histogram()
                    println("END")
                }

                "warnings" -> {
                    warnings.messages.forEach { println("WARNING " + it.replace('\n', ' ')) }
                    println("END")
                }

                else -> println("ERROR unknown command")
            }
        }
    }

    private fun stop() {
        servers.values.forEach { it.stop(Duration.ofSeconds(STOP_GRACE_SECONDS)) }
        offload.shutdown()
        val after = sample(gc = true) + mapOf(
            "fdsBeforeStart" to fdsBeforeStart.toString(),
            "threadsBeforeStart" to threadsBeforeStart.toString(),
        )
        val opened = Proc.fdTargets().filter { (fd, target) -> fdTargetsBeforeStart[fd] != target }
        System.err.println("Descriptors open after stop that were not before start: $opened")
        println("STOPPED " + format(after))
        System.out.flush()
    }

    // ------------------------------------------------------------------ the handler

    private fun handle(exchange: HttpExchange) {
        val request = exchange.request
        count(request.path)
        when (request.path) {
            "/hello" -> exchange.respond(OK, listOf("Content-Type" to "text/plain"), HELLO)

            "/echo" -> exchange.respond(OK, body = request.body.readAll(ECHO_LIMIT))

            "/sink" -> sink(exchange)

            "/stream" -> {
                val (pieces, size) = streamShape(request.query)
                if (query(request.query, "off") == "1") {
                    exchange.offload(offload) { stream(it, pieces, size) }
                } else {
                    stream(exchange, pieces, size)
                }
            }

            "/offload" -> exchange.offload(offload) { it.respond(OK, body = OFFLOADED) }

            else -> exchange.respond(NOT_FOUND)
        }
    }

    /** Reads the body a piece at a time and answers with its length and CRC-32. */
    private fun sink(exchange: HttpExchange) {
        val crc = CRC32()
        val piece = ByteArray(SINK_PIECE)
        var total = 0L
        while (true) {
            val n = exchange.request.body.read(piece, 0, piece.size)
            if (n < 0) break
            crc.update(piece, 0, n)
            total += n
        }
        exchange.respond(OK, body = "$total ${crc.value}".toByteArray())
    }

    /** Streams [pieces] pieces of [size] bytes of the pattern. A client that goes away mid-stream is counted. */
    private fun stream(exchange: HttpExchange, pieces: Int, size: Int) {
        val out = exchange.stream(OK, listOf("Content-Type" to "application/octet-stream"))
        try {
            repeat(pieces) { out.write(pattern, 0, size) }
            out.close()
        } catch (e: IOException) {
            count("stream-abandoned")
            throw e
        }
    }

    private fun streamShape(query: String?): Pair<Int, Int> {
        val pieces = query(query, "pieces")?.toIntOrNull() ?: DEFAULT_PIECES
        val size = query(query, "size")?.toIntOrNull() ?: DEFAULT_PIECE
        return pieces.coerceIn(1, MAX_PIECES) to size.coerceIn(1, MAX_PIECE)
    }

    private fun query(query: String?, name: String): String? = query?.split('&')
        ?.firstOrNull { it.substringBefore('=') == name }?.substringAfter('=')

    private fun count(name: String) = counts.computeIfAbsent(name) { AtomicLong() }.incrementAndGet()

    // ------------------------------------------------------------------ sampling

    /** The heap's largest classes after a full GC, to stderr (the run's server.log): what to look at if heap grows. */
    private fun histogram() {
        val text = classHistogram()
        System.err.println("Class histogram at ${java.time.Instant.now()}:")
        text.lineSequence().take(HISTOGRAM_LINES).forEach { System.err.println(it) }
        System.err.flush()
    }

    private fun sample(gc: Boolean): Map<String, String> {
        var live = 0L
        if (gc) {
            // A full GC, then a pause for the cleaner: native memory freed by an automatic arena goes once its segment
            // is collected and the cleaner has run. The class histogram then collects again and counts what is
            // live to the byte, where the heap's used figure moves in whole regions.
            System.gc()
            Thread.sleep(GC_PAUSE_MILLIS)
            live = Regex("Total\\s+\\d+\\s+(\\d+)").find(classHistogram())?.groupValues?.get(1)?.toLong() ?: 0L
        }
        val memory = ManagementFactory.getMemoryMXBean()
        val pools = ManagementFactory.getPlatformMXBeans(BufferPoolMXBean::class.java).associateBy { it.name }
        val os = ManagementFactory.getOperatingSystemMXBean() as com.sun.management.OperatingSystemMXBean
        val gcs = ManagementFactory.getGarbageCollectorMXBeans()
        val native = nativeMemory()
        return linkedMapOf(
            "heap" to live.toString(),
            "heapUsed" to memory.heapMemoryUsage.used.toString(),
            "nonHeap" to memory.nonHeapMemoryUsage.used.toString(),
            "direct" to (pools["direct"]?.memoryUsed ?: 0L).toString(),
            "directCount" to (pools["direct"]?.count ?: 0L).toString(),
            "mapped" to (pools["mapped"]?.memoryUsed ?: 0L).toString(),
            "nmtCommitted" to native.first.toString(),
            "nmtOther" to native.second.toString(),
            "rss" to (Proc.rssKib() * KIB).toString(),
            "maps" to Proc.mappings().toString(),
            "fds" to Proc.fds().toString(),
            "threads" to ManagementFactory.getThreadMXBean().threadCount.toString(),
            "gannetThreads" to Thread.getAllStackTraces().keys.count { it.name.startsWith("gannet-") }.toString(),
            "cpuNanos" to os.processCpuTime.toString(),
            "gcCount" to gcs.sumOf { it.collectionCount }.toString(),
            "gcMillis" to gcs.sumOf { it.collectionTime }.toString(),
            "warnings" to warnings.count.get().toString(),
        ) + counts.entries.associate { (name, n) -> "count$name" to n.get().toString() }
    }

    private fun serverStats(): Map<String, String> = servers.entries.flatMap { (name, server) ->
        val s = server.stats()
        listOf(
            "$name.open" to s.open,
            "$name.fibers" to s.fibers,
            "$name.sessions" to s.sessions,
            "$name.zombies" to s.zombies,
            "$name.handshakes" to s.handshakes,
            "$name.freeBuffers" to s.freeBuffers,
            "$name.buffers" to s.buffers,
            "$name.slotSlabs" to s.slotSlabs,
            "$name.engineWaiting" to s.engineWaiting,
        ).map { it.first to it.second.toString() }
    }.toMap()

    /** `GC.class_histogram`, which collects first: every class's live instances and bytes, and their total. */
    private fun classHistogram(): String = try {
        ManagementFactory.getPlatformMBeanServer().invoke(
            ObjectName("com.sun.management:type=DiagnosticCommand"),
            "gcClassHistogram",
            arrayOf<Any>(arrayOf<String>()),
            arrayOf(Array<String>::class.java.name),
        ) as String
    } catch (e: Exception) {
        "no class histogram: $e"
    }

    /** NMT's committed total and its "Other" category (direct and FFM memory), in bytes; zeros without NMT. */
    private fun nativeMemory(): Pair<Long, Long> = try {
        val text = ManagementFactory.getPlatformMBeanServer().invoke(
            ObjectName("com.sun.management:type=DiagnosticCommand"),
            "vmNativeMemory",
            arrayOf<Any>(arrayOf("summary")),
            arrayOf(Array<String>::class.java.name),
        ) as String
        val total = Regex("Total: reserved=(\\d+)KB, committed=(\\d+)KB").find(text)?.groupValues?.get(2)
        val other = Regex("Other \\(reserved=(\\d+)KB, committed=(\\d+)KB\\)").find(text)?.groupValues?.get(2)
        (total?.toLong() ?: 0L) * KIB to (other?.toLong() ?: 0L) * KIB
    } catch (_: Exception) {
        0L to 0L
    }

    private fun format(values: Map<String, String>) = values.entries.joinToString(" ") { "${it.key}=${it.value}" }

    /** Counts the server's warnings, and keeps the first few: any at all is a failure of the soak. */
    private class WarningLog : Handler() {
        val count = AtomicLong()
        val messages: MutableList<String> = CopyOnWriteArrayList()

        override fun publish(record: LogRecord) {
            if (record.level.intValue() < Level.WARNING.intValue()) return
            count.incrementAndGet()
            if (messages.size < KEPT_WARNINGS) {
                val cause = record.thrown?.cause?.let { " caused by $it" }.orEmpty()
                val thrown = record.thrown?.let { " : $it$cause" }.orEmpty()
                val text = java.text.MessageFormat(record.message).format(record.parameters ?: emptyArray<Any>())
                messages += "${record.level} $text$thrown"
            }
        }

        override fun flush() = Unit

        override fun close() = Unit
    }

    companion object {
        const val LOGGER = "io.github.matthewjones372.gannet"
        const val CAP = 32
        private const val CAP_IDLE_SECONDS = 120L
        const val LIMIT_SECONDS = 2L
        const val IDLE_SECONDS = 5L
        const val MAX_BODY = 8L shl 20
        const val ECHO_LIMIT = 1L shl 20
        const val MAX_PIECE = 64 * 1024
        const val MAX_PIECES = 4096
        private const val PATTERN = 251
        private const val DEFAULT_PIECES = 16
        private const val DEFAULT_PIECE = 4096
        private const val SINK_PIECE = 64 * 1024
        private const val STOP_GRACE_SECONDS = 5L
        private const val TICK_MILLIS = 100L
        private const val MAIN_CAP = 4096
        private const val BUFFERS = 1024
        private const val TLS_HANDSHAKES = 16
        private const val OK = 200
        private const val NOT_FOUND = 404
        private const val KIB = 1024L
        private const val GC_PAUSE_MILLIS = 200L
        private const val KEPT_WARNINGS = 50
        private const val HISTOGRAM_LINES = 40
        private val HELLO = "hello\n".toByteArray()
        private val OFFLOADED = "offloaded\n".toByteArray()
    }
}
