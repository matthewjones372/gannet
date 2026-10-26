# Changelog

All notable changes to Gannet are recorded here. Gannet is experimental, and until 1.0 any release may change the API.

## Unreleased

### Added

- `HttpExchange.respond(status, headers) { out -> ... }`: a whole reply whose body a writer puts into a buffer the
  carrier keeps and reuses, so a body encoded as it is written (a JSON library's `UTF8JsonGenerator`, say) needs no
  array of its own. On the carrier the writer runs before anything is committed, so a throw leaves the exchange for
  the caller to answer; from another thread the exchange is answered at once and the writer runs later on the
  carrier, where a throw closes the connection with the reply unsent. A default implementation collects the body into
  an array, so a class of your own implementing `HttpExchange` keeps compiling.

- The HTTP/1.1 server on io_uring, moved out of Pelican's `pelican-uring` module into a library of its own:
  `GannetServer`, `GannetConfig`, `GannetSupport`, `GannetUnavailableException`, and the handler API
  (`HttpHandler`, `HttpExchange`, `HttpRequest`, `Headers`, `RequestBody`, `ResponseStream`).
- `GANNET_REQUIRE_URING=1` makes the io_uring tests fail rather than skip where io_uring is unusable.
- TLS 1.3 and 1.2 through the JDK's `SSLEngine`: `GannetConfig.tls` takes a `GannetTls`, made from an `SSLContext`
  or from a PKCS#12 keystore, with optional protocols, cipher suites, client authentication (`GannetTls.ClientAuth`)
  and an executor for the handshake's delegated tasks. ALPN offers `http/1.1`. Pipelining, keep-alive, HEAD, chunked
  bodies, `100-continue` and streamed replies work as over plain TCP, writes still never park, and a failed handshake
  closes only its own connection. The TLS path is new.
- `ResponseStream.flush`, which waits until the kernel has taken everything written so far: what `write` used to
  guarantee on every call. A new member of the interface, so a class of your own implementing `ResponseStream` needs
  it too.
- Limits against slow and hostile clients, each a `GannetConfig` field: `headTimeout` (a head sent a byte at a time),
  `bodyTimeout` with `minBodyRate` (a body that trickles in), `writeTimeout` (a client that stops reading; a streaming
  handler's write then throws `IOException`), `handshakeTimeout` (TLS), `maxConnections` per server and
  `maxHandshakes` per carrier (both stop accepting at the cap, leaving new connections in the listen backlog), and
  `maxBufferedBytes` per connection (queued replies past it make the writer wait; unread request bytes past it close
  the connection).
- `GannetConfig.maxHeaderCount` (default 100): a request with more header field lines is answered 431.
- Fuzz targets for the request-head parser, the chunked decoder and a whole connection's input (Jazzer), replayed by
  `test` and fuzzed by the `fuzz` task and a CI smoke step; RFC 9112 conformance and request-smuggling tests.
- An epoll engine, so Gannet runs where io_uring is blocked (Docker's default seccomp profile,
  `kernel.io_uring_disabled`) or the kernel is older than 6.1. `GannetConfig.engine` takes a `GannetEngine`: `AUTO`
  (the default) runs on io_uring where it can and falls back to epoll where it cannot, logging the choice and its
  reason once at `INFO`; `IO_URING` and `EPOLL` insist on one. `GannetServer.engine` says which one a server runs on,
  `GannetSupport.engine()` which one `AUTO` picks here, and `GannetSupport.problem(engine)` what stops a given one.
  The epoll engine keeps the carrier-per-core, continuation-per-connection model, TLS, backpressure, timeouts,
  graceful stop and port zero. io_uring stays the faster engine. The epoll path is new.
- `./gradlew check` runs the whole suite on both engines: `test` on `AUTO`, and `testEpoll` with every server on epoll.
- A soak test, `./gradlew soak -PsoakMinutes=N`, outside `test`: a server in its own JVM, pinned to its own cores,
  under a mixed load for N minutes on each engine (keep-alive, Content-Length and chunked POSTs up to 4 MiB,
  pipelining, streamed replies abandoned midway, clients that trip each timeout, dropped connections, malformed
  requests, TLS with full and abandoned handshakes, churn past `maxConnections`). It samples heap after GC, direct and
  native memory, threads, descriptors and the carriers' connections, fibers and receive buffers, with throughput and
  latency per interval, and fails on a leak, an upward heap trend, degraded latency, or any 5xx or server warning.
  An opt-in `soak` workflow runs it by hand and weekly. The carriers' counts come from an internal hook, not API.

### Changed

- `GannetConfig` has new last parameters, `tls`, the limits, `maxHeaderCount` and `engine` after it, and
  `streamHighWatermark` after `heldBuffers`, so its constructor and `copy` signatures changed.
- `idleTimeout` now covers only a connection with nothing of a request read. A head, a body, a reply and a TLS
  handshake each have their own timeout, and a reply's counts from the client's last progress, not from the write.
- A streamed reply's `ResponseStream.write` no longer waits for each piece's own send. It queues the piece (sent at
  once when nothing is in flight, else right behind what is) and waits only while more than the new
  `GannetConfig.streamHighWatermark` bytes (64 KiB by default) are still unsent; zero restores a wait on every write.
  From a thread other than the carrier, a write below the watermark no longer waits for the carrier at all. A
  producer faster than one carrier round trip per piece is no longer held to that pace: 64-byte frames from another
  thread went from about 21 µs to under 2 µs each on loopback. `GannetConfig` gains that parameter, after
  `heldBuffers`.
- `RequestBody.readAll` reads a body of known length straight from the connection's input into an array of exactly
  its size, and a chunked one into an array that starts at 64 bytes and doubles, trimmed once at the end. It used to
  zero a 16 KiB buffer to read through on every call, and a second 16 KiB one to collect a chunked body into. A
  2-byte body now costs its own 24-byte array where it cost 16 KiB (32 KiB chunked), and a minimal handler reading
  small POST bodies whole went from about 208k to 302k requests a second on loopback (170k to 299k chunked). A
  declared length over 1 MiB still starts at 1 MiB and grows as the bytes arrive. Streamed reads off the carrier and
  the drain of an unread body size their buffers to what a known length has left, too.
- `GannetSupport.problem()` now reports a problem only when no engine can run: where io_uring is refused but epoll
  works, it is null. Linux older than 6.1 is no longer a problem by itself; it keeps io_uring out, and the server runs
  on epoll.

### Fixed

- `Transfer-Encoding` and `Connection` tokens were lower-cased with Unicode rules, so `chun\u212Aed` (a Kelvin sign)
  read as `chunked`; and list elements were trimmed of Unicode spaces, so `Content-Length: 5\u00A0` read as 5. Both
  now fold and trim ASCII only, and are refused.
- Chunk size lines accepted a bare LF, leading or trailing whitespace, and anything after a `;`, including a bare CR;
  chunk data could end in a bare LF. Chunk lines now need CR LF and extensions must parse as RFC 9112 says.
- Trailer fields were thrown away unchecked; a malformed one now breaks the body.
- A `Host` value that is not `uri-host [ ":" port ]`, and an absolute-form target with an empty host or userinfo,
  were accepted; both are 400 now.
- A `maxHeaderBytes` near `Int.MAX_VALUE` overflowed the parser's index arithmetic.
- Empty lines before a request were skipped without limit; more than 256 bytes of them is a 400.
