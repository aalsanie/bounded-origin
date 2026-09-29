"""Regenerate descriptive publication tables from a campaign directory or evidence ZIP."""

import argparse
from collections import defaultdict
import csv
import hashlib
import json
import math
from pathlib import Path
import statistics
from xml.sax.saxutils import escape
import zipfile

from microbench import summarize


SUITES = ("configuration", "routes", "overlap", "keys", "programmatic")


class Evidence:
    def __init__(self, path):
        self.path = path
        self.archive = zipfile.ZipFile(path) if path.is_file() else None

    def close(self):
        if self.archive is not None:
            self.archive.close()

    def data(self, name):
        return self.archive.read(name) if self.archive is not None else (self.path / name).read_bytes()

    def json(self, name):
        return json.loads(self.data(name))

    def verify_archive(self):
        if self.archive is None:
            return None
        manifest = self.json("evidence-manifest.json")
        names = self.archive.namelist()
        if len(names) != len(set(names)) or set(names) != set(manifest["files"]) | {"evidence-manifest.json"}:
            raise ValueError("archive membership differs from its manifest")
        for name, expected in manifest["files"].items():
            if hashlib.sha256(self.data(name)).hexdigest() != expected:
                raise ValueError("evidence hash differs: " + name)
        return len(manifest["files"])


def distribution(values):
    available = [value for value in values if value is not None]
    if any(type(value) not in (int, float) or not math.isfinite(value) or value < 0 for value in available):
        raise ValueError("invalid measurement")
    return {"available_repetitions": len(available),
            "mean": statistics.mean(available) if available else None,
            "median": statistics.median(available) if available else None,
            "stdev": statistics.stdev(available) if len(available) > 1 else None,
            "min": min(available) if available else None, "max": max(available) if available else None}


def system_groups(trials, protocol):
    groups = defaultdict(list)
    observed = set()
    for row in trials:
        identity = row["cell"], row["mode"], row["phase"], row["repetition"]
        if identity in observed:
            raise ValueError("duplicate system repetition")
        observed.add(identity)
        measured = row["repetition"] >= protocol["warmup_runs"]
        if row["measured"] != measured:
            raise ValueError("warmup membership differs from the protocol")
        if measured:
            groups[identity[:3]].append(row)
    expected = {(cell["name"], mode, "cold") for cell in protocol["cells"] for mode in ("direct", "bounded", "materialize")}
    expected.update((cell["name"], "materialize", phase) for cell in protocol["cells"]
                    if cell["workload"] == "reuse" for phase in ("warm", "restart"))
    if set(groups) != expected:
        raise ValueError("incomplete system comparison matrix")
    all_repetitions = range(protocol["warmup_runs"] + protocol["measured_repetitions"])
    if observed != {(*group, repetition) for group in expected for repetition in all_repetitions}:
        raise ValueError("incomplete warmup or measurement evidence")
    repetitions = set(range(protocol["warmup_runs"], protocol["warmup_runs"] + protocol["measured_repetitions"]))
    for rows in groups.values():
        if {row["repetition"] for row in rows} != repetitions:
            raise ValueError("incomplete measured repetitions")
    return groups


