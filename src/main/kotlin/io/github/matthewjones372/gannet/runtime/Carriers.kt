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
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** A server's carriers: started together on one port, stopped and joined together. */
internal class Carriers private constructor(
    private val threads: List<Carrier>,
    val port: Int,
    private val grace: Duration,
    private val open: AtomicInteger,
) {
    private val stopping = AtomicBoolean()

    fun stop(grace: Duration?) {
        if (stopping.compareAndSet(false, true)) threads.forEach { it.requestStop((grace ?: this.grace).toNanos()) }
        join()
    }

    /**
     * What the server holds right now, each carrier counted on itself: connections open, and per carrier its
     * fibers, buffers and waiting operations. For the soak and the tests, not API. Throws once a carrier has stopped.
     */
    fun stats(timeout: Duration = Duration.ofSeconds(STATS_SECONDS)): ServerStats {
        val pending = threads.map { it.stats() }
        val carriers = pending.map { future ->
            val s = future.get(timeout.toMillis(), TimeUnit.MILLISECONDS)
            CarrierStats(
                s.fibers, s.sessions, s.zombies, s.handshakes, s.freeBuffers, s.buffers, s.slotSlabs, s.freeSlots,
                s.engineWaiting,
            )
        }
        return ServerStats(open.get(), carriers)
    }

    /** Joins every carrier, unless called from one: a handler that stops its own server cannot wait for itself. */
    fun join() {
        if (threads.none { it === Thread.currentThread() }) threads.forEach { it.join() }
    }

    companion object {
        fun start(config: GannetConfig, handler: HttpHandler, epoll: Boolean): Carriers {
            val listeners = listen(ipv4(config.host), config.port, config.carriers, config.backlog)
            val port = Sys.localPort(listeners.first())
            val settings = Carrier.Settings(
                config.ringEntries, config.completionEntries, config.bufferCount, config.bufferSize, config.fixedFiles,
                config.tick.toNanos(), config.heldBuffers, config.maxConnections,
                // Only TLS connections have a handshake worth capping.
                if (config.tls != null) config.maxHandshakes else Int.MAX_VALUE,
                epoll,
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
            return Carriers(threads, port, config.stopGrace, open)
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

/** A server's counts at one moment; see [Carriers.stats]. [open] is connections accepted and not yet closed. */
internal data class ServerStats(val open: Int, val carriers: List<CarrierStats>) {
    val fibers get() = carriers.sumOf { it.fibers }
    val sessions get() = carriers.sumOf { it.sessions }
    val zombies get() = carriers.sumOf { it.zombies }
    val handshakes get() = carriers.sumOf { it.handshakes }
    val freeBuffers get() = carriers.sumOf { it.freeBuffers }
    val buffers get() = carriers.sumOf { it.buffers }
    val slotSlabs get() = carriers.sumOf { it.slotSlabs }
    val engineWaiting get() = carriers.sumOf { it.engineWaiting }
}

/**
 * One carrier's counts: fibers alive (its acceptor among them), fibers serving a connection, fibers ended with I/O
 * still in flight, TLS handshakes in progress, receive buffers free out of [buffers], send-slot slabs mapped and slots
 * free in them, and operations the engine holds waiting (epoll only; -1 on io_uring).
 */
internal data class CarrierStats(
    val fibers: Int,
    val sessions: Int,
    val zombies: Int,
    val handshakes: Int,
    val freeBuffers: Int,
    val buffers: Int,
    val slotSlabs: Int,
    val freeSlots: Int,
    val engineWaiting: Int,
)

/** Null if a ring can be set up on this machine, else why not: seccomp and `io_uring_disabled` both refuse it. */
internal fun probeRing(): String? = try {
    Ring(PROBE_ENTRIES, PROBE_ENTRIES).close()
    null
} catch (e: UnsupportedOperationException) {
    e.message
}

/** Null if an epoll instance can be made on this machine, else why not. */
internal fun probeEpoll(): String? = Epoll.probe()

private const val PROBE_ENTRIES = 4
private const val STATS_SECONDS = 10L
