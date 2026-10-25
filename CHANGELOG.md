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
- Limits against slow and hostile clients, each a `GannetConfig` field: `headTimeout` (a head sent a byte at a time),
  `bodyTimeout` with `minBodyRate` (a body that trickles in), `writeTimeout` (a client that stops reading; a streaming
  handler's write then throws `IOException`), `handshakeTimeout` (TLS), `maxConnections` per server and
  `maxHandshakes` per carrier (both stop accepting at the cap, leaving new connections in the listen backlog), and
  `maxBufferedBytes` per connection (queued replies past it make the writer wait; unread request bytes past it close
  the connection).

### Changed

- `GannetConfig` has new last parameters, `tls` and the limits after it, so its constructor and `copy` signatures
  changed.
- `idleTimeout` now covers only a connection with nothing of a request read. A head, a body, a reply and a TLS
  handshake each have their own timeout, and a reply's counts from the client's last progress, not from the write.
