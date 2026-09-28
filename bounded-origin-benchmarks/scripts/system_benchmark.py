"""Free-running synthetic experiments. Run controls separately; publish only clean green main."""

import argparse
import contextlib
import copy
import csv
import json
import os
from pathlib import Path
import random
import shutil
import statistics
import sys
import threading
import time

from microbench import REPOSITORY, digest, git, write_json
from system_invariants import Episode, prepare
from system_provenance import verify_dependencies
from system_support import (await_value, evidence_hashes, journal_summary, load, process_sample,
                            require, summarize_requests, write_rows)


def cells(smoke):
    result = []
    count = 8 if smoke else 256
    for concurrency in ([4] if smoke else [1, 4, 16, 64]):
        result.append({"name": f"same-{concurrency}", "workload": "same", "concurrency": concurrency, "count": count, "active": 1, "queued": 0})
    result.append({"name": "noise", "workload": "noise", "concurrency": 64, "count": count, "active": 1, "queued": 0})
    for queued in [0, 8]:
        for rate in ([1000] if smoke else [100, 1000]):
            result.append({"name": f"unique-{queued}-{rate}", "workload": "unique", "concurrency": 64,
                           "count": count, "active": 2, "queued": queued, "rate": rate})
    for workload in ["reuse", "low-mix", "failure", "slow", "large", "denied"]:
        result.append({"name": workload, "workload": workload, "concurrency": 1 if workload in ("reuse", "low-mix") else 16,
                       "count": (8 if smoke else 64) if workload != "reuse" else count, "active": 2, "queued": 8,
                       "rate": (100 if smoke else 5) if workload == "low-mix" else None})
    return result


def targets(cell, seed):
    workload = cell["workload"]
    result = []
    for index in range(cell["count"]):
        if workload == "same":
            target = "/work/one?q=a"
        elif workload == "noise":
            target = f"/work/%6Fne?q=a&noise={seed}-{index}" + "".join(f"&unused{n}={index * 32 + n}" for n in range(32))
        elif workload == "reuse":
            target = f"/work/key{index % 16}?q=a"
        elif workload == "low-mix":
            target = f"/work/key{index if index % 5 == 0 else index % 8}?q=a"
        else:
            prefix = "fail" if workload == "failure" else "denied" if workload == "denied" else "work"
            target = f"/{prefix}/key{index}?q=a"
        result.append(target)
    random.Random(seed).shuffle(result)
    return result


class Samples:
    def __init__(self, episode, path):
        self.episode = episode
        self.path = path
        self.stop = threading.Event()
        self.rows = []
        self.failure = None
        self.thread = threading.Thread(target=self.collect, name="experiment-sampler", daemon=True)

    def collect(self):
        try:
            while not self.stop.is_set():
                gateway = self.episode.gateway
                self.rows.append({"monotonic_ns": time.perf_counter_ns(),
                                  "metrics": gateway.metrics() if gateway is not None else None,
                                  "gateway_process": process_sample(gateway.child.process.pid) if gateway is not None else None,
                                  "origin_process": process_sample(self.episode.origin.child.process.pid)})
                self.stop.wait(.1)
        except BaseException as error:
            self.failure = error

    def __enter__(self):
        self.thread.start()
        return self

    def __exit__(self, kind, value, traceback):
        self.stop.set()
        self.thread.join(timeout=5)
        write_rows(self.path, self.rows)
        require(not self.thread.is_alive(), "resource sampler did not stop")
        if self.failure is not None:
            raise RuntimeError("resource sampler failed; partial samples retained") from self.failure