def system_summary(groups):
    result = []
    for key, rows in sorted(groups.items()):
        metrics = {
            "offered": lambda row: row["requests"]["offered"],
            "attempted": lambda row: row["requests"]["attempted"],
            "completed_http": lambda row: row["requests"]["completed_http"],
            "request_errors": lambda row: row["requests"]["request_errors"],
            "client_drops": lambda row: row["requests"]["client_capacity_drops"],
            "origin_executions": lambda row: row["origin"]["starts"],
            "origin_peak": lambda row: row["origin"]["peak"],
            "origin_cpu_s_per_1000_attempted": lambda row: row["origin_cpu_seconds_per_1000_attempted"],
            "origin_cpu_s_per_1000_successful": lambda row: row["origin_cpu_seconds_per_1000_successful"],
            "origin_compute_cpu_s_per_1000_attempted": lambda row: row["origin_compute_cpu_seconds_per_1000_attempted"],
            "origin_work_amplification": lambda row: row["origin_work_amplification"],
            "completed_http_per_second": lambda row: row["completed_http_per_second"],
            "successful_200_per_second": lambda row: row["successful_200_per_second"],
            "gateway_cpu_s": lambda row: row["gateway_process_cpu_ns"] / 1e9 if row["gateway_process_cpu_ns"] is not None else None,
            "driver_cpu_s": lambda row: row["timing"]["driver_process_cpu_ns"] / 1e9,
            "gateway_rss_sampled_peak_bytes": lambda row: row["gateway_rss_sampled_peak_bytes"],
            "queue_sampled_peak": lambda row: row["gateway_queue_sampled_peak"],
            "artifact_hits": lambda row: row["metric_delta"].get("bounded_origin_artifact_hits_total"),
            "single_flight_joins": lambda row: row["metric_delta"].get("bounded_origin_single_flight_joins_total"),
            "aggregate_gateway_rejections": lambda row: row["metric_delta"].get("bounded_origin_rejections_total"),
            "pool_rejections": lambda row: row["metric_delta"].get("bounded_origin_origin_pool_rejections_total"),
            "response_body_bytes": lambda row: row["requests"]["response_body_bytes"],
            "bytes_stored": lambda row: row["metric_delta"].get("bounded_origin_bytes_stored_total"),
            "scheduler_lag_p99_ms": lambda row: row["requests"]["scheduler_lag_ns_p99"] / 1e6 if row["requests"]["scheduler_lag_ns_p99"] is not None else None,
        }
        for percentile in ("p50", "p95", "p99"):
            metrics["latency_" + percentile + "_ms"] = lambda row, p=percentile: row["requests"]["latency_ns"][p] / 1e6
        for status in sorted({status for row in rows for status in row["requests"]["statuses"]}):
            metrics["http_" + status] = lambda row, s=status: row["requests"]["statuses"].get(s, 0)
            for percentile in ("p50", "p95", "p99"):
                def latency(row, s=status, p=percentile):
                    value = row["requests"]["latency_by_status_ns"].get(s, {}).get(p)
                    return value / 1e6 if value is not None else None
                metrics[f"http_{status}_latency_{percentile}_ms"] = latency
        for name, extract in metrics.items():
            result.append({"cell": key[0], "mode": key[1], "phase": key[2], "metric": name,
                           "repetitions": len(rows), **distribution([extract(row) for row in rows])})
    return result


def micro_summary(evidence):
    rows, hashes = [], {}
    head = evidence.json("system/environment.json")["head"]
    for suite in SUITES:
        completion = evidence.json(suite + "/completed.json")
        raw = evidence.data(suite + "/raw.json")
        digest = hashlib.sha256(raw).hexdigest()
        if completion["kind"] != "publication" or completion["head_after"] != head or digest != completion["raw_sha256"]:
            raise ValueError("microbenchmark source or completion hash differs")
        results = json.loads(raw)
        validated = summarize(results)
        if len(validated) != completion["cells"]:
            raise ValueError("incomplete microbenchmark suite")
        for row in results:
            if row["forks"] != 10 or row["measurementIterations"] != 5 or row["warmupIterations"] != 5:
                raise ValueError("unexpected publication forks/iterations")
            for name, metric, unit in [("average_time", row["primaryMetric"], "us/op"),
                                       ("allocation", row["secondaryMetrics"]["gc.alloc.rate.norm"], "B/op")]:
                raw_data = metric["rawData"]
                if metric["scoreUnit"] != unit or len(raw_data) != 10 or any(len(fork) != 5 for fork in raw_data):
                    raise ValueError("invalid metric units or fork membership")
                for fork in raw_data:
                    distribution(fork)
                means = [statistics.mean(fork) for fork in raw_data]
                rows.append({"suite": suite, "benchmark": row["benchmark"], "params": json.dumps(row["params"], sort_keys=True),
                             "metric": name, "unit": unit, "forks": len(means), **distribution(means)})
        hashes[suite] = digest
    return rows, hashes


