"""Local JSON commands for people and coding agents."""

import argparse
import json
import os
import subprocess
import sys
from pathlib import Path

from .edn import edn
from .graph import scan
from .reports import metrics_status
from .source import locate
from .syntax import read_source
from .workspace import capture_process


def argument_parser():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    for command in ("scan", "inspect", "source", "metrics"):
        subparser = commands.add_parser(command)
        subparser.add_argument("--root", type=Path, required=True)
        subparser.add_argument("--prefix", default="")
        subparser.add_argument("--format", choices=("json", "edn"), default="json")
        if command in ("source", "metrics"):
            subparser.add_argument("--namespace", default="")
        if command == "source":
            subparser.add_argument("--name", default="")
        if command == "metrics":
            subparser.add_argument("--metrics", type=Path, required=True)
    refresh = commands.add_parser("refresh")
    refresh.add_argument("--policy", type=Path, required=True)
    refresh.add_argument("--timeout", type=float, default=120)
    refresh.add_argument("--format", choices=("json", "edn"), default="json")
    return parser


def inspect_package(root, prefix):
    graph = scan(root, prefix)
    local = [
        component for component in graph["classes"] if not component.get("foreign")
    ]
    functions = sum(len(component.get("ops", [])) for component in local)
    return {
        "files": len(graph["files"]),
        "components": len(local),
        "functions": functions,
        "edges": len(graph["edges"]),
        "warnings": graph["warnings"],
    }


def source_definition(options):
    definition = locate(options.root, options.prefix, options.namespace, options.name)
    if definition is None:
        raise ValueError("Definition not found")
    lines = read_source(Path(definition["file"])).splitlines()
    selected = lines[definition["line"] - 1 : definition["end-line"]]
    return {**definition, "source": "\n".join(selected)}


def quality_report(options):
    result = metrics_status(options.metrics.resolve(), options.root)
    if not options.namespace or result["status"] != "current":
        return result
    report = json.loads(Path(result["report"]).read_text())
    for key in ("functions", "mutants"):
        result[key] = [
            entry for entry in report[key] if entry["namespace"] == options.namespace
        ]
    return result


def refresh_diagram(options):
    executable = os.environ.get("UML_CLOJURE", "clojure")
    command = [executable, "-M:ir", str(options.policy)]
    completed = capture_process(command, options.timeout)
    if completed.returncode:
        raise RuntimeError(completed.stderr or completed.stdout)
    return {"status": "refreshed", "output": completed.stdout.strip()}


def execute(options):
    if options.command == "refresh":
        return refresh_diagram(options)
    options.root = options.root.resolve()
    if not options.root.is_dir():
        raise ValueError(f"Source root is not a directory: {options.root}")
    if options.command == "scan":
        return scan(options.root, options.prefix)
    if options.command == "inspect":
        return inspect_package(options.root, options.prefix)
    if options.command == "source":
        return source_definition(options)
    return quality_report(options)


def main(arguments=None):
    options = argument_parser().parse_args(arguments)
    try:
        result = execute(options)
    except (
        OSError,
        ValueError,
        RuntimeError,
        SyntaxError,
        UnicodeError,
        subprocess.TimeoutExpired,
    ) as error:
        print(json.dumps({"error": str(error)}), file=sys.stderr)
        return 1
    encoded = edn(result) if options.format == "edn" else json.dumps(result, indent=2)
    print(encoded)
    return 0
