import copy
import contextlib
import io
import hashlib
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import microbench


def result():
    return {"benchmark": "fixture", "params": {"routeCount": "16"}, "mode": "avgt",
            "forks": 2, "measurementIterations": 2,
            "primaryMetric": {"scoreUnit": "us/op", "rawData": [[1, 3], [5, 7]]}}


class EvidenceTest(unittest.TestCase):
    def test_artifact_digest_covers_the_entire_stream(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory, "artifact")
            contents = b"prefix" + b"data" * 300_000 + b"suffix"
            path.write_bytes(contents)
            self.assertEqual(hashlib.sha256(contents).hexdigest(), microbench.digest(path))

    def test_forks_are_summary_units_and_all_iterations_are_retained(self):
        raw = result()
        before = copy.deepcopy(raw)
        summary = microbench.summarize([raw])[0]
        self.assertEqual(4, summary["mean_of_fork_means"])
        self.assertEqual(4, summary["median_of_fork_means"])
        self.assertAlmostEqual(8 ** 0.5, summary["stdev_of_fork_means"])
        self.assertEqual(2, summary["min_fork_mean"])
        self.assertEqual(6, summary["max_fork_mean"])
        self.assertEqual(raw, before)
        raw["forks"] = 1
        raw["primaryMetric"]["rawData"] = [[1, 3]]
        self.assertIsNone(microbench.summarize([raw])[0]["stdev_of_fork_means"])

    def test_invalid_incomplete_or_duplicate_results_fail(self):
        for raw in [[], [result(), result()]]:
            with self.assertRaises(ValueError):
                microbench.summarize(raw)
        for field, value in [("forks", 3), ("measurementIterations", 3), ("mode", "thrpt")]:
            raw = result()
            raw[field] = value
            with self.assertRaises(ValueError):
                microbench.summarize([raw])
        for field, value in [("rawData", []), ("rawData", [[], []]),
                             ("rawData", [[1, float("nan")], [5, 7]]),
                             ("rawData", [[1, True], [5, 7]]),
                             ("rawData", [[1, -1], [5, 7]]),
                             ("rawData", [[1, "NaN"], [5, 7]]), ("scoreUnit", "ops/s")]:
            raw = result()
            raw["primaryMetric"][field] = value
            with self.assertRaises(ValueError):
                microbench.summarize([raw])

    def test_smoke_and_publication_commands_are_distinct(self):
        smoke = microbench.jmh_arguments("all", True, Path("raw.json"))
        measured = microbench.jmh_arguments("all", False, Path("raw.json"))
        self.assertEqual("1", smoke[smoke.index("-f") + 1])
        self.assertEqual("0", smoke[smoke.index("-wi") + 1])
        self.assertEqual("10", measured[measured.index("-f") + 1])
        self.assertEqual("5", measured[measured.index("-wi") + 1])
        self.assertEqual("5", measured[measured.index("-i") + 1])
        self.assertNotIn("-p", measured)
        self.assertIn("-prof", measured)
        for suite, name in microbench.SUITES.items():
            self.assertIn(name, microbench.jmh_arguments(suite, True, Path("raw.json"))[1])

    def test_evidence_is_never_overwritten(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory, "metadata.json")
            microbench.write_json(path, {"source": "original"})
            original_digest = microbench.digest(path)
            with self.assertRaises(FileExistsError):
                microbench.write_json(path, {"source": "replacement"})
            self.assertEqual({"source": "original"}, json.loads(path.read_text(encoding="utf-8")))
            self.assertEqual(original_digest, microbench.digest(path))

    def test_publication_refuses_unmerged_or_uncontrolled_source(self):
        source = {("rev-parse", "HEAD"): "accepted", ("status", "--porcelain"): "",
                  ("branch", "--show-current"): "main", ("rev-parse", "origin/main"): "accepted"}
        cases = [(key, replacement) for key, replacement in [
            (("status", "--porcelain"), " M source.java"),
            (("branch", "--show-current"), "feature"),
            (("rev-parse", "origin/main"), "different")]]
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory, "evidence")
            for key, replacement in cases:
                values = dict(source)
                values[key] = replacement
                with self.subTest(source=values), patch.object(microbench, "git", side_effect=lambda *args: values[args]), contextlib.redirect_stderr(io.StringIO()):
                    with self.assertRaises(SystemExit) as error:
                        microbench.main(["--java", "java", "--output", str(output)])
                    self.assertEqual(2, error.exception.code)
                    self.assertFalse(output.exists())
            with patch.object(microbench, "git", side_effect=lambda *args: source[args]), contextlib.redirect_stderr(io.StringIO()):
                with self.assertRaises(SystemExit):
                    microbench.main(["--java", "java", "--output", str(output)])
                self.assertFalse(output.exists())

    def test_runner_refuses_existing_evidence_directory(self):
        with tempfile.TemporaryDirectory() as directory, patch.object(microbench, "git", return_value="unchanged"):
            marker = Path(directory, "old-result")
            marker.write_text("historical evidence", encoding="utf-8")
            with self.assertRaises(FileExistsError):
                microbench.main(["--smoke", "--java", "java", "--output", directory])
            self.assertEqual("historical evidence", marker.read_text(encoding="utf-8"))


if __name__ == "__main__":
    unittest.main()
