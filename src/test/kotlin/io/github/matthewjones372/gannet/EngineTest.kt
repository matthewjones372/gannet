package io.github.matthewjones372.gannet

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.Test

class EngineTest {
    private val refused = "io_uring_setup was refused (EPERM)"

    @Test
    fun `AUTO takes io_uring where it works, and never tries epoll then`() {
        val chosen = select(GannetEngine.AUTO, null, { null }, { error("epoll was probed") })
        chosen.engine shouldBe GannetEngine.IO_URING
        chosen.problem.shouldBeNull()
    }

    @Test
    fun `AUTO falls back to epoll where io_uring is refused, and says why`() {
        val chosen = select(GannetEngine.AUTO, null, { refused }, { null })
        chosen.engine shouldBe GannetEngine.EPOLL
        chosen.problem.shouldBeNull()
        chosen.reason shouldContain refused
    }

    @Test
    fun `no engine is available only when neither works`() {
        val chosen = select(GannetEngine.AUTO, null, { refused }, { "epoll_create1 failed: errno 1" })
        chosen.engine.shouldBeNull()
        chosen.problem!! shouldContain "no engine available"
        chosen.problem shouldContain refused
        chosen.problem shouldContain "epoll_create1"
    }

    @Test
    fun `an engine asked for by name is that engine or its problem, with no fallback`() {
        select(GannetEngine.IO_URING, null, { refused }, { null }).problem shouldBe refused
        select(GannetEngine.IO_URING, null, { null }, { "no" }).engine shouldBe GannetEngine.IO_URING
        select(GannetEngine.EPOLL, null, { null }, { "no" }).problem shouldBe "no"
        select(GannetEngine.EPOLL, null, { refused }, { null }).engine shouldBe GannetEngine.EPOLL
    }

    @Test
    fun `missing flags or the wrong platform stop every engine alike`() {
        val chosen = select(GannetEngine.AUTO, "Gannet needs Linux; this is Mac OS X.", { null }, { null })
        chosen.engine.shouldBeNull()
        chosen.problem shouldBe "Gannet needs Linux; this is Mac OS X."
    }

    @Test
    fun `a server whose io_uring is refused runs on epoll`() {
        assumeServer()
        val selection = select(GannetEngine.AUTO, null, { refused }, { GannetSupport.problem(GannetEngine.EPOLL) })
        val server = GannetServer.start(testConfig.copy(engine = GannetEngine.AUTO), { ex ->
            ex.respond(200, body = "on epoll".toByteArray())
        }, selection)
        try {
            server.engine shouldBe GannetEngine.EPOLL
            Raw(server.port).use { raw -> raw.send(get("/")).read().text shouldBe "on epoll" }
        } finally {
            server.stop()
        }
    }

    @Test
    fun `a server with no engine to run on is refused with the reason`() {
        val selection = select(GannetEngine.AUTO, null, { refused }, { "epoll refused too" })
        val e =
            shouldThrow<GannetUnavailableException> { GannetServer.start(testConfig, { it.respond(200) }, selection) }
        e.message!! shouldContain "no engine available"
    }

    @Test
    fun `a server says which engine it runs on, and AUTO is never one`() =
        serving(testConfig, { it.respond(200) }) { server ->
            server.engine shouldBe EngineTests.resolved
            (server.engine == GannetEngine.AUTO) shouldBe false
        }

    @Test
    fun `epoll is available wherever any engine is`() {
        assumeServer()
        GannetSupport.problem(GannetEngine.EPOLL).shouldBeNull()
        GannetSupport.engine() shouldBe
            if (GannetSupport.problem(GannetEngine.IO_URING) == null) GannetEngine.IO_URING else GannetEngine.EPOLL
    }

    /** Where `GANNET_EXPECT_ENGINE` names one, as the CI job inside Docker's default seccomp profile does. */
    @Test
    fun `AUTO picks the engine this host is expected to have`() {
        val expected = System.getenv("GANNET_EXPECT_ENGINE")
        Assumptions.assumeTrue(expected != null, "GANNET_EXPECT_ENGINE is not set")
        GannetSupport.engine() shouldBe GannetEngine.valueOf(expected!!)
    }
}