def phase(episode, cell, request_targets, name):
    directory = episode.directory / name
    directory.mkdir()
    before = episode.origin.stats()
    require(before["active"] == 0, "previous origin work overlaps a new measurement phase")
    gateway = episode.gateway
    metrics_before = gateway.metrics() if gateway is not None else {}
    process_before = process_sample(gateway.child.process.pid) if gateway is not None else None
    write_json(directory / "before.json", {"origin": before, "metrics": metrics_before, "gateway_process": process_before})
    with Samples(episode, directory / "samples.jsonl") as samples:
        rows, timing = load(episode.listen, request_targets, cell["concurrency"], episode.args.body_bytes, cell.get("rate"))
    write_rows(directory / "requests.jsonl", rows)
    after = await_value(episode.origin.stats, lambda sample: sample["active"] == 0, "measured origin completion", timeout=90)
    metrics_after = gateway.metrics() if gateway is not None else {}
    process_after = process_sample(gateway.child.process.pid) if gateway is not None else None
    write_json(directory / "after.json", {"origin": after, "metrics": metrics_after, "gateway_process": process_after})
    ledger = journal_summary(episode.directory / "origin.jsonl", after_id=before["starts"])
    for field in ["starts", "completed", "work_cpu_ns"]:
        require(ledger[field] == after[field] - before[field], f"origin journal and snapshot differ: {field}")
    requests = summarize_requests(rows)
    require(requests["response_validation_errors"] == 0, "wrong response representation; raw rows retained")
    if gateway is not None:
        require(ledger["peak"] <= cell["active"], "actual origin work exceeded configured bound")
    if cell["workload"] == "denied" and gateway is not None:
        require(ledger["starts"] == 0, "denied work reached the origin")
    if name in ("warm", "restart"):
        require(ledger["starts"] == 0, "fully materialized operations recomputed")
        require(requests["statuses"] == {"200": len(rows)}, "materialized reuse did not return every body")
    successes = requests["statuses"].get("200", 0)
    process_cpu = after["process_cpu_ns"] - before["process_cpu_ns"] if min(before["process_cpu_ns"], after["process_cpu_ns"]) >= 0 else None
    require(process_cpu is None or process_cpu >= 0, "origin process CPU counter moved backwards")
    metric_delta = {key: value - metrics_before[key] for key, value in metrics_after.items() if key.endswith("_total")}
    summary = {"phase": name, "origin": ledger, "requests": requests, "timing": timing, "metric_delta": metric_delta,
               **cpu_rates(ledger["work_cpu_ns"], process_cpu, requests["attempted"], successes),
               "origin_work_amplification": ledger["starts"] / requests["unique_operations_attempted"] if requests["unique_operations_attempted"] else None,
               "completed_http_per_second": requests["completed_http"] * 1e9 / timing["elapsed_ns"],
               "successful_200_per_second": successes * 1e9 / timing["elapsed_ns"],
               "gateway_process_cpu_ns": process_after["cpu_ns"] - process_before["cpu_ns"] if process_before is not None else None,
               "origin_process_cpu_ns": process_cpu,
               "gateway_rss_sampled_peak_bytes": max((row["gateway_process"]["rss_bytes"] for row in samples.rows if row["gateway_process"] is not None), default=None),
               "gateway_queue_sampled_peak": max((row["metrics"]["bounded_origin_origin_queue_depth"] for row in samples.rows if row["metrics"] is not None), default=None),
               "network_bytes": None}
    write_json(directory / "summary.json", summary)
    return summary


def cpu_rates(compute_ns, process_ns, attempted, successful):
    def rate(nanos, requests):
        return nanos / 1e6 / requests if nanos is not None and requests else None
    return {"origin_cpu_seconds_per_1000_attempted": rate(process_ns, attempted),
            "origin_cpu_seconds_per_1000_successful": rate(process_ns, successful),
            "origin_compute_cpu_seconds_per_1000_attempted": rate(compute_ns, attempted)}


def code_warmup(episode, name, count):
    rows, timing = load(episode.listen, [f"/warmup/code{index}" for index in range(count)], 1, episode.args.body_bytes)
    write_rows(episode.directory / f"{name}-code-warmup.jsonl", rows)
    summary = summarize_requests(rows)
    require(summary["statuses"] == {"200": count} and summary["request_errors"] == 0, "code warmup failed")
    episode.await_origin("active", 0)
    if episode.gateway is not None:
        episode.await_metric("origin_in_flight", 0)


