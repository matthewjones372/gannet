package io.github.matthewjones372.gannet.soak

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.Locale
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

/**
 * The soak's driver. For each engine it starts [SoakServer] in a JVM of its own, pinned to its own cores, warms it
 * up, takes a baseline, drives the mixed [Load] for the configured minutes while sampling both sides every interval,
 * stops the load, waits out a grace period, and then checks that nothing leaked or degraded:
 *
 * - descriptors and threads are back to the baseline, and every connection, fiber and receive buffer has been given
 *   back on every server;
 * - heap after GC (and direct memory) do not trend upward over the steady-state window, by the slope of a least
 *   squares line;
 * - keep-alive GET latency in the last interval is not much worse than in the first;
 * - no request failed in a way the server is to blame for, no 5xx was answered, and the server logged no warning.
 *
 * Each engine's samples go to `samples.csv` and its verdict to `summary.txt` under the output directory. Settings are
 * system properties, which the `soak` Gradle task fills from `-Psoak...` properties; see [Settings].
 */
fun main() {
    val settings = Settings.fromSystem()
    Files.createDirectories(settings.out)
    settings.loadCpus?.let { pin(it) }
    val keyStore = Certs.make(settings.out)
    println(settings.describe())
    val verdicts = settings.engines.map { Run(it, settings, keyStore).execute() }
    println()
    verdicts.forEach { println(it.headline) }
    exitProcess(if (verdicts.all { it.passed }) 0 else 1)
}

/** What the soak runs, from system properties `soak.*`. */
internal class Settings(
    val minutes: Double,
    val engines: List<String>,
    val intervalSeconds: Long,
    val warmupSeconds: Long,
    val graceSeconds: Long,
    val serverCpus: String?,
    val loadCpus: String?,
    val carriers: Int,
    val heapSlopeLimit: Double,
    val out: Path,
    val scenarios: Set<String>?,
) {
    fun describe() = "Soak: ${fmt(minutes)} min per engine on $engines, ${intervalSeconds}s intervals, " +
        "${warmupSeconds}s warm-up, ${graceSeconds}s grace, scenarios ${scenarios ?: "all"}; " +
        "server on cpus ${serverCpus ?: "any"} with $carriers " +
        "carriers, load on cpus ${loadCpus ?: "any"}; heap slope limit $heapSlopeLimit MB/min; output in $out"

    companion object {
        private fun prop(name: String) = System.getProperty("soak.$name")?.takeIf { it.isNotBlank() }

        private fun long(name: String, default: Long) = prop(name)?.toLong() ?: default

        private fun double(name: String, default: Double) = prop(name)?.toDouble() ?: default

        fun fromSystem(): Settings {
            val minutes = double("minutes", DEFAULT_MINUTES)
            val cores = Runtime.getRuntime().availableProcessors()
            // Half the machine each, when there are cores enough to split; else nothing is pinned.
            val split = cores >= MIN_SPLIT_CORES
            val serverCpus = prop("serverCpus") ?: if (split) "0-${cores / 2 - 1}" else null
            val loadCpus = prop("loadCpus") ?: if (split) "${cores / 2}-${cores - 1}" else null
            val interval = long(
                "intervalSeconds",
                (minutes * SECONDS_PER_MINUTE / TARGET_INTERVALS).toLong().coerceIn(MIN_INTERVAL, MAX_INTERVAL),
            )
            return Settings(
                minutes = minutes,
                engines = (prop("engines") ?: "IO_URING,EPOLL").split(',').map { it.trim().uppercase() },
                intervalSeconds = interval,
                warmupSeconds = long(
                    "warmupSeconds",
                    (minutes * SECONDS_PER_MINUTE * WARMUP_SHARE).toLong().coerceIn(MIN_WARMUP, MAX_WARMUP),
                ),
                graceSeconds = long("graceSeconds", DEFAULT_GRACE),
                serverCpus = serverCpus,
                loadCpus = loadCpus,
                carriers = long("carriers", serverCpus?.let { cpuCount(it) }?.toLong() ?: 2L).toInt(),
                heapSlopeLimit = double("heapSlope", DEFAULT_SLOPE),
                out = Path.of(prop("out") ?: "build/soak"),
                scenarios = prop("scenarios")?.split(',')?.map { it.trim() }?.toSet(),
            )
        }

        /** How many cpus a taskset list such as `0-1,4` names. */
        fun cpuCount(list: String): Int = list.split(',').sumOf { part ->
            if ('-' in part) part.substringAfter('-').toInt() - part.substringBefore('-').toInt() + 1 else 1
        }

        private const val DEFAULT_MINUTES = 5.0
        private const val MIN_SPLIT_CORES = 4
        private const val SECONDS_PER_MINUTE = 60
        private const val TARGET_INTERVALS = 30
        private const val MIN_INTERVAL = 5L
        private const val MAX_INTERVAL = 30L
        private const val WARMUP_SHARE = 0.05
        private const val MIN_WARMUP = 15L
        private const val MAX_WARMUP = 60L
        private const val DEFAULT_GRACE = 90L
        private const val DEFAULT_SLOPE = 0.25
    }
}

