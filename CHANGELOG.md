# Changelog

All notable changes to Gannet are recorded here. Gannet is experimental, and until 1.0 any release may change the API.

## Unreleased

### Added

- The HTTP/1.1 server on io_uring, moved out of Pelican's `pelican-uring` module into a library of its own:
  `GannetServer`, `GannetConfig`, `GannetSupport`, `GannetUnavailableException`, and the handler API
  (`HttpHandler`, `HttpExchange`, `HttpRequest`, `Headers`, `RequestBody`, `ResponseStream`).
- `GANNET_REQUIRE_URING=1` makes the io_uring tests fail rather than skip where io_uring is unusable.
