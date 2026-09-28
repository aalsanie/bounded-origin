"""Explicit packaged-runtime controls; barrier timings are not performance data."""

import argparse
from concurrent.futures import ThreadPoolExecutor
import contextlib
import json
import os
from pathlib import Path
import shutil
import socket
import subprocess
import sys
import time

from microbench import REPOSITORY, digest, environment, git, write_json
from system_support import (Gateway, Origin, await_value, configuration, control, evidence_hashes,
                            free_ports, journal_summary, request, require, runtime_environment, summarize_requests, write_rows)


class Episode:
    def __init__(self, args, name, strategy="BOUNDED_COMPUTE", *, active=1, queued=0,
                 policy_active=None, policy_queued=None, failure=None, direct=False, held=True):
        self.directory = args.output / name
        self.directory.mkdir()
        self.gateway = None
        self.origin = None
        self.futures = []
        self.observations = []
        self.sequence = 0
        self.args = args
        self.listen, self.admin = free_ports()
        self.config = self.directory / "gateway.yaml"
        write_json(self.directory / "parameters.json", {"strategy": strategy, "global_active": active,
                   "global_queued": queued, "policy_active": policy_active, "policy_queued": policy_queued,
                   "failure": failure, "direct_origin": direct, "held": held,
                   "cpu_iterations": args.iterations, "body_bytes": args.body_bytes})
        try:
            self.origin = Origin(args.java, args.libraries, self.directory, args.iterations, args.body_bytes, held,
                                 getattr(args, "origin_cpu", None))
            with self.config.open("x", encoding="utf-8", newline="\n") as stream:
                stream.write(configuration(self.directory, self.listen, self.admin, self.origin.port, strategy,
                                           active, queued, policy_active, policy_queued, failure, args.body_bytes))
            if direct:
                self.listen = self.origin.port
            else:
                command = (["cmd.exe", "/d", "/c"] if os.name == "nt" else []) + [str(args.launcher), "validate", "--config", str(self.config)]
                with (self.directory / "validate.log").open("xb") as log:
                    env = runtime_environment(args.java)
                    validation = subprocess.run(command, stdout=log, stderr=subprocess.STDOUT, env=env, check=False)
                require(validation.returncode == 0, "packaged CLI rejected experiment configuration")
                self.gateway = Gateway(args.launcher, self.config, self.directory, self.admin,
                                       java=args.java, cpu=getattr(args, "gateway_cpu", None))
        except BaseException:
            self.close()
            raise

    def origin_stats(self):
        value = self.origin.stats()
        self.observations.append({"monotonic_ns": time.perf_counter_ns(), "origin": value})
        return value

    def metrics(self):
        value = self.gateway.metrics()
        self.observations.append({"monotonic_ns": time.perf_counter_ns(), "gateway": value})
        return value

    def await_origin(self, field, value):
        return await_value(self.origin_stats, lambda sample: sample[field] == value, f"origin {field}={value}")

    def await_metric(self, name, value):
        key = "bounded_origin_" + name
        return await_value(self.metrics, lambda sample: sample[key] == value, f"{key}={value}")

    def submit(self, pool, target):
        future = pool.submit(request, self.listen, target, ordinal=len(self.futures), expected_bytes=self.args.body_bytes)
        self.futures.append(future)
        return future

    def success(self, future, status=200):
        row = future.result(timeout=85)
        require(row["error"] is None and row["status"] == status, f"unexpected request outcome: {row}")
        return row

    def restart(self, crash=False):
        self.metrics()
        self.gateway.close(crash)
        self.gateway = None
        self.sequence += 1
        self.gateway = Gateway(self.args.launcher, self.config, self.directory, self.admin, self.sequence,
                               java=self.args.java, cpu=getattr(self.args, "gateway_cpu", None))

    def raw(self, target):
        channel = socket.create_connection(("127.0.0.1", self.listen), timeout=3)
        channel.sendall(f"GET {target} HTTP/1.1\r\nHost: 127.0.0.1:{self.listen}\r\nConnection: close\r\n\r\n".encode("ascii"))
        self.observations.append({"raw_client": target, "event": "connected", "monotonic_ns": time.perf_counter_ns()})
        return channel

    def close(self):
        try:
            if self.gateway is not None:
                self.gateway.close()
        finally:
            try:
                if self.origin is not None:
                    self.origin.close()
            finally:
                write_rows(self.directory / "observations.jsonl", self.observations)
                rows = [future.result() for future in self.futures if future.done() and future.exception() is None]
                write_rows(self.directory / "requests.jsonl", rows)