def run_cell(args, cell, repetition, mode, measured):
    actual = copy.copy(args)
    actual.iterations = args.iterations * (10 if cell["workload"] == "slow" else 1)
    actual.body_bytes = 1_048_576 if cell["workload"] == "large" else args.body_bytes
    label = f"{cell['name']}-{repetition:02d}-{mode}"
    direct = mode == "direct"
    strategy = "MATERIALIZE" if mode == "materialize" else "BOUNDED_COMPUTE"
    request_targets = targets(cell, args.seed + repetition)
    with contextlib.closing(Episode(actual, label, strategy=strategy, active=cell["active"], queued=cell["queued"], direct=direct, held=False)) as episode:
        write_json(episode.directory / "trial.json", {"kind": args.kind, "measured": measured, "repetition": repetition,
                   "mode": mode, "cell": cell, "seed": args.seed + repetition, "targets": request_targets})
        code_warmup(episode, "initial", 2 if args.smoke else 32)
        summaries = [phase(episode, cell, request_targets, "cold")]
        if mode == "materialize" and cell["workload"] == "reuse":
            summaries.append(phase(episode, cell, request_targets, "warm"))
            episode.restart()
            code_warmup(episode, "restart", 2 if args.smoke else 32)
            summaries.append(phase(episode, cell, request_targets, "restart"))
    write_json(episode.directory / "completed.json", {"hashes": evidence_hashes(episode.directory)})
    print(f"Completed {args.kind} {label}", flush=True)
    return [{"cell": cell["name"], "mode": mode, "measured": measured, "repetition": repetition, **summary} for summary in summaries]


def aggregate(rows):
    grouped = {}
    for row in rows:
        if row["measured"]:
            grouped.setdefault((row["cell"], row["mode"], row["phase"]), []).append(row)
    result = []
    for key, group in sorted(grouped.items()):
        for metric in ["origin_cpu_seconds_per_1000_attempted", "origin_cpu_seconds_per_1000_successful", "origin_compute_cpu_seconds_per_1000_attempted",
                       "origin_work_amplification", "completed_http_per_second", "gateway_process_cpu_ns",
                       "gateway_rss_sampled_peak_bytes", "gateway_queue_sampled_peak"]:
            values = [row[metric] for row in group if row[metric] is not None]
            result.append({"cell": key[0], "mode": key[1], "phase": key[2], "metric": metric,
                           "repetitions": len(group), "available_repetitions": len(values),
                           "mean": statistics.mean(values) if values else None,
                           "median": statistics.median(values) if values else None,
                           "stdev": statistics.stdev(values) if len(values) > 1 else None,
                           "min": min(values) if values else None, "max": max(values) if values else None})
    return result


