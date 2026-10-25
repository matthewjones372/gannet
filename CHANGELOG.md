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
- `ResponseStream.flush`, which waits until the kernel has taken everything written so far: what `write` used to
  guarantee on every call. A new member of the interface, so a class of your own implementing `ResponseStream` needs
  it too.

### Changed

- `GannetConfig` has a new last parameter, `tls`, so its constructor and `copy` signatures changed.
- A streamed reply's `ResponseStream.write` no longer waits for each piece's own send. It queues the piece (sent at
  once when nothing is in flight, else right behind what is) and waits only while more than the new
  `GannetConfig.streamHighWatermark` bytes (64 KiB by default) are still unsent; zero restores a wait on every write.
  From a thread other than the carrier, a write below the watermark no longer waits for the carrier at all. A
  producer faster than one carrier round trip per piece is no longer held to that pace: 64-byte frames from another
  thread went from about 21 µs to under 2 µs each on loopback. `GannetConfig` gains that parameter, after
  `heldBuffers`.
