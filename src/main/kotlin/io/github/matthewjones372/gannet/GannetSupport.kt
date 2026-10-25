package io.github.matthewjones372.gannet

import io.github.matthewjones372.gannet.runtime.probeEpoll
import io.github.matthewjones372.gannet.runtime.probeRing

/** Whether this JVM, on this machine, can run a [GannetServer], and on which [GannetEngine]. */
object GannetSupport {
    /** The JVM flags every process running the runtime needs, on either engine. */
    const val FLAGS: String = "--enable-native-access=ALL-UNNAMED --add-exports java.base/jdk.internal.vm=ALL-UNNAMED"

    private val base: String? by lazy {
        platformProblem(System.getProperty("os.name"), System.getProperty("os.arch")) ?: flagProblem()
    }
    private val uring: String? by lazy { kernelProblem(System.getProperty("os.version")) ?: probeRing() }
    private val epoll: String? by lazy { probeEpoll() }
    private val auto: Selection by lazy { select(GannetEngine.AUTO, base, { uring }, { epoll }).also(::announce) }

    /**
     * Null when a server can start here on some engine, else what stands in the way, worded for whoever has to fix
     * it. Where io_uring is refused but epoll works, that is null: a server falls back to epoll.
     */
    fun problem(): String? = problem(GannetEngine.AUTO)

    /** Null when a server can start here on [engine], else what stands in the way. */
    fun problem(engine: GannetEngine): String? = selection(engine).problem

    /** The engine a server configured with [GannetEngine.AUTO] runs on here, or null when it cannot run at all. */
    fun engine(): GannetEngine? = auto.engine

    /** Throws [GannetUnavailableException] with [problem]'s answer, if it has one. */
    fun require() {
        problem()?.let { throw GannetUnavailableException(it) }
    }

    internal fun selection(engine: GannetEngine): Selection =
        if (engine == GannetEngine.AUTO) auto else select(engine, base, { uring }, { epoll })

    private fun announce(selection: Selection) {
        val engine = selection.engine ?: return
        logger.log(System.Logger.Level.INFO) { "Gannet runs on ${engine.name.lowercase()}: ${selection.reason}" }
    }

    internal fun platformProblem(os: String, arch: String): String? = when {
        !os.startsWith("Linux") -> "Gannet needs Linux; this is $os."
        arch !in ARCHES -> "Gannet runs on x86-64 and arm64; this is $arch."
        Runtime.version().feature() < JDK -> "Gannet needs JDK $JDK or later."
        else -> null
    }

    /** What keeps io_uring out on [kernel], before a ring is even tried. */
    internal fun kernelProblem(kernel: String): String? = if (kernelAtLeast(kernel, KERNEL_MAJOR, KERNEL_MINOR)) null
    else "io_uring needs Linux 6.1 or later; this is $kernel."

    private fun flagProblem(): String? {
        val module = GannetSupport::class.java.module
        val exported = Any::class.java.module.isExported("jdk.internal.vm", module)
        return if (exported && module.isNativeAccessEnabled) null
        else "Gannet needs the JVM flags $FLAGS."
    }

    internal fun kernelAtLeast(release: String, major: Int, minor: Int): Boolean {
        val parts = release.takeWhile { it.isDigit() || it == '.' }.split('.').mapNotNull { it.toIntOrNull() }
        val found = parts.getOrElse(0) { 0 } to parts.getOrElse(1) { 0 }
        return found.first > major || (found.first == major && found.second >= minor)
    }

    private val logger = System.getLogger("io.github.matthewjones372.gannet")
    private val ARCHES = setOf("amd64", "x86_64", "aarch64")
    private const val JDK = 25
    private const val KERNEL_MAJOR = 6
    private const val KERNEL_MINOR = 1
}

/** Which engine a server runs on ([engine]), and why; or, with no engine, the [problem] that stops it. */
internal class Selection(val engine: GannetEngine?, val problem: String?, val reason: String)

/**
 * What a server asking for [requested] runs on, given what stops any engine ([base]) and what stops each one. Each
 * probe is called only when the answer depends on it, so AUTO on a machine with io_uring never tries epoll.
 */
internal fun select(requested: GannetEngine, base: String?, uring: () -> String?, epoll: () -> String?): Selection {
    if (base != null) return Selection(null, base, base)
    return when (requested) {
        GannetEngine.IO_URING -> only(GannetEngine.IO_URING, uring())

        GannetEngine.EPOLL -> only(GannetEngine.EPOLL, epoll())

        GannetEngine.AUTO -> {
            val refused = uring() ?: return Selection(GannetEngine.IO_URING, null, "io_uring is available")
            val alsoRefused = epoll()
                ?: return Selection(GannetEngine.EPOLL, null, "io_uring is unavailable, so epoll instead. $refused")
            val problem = "Gannet has no engine available here. io_uring: $refused epoll: $alsoRefused"
            Selection(null, problem, problem)
        }
    }
}

private fun only(engine: GannetEngine, problem: String?): Selection =
    if (problem == null) Selection(engine, null, "configured") else Selection(null, problem, problem)

/** The runtime cannot start here; the message says why and what would fix it. */
class GannetUnavailableException(message: String) : IllegalStateException(message)