@contextlib.contextmanager
def experiment(args, name, **parameters):
    episode = Episode(args, name, **parameters)
    try:
        yield episode
    finally:
        episode.close()
    ledger = journal_summary(episode.directory / "origin.jsonl")
    final = json.loads((episode.directory / "origin-final.json").read_text(encoding="utf-8"))
    require(all(final[key] == value for key, value in ledger.items()), "independent journal disagrees with origin counters")
    require(ledger["active"] == 0, "synthetic work was lost at shutdown")
    rows = [json.loads(line) for line in (episode.directory / "requests.jsonl").read_text(encoding="utf-8").splitlines()]
    write_json(episode.directory / "verified.json", {"kind": "invariant-control", "origin": ledger,
               "requests": summarize_requests(rows), "hashes": evidence_hashes(episode.directory)})
    print(f"Verified {name}", flush=True)


def same_key(args, strategy, concurrency, noise):
    with experiment(args, f"{strategy}-{'noise' if noise else 'same'}-{concurrency}", strategy=strategy) as ep:
        with ThreadPoolExecutor(max_workers=concurrency) as pool:
            try:
                first = ep.submit(pool, "/work/one?q=a&q=b")
                ep.await_origin("starts", 1)
                for index in range(1, concurrency):
                    target = f"/work/%6Fne?q=b&noise={index}&%71=a&extra{index}={index}" if noise else "/work/one?q=a&q=b"
                    ep.submit(pool, target)
                ep.await_metric("single_flight_joins_total", concurrency - 1)
                require(ep.origin_stats()["active"] == 1, "same operation duplicated at independent origin")
            finally:
                ep.origin.release()
            bodies = {ep.success(future)["body_sha256"] for future in ep.futures}
            require(len(bodies) == 1, "equivalent work produced different responses")
            ep.await_origin("active", 0)
            ep.await_metric("origin_work_outstanding", 0)
            require(ep.origin_stats()["starts"] == 1, "more than one held execution")
            if strategy == "MATERIALIZE":
                ep.success(ep.submit(pool, "/work/one?q=b&q=a&new_noise=1000"))
                ep.restart()
                ep.success(ep.submit(pool, "/work/one?q=a&q=b"))
                require(ep.origin_stats()["starts"] == 1, "persisted equivalent artifact recomputed")
                ep.await_metric("artifact_hits_total", 1)


def unique_keys(args, strategy, active, queued, policy_active=None, policy_queued=None):
    n = min(active, policy_active) if policy_active is not None else active
    q = min(queued, policy_queued) if policy_queued is not None else queued
    name = f"{strategy}-unique-{active}-{queued}-{policy_active}-{policy_queued}"
    with experiment(args, name, strategy=strategy, active=active, queued=queued,
                    policy_active=policy_active, policy_queued=policy_queued) as ep:
        with ThreadPoolExecutor(max_workers=n + q + 1) as pool:
            try:
                admitted = []
                for index in range(n):
                    admitted.append(ep.submit(pool, f"/work/active{index}"))
                    ep.await_origin("active", index + 1)
                for index in range(q):
                    admitted.append(ep.submit(pool, f"/work/queued{index}"))
                    ep.await_metric("origin_queue_depth", index + 1)
                for index in range(4):
                    ep.success(ep.submit(pool, f"/work/rejected{index}"), 503)
                require(ep.origin_stats()["starts"] == n, "unique-key pressure escaped admission")
                ep.await_metric("origin_queue_depth", q)
            finally:
                ep.origin.release()
            for future in admitted:
                ep.success(future)
            ep.await_metric("origin_queue_depth", 0)
            require(ep.origin_stats()["starts"] == n + q, "queue did not execute exactly admitted work")
            require(ep.origin_stats()["peak"] <= n, "actual origin peak exceeded admission bound")