def tables(system, micro, protocol):
    lookup = {(row["cell"], row["mode"], row["phase"], row["metric"]): row for row in system}
    order = {cell["name"]: index for index, cell in enumerate(protocol["cells"])}
    keys = sorted({key[:3] for key in lookup}, key=lambda key: (order[key[0]], ("direct", "bounded", "materialize").index(key[1]), ("cold", "warm", "restart").index(key[2])))
    lines = ["<!-- generated-results:start -->", "<details>", "<summary>System results — all workloads and outcomes</summary>", "", "### System results", "",
             "Each row summarizes ten trials. Counts are means; origin starts also show the full range.",
             "Origin peak is the largest independently observed active count. Client drops were never sent.", "",
             "| Cell | Mode / state | Attempted | 200 | 403 | 500 | 503 | Client drops | Origin starts, mean [min, max] | Origin peak |",
             "|---|---|---:|---:|---:|---:|---:|---:|---:|---:|"]
    for key in keys:
        def mean(metric):
            row = lookup.get((*key, metric))
            return row["mean"] if row is not None else 0
        starts = lookup[*key, "origin_executions"]
        values = [mean(metric) for metric in ("attempted", "http_200", "http_403", "http_500", "http_503", "client_drops")]
        lines.append(f"| {key[0]} | {key[1]} / {key[2]} | " + " | ".join(f"{value:.1f}" for value in values)
                     + f" | {starts['mean']:.1f} [{starts['min']}, {starts['max']}] | {lookup[*key, 'origin_peak']['max']} |")
    lines += ["", "</details>", "", "<details>", "<summary>CPU and latency — all workloads and outcomes</summary>", "", "### CPU and latency", "",
              "Origin CPU is whole-process seconds per 1,000 attempted requests: mean ± sample SD.",
              "Latency is the median of trial percentiles in milliseconds; p99 includes the full trial range.",
              "Rejections are included. The CSV also retains per-status latency, CPU per successful response, throughput, queue and RSS.", "",
              "| Cell | Mode / state | Origin CPU s / 1,000 attempted | p50 ms | p99 ms [min, max] |",
              "|---|---|---:|---:|---:|"]
    for key in keys:
        cpu, p50, p99 = [lookup[*key, metric] for metric in ("origin_cpu_s_per_1000_attempted", "latency_p50_ms", "latency_p99_ms")]
        lines.append(f"| {key[0]} | {key[1]} / {key[2]} | {cpu['mean']:.4f} ± {cpu['stdev']:.4f} | {p50['median']:.3f} | {p99['median']:.3f} [{p99['min']:.3f}, {p99['max']:.3f}] |")
    lines += ["", "</details>", "", "<details>", "<summary>Mechanism costs — configuration, matching and keys</summary>", "", "### Mechanism examples", "",
              "Time and allocation are means of ten fork means; ± is sample SD across forks.",
              "These rows cover full configuration loading, last-match routing, order-independent query projection and the fixed-path comparison.",
              "Every method and parameter combination, including misses, overlaps, ordered queries and aliases, remains in the CSV and raw JSON.", "",
              "| Method | Parameters | µs / operation | Bytes / operation |", "|---|---|---:|---:|"]
    allocations = {(row["benchmark"], row["params"]): row for row in micro if row["metric"] == "allocation"}
    for row in micro:
        if row["metric"] != "average_time":
            continue
        method, params = row["benchmark"].split(".")[-1], json.loads(row["params"])
        selected = ((row["suite"] == "configuration" and method == "completeLoadAndCompile")
                    or (row["suite"] == "routes" and params["position"] == "LAST")
                    or (row["suite"] == "keys" and method == "projectAndCanonicalize" and params["ordered"] == "false" and params["queryCase"] in ("SELECTED", "NOISE", "DUPLICATES"))
                    or (row["suite"] == "programmatic" and params["position"] == "LAST"))
        if selected:
            allocation = allocations[row["benchmark"], row["params"]]
            label = ", ".join(f"{key}={value}" for key, value in sorted(params.items()))
            lines.append(f"| {method} | {label} | {row['mean']:.3f} ± {row['stdev']:.3f} | {allocation['mean']:.1f} |")
    lines += ["", "</details>", "", "<!-- generated-results:end -->", ""]
    return "\n".join(lines)


