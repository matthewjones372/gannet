package io.github.matthewjones372.gannet

import io.github.matthewjones372.gannet.runtime.probeRing

/** Whether this JVM, on this machine, can run a [GannetServer]. */
object GannetSupport {
    /** The JVM flags every process running the runtime needs. */
    const val FLAGS: String = "--enable-native-access=ALL-UNNAMED --add-exports java.base/jdk.internal.vm=ALL-UNNAMED"

    private val found: String? by lazy { problemWith(System.getProperty("os.name"), System.getProperty("os.version")) }

    /** Null when the runtime can start here, else what stands in the way, worded for whoever has to fix it. */
    fun problem(): String? = found

    /** Throws [GannetUnavailableException] with [problem]'s answer, if it has one. */
    fun require() {
        problem()?.let { throw GannetUnavailableException(it) }
    }

    internal fun problemWith(os: String, kernel: String): String? =
        platformProblem(os, System.getProperty("os.arch"), kernel) ?: flagProblem() ?: probeRing()

    internal fun platformProblem(os: String, arch: String, kernel: String): String? = when {
        !os.startsWith("Linux") -> "Gannet needs Linux 6.1 or later; this is $os."

        arch !in ARCHES -> "Gannet runs on x86-64 and arm64; this is $arch."

        !kernelAtLeast(kernel, KERNEL_MAJOR, KERNEL_MINOR) ->
            "Gannet needs Linux 6.1 or later; this is $kernel."

        Runtime.version().feature() < JDK -> "Gannet needs JDK $JDK or later."

        else -> null
    }

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

    private val ARCHES = setOf("amd64", "x86_64", "aarch64")
    private const val JDK = 25
    private const val KERNEL_MAJOR = 6
    private const val KERNEL_MINOR = 1
}

/** The runtime cannot start here; the message says why and what would fix it. */
class GannetUnavailableException(message: String) : IllegalStateException(message)
