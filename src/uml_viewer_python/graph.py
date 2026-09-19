"""Build the viewer's topology from Python declarations and imports."""

import ast
from collections import Counter

from .edn import Keyword
from .imports import (
    expression_name,
    import_facts,
    resolve_alias,
    visible_import_aliases,
)
from .syntax import definition_nodes, fields_for, load_modules, method_entry


def identifier(namespace, prefix):
    if namespace == prefix:
        return Keyword("__init__")
    if prefix and namespace.startswith(prefix + "."):
        return Keyword(namespace[len(prefix) + 1 :])
    return Keyword(namespace)


def operations_for(nodes):
    functions = [
        node
        for node in nodes
        if isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef))
    ]
    counts = Counter(node.name for node in functions)
    operations = []
    for function in functions:
        name = function.name
        if counts[name] > 1:
            name += "@" + str(function.lineno)
        operations.append(method_entry(function, name))
    return operations


class GraphBuilder:
    def __init__(self, modules, prefix):
        self.modules = modules
        self.prefix = prefix
        self.classes = []
        self.edges = {}
        self.warnings = []
        self.foreign = set()
        self.aliases = {
            name: visible_import_aliases(module) for name, module in modules.items()
        }
        self.class_names = self.find_classes()

    def find_classes(self):
        names = set()
        for namespace, module in self.modules.items():
            for path, node in definition_nodes(module["tree"]):
                if isinstance(node, ast.ClassDef):
                    names.add(namespace + "." + ".".join(path))
        return names

    def target_node(self, target):
        if target in self.modules or target in self.class_names:
            return identifier(target, self.prefix)
        # Imported functions and constants belong to their defining module.
        candidate = target
        while "." in candidate:
            candidate = candidate.rpartition(".")[0]
            if candidate in self.modules:
                return identifier(candidate, self.prefix)
        self.foreign.add(target)
        return Keyword("external." + target)

    def add_edge(self, origin, target, kind, line):
        destination = self.target_node(target)
        if destination == origin:
            return
        identity = (origin, destination, kind)
        self.edges.setdefault(
            identity,
            {
                "from": origin,
                "to": destination,
                "kind": Keyword(kind),
                "line": line,
            },
        )

    def resolve_base(self, name, namespace):
        target = resolve_alias(name, self.aliases[namespace])
        local_name = namespace + "." + name
        if target == name and local_name in self.class_names:
            return local_name
        visited = set()
        while target not in self.modules and target not in self.class_names:
            if target in visited:
                break
            visited.add(target)
            owner, _, member = target.rpartition(".")
            imported = self.aliases.get(owner, {}).get(member)
            if not imported:
                break
            target = imported
        return target

    def add_bases(self, record, node, namespace):
        for base in node.bases:
            name = expression_name(base)
            if not name:
                self.warnings.append(
                    f"{record['ns']}:{base.lineno}: computed base not resolved"
                )
                continue
            target = self.resolve_base(name, namespace)
            if target in {"typing.Protocol", "typing_extensions.Protocol"}:
                record["stereotype"] = Keyword("interface")
            if target == "abc.ABC":
                record["stereotype"] = Keyword("abstract")
            self.add_edge(record["id"], target, "inheritance", base.lineno)

    def add_class(self, module, path, node):
        namespace = module["namespace"]
        full_name = namespace + "." + ".".join(path)
        record = {
            "id": identifier(full_name, self.prefix),
            "name": node.name,
            "ns": full_name,
            "file": str(module["path"]),
            "line": node.lineno,
            "ops": operations_for(node.body),
            "fields": fields_for(node),
        }
        self.classes.append(record)
        self.add_bases(record, node, namespace)

    def add_dynamic_imports(self, module, aliases):
        namespace = module["namespace"]
        origin = identifier(namespace, self.prefix)
        for call in ast.walk(module["tree"]):
            if not isinstance(call, ast.Call):
                continue
            name = expression_name(call.func)
            resolved = resolve_alias(name, aliases) if name else None
            if resolved not in {"importlib.import_module", "__import__"}:
                continue
            argument = call.args[0] if call.args else None
            target = argument.value if isinstance(argument, ast.Constant) else None
            if isinstance(target, str) and not target.startswith("."):
                self.add_edge(origin, target, "dependency", call.lineno)
            else:
                self.warnings.append(
                    f"{namespace}:{call.lineno}: dynamic import target not resolved"
                )

    def add_module(self, module):
        namespace = module["namespace"]
        module_id = identifier(namespace, self.prefix)
        definitions = list(definition_nodes(module["tree"]))
        top_level = [node for path, node in definitions if len(path) == 1]
        self.classes.append(
            {
                "id": module_id,
                "name": namespace.split(".")[-1] + " [module]",
                "ns": namespace,
                "file": str(module["path"]),
                "ops": operations_for(top_level),
            }
        )
        aliases, dependencies, warnings = import_facts(module)
        self.warnings.extend(warnings)
        for target, line in dependencies:
            self.add_edge(module_id, target, "dependency", line)
        for path, node in definitions:
            if isinstance(node, ast.ClassDef):
                self.add_class(module, path, node)
        self.add_dynamic_imports(module, aliases)

    def build(self):
        for module in self.modules.values():
            self.add_module(module)
        for name in sorted(self.foreign):
            self.classes.append(
                {
                    "id": Keyword("external." + name),
                    "name": name,
                    "ns": name,
                    "foreign": True,
                }
            )
        files = [str(module["path"]) for module in self.modules.values()]
        return {
            "classes": self.classes,
            "edges": list(self.edges.values()),
            "warnings": self.warnings,
            "files": files,
        }


def scan(root, prefix):
    modules = load_modules(root.resolve(), prefix)
    return GraphBuilder(modules, prefix).build()
