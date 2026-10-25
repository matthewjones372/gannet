package io.github.matthewjones372.gannet

import java.net.http.HttpClient
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyStore
import java.time.Duration
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLParameters
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManagerFactory

/** A self-signed certificate for 127.0.0.1, made with the JDK's keytool once per test run. */
object TestCerts {
    val password: CharArray = "gannet-test".toCharArray()

    val keyStore: Path by lazy {
        val dir = Files.createTempDirectory("gannet-tls")
        val file = dir.resolve("server.p12")
        val keytool = Path.of(System.getProperty("java.home"), "bin", "keytool").toString()
        val process = ProcessBuilder(
            keytool, "-genkeypair", "-alias", "gannet", "-keyalg", "EC", "-groupname", "secp256r1",
            "-dname", "CN=localhost", "-ext", "SAN=ip:127.0.0.1,dns:localhost", "-validity", "2",
            "-storetype", "PKCS12", "-keystore", file.toString(),
            "-storepass", String(password), "-keypass", String(password),
        ).redirectErrorStream(true).start()
        val output = process.inputStream.readAllBytes().decodeToString()
        check(process.waitFor(60, TimeUnit.SECONDS) && process.exitValue() == 0) { "keytool failed: $output" }
        file.toFile().deleteOnExit()
        dir.toFile().deleteOnExit()
        file
    }

    /** Trusts exactly the test certificate. */
    val trust: TrustManagerFactory by lazy {
        val server = KeyStore.getInstance("PKCS12").apply {
            Files.newInputStream(keyStore).use { load(it, password) }
        }
        val anchors = KeyStore.getInstance("PKCS12").apply {
            load(null, null)
            setCertificateEntry("gannet", server.getCertificate("gannet"))
        }
        TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(anchors) }
    }

    val client: SSLContext by lazy { SSLContext.getInstance("TLS").apply { init(null, trust.trustManagers, null) } }
}

val testTls = GannetTls(TestCerts.keyStore, TestCerts.password)

val tlsConfig = testConfig.copy(tls = testTls)

/** A TLS socket to [port], handshaken, offering [alpn] when given and limited to [protocols] when given. */
fun tlsSocket(port: Int, protocols: List<String>? = null, alpn: List<String>? = null): SSLSocket {
    val socket = TestCerts.client.socketFactory.createSocket("127.0.0.1", port) as SSLSocket
    socket.soTimeout = 20_000
    socket.sslParameters = socket.sslParameters.also { p ->
        protocols?.let { p.protocols = it.toTypedArray() }
        alpn?.let { p.applicationProtocols = it.toTypedArray() }
    }
    socket.startHandshake()
    return socket
}

/** A [Raw] client over TLS. */
fun tlsRaw(port: Int, protocols: List<String>? = null): Raw = Raw(tlsSocket(port, protocols))

/** The JDK's client, trusting the test certificate, limited to [protocols] when given. */
fun httpsClient(protocols: List<String>? = null): HttpClient = HttpClient.newBuilder()
    .sslContext(TestCerts.client)
    .connectTimeout(Duration.ofSeconds(10))
    .apply { protocols?.let { sslParameters(SSLParameters(null, it.toTypedArray())) } }
    .build()
