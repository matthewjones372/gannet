# Changelog

All notable changes to Gannet are recorded here. Gannet is experimental, and until 1.0 any release may change the API.

## Unreleased

### Added

- The HTTP/1.1 server on io_uring, moved out of Pelican's `pelican-uring` module into a library of its own:
  `GannetServer`, `GannetConfig`, `GannetSupport`, `GannetUnavailableException`, and the handler API
  (`HttpHandler`, `HttpExchange`, `HttpRequest`, `Headers`, `RequestBody`, `ResponseStream`).
- `GANNET_REQUIRE_URING=1` makes the io_uring tests fail rather than skip where io_uring is unusable.
- TLS 1.3 and 1.2 through the JDK's `SSLEngine`: `GannetConfig.tls` takes a `GannetTls`, made from an `SSLContext`
  or from a PKCS#12 keystore, with optional protocols, cipher suites, client authentication (`GannetTls.ClientAuth`)
  and an executor for the handshake's delegated tasks. ALPN offers `http/1.1`. Pipelining, keep-alive, HEAD, chunked
  bodies, `100-continue` and streamed replies work as over plain TCP, writes still never park, and a failed handshake
  closes only its own connection. The TLS path is new.
- An epoll engine, so Gannet runs where io_uring is blocked (Docker's default seccomp profile,
  `kernel.io_uring_disabled`) or the kernel is older than 6.1. `GannetConfig.engine` takes a `GannetEngine`: `AUTO`
  (the default) runs on io_uring where it can and falls back to epoll where it cannot, logging the choice and its
  reason once at `INFO`; `IO_URING` and `EPOLL` insist on one. `GannetServer.engine` says which one a server runs on,
  `GannetSupport.engine()` which one `AUTO` picks here, and `GannetSupport.problem(engine)` what stops a given one.
  The epoll engine keeps the carrier-per-core, continuation-per-connection model, TLS, backpressure, timeouts,
  graceful stop and port zero. io_uring stays the faster engine. The epoll path is new.
- `./gradlew check` runs the whole suite on both engines: `test` on `AUTO`, and `testEpoll` with every server on epoll.

### Changed

- `GannetConfig` has two new last parameters, `tls` and `engine`, so its constructor and `copy` signatures changed.
- `GannetSupport.problem()` now reports a problem only when no engine can run: where io_uring is refused but epoll
  works, it is null. Linux older than 6.1 is no longer a problem by itself; it keeps io_uring out, and the server runs
  on epoll.
