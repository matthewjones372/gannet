package io.github.matthewjones372.gannet.runtime

import io.github.matthewjones372.gannet.GannetConfig
import io.github.matthewjones372.gannet.GannetUnavailableException
import io.github.matthewjones372.gannet.HttpHandler
import java.io.IOException
import java.net.Inet4Address
import java.net.InetAddress
import java.nio.ByteBuffer
import java.time.Duration
import java.util.concurrent.CompletionException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** A server's carriers: started together on one port, stopped and joined together. */
internal class Carriers private constructor(
    private val threads: List<Carrier>,
    val port: Int,
    private val grace: Duration,
) {
    private val stopping = AtomicBoolean()

    fun stop(grace: Duration?) {
        if (stopping.compareAndSet(false, true)) threads.forEach { it.requestStop((grace ?: this.grace).toNanos()) }
        join()
    }

    /** Joins every carrier, unless called from one: a handler that stops its own server cannot wait for itself. */
    fun join() {
        if (threads.none { it === Thread.currentThread() }) threads.forEach { it.join() }
    }

    companion object {
        fun start(config: GannetConfig, handler: HttpHandler): Carriers {
            val listeners = listen(ipv4(config.host), config.port, config.carriers, config.backlog)
            val port = Sys.localPort(listeners.first())
            val settings = Carrier.Settings(
                config.ringEntries, config.completionEntries, config.bufferCount, config.bufferSize, config.fixedFiles,
                config.tick.toNanos(), config.heldBuffers, config.maxConnections,
                // Only TLS connections have a handshake worth capping.
                if (config.tls != null) config.maxHandshakes else Int.MAX_VALUE,
            )
            val open = AtomicInteger()
            val threads = listeners.mapIndexed { i, listener ->
                val shared = Shared(config, handler)
                Carrier("gannet-$port-$i", listener, settings, open) { fd -> Connection(fd, shared).run() }
            }
            threads.forEach { it.start() }
            val failure = threads.firstNotNullOfOrNull { failureOf(it) }
            if (failure != null) {
                threads.forEach { it.requestStop(0) }
                threads.forEach { it.join() }
                throw if (failure is UnsupportedOperationException) {
                    GannetUnavailableException(failure.message.orEmpty())
                } else {
                    failure
                }
            }
            return Carriers(threads, port, config.stopGrace)
        }

        private fun failureOf(carrier: Carrier): Throwable? = try {
            carrier.started.join()
            null
        } catch (e: CompletionException) {
            e.cause
        }

        /** One listener per carrier. Port zero is chosen by the first bind; the rest share what it was given. */
        private fun listen(address: Int, port: Int, count: Int, backlog: Int): List<Int> {
            val first = Sys.listen(address, port, backlog)
            val bound = try {
                Sys.localPort(first)
            } catch (e: IOException) {
                Sys.close(first)
                throw e
            }
            return (1 until count).fold(listOf(first)) { opened, _ ->
                try {
                    opened + Sys.listen(address, bound, backlog)
                } catch (e: IOException) {
                    opened.forEach { Sys.close(it) }
                    throw e
                }
            }
        }

        private fun ipv4(host: String): Int {
            val address = InetAddress.getByName(host)
            require(address is Inet4Address) { "Gannet binds IPv4 only; $host is $address" }
            return ByteBuffer.wrap(address.address).int
        }
    }
}

/** Null if a ring can be set up on this machine, else why not: seccomp and `io_uring_disabled` both refuse it. */
internal fun probeRing(): String? = try {
    Ring(PROBE_ENTRIES, PROBE_ENTRIES).close()
    null
} catch (e: UnsupportedOperationException) {
    e.message
}

private const val PROBE_ENTRIES = 4