def semantic_controls(args, strategy):
    with experiment(args, f"{strategy}-semantic-controls", strategy=strategy, held=False) as ep:
        with ThreadPoolExecutor(max_workers=1) as pool:
            hashes = {ep.success(ep.submit(pool, target))["body_sha256"] for target in
                      ["/work/one", "/work/one?q", "/work/one?q=", "/work/one?q=a", "/work/one?q=a&q=a"]}
            require(len(hashes) == 5, "selected semantics were accidentally collapsed")
            require(ep.origin_stats()["starts"] == 5, "selected controls did not execute distinct work")
            for target in ["/unmatched", "/denied/one"]:
                ep.success(ep.submit(pool, target), 403)
            require(ep.origin_stats()["starts"] == 5, "denied input dispatched origin work")


def unresolved(args, strategy, failure):
    with experiment(args, f"{strategy}-unresolved-{failure}", strategy=strategy, failure=failure) as ep:
        with ThreadPoolExecutor(max_workers=2) as pool:
            try:
                if failure in ("disconnect", "crash"):
                    with ep.raw("/work/one"):
                        ep.await_origin("active", 1)
                    with ep.raw("/work/one"):
                        ep.await_metric("single_flight_joins_total", 1)
                else:
                    first = ep.submit(pool, "/reset/one" if failure == "reset" else "/work/one")
                    ep.await_origin("active", 1)
                    ep.success(first, 502 if failure == "reset" else 504)
                for index in range(2, 5):
                    ep.success(ep.submit(pool, f"/work/key{index}"), 503)
                    require(ep.origin_stats()["active"] == 1, "uncertain work disappeared")
                    require(ep.origin_stats()["starts"] == 1, "distinct keys replaced unfinished work")
                ep.restart(crash=failure == "crash")
                ep.await_metric("origin_work_unresolved", 1)
                for target in ["/work/one", "/work/after-restart"]:
                    ep.success(ep.submit(pool, target), 503)
                require(ep.origin_stats()["active"] == 1 and ep.origin_stats()["peak"] == 1,
                        "restart forgot independently unfinished computation")
            finally:
                ep.origin.release()
            ep.await_origin("active", 0)
            ep.success(ep.submit(pool, "/work/after-remote-finish"), 503)
            ep.await_metric("origin_work_outstanding", 1)


def direct_control(args):
    with experiment(args, "direct-origin-overlap-control", direct=True) as ep:
        with ThreadPoolExecutor(max_workers=4) as pool:
            try:
                for index in range(4):
                    ep.submit(pool, f"/work/direct{index}")
                ep.await_origin("active", 4)
            finally:
                ep.origin.release()
            for future in ep.futures:
                ep.success(future)
            require(ep.origin_stats()["peak"] == 4, "origin itself concealed a capacity-one defect")


def run_controls(args):
    if args.suite in ("all", "direct"):
        direct_control(args)
    for strategy in ["BOUNDED_COMPUTE", "MATERIALIZE"]:
        if args.suite in ("all", "same"):
            for concurrency in [1, 4, 16, 64]:
                same_key(args, strategy, concurrency, False)
            same_key(args, strategy, 64, True)
        if args.suite in ("all", "unique"):
            for active, queued, policy_active, policy_queued in [(1, 0, None, None), (2, 0, None, None), (2, 2, None, None), (3, 4, 1, 1)]:
                unique_keys(args, strategy, active, queued, policy_active, policy_queued)
        if args.suite in ("all", "semantic"):
            semantic_controls(args, strategy)
        if args.suite in ("all", "unresolved"):
            for failure in ["response", "executor", "reset", "disconnect", "crash"]:
                unresolved(args, strategy, failure)


