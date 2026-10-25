package io.github.matthewjones372.gannet

import io.kotest.matchers.shouldBe
import org.eclipse.jetty.client.BytesRequestContent
import org.eclipse.jetty.client.HttpClient
import org.junit.jupiter.api.Test
import java.net.URI
import java.net.http.HttpRequest.BodyPublishers
import java.net.http.HttpResponse.BodyHandlers
import java.util.concurrent.Executors
import java.net.http.HttpClient as JdkClient
import java.net.http.HttpRequest as JdkRequest

/** One endpoint per reply shape: strict, empty, streamed, large, echoed, refused. */
private fun shapes(threads: java.util.concurrent.ExecutorService) = HttpHandler { ex ->
    when (ex.request.path) {
        "/strict" -> ex.respond(200, listOf("Content-Type" to "text/plain"), "strict".toByteArray())

        "/empty" -> ex.respond(204)

        "/stream" -> ex.offload(threads) { e ->
            e.stream(200).use { out ->
                repeat(100) {
                    out.write("frame $it\n".toByteArray())
                }
            }
        }

        "/large" -> ex.respond(200, body = ByteArray(1_000_000) { 'L'.code.toByte() })

        "/echo" -> ex.offload(threads) { e -> e.respond(200, body = e.request.body.readAll()) }

        else -> ex.respond(404, body = "nowhere".toByteArray())
    }
}

private val frames = (0 until 100).joinToString("") { "frame $it\n" }

class ClientsTest {
    @Test
    fun `the JDK client reads every reply shape`() = Executors.newVirtualThreadPerTaskExecutor().use { threads ->
        serving(handler = shapes(threads)) { server ->
            val client = JdkClient.newBuilder().version(JdkClient.Version.HTTP_1_1).build()
            fun send(path: String, method: String = "GET", body: ByteArray? = null) = client.send(
                JdkRequest.newBuilder(URI("http://127.0.0.1:${server.port}$path"))
                    .method(method, body?.let { BodyPublishers.ofByteArray(it) } ?: BodyPublishers.noBody())
                    .build(),
                BodyHandlers.ofString(),
            )
            repeat(3) { _ ->
                send("/strict").body() shouldBe "strict"
                send("/empty").statusCode() shouldBe 204
                send("/stream").body() shouldBe frames
                send("/large").body().length shouldBe 1_000_000
                send("/echo", "POST", "posted".toByteArray()).body() shouldBe "posted"
                send("/strict", "HEAD").let {
                    it.statusCode() shouldBe 200
                    it.body() shouldBe ""
                }
                send("/missing").statusCode() shouldBe 404
            }
            client.close()
        }
    }

    @Test
    fun `the Jetty client reads every reply shape`() = Executors.newVirtualThreadPerTaskExecutor().use { threads ->
        serving(handler = shapes(threads)) { server ->
            val client = HttpClient().apply { start() }
            val base = "http://127.0.0.1:${server.port}"
            try {
                repeat(3) {
                    client.GET("$base/strict").contentAsString shouldBe "strict"
                    client.GET("$base/empty").status shouldBe 204
                    client.GET("$base/stream").contentAsString shouldBe frames
                    client.newRequest("$base/large").send().content.size shouldBe 1_000_000
                    client.POST("$base/echo").body(BytesRequestContent("jetty".toByteArray())).send()
                        .contentAsString shouldBe "jetty"
                    client.newRequest("$base/strict").method("HEAD").send().status shouldBe 200
                    client.GET("$base/missing").status shouldBe 404
                }
            } finally {
                client.stop()
            }
        }
    }
}
