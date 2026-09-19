"""Measure tests and mutations in a copy of a Python project."""

import argparse
import json
import os
import shutil
import sys
import time
from collections import Counter
from pathlib import Path

import coverage

from .metrics import add_coverage, decode_source, function_records, mutant_inventory
from .mutations import candidates
from .reports import atomic_json, publish
from .workspace import IGNORED, run_process, source_hashes


def arguments():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--project", type=Path, required=True)
    parser.add_argument("--source", required=True)
    parser.add_argument("--prefix", required=True)
    parser.add_argument("--work", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--tests", nargs="+", default=["tests"])
    parser.add_argument(
        "--mutation-limit",
        type=int,
        default=25,
        help="Maximum covered mutants to run; 0 runs all",
    )
    parser.add_argument("--mutate", default="*.py")
    parser.add_argument("--timeout", type=float, default=120)
    parser.add_argument("--baseline-timeout", type=float, default=1800)
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument("--resume", action="store_true")
    mode.add_argument("--retry-baseline", action="store_true")
    options = parser.parse_args()
    for name in ("project", "work", "output"):
        setattr(options, name, getattr(options, name).resolve())
    validate_paths(options)
    if (
        options.mutation_limit < 0
        or min(options.timeout, options.baseline_timeout) <= 0
    ):
        parser.error("Mutation limit must be non-negative and timeouts positive")
    return options


def validate_paths(options):
    if not options.project.is_dir():
        raise ValueError("Project must be a directory")
    for destination in (options.work, options.output):
        if destination.is_relative_to(options.project):
            raise ValueError("Work and output directories must be outside the project")
    source = (options.project / options.source).resolve()
    if not source.is_relative_to(options.project) or not source.is_dir():
        raise ValueError("Source must be a directory inside the project")
    options.source = str(source.relative_to(options.project))
    for selector in options.tests:
        selected = (options.project / selector.split("::")[0]).resolve()
        if Path(selector).is_absolute() or not selected.is_relative_to(options.project):
            raise ValueError("Test selectors must stay inside the copied project")
        if selector.startswith("-"):
            raise ValueError("Use test paths or node IDs, not pytest options")


def prepare_checkout(options, hashes):
    identity = {
        "hashes": hashes,
        "tests": options.tests,
        "source": options.source,
        "prefix": options.prefix,
        "mutate": options.mutate,
        "python": sys.version,
    }
    options.work.mkdir(parents=True, exist_ok=True)
    identity_path = options.work / "identity.json"
    checkout = options.work / "checkout"
    if options.resume:
        if json.loads(identity_path.read_text()) != identity:
            raise ValueError("Inputs changed; use a new work directory")
        return checkout
    if options.retry_baseline:
        previous = json.loads(identity_path.read_text())
        has_report = (options.work / "report.json").exists()
        if previous["hashes"] != hashes or has_report:
            raise ValueError("Retry requires unchanged inputs and a failed baseline")
    else:
        if checkout.exists():
            raise ValueError("Work directory already used; choose a new one or resume")
        shutil.copytree(
            options.project, checkout, ignore=shutil.ignore_patterns(*IGNORED)
        )
    atomic_json(identity_path, identity)
    return checkout


def test_environment(options, checkout):
    environment = dict(os.environ)
    environment.update(
        {
            "PYTHONDONTWRITEBYTECODE": "1",
            "PYTEST_DISABLE_PLUGIN_AUTOLOAD": "1",
            "PYTHONUNBUFFERED": "1",
            "COVERAGE_FILE": str(options.work / ".coverage"),
            "UML_SOURCE": str(checkout / options.source),
            "TMPDIR": str(options.work),
            "XDG_CACHE_HOME": str(options.work / "cache"),
        }
    )
    search_paths = environment.get("PYTHONPATH", "").split(os.pathsep)
    absolute_paths = [str(Path(path).resolve()) for path in search_paths if path]
    environment["PYTHONPATH"] = os.pathsep.join([str(checkout), *absolute_paths])
    return environment


def baseline_report(options, checkout, environment, hashes):
    command = [
        sys.executable,
        "-B",
        "-m",
        "uml_viewer_python.coverage_runner",
        "-q",
        "-p",
        "no:cacheprovider",
        *options.tests,
    ]
    print(json.dumps({"stage": "baseline-tests", "tests": options.tests}), flush=True)
    baseline = run_process(
        command,
        checkout,
        environment,
        options.work / "baseline.log",
        options.baseline_timeout,
    )
    if baseline["status"] != "passed":
        atomic_json(options.work / "baseline-failure.json", baseline)
        raise RuntimeError(
            f"Baseline did not pass; see {options.work / 'baseline.log'}"
        )
    (options.work / "baseline-failure.json").unlink(missing_ok=True)
    measurement = coverage.Coverage(
        data_file=environment["COVERAGE_FILE"], config_file=False
    )
    measurement.load()
    coverage_path = options.work / "coverage.json"
    measurement.json_report(outfile=str(coverage_path))
    source = checkout / options.source
    records = function_records(source, options.prefix)
    add_coverage(records, measurement)
    inventory = mutant_inventory(records, source, measurement, options.mutate)
    for mutant in inventory:
        mutant.pop("changed-source")
    return {
        "baseline": baseline,
        "functions": records,
        "mutants": inventory,
        "measured-at": time.time(),
        "source-files": [
            {"path": str(options.project / path), "sha256": digest}
            for path, digest in hashes.items()
        ],
        "coverage-summary": json.loads(coverage_path.read_text())["totals"],
    }


def run_mutant(mutant, records, checkout, environment, logfile, options):
    filename = checkout / options.source / mutant["file"]
    original = filename.read_bytes()
    functions = [record for record in records if record["file"] == str(filename)]
    source, encoding = decode_source(original)
    inventory = candidates(source, functions)
    changed = next(
        candidate for candidate in inventory if candidate["id"] == mutant["id"]
    )
    command = [
        sys.executable,
        "-B",
        "-m",
        "pytest",
        "-q",
        "-x",
        "-p",
        "no:cacheprovider",
        *mutant["tests"],
    ]
    try:
        filename.write_bytes(changed["changed-source"].encode(encoding))
        result = run_process(command, checkout, environment, logfile, options.timeout)
    finally:
        filename.write_bytes(original)
    status = {0: "survived", 1: "killed"}.get(result.get("exit-code"), "error")
    if result["status"] == "timeout":
        status = "timeout"
    mutant.update({"status": status, "seconds": result["seconds"], "log": str(logfile)})


def run_campaign(options):
    print(json.dumps({"stage": "hashing-inputs"}), flush=True)
    hashes = source_hashes(options.project)
    checkout = prepare_checkout(options, hashes)
    environment = test_environment(options, checkout)
    report_path = options.work / "report.json"
    if options.resume:
        report = json.loads(report_path.read_text())
    else:
        report = baseline_report(options, checkout, environment, hashes)
        atomic_json(report_path, report)
    attempted = 0
    for index, mutant in enumerate(report["mutants"]):
        if mutant["status"] != "pending":
            continue
        if options.mutation_limit and attempted >= options.mutation_limit:
            break
        logfile = options.work / f"mutant-{index}.log"
        run_mutant(mutant, report["functions"], checkout, environment, logfile, options)
        attempted += 1
        atomic_json(report_path, report)
        print(json.dumps({"mutant": index, "status": mutant["status"]}), flush=True)
    if source_hashes(options.project) != hashes:
        raise RuntimeError("Original project changed; scores not published")
    source = (options.project / options.source).resolve()
    publish(report, options.output, source)
    counts = Counter(mutant["status"] for mutant in report["mutants"])
    print(
        json.dumps(
            {
                "report": str(options.output / "report.json"),
                "coverage": report["coverage-summary"],
                "mutations": dict(counts),
            }
        )
    )


def main():
    try:
        run_campaign(arguments())
    except (OSError, ValueError, RuntimeError) as error:
        print(json.dumps({"error": str(error)}), file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
