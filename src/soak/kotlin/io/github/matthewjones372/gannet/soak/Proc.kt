package io.github.matthewjones372.gannet.soak

import java.io.File

/** What Linux says about this process and the machine, read from `/proc`. Zero or empty where it cannot be read. */
internal object Proc {
    /** Descriptors this process has open, the one listing them among them. */
    fun fds(): Int = File("/proc/self/fd").list()?.size ?: 0

    /** What each open descriptor is, by number: a file's path, `socket:[inode]`, `anon_inode:[io_uring]` and so on. */
    fun fdTargets(): Map<String, String> = File("/proc/self/fd").listFiles().orEmpty().associate { fd ->
        fd.name to try {
            java.nio.file.Files.readSymbolicLink(fd.toPath()).toString()
        } catch (_: java.io.IOException) {
            "?"
        }
    }

    /** Resident set size, in KiB. */
    fun rssKib(): Long = status("VmRSS")

    /** Memory mappings: one more that is never unmapped shows here even when nothing touches its pages. */
    fun mappings(): Int = File("/proc/self/maps").useLines { it.count() }

    /** The 1, 5 and 15 minute load averages. */
    fun loadAverage(): List<Double> =
        File("/proc/loadavg").readText().trim().split(' ').take(LOAD_FIELDS).map { it.toDouble() }

    /** Each core's busy and total jiffies since boot, by core number. */
    fun cpuTimes(): Map<Int, Pair<Long, Long>> = File("/proc/stat").readLines()
        .filter { it.startsWith("cpu") && it.length > CPU.length && it[CPU.length].isDigit() }
        .associate { line ->
            val parts = line.split(Regex("\\s+"))
            val values = parts.drop(1).map { it.toLong() }
            // user nice system idle iowait irq softirq steal: idle and iowait are not busy.
            val idle = values[IDLE] + values[IOWAIT]
            parts[0].removePrefix(CPU).toInt() to ((values.sum() - idle) to values.sum())
        }

    /** Each core's busy share between two [cpuTimes] readings, in percent. */
    fun busy(before: Map<Int, Pair<Long, Long>>, after: Map<Int, Pair<Long, Long>>): Map<Int, Int> =
        after.mapValues { (core, now) ->
            val then = before[core] ?: (0L to 0L)
            val total = now.second - then.second
            if (total <= 0) 0 else ((now.first - then.first) * PERCENT / total).toInt()
        }

    private fun status(field: String): Long = File("/proc/self/status").useLines { lines ->
        lines.firstOrNull { it.startsWith("$field:") }
            ?.substringAfter(':')?.trim()?.substringBefore(' ')?.toLongOrNull() ?: 0L
    }

    private const val CPU = "cpu"
    private const val IDLE = 3
    private const val IOWAIT = 4
    private const val LOAD_FIELDS = 3
    private const val PERCENT = 100
}
