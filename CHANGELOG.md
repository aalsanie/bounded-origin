# Changelog

## Unreleased

### Policy

* Added policy evaluation. Requests are reduced to stable operation keys before any expensive origin work is considered.
* Added explicit execution modes for serving existing artifacts, bounded origin work, durable materialization, client-side computation, and denial.

### Origin execution

* Added single-flight origin execution. Concurrent requests for the same operation share one origin job instead of multiplying upstream work.
* Added bounded scheduling so origin work cannot grow with request volume. Overload is rejected explicitly rather than pushed into an unbounded executor.
* Added failure cooldowns to stop repeatedly failing operations from turning into retry storms.

### Artifact store

* Added a durable SHA-256 content-addressed filesystem store. Artifact bodies are streamed, deduplicated, and published atomically.
* Added startup recovery and corruption handling. Incomplete or invalid artifacts are never served.
* Added bounded storage with deterministic eviction while active readers remain safe.

### Gateway

* Added the first Netty 4.2 HTTP/1.1 gateway implementation.
* Request and origin bodies are streamed through bounded spools; untrusted uploads do not consume origin-execution capacity while still arriving.
* Client disconnects do not cancel shared origin work or trigger duplicate materialization.
* Added bounded origin connection pooling, strict HTTP parsing, backpressure, graceful draining, and health/readiness/metrics endpoints.
