package io.github.matthewjones372.gannet

import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyStore
import java.util.concurrent.Executor
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine

/**
 * TLS for every connection a [GannetServer] accepts, through the JDK's own `SSLEngine`. Set it as [GannetConfig.tls];
 * the server then speaks only TLS on its port.
 *
 * ALPN offers `http/1.1`, so a client that also offers `h2` is answered over HTTP/1.1, and one that offers only
 * protocols the server does not speak is refused during the handshake.
 *
 * @property context where the keys, the trust and the TLS implementation come from.
 * @property clientAuth whether a client certificate is asked for; [context]'s trust managers check one that is sent.
 * @property protocols the TLS versions accepted, as `SSLParameters` names them.
 * @property cipherSuites the cipher suites accepted, or null for [context]'s defaults.
 * @property handshakeExecutor where the handshake's CPU-heavy steps (key exchange and signing, the engine's delegated
 *   tasks) run. Null runs them on the carrier, which stalls every other connection on it meanwhile; an executor runs
 *   them there while the connection's fiber parks and the carrier serves the rest.
 */
class GannetTls(
    val context: SSLContext,
    val clientAuth: ClientAuth = ClientAuth.NONE,
    val protocols: List<String> = DEFAULT_PROTOCOLS,
    val cipherSuites: List<String>? = null,
    val handshakeExecutor: Executor? = null,
) {
    /**
     * TLS with the key and certificate chain in the PKCS#12 file at [keyStore], whose store and key share
     * [password], and the other settings at their defaults.
     */
    constructor(keyStore: Path, password: CharArray) : this(pkcs12(keyStore, password))

    /** Whether a client is asked for a certificate during the handshake. */
    enum class ClientAuth {
        /** No certificate is asked for. */
        NONE,

        /** A certificate is asked for; a client without one is still served. */
        WANT,

        /** A certificate is required; a client without one fails the handshake. */
        NEED,
    }

    init {
        require(protocols.isNotEmpty()) { "protocols must name at least one TLS version" }
        require(cipherSuites == null || cipherSuites.isNotEmpty()) { "cipherSuites must be null or name one suite" }
        // Made once here, so a protocol or suite the context does not support fails now rather than per connection.
        engine()
    }

    /** A server-side engine for one connection, with this configuration's parameters. */
    internal fun engine(): SSLEngine = context.createSSLEngine().apply {
        useClientMode = false
        sslParameters = sslParameters.also { p ->
            p.protocols = protocols.toTypedArray()
            cipherSuites?.let { p.cipherSuites = it.toTypedArray() }
            when (clientAuth) {
                ClientAuth.NONE -> p.needClientAuth = false
                ClientAuth.WANT -> p.wantClientAuth = true
                ClientAuth.NEED -> p.needClientAuth = true
            }
            // Server preference order: when `h2` joins, it goes first, and the connection reads the engine's
            // `applicationProtocol` after the handshake to choose which protocol to serve.
            p.applicationProtocols = APPLICATION_PROTOCOLS.toTypedArray()
        }
    }

    override fun toString(): String =
        "GannetTls(protocols=$protocols, clientAuth=$clientAuth, cipherSuites=${cipherSuites ?: "default"}, " +
            "handshakeExecutor=${handshakeExecutor != null})"

    companion object {
        /** TLS 1.3 and 1.2, newest first. */
        val DEFAULT_PROTOCOLS: List<String> = listOf("TLSv1.3", "TLSv1.2")

        /** The application protocols ALPN offers, in the server's order of preference. */
        val APPLICATION_PROTOCOLS: List<String> = listOf("http/1.1")

        /**
         * An `SSLContext` whose key managers hold the PKCS#12 file at [keyStore], read with [password] for the store
         * and its key; its trust managers are the JDK's defaults.
         */
        fun pkcs12(keyStore: Path, password: CharArray): SSLContext {
            val store = KeyStore.getInstance("PKCS12")
            Files.newInputStream(keyStore).use { store.load(it, password) }
            val keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
            keys.init(store, password)
            return SSLContext.getInstance("TLS").apply { init(keys.keyManagers, null, null) }
        }
    }
}
