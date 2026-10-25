package io.github.matthewjones372.gannet.soak

import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyStore
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory

/** A self-signed certificate for 127.0.0.1, made with the JDK's keytool: the server loads it, the load trusts it. */
internal object Certs {
    val password: CharArray = "gannet-soak".toCharArray()

    /** Makes a PKCS#12 keystore in [dir] and says where it is. */
    fun make(dir: Path): Path {
        val file = dir.resolve("soak.p12")
        Files.deleteIfExists(file)
        val keytool = Path.of(System.getProperty("java.home"), "bin", "keytool").toString()
        val process = ProcessBuilder(
            keytool, "-genkeypair", "-alias", "gannet", "-keyalg", "EC", "-groupname", "secp256r1",
            "-dname", "CN=localhost", "-ext", "SAN=ip:127.0.0.1,dns:localhost", "-validity", "7",
            "-storetype", "PKCS12", "-keystore", file.toString(),
            "-storepass", String(password), "-keypass", String(password),
        ).redirectErrorStream(true).start()
        val output = process.inputStream.readAllBytes().decodeToString()
        check(process.waitFor(KEYTOOL_SECONDS, TimeUnit.SECONDS) && process.exitValue() == 0) {
            "keytool failed: $output"
        }
        return file
    }

    /** A client context that trusts exactly the certificate in [keyStore]. */
    fun client(keyStore: Path): SSLContext {
        val server = KeyStore.getInstance("PKCS12").apply { Files.newInputStream(keyStore).use { load(it, password) } }
        val anchors = KeyStore.getInstance("PKCS12").apply {
            load(null, null)
            setCertificateEntry("gannet", server.getCertificate("gannet"))
        }
        val trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(anchors) }
        return SSLContext.getInstance("TLS").apply { init(null, trust.trustManagers, null) }
    }

    private const val KEYTOOL_SECONDS = 60L
}
