import ctypes
from ctypes import wintypes
import hashlib
import json
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import MagicMock, patch

import system_provenance as provenance


class SystemProvenanceTest(unittest.TestCase):
    def test_committed_and_checkout_lock_bytes_are_distinct_and_untracked_files_are_ignored(self):
        committed = b"module:dependency:1.0=runtime\n"
        checked_out = committed.replace(b"\n", b"\r\n")
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "nested module").mkdir()
            names = ["gradle.lockfile", "nested module/gradle.lockfile"]
            for name in names:
                (root / name).write_bytes(checked_out)
            (root / "untracked").mkdir()
            (root / "untracked/gradle.lockfile").write_text("not a committed input", encoding="utf-8")
            responses = {("rev-parse", "HEAD"): "commit", ("ls-tree", "-r", "--name-only", "-z", "commit"):
                         "gradle.lockfile\0nested module/gradle.lockfile\0ordinary.txt\0"}
            with patch.object(provenance, "REPOSITORY", root), patch.object(provenance, "git", side_effect=lambda *args: responses[args]), patch.object(provenance.subprocess, "run", return_value=subprocess.CompletedProcess([], 0, stdout=committed)) as run:
                manifest = provenance.dependency_locks()
                self.assertEqual({name: {"committed_sha256": hashlib.sha256(committed).hexdigest(),
                                        "working_tree_sha256": hashlib.sha256(checked_out).hexdigest()}
                                  for name in names}, manifest["files"])
                self.assertEqual("commit", manifest["commit"])
                self.assertEqual(["commit:" + name for name in names], [call.args[0][-1] for call in run.call_args_list])
                provenance.verify_dependencies(manifest)
                (root / names[0]).write_bytes(b"changed\n")
                with self.assertRaisesRegex(AssertionError, "dependency locks changed"):
                    provenance.verify_dependencies(manifest)
                (root / names[0]).unlink()
                with self.assertRaises(FileNotFoundError):
                    provenance.dependency_locks()
        with patch.object(provenance, "git", return_value=""):
            with self.assertRaisesRegex(AssertionError, "no committed Gradle"):
                provenance.dependency_locks()

    def test_linux_mount_probe_preserves_nested_mount_details_and_raw_evidence(self):
        mount = {"target": "/mnt/evidence space", "source": "/dev/sdb[/sub volume]", "fstype": "btrfs",
                 "options": "rw,noatime,subvol=/sub volume", "fsroot": "/sub volume", "maj:min": "8:16"}
        raw = json.dumps({"filesystems": [mount]})
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "evidence space"
            path.mkdir()
            with patch.object(provenance.sys, "platform", "linux"), patch.object(provenance.subprocess, "run", return_value=subprocess.CompletedProcess([], 0, raw, "")) as run:
                record = provenance.filesystem(path)
            self.assertIsNone(record["error"])
            self.assertEqual(str(path.resolve()), record["resolved_path"])
            self.assertEqual("btrfs", record["filesystem_type"])
            self.assertEqual(mount["target"], record["mount_point"])
            self.assertEqual(mount, record["mount"])
            self.assertEqual(raw, record["probe"]["stdout"])
            command = run.call_args.args[0]
            self.assertEqual("/proc/self/mountinfo", command[command.index("--tab-file") + 1])
            self.assertEqual(str(path.resolve()), command[command.index("--target") + 1])
            self.assertEqual("C", run.call_args.kwargs["env"]["LC_ALL"])

    def test_missing_failed_or_malformed_mount_probes_keep_an_explicit_error(self):
        results = [FileNotFoundError("findmnt unavailable"), subprocess.TimeoutExpired(["findmnt"], 10),
                   subprocess.CompletedProcess([], 1, "", "permission denied"),
                   subprocess.CompletedProcess([], 0, "not json", ""),
                   subprocess.CompletedProcess([], 0, '{"filesystems": []}', ""),
                   subprocess.CompletedProcess([], 0, '{"filesystems": [{"fstype": "ext4"}]}', ""),
                   subprocess.CompletedProcess([], 0, '{"filesystems": [null]}', "")]
        with tempfile.TemporaryDirectory() as directory, patch.object(provenance.sys, "platform", "linux"):
            for result in results:
                with self.subTest(result=result), patch.object(provenance.subprocess, "run", side_effect=[result]):
                    record = provenance.filesystem(Path(directory))
                    self.assertIsNotNone(record["error"])
                    self.assertIsNone(record["filesystem_type"])
                    self.assertIsNotNone(record["probe"])
                    if isinstance(result, subprocess.CompletedProcess):
                        self.assertEqual(result.returncode, record["probe"]["exit_code"])
                        self.assertEqual(result.stderr, record["probe"]["stderr"])

    def test_windows_probe_uses_containing_volume_not_drive_letter_guess(self):
        kernel = MagicMock()

        def get_path(path, target, size):
            target.value = "C:\\mounted-volume\\"
            return 1

        def get_info(mount, label, size, serial, maximum, flags, filesystem, filesystem_size):
            self.assertEqual("C:\\mounted-volume\\", mount)
            ctypes.cast(serial, wintypes.LPDWORD).contents.value = 42
            ctypes.cast(flags, wintypes.LPDWORD).contents.value = 128
            filesystem.value = "ReFS"
            return 1

        kernel.GetVolumePathNameW.side_effect = get_path
        kernel.GetVolumeInformationW.side_effect = get_info
        with patch.object(provenance.ctypes, "WinDLL", create=True, return_value=kernel):
            record = provenance.windows_volume(Path("fixture"))
            self.assertEqual({"mount_point": "C:\\mounted-volume\\", "filesystem_type": "ReFS",
                              "volume_serial": "0000002a", "filesystem_flags": 128}, record)
            for operation in [kernel.GetVolumeInformationW, kernel.GetVolumePathNameW]:
                operation.side_effect = None
                operation.return_value = 0
                with patch.object(provenance.ctypes, "get_last_error", create=True, return_value=5), patch.object(provenance.ctypes, "WinError", create=True, side_effect=lambda code: OSError(code, "volume unavailable")):
                    with self.assertRaisesRegex(OSError, "volume unavailable"):
                        provenance.windows_volume(Path("fixture"))

    def test_publication_fails_after_retaining_missing_filesystem_evidence(self):
        with tempfile.TemporaryDirectory() as directory, patch.object(provenance.sys, "platform", "unsupported"):
            root = Path(directory)
            locations = {"evidence": root, "store": root / "missing"}
            output = root / "publication.json"
            with self.assertRaisesRegex(AssertionError, "publication requires filesystem provenance"):
                provenance.capture_filesystems(output, locations, required=True)
            evidence = json.loads(output.read_text(encoding="utf-8"))
            self.assertEqual({"evidence", "store"}, set(evidence["locations"]))
            self.assertIn("unsupported", evidence["locations"]["evidence"]["error"])
            self.assertIn("FileNotFoundError", evidence["locations"]["store"]["error"])
            self.assertIsNone(evidence["locations"]["store"]["resolved_path"])
            provenance.capture_filesystems(root / "control.json", locations, required=False)
            with self.assertRaises(FileExistsError):
                provenance.capture_filesystems(output, locations, required=False)

    def test_native_volume_probe_records_actual_existing_locations(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "filesystem.json"
            provenance.capture_filesystems(output, {"evidence": Path(directory), "repository": provenance.REPOSITORY}, required=True)
            evidence = json.loads(output.read_text(encoding="utf-8"))
            for record in evidence["locations"].values():
                self.assertIsNone(record["error"])
                self.assertTrue(record["filesystem_type"])
                self.assertTrue(record["mount_point"])
                self.assertTrue(Path(record["resolved_path"]).exists())


if __name__ == "__main__":
    unittest.main()
