package io.github.matthewjones372.gannet

import io.github.matthewjones372.gannet.runtime.Carriers
import java.time.Duration

/**
 * An HTTP/1.1 server on io_uring: [GannetConfig.carriers] platform threads, each with its own ring and a listening
 * socket sharing one port, each connection a fiber on one of them. Needs Linux 6.1+, JDK 25 and [GannetSupport.FLAGS].
 */
class GannetServer private constructor(private val carriers: Carriers, val host: String) : AutoCloseable {
    /** The bound port, the kernel's choice when the configuration asked for zero. */
    val port: Int get() = carriers.port

    /** Stops accepting, closes idle connections, lets the rest finish within [GannetConfig.stopGrace], and joins. */
    fun stop() = carriers.stop(null)

    /** [stop] with a grace period of its own. */
    fun stop(grace: Duration) = carriers.stop(grace)

    /** Blocks until the server has stopped, whoever stopped it. */
    fun awaitStopped() = carriers.join()

    override fun close() = stop()

    companion object {
        /**
         * Binds and starts serving [handler]; returns once every carrier is accepting.
         *
         * @throws GannetUnavailableException when this machine or JVM cannot run Gannet.
         * @throws java.net.BindException when the port is taken.
         */
        fun start(config: GannetConfig = GannetConfig(), handler: HttpHandler): GannetServer {
            GannetSupport.require()
            return GannetServer(Carriers.start(config, handler), config.host)
        }
    }
}
