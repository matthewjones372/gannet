package io.github.matthewjones372.gannet

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.net.BindException
import java.net.ConnectException
import java.net.ServerSocket
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class LifecycleTest {
    private val threads = Executors.newVirtualThreadPerTaskExecutor()

    @AfterEach
    fun close() = threads.close()

    private fun carrierThreads(port: Int) =
        Thread.getAllStackTraces().keys.filter { it.name.startsWith("gannet-$port-") }

    @Test
    fun `port zero is one port that every carrier accepts on`() {
        val seen = ConcurrentHashMap.newKeySet<String>()
        val recording = HttpHandler { ex -> seen.add(Thread.currentThread().name).also { ex.respond(200) } }
        serving(testConfig.copy(carriers = 4), recording) { server ->
            (server.port > 0) shouldBe true
            repeat(200) { Raw(server.port).use { raw -> raw.send(get("/", "Connection: close")).read() } }
            seen shouldBe (0 until 4).map { "gannet-${server.port}-$it" }.toSet()
        }
    }

    @Test
    fun `a chosen port is bound, and a port another socket holds is refused`() {
        assumeUring()
        val free = ServerSocket(0).use { it.localPort }
        val server = GannetServer.start(testConfig.copy(port = free)) { it.respond(200) }
        server.port shouldBe free
        server.host shouldBe "127.0.0.1"
        server.stop()
        ServerSocket(0).use { taken ->
            val config = testConfig.copy(port = taken.localPort)
            shouldThrow<BindException> { GannetServer.start(config) { it.respond(200) } }
        }
    }

    @Test
    fun `stop closes idle connections, joins every carrier and refuses new ones`() {
        assumeUring()
        val server = GannetServer.start(testConfig) { it.respond(200) }
        val port = server.port
        carrierThreads(port) shouldHaveSize 2
        val idle = Raw(port)
        idle.send(get("/")).read().status shouldBe 200
        server.stop()
        idle.closedByServer() shouldBe true
        carrierThreads(port).shouldBeEmpty()
        shouldThrow<ConnectException> { Raw(port) }
        server.stop()
        server.awaitStopped()
    }

    @Test
    fun `stop lets an exchange in progress finish, and closes its connection after`() {
        assumeUring()
        val gate = CountDownLatch(1)
        val started = CountDownLatch(1)
        val server = GannetServer.start(testConfig) { ex ->
            ex.offload(threads) {
                started.countDown()
                gate.await()
                it.respond(200, body = "finished".toByteArray())
            }
        }
        Raw(server.port).use { raw ->
            raw.send(get("/"))
            started.await()
            val stopping = Thread.ofPlatform().start { server.stop() }
            stopping.join(200)
            stopping.isAlive shouldBe true
            gate.countDown()
            val reply = raw.read()
            reply.text shouldBe "finished"
            reply["Connection"] shouldBe "close"
            stopping.join()
        }
    }

    @Test
    fun `stop closes what is still running once its grace runs out`() {
        assumeUring()
        val never = CountDownLatch(1)
        val started = CountDownLatch(1)
        val server = GannetServer.start(testConfig) { ex ->
            ex.offload(threads) {
                started.countDown()
                never.await()
                it.respond(200)
            }
        }
        Raw(server.port).use { raw ->
            raw.send(get("/"))
            started.await()
            server.stop(Duration.ofMillis(100))
            raw.closedByServer() shouldBe true
            carrierThreads(server.port).shouldBeEmpty()
        }
        never.countDown()
    }

    @Test
    fun `stop ends a stream held by a reader that never reads`() {
        assumeUring()
        val server = GannetServer.start(testConfig.copy(stopGrace = Duration.ofMillis(100))) { ex ->
            ex.stream(200).use { out -> repeat(10_000) { out.write(ByteArray(64 * 1024)) } }
        }
        Raw(server.port).use { raw ->
            raw.send(get("/"))
            line(raw.socket.getInputStream())
            server.stop()
            carrierThreads(server.port).shouldBeEmpty()
        }
    }

    @Test
    fun `a handler can stop its own server`() {
        assumeUring()
        lateinit var server: GannetServer
        server = GannetServer.start(testConfig) { ex ->
            ex.respond(200, body = "bye".toByteArray())
            server.stop()
        }
        Raw(server.port).use { raw ->
            raw.send(get("/")).read().text shouldBe "bye"
            raw.closedByServer() shouldBe true
        }
        server.awaitStopped()
        carrierThreads(server.port).shouldBeEmpty()
    }

    @Test
    fun `a connection that sends nothing is closed after the idle timeout`() =
        serving(testConfig.copy(idleTimeout = Duration.ofMillis(200)), { it.respond(200) }) { server ->
            Raw(server.port).use { raw -> raw.closedByServer() shouldBe true }
            Raw(server.port).use { raw ->
                raw.send("GET / HTTP/1.1\r\nHo")
                raw.closedByServer() shouldBe true
            }
        }

    @Test
    fun `stop waits for a thread blocked in awaitStopped`() {
        assumeUring()
        val server = GannetServer.start(testConfig) { it.respond(200) }
        val waiter = Thread.ofPlatform().start { server.awaitStopped() }
        waiter.join(100)
        waiter.isAlive shouldBe true
        server.close()
        waiter.join(TimeUnit.SECONDS.toMillis(30))
        waiter.isAlive shouldBe false
    }

    @Test
    fun `a carrier that cannot set up its ring fails start, naming why`() {
        assumeUring()
        val e = shouldThrow<GannetUnavailableException> {
            GannetServer.start(testConfig.copy(ringEntries = 64, completionEntries = 1)) { it.respond(200) }
        }
        e.message!! shouldContain "EINVAL"
    }

    @Test
    fun `an IPv6 host is refused`() {
        assumeUring()
        shouldThrow<IllegalArgumentException> { GannetServer.start(testConfig.copy(host = "::1")) { it.respond(200) } }
            .message!! shouldContain "IPv4"
    }

    @Test
    fun `direct descriptors can be turned off`() =
        serving(testConfig.copy(fixedFiles = 0), { it.respond(200, body = "plain".toByteArray()) }) { server ->
            repeat(3) { Raw(server.port).use { raw -> raw.send(get("/")).read().text shouldBe "plain" } }
        }
}