/** Pins this whole JVM, every thread it has and will have, to [cpus]. */
private fun pin(cpus: String) {
    val pid = ProcessHandle.current().pid().toString()
    val process = ProcessBuilder("taskset", "-a", "-p", "-c", cpus, pid).redirectErrorStream(true).start()
    val output = process.inputStream.readAllBytes().decodeToString()
    if (!process.waitFor(TASKSET_SECONDS, TimeUnit.SECONDS) || process.exitValue() != 0) {
        System.err.println("Could not pin the load to cpus $cpus: $output")
    }
}

private const val TASKSET_SECONDS = 10L

/** One `SAMPLE` or `STOPPED` line from the server, as numbers by key. */
internal class Sample(val values: Map<String, Long>) {
    operator fun get(key: String): Long = values[key] ?: 0L

    fun mb(key: String): Double = this[key] / MB

    companion object {
        const val MB = 1024.0 * 1024.0

        fun parse(line: String): Sample = Sample(
            line.substringAfter(' ').split(' ').filter { '=' in it }
                .associate { it.substringBefore('=') to (it.substringAfter('=').toLongOrNull() ?: 0L) },
        )
    }
}

/** One interval of the main phase: when it ended, what the load saw in it, what the server held at its end. */
internal class Interval(
    val seconds: Double,
    val latency: Map<String, Histogram.Snapshot>,
    val errors: Map<String, Long>,
    val sample: Sample,
    val load: List<Double>,
    val busy: Map<Int, Int>,
    val serverCpu: Double,
)

/** One check and whether it held. */
internal class Check(val name: String, val passed: Boolean, val detail: String)

/** An engine's result. */
internal class Verdict(val engine: String, val checks: List<Check>, val minutes: Double) {
    val passed = checks.all { it.passed }
    val headline: String
        get() = "$engine: ${if (passed) "PASSED" else "FAILED"} after ${fmt(minutes)} min" +
            checks.filterNot { it.passed }.joinToString("") { "\n  FAILED ${it.name}: ${it.detail}" }
}

/** The server JVM: its commands go in on stdin, and its replies come back, one line each, on stdout. */
private class ServerProcess(command: List<String>, log: File) : AutoCloseable {
    private val process = ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.appendTo(log)).start()
    private val lines = LinkedBlockingQueue<String>()
    private val writer = process.outputStream.bufferedWriter()
    val pid: Long = process.pid()

    init {
        Thread.ofPlatform().daemon().name("soak-server-out").start {
            process.inputStream.bufferedReader().useLines { all ->
                all.forEach { line ->
                    if (line == "END" || REPLIES.any { line.startsWith(it) }) {
                        lines.put(line)
                    } else {
                        log.appendText(line + "\n")
                    }
                }
            }
            lines.put(EOF)
        }
    }

    /** The next line starting with [prefix], waiting up to [seconds]. */
    fun await(prefix: String, seconds: Long = REPLY_SECONDS): String {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds)
        while (true) {
            val left = deadline - System.nanoTime()
            val line = lines.poll(maxOf(0L, left), TimeUnit.NANOSECONDS)
                ?: error("The server (pid $pid) said nothing starting $prefix within $seconds s")
            check(line != EOF) { "The server (pid $pid) exited while waiting for $prefix" }
            if (line.startsWith(prefix)) return line
        }
    }

    fun command(text: String) {
        writer.write(text + "\n")
        writer.flush()
    }

    fun sample(): Sample {
        command("sample")
        return Sample.parse(await("SAMPLE"))
    }

    fun warnings(): List<String> {
        command("warnings")
        val all = mutableListOf<String>()
        while (true) {
            val line = await("")
            if (line == "END") return all
            if (line.startsWith("WARNING ")) all += line.removePrefix("WARNING ")
        }
    }

    /** Has the server write its heap's class histogram to its log. */
    fun histogram() {
        command("histogram")
        await("END")
    }

    fun stop(): Sample {
        command("stop")
        return Sample.parse(await("STOPPED", STOP_SECONDS))
    }

    /** Waits for the server to exit, and ends it (this one process, by its handle) if it does not. */
    override fun close() {
        if (!process.waitFor(EXIT_SECONDS, TimeUnit.SECONDS)) {
            process.destroy()
            if (!process.waitFor(EXIT_SECONDS, TimeUnit.SECONDS)) process.destroyForcibly()
        }
    }

    private companion object {
        const val EOF = "\u0000EOF"
        val REPLIES = listOf("READY", "SAMPLE", "STOPPED", "WARNING")
        const val REPLY_SECONDS = 120L
        const val STOP_SECONDS = 120L
        const val EXIT_SECONDS = 30L
    }
}