def readme_results(system, protocol):
    lookup = {(row["cell"], row["mode"], row["phase"], row["metric"]): row for row in system}
    cell = next(cell for cell in protocol["cells"] if cell["name"] == "same-64")
    lines = ["<!-- generated-readme-results:start -->",
             f"For {cell['count']} requests naming one operation at concurrency {cell['concurrency']}, "
             f"across {protocol['measured_repetitions']} measured repetitions,",
             f"both gateway strategies used active capacity {cell['active']} and queue capacity {cell['queued']}:", "",
             "| Path | Origin executions, mean [min, max] | Maximum actual origin concurrency |",
             "|---|---:|---:|"]
    for mode, label in (("direct", "Direct origin"), ("bounded", "`BOUNDED_COMPUTE`"),
                        ("materialize", "`MATERIALIZE`")):
        starts = lookup[cell["name"], mode, "cold", "origin_executions"]
        peak = lookup[cell["name"], mode, "cold", "origin_peak"]["max"]
        lines.append(f"| {label} | {starts['mean']:g} [{starts['min']}, {starts['max']}] | {peak} |")
    direct, bounded = [lookup["same-1", mode, "cold", "latency_p99_ms"]["median"]
                       for mode in ("direct", "bounded")]
    lines += ["", "There is a latency cost. In the separate sequential-request comparison,",
              f"the median of per-trial p99 latencies was **{bounded:.1f} ms** through `BOUNDED_COMPUTE`",
              f"versus **{direct:.1f} ms** directly.", "",
              "<!-- generated-readme-results:end -->", ""]
    return "\n".join(lines)


