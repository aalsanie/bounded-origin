"""Packaged system experiment support. No performance thresholds or product hooks."""

import base64
from concurrent.futures import ThreadPoolExecutor, wait, FIRST_COMPLETED
import contextlib
import hashlib
import http.client
import json
import math
import os
from pathlib import Path
import socket
import subprocess
import sys
import threading
import time
from urllib.parse import unquote, urlsplit

from microbench import REPOSITORY, digest, write_json


PREFIX = "io.github.aalsanie.boundedorigin.benchmarks."


def runtime_environment(java):
    env = os.environ.copy()
    for name in ("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS", "JAVA_OPTS", "BOUNDED_ORIGIN_OPTS"):
        env.pop(name, None)
    env["JAVA_HOME"] = str(Path(java).resolve().parent.parent)
    return env


def require(condition, message):
    if not condition:
        raise AssertionError(message)


def await_value(read, predicate, description, timeout=30):
    deadline = time.monotonic() + timeout
    last = None
    while time.monotonic() < deadline:
        last = read()
        if predicate(last):
            return last
        threading.Event().wait(0.01)
    raise TimeoutError(f"{description}: last observation {last!r}")


def semantic_identity(target):
    parsed = urlsplit(target)
    selected = []
    for pair in parsed.query.split("&"):
        name, equals, value = pair.partition("=")
        if unquote(name) == "q":
            selected.append("value:" + unquote(value) if equals else "bare")
    return unquote(parsed.path) + "".join(f"|{len(value)}:{value}" for value in sorted(selected))


def request(port, target, *, ordinal=0, scheduled_ns=None, timeout=80, expected_bytes=None):
    started = time.perf_counter_ns()
    row = {"ordinal": ordinal, "target": target, "identity": semantic_identity(target),
           "scheduled_ns": scheduled_ns, "started_ns": started,
           "scheduler_lag_ns": max(0, started - scheduled_ns) if scheduled_ns is not None else None,
           "status": None, "error": None, "body_bytes": 0, "body_sha256": None,
           "client_capacity_drop": False}
    connection = http.client.HTTPConnection("127.0.0.1", port, timeout=timeout)
    try:
        connection.request("GET", target, headers={"Connection": "close"})
        response = connection.getresponse()
        body = response.read()
        row.update(status=response.status, body_bytes=len(body), body_sha256=hashlib.sha256(body).hexdigest())
        if expected_bytes is not None and response.status == 200:
            require(len(body) == expected_bytes, "synthetic response length differs")
            require(body.startswith((row["identity"] + "\n").encode("utf-8")), "synthetic response identity differs")
    except http.client.IncompleteRead as error:
        row["body_bytes"] = len(error.partial)
        row["body_sha256"] = hashlib.sha256(error.partial).hexdigest()
        row["error"] = f"{type(error).__name__}: {error}"
    except (OSError, http.client.HTTPException) as error:
        row["error"] = f"{type(error).__name__}: {error}"
    except AssertionError as error:
        row["error"] = f"ResponseValidationError: {error}"
    finally:
        connection.close()
    row["finished_ns"] = time.perf_counter_ns()
    row["latency_ns"] = row["finished_ns"] - started
    return row


def control(port, target):
    with contextlib.closing(http.client.HTTPConnection("127.0.0.1", port, timeout=3)) as connection:
        connection.request("GET", target, headers={"Connection": "close"})
        response = connection.getresponse()
        data = response.read()
        if response.status != 200:
            raise RuntimeError(f"control endpoint {target}: HTTP {response.status}")
        return data


def parse_metrics(data):
    result = {}
    for line in data.decode("ascii").splitlines():
        if line and not line.startswith("#"):
            name, value = line.split()
            require(name not in result and math.isfinite(float(value)), "invalid metrics sample")
            result[name] = float(value)
    return result


def free_ports():
    with socket.socket() as first, socket.socket() as second:
        first.bind(("127.0.0.1", 0))
        second.bind(("127.0.0.1", 0))
        return first.getsockname()[1], second.getsockname()[1]


