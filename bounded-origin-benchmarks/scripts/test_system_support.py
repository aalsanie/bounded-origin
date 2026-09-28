import base64
import copy
import json
import http.client
from pathlib import Path
import tempfile
import threading
import unittest
from unittest.mock import patch, MagicMock

import system_support as support


def event(kind, identifier, active, clock, cpu=0):
    return {"event": kind, "id": identifier, "active": active, "monotonic_ns": clock,
            "cpu_ns": cpu, "target_base64": base64.b64encode(b"/work/one").decode("ascii")}


def outcome(index=0, status=200, latency=10, body="aaa"):
    return {"ordinal": index, "target": "/work/one", "identity": "/work/one", "status": status,
            "latency_ns": latency, "scheduler_lag_ns": 0, "client_capacity_drop": False,
            "error": None, "body_bytes": 64, "body_sha256": body}


class SystemEvidenceTest(unittest.TestCase):
    def test_ambient_java_options_cannot_override_declared_trial_resources(self):
        keys = ("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS", "JAVA_OPTS", "BOUNDED_ORIGIN_OPTS")
        ambient = {key: "unexpected-options" for key in keys}
        with patch.dict(support.os.environ, ambient):
            actual = support.runtime_environment("runtime/bin/java")
            self.assertTrue(all(key not in actual for key in keys))
            self.assertTrue(all(support.os.environ[key] == value for key, value in ambient.items()))
            self.assertEqual(str(Path("runtime").resolve()), actual["JAVA_HOME"])

    def test_failed_body_validation_and_partial_transport_are_kept_as_raw_outcomes(self):
        connection = MagicMock()
        response = connection.getresponse.return_value
        response.status = 200
        response.read.return_value = b"wrong-body"
        with patch.object(support.http.client, "HTTPConnection", return_value=connection):
            invalid = support.request(1, "/work/one", expected_bytes=10)
            self.assertEqual(200, invalid["status"])
            self.assertTrue(invalid["error"].startswith("ResponseValidationError:"))
            self.assertEqual(10, invalid["body_bytes"])
            self.assertEqual(1, support.summarize_requests([invalid])["response_validation_errors"])
            self.assertEqual(0, support.summarize_requests([invalid])["transport_errors"])
            response.read.side_effect = http.client.IncompleteRead(b"partial", 100)
            partial = support.request(1, "/work/one")
            self.assertIsNone(partial["status"])
            self.assertEqual(7, partial["body_bytes"])
            self.assertTrue(partial["error"].startswith("IncompleteRead:"))
            self.assertEqual(0, support.summarize_requests([partial])["completed_http"])
            self.assertEqual(1, support.summarize_requests([partial])["transport_errors"])
        self.assertEqual(2, connection.close.call_count)

    def test_independent_events_reconstruct_outstanding_work_and_cpu(self):
        rows = [event("start", 1, 1, 1), event("start", 2, 2, 2),
                event("finish", 1, 1, 3, 13), event("finish", 2, 0, 4, 17)]
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory, "origin.jsonl")
            support.write_rows(path, rows)
            before = path.read_bytes()
            self.assertEqual({"starts": 2, "completed": 2, "active": 0, "peak": 2, "work_cpu_ns": 30},
                             support.journal_summary(path))
            self.assertEqual(before, path.read_bytes())
            path.write_text(json.dumps(rows[0]) + "\n", encoding="utf-8")
            self.assertEqual(1, support.journal_summary(path)["active"])

    def test_impossible_or_corrupt_origin_history_is_not_summarized_as_success(self):
        cases = [[event("finish", 1, 0, 1, 5)], [event("start", 1, 0, 1)],
                 [event("start", 1, 1, 2), event("finish", 1, 0, 1, 5)],
                 [event("start", 1, 1, 1), event("start", 1, 2, 2)],
                 [event("start", 1, 1, 1), event("finish", 1, 0, 2, -1)],
                 [event("start", 1, 1, 1, 5)], [event("unknown", 1, 0, 1)]]
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory, "origin.jsonl")
            for rows in cases:
                with self.subTest(rows=rows):
                    path.write_text("".join(json.dumps(row) + "\n" for row in rows), encoding="utf-8")
                    with self.assertRaises((AssertionError, ValueError)):
                        support.journal_summary(path)

    def test_summary_retains_rejections_errors_and_client_capacity_drops(self):
        rows = [outcome(), outcome(1, 503, 20), outcome(2, None, 30),
                {"ordinal": 3, "client_capacity_drop": True}]
        rows[2]["error"] = "ConnectionResetError"
        original = copy.deepcopy(rows)
        summary = support.summarize_requests(rows)
        self.assertEqual((4, 3, 2, 1, 1), tuple(summary[key] for key in
                         ["offered", "attempted", "completed_http", "client_capacity_drops", "transport_errors"]))
        self.assertEqual({"200": 1, "503": 1}, summary["statuses"])
        self.assertEqual({"p50": 20, "p95": 30, "p99": 30}, summary["latency_ns"])
        self.assertEqual(10, summary["latency_by_status_ns"]["200"]["p99"])
        self.assertEqual(20, summary["latency_by_status_ns"]["503"]["p99"])
        self.assertEqual(30, summary["latency_by_status_ns"]["transport-error"]["p99"])
        self.assertEqual(original, rows)
        self.assertIsNone(support.summarize_requests([])["latency_ns"]["p99"])
        with self.assertRaisesRegex(AssertionError, "duplicate"):
            support.summarize_requests([outcome(), outcome()])
        with self.assertRaisesRegex(AssertionError, "different bytes"):
            support.summarize_requests([outcome(), outcome(1, body="bbb")])
        with self.assertRaisesRegex(AssertionError, "invalid latency"):
            support.summarize_requests([outcome(latency=-1)])

    def test_fixture_identity_matches_selected_not_raw_query(self):
        self.assertEqual("/work/one|7:value:a|7:value:b", support.semantic_identity("/work/%6Fne?q=b&ignored=a&%71=a"))
        targets = ["/work/one", "/work/two", "/work/one?q", "/work/one?q=", "/work/one?q=a", "/work/one?q=a&q=a"]
        self.assertEqual(6, len({support.semantic_identity(target) for target in targets}))
        self.assertEqual(support.semantic_identity("/work/one?q=+"), support.semantic_identity("/work/one?q=%2B"))
        self.assertNotEqual(support.semantic_identity("/work/one?q=+"), support.semantic_identity("/work/one?q=%20"))

    def test_closed_loop_never_has_more_than_declared_client_work(self):
        entered, release = threading.Event(), threading.Event()
        lock = threading.Lock()
        counts = {"active": 0, "peak": 0}

        def send(port, target, **parameters):
            with lock:
                counts["active"] += 1
                counts["peak"] = max(counts["peak"], counts["active"])
                if counts["active"] == 2:
                    entered.set()
            if not release.wait(5):
                raise AssertionError("load phase did not release")
            with lock:
                counts["active"] -= 1
            return outcome(parameters["ordinal"])

        with patch.object(support, "request", side_effect=send):
            with support.ThreadPoolExecutor(max_workers=1) as controller:
                future = controller.submit(support.load, 0, ["/work/one"] * 6, 2, 64)
                try:
                    self.assertTrue(entered.wait(5))
                    self.assertEqual(2, counts["active"])
                finally:
                    release.set()
                rows, timing = future.result(timeout=5)
        self.assertEqual(2, counts["peak"])
        self.assertEqual(list(range(6)), [row["ordinal"] for row in rows])
        self.assertEqual(6, support.summarize_requests(rows)["attempted"])
        self.assertIsNone(timing["rate"])

    def test_open_loop_records_offers_rejected_by_client_capacity(self):
        entered, release = threading.Event(), threading.Event()
        offered = threading.Event()

        def targets():
            yield "/work/one"
            if not entered.wait(5):
                raise AssertionError("client did not start")
            yield "/work/two"
            yield "/work/three"
            offered.set()

        def send(port, target, **parameters):
            entered.set()
            if not release.wait(5):
                raise AssertionError("load phase did not release")
            return outcome(parameters["ordinal"])

        with patch.object(support, "request", side_effect=send):
            with support.ThreadPoolExecutor(max_workers=1) as controller:
                future = controller.submit(support.load, 0, targets(), 1, 64, 1_000_000)
                try:
                    self.assertTrue(offered.wait(5))
                finally:
                    release.set()
                rows, timing = future.result(timeout=5)
        summary = support.summarize_requests(rows)
        self.assertEqual(3, summary["offered"])
        self.assertEqual(1, summary["attempted"])
        self.assertEqual(2, summary["client_capacity_drops"])
        self.assertEqual(1_000_000, timing["rate"])

    def test_metrics_and_configuration_preserve_declared_limits(self):
        self.assertEqual({"gauge": 2.0}, support.parse_metrics(b"# comment\ngauge 2\n"))
        for malformed in [b"gauge nan\n", b"gauge 1\ngauge 2\n"]:
            with self.assertRaises(AssertionError):
                support.parse_metrics(malformed)
        yaml = support.configuration(Path("a'b"), 1, 2, 3, "MATERIALIZE", 2, 4, 1, 1, "executor")
        self.assertIn("origin.max-active: 2", yaml)
        self.assertIn("origin.max-queued: 4", yaml)
        self.assertIn("max-active: 1", yaml)
        self.assertIn("max-queued: 1", yaml)
        self.assertIn("origin.completion-contract: RESPONSE_COMPLETE", yaml)
        self.assertIn("a''b", yaml)
        self.assertIn("origin.max-execution-duration: PT1S", yaml)
        self.assertIn("origin.response-timeout: PT60S", yaml)
        self.assertIn("http.max-request-body-bytes: 0", yaml)
        with self.assertRaisesRegex(AssertionError, "request timeout cannot precede"):
            support.configuration(Path("fixture"), 1, 2, 3, "BOUNDED_COMPUTE", failure="request")

    def test_evidence_paths_are_exclusive(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory, "requests.jsonl")
            support.write_rows(path, [outcome()])
            with self.assertRaises(FileExistsError):
                support.write_rows(path, [])
            self.assertEqual(1, len(path.read_text(encoding="utf-8").splitlines()))


if __name__ == "__main__":
    unittest.main()
