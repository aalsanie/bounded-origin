import hashlib
import json
from pathlib import Path
import tempfile
import unittest
import zipfile

from summarize_results import Evidence, distribution, system_groups


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


if __name__ == "__main__":
    unittest.main()