def configuration(directory, listen, admin, origin, strategy, active=1, queued=0,
                  policy_active=None, policy_queued=None, failure=None, body_bytes=1024):
    require(failure != "request", "request timeout cannot precede configured origin/execution timeouts")
    policy_active = active if policy_active is None else policy_active
    policy_queued = queued if policy_queued is None else policy_queued
    execution = "PT1S" if failure == "executor" else "PT60S"
    response = "PT1S" if failure == "response" else "PT60S"
    downstream = "PT70S"
    scalar = lambda path: "'" + str(path).replace("'", "''") + "'"
    return f"""schema: 1
gateway:
  listen.host: 127.0.0.1
  listen.port: {listen}
  admin.host: 127.0.0.1
  admin.port: {admin}
  origin.host: 127.0.0.1
  origin.port: {origin}
  origin.completion-contract: RESPONSE_COMPLETE
  origin.ownership-directory: {scalar(directory / 'ownership')}
  temporary.directory: {scalar(directory / 'spool')}
  ingress.trust: UNTRUSTED
  http.max-request-body-bytes: 0
  origin.max-active: {active}
  origin.max-queued: {queued}
  origin.max-connections: {active}
  origin.max-pending-acquires: 0
  origin.max-execution-duration: {execution}
  origin.response-timeout: {response}
  origin.max-result-bytes: {body_bytes}
  request.timeout: {downstream}
  drain.timeout: PT1S
  spool.max-bytes: {max(1048576, body_bytes * 256)}
  spool.max-files: 256
store:
  directory: {scalar(directory / 'store')}
  max-bytes: {max(67108864, body_bytes * 1024)}
  max-artifact-bytes: {body_bytes}
routes:
  - id: work
    version: 1
    precedence: 1
    match:
      path: /{{kind}}/{{id}}
      method: GET
      trust: UNTRUSTED
    strategy: {strategy}
    representation: PUBLIC_IMMUTABLE
    materializer-version: synthetic-v1
    key:
      path: [kind, id]
      query:
        include: [q]
        order-independent: true
    budget:
      max-active: {policy_active}
      max-queued: {policy_queued}
      max-execution-duration: {execution}
      max-result-bytes: {body_bytes}
  - id: explicit-deny
    version: 1
    precedence: 10
    match:
      path: /denied/**
      method: GET
    strategy: DENY
fallback:
  id: fallback-deny
  version: 1
  precedence: -2147483648
  strategy: DENY
"""


class ManagedProcess:
    def __init__(self, command, directory, name, env=None):
        self.log = (directory / f"{name}.log").open("xb")
        write_json(directory / f"{name}-command.json", command)
        self.process = None
        try:
            self.process = subprocess.Popen(command, stdin=subprocess.PIPE, stdout=self.log,
                                            stderr=subprocess.STDOUT, env=env)
        except BaseException:
            self.log.close()
            raise

    def alive(self):
        require(self.process.poll() is None, f"child exited {self.process.returncode}; see {self.log.name}")

    def stop(self, *, graceful=False, stdin=False):
        process = self.process
        try:
            if process.poll() is None:
                if stdin:
                    process.stdin.close()
                elif os.name == "nt":
                    result = subprocess.run(["taskkill", "/PID", str(process.pid), "/T", "/F"],
                                            stdout=subprocess.PIPE, stderr=subprocess.STDOUT, check=False)
                    require(result.returncode == 0, "failed to terminate owned Windows process tree")
                elif graceful:
                    process.terminate()
                else:
                    process.kill()
                try:
                    process.wait(timeout=15)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait(timeout=10)
                    raise
        finally:
            process.stdin.close()
            self.log.close()


