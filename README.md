# Gannet

> [!WARNING]
> **Experimental. Not for production use.**
>
> - TLS is new and has had little scrutiny: no kernel TLS offload, and the handler cannot see the client's
>   certificate yet. A handshake timeout and a per-carrier cap on handshakes blunt a handshake flood, but nothing
>   limits handshakes per client address. A proxy that terminates TLS remains the safer choice.
> - The limits below are new. They bound what one connection can hold and for how long, but there is no per-address
>   limiting or rate limiting, and a client that reads a reply slowly but steadily is held only by the caps.
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
- **Backpressure is built in.** A `ResponseStream.write` queues its bytes and returns while the bytes not yet taken
  by the kernel stay under `GannetConfig.streamHighWatermark` (64 KiB by default); past it the write waits, so a slow
  reader holds the writer back and memory stays bounded. `ResponseStream.flush` waits until the kernel has taken
  everything written.
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

            // A body written straight into the carrier's buffer, as a JSON library encoding to a stream would.
            "/written" -> exchange.respond(200, listOf("Content-Type" to "text/plain")) { out ->
                out.write("written in place\n".toByteArray())
            }

            // Reading the body parks this connection's continuation, not the carrier thread.
            "/echo" -> exchange.respond(200, body = request.body.readAll(limit = 1L shl 20))

            // Anything that blocks the thread itself (JDBC, a lock, a sleep) goes elsewhere.
            "/report" -> exchange.offload(blocking) { ex ->
                ex.respond(200, body = slowReport().toByteArray())
            }

            "/count" -> exchange.stream(200, listOf("Content-Type" to "text/plain")).use { out ->
                (1..5).forEach { out.write("$it\n".toByteArray()) } // waits only past the watermark
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
`Content-Length` and chunked framing are added for you. `GannetConfig` holds the limits (request line, header size
and count, body size, timeouts, connection and memory caps, stop grace), the engine, and the ring and buffer sizing.

### Limits

One slow or hostile client cannot hold a connection, a carrier's handshake capacity or memory indefinitely. Every
limit is a `GannetConfig` field:

| Field | Default | What happens past it |
|---|---|---|
| `idleTimeout` | 30 s | A connection with nothing of a request read (before the first, or between keep-alive requests) is closed. |
| `headTimeout` | 20 s | A request head not complete this long after its first byte is closed: a head sent a byte at a time (slow loris) is cut off. |
| `bodyTimeout`, `minBodyRate` | 30 s, 1024 B/s | Time spent waiting for body bytes may reach `bodyTimeout` plus one second per `minBodyRate` bytes read; past it the connection is closed. Time the handler spends between reads does not count. |
| `writeTimeout` | 30 s | A reply the client takes none of for this long closes the connection, and a handler writing to it gets an `IOException`. |
| `handshakeTimeout` | 10 s | A TLS handshake not finished this long after the accept is closed. |
| `maxConnections` | 10 000 | Connections served at once, per server. At the cap each carrier stops accepting: the connection it accepted last waits unserved, the rest wait in the listen backlog, and nothing is refused. |
| `maxHandshakes` | 64 | TLS handshakes in progress at once, per carrier. At the cap that carrier stops accepting until one finishes. |
| `maxBufferedBytes` | 1 MiB | Replies queued for one connection while a send is in flight: past it the writer waits for the client, under `writeTimeout`, and no more pipelined requests are read meanwhile. Unread request bytes: past it the connection is closed. |
| `maxRequestLineBytes`, `maxHeaderBytes`, `maxHeaderCount` | 8 KiB, 16 KiB, 100 | Answered 414 or 431, and closed. |
| `maxBodyBytes`, `drainBytes` | unlimited, 64 KiB | A body read past `maxBodyBytes` fails with `BodyTooLargeException` (413); an unread body longer than `drainBytes` closes the connection rather than being read and dropped. |
| `heldBuffers` | 16 | A connection holding this many unread receive buffers has its receive paused, so the kernel's window slows the sender. |

The carrier checks the timeouts on each tick (`GannetConfig.tick`, 250 ms by default), so one fires up to a tick late.
A connection closed by a limit is shut down without a reply.

### Request parsing

Request parsing follows RFC 9112 strictly where leniency invites request smuggling: `Content-Length` with
`Transfer-Encoding`, differing or malformed lengths, obfuscated transfer codings, bare CRs, obs-fold, invalid `Host`
values and malformed chunk lines or extensions are refused with a 4xx or 5xx status and the connection closed. The
head parser and chunked decoder are fuzzed with Jazzer, and a table of malformed and smuggling requests runs as tests.

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

`./gradlew fuzz -PfuzzTarget='*HeadFuzzTest*' -PfuzzSeconds=300` fuzzes one target (`HeadFuzzTest`,
`ChunkedFuzzTest` or `ConnectionFuzzTest`); `test` replays each target's seeds and saved findings from
`src/test/resources`.

The tests start real servers, and `check` runs the whole suite twice: `test` with servers on `AUTO`, which is io_uring
where the host allows it, and `testEpoll` with every server on epoll. Where no engine can run (off Linux) they skip.
Set `GANNET_REQUIRE_URING=1` to make them fail instead, and to make `test` insist on io_uring rather than fall back,
which is what CI does. CI also runs `test` inside Docker's default seccomp profile, where `AUTO` has to fall back.

### Soak testing

```
./gradlew soak -PsoakMinutes=20
```

`soak` is not part of `build` (`check` only compiles and lints it). It starts the servers in a JVM of their own and, for
the given minutes on each engine (`-PsoakEngines=IO_URING,EPOLL` by default), drives a mixed load against them from a
second JVM: keep-alive GETs, Content-Length and chunked POSTs up to 4 MiB, pipelined batches, streamed replies (some
written from another thread, a fifth abandoned midway), clients that trip the head, body, write and idle timeouts,
connections dropped mid-request, malformed requests, TLS requests, full and abandoned handshakes, connection churn, and
a server with a cap of 32 connections filled past it. The clients meant to trip a limit go to servers whose limits are 2
and 5 seconds, the rest to servers whose limits are generous, so a load generator short of cpu never trips one by
accident. On a machine of four cores or more the servers and the load are pinned (`taskset`) to separate halves of them;
`-PsoakServerCpus=0-1 -PsoakLoadCpus=2-3` says which.

Every interval it records, from the server's JVM, live heap after a full GC, direct and native memory (NMT), RSS and
mappings, threads, open descriptors, and each server's open connections, fibers and free receive buffers (an internal
hook, not API), and from the load, throughput and latency percentiles per scenario, with the machine's load average
and per-core busy time. After the load stops and the server settles, it fails unless descriptors, threads and direct
memory are back to the post-warm-up baseline, every connection is closed and every buffer returned, the live heap's
least-squares slope over the steady state (the run after its first fifth) is below 0.25 MB/min (`-PsoakHeapSlope`),
the last interval's GET p50 and p99 are within 1.5x + 0.5 ms and 2x + 5 ms of the first's, and no request failed for
a reason that is the server's fault, no 5xx was answered and the server logged no warning. `build/soak/<engine>/` holds
`samples.csv`, `summary.txt` (series, checks) and `server.log` (class histograms at the baseline and the end).

The `soak` workflow runs it by hand (`workflow_dispatch`, 20 minutes per engine by default) and weekly.

`./gradlew publishToMavenLocal` installs the current snapshot without needing signing keys. A release is a `v*` tag,
which the `publish` workflow builds, signs and uploads to Maven Central.

## Snapshots

Untagged commits can be published to Maven Central's snapshot repository by dispatching the `snapshot` workflow from
a branch. Snapshots are unsigned and dropped after 90 days. To use one, add the repository and depend on the next
version with `-SNAPSHOT` appended, e.g. `0.1.0-SNAPSHOT`:

```kotlin
repositories {
    mavenCentral()
    maven("https://central.sonatype.com/repository/maven-snapshots/")
}
```

## License

Apache License 2.0. See [LICENSE](LICENSE).
