package io.github.matthewjones372.gannet

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import java.nio.file.Path

/** Configuration alone: no server, so these run wherever the JDK does. */
class TlsConfigTest {
    @Test
    fun `the defaults are TLS 1·3 and 1·2, no client certificate, and http 1·1 by ALPN`() {
        testTls.protocols shouldBe listOf("TLSv1.3", "TLSv1.2")
        testTls.clientAuth shouldBe GannetTls.ClientAuth.NONE
        testTls.cipherSuites shouldBe null
        GannetTls.APPLICATION_PROTOCOLS shouldBe listOf("http/1.1")
        testTls.toString() shouldContain "TLSv1.3"
        GannetConfig().tls shouldBe null
    }

    @Test
    fun `a protocol or suite the context lacks is refused at once`() {
        shouldThrow<IllegalArgumentException> { GannetTls(testTls.context, protocols = listOf("TLSv0.9")) }
        shouldThrow<IllegalArgumentException> { GannetTls(testTls.context, cipherSuites = listOf("TLS_NOTHING")) }
        shouldThrow<IllegalArgumentException> { GannetTls(testTls.context, protocols = emptyList()) }
        shouldThrow<IllegalArgumentException> { GannetTls(testTls.context, cipherSuites = emptyList()) }
    }

    @Test
    fun `chosen suites are kept`() {
        val suites = listOf("TLS_AES_128_GCM_SHA256")
        GannetTls(testTls.context, protocols = listOf("TLSv1.3"), cipherSuites = suites).cipherSuites shouldBe suites
    }

    @Test
    fun `a keystore that is not there is an error, not a server without TLS`() {
        shouldThrow<java.nio.file.NoSuchFileException> {
            GannetTls(Path.of("/nonexistent/gannet.p12"), "x".toCharArray())
        }
    }
}