def validate_publication(parser, args):
    if args.smoke:
        return
    if git("status", "--porcelain") or git("branch", "--show-current") != "main" or git("rev-parse", "HEAD") != git("rev-parse", "origin/main"):
        parser.error("publication requires clean main equal to origin/main")
    cpus = [args.origin_cpu, args.gateway_cpu, args.client_cpu]
    if sys.platform != "linux" or not args.ci_run or None in cpus or len(set(cpus)) != 3:
        parser.error("publication requires Linux, verified --ci-run and three distinct process CPU allocations")
    if not set(cpus) <= os.sched_getaffinity(0):
        parser.error("requested CPU allocation is unavailable")


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--java", default=shutil.which("java"))
    parser.add_argument("--smoke", action="store_true")
    parser.add_argument("--suite", choices=["all", "same", "noise", "unique", "reuse", "low-mix", "failure", "slow", "large", "denied"], default="all")
    parser.add_argument("--iterations", type=int, default=10_000_000)
    parser.add_argument("--body-bytes", type=int, default=4096)
    parser.add_argument("--seed", type=int, default=20260927)
    for role in ["origin", "gateway", "client"]:
        parser.add_argument(f"--{role}-cpu", type=int)
    parser.add_argument("--ci-run")
    args = parser.parse_args(argv)
    if not args.java or not 1 <= args.iterations <= 100_000_000 or not 128 <= args.body_bytes <= 16_777_216:
        parser.error("valid Java21, CPU iterations and body bytes are required")
    validate_publication(parser, args)
    args.kind = "system-smoke" if args.smoke else "system-publication"
    metadata = prepare(args)
    if args.client_cpu is not None:
        os.sched_setaffinity(0, {args.client_cpu})
    selected = [cell for cell in cells(args.smoke) if args.suite in ("all", cell["workload"])]
    write_json(args.output / "protocol.json", {"kind": args.kind, "cells": selected, "warmup_runs": 0 if args.smoke else 2,
               "measured_repetitions": 1 if args.smoke else 10, "code_warmup_requests": 2 if args.smoke else 32,
               "ci_run_declared_verified": args.ci_run, "seed": args.seed,
               "origin_cpu_method": "ProcessHandle.totalCpuDuration bookends: whole origin process, including HTTP, journaling, JIT and GC",
               "origin_compute_cpu_method": "ThreadMXBean platform-thread CPU for deterministic compute plus body generation only",
               "cpu_clock_limitations": "reported nanosecond units do not imply nanosecond resolution; short Windows controls may quantize to zero",
               "gateway_cpu_method": "Linux /proc/PID/stat user+system ticks; unavailable on Windows",
               "rss_method": "Linux /proc/PID/stat, sampled at 100ms; peak is a lower bound",
               "network_bytes": "unmeasured; response body bytes are separately counted",
               "allocation_limitations": ["affinity is not exclusive host reservation", "fixed heap is not an RSS limit", "SMT/core topology is recorded, not inferred independent"],
               "baseline": "same independent synthetic origin, CPU work, body, sequence and client; no gateway or artifact reuse",
               "client": "HTTP/1.1, one connection per request, no retries, bounded concurrency; same policy for direct and gateway",
               "denied_comparison": "denial intentionally performs different work; not a throughput improvement claim"})
    all_rows = []
    try:
        for cell in selected:
            for repetition in range(1 if args.smoke else 12):
                modes = ["direct", "bounded", "materialize"]
                modes = modes[repetition % 3:] + modes[:repetition % 3]
                pairs = []
                for mode in modes:
                    rows = run_cell(args, cell, repetition, mode, args.smoke or repetition >= 2)
                    pairs.extend(rows)
                    all_rows.extend(rows)
                hashes = {}
                for row in pairs:
                    for identity, body in row["requests"]["successful_body_sha256"].items():
                        require(hashes.setdefault(identity, body) == body, "baseline/treatment representations differ")
        require(git("rev-parse", "HEAD") == metadata["head"] and git("status", "--porcelain") == metadata["status"],
                "source state changed during campaign")
        verify_dependencies(metadata["dependencies"])
        for name, expected in metadata["harness"].items():
            require(digest(Path(__file__).parent / name) == expected, "harness changed during campaign")
        for name, expected in metadata["source_hashes"].items():
            require(digest(REPOSITORY / name) == expected, "benchmark source changed during campaign")
        require(evidence_hashes(args.output / "_bin") == json.loads((args.output / "installed-hashes.json").read_text(encoding="utf-8")),
                "captured executable artifacts changed during campaign")
        write_json(args.output / "trials.json", all_rows)
        summary = aggregate(all_rows)
        with (args.output / "summary.csv").open("x", newline="", encoding="utf-8") as stream:
            writer = csv.DictWriter(stream, fieldnames=list(summary[0]))
            writer.writeheader()
            writer.writerows(summary)
        write_json(args.output / "completed.json", {"kind": args.kind, "trials": len(all_rows), "hashes": evidence_hashes(args.output)})
    except BaseException as error:
        write_json(args.output / "failed.json", {"error": f"{type(error).__name__}: {error}"})
        raise


if __name__ == "__main__":
    main()
