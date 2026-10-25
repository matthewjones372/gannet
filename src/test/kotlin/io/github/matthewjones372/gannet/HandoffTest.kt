package io.github.matthewjones372.gannet

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class HandoffTest {
    private val threads = Executors.newVirtualThreadPerTaskExecutor()

    @AfterEach
    fun close() = threads.close()

    @Test
    fun `an offloaded handler answers from a virtual thread`() {
        val where = LinkedBlockingQueue<String>()
        serving(
            handler = { ex ->
                where.add("handle on carrier: ${ex.onCarrier}")
                ex.offload(threads) {
                    where.add("work on carrier: ${it.onCarrier}, virtual: ${Thread.currentThread().isVirtual}")
                    it.respond(200, body = "from ${it.request.path}".toByteArray())
                }
            },
        ) { server ->
            Raw(server.port).use { raw -> raw.send(get("/vt")).read().text shouldBe "from /vt" }
            where.take() shouldBe "handle on carrier: true"
            where.take() shouldBe "work on carrier: false, virtual: true"
        }
    }

    @Test
    fun `the carrier answers other requests while every handler thread blocks`() {
        val gate = CountDownLatch(1)
        val blocked = CountDownLatch(1)
        serving(
            testConfig.copy(carriers = 1),
            { ex ->
                if (ex.request.path == "/block") {
                    ex.offload(threads) {
                        blocked.countDown()
                        gate.await()
                        it.respond(200, body = "unblocked".toByteArray())
                    }
                } else {
                    ex.respond(404)
                }
            },
        ) { server ->
            Raw(server.port).use { slow ->
                slow.send(get("/block"))
                blocked.await()
                Raw(server.port).use { raw -> raw.send(get("/missing")).read().status shouldBe 404 }
                gate.countDown()
                slow.read().text shouldBe "unblocked"
            }
        }
    }

    @Test
    fun `pipelined requests stay in order when some are answered off the carrier`() = serving(
        handler = { ex ->
            val n = ex.request.path.removePrefix("/").toInt()
            if (n % 2 == 0) {
                ex.offload(threads) {
                    Thread.sleep((10 - n % 10).toLong())
                    it.respond(200, body = "$n".toByteArray())
                }
            } else {
                ex.respond(200, body = "$n".toByteArray())
            }
        },
    ) { server ->
        Raw(server.port).use { raw ->
            raw.send((1..40).joinToString("") { get("/$it") })
            (1..40).forEach { raw.read().text shouldBe "$it" }
        }
    }

    @Test
    fun `a detached exchange is answered from wherever its future completes`() {
        val pending = LinkedBlockingQueue<Pair<HttpExchange, CompletableFuture<String>>>()
        serving(
            handler = { ex ->
                ex.detach()
                val future = CompletableFuture<String>()
                future.thenAccept { ex.respond(200, body = it.toByteArray()) }
                pending.add(ex to future)
            },
        ) { server ->
            Raw(server.port).use { raw ->
                raw.send(get("/later"))
                val (exchange, future) = pending.take()
                Thread.ofPlatform().start { future.complete("completed for ${exchange.request.path}") }
                raw.read().text shouldBe "completed for /later"
            }
        }
    }

    @Test
    fun `an offloaded failure is answered by its kind`() = serving(
        handler = { ex ->
            ex.offload(threads) {
                when (it.request.path) {
                    "/large" -> throw BodyTooLargeException(1)
                    "/malformed" -> throw MalformedBodyException("bad")
                    "/silent" -> Unit
                    else -> error("boom")
                }
            }
        },
    ) { server ->
        Raw(server.port).use { raw ->
            raw.send(get("/large")).read().status shouldBe 413
            raw.send(get("/silent")).read().status shouldBe 500
            raw.send(get("/boom")).read().status shouldBe 500
            raw.send(get("/malformed")).read().let {
                it.status shouldBe 400
                it["Connection"] shouldBe "close"
            }
        }
    }

    @Test
    fun `an offloaded stream left open is ended, and one that fails is cut short`() = serving(
        handler = { ex ->
            ex.offload(threads) {
                val out = it.stream(200)
                out.write("open".toByteArray())
                if (it.request.path == "/fail") error("mid-stream")
            }
        },
    ) { server ->
        Raw(server.port).use { raw -> raw.send(get("/ok")).read().text shouldBe "open" }
        Raw(server.port).use { raw ->
            raw.send(get("/fail"))
            val input = raw.socket.getInputStream()
            generateSequence { line(input) }.takeWhile { it.isNotEmpty() }.toList()
            input.readAllBytes().toString(Charsets.US_ASCII) shouldBe "4\r\nopen\r\n"
        }
    }

    @Test
    fun `a stream aborted from a virtual thread is cut short`() = serving(
        handler = { ex ->
            ex.offload(threads) {
                val out = it.stream(200)
                out.write("cut".toByteArray())
                out.abort()
                out.abort()
            }
        },
    ) { server ->
        Raw(server.port).use { raw ->
            raw.send(get("/"))
            val input = raw.socket.getInputStream()
            generateSequence { line(input) }.takeWhile { it.isNotEmpty() }.toList()
            input.readAllBytes().toString(Charsets.US_ASCII) shouldBe "3\r\ncut\r\n"
        }
    }

    @Test
    fun `an executor that refuses the work is a 500`() = serving(
        handler = { ex -> ex.offload({ throw java.util.concurrent.RejectedExecutionException("full") }) {} },
    ) { server ->
        Raw(server.port).use { raw -> raw.send(get("/")).read().status shouldBe 500 }
    }

    @Test
    fun `a thread that answers after the connection closed is told so`() {
        val answered = CountDownLatch(1)
        val outcome = LinkedBlockingQueue<String>()
        serving(
            handler = { ex ->
                ex.offload(threads) {
                    answered.await()
                    val out = it.stream(200)
                    try {
                        repeat(1000) { out.write(ByteArray(64 * 1024)) }
                        outcome.add("wrote everything")
                    } catch (e: java.io.IOException) {
                        outcome.add("failed: ${e.message}")
                    }
                }
            },
        ) { server ->
            Raw(server.port).use { raw -> raw.send(get("/")) }
            answered.countDown()
            outcome.poll(30, TimeUnit.SECONDS)!! shouldStartWith "failed"
        }
    }
}
