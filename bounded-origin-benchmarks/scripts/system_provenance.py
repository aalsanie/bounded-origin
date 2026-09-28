"""Dependency and filesystem evidence collected outside measured request phases."""

import ctypes
from ctypes import wintypes
import datetime
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys

from microbench import REPOSITORY, digest, git, write_json
from system_support import require


def dependency_locks():
    commit = git("rev-parse", "HEAD")
    names = sorted(name for name in git("ls-tree", "-r", "--name-only", "-z", commit).split("\0")
                   if name and Path(name).name == "gradle.lockfile")
    require(names, "no committed Gradle dependency locks found")
    files = {}
    for name in names:
        result = subprocess.run(["git", "-c", f"safe.directory={REPOSITORY}", "show", f"{commit}:{name}"],
                                cwd=REPOSITORY, stdout=subprocess.PIPE, stderr=subprocess.PIPE, check=True)
        files[name] = {"committed_sha256": hashlib.sha256(result.stdout).hexdigest(),
                       "working_tree_sha256": digest(REPOSITORY / name)}
    return {"commit": commit, "files": files}


def verify_dependencies(expected):
    require(dependency_locks() == expected, "dependency locks changed during build or campaign")


def windows_volume(path):
    kernel = ctypes.WinDLL("kernel32", use_last_error=True)
    volume_path = kernel.GetVolumePathNameW
    volume_path.argtypes = [wintypes.LPCWSTR, wintypes.LPWSTR, wintypes.DWORD]
    volume_path.restype = wintypes.BOOL
    volume_info = kernel.GetVolumeInformationW
    volume_info.argtypes = [wintypes.LPCWSTR, wintypes.LPWSTR, wintypes.DWORD,
                           wintypes.LPDWORD, wintypes.LPDWORD, wintypes.LPDWORD,
                           wintypes.LPWSTR, wintypes.DWORD]
    volume_info.restype = wintypes.BOOL
    mount = ctypes.create_unicode_buffer(32768)
    filesystem = ctypes.create_unicode_buffer(261)
    serial, flags = wintypes.DWORD(), wintypes.DWORD()
    if not volume_path(str(path), mount, len(mount)):
        raise ctypes.WinError(ctypes.get_last_error())
    if not volume_info(mount.value, None, 0, ctypes.byref(serial), None, ctypes.byref(flags), filesystem, len(filesystem)):
        raise ctypes.WinError(ctypes.get_last_error())
    return {"mount_point": mount.value, "filesystem_type": filesystem.value,
            "volume_serial": f"{serial.value:08x}", "filesystem_flags": flags.value}


def filesystem(path):
    record = {"path": str(path.absolute()), "resolved_path": None, "mount_point": None,
              "filesystem_type": None, "probe": None, "error": None}
    try:
        resolved = path.resolve(strict=True)
        record["resolved_path"] = str(resolved)
        if sys.platform == "linux":
            command = ["findmnt", "--json", "--kernel", "--tab-file", "/proc/self/mountinfo",
                       "--target", str(resolved), "--output", "TARGET,SOURCE,FSTYPE,OPTIONS,FSROOT,MAJ:MIN"]
            record["probe"] = {"command": command}
            result = subprocess.run(command, text=True, encoding="utf-8", errors="replace",
                                    stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=10, check=False,
                                    env={**os.environ, "LC_ALL": "C"})
            record["probe"].update(exit_code=result.returncode, stdout=result.stdout, stderr=result.stderr)
            require(result.returncode == 0, "findmnt failed")
            mounts = json.loads(result.stdout)["filesystems"]
            require(len(mounts) == 1, "findmnt must identify exactly one containing mount")
            mount = mounts[0]
            require(isinstance(mount, dict), "findmnt returned an invalid mount record")
            require(all(isinstance(mount.get(key), str) and mount[key] for key in
                        ["target", "source", "fstype", "options", "fsroot", "maj:min"]),
                    "findmnt returned incomplete mount provenance")
            record.update(mount_point=mount["target"], filesystem_type=mount["fstype"], mount=mount)
        elif sys.platform == "win32":
            record["probe"] = {"api": ["GetVolumePathNameW", "GetVolumeInformationW"]}
            record.update(windows_volume(resolved))
            require(record["mount_point"] and record["filesystem_type"], "Windows returned incomplete volume provenance")
        else:
            raise OSError(f"filesystem probe is unsupported on {sys.platform}")
    except (OSError, subprocess.TimeoutExpired, ValueError, KeyError, TypeError, AssertionError) as error:
        record["error"] = f"{type(error).__name__}: {error}"
    return record


def capture_filesystems(output, locations, *, required):
    records = {name: filesystem(path) for name, path in locations.items()}
    write_json(output, {"utc": datetime.datetime.now(datetime.timezone.utc).isoformat(),
                       "platform": sys.platform, "locations": records,
                       "scope": "OS-visible mounts/volumes at capture time; underlying host storage and durability are not inferred"})
    missing = [name for name, record in records.items() if record["error"] is not None]
    require(not required or not missing, f"publication requires filesystem provenance for {missing}; raw probe retained in {output}")