/** One engine's soak, start to verdict. */
private class Run(private val engine: String, private val settings: Settings, private val keyStore: Path) {
    private val dir: Path = settings.out.resolve(engine.lowercase()).also { Files.createDirectories(it) }
    private val csv = dir.resolve("samples.csv").toFile()
    private val log = dir.resolve("server.log").toFile().also { it.writeText("") }
    private val intervals = mutableListOf<Interval>()
    private val tag = when (engine) {
        "IO_URING" -> "uring"
        "EPOLL" -> "epoll"
        else -> engine.lowercase()
    }

    fun execute(): Verdict {
        println("\n=== $engine: starting the server")
        ServerProcess(serverCommand(), log).use { server ->
            val ready = server.await("READY", READY_SECONDS)
            println("[$tag] $ready (server pid ${server.pid}, driver pid ${ProcessHandle.current().pid()})")
            val fields = ready.split(' ').filter { '=' in it }
                .associate { it.substringBefore('=') to it.substringAfter('=') }
            val target = Target(
                main = fields.getValue("main").toInt(),
                limits = fields.getValue("limits").toInt(),
                tls = fields.getValue("tls").toInt(),
                tlsLimits = fields.getValue("tls-limits").toInt(),
                cap = fields.getValue("cap").toInt(),
                capLimit = SoakServer.CAP,
                limitSeconds = fields.getValue("head").toLong(),
                idleSeconds = fields.getValue("idle").toLong(),
                client = Certs.client(keyStore),
            )
            val load = Load(target, settings.scenarios)
            val started = server.sample()

            println("[$tag] warming up for ${settings.warmupSeconds}s")
            load.start()
            Thread.sleep(TimeUnit.SECONDS.toMillis(settings.warmupSeconds))
            val warmStragglers = load.stop(STOP_WAIT_MILLIS)
            val baseline = quiesce(server, null, settings.graceSeconds)
            server.histogram()
            load.scenarios.forEach { it.stats.latency.cut() }
            println("[$tag] baseline: ${brief(baseline)}")

            println("[$tag] driving the load for ${fmt(settings.minutes)} min")
            val mainStart = System.nanoTime()
            load.start()
            drive(server, load, mainStart)
            val stragglers = load.stop(STOP_WAIT_MILLIS)
            val loadStopped = System.nanoTime()
            println("[$tag] load stopped; waiting up to ${settings.graceSeconds}s for the server to settle")
            val settled = quiesce(server, baseline, settings.graceSeconds)
            val settleSeconds = (System.nanoTime() - loadStopped) / NANOS
            println("[$tag] settled after ${fmt(settleSeconds)}s: ${brief(settled)}")
            server.histogram()
            val warnings = server.warnings()
            val stopped = server.stop()
            val checks = checks(load, baseline, settled, stopped, warnings, stragglers + warmStragglers, started)
            val verdict = Verdict(engine, checks, settings.minutes)
            report(verdict, load, baseline, settled, stopped, warnings, settleSeconds)
            return verdict
        }
    }

