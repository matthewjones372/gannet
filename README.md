# Gannet

> [!WARNING]
> **Experimental. Not for production use.**
>
> - TLS is new and has had little scrutiny: no kernel TLS offload and no handshake-level DoS protection, and the
>   handler cannot see the client's certificate yet. A proxy that terminates TLS remains the safer choice.
> - HTTP/1.1 (and 1.0) only; no HTTP/2, no WebSockets.
> - Linux only, on x86-64 or arm64. io_uring needs Linux 6.1 or later; the epoll engine is new.
> - It uses a JDK internal, `jdk.internal.vm.Continuation`, which the JDK may change or remove in any release.
> - The API may change between any two versions until 1.0.

Gannet is an HTTP/1.1 server for the JVM built directly on Linux's io_uring. It runs with or without io_uring:
where io_uring is blocked, as it is in Docker's default seccomp profile and under `kernel.io_uring_disabled`, it
falls back to an epoll engine with the same API and features.

- **One carrier thread per core.** Each carrier is a platform thread with its own io_uring (or epoll instance) and
  its own listening socket, all sharing one port (`SO_REUSEPORT`). A connection stays on the carrier that accepted it,
  so carriers share nothing on the hot path.
- **Connections are continuations.** Each connection runs as a continuation on its carrier. A handler is written as
  straight-line, blocking-style code: reading the request body or writing a streamed reply parks that connection's
  continuation until the I/O completes, and the carrier serves other connections meanwhile. No
  callbacks, no futures, no coroutines.
- **Blocking work is handed off explicitly.** Anything that blocks the *thread* (JDBC, a lock held across a wait,
  `Thread.sleep`) goes to an executor of your choosing through `exchange.offload(...)`, or the exchange is detached and
  answered later from any thread. Every exchange call works from any thread.
- **Backpressure is built in.** Each `ResponseStream.write` returns once the kernel has taken the bytes, so a slow
  reader holds the writer back.
- **TLS 1.3 and 1.2, through the JDK's `SSLEngine`** (new). Ciphertext from the ring's receive buffers is
  decrypted into the request parser's buffer, and replies are encrypted on their way to the same send path, so writes
  still never park and backpressure is unchanged. ALPN offers `http/1.1`.

## Requirements

- Linux on x86-64 or arm64. io_uring, the faster engine, needs Linux 6.1+ with io_uring enabled; where it is not,
  Gannet runs on epoll instead.
- JDK 25+.
- Two JVM flags on every JVM that runs a server:

  ```
  --enable-native-access=ALL-UNNAMED --add-exports java.base/jdk.internal.vm=ALL-UNNAMED
  ```

  The same string is `GannetSupport.FLAGS`. Both engines need both flags.

`GannetSupport.problem()` returns null when this machine and JVM can run a server on some engine, and otherwise a
message saying what stands in the way and how to fix it. `GannetServer.start` throws `GannetUnavailableException`
with that message.

## Engines

`GannetConfig.engine` picks what the carriers do their I/O through:

- `AUTO` (the default): io_uring where this machine allows it, else epoll. The choice and the reason are logged
  once, at `INFO`, on the `io.github.matthewjones372.gannet` logger.
- `IO_URING`: io_uring only. Fails to start where io_uring is refused.
- `EPOLL`: epoll and non-blocking sockets only.

io_uring is the faster engine: it sends a whole pass's I/O in one system call and receives into buffers the kernel
picks, where epoll makes a system call for every receive and every send. On a keep-alive loop of small GETs on a
shared 4-core VM, epoll served between about three quarters of io_uring's requests per second (two carriers, 124k
against 166k) and as many (one carrier, about 74k each); the gap widens as a pass carries more I/O. Both engines keep the same model (a carrier per core,
connections as continuations, writes that never park) and the same features: TLS, backpressure, timeouts, graceful
stop and port zero.

