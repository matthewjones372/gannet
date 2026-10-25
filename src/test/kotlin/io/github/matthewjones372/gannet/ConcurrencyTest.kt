package io.github.matthewjones372.gannet

import io.kotest.matchers.collections.shouldBeEmpty
import org.junit.jupiter.api.Test
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors

/** Echoes the body back, from the carrier for even paths and from a virtual thread for odd ones. */
private fun echoing(threads: java.util.concurrent.ExecutorService) = HttpHandler { ex ->
    val n = ex.request.path.substringAfterLast('/').toInt()
    if (n % 2 == 0) ex.respond(200, body = ex.request.body.readAll())
    else ex.offload(threads) { it.respond(200, body = it.request.body.readAll()) }
}

class ConcurrencyTest {
    /** [connections] clients at once, each sending [requests] requests with bodies and checking every answer. */
    private fun hammer(config: GannetConfig, connections: Int, requests: Int, bodySize: (Int) -> Int) {
        Executors.newVirtualThreadPerTaskExecutor().use { threads ->
            val failures = ConcurrentLinkedQueue<String>()
            serving(config, echoing(threads)) { server ->
                Executors.newVirtualThreadPerTaskExecutor().use { clients ->
                    repeat(connections) { c ->
                        clients.execute {
                            Raw(server.port).use { raw ->
                                repeat(requests) { r ->
                                    val body = "c$c-r$r-" + "x".repeat(bodySize(r))
                                    val head = "POST /echo/${c + r} HTTP/1.1\r\nHost: x\r\n"
                                    val reply = raw.send("${head}Content-Length: ${body.length}\r\n\r\n$body").read()
                                    if (reply.text != body) failures.add("connection $c request $r: ${reply.status}")
                                }
                            }
                        }
                    }
                }
            }
            failures.toList().shouldBeEmpty()
        }
    }

    @Test
    fun `many connections at once are each answered correctly`() =
        hammer(testConfig.copy(carriers = 4), connections = 300, requests = 20) { it * 97 % 3000 }

    @Test
    fun `connections are served when the carrier's receive buffers run out`() =
        hammer(testConfig.copy(bufferCount = 4, bufferSize = 512, heldBuffers = 2), connections = 50, requests = 10) {
            it * 1000
        }

    @Test
    fun `connections are served on ordinary descriptors`() =
        hammer(testConfig.copy(fixedFiles = 0), connections = 50, requests = 10) { it * 50 }

    @Test
    fun `pipelined bursts on many connections are answered in order`() {
        Executors.newVirtualThreadPerTaskExecutor().use { threads ->
            val failures = ConcurrentLinkedQueue<String>()
            serving(handler = echoing(threads)) { server ->
                Executors.newVirtualThreadPerTaskExecutor().use { clients ->
                    repeat(100) { c ->
                        clients.execute {
                            Raw(server.port).use { raw ->
                                raw.send((0 until 16).joinToString("") { get("/p/$it") })
                                (0 until 16).forEach { if (raw.read().status != 200) failures.add("$c/$it") }
                            }
                        }
                    }
                }
            }
            failures.toList().shouldBeEmpty()
        }
    }
}