class Origin:
    def __init__(self, java, libraries, directory, iterations, body_bytes, held, cpu=None):
        command = [java, "-Xms128m", "-Xmx128m", "-XX:ActiveProcessorCount=2", "-cp", str(libraries) + os.sep + "*",
                   PREFIX + "SyntheticOrigin", str(directory), str(iterations), str(body_bytes), "held" if held else "free"]
        if cpu is not None:
            command = ["taskset", "-c", str(cpu)] + command
        self.child = ManagedProcess(command, directory, "origin", runtime_environment(java))
        try:
            def ready():
                self.child.alive()
                path = directory / "origin-ready.json"
                try:
                    return json.loads(path.read_text(encoding="utf-8")) if path.exists() else None
                except json.JSONDecodeError:
                    return None
            metadata = await_value(ready, lambda value: value is not None, "origin readiness")
            self.port = metadata["port"]
        except BaseException:
            self.child.stop()
            raise

    def stats(self):
        self.child.alive()
        return json.loads(control(self.port, "/control/stats"))

    def release(self):
        control(self.port, "/control/release")

    def close(self):
        self.child.stop(stdin=True)


class Gateway:
    def __init__(self, launcher, config, directory, admin, sequence=0, java=None, cpu=None):
        command = (["cmd.exe", "/d", "/c"] if os.name == "nt" else []) + [str(launcher), "run", "--config", str(config)]
        env = runtime_environment(java) if java is not None else os.environ.copy()
        env["JAVA_OPTS"] = "-Xms256m -Xmx256m -XX:ActiveProcessorCount=2"
        if java is not None:
            env["JAVA_HOME"] = str(Path(java).resolve().parent.parent)
        if cpu is not None:
            command = ["taskset", "-c", str(cpu)] + command
        self.child = ManagedProcess(command, directory, f"gateway-{sequence}", env)
        self.admin = admin
        try:
            def ready():
                self.child.alive()
                try:
                    control(admin, "/ready")
                    return True
                except (OSError, http.client.HTTPException, RuntimeError):
                    return False
            await_value(ready, bool, "packaged CLI readiness")
        except BaseException:
            self.close()
            raise

    def metrics(self):
        self.child.alive()
        return parse_metrics(control(self.admin, "/metrics"))

    def close(self, crash=False):
        self.child.stop(graceful=not crash)


def journal_summary(path, after_id=0):
    active = set()
    finished = set()
    peak, cpu, previous, starts = 0, 0, None, 0
    for line in path.read_text(encoding="utf-8").splitlines():
        row = json.loads(line)
        require(type(row["id"]) is int and row["id"] > 0, "invalid origin event ID")
        if row["id"] <= after_id:
            continue
        require(isinstance(row["monotonic_ns"], int), "invalid event clock")
        require(previous is None or row["monotonic_ns"] >= previous, "nonmonotonic origin journal")
        previous = row["monotonic_ns"]
        base64.b64decode(row["target_base64"], validate=True).decode("utf-8")
        if row["event"] == "start":
            require(row["id"] not in active | finished, "duplicate origin start")
            require(row["cpu_ns"] == 0, "CPU recorded before work")
            starts += 1
            active.add(row["id"])
        elif row["event"] == "finish":
            require(row["id"] in active, "unowned origin completion")
            require(type(row["cpu_ns"]) is int and row["cpu_ns"] >= 0, "invalid origin CPU")
            active.remove(row["id"])
            finished.add(row["id"])
            cpu += row["cpu_ns"]
        else:
            raise ValueError("unknown origin journal event")
        require(row["active"] == len(active), "origin accounting differs from event history")
        peak = max(peak, len(active))
    return {"starts": starts, "completed": len(finished), "active": len(active), "peak": peak, "work_cpu_ns": cpu}


def percentile(values, fraction):
    if not values:
        return None
    ordered = sorted(values)
    # Nearest rank, retaining failure latencies and never interpolating unseen observations.
    return ordered[max(0, math.ceil(fraction * len(ordered)) - 1)]