def prepare(args):
    args.output = args.output.resolve()
    args.output.mkdir(parents=True, exist_ok=False)
    args.java = str(Path(args.java).resolve())
    machine = environment(args.java)
    machine.pop("heap")
    machine.pop("jvm_active_processor_count")
    metadata = {"kind": getattr(args, "kind", "invariant-control"), "head": git("rev-parse", "HEAD"),
                "tree": git("rev-parse", "HEAD^{tree}"), "status": git("status", "--porcelain"),
                "environment": machine, "iterations": args.iterations, "body_bytes": args.body_bytes,
                "process_limits": {"origin_heap": "128m", "gateway_heap": "256m", "jvm_active_processor_count": 2,
                                   "origin_cpu": getattr(args, "origin_cpu", None), "gateway_cpu": getattr(args, "gateway_cpu", None),
                                   "client_cpu": getattr(args, "client_cpu", None), "rss_limit": None},
                "harness": {path.name: digest(path) for path in sorted(Path(__file__).parent.glob("*.py"))},
                "limitations": ["Barrier latency is not performance evidence", "Windows gateway stop is forced process-tree termination",
                                "work outstanding includes the explicit pre-computation barrier; it does not claim CPU use while held"]}
    write_json(args.output / "environment.json", metadata)
    # Save uncommitted authored sources too: a SHA and dirty-status flag alone do not identify development code.
    sources = {str(path.relative_to(REPOSITORY)): path.read_text(encoding="utf-8")
               for root in [REPOSITORY / "bounded-origin-benchmarks/src", REPOSITORY / "bounded-origin-benchmarks/scripts"]
               for path in sorted(root.rglob("*")) if path.suffix in (".java", ".py")}
    write_json(args.output / "harness-sources.json", sources)
    metadata["source_hashes"] = {name: digest(REPOSITORY / name) for name in sources}
    write_json(args.output / "source-hashes.json", metadata["source_hashes"])
    build = [args.java, "-cp", str(REPOSITORY / "gradle/wrapper/gradle-wrapper.jar"), "org.gradle.wrapper.GradleWrapperMain",
             ":bounded-origin-benchmarks:installBenchmarks", ":bounded-origin-cli:preparePackagedDistributionTest", "--no-daemon"]
    write_json(args.output / "build-command.json", build)
    with (args.output / "build.log").open("xb") as log:
        result = subprocess.run(build, cwd=REPOSITORY, stdout=log, stderr=subprocess.STDOUT, check=False)
    require(result.returncode == 0, "benchmark/package build failed; raw log retained")
    libraries = REPOSITORY / "bounded-origin-benchmarks/build/benchmark/lib"
    launcher_name = "bounded-origin.bat" if os.name == "nt" else "bounded-origin"
    launchers = list((REPOSITORY / "bounded-origin-cli/build/packaged-distribution-test").glob(f"*/bin/{launcher_name}"))
    require(len(launchers) == 1, "expected exactly one extracted packaged CLI")
    for name, expected in metadata["source_hashes"].items():
        require(digest(REPOSITORY / name) == expected, "benchmark source changed during build")
    installed = args.output / "_bin"
    installed.mkdir()
    args.libraries = installed / "lib"
    shutil.copytree(libraries, args.libraries)
    shutil.copytree(launchers[0].parent.parent, installed / "cli")
    args.launcher = installed / "cli/bin" / launcher_name
    extension = "*.zip" if os.name == "nt" else "*.tar"
    artifacts = list(libraries.glob("*.jar")) + list((REPOSITORY / "bounded-origin-cli/build/distributions").glob(extension))
    write_json(args.output / "artifacts.json", {str(path.relative_to(REPOSITORY)): digest(path) for path in artifacts if path.is_file()})
    write_json(args.output / "installed-hashes.json", evidence_hashes(installed))
    return metadata


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--java", default=shutil.which("java"))
    parser.add_argument("--suite", choices=["all", "same", "unique", "semantic", "unresolved", "direct"], default="all")
    parser.add_argument("--iterations", type=int, default=10000)
    parser.add_argument("--body-bytes", type=int, default=1024)
    args = parser.parse_args(argv)
    if not args.java or not 0 <= args.iterations <= 1_000_000_000 or not 128 <= args.body_bytes <= 16_777_216:
        parser.error("Java21 and valid synthetic CPU/body parameters are required")
    metadata = prepare(args)
    try:
        run_controls(args)
        require(git("rev-parse", "HEAD") == metadata["head"], "source commit changed during controls")
        write_json(args.output / "completed.json", {"kind": "invariant-control", "cases": len(list(args.output.glob("*/verified.json"))),
                   "hashes": evidence_hashes(args.output)})
    except BaseException as error:
        write_json(args.output / "failed.json", {"error": f"{type(error).__name__}: {error}"})
        raise


if __name__ == "__main__":
    main()