    private fun serverCommand(): List<String> {
        val java = ProcessHandle.current().info().command().orElse("java")
        val pin = settings.serverCpus?.let { listOf("taskset", "-c", it) }.orEmpty()
        return pin + listOf(
            java,
            "--enable-native-access=ALL-UNNAMED",
            "--add-exports", "java.base/jdk.internal.vm=ALL-UNNAMED",
            "-Xms256m", "-Xmx256m",
            "-XX:NativeMemoryTracking=summary",
            "-cp", System.getProperty("java.class.path"),
            "io.github.matthewjones372.gannet.soak.SoakServerKt",
            "engine=$engine", "carriers=${settings.carriers}", "keystore=$keyStore",
        )
    }

    /** Samples every interval until the run's minutes are up, printing a line and writing a CSV row for each. */
    private fun drive(server: ServerProcess, load: Load, start: Long) {
        val end = start + (settings.minutes * SECONDS_PER_MINUTE * NANOS).toLong()
        val step = TimeUnit.SECONDS.toNanos(settings.intervalSeconds)
        var next = start + step
        var cpus = Proc.cpuTimes()
        var lastErrors = load.scenarios.associate { it.name to 0L }
        var lastCpu = server.sample()["cpuNanos"]
        var lastAt = System.nanoTime()
        while (next <= end + step / 2) {
            val sleep = next - System.nanoTime()
            if (sleep > 0) Thread.sleep(TimeUnit.NANOSECONDS.toMillis(sleep))
            val latency = load.scenarios.associate { it.name to it.stats.latency.cut() }
            val errors = load.scenarios.associate { it.name to it.stats.unexpected.get() + it.stats.serverErrors.get() }
            val nowCpus = Proc.cpuTimes()
            val sample = server.sample()
            val now = System.nanoTime()
            val interval = Interval(
                seconds = (now - start) / NANOS,
                latency = latency,
                errors = errors.mapValues { (k, v) -> v - lastErrors.getValue(k) },
                sample = sample,
                load = Proc.loadAverage(),
                busy = Proc.busy(cpus, nowCpus),
                serverCpu = (sample["cpuNanos"] - lastCpu) * PERCENT / (now - lastAt).toDouble(),
            )
            intervals += interval
            write(interval)
            println(line(interval))
            cpus = nowCpus
            lastErrors = errors
            lastCpu = sample["cpuNanos"]
            lastAt = now
            next += step
        }
    }