def summarize_requests(rows):
    require(len({row["ordinal"] for row in rows}) == len(rows), "duplicate request ordinal")
    sent = [row for row in rows if not row["client_capacity_drop"]]
    latencies = [row["latency_ns"] for row in sent]
    require(all(isinstance(value, int) and value >= 0 for value in latencies), "invalid latency")
    statuses = {}
    bodies = {}
    status_latencies = {}
    for row in sent:
        status_latencies.setdefault(str(row["status"]) if row["status"] is not None else "transport-error", []).append(row["latency_ns"])
        if row["status"] is not None:
            statuses[str(row["status"])] = statuses.get(str(row["status"]), 0) + 1
        if row["status"] == 200 and row["error"] is None:
            prior = bodies.setdefault(row["identity"], row["body_sha256"])
            require(prior == row["body_sha256"], "same operation produced different bytes")
    return {"offered": len(rows), "attempted": len(sent), "completed_http": sum(statuses.values()),
            "client_capacity_drops": len(rows) - len(sent), "request_errors": sum(row["error"] is not None for row in sent),
            "transport_errors": sum(row["error"] is not None and not row["error"].startswith("ResponseValidationError:") for row in sent),
            "response_validation_errors": sum(row["error"] is not None and row["error"].startswith("ResponseValidationError:") for row in sent),
            "statuses": statuses, "unique_operations_attempted": len({row["identity"] for row in sent}),
            "response_body_bytes": sum(row["body_bytes"] for row in sent),
            "latency_ns": {label: percentile(latencies, quantile) for label, quantile in [("p50", .5), ("p95", .95), ("p99", .99)]},
            "latency_by_status_ns": {status: {label: percentile(values, quantile) for label, quantile in [("p50", .5), ("p95", .95), ("p99", .99)]}
                                     for status, values in status_latencies.items()},
            "scheduler_lag_ns_p99": percentile([row["scheduler_lag_ns"] for row in sent if row["scheduler_lag_ns"] is not None], .99),
            "successful_body_sha256": bodies}


def load(port, targets, concurrency, body_bytes, rate=None):
    require(concurrency > 0 and (rate is None or rate > 0), "invalid load budget")
    rows = []
    start = time.perf_counter_ns()
    cpu_before = time.process_time_ns()
    with ThreadPoolExecutor(max_workers=concurrency) as workers:
        pending = set()
        for index, target in enumerate(targets):
            scheduled = start + int(index * 1e9 / rate) if rate is not None else None
            if scheduled is not None:
                threading.Event().wait(max(0, (scheduled - time.perf_counter_ns()) / 1e9))
            elif len(pending) == concurrency:
                completed, pending = wait(pending, return_when=FIRST_COMPLETED)
                rows.extend(future.result() for future in completed)
            completed = {future for future in pending if future.done()}
            pending -= completed
            rows.extend(future.result() for future in completed)
            if len(pending) == concurrency:
                rows.append({"ordinal": index, "target": target, "scheduled_ns": scheduled,
                             "client_capacity_drop": True, "identity": semantic_identity(target)})
            else:
                pending.add(workers.submit(request, port, target, ordinal=index, scheduled_ns=scheduled, expected_bytes=body_bytes))
        rows.extend(future.result() for future in pending)
    elapsed = time.perf_counter_ns() - start
    return sorted(rows, key=lambda row: row["ordinal"]), {"elapsed_ns": elapsed,
            "driver_process_cpu_ns": time.process_time_ns() - cpu_before, "rate": rate, "concurrency": concurrency}


def write_rows(path, rows):
    with path.open("x", encoding="utf-8", newline="\n") as stream:
        for row in rows:
            stream.write(json.dumps(row, allow_nan=False) + "\n")


def evidence_hashes(directory):
    return {str(path.relative_to(directory)): digest(path) for path in sorted(directory.rglob("*")) if path.is_file()}


def process_sample(pid):
    if sys.platform != "linux":
        return None
    fields = Path(f"/proc/{pid}/stat").read_text(encoding="utf-8").rsplit(")", 1)[1].split()
    return {"cpu_ns": (int(fields[11]) + int(fields[12])) * 1_000_000_000 // os.sysconf("SC_CLK_TCK"),
            "rss_bytes": int(fields[21]) * os.sysconf("SC_PAGE_SIZE")}
