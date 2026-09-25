"""Capture standalone JMH evidence; smoke runs are never publication measurements."""

import argparse
import csv
import datetime
import hashlib
import json
import math
import os
from pathlib import Path
import platform
import shutil
import statistics
import subprocess
import sys


REPOSITORY = Path(__file__).resolve().parents[2]
SUITES = {
    "configuration": "ConfigurationBenchmark",
    "routes": "RouteEvaluationBenchmark",
    "overlap": "OverlapEvaluationBenchmark",
    "keys": "SemanticKeyBenchmark",
    "programmatic": "ProgrammaticComparisonBenchmark",
    "all": ".*Benchmark",
}


def capture(command, *, required=True):
    result = subprocess.run(command, cwd=REPOSITORY, text=True, encoding="utf-8",
                            errors="replace", stdout=subprocess.PIPE,
                            stderr=subprocess.STDOUT, check=False)
    if required and result.returncode:
        raise RuntimeError(f"Command failed ({result.returncode}): {command!r}\n{result.stdout}")
    return {"command": command, "exit_code": result.returncode, "output": result.stdout}


def git(*args):
    return capture(["git", "-c", f"safe.directory={REPOSITORY}", *args])["output"].strip()


def digest(path):
    value = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            value.update(block)
    return value.hexdigest()


def write_json(path, value):
    with path.open("x", encoding="utf-8", newline="\n") as stream:
        json.dump(value, stream, indent=2, allow_nan=False)
        stream.write("\n")


def jmh_arguments(suite, smoke, result):
    arguments = ["org.openjdk.jmh.Main", f"io.github.aalsanie.boundedorigin.cli.{SUITES[suite]}.*",
                 "-t", "1", "-foe", "true", "-rf", "json", "-rff", str(result),
                 "-f", "1" if smoke else "10", "-wi", "0" if smoke else "5",
                 "-i", "1" if smoke else "5", "-w", "1s", "-r", "10ms" if smoke else "1s",
                 "-jvmArgsAppend", "-Xms512m -Xmx512m -XX:ActiveProcessorCount=1"]
    if smoke:
        for parameter in ["routeCount=16", "shape=FIXED", "position=LAST",
                          "queryDimensions=1", "queryCase=SELECTED", "ordered=false"]:
            arguments += ["-p", parameter]
    else:
        arguments += ["-prof", "gc"]
    return arguments


def summarize(results):
    if not isinstance(results, list) or not results:
        raise ValueError("JMH returned no result records")
    summary = []
    seen = set()
    for row in results:
        key = (row["benchmark"], json.dumps(row["params"], sort_keys=True))
        if key in seen:
            raise ValueError("duplicate benchmark/parameter cell")
        seen.add(key)
        if row["mode"] != "avgt" or row["primaryMetric"]["scoreUnit"] != "us/op":
            raise ValueError("unexpected measurement units")
        raw = row["primaryMetric"]["rawData"]
        if len(raw) != row["forks"] or not raw:
            raise ValueError("missing fork data")
        means = []
        for samples in raw:
            if len(samples) != row["measurementIterations"] or not samples:
                raise ValueError("missing iteration data")
            if any(isinstance(value, bool) or not isinstance(value, (int, float)) or not math.isfinite(value) or value < 0
                   for value in samples):
                raise ValueError("invalid iteration value")
            means.append(statistics.mean(samples))
        summary.append({"benchmark": key[0], "params": key[1], "unit": "us/op",
                        "forks": len(means), "iterations_per_fork": row["measurementIterations"],
                        "mean_of_fork_means": statistics.mean(means),
                        "median_of_fork_means": statistics.median(means),
                        "stdev_of_fork_means": statistics.stdev(means) if len(means) > 1 else None,
                        "min_fork_mean": min(means), "max_fork_mean": max(means)})
    return summary


