package io.github.matthewjones372.gannet

import io.github.matthewjones372.gannet.runtime.Carriers
import io.github.matthewjones372.gannet.runtime.ServerStats
import java.time.Duration

/**
 * An HTTP/1.1 server on io_uring, or on epoll where io_uring is refused: [GannetConfig.carriers] platform threads,
 * each with its own ring (or epoll instance) and a listening socket sharing one port, each connection a fiber on one
 * of them. Needs Linux, JDK 25 and [GannetSupport.FLAGS]; io_uring needs Linux 6.1 or later.
 *
 * @property engine what this server's carriers run on: never [GannetEngine.AUTO], which is resolved at start.
 */
class GannetServer private constructor(private val carriers: Carriers, val host: String, val engine: GannetEngine) :
    AutoCloseable {
    /** The bound port, the kernel's choice when the configuration asked for zero. */
    val port: Int get() = carriers.port

    /** Stops accepting, closes idle connections, lets the rest finish within [GannetConfig.stopGrace], and joins. */
    fun stop() = carriers.stop(null)

    /** [stop] with a grace period of its own. */
    fun stop(grace: Duration) = carriers.stop(grace)

    /** Blocks until the server has stopped, whoever stopped it. */
    fun awaitStopped() = carriers.join()

    override fun close() = stop()

    /** What the server holds right now: open connections, and each carrier's fibers and buffers. Not API. */
    internal fun stats(): ServerStats = carriers.stats()

    companion object {
        /**
         * Binds and starts serving [handler]; returns once every carrier is accepting.
         *
         * @throws GannetUnavailableException when this machine or JVM cannot run Gannet on [GannetConfig.engine].
         * @throws java.net.BindException when the port is taken.
         */
        fun start(config: GannetConfig = GannetConfig(), handler: HttpHandler): GannetServer =
            start(config, handler, GannetSupport.selection(config.engine))

        /** [start] on what [selection] chose, which a test can make up to have io_uring refused. */
        internal fun start(config: GannetConfig, handler: HttpHandler, selection: Selection): GannetServer {
            val engine = selection.engine ?: throw GannetUnavailableException(selection.problem.orEmpty())
            return GannetServer(Carriers.start(config, handler, engine == GannetEngine.EPOLL), config.host, engine)
        }
    }
}
