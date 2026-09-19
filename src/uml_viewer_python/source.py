"""Locate a module, class, or method in its source file."""

from .syntax import parse_module, source_records


def locate(root, prefix, namespace, name):
    if prefix and namespace != prefix and not namespace.startswith(prefix + "."):
        return None
    relative = namespace[len(prefix) :].lstrip(".") if prefix else namespace
    segments = relative.split(".") if relative else []
    module = None
    for count in range(len(segments), -1, -1):
        stem = root.joinpath(*segments[:count])
        candidates = (
            [stem.with_suffix(".py"), stem / "__init__.py"]
            if count
            else [root / "__init__.py"]
        )
        for path in candidates:
            if path.is_file() and path.resolve().is_relative_to(root):
                module = parse_module(path, root, prefix)
                break
        if module:
            break
    if module is None:
        return None
    module_name = module["namespace"]
    class_path = namespace[len(module_name) :].lstrip(".")
    qualified = ".".join(part for part in [class_path, name] if part)
    span = (
        source_records(module).get(qualified)
        if qualified
        else {"line": 1, "end-line": 1}
    )
    if span is None:
        return None
    return {"file": str(module["path"]), **span}
