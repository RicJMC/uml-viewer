"""Publish quality snapshots atomically for the viewer and local agents."""

import hashlib
import json
from collections import Counter, defaultdict
from pathlib import Path

from .edn import edn


def atomic_json(path, value):
    temporary = path.with_suffix(path.suffix + ".tmp")
    temporary.write_text(json.dumps(value, indent=2) + "\n")
    temporary.replace(path)


def mutation_forms(mutants):
    grouped = defaultdict(list)
    for mutant in mutants:
        grouped[(mutant["namespace"], mutant["name"])].append(mutant)
    forms = defaultdict(list)
    for (namespace, name), members in grouped.items():
        counts = Counter(member["status"] for member in members)
        complete = all(member["status"] in {"killed", "survived"} for member in members)
        forms[namespace].append(
            {
                "name": name,
                "killed": counts["killed"],
                "survived": counts["survived"],
                "uncovered": counts["uncovered"],
                "status": "complete" if complete else "partial",
                "counts": dict(counts),
                "total": len(members),
            }
        )
    return forms


def write_mutations(directory, mutants):
    directory.mkdir(exist_ok=True)
    for previous in directory.glob("python-*.edn"):
        previous.unlink()
    for namespace, members in mutation_forms(mutants).items():
        snapshot = {"namespace": namespace, "forms": members}
        identity = hashlib.sha256(namespace.encode()).hexdigest()[:16]
        filename = directory / ("python-" + identity + ".edn")
        filename.write_text(edn(snapshot))


def snapshot_manifest(report, original_source):
    counts = Counter(mutant["status"] for mutant in report["mutants"])
    return {
        "source-root": str(original_source),
        "source-files": report["source-files"],
        "python-files": sorted(str(path) for path in original_source.rglob("*.py")),
        "baseline": report["baseline"],
        "mutation-counts": dict(counts),
        "measured-at": report["measured-at"],
        "coverage-kind": "Python statement coverage; C extensions excluded",
        "mutation-operators": [
            "single comparison inversion",
            "boolean flip",
            "addition/subtraction swap",
            "multiply to floor divide",
        ],
    }


def publish(report, destination, original_source):
    destination.mkdir(parents=True, exist_ok=True)
    marker = destination / "updating"
    marker.write_text("Publishing metrics; do not load this snapshot yet.\n")
    entries = {"entries": report["functions"]}
    (destination / "crap.edn").write_text(edn(entries))
    write_mutations(destination / "mutate", report["mutants"])
    manifest = snapshot_manifest(report, original_source)
    (destination / "manifest.edn").write_text(edn(manifest))
    atomic_json(destination / "report.json", report)
    marker.unlink()


def metrics_status(directory, root):
    root = root.resolve()
    report_path = directory / "report.json"
    if (directory / "updating").exists():
        return {"status": "updating", "report": str(report_path)}
    if not report_path.exists():
        return {"status": "missing", "report": str(report_path)}
    report = json.loads(report_path.read_text())
    mismatches = []
    for source in report["source-files"]:
        path = Path(source["path"])
        if not path.exists():
            mismatches.append(str(path))
        elif hashlib.sha256(path.read_bytes()).hexdigest() != source["sha256"]:
            mismatches.append(str(path))
    measured_paths = [Path(source["path"]) for source in report["source-files"]]
    expected_python = {
        path
        for path in measured_paths
        if path.is_relative_to(root) and path.suffix == ".py"
    }
    actual_python = set(root.rglob("*.py"))
    mismatches.extend(str(path) for path in actual_python - expected_python)
    if not any(path.is_relative_to(root) for path in measured_paths):
        return {"status": "wrong-project", "report": str(report_path)}
    return {
        "status": "stale" if mismatches else "current",
        "changed-files": mismatches,
        "baseline": report["baseline"],
        "coverage": report["coverage-summary"],
        "mutations": dict(Counter(m["status"] for m in report["mutants"])),
        "report": str(report_path),
    }