    /**
     * Samples every couple of seconds until no connection is open on any server, every buffer is free, and (given a
     * [baseline]) descriptors and threads are back to it; or until [seconds] pass. The last sample.
     */
    private fun quiesce(server: ServerProcess, baseline: Sample?, seconds: Long): Sample {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds)
        while (true) {
            val sample = server.sample()
            val quiet = SERVERS.all { s ->
                sample["$s.open"] == 0L && sample["$s.sessions"] == 0L && sample["$s.zombies"] == 0L &&
                    sample["$s.handshakes"] == 0L && sample["$s.freeBuffers"] == sample["$s.buffers"]
            }
            val back = baseline == null ||
                (sample["fds"] <= baseline["fds"] && sample["threads"] <= baseline["threads"])
            if ((quiet && back) || System.nanoTime() > deadline) return sample
            Thread.sleep(QUIESCE_POLL_MILLIS)
        }
    }

    // ------------------------------------------------------------------ the checks

    private fun checks(
        load: Load,
        baseline: Sample,
        settled: Sample,
        stopped: Sample,
        warnings: List<String>,
        stragglers: Int,
        started: Sample,
    ): List<Check> {
        val checks = mutableListOf<Check>()
        checks += Check(
            "descriptors back to baseline",
            settled["fds"] <= baseline["fds"],
            "baseline ${baseline["fds"]}, after ${settled["fds"]}",
        )
        checks += Check(
            "threads back to baseline",
            settled["threads"] <= baseline["threads"],
            "baseline ${baseline["threads"]}, after ${settled["threads"]}",
        )
        SERVERS.forEach { s ->
            val held = listOf("open", "sessions", "zombies", "handshakes").associateWith { settled["$s.$it"] }
            checks += Check("$s: every connection closed", held.values.all { it == 0L }, held.toString())
            checks += Check(
                "$s: every receive buffer returned",
                settled["$s.freeBuffers"] == settled["$s.buffers"],
                "${settled["$s.freeBuffers"]} free of ${settled["$s.buffers"]}",
            )
            checks += Check(
                "$s: one fiber (the acceptor) per carrier",
                settled["$s.fibers"] == started["$s.fibers"],
                "at start ${started["$s.fibers"]}, after ${settled["$s.fibers"]}",
            )
            if (baseline["$s.engineWaiting"] >= 0) {
                checks += Check(
                    "$s: no epoll operation left waiting",
                    settled["$s.engineWaiting"] <= baseline["$s.engineWaiting"],
                    "baseline ${baseline["$s.engineWaiting"]}, after ${settled["$s.engineWaiting"]}",
                )
            }
        }
        checks += slope("heap after GC", "heap", settings.heapSlopeLimit)
        // Direct memory under load is mostly the send buffers of connections open at that moment, too noisy to fit a
        // line to; once they have all closed, it must be back where it was.
        checks += Check(
            "direct memory back to baseline",
            settled["direct"] <= baseline["direct"] + DIRECT_SLACK,
            "baseline ${fmt(baseline.mb("direct"))} MB, after ${fmt(settled.mb("direct"))} MB " +
                "(${baseline["directCount"]} -> ${settled["directCount"]} buffers)",
        )
        checks += latency()
        val unexpected = load.scenarios.sumOf { it.stats.unexpected.get() }
        val serverErrors = load.scenarios.sumOf { it.stats.serverErrors.get() }
        checks += Check(
            "no unexpected failures",
            unexpected == 0L,
            load.scenarios.filter { it.stats.unexpected.get() > 0 }
                .joinToString("; ") { "${it.name}: ${it.stats.unexpected.get()} (${it.stats.errors.firstOrNull()})" }
                .ifEmpty { "none" },
        )
        checks += Check("no 5xx replies", serverErrors == 0L, "$serverErrors")
        checks += Check(
            "no server warnings",
            warnings.isEmpty() && settled["warnings"] == 0L,
            "${settled["warnings"]}${warnings.firstOrNull()?.let { ": $it" }.orEmpty()}",
        )
        checks += Check("every load worker stopped", stragglers == 0, "$stragglers left")
        checks += Check(
            "descriptors released on stop",
            stopped["fds"] <= stopped["fdsBeforeStart"],
            "before start ${stopped["fdsBeforeStart"]}, after stop ${stopped["fds"]}",
        )
        checks += Check(
            "carrier threads ended on stop",
            stopped["gannetThreads"] == 0L,
            "${stopped["gannetThreads"]} left; threads before start ${stopped["threadsBeforeStart"]}, " +
                "after stop ${stopped["threads"]}",
        )
        return checks
    }

    /** The steady-state window: the main phase's samples after its first fifth. */
    private fun steady(): List<Interval> = intervals.drop(maxOf(1, intervals.size / STEADY_SKIP))

    private fun slope(what: String, key: String, limit: Double): Check {
        val window = steady()
        if (window.size < MIN_POINTS) return Check("$what not trending up", false, "only ${window.size} samples")
        val fit = Fit.of(window.map { it.seconds / SECONDS_PER_MINUTE }, window.map { it.sample.mb(key) })
        return Check(
            "$what not trending up",
            fit.slope < limit,
            "slope ${fmt(fit.slope, SLOPE_DIGITS)} MB/min (limit $limit) over ${window.size} samples, " +
                "${fmt(window.first().sample.mb(key))} -> ${fmt(window.last().sample.mb(key))} MB",
        )
    }

    private fun latency(): Check {
        val get = intervals.map { it.latency.getValue("get") }.filter { it.count > 0 }
        if (get.isEmpty() && settings.scenarios != null) return Check("latency not degraded", true, "no GETs run")
        if (get.size < 2) return Check("latency not degraded", false, "only ${get.size} intervals with GETs")
        val first = get.first()
        val last = get.last()
        val p50 = last.percentile(P50) <= first.percentile(P50) * P50_RATIO + P50_SLACK_MICROS
        val p99 = last.percentile(P99) <= first.percentile(P99) * P99_RATIO + P99_SLACK_MICROS
        return Check(
            "latency not degraded",
            p50 && p99,
            "GET p50 ${first.percentile(P50)} -> ${last.percentile(P50)} us, " +
                "p99 ${first.percentile(P99)} -> ${last.percentile(P99)} us " +
                "(allowed ${P50_RATIO}x+${P50_SLACK_MICROS}us, ${P99_RATIO}x+${P99_SLACK_MICROS}us)",
        )
    }

    // ------------------------------------------------------------------ reporting

    private fun brief(s: Sample) = "heap ${fmt(s.mb("heap"))} MB, direct ${fmt(s.mb("direct"))} MB, " +
        "rss ${fmt(s.mb("rss"))} MB, fds ${s["fds"]}, threads ${s["threads"]}, maps ${s["maps"]}, " +
        SERVERS.joinToString(", ") {
            "$it ${s["$it.open"]} open/${s["$it.fibers"]} fibers/" +
                "${s["$it.freeBuffers"]}/${s["$it.buffers"]} buffers free"
        }

    private fun line(i: Interval): String {
        val get = i.latency.getValue("get")
        val ops = i.latency.values.sumOf { it.count }
        val errors = i.errors.values.sum()
        val s = i.sample
        return String.format(
            Locale.ROOT,
            "[%s %6.0fs] ops %6.0f/s get %6.0f/s p50 %5dus p99 %6dus | heap %6.1fMB direct %5.1fMB rss %6.1fMB " +
                "fds %4d thr %3d | main %4d open %4d/%4d buf | tls %3d open | load %.2f busy %s srv %3.0f%% | err %d",
            tag, i.seconds, ops / settings.intervalSeconds.toDouble(), get.count / settings.intervalSeconds.toDouble(),
            get.percentile(P50), get.percentile(P99), s.mb("heap"), s.mb("direct"), s.mb("rss"), s["fds"],
            s["threads"], s["main.open"], s["main.freeBuffers"], s["main.buffers"], s["tls.open"], i.load[0],
            i.busy.toSortedMap().values.joinToString("/"), i.serverCpu, errors,
        )
    }

    private var header: List<String>? = null

    private fun write(i: Interval) {
        val sampleKeys = header ?: i.sample.values.keys.sorted().also { keys ->
            header = keys
            val scenarioColumns = i.latency.keys.flatMap { n ->
                listOf("${n}_ops", "${n}_p50us", "${n}_p99us", "${n}_err")
            }
            val cpuColumns = i.busy.keys.sorted().map { "cpu${it}_busy" }
            csv.writeText(
                (listOf("seconds") + scenarioColumns + keys + listOf("load1", "server_cpu_pct") + cpuColumns)
                    .joinToString(",") + "\n",
            )
        }
        val row = listOf(fmt(i.seconds)) +
            i.latency.flatMap { (n, h) ->
                listOf(h.count, h.percentile(P50), h.percentile(P99), i.errors.getValue(n)).map { it.toString() }
            } +
            sampleKeys.map { i.sample[it].toString() } +
            listOf(fmt(i.load[0]), fmt(i.serverCpu)) +
            i.busy.toSortedMap().values.map { it.toString() }
        csv.appendText(row.joinToString(",") + "\n")
    }

    private fun report(
        verdict: Verdict,
        load: Load,
        baseline: Sample,
        settled: Sample,
        stopped: Sample,
        warnings: List<String>,
        settleSeconds: Double,
    ) {
        val text = StringBuilder()
        text.appendLine("Gannet soak: $engine, ${fmt(settings.minutes)} min, finished ${Instant.now()}")
        text.appendLine(settings.describe())
        text.appendLine()
        text.appendLine("Time series over the main phase (start -> end, least-squares slope over the steady window):")
        series("heap after GC (MB)") { it.sample.mb("heap") }.also(text::appendLine)
        series("direct (MB)") { it.sample.mb("direct") }.also(text::appendLine)
        series("NMT committed (MB)") { it.sample.mb("nmtCommitted") }.also(text::appendLine)
        series("RSS (MB)") { it.sample.mb("rss") }.also(text::appendLine)
        series("mappings") { it.sample["maps"].toDouble() }.also(text::appendLine)
        series("fds") { it.sample["fds"].toDouble() }.also(text::appendLine)
        series("threads") { it.sample["threads"].toDouble() }.also(text::appendLine)
        series("main open connections") { it.sample["main.open"].toDouble() }.also(text::appendLine)
        series("main free buffers") { it.sample["main.freeBuffers"].toDouble() }.also(text::appendLine)
        series("GET p50 (us)") { it.latency.getValue("get").percentile(P50).toDouble() }.also(text::appendLine)
        series("GET p99 (us)") { it.latency.getValue("get").percentile(P99).toDouble() }.also(text::appendLine)
        series("GET per second") { it.latency.getValue("get").count / settings.intervalSeconds.toDouble() }
            .also(text::appendLine)
        series("all ops per second") { i -> i.latency.values.sumOf { it.count } / settings.intervalSeconds.toDouble() }
            .also(text::appendLine)
        series("1-min load average") { it.load[0] }.also(text::appendLine)
        series("server cpu (%)") { it.serverCpu }.also(text::appendLine)
        text.appendLine()
        text.appendLine("Scenarios (whole main phase + warm-up):")
        load.scenarios.forEach { sc ->
            val all = intervals.fold(Histogram.EMPTY) { acc, i -> acc.merge(i.latency.getValue(sc.name)) }
            text.appendLine(
                String.format(
                    Locale.ROOT,
                    "  %-14s ok %9d  unexpected %4d  5xx %3d  p50 %7d us  p99 %8d us  p99.9 %8d us",
                    sc.name, sc.stats.ok.get(), sc.stats.unexpected.get(), sc.stats.serverErrors.get(),
                    all.percentile(P50), all.percentile(P99), all.percentile(P999),
                ),
            )
            sc.stats.errors.take(ERRORS_SHOWN).forEach { text.appendLine("      $it") }
        }
        text.appendLine()
        text.appendLine("Baseline after warm-up: ${brief(baseline)}")
        text.appendLine("Settled ${fmt(settleSeconds)}s after the load: ${brief(settled)}")
        text.appendLine(
            "After stop: fds ${stopped["fds"]} (before start ${stopped["fdsBeforeStart"]}), threads " +
                "${stopped["threads"]} (before start ${stopped["threadsBeforeStart"]}), " +
                "heap ${fmt(stopped.mb("heap"))} MB",
        )
        val counted = settled.values.filterKeys { it.startsWith("count") }
        text.appendLine("Server requests by path: $counted")
        if (warnings.isNotEmpty()) {
            text.appendLine("Server warnings:")
            warnings.forEach { text.appendLine("  $it") }
        }
        text.appendLine()
        text.appendLine("Checks:")
        verdict.checks.forEach { text.appendLine("  ${if (it.passed) "PASS" else "FAIL"}  ${it.name}: ${it.detail}") }
        text.appendLine()
        text.appendLine(verdict.headline)
        dir.resolve("summary.txt").toFile().writeText(text.toString())
        println()
        print(text)
    }

    private fun series(name: String, value: (Interval) -> Double): String {
        if (intervals.isEmpty()) return "  $name: no samples"
        val window = steady()
        val slope = if (window.size >= MIN_POINTS) {
            Fit.of(window.map { it.seconds / SECONDS_PER_MINUTE }, window.map(value)).slope
        } else {
            Double.NaN
        }
        val values = intervals.map(value)
        return String.format(
            Locale.ROOT,
            "  %-22s start %10.2f  end %10.2f  min %10.2f  max %10.2f  slope %+10.4f /min",
            name, values.first(), values.last(), values.min(), values.max(), slope,
        )
    }

    private companion object {
        val SERVERS = listOf("main", "limits", "tls", "tls-limits", "cap")
        const val READY_SECONDS = 120L
        const val STOP_WAIT_MILLIS = 30_000L
        const val QUIESCE_POLL_MILLIS = 2_000L
        const val NANOS = 1e9
        const val SECONDS_PER_MINUTE = 60.0
        const val PERCENT = 100.0
        const val STEADY_SKIP = 5
        const val MIN_POINTS = 3
        const val P50 = 50.0
        const val P99 = 99.0
        const val P999 = 99.9
        const val P50_RATIO = 1.5
        const val P50_SLACK_MICROS = 500
        const val P99_RATIO = 2.0
        const val P99_SLACK_MICROS = 5_000
        const val ERRORS_SHOWN = 5
        const val SLOPE_DIGITS = 4
        const val DIRECT_SLACK = 1L shl 20
    }
}

/** A least-squares line through points. */
internal class Fit(val slope: Double, val intercept: Double) {
    companion object {
        fun of(x: List<Double>, y: List<Double>): Fit {
            val n = x.size
            val mx = x.average()
            val my = y.average()
            var sxy = 0.0
            var sxx = 0.0
            for (i in 0 until n) {
                sxy += (x[i] - mx) * (y[i] - my)
                sxx += (x[i] - mx) * (x[i] - mx)
            }
            val slope = if (sxx == 0.0) 0.0 else sxy / sxx
            return Fit(slope, my - slope * mx)
        }
    }
}

internal fun fmt(value: Double, digits: Int = 2): String = String.format(Locale.ROOT, "%.${digits}f", value)
