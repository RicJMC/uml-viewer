"""Resolve static import names and identify unresolved dependencies."""

import ast


def relative_base(module, statement):
    namespace = module["namespace"]
    package = namespace if module["package"] else namespace.rpartition(".")[0]
    if not statement.level:
        return statement.module or ""
    parts = package.split(".") if package else []
    if statement.level > len(parts):
        raise ValueError(
            f"Relative import above package in {namespace}:{statement.lineno}"
        )
    keep = len(parts) - statement.level + 1
    base = ".".join(parts[:keep])
    return ".".join(part for part in [base, statement.module] if part)


def import_facts(module):
    aliases = {}
    dependencies = []
    warnings = []
    # Nested and conditional imports remain dependencies, not proof of execution.
    for statement in ast.walk(module["tree"]):
        if isinstance(statement, ast.Import):
            for imported in statement.names:
                local = imported.asname or imported.name.split(".")[0]
                target = imported.name if imported.asname else local
                aliases[local] = target
                dependencies.append((imported.name, statement.lineno))
        elif isinstance(statement, ast.ImportFrom):
            base = relative_base(module, statement)
            for imported in statement.names:
                if imported.name == "*":
                    warnings.append(
                        f"{module['namespace']}:{statement.lineno}: wildcard import not expanded"
                    )
                    dependencies.append((base, statement.lineno))
                    continue
                target = ".".join(part for part in [base, imported.name] if part)
                aliases[imported.asname or imported.name] = target
                dependencies.append((target, statement.lineno))
    return aliases, dependencies, warnings


def expression_name(node):
    if isinstance(node, ast.Name):
        return node.id
    if isinstance(node, ast.Attribute):
        parent = expression_name(node.value)
        return parent + "." + node.attr if parent else None
    if isinstance(node, ast.Subscript):
        return expression_name(node.value)
    return None


def resolve_alias(name, aliases):
    first, separator, rest = name.partition(".")
    resolved = aliases.get(first, first)
    return resolved + separator + rest


def visible_import_aliases(module):
    """Only module-scope imports can resolve bases without guessing local scope."""
    aliases = {}
    for statement in module["tree"].body:
        if isinstance(statement, (ast.Import, ast.ImportFrom)):
            fragment = dict(module)
            fragment["tree"] = ast.Module(body=[statement], type_ignores=[])
            aliases.update(import_facts(fragment)[0])
    return aliases
