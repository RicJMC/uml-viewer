"""Copy measurement inputs and bound the lifetime of test processes."""

import hashlib
import os
import signal
import subprocess
import time
from pathlib import Path

IGNORED = {
    ".git",
    ".venv",
    "venv",
    "__pycache__",
    ".pytest_cache",
    ".mypy_cache",
    ".ruff_cache",
    ".tox",
    ".coverage",
    "target",
    "build",
    "dist",
    "node_modules",
}


def source_hashes(root):
    hashes = {}
    for directory, subdirectories, filenames in os.walk(root):
        subdirectories[:] = sorted(
            name for name in subdirectories if name not in IGNORED
        )
        for name in subdirectories + sorted(filenames):
            if name in IGNORED:
                continue
            path = Path(directory) / name
            relative = path.relative_to(root)
            if path.is_symlink():
                raise ValueError(f"Copy requires regular files, found symlink: {path}")
            if path.is_file():
                hashes[str(relative)] = hashlib.sha256(path.read_bytes()).hexdigest()
    return hashes


def run_process(command, checkout, environment, logfile, timeout):
    start = time.monotonic()
    with logfile.open("w") as stream:
        process = subprocess.Popen(
            command,
            cwd=checkout,
            env=environment,
            stdout=stream,
            stderr=subprocess.STDOUT,
            start_new_session=True,
        )
        try:
            code = process.wait(timeout=timeout)
        except subprocess.TimeoutExpired:
            os.killpg(process.pid, signal.SIGKILL)
            process.wait()
            return {"status": "timeout", "seconds": time.monotonic() - start}
        except BaseException:
            if process.poll() is None:
                os.killpg(process.pid, signal.SIGKILL)
            process.wait()
            raise
    return {
        "status": "passed" if code == 0 else "failed",
        "exit-code": code,
        "seconds": time.monotonic() - start,
    }


def capture_process(command, timeout):
    process = subprocess.Popen(
        command,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
        start_new_session=True,
    )
    try:
        output, errors = process.communicate(timeout=timeout)
    except BaseException:
        if process.poll() is None:
            os.killpg(process.pid, signal.SIGKILL)
        process.communicate()
        raise
    return subprocess.CompletedProcess(command, process.returncode, output, errors)