`server.engine` says which engine a running server is on, and `GannetSupport.engine()` which one `AUTO` would pick
here. `GannetSupport.problem(GannetEngine.IO_URING)` says why io_uring in particular cannot run.

## Usage

```kotlin
dependencies {
    implementation("io.github.matthewjones372:gannet:0.1.0-SNAPSHOT")
}
```

```kotlin
import io.github.matthewjones372.gannet.GannetConfig
import io.github.matthewjones372.gannet.GannetServer
import java.util.concurrent.Executors

fun main() {
    val blocking = Executors.newVirtualThreadPerTaskExecutor()

    val server = GannetServer.start(GannetConfig(host = "127.0.0.1", port = 8080)) { exchange ->
        val request = exchange.request
        when (request.path) {
            "/hello" -> exchange.respond(200, listOf("Content-Type" to "text/plain"), "hello\n".toByteArray())

            // Reading the body parks this connection's continuation, not the carrier thread.
            "/echo" -> exchange.respond(200, body = request.body.readAll(limit = 1L shl 20))

            // Anything that blocks the thread itself (JDBC, a lock, a sleep) goes elsewhere.
            "/report" -> exchange.offload(blocking) { ex ->
                ex.respond(200, body = slowReport().toByteArray())
            }

            "/count" -> exchange.stream(200, listOf("Content-Type" to "text/plain")).use { out ->
                (1..5).forEach { out.write("$it\n".toByteArray()) } // each write waits for the kernel
            }

            else -> exchange.respond(404)
        }
    }

    println("Listening on http://${server.host}:${server.port}/hello")
    server.awaitStopped()
}
```

The handler runs on the carrier. Returning without answering (and without detaching) is answered with a 500; a throw
is answered with a 413 for `BodyTooLargeException`, a 400 for `MalformedBodyException`, and a 500 otherwise. `Date`,
`Content-Length` and chunked framing are added for you. `GannetConfig` holds the limits (request line, header and
body sizes, idle timeout, stop grace), the engine, and the ring and buffer sizing.

### TLS

```kotlin
import io.github.matthewjones372.gannet.GannetTls
import java.nio.file.Path

val tls = GannetTls(Path.of("server.p12"), "changeit".toCharArray())
val server = GannetServer.start(GannetConfig(port = 8443, tls = tls)) { exchange ->
    exchange.respond(200, body = "hello over TLS\n".toByteArray())
}
```

The port then speaks only TLS. `GannetTls(sslContext, ...)` takes any `SSLContext`, plus the protocols (TLS 1.3 and 1.2
by default), cipher suites and whether to ask for a client certificate (`ClientAuth.NONE`, `WANT` or `NEED`). The
handshake's CPU-heavy steps run on the carrier unless `handshakeExecutor` names an executor, where they run while the
connection waits and the carrier serves the others. A failed handshake closes only that connection and is logged at
`DEBUG` on the `io.github.matthewjones372.gannet` logger.

`server.stop()` stops accepting, closes idle connections, lets exchanges in progress finish within
`GannetConfig.stopGrace`, and joins the carriers.

## Building and testing

```
./gradlew build
```

`build` compiles, runs the tests, and checks detekt, Spotless (ktlint), the binary API dump in `api/gannet.api`
(`./gradlew apiDump` after an intended API change), and, where the io_uring tests run, the Kover coverage floor.

The tests start real servers, and `check` runs the whole suite twice: `test` with servers on `AUTO`, which is io_uring
where the host allows it, and `testEpoll` with every server on epoll. Where no engine can run (off Linux) they skip.
Set `GANNET_REQUIRE_URING=1` to make them fail instead, and to make `test` insist on io_uring rather than fall back,
which is what CI does. CI also runs `test` inside Docker's default seccomp profile, where `AUTO` has to fall back.

`./gradlew publishToMavenLocal` installs the current snapshot without needing signing keys. A release is a `v*` tag,
which the `publish` workflow builds, signs and uploads to Maven Central.

## License

Apache License 2.0. See [LICENSE](LICENSE).
