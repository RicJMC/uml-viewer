"""Read Python declarations without executing the examined package."""

import ast
import tokenize


def read_source(path):
    with tokenize.open(path) as stream:
        return stream.read()


def parse_module(path, root, prefix):
    relative = path.relative_to(root).with_suffix("")
    parts = list(relative.parts)
    is_package = parts[-1] == "__init__"
    if is_package:
        parts.pop()
    namespace = ".".join([prefix, *parts]) if prefix else ".".join(parts)
    if not namespace:
        namespace = root.name
    source = read_source(path)
    tree = ast.parse(source, filename=str(path))
    return {
        "path": path,
        "namespace": namespace,
        "package": is_package,
        "source": source,
        "tree": tree,
    }


def load_modules(root, prefix):
    root = root.resolve()
    ignored = {".git", ".venv", "venv", "__pycache__", "node_modules"}
    modules = {}
    for path in sorted(root.rglob("*.py")):
        if any(part in ignored for part in path.relative_to(root).parts):
            continue
        if not path.resolve().is_relative_to(root):
            raise ValueError(f"Source symlink leaves scan root: {path}")
        module = parse_module(path, root, prefix)
        namespace = module["namespace"]
        if namespace in modules:
            raise ValueError(f"Ambiguous module {namespace}: {path}")
        modules[namespace] = module
    if not modules:
        raise ValueError(f"No Python files found under {root}")
    return modules


def definition_nodes(node, parents=()):
    for child in ast.iter_child_nodes(node):
        if isinstance(child, (ast.ClassDef, ast.FunctionDef, ast.AsyncFunctionDef)):
            qualified = parents + (child.name,)
            yield qualified, child
            yield from definition_nodes(child, qualified)
        else:
            yield from definition_nodes(child, parents)


def method_entry(node, name):
    signature = ast.unparse(node.args)
    returns = ast.unparse(node.returns) if node.returns else None
    prefix = "async " if isinstance(node, ast.AsyncFunctionDef) else ""
    text = prefix + name + "(" + signature + ")"
    if returns:
        text += " -> " + returns
    return {
        "name": name,
        "text": text,
        "private": node.name.startswith("_") and not node.name.startswith("__"),
        "line": node.lineno,
        "end-line": node.end_lineno,
    }


def assignment_targets(node):
    if isinstance(node, ast.Assign):
        return node.targets
    if isinstance(node, ast.AnnAssign):
        return [node.target]
    return []


def instance_fields(method):
    names = set()
    for statement in ast.walk(method):
        for target in assignment_targets(statement):
            if not isinstance(target, ast.Attribute):
                continue
            if isinstance(target.value, ast.Name) and target.value.id == "self":
                names.add(target.attr)
    return sorted(names)


def fields_for(node):
    fields = {}
    for statement in node.body:
        for target in assignment_targets(statement):
            if not isinstance(target, ast.Name):
                continue
            field = {"name": target.id}
            if isinstance(statement, ast.AnnAssign):
                field["type"] = ast.unparse(statement.annotation)
            fields.setdefault(target.id, field)
        if isinstance(statement, (ast.FunctionDef, ast.AsyncFunctionDef)):
            for name in instance_fields(statement):
                fields.setdefault(name, {"name": name})
    for field in fields.values():
        field["text"] = field["name"]
        if field.get("type"):
            field["text"] += " : " + field["type"]
    return list(fields.values())


def source_records(module):
    records = {}
    for path, node in definition_nodes(module["tree"]):
        qualified = ".".join(path)
        start = min(
            [node.lineno] + [decorator.lineno for decorator in node.decorator_list]
        )
        records[qualified] = {"line": start, "end-line": node.end_lineno}
        records[qualified + "@" + str(node.lineno)] = records[qualified]
    return records