def result_charts(system, protocol):
    lookup = {(row["cell"], row["mode"], row["phase"], row["metric"]): row for row in system}
    cells = {cell["name"]: cell for cell in protocol["cells"]}
    modes = (("direct", "Direct origin", "#52525b"),
             ("bounded", "BOUNDED_COMPUTE", "#1d4ed8"),
             ("materialize", "MATERIALIZE", "#047857"))
    names = ("same-1", "same-4", "same-16", "same-64")
    counts = {cells[name]["count"] for name in names}
    if len(counts) != 1 or any(cells[name]["active"] != 1 or cells[name]["queued"] != 0 for name in names):
        raise ValueError("charts require equal request counts and active=1, queued=0")
    count = counts.pop()
    repetitions = protocol["measured_repetitions"]
    for name in names:
        for mode, _, _ in modes:
            for metric, expected in (("attempted", count), ("http_200", count),
                                     ("request_errors", 0), ("client_drops", 0)):
                row = lookup[name, mode, "cold", metric]
                if row["min"] != expected or row["max"] != expected:
                    raise ValueError("equivalent-request charts require all requests to complete with HTTP 200")

    def text(x, y, value, size=14, color="#18181b", anchor="start"):
        return (f'<text x="{x:g}" y="{y:g}" font-size="{size}" fill="{color}" '
                f'text-anchor="{anchor}">{escape(str(value))}</text>')

    def line(x1, y1, x2, y2, color="#d4d4d8", width=1):
        return f'<line x1="{x1:g}" y1="{y1:g}" x2="{x2:g}" y2="{y2:g}" stroke="{color}" stroke-width="{width}"/>'

    def svg(title, description, height, elements):
        return '\n'.join([
            f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 880 {height}" role="img" aria-labelledby="title desc">',
            f'<title id="title">{escape(title)}</title>', f'<desc id="desc">{escape(description)}</desc>',
            f'<rect width="880" height="{height}" fill="#fff"/>',
            '<g font-family="system-ui, -apple-system, Segoe UI, sans-serif">',
            *elements, '</g>', '</svg>', ''])

    work = [text(28, 34, "Origin executions as equivalent demand overlaps", 21),
            text(28, 60, f"{count} requests per trial · {repetitions} trials · gateway active 1 / queued 0"),
            text(28, 83, "Points: mean executions. Whiskers and labels: full trial range.", 13)]
    max_work = max(lookup[name, mode, "cold", "origin_executions"]["max"] for name in names for mode, _, _ in modes)
    ceiling = max(1, math.ceil(max_work / 64) * 64)
    xs = [110 + index * 220 for index in range(len(names))]
    y = lambda value: 372 - 228 * value / ceiling
    for index in range(5):
        value = index * ceiling / 4
        work += [line(90, y(value), 800, y(value)), text(78, y(value) + 5, f"{value:g}", anchor="end")]
    work += [text(90, 121, "Executions / trial", 13)]
    descriptions = []
    for mode, label, color in modes:
        points = [(x, lookup[name, mode, "cold", "origin_executions"]) for x, name in zip(xs, names)]
        coordinates = " ".join(f"{x:g},{y(row['mean']):g}" for x, row in points)
        work.append(f'<polyline points="{coordinates}" fill="none" stroke="{color}" stroke-width="2.5"/>')
        for (x, row), name in zip(points, names):
            low, high, mean = row["min"], row["max"], row["mean"]
            value_label = f"{mean:g}" if low == high else f"{mean:g} [{low:g}, {high:g}]"
            work += [line(x, y(low), x, y(high), color, 2),
                     line(x - 5, y(low), x + 5, y(low), color, 2),
                     line(x - 5, y(high), x + 5, y(high), color, 2),
                     f'<circle cx="{x:g}" cy="{y(mean):g}" r="4" fill="{color}"/>',
                     text(x, y(mean) + (24 if mode == "materialize" else 23 if name == "same-1" and mode == "bounded" else -12),
                          value_label, 14, color, "middle")]
            descriptions.append(f"{label}, concurrency {cells[name]['concurrency']}: mean {mean:g}, range {low:g} to {high:g}.")
    for x, name in zip(xs, names):
        work.append(text(x, 424, cells[name]["concurrency"], anchor="middle"))
    work.append(text(445, 452, "Client concurrency (measured settings)", anchor="middle"))
    for index, (_, label, color) in enumerate(modes):
        x = 50 + 275 * index
        work += [line(x, 484, x + 23, 484, color, 3), text(x + 31, 489, label, 13)]
    work.append(text(28, 523, "MATERIALIZE starts with the workload key absent, then reuses its published result.", 13))

    latency = [text(28, 34, "Latency at sequential and concurrent demand", 21),
               text(28, 60, f"{count} equivalent requests per trial · {repetitions} trials · all returned HTTP 200"),
               text(28, 83, "Point: median of trial p99s. Whisker: full range of trial p99s. Linear axes start at zero.", 13)]
    latency_descriptions = []
    for index, name in enumerate(("same-1", "same-64")):
        top = 126 + index * 226
        concurrency = cells[name]["concurrency"]
        latency.append(text(28, top, f"Concurrency {concurrency}" + (" — sequential" if concurrency == 1 else ""), 17))
        rows = [lookup[name, mode, "cold", "latency_p99_ms"] for mode, _, _ in modes]
        maximum = max(row["max"] for row in rows)
        step = 10 ** math.floor(math.log10(maximum)) if maximum else 1
        ceiling = max(1, math.ceil(maximum / step)) * step
        x = lambda value: 230 + 335 * value / ceiling
        for tick in range(5):
            value = ceiling * tick / 4
            latency += [line(x(value), top + 18, x(value), top + 138),
                        text(x(value), top + 159, f"{value:g}", 12, anchor="middle")]
        latency.append(text(400, top + 183, "p99 latency (ms)", 13, anchor="middle"))
        for rank, ((_, label, color), row) in enumerate(zip(modes, rows)):
            row_y = top + 35 + rank * 45
            low, high, median = row["min"], row["max"], row["median"]
            latency += [text(28, row_y + 5, label, 14, color),
                        line(x(low), row_y, x(high), row_y, color, 2),
                        line(x(low), row_y - 6, x(low), row_y + 6, color, 2),
                        line(x(high), row_y - 6, x(high), row_y + 6, color, 2),
                        f'<circle cx="{x(median):g}" cy="{row_y:g}" r="5" fill="{color}"/>',
                        text(600, row_y + 5, f"{median:.1f} [{low:.1f}, {high:.1f}] ms", 14, color)]
            latency_descriptions.append(f"{label}, concurrency {concurrency}: median trial p99 {median:.3f} ms, range {low:.3f} to {high:.3f} ms.")
    latency += [text(28, 573, "Panels use different scales. MATERIALIZE includes initial production and subsequent hits.", 13),
                text(28, 596, "These are complete request paths on the same shared host; component costs are not isolated.", 13)]
    return {
        "origin-executions.svg": svg("Origin executions by client concurrency", " ".join(descriptions), 546, work),
        "latency.svg": svg("Request latency tradeoff", " ".join(latency_descriptions), 620, latency),
    }