def environment(java):
    result = {"utc": datetime.datetime.now(datetime.timezone.utc).isoformat(),
              "platform": platform.platform(), "python": sys.version,
              "logical_cpus": os.cpu_count(),
              "java": capture([java, "-XshowSettings:properties", "-version"]),
              "heap": "512m", "jvm_active_processor_count": 1,
              "cpu_affinity": sorted(os.sched_getaffinity(0)) if hasattr(os, "sched_getaffinity") else None,
              "load_average": os.getloadavg() if hasattr(os, "getloadavg") else None}
    if sys.platform == "linux":
        result["cpu_topology"] = capture(["lscpu", "--json"], required=False)
        for name in ["meminfo", "version"]:
            result[name] = Path("/proc", name).read_text(encoding="utf-8")
        for name in ["cpu.max", "memory.max", "cpuset.cpus.effective"]:
            path = Path("/sys/fs/cgroup", name)
            result[name] = path.read_text(encoding="utf-8") if path.exists() else None
    elif os.name == "nt":
        result["hardware"] = capture([
            "powershell.exe", "-NoProfile", "-NonInteractive", "-Command",
            "Get-CimInstance Win32_Processor | Select-Object Name,NumberOfCores,NumberOfLogicalProcessors | ConvertTo-Json; "
            "Get-CimInstance Win32_ComputerSystem | Select-Object TotalPhysicalMemory | ConvertTo-Json"], required=False)
    return result


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", required=True, type=Path, help="new evidence directory; never overwritten")
    parser.add_argument("--suite", choices=SUITES, default="all")
    parser.add_argument("--java", default=shutil.which("java"))
    parser.add_argument("--smoke", action="store_true", help="fixture validation only; not publication evidence")
    parser.add_argument("--cpu", type=int, help="Linux logical CPU affinity for this process and all forks")
    parser.add_argument("--ci-run", help="verified green main CI URL, required for publication")
    args = parser.parse_args(argv)
    if not args.java:
        parser.error("a Java 21 executable is required")
    head, status = git("rev-parse", "HEAD"), git("status", "--porcelain")
    if not args.smoke:
        if status or git("branch", "--show-current") != "main" or head != git("rev-parse", "origin/main"):
            parser.error("publication requires clean main equal to origin/main; use --smoke for development")
        if not args.ci_run or args.cpu is None or sys.platform != "linux":
            parser.error("publication requires a verified --ci-run and explicit Linux --cpu affinity")
    if args.cpu is not None:
        if not hasattr(os, "sched_setaffinity") or args.cpu not in os.sched_getaffinity(0):
            parser.error("requested CPU affinity is unavailable")
        os.sched_setaffinity(0, {args.cpu})
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=False)
    metadata = {"kind": "smoke" if args.smoke else "publication", "head": head,
                "tree": git("rev-parse", "HEAD^{tree}"), "status": status,
                "ci_run_declared_verified": args.ci_run, "suite": args.suite,
                "runner_sha256": digest(Path(__file__)), "environment": environment(args.java),
                "limitations": ["CPU affinity is not exclusive host reservation",
                                "512m bounds Java heap, not total process RSS",
                                "microbenchmarks do not measure HTTP or origin computation"]}
    write_json(output / "environment.json", metadata)
    build = [args.java, "-classpath", str(REPOSITORY / "gradle/wrapper/gradle-wrapper.jar"),
             "org.gradle.wrapper.GradleWrapperMain", ":bounded-origin-benchmarks:installBenchmarks", "--no-daemon"]
    java = [args.java, "-Xms512m", "-Xmx512m", "-XX:ActiveProcessorCount=1", "-cp",
            str(REPOSITORY / "bounded-origin-benchmarks/build/benchmark/lib") + os.sep + "*"]
    commands = [build, java + ["io.github.aalsanie.boundedorigin.cli.FixtureCatalog", str(output / "fixtures")],
                java + jmh_arguments(args.suite, args.smoke, output / "raw.json")]
    write_json(output / "commands.json", commands)
    for index, command in enumerate(commands):
        with (output / f"command-{index}.log").open("x", encoding="utf-8") as log:
            result = subprocess.run(command, cwd=REPOSITORY, stdout=log, stderr=subprocess.STDOUT, check=False)
        if result.returncode:
            raise RuntimeError(f"Command {index} failed with exit {result.returncode}; evidence retained at {output}")
        if index == 0:
            libraries = REPOSITORY / "bounded-origin-benchmarks/build/benchmark/lib"
            write_json(output / "artifacts.json", {path.name: digest(path) for path in sorted(libraries.glob("*.jar"))})
            write_json(output / "dependencies.json", {str(path.relative_to(REPOSITORY)): digest(path)
                       for path in sorted(REPOSITORY.glob("*/gradle.lockfile"))})
    head_after = git("rev-parse", "HEAD")
    status_after = git("status", "--porcelain")
    if head_after != head or digest(Path(__file__)) != metadata["runner_sha256"] or status_after != status:
        raise RuntimeError(f"Source state changed during the campaign; evidence retained at {output}")
    results = json.loads((output / "raw.json").read_text(encoding="utf-8"))
    summary = summarize(results)
    with (output / "summary.csv").open("x", encoding="utf-8", newline="") as stream:
        writer = csv.DictWriter(stream, fieldnames=list(summary[0]))
        writer.writeheader()
        writer.writerows(summary)
    write_json(output / "completed.json", {"utc": datetime.datetime.now(datetime.timezone.utc).isoformat(),
               "cells": len(summary), "raw_sha256": digest(output / "raw.json"),
               "kind": metadata["kind"], "head_after": head_after})
    print(f"Saved {metadata['kind']} evidence in {output}")


if __name__ == "__main__":
    main()
