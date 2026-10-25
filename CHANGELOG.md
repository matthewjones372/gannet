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

- `GannetConfig.maxHeaderCount` (default 100): a request with more header field lines is answered 431.
- Fuzz targets for the request-head parser, the chunked decoder and a whole connection's input (Jazzer), replayed by
  `test` and fuzzed by the `fuzz` task and a CI smoke step; RFC 9112 conformance and request-smuggling tests.

### Changed

- `GannetConfig` has new last parameters, `tls` and `maxHeaderCount`, so its constructor and `copy` signatures
  changed.

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
