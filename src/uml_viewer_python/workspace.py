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

# Viewer runtime state written while a measurement runs. It is not an input,
# so it must neither fail the copy nor trip the changed-project guard.
RUNTIME = {".uml-viewer", ".metrics"}


def ignored(name):
    return name in IGNORED or name in RUNTIME


def copy_ignore(directory, names):
    """Names copytree must skip: caches, viewer runtime state, symlinks."""
    base = Path(directory)
    return [
        name
        for name in names
        if ignored(name) or (base / name).is_symlink()
    ]


def source_hashes(root):
    hashes = {}
    for directory, subdirectories, filenames in os.walk(root):
        subdirectories[:] = sorted(
            name
            for name in subdirectories
            if not ignored(name) and not (Path(directory) / name).is_symlink()
        )
        for name in subdirectories + sorted(filenames):
            if ignored(name):
                continue
            path = Path(directory) / name
            if path.is_symlink() or not path.is_file():
                continue
            relative = path.relative_to(root)
            hashes[str(relative)] = hashlib.sha256(path.read_bytes()).hexdigest()
    return hashes


def select_hashes(hashes, paths):
    """Hashes for files under any project-relative path in `paths`."""
    wanted = {path for path in paths if path}
    prefixes = tuple(path.rstrip("/") + "/" for path in wanted)
    return {
        relative: digest
        for relative, digest in hashes.items()
        if relative in wanted or relative.startswith(prefixes)
    }


def changed_hashes(before, after):
    """Project-relative paths whose content differs between two hash maps."""
    return [
        path
        for path in sorted(set(before) | set(after))
        if before.get(path) != after.get(path)
    ]


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
