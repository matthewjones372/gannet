package io.github.matthewjones372.gannet

import org.junit.jupiter.api.Assumptions

/**
 * The engine this test run starts its servers on, from the `gannet.engine` system property the Gradle task sets:
 * `test` leaves it unset, for [GannetEngine.AUTO], and `testEpoll` sets `EPOLL`, so the whole suite runs on both.
 */
object EngineTests {
    val engine: GannetEngine =
        System.getProperty("gannet.engine")?.let { GannetEngine.valueOf(it) } ?: GannetEngine.AUTO

    /** `GANNET_REQUIRE_URING=1`: tests fail rather than skip, and AUTO must find io_uring rather than fall back. */
    val required: Boolean = System.getenv("GANNET_REQUIRE_URING") == "1"

    /** What stops this run's servers starting here, or null. */
    val problem: String? =
        GannetSupport.problem(if (required && engine == GannetEngine.AUTO) GannetEngine.IO_URING else engine)

    /** The engine this run's servers turn out to run on, or null where they cannot start. */
    val resolved: GannetEngine? = if (engine ==
        GannetEngine.AUTO
    ) GannetSupport.engine() else engine.takeIf { problem == null }
}

/**
 * Skips a test where this run's servers cannot start, unless `GANNET_REQUIRE_URING=1` says this host must run it, in
 * which case it fails.
 */
fun assumeServer() {
    val problem = EngineTests.problem ?: return
    Assumptions.assumeTrue(!EngineTests.required, problem)
    throw AssertionError("GANNET_REQUIRE_URING=1, but: $problem")
}

/** [assumeServer], and skips unless the servers run on io_uring: for what only io_uring has, such as its ring sizes. */
fun assumeUring() {
    assumeServer()
    Assumptions.assumeTrue(EngineTests.resolved == GannetEngine.IO_URING, "this run's servers are not on io_uring")
}
