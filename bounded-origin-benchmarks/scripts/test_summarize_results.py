import hashlib
import json
from pathlib import Path
import tempfile
import unittest
import xml.etree.ElementTree as ET
import zipfile

from summarize_results import Evidence, distribution, result_charts, system_groups


class ResultsTest(unittest.TestCase):
    def test_retains_outlier_and_distinguishes_missing_from_zero(self):
        result = distribution([1, 1, 100, None, 0])
        self.assertEqual(4, result["available_repetitions"])
        self.assertEqual(25.5, result["mean"])
        self.assertEqual(1, result["median"])
        self.assertEqual(0, result["min"])
        self.assertEqual(100, result["max"])
        self.assertAlmostEqual(49.6689037528, result["stdev"])
        self.assertIsNone(distribution([None])["mean"])

    def test_rejects_invalid_measurements(self):
        for value in (True, -1, float("nan"), float("inf")):
            with self.subTest(value=value), self.assertRaises(ValueError):
                distribution([value])

    def test_excludes_only_declared_warmups_and_requires_all_phases(self):
        protocol = {"warmup_runs": 2, "measured_repetitions": 2,
                    "cells": [{"name": "reuse", "workload": "reuse"}]}
        rows = [{"cell": "reuse", "mode": mode, "phase": phase, "repetition": repetition,
                 "measured": repetition >= 2}
                for mode in ("direct", "bounded", "materialize")
                for phase in (("cold", "warm", "restart") if mode == "materialize" else ("cold",))
                for repetition in range(4)]
        groups = system_groups(rows, protocol)
        self.assertEqual(5, len(groups))
        self.assertTrue(all([row["repetition"] for row in group] == [2, 3] for group in groups.values()))
        for incomplete in (rows[1:], rows[:-1], rows + [rows[0]]):
            with self.assertRaises(ValueError):
                system_groups(incomplete, protocol)
        changed = [dict(row) for row in rows]
        changed[2]["measured"] = False
        with self.assertRaises(ValueError):
            system_groups(changed, protocol)

    def test_archive_checks_every_included_file(self):
        with tempfile.TemporaryDirectory() as directory:
            expected = hashlib.sha256(b"original").hexdigest()
            for name, body in (("intact", b"original"), ("altered", b"changed")):
                path = Path(directory, name + ".zip")
                with zipfile.ZipFile(path, "w") as archive:
                    archive.writestr("raw.json", body)
                    archive.writestr("evidence-manifest.json", json.dumps({"files": {"raw.json": expected}}))
                evidence = Evidence(path)
                try:
                    if name == "intact":
                        self.assertEqual(1, evidence.verify_archive())
                    else:
                        with self.assertRaises(ValueError):
                            evidence.verify_archive()
                finally:
                    evidence.close()

    @staticmethod
    def chart_data():
        protocol = {"measured_repetitions": 10, "cells": [
            {"name": f"same-{concurrency}", "concurrency": concurrency, "count": 257,
             "active": 1, "queued": 0} for concurrency in (1, 4, 16, 64)]}
        rows = []
        for cell in protocol["cells"]:
            for mode in ("direct", "bounded", "materialize"):
                for metric, values in (
                    ("attempted", [257] * 10), ("http_200", [257] * 10),
                    ("client_drops", [0] * 10), ("request_errors", [0] * 10),
                    ("origin_executions", [1] * 9 + [101]),
                    ("latency_p99_ms", [2] * 9 + [102]),
                ):
                    rows.append({"cell": cell["name"], "mode": mode, "phase": "cold",
                                 "metric": metric, **distribution(values)})
        return rows, protocol

    def test_charts_use_measured_counts_and_trial_percentiles_with_outliers(self):
        rows, protocol = self.chart_data()
        charts = result_charts(rows, protocol)
        work = ET.fromstring(charts["origin-executions.svg"])
        latency = ET.fromstring(charts["latency.svg"])
        work_text, latency_text = " ".join(work.itertext()), " ".join(latency.itertext())
        self.assertIn("257 requests per trial", work_text)
        self.assertIn("11 [1, 101]", work_text)
        self.assertIn("2.0 [2.0, 102.0] ms", latency_text)
        self.assertNotIn("12.0 [2.0, 102.0]", latency_text)
        self.assertIn("Linear axes start at zero", latency_text)
        self.assertIn("Panels use different scales", latency_text)
        self.assertEqual(12, len(work.findall(".//{*}circle")))
        self.assertEqual(6, len(latency.findall(".//{*}circle")))

    def test_charts_reject_changed_workload_or_incomplete_delivery(self):
        for change in ("count", "budget", "drop", "error", "status"):
            rows, protocol = self.chart_data()
            if change == "count":
                protocol["cells"][0]["count"] = 258
            elif change == "budget":
                protocol["cells"][0]["queued"] = 1
            else:
                metric = {"drop": "client_drops", "error": "request_errors", "status": "http_200"}[change]
                next(row for row in rows if row["metric"] == metric)["max"] += 1
            with self.subTest(change=change), self.assertRaises(ValueError):
                result_charts(rows, protocol)


if __name__ == "__main__":
    unittest.main()
