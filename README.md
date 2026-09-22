# Bounded Origin

[![Verification](https://img.shields.io/github/actions/workflow/status/aalsanie/bounded-origin/ci.yml?branch=main&label=verification)](https://github.com/aalsanie/bounded-origin/actions/workflows/ci.yml)
[![License: AGPL-3.0](https://img.shields.io/badge/license-AGPL--3.0-blue)](LICENSE)

**HTTP admission control for expensive origin work.**

Bounded Origin is a Java 21 gateway for endpoints where a cheap request can trigger expensive backend computation. It maps requests to semantic operations, coalesces equivalent in-flight work, applies global and per-policy execution budgets, can persist reusable artifacts, and denies unmatched traffic by default.

> **Invariant:** untrusted demand must not control the rate of expensive origin computation.

## The problem

Transport-level controls do not define computational work.

A connection limit bounds connections. A request-rate limit bounds requests. A timeout bounds how long one component waits. None of those, by themselves, establish which requests represent the same expensive operation or how much new origin work may be admitted.

Bounded Origin moves that decision to a semantic operation boundary:

```text
request
  -> policy match
  -> semantic operation key
  -> artifact / single-flight / admission decision
  -> origin
```

This lets the gateway distinguish duplicate work from genuinely new work and apply computation budgets before dispatching it.

## Execution strategies

Each route selects one strategy:

| Strategy | Behavior |
| --- | --- |
| `DENY` | Reject without origin work. |
| `ARTIFACT_ONLY` | Serve an existing artifact or fail without computing it. |
| `BOUNDED_COMPUTE` | Execute through configured global and per-policy budgets. |
| `MATERIALIZE` | Execute bounded work and persist the resulting artifact for reuse. |
| `CLIENT_COMPUTE` | Return a configured client-computation description instead of calling the origin. |

Routes may derive semantic identity from captured path values and selected query parameters. Equivalent operations share one in-flight execution.

A required fallback policy is `DENY`.

## Run it

Requires Java 21.

Build the CLI distribution:

```bash
./gradlew :bounded-origin-cli:installDist
```

Create `bounded-origin.yaml`:

```yaml
schema: 1

gateway:
  origin.host: 127.0.0.1
  origin.port: 9000
  temporary.directory: ./build/bounded-origin/tmp
  origin.max-active: 2
  origin.max-queued: 8
  origin.max-execution-duration: PT5S
  origin.max-result-bytes: 1048576

store:
  directory: ./build/bounded-origin/store
  max-bytes: 67108864
  max-artifact-bytes: 1048576

routes:
  - id: report
    version: 1
    precedence: 100
    match:
      method: GET
      path: /reports/{id}
      trust: UNTRUSTED
    strategy: BOUNDED_COMPUTE
    key:
      path: [id]
      query:
        include: []
        order-independent: true
    materializer-version: report-v1
    budget:
      max-active: 1
      max-queued: 4
      max-execution-duration: PT5S
      max-result-bytes: 1048576

fallback:
  id: default-deny
  version: 1
  precedence: -2147483648
  strategy: DENY
```

Validate configuration without starting the runtime:

```bash
./bounded-origin-cli/build/install/bounded-origin/bin/bounded-origin validate --config bounded-origin.yaml
```

Run:

```bash
./bounded-origin-cli/build/install/bounded-origin/bin/bounded-origin run --config bounded-origin.yaml
```

Defaults:

- gateway: `0.0.0.0:8080`
- admin: `127.0.0.1:8081`
- admin endpoints: `/health`, `/ready`, `/metrics`

Windows distributions include `bounded-origin.bat`.

## Operational properties

- semantic keys from configured path captures and query dimensions
- single-flight execution for equivalent operations
- global and per-policy active/queue/result/time budgets
- persistent filesystem artifact storage
- deterministic route precedence
- explicit deny fallback
- strict YAML structure and configuration bounds
- strict HTTP request-target and framing checks
- bounded request/response spooling
- readiness, health, metrics, and structured request logs
- immutable runtime configuration until restart
- graceful shutdown and deterministic resource cleanup

## Verification

The repository enforces:

- Java 21 with `-Xlint:all -Werror`
- minimum 91% line and branch coverage
- PIT mutation gates for core and CLI code
- SpotBugs and formatting checks
- dependency locking and checksum verification
- bytecode-derived public API snapshots
- reproducible JAR and distribution archives
- Linux and Windows CI
- packaged CLI process tests
- Docker smoke tests
- concurrency, lifecycle, parser, storage, and adversarial HTTP tests

Run the full verification suite:

```bash
./gradlew check
```

## Status

Bounded Origin is pre-release (`0.1.0-SNAPSHOT`).

An adversarial architecture audit is still in progress. Public benchmark claims are intentionally withheld until remote-work ownership across timeout, transport failure, and process restart has been fully hardened and re-verified.

## License

[AGPL-3.0](LICENSE)
