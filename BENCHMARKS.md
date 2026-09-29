# Measured control and mechanism costs

This campaign asks whether increasing untrusted demand can exceed the configured
computation bound under the supported origin contract, and what the generic
machinery costs. The origin is **synthetic**. These results do not measure Git/cgit,
identify bots, or estimate savings for the incident that motivated the project.

Measurements used clean merged main
[`08c8043fb2ba6b6dc4442ce74a1eccf31f84bc05`](https://github.com/aalsanie/bounded-origin/commit/08c8043fb2ba6b6dc4442ce74a1eccf31f84bc05),
tree `f6d4ee42e2257abc5ab609d010c2f88714e83a75`, after its
[complete CI run passed](https://github.com/aalsanie/bounded-origin/actions/runs/36397376357).
The campaign completed on 28 September 2026. Documentation and table generation
were added afterward; no runtime optimization was made from these measurements.

## Hypotheses established before measurement

| Hypothesis | Observation that would falsify it |
|---|---|
| Overlapping callers of one semantic operation share one producer. | More than one independently observed origin start while that controlled flight is held. Sequential `BOUNDED_COMPUTE` requests after completion are allowed to recompute. |
| Unique-key demand respects global/policy active and queue bounds. | Origin active work exceeds its corresponding capacity, or controlled queue occupancy exceeds its limit. Sampled gauges alone cannot establish exact queue maxima. |
| Completed immutable materialization suppresses equivalent recomputation, including restart. | Unexpected origin starts or incorrect response bytes in the warm/restarted phase. |
| Unselected query noise and supported aliases preserve configured identity. | Different producer inputs/keys or duplicate overlapping computation. Different selected values and missing/bare/empty parameters are controls that must remain distinct. |
| Denied/unmatched requests cause no origin work. | Any independent origin start for those gateway requests. |
| Deadline/transport uncertainty retains expensive-work ownership. | With capacity one, distinct-key arrivals produce more than one actual unfinished computation while prior work continues, including through restart. Availability loss is a cost, not a reason to release unknown work. |

Microbenchmarks are descriptive. They do not presume that configuration is free,
that a configured matcher must outperform a handwritten one, or that measured
timing establishes security. No machine-dependent performance threshold was added
to ordinary CI.

## Workloads and comparison definitions

### Independent controls

[system_invariants.py](bounded-origin-benchmarks/scripts/system_invariants.py) runs
31 packaged-runtime controls. Explicit barriers cover overlap, semantic noise and
selected-value distinctions, global/policy pressure with zero/nonzero queues,
materialized reuse/restart, denial, response/executor deadline uncertainty and
restart. Both computation strategies are exercised. An uncapped direct-origin
control establishes that the origin fixture itself can exceed gateway capacity.
Its journal records work that continues despite disconnect or transport reset.
Barrier-induced latency is excluded from the performance comparisons below.

These controls supplement deterministic unit, concurrency, protocol, filesystem
and packaged process regressions. A passing benchmark does not replace those
tests or prove arbitrary origins honor the completion contract.

### Free-running system experiment

[system_benchmark.py](bounded-origin-benchmarks/scripts/system_benchmark.py) runs
three separate processes: a bounded Python client, the actual packaged CLI where
applicable, and an independently instrumented Java synthetic origin. The origin
uses a deterministic CPU loop whose result contributes to its response. It finishes
computation/body generation before the complete response and has no semaphore at
the tested gateway capacity. Serialized start/finish journal events independently
record the actual work count and peak, rather than inferring CPU work from gateway
connections or dispatch counters.

The **direct baseline** sends the same operation sequence to that identical origin
without Bounded Origin or artifact reuse. Cost, payload, seed, CPU allocation and
client connection policy match the gateway comparison. `bounded` means
`BOUNDED_COMPUTE`; `materialize` means `MATERIALIZE`. There is no deliberately slow
alternative implementation or claimed comparison to an optimized HTTP cache.
The gateway's upstream pool is part of the measured product. All clients use
HTTP/1.1, one connection per request and no retries.

| Cell | Demand and keys | Global / policy active; queued |
|---|---|---|
| `same-1/4/16/64` | 256 requests for one operation, closed-loop at the named concurrency. | 1; 0 |
| `noise` | 256 equivalent requests at concurrency 64; encoded path alias and 33 unselected query parameters with changing values. | 1; 0 |
| `unique-0/8-100/1000` | 256 distinct scheduled offers at 100 or 1,000/second, client capacity 64; queue size in the cell name. | 2; 0 or 8 |
| `reuse` | 256 requests over 16 keys, concurrency 1; initial, warm and gateway-restart phases for materialization. | 2; 8 |
| `low-mix` | 64 requests at 5 offers/second, concurrency 1, mixed repeated/distinct keys. | 2; 8 |
| `failure` | 64 distinct requests, concurrency 16; origin returns eligible public HTTP 500. | 2; 8 |
| `slow` | 64 distinct requests, concurrency 16; ten times the normal CPU iterations. | 2; 8 |
| `large` | 64 distinct requests, concurrency 16; 1 MiB response body. | 2; 8 |
| `denied` | 64 distinct requests, concurrency 16; gateway denies the path. The direct origin still computes. | 2; 8 |

Normal origin cost is 10,000,000 iterations and a 4,096-byte body. The routes select
one `q` query dimension with order-independent values and named path captures;
unselected parameters do not reach the producer. The seed is 20260927 plus the
repetition index. Baseline/treatment order rotates between repetitions. Denial
intentionally performs different work; its CPU reduction is not equivalent-work
throughput improvement. Rejection under overload must be interpreted similarly.

Each cell has two preserved warmup trials and ten measured repetitions, with fresh
processes and store/ownership domains. Each process first receives 32 disjoint
code-warmup requests. `cold` therefore means the **workload keys are absent**, not
that the filesystem/JVM or whole artifact store is empty. Warm/restarted phases
reuse the explicit materialization state. Restarted gateways receive disjoint
code warmup again. The complete matrix has 564 phases including warmups, with
47 groups of ten measured phases.

Gateway configurations use public immutable representations, explicit
`RESPONSE_COMPLETE` ownership, active/queue limits from the table, one origin
connection per active slot, zero pending connection acquisitions, and 60-second
origin response/execution deadlines with a 70-second request timeout. These free-
running deadlines are separate from adverse deadline controls. Every actual YAML,
including store/spool/result limits, is in the evidence; defaults and cross-field
rules are described in [Configuration](CONFIGURATION.md).

At an offered rate, a full client records an unsent capacity drop rather than
silently queuing. Offered, attempted, completed HTTP, HTTP status, transport error
and dropped counts are different quantities. Scheduler delay is recorded. Thus
`1000` labels scheduled offers, not a claim that 1,000 requests/second reached the
gateway. Throughput means either completed HTTP responses/second or successful
HTTP 200 responses/second, explicitly named in the CSV.

### JVM mechanism experiment

[microbench.py](bounded-origin-benchmarks/scripts/microbench.py) uses pinned JMH
1.37, average time, one worker thread and the GC allocation profiler. Each cell has
ten fresh JVM forks, five one-second warmup iterations and five one-second measured
iterations. Raw per-fork/per-iteration data are retained. JMH is confined to the
benchmark module and its locked, verified dependencies.

| Suite | Measured operations and parameters |
|---|---|
| `configuration` | Bounded file/YAML load and decode; gateway field validation; semantic validation/compilation; policy compilation; route-template compilation; full load plus validation. Route counts 1, 16, 128, 256. |
| `routes` | Actual `PolicyEngine.evaluate`, fixed/capture/catch-all templates, first/middle/last/miss, route counts 16, 128, 256. |
| `overlap` | Overlapping patterns resolved by precedence, route counts 16, 128, 256. |
| `keys` | Projection plus canonicalization and already-projected canonicalization; 1, 8, 32, 128 query dimensions; selected, noise, duplicate, missing, bare, empty, encoded and reversed inputs; both query-order modes. |
| `programmatic` | Configured versus equivalent public-API fixed-path policies, first/last/miss at 1, 16, 128, 256 routes. |

There are 215 cells. Route counts were chosen against parser byte/node limits, not
observed speed: representative serialized configurations at 256 routes already
exercise a meaningful fraction of those limits. The nominal 1,024-route maximum
does not imply every full YAML of that size fits all parser limits. No scaling
claim is made beyond measured configurations.

File loading includes bounded read, UTF-8/YAML guards and model decode; it is not
pure YAML parser cost. Semantic validation includes policy compilation. Phase
measurements overlap and must not be summed. The JVM and filesystem cache are warm
during measurements; these are not whole-process cold-start times. Route evaluation
scans the policy table; position labels do not imply early-exit matching.

The programmatic comparison checks equivalent decisions, keys and producer inputs
for fixed normalized paths with identical policy/version/representation/body/host/
method/trust semantics. Its handwritten matcher does not implement the general
template language. The comparison quantifies that restricted abstraction cost,
not a universally interchangeable faster implementation.

## Environment and measurement methods

| Item | Recorded environment |
|---|---|
| Runtime | Debian OpenJDK 21.0.9+10-Debian-1, Java 21; Python 3.13.7. |
| OS | Kali Linux on WSL2, kernel 5.15.153.1; glibc 2.41. |
| Physical host | AMD Ryzen 9 5900HX, 8 cores / 16 logical processors; Windows 11 Home 10.0.26200; 33,735,802,880 bytes RAM. |
| Guest allocation | 14 logical processors / 7 reported cores; 16,079,628 KiB reported memory. |
| Affinity | Origin/JMH logical CPU 2, gateway CPU 4, client CPU 6; distinct guest-reported cores. |
| JVM limits | Origin heap 128 MiB, gateway 256 MiB, both `ActiveProcessorCount=2`; JMH heap 512 MiB and `ActiveProcessorCount=1`. |
| Filesystem | Native Linux ext4 (`/dev/sde`) for source, benchmark evidence, store, ownership and spool; the original Windows checkout was on 9p. Mount source/options and actual per-process locations are recorded. |
| Host conditions | Shared Windows/WSL machine, host Turbo power mode. No exclusive hardware reservation or host-noise isolation. |

Host hardware was captured during the run at 10:51:11 UTC and is labeled accordingly.
Guest metadata and load observations were captured by the runners. CPU affinity
does not reserve a core; fixed heaps do not bound total RSS. The guest did not expose
the queried cgroup-v2 root limit files; launch evidence records the actual v1/hybrid
mount/allocation information rather than inventing a quota. Timing is evidence for
this shared environment, not controlled exclusive-hardware performance.

Primary origin CPU uses before/after `ProcessHandle.totalCpuDuration`: the whole
origin process, including HTTP, journal instrumentation, JIT and GC. A separately
named `ThreadMXBean` metric measures compute/body-generation thread CPU. Gateway
CPU uses Linux `/proc/PID/stat` user/system ticks and includes sampling requests.
Reported nanosecond units do not imply nanosecond clock resolution. A tiny warm
phase quantized to zero does not establish zero CPU consumption.

Latency includes failures and uses nearest-rank per-trial percentiles. The tables
report distributions of independent trial percentiles; they are not a pooled
global p99. JMH summaries use independent fork means. Mean, median, sample standard
deviation and full ranges are retained in CSV; no outliers were removed and no
statistical significance is claimed. Queue/RSS samples occur approximately every
100 ms, so their observed peaks are lower bounds. Response body bytes are counted;
IP/TCP network bytes are unmeasured.

## Results

All controls completed, and independent origin counts respected each configured
bound in every measured gateway phase. All successful comparison bodies agreed.
No transport or response-validation error occurred in the system phases. HTTP
500/503 and unsent client drops remain in the data.

The tables below are generated from the raw archive by
[summarize_results.py](bounded-origin-benchmarks/scripts/summarize_results.py).
Complete distributions, including omitted table columns, are in
[system.csv](bounded-origin-benchmarks/results/2026-09-28/generated/system.csv) and
[micro.csv](bounded-origin-benchmarks/results/2026-09-28/generated/micro.csv).

<!-- generated-results:start -->
### System results

Every row summarizes ten measured repetitions. Counts are means per repetition; origin starts also show the full range. Peak is the largest independently observed origin count. Drops were offered but never sent by the finite client. HTTP statuses remain separate from transport errors.

| Cell | Mode / state | Attempted | 200 | 403 | 500 | 503 | Client drops | Origin starts, mean [min, max] | Origin peak |
|---|---|---:|---:|---:|---:|---:|---:|---:|---:|
| same-1 | direct / cold | 256.0 | 256.0 | 0.0 | 0.0 | 0.0 | 0.0 | 256.0 [256, 256] | 1 |
| same-1 | bounded / cold | 256.0 | 256.0 | 0.0 | 0.0 | 0.0 | 0.0 | 256.0 [256, 256] | 1 |
| same-1 | materialize / cold | 256.0 | 256.0 | 0.0 | 0.0 | 0.0 | 0.0 | 1.0 [1, 1] | 1 |
| same-4 | direct / cold | 256.0 | 256.0 | 0.0 | 0.0 | 0.0 | 0.0 | 256.0 [256, 256] | 4 |
| same-4 | bounded / cold | 256.0 | 256.0 | 0.0 | 0.0 | 0.0 | 0.0 | 64.0 [64, 64] | 1 |
| same-4 | materialize / cold | 256.0 | 256.0 | 0.0 | 0.0 | 0.0 | 0.0 | 1.0 [1, 1] | 1 |
| same-16 | direct / cold | 256.0 | 256.0 | 0.0 | 0.0 | 0.0 | 0.0 | 256.0 [256, 256] | 16 |
| same-16 | bounded / cold | 256.0 | 256.0 | 0.0 | 0.0 | 0.0 | 0.0 | 16.9 [16, 17] | 1 |
| same-16 | materialize / cold | 256.0 | 256.0 | 0.0 | 0.0 | 0.0 | 0.0 | 1.0 [1, 1] | 1 |
| same-64 | direct / cold | 256.0 | 256.0 | 0.0 | 0.0 | 0.0 | 0.0 | 256.0 [256, 256] | 45 |
| same-64 | bounded / cold | 256.0 | 256.0 | 0.0 | 0.0 | 0.0 | 0.0 | 10.0 [9, 13] | 1 |
| same-64 | materialize / cold | 256.0 | 256.0 | 0.0 | 0.0 | 0.0 | 0.0 | 1.0 [1, 1] | 1 |
| noise | direct / cold | 256.0 | 256.0 | 0.0 | 0.0 | 0.0 | 0.0 | 256.0 [256, 256] | 42 |
| noise | bounded / cold | 256.0 | 256.0 | 0.0 | 0.0 | 0.0 | 0.0 | 10.3 [7, 15] | 1 |
| noise | materialize / cold | 256.0 | 256.0 | 0.0 | 0.0 | 0.0 | 0.0 | 1.0 [1, 1] | 1 |
| unique-0-100 | direct / cold | 241.3 | 241.3 | 0.0 | 0.0 | 0.0 | 14.7 | 241.3 [230, 246] | 41 |
| unique-0-100 | bounded / cold | 256.0 | 69.2 | 0.0 | 0.0 | 186.8 | 0.0 | 69.2 [64, 74] | 2 |
| unique-0-100 | materialize / cold | 256.0 | 69.6 | 0.0 | 0.0 | 186.4 | 0.0 | 69.6 [64, 74] | 2 |
| unique-0-1000 | direct / cold | 78.9 | 78.9 | 0.0 | 0.0 | 0.0 | 177.1 | 78.9 [76, 81] | 18 |
| unique-0-1000 | bounded / cold | 83.9 | 7.8 | 0.0 | 0.0 | 76.1 | 172.1 | 7.8 [6, 10] | 2 |
| unique-0-1000 | materialize / cold | 80.3 | 7.5 | 0.0 | 0.0 | 72.8 | 175.7 | 7.5 [6, 10] | 2 |
| unique-8-100 | direct / cold | 241.8 | 241.8 | 0.0 | 0.0 | 0.0 | 14.2 | 241.8 [234, 250] | 41 |
| unique-8-100 | bounded / cold | 256.0 | 77.8 | 0.0 | 0.0 | 178.2 | 0.0 | 77.8 [73, 82] | 2 |
| unique-8-100 | materialize / cold | 256.0 | 74.2 | 0.0 | 0.0 | 181.8 | 0.0 | 74.2 [66, 85] | 2 |
| unique-8-1000 | direct / cold | 78.3 | 78.3 | 0.0 | 0.0 | 0.0 | 177.7 | 78.3 [76, 80] | 16 |
| unique-8-1000 | bounded / cold | 88.3 | 17.0 | 0.0 | 0.0 | 71.3 | 167.7 | 17.0 [15, 18] | 2 |
| unique-8-1000 | materialize / cold | 87.0 | 15.3 | 0.0 | 0.0 | 71.7 | 169.0 | 15.3 [14, 16] | 2 |
| reuse | direct / cold | 256.0 | 256.0 | 0.0 | 0.0 | 0.0 | 0.0 | 256.0 [256, 256] | 1 |
| reuse | bounded / cold | 256.0 | 256.0 | 0.0 | 0.0 | 0.0 | 0.0 | 256.0 [256, 256] | 1 |
| reuse | materialize / cold | 256.0 | 256.0 | 0.0 | 0.0 | 0.0 | 0.0 | 16.0 [16, 16] | 1 |
| reuse | materialize / warm | 256.0 | 256.0 | 0.0 | 0.0 | 0.0 | 0.0 | 0.0 [0, 0] | 0 |
| reuse | materialize / restart | 256.0 | 256.0 | 0.0 | 0.0 | 0.0 | 0.0 | 0.0 [0, 0] | 0 |
| low-mix | direct / cold | 64.0 | 64.0 | 0.0 | 0.0 | 0.0 | 0.0 | 64.0 [64, 64] | 1 |
| low-mix | bounded / cold | 64.0 | 64.0 | 0.0 | 0.0 | 0.0 | 0.0 | 64.0 [64, 64] | 1 |
| low-mix | materialize / cold | 64.0 | 64.0 | 0.0 | 0.0 | 0.0 | 0.0 | 19.0 [19, 19] | 1 |
| failure | direct / cold | 64.0 | 0.0 | 0.0 | 64.0 | 0.0 | 0.0 | 64.0 [64, 64] | 15 |
| failure | bounded / cold | 64.0 | 0.0 | 0.0 | 16.6 | 47.4 | 0.0 | 16.6 [16, 18] | 2 |
| failure | materialize / cold | 64.0 | 0.0 | 0.0 | 17.6 | 46.4 | 0.0 | 17.6 [16, 20] | 2 |
| slow | direct / cold | 64.0 | 64.0 | 0.0 | 0.0 | 0.0 | 0.0 | 64.0 [64, 64] | 16 |
| slow | bounded / cold | 64.0 | 11.3 | 0.0 | 0.0 | 52.7 | 0.0 | 11.3 [11, 12] | 2 |
| slow | materialize / cold | 64.0 | 11.6 | 0.0 | 0.0 | 52.4 | 0.0 | 11.6 [11, 12] | 2 |
| large | direct / cold | 64.0 | 64.0 | 0.0 | 0.0 | 0.0 | 0.0 | 64.0 [64, 64] | 16 |
| large | bounded / cold | 64.0 | 34.5 | 0.0 | 0.0 | 29.5 | 0.0 | 34.5 [28, 46] | 2 |
| large | materialize / cold | 64.0 | 20.6 | 0.0 | 0.0 | 43.4 | 0.0 | 20.6 [17, 28] | 2 |
| denied | direct / cold | 64.0 | 64.0 | 0.0 | 0.0 | 0.0 | 0.0 | 64.0 [64, 64] | 16 |
| denied | bounded / cold | 64.0 | 0.0 | 64.0 | 0.0 | 0.0 | 0.0 | 0.0 [0, 0] | 0 |
| denied | materialize / cold | 64.0 | 0.0 | 64.0 | 0.0 | 0.0 | 0.0 | 0.0 [0, 0] | 0 |

### CPU and latency

Origin CPU is whole-process CPU seconds per 1,000 attempted requests, reported as mean ± sample SD across repetitions. Latency columns are the median of per-trial percentiles in milliseconds; the p99 column retains their full range. Rejections are included. Per-status latency, CPU per successful response, throughput, queue, RSS and other counters are in the CSV.

| Cell | Mode / state | Origin CPU s / 1,000 attempted | p50 ms | p99 ms [min, max] |
|---|---|---:|---:|---:|
| same-1 | direct / cold | 12.8164 ± 0.0833 | 12.398 | 20.386 [18.771, 25.143] |
| same-1 | bounded / cold | 13.0039 ± 0.0468 | 69.032 | 81.510 [80.515, 90.678] |
| same-1 | materialize / cold | 0.0586 ± 0.0206 | 2.134 | 15.222 [14.725, 18.270] |
| same-4 | direct / cold | 12.7773 ± 0.0468 | 48.684 | 119.753 [93.844, 140.436] |
| same-4 | bounded / cold | 3.4180 ± 0.0560 | 70.798 | 90.551 [84.866, 156.755] |
| same-4 | materialize / cold | 0.0625 ± 0.0202 | 12.864 | 36.073 [33.834, 37.899] |
| same-16 | direct / cold | 12.7734 ± 0.0864 | 198.401 | 461.905 [378.363, 472.088] |
| same-16 | bounded / cold | 0.8711 ± 0.0264 | 81.287 | 125.833 [112.770, 164.256] |
| same-16 | materialize / cold | 0.0508 ± 0.0189 | 48.479 | 100.865 [89.506, 131.086] |
| same-64 | direct / cold | 12.7148 ± 0.0496 | 797.530 | 1283.476 [1204.219, 1545.132] |
| same-64 | bounded / cold | 0.5156 ± 0.0777 | 192.522 | 356.067 [291.077, 407.995] |
| same-64 | materialize / cold | 0.0625 ± 0.0202 | 176.336 | 389.069 [333.877, 438.759] |
| noise | direct / cold | 13.0703 ± 0.2052 | 801.563 | 1332.553 [1258.260, 1543.477] |
| noise | bounded / cold | 0.5352 ± 0.1341 | 191.328 | 333.271 [297.894, 462.171] |
| noise | materialize / cold | 0.0586 ± 0.0206 | 182.838 | 371.335 [274.894, 684.542] |
| unique-0-100 | direct / cold | 12.6119 ± 0.0897 | 491.244 | 1031.430 [896.704, 1201.710] |
| unique-0-100 | bounded / cold | 3.5820 ± 0.2156 | 2.733 | 82.931 [76.936, 93.709] |
| unique-0-100 | materialize / cold | 3.6445 ± 0.1842 | 2.958 | 97.520 [82.845, 112.667] |
| unique-0-1000 | direct / cold | 13.0165 ± 0.1167 | 595.235 | 945.238 [898.593, 990.273] |
| unique-0-1000 | bounded / cold | 1.2557 ± 0.2181 | 238.756 | 322.041 [295.202, 357.945] |
| unique-0-1000 | materialize / cold | 1.2750 ± 0.3507 | 249.715 | 342.477 [315.430, 383.106] |
| unique-8-100 | direct / cold | 12.5974 ± 0.0526 | 483.417 | 993.064 [888.661, 1049.354] |
| unique-8-100 | bounded / cold | 4.0391 ± 0.2019 | 2.908 | 401.086 [354.182, 424.434] |
| unique-8-100 | materialize / cold | 3.8906 ± 0.2901 | 3.507 | 511.504 [429.877, 517.187] |
| unique-8-1000 | direct / cold | 13.1157 ± 0.1958 | 575.249 | 945.241 [905.276, 987.703] |
| unique-8-1000 | bounded / cold | 2.6355 ± 0.3328 | 224.557 | 576.693 [491.182, 645.029] |
| unique-8-1000 | materialize / cold | 2.4488 ± 0.3192 | 218.404 | 601.182 [523.563, 704.501] |
| reuse | direct / cold | 12.6172 ± 0.0664 | 12.248 | 20.417 [19.072, 26.027] |
| reuse | bounded / cold | 12.9336 ± 0.0388 | 70.240 | 90.286 [84.280, 90.880] |
| reuse | materialize / cold | 0.8203 ± 0.0000 | 1.887 | 97.749 [89.297, 107.159] |
| reuse | materialize / warm | 0.0156 ± 0.0202 | 1.379 | 14.044 [12.130, 16.825] |
| reuse | materialize / restart | 0.0039 ± 0.0124 | 2.145 | 15.991 [11.814, 17.908] |
| low-mix | direct / cold | 13.6094 ± 0.2010 | 13.152 | 24.890 [24.495, 27.438] |
| low-mix | bounded / cold | 13.4062 ± 0.2834 | 27.670 | 39.641 [38.584, 44.878] |
| low-mix | materialize / cold | 4.1250 ± 0.0807 | 3.645 | 57.560 [50.941, 73.396] |
| failure | direct / cold | 13.1094 ± 0.1368 | 195.065 | 391.259 [367.456, 461.298] |
| failure | bounded / cold | 3.4844 ± 0.1956 | 41.947 | 431.131 [405.720, 501.670] |
| failure | materialize / cold | 3.6562 ± 0.2965 | 40.450 | 443.373 [359.325, 487.287] |
| slow | direct / cold | 114.2344 ± 0.3642 | 1803.494 | 2291.026 [2231.323, 2333.203] |
| slow | bounded / cold | 20.1406 ± 0.8413 | 36.799 | 1391.111 [1272.271, 1459.904] |
| slow | materialize / cold | 20.7500 ± 0.8833 | 37.335 | 1461.348 [1386.991, 1487.004] |
| large | direct / cold | 16.7656 ± 0.2951 | 236.520 | 623.762 [520.924, 704.255] |
| large | bounded / cold | 8.7500 ± 1.4565 | 168.198 | 451.583 [410.082, 656.891] |
| large | materialize / cold | 5.2031 ± 0.9993 | 57.064 | 539.857 [492.552, 623.038] |
| denied | direct / cold | 13.3906 ± 0.5106 | 207.030 | 386.853 [353.656, 463.050] |
| denied | bounded / cold | 0.0312 ± 0.0659 | 51.773 | 103.056 [72.517, 134.553] |
| denied | materialize / cold | 0.0156 ± 0.0494 | 47.464 | 98.340 [68.938, 126.260] |

### Mechanism examples

Time and allocation are means of ten fork means; ± is sample SD across those forks. Selected rows cover full configuration loading, last-match routing, order-independent query projection and the fixed-path comparison. The CSV and raw JSON retain every method and parameter combination, including misses, overlaps, ordered queries and all alias variants.

| Method | Parameters | µs / operation | Bytes / operation |
|---|---|---:|---:|
| completeLoadAndCompile | routeCount=1 | 76.925 ± 3.822 | 137993.0 |
| completeLoadAndCompile | routeCount=16 | 626.597 ± 33.263 | 1159029.9 |
| completeLoadAndCompile | routeCount=128 | 4084.485 ± 81.943 | 8526611.9 |
| completeLoadAndCompile | routeCount=256 | 8791.821 ± 201.217 | 16990640.8 |
| evaluate | position=LAST, routeCount=16, shape=FIXED | 4.278 ± 0.089 | 13651.2 |
| evaluate | position=LAST, routeCount=16, shape=CAPTURE | 4.958 ± 0.212 | 16008.0 |
| evaluate | position=LAST, routeCount=16, shape=CATCH_ALL | 4.895 ± 0.127 | 15884.8 |
| evaluate | position=LAST, routeCount=128, shape=FIXED | 13.994 ± 0.601 | 39736.1 |
| evaluate | position=LAST, routeCount=128, shape=CAPTURE | 16.334 ± 0.682 | 47849.7 |
| evaluate | position=LAST, routeCount=128, shape=CATCH_ALL | 17.038 ± 0.341 | 54347.3 |
| evaluate | position=LAST, routeCount=256, shape=FIXED | 24.346 ± 0.702 | 69460.9 |
| evaluate | position=LAST, routeCount=256, shape=CAPTURE | 29.741 ± 1.281 | 84737.0 |
| evaluate | position=LAST, routeCount=256, shape=CATCH_ALL | 31.043 ± 1.099 | 98419.4 |
| projectAndCanonicalize | ordered=false, queryCase=SELECTED, queryDimensions=1 | 2.833 ± 0.062 | 10452.8 |
| projectAndCanonicalize | ordered=false, queryCase=SELECTED, queryDimensions=8 | 3.722 ± 0.033 | 13947.2 |
| projectAndCanonicalize | ordered=false, queryCase=SELECTED, queryDimensions=32 | 6.933 ± 0.388 | 25197.6 |
| projectAndCanonicalize | ordered=false, queryCase=SELECTED, queryDimensions=128 | 19.520 ± 0.209 | 74433.7 |
| projectAndCanonicalize | ordered=false, queryCase=NOISE, queryDimensions=1 | 4.381 ± 0.043 | 16991.2 |
| projectAndCanonicalize | ordered=false, queryCase=NOISE, queryDimensions=8 | 5.601 ± 0.406 | 20448.0 |
| projectAndCanonicalize | ordered=false, queryCase=NOISE, queryDimensions=32 | 8.667 ± 0.126 | 31660.1 |
| projectAndCanonicalize | ordered=false, queryCase=NOISE, queryDimensions=128 | 21.298 ± 0.196 | 80377.7 |
| projectAndCanonicalize | ordered=false, queryCase=DUPLICATES, queryDimensions=1 | 3.002 ± 0.027 | 10972.0 |
| projectAndCanonicalize | ordered=false, queryCase=DUPLICATES, queryDimensions=8 | 4.854 ± 0.040 | 17626.4 |
| projectAndCanonicalize | ordered=false, queryCase=DUPLICATES, queryDimensions=32 | 11.147 ± 0.121 | 41916.9 |
| projectAndCanonicalize | ordered=false, queryCase=DUPLICATES, queryDimensions=128 | 37.563 ± 0.386 | 138984.2 |
| configured | position=LAST, routeCount=1 | 2.671 ± 0.060 | 9720.8 |
| configured | position=LAST, routeCount=16 | 4.124 ± 0.154 | 13106.4 |
| configured | position=LAST, routeCount=128 | 13.737 ± 0.404 | 39300.1 |
| configured | position=LAST, routeCount=256 | 23.604 ± 0.944 | 69068.9 |
| programmatic | position=LAST, routeCount=1 | 2.417 ± 0.037 | 9224.0 |
| programmatic | position=LAST, routeCount=16 | 2.701 ± 0.029 | 9229.6 |
| programmatic | position=LAST, routeCount=128 | 4.824 ± 0.081 | 9320.0 |
| programmatic | position=LAST, routeCount=256 | 5.991 ± 0.340 | 9326.4 |

<!-- generated-results:end -->

## Interpretation and limits

The mechanism controlled admitted work in this experiment. Increasing equivalent
concurrency reduced origin starts through shared flights; materialization reused
completed output and eliminated workload recomputation after gateway restart.
Distinct-key pressure produced explicit overload responses while actual origin
work remained bounded. Denied gateway traffic caused no origin starts. The adverse
controls retained unresolved capacity, including across restart, while the origin
continued real work. That outcome deliberately trades availability for safety.

Single-flight is not permanent deduplication: the free-running bounded rows contain
successive executions after earlier flights complete. Lower origin CPU under
overload partly reflects work rejected or never transmitted by the finite client.
It cannot be described as serving the same work more efficiently. Warm artifact
reuse is a different declared state from cold production of the artifact.

Costs are material. Sequential bounded requests added latency against the direct
origin in this environment. Route classification and allocation grew with table
size; selected-query projection and duplicate values also have measurable costs.
The restricted fixed-path programmatic comparison was cheaper than configured
matching. The measured end-to-end latency has not been decomposed into storage
durability, transport, pool, scheduler and policy costs; attributing every added
millisecond to one mechanism would exceed the evidence.

This is a finite synthetic experiment on one shared host, not a long-duration soak,
an exhaustive capacity/fairness proof, a cold-disk startup study or a real scraper
workload. Request count, latency, origin executions, origin CPU and rejection are
separate outcomes. The implementation/test evidence and explicit deployment
contracts establish the claimed ownership boundary; a benchmark cannot prove a
different origin tells the truth about completion or immutable public output.

The future independent Git consumer must measure actual renderer and delegated
process lifetime/CPU, including disconnect and cancellation, realistic semantic
equivalence and distinct-key pressure. This campaign supplies generic mechanism
evidence only. No Git proof repository or performance result is implied.

## Reproduce or inspect

The [evidence ZIP](bounded-origin-benchmarks/results/2026-09-28/evidence.zip) retains
all measurement/warmup rows, origin journals, client outcomes, process samples,
logs, configurations, benchmark fixtures, harness sources and completion manifests.
It also contains environment, source/tree, executable hashes, every committed
`gradle.lockfile` SHA-256, and filesystem/mount provenance. Check its
[SHA256SUMS](bounded-origin-benchmarks/results/2026-09-28/SHA256SUMS).

Its `evidence-manifest.json` hashes every included file and lists omitted files
with hashes and reasons. Only rebuildable distribution files and runtime
store/spool contents were omitted from this transport archive. Original completion
manifests remain unchanged; omission is not removal of a measurement or outlier.
`launch/commands.json` records the exact campaign commands. The archive is not
needed for normal builds, tests or new experiments.

Regenerate tables and verify archive membership/hashes with Python 3.11 or later
from this documentation revision, using a new output directory:

```sh
python bounded-origin-benchmarks/scripts/summarize_results.py \
  --evidence bounded-origin-benchmarks/results/2026-09-28/evidence.zip \
  --output build/reproduced-results
```

This produces `system.csv`, `micro.csv`, `tables.md` and `provenance.json`. The
`tables.md` content is the marked generated section above. Provenance identifies
the source SHA, raw input hashes and summarizer hash. CSVs can be compared directly
with the committed files. Summaries retain every observed cell; table selection
for mechanism examples is explicit in the script, not chosen by fastest result.

For new measurements, use JDK 21, Python 3.11+ and the repository's Gradle wrapper
on native Linux storage. The publication runners require clean merged `main`
equal to `origin/main`, a verified green CI URL and valid explicit CPU affinities.
`--ci-run` records the operator's verification; it does not itself authenticate CI
success. Build/test the current source first. Choose distinct available cores and
fresh evidence directories outside the source tree; adjust the example CPU IDs
for the recorded topology of that machine.

```sh
python3 bounded-origin-benchmarks/scripts/system_invariants.py --output /path/to/new-controls
python3 bounded-origin-benchmarks/scripts/system_benchmark.py \
  --output /path/to/new-system --ci-run YOUR_VERIFIED_MAIN_CI_URL \
  --origin-cpu 2 --gateway-cpu 4 --client-cpu 6
python3 bounded-origin-benchmarks/scripts/microbench.py \
  --suite configuration --output /path/to/new-configuration \
  --ci-run YOUR_VERIFIED_MAIN_CI_URL --cpu 2
```

Run the last command separately with suites `routes`, `overlap`, `keys` and
`programmatic`, each with its own new directory. Do not run suites concurrently.
Runners build the packaged artifacts, verify dependency locks, capture metadata
and preserve failed runs. The full campaign takes hours. `--smoke` is available
for development fixture validation and is labeled non-publication evidence.
Do not weaken clean-main checks to rerun an old revision; inspect/re-summarize the
retained historical evidence, or publish a separately identified new campaign.

Run `python -m unittest discover -s bounded-origin-benchmarks/scripts -p 'test_*.py'`
to check evidence tooling without a measurement campaign. Correctness gates verify
fixtures and invariant counters; no absolute latency or throughput assertion runs
in ordinary CI.
