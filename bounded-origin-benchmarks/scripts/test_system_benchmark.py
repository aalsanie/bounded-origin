import argparse
import contextlib
import copy
import io
from pathlib import Path
import unittest
from unittest.mock import patch

import system_benchmark as benchmark
import system_support as support


class SystemCampaignTest(unittest.TestCase):
    def test_workloads_are_reproducible_and_noise_keeps_the_selected_operation(self):
        cells = benchmark.cells(False)
        self.assertEqual([1, 4, 16, 64], [cell["concurrency"] for cell in cells if cell["workload"] == "same"])
        self.assertEqual({0, 8}, {cell["queued"] for cell in cells if cell["workload"] == "unique"})
        self.assertEqual({100, 1000}, {cell["rate"] for cell in cells if cell["workload"] == "unique"})
        for cell in cells:
            sequence = benchmark.targets(cell, 13)
            self.assertEqual(cell["count"], len(sequence))
            self.assertEqual(sequence, benchmark.targets(cell, 13))
            identities = {support.semantic_identity(target) for target in sequence}
            if cell["workload"] in ("same", "noise"):
                self.assertEqual({"/work/one|7:value:a"}, identities)
            elif cell["workload"] == "reuse":
                self.assertEqual(16, len(identities))
            elif cell["workload"] != "low-mix":
                self.assertEqual(cell["count"], len(identities))
        noise = next(cell for cell in cells if cell["workload"] == "noise")
        self.assertEqual(noise["count"], len(set(benchmark.targets(noise, 13))))

    def test_independent_trials_not_requests_are_statistical_units(self):
        metrics = {key: value for key, value in [
            ("origin_cpu_seconds_per_1000_attempted", 1), ("origin_cpu_seconds_per_1000_successful", None),
            ("origin_compute_cpu_seconds_per_1000_attempted", 0.5),
            ("origin_work_amplification", 1), ("completed_http_per_second", 10), ("gateway_process_cpu_ns", None),
            ("gateway_rss_sampled_peak_bytes", 256), ("gateway_queue_sampled_peak", 2)]}
        base = {"cell": "fixture", "mode": "bounded", "phase": "cold", "measured": True, **metrics}
        rows = [{**base, "measured": False, "origin_cpu_seconds_per_1000_attempted": 999}, base,
                {**base, "origin_cpu_seconds_per_1000_attempted": 5}]
        original = copy.deepcopy(rows)
        summary = benchmark.aggregate(rows)
        cpu = next(row for row in summary if row["metric"] == "origin_cpu_seconds_per_1000_attempted")
        self.assertEqual(2, cpu["repetitions"])
        self.assertEqual(3, cpu["mean"])
        self.assertEqual(3, cpu["median"])
        self.assertAlmostEqual(8 ** .5, cpu["stdev"])
        missing = next(row for row in summary if row["metric"] == "gateway_process_cpu_ns")
        self.assertIsNone(missing["mean"])
        self.assertEqual(0, missing["available_repetitions"])
        self.assertEqual(original, rows)

    def test_cpu_units_keep_computation_and_whole_process_distinct(self):
        rates = benchmark.cpu_rates(2_000_000_000, 5_000_000_000, 1000, 500)
        self.assertEqual(5, rates["origin_cpu_seconds_per_1000_attempted"])
        self.assertEqual(10, rates["origin_cpu_seconds_per_1000_successful"])
        self.assertEqual(2, rates["origin_compute_cpu_seconds_per_1000_attempted"])
        self.assertIsNone(benchmark.cpu_rates(10, None, 1, 0)["origin_cpu_seconds_per_1000_attempted"])
        self.assertIsNone(benchmark.cpu_rates(10, 20, 1, 0)["origin_cpu_seconds_per_1000_successful"])
        self.assertTrue(all(value is None for value in benchmark.cpu_rates(10, 20, 0, 0).values()))

    def test_publication_refuses_dirty_unmerged_or_unallocated_runs(self):
        args = argparse.Namespace(smoke=False, ci_run="verified", origin_cpu=1, gateway_cpu=2, client_cpu=3)
        state = {("status", "--porcelain"): "", ("branch", "--show-current"): "main",
                 ("rev-parse", "HEAD"): "accepted", ("rev-parse", "origin/main"): "accepted"}
        parser = argparse.ArgumentParser()
        for key, replacement in [(("status", "--porcelain"), " M source"),
                                 (("branch", "--show-current"), "feature"), (("rev-parse", "origin/main"), "other")]:
            altered = {**state, key: replacement}
            with patch.object(benchmark, "git", side_effect=lambda *arguments: altered[arguments]), contextlib.redirect_stderr(io.StringIO()):
                with self.assertRaises(SystemExit):
                    benchmark.validate_publication(parser, args)
        with patch.object(benchmark, "git", side_effect=lambda *arguments: state[arguments]), patch.object(benchmark.sys, "platform", "linux"), patch.object(benchmark.os, "sched_getaffinity", create=True, return_value={1, 2, 3}), contextlib.redirect_stderr(io.StringIO()):
            benchmark.validate_publication(parser, args)
            for changed in [dict(ci_run=None), dict(client_cpu=2), dict(origin_cpu=None), dict(origin_cpu=4)]:
                invalid = argparse.Namespace(**{**vars(args), **changed})
                with self.assertRaises(SystemExit):
                    benchmark.validate_publication(parser, invalid)

    def test_linux_process_sample_counts_cpu_ticks_and_resident_pages(self):
        # Linux fields begin with state after the final parenthesis, even when comm contains ')'.
        fields = ["0"] * 22
        fields[0], fields[11], fields[12], fields[21] = "S", "11", "7", "23"
        with patch.object(support.sys, "platform", "linux"), patch.object(Path, "read_text", return_value="123 (worker) name) " + " ".join(fields)), patch.object(support.os, "sysconf", create=True, side_effect=lambda name: 100 if name == "SC_CLK_TCK" else 4096):
            self.assertEqual({"cpu_ns": 180_000_000, "rss_bytes": 23 * 4096}, support.process_sample(123))
        with patch.object(support.sys, "platform", "win32"):
            self.assertIsNone(support.process_sample(123))


if __name__ == "__main__":
    unittest.main()
