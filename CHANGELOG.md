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
- Limits against slow and hostile clients, each a `GannetConfig` field: `headTimeout` (a head sent a byte at a time),
  `bodyTimeout` with `minBodyRate` (a body that trickles in), `writeTimeout` (a client that stops reading; a streaming
  handler's write then throws `IOException`), `handshakeTimeout` (TLS), `maxConnections` per server and
  `maxHandshakes` per carrier (both stop accepting at the cap, leaving new connections in the listen backlog), and
  `maxBufferedBytes` per connection (queued replies past it make the writer wait; unread request bytes past it close
  the connection).

- `GannetConfig.maxHeaderCount` (default 100): a request with more header field lines is answered 431.
- Fuzz targets for the request-head parser, the chunked decoder and a whole connection's input (Jazzer), replayed by
  `test` and fuzzed by the `fuzz` task and a CI smoke step; RFC 9112 conformance and request-smuggling tests.

### Changed

- `GannetConfig` has new last parameters, `tls`, the limits and `maxHeaderCount` after it, and
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