def write_csv(path, rows):
    with path.open("x", encoding="utf-8", newline="") as stream:
        writer = csv.DictWriter(stream, fieldnames=list(rows[0]), lineterminator="\n")
        writer.writeheader()
        writer.writerows(rows)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--evidence", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True, help="new directory; never overwritten")
    args = parser.parse_args()
    evidence = Evidence(args.evidence)
    try:
        verified_files = evidence.verify_archive()
        completion = evidence.json("system/completed.json")
        raw = evidence.data("system/trials.json")
        raw_hash = hashlib.sha256(raw).hexdigest()
        if completion["kind"] != "system-publication" or completion["hashes"]["trials.json"] != raw_hash:
            raise ValueError("system data does not match a completed publication campaign")
        trials, protocol = json.loads(raw), evidence.json("system/protocol.json")
        if len(trials) != completion["trials"] or protocol["measured_repetitions"] != 10:
            raise ValueError("incomplete system campaign")
        groups = system_groups(trials, protocol)
        system = system_summary(groups)
        micro, micro_hashes = micro_summary(evidence)
        args.output.mkdir(parents=True, exist_ok=False)
        write_csv(args.output / "system.csv", system)
        write_csv(args.output / "micro.csv", micro)
        (args.output / "tables.md").write_text(tables(system, micro, protocol), encoding="utf-8", newline="\n")
        (args.output / "readme-results.md").write_text(readme_results(system, protocol), encoding="utf-8", newline="\n")
        charts = result_charts(system, protocol)
        for name, content in charts.items():
            (args.output / name).write_text(content, encoding="utf-8", newline="\n")
        metadata = {"head": evidence.json("system/environment.json")["head"],
                    "system_trials_sha256": raw_hash, "micro_raw_sha256": micro_hashes,
                    "archive_files_verified": verified_files, "system_groups": len(groups),
                    "micro_cells": len(micro) // 2, "outlier_removal": False,
                    "summary_units": "independent trial or fork mean; latency summaries are distributions of per-trial percentiles",
                    "script_sha256": hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
                    "charts_sha256": {name: hashlib.sha256(content.encode("utf-8")).hexdigest()
                                      for name, content in charts.items()}}
        (args.output / "provenance.json").write_text(json.dumps(metadata, indent=2) + "\n", encoding="utf-8", newline="\n")
        print(json.dumps(metadata, indent=2))
    finally:
        evidence.close()


if __name__ == "__main__":
    main()
