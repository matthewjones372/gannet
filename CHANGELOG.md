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

### Changed

- `GannetConfig` has a new last parameter, `tls`, so its constructor and `copy` signatures changed.
