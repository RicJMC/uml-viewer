"""Measure function complexity, executed statements, and mutation sites."""

import ast
import io
import tokenize
from collections import defaultdict
from pathlib import Path

from radon.complexity import cc_visit

from .graph import scan
from .mutations import candidates
from .syntax import load_modules


def function_records(root, prefix):
    graph = scan(root, prefix)
    modules = load_modules(root, prefix)
    blocks_by_file = {}
    nodes_by_file = {}
    for module in modules.values():
        blocks = cc_visit(module["source"])
        pending = list(blocks)
        complexities = {}
        while pending:
            block = pending.pop()
            if hasattr(block, "methods"):
                pending.extend(block.methods)
            else:
                complexities[block.lineno] = block.complexity
            pending.extend(getattr(block, "closures", []))
            pending.extend(getattr(block, "inner_classes", []))
        path = str(module["path"])
        blocks_by_file[path] = complexities
        nodes_by_file[path] = {
            node.lineno: node
            for node in ast.walk(module["tree"])
            if isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef))
        }
    records = []
    for component in graph["classes"]:
        for operation in component.get("ops", []):
            path = component["file"]
            node = nodes_by_file[path][operation["line"]]
            complexity = blocks_by_file[path].get(node.lineno)
            if complexity is None:
                complexity = cc_visit(ast.unparse(node))[0].complexity
            record = dict(operation)
            record.update(
                {
                    "namespace": component["ns"],
                    "file": path,
                    "complexity": complexity,
                    "body-line": node.body[0].lineno,
                }
            )
            records.append(record)
    return records


def add_coverage(records, measurement):
    analyses = {}
    for record in records:
        filename = record["file"]
        if filename not in analyses:
            _, statements, _, missing, _ = measurement.analysis2(filename)
            analyses[filename] = (set(statements), set(missing))
        statements, missing = analyses[filename]
        body = set(range(record["body-line"], record["end-line"] + 1))
        executable = statements & body
        executed = executable - missing
        record["statements"] = len(executable)
        record["covered"] = len(executed)
        if not executable:
            continue
        ratio = len(executed) / len(executable)
        complexity = record["complexity"]
        record["coverage"] = 100 * ratio
        record["crap"] = complexity**2 * (1 - ratio) ** 3 + complexity


def decode_source(encoded):
    encoding, _ = tokenize.detect_encoding(io.BytesIO(encoded).readline)
    return encoded.decode(encoding), encoding


def mutant_inventory(records, root, measurement, pattern):
    by_file = defaultdict(list)
    for record in records:
        by_file[record["file"]].append(record)
    inventory = []
    contexts = measurement.get_data()
    for filename, functions in sorted(by_file.items()):
        relative = Path(filename).relative_to(root)
        if not relative.match(pattern):
            continue
        source, _ = decode_source(Path(filename).read_bytes())
        line_contexts = contexts.contexts_by_lineno(filename)
        executed_lines = set(contexts.lines(filename) or [])
        for mutant in candidates(source, functions):
            tests = sorted(set(line_contexts.get(mutant["line"], [])) - {""})
            status = "pending" if tests else "uncovered"
            if not tests and mutant["line"] in executed_lines:
                status = "unattributed"
            mutant.update({"file": str(relative), "tests": tests, "status": status})
            inventory.append(mutant)
    return inventory
