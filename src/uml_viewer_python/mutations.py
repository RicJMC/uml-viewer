"""Small, explicit Python mutation operator set, not a correctness proof."""

import ast
import copy
import hashlib

COMPARISONS = {
    ast.Eq: ast.NotEq,
    ast.NotEq: ast.Eq,
    ast.Lt: ast.GtE,
    ast.LtE: ast.Gt,
    ast.Gt: ast.LtE,
    ast.GtE: ast.Lt,
    ast.Is: ast.IsNot,
    ast.IsNot: ast.Is,
    ast.In: ast.NotIn,
    ast.NotIn: ast.In,
}
ARITHMETIC = {ast.Add: ast.Sub, ast.Sub: ast.Add, ast.Mult: ast.FloorDiv}


def replacement(node):
    changed = copy.deepcopy(node)
    if isinstance(node, ast.Compare) and len(node.ops) == 1:
        changed.ops = [COMPARISONS[type(node.ops[0])]()]
    elif isinstance(node, ast.BinOp) and type(node.op) in ARITHMETIC:
        changed.op = ARITHMETIC[type(node.op)]()
    elif isinstance(node, ast.Constant) and isinstance(node.value, bool):
        changed.value = not node.value
    else:
        return None
    return ast.unparse(changed)


def replace_expression(source, node, expression):
    # AST columns are UTF-8 byte offsets, not Python string indices.
    lines = source.encode("utf-8").splitlines(keepends=True)
    start = sum(map(len, lines[: node.lineno - 1])) + node.col_offset
    end = sum(map(len, lines[: node.end_lineno - 1])) + node.end_col_offset
    encoded = source.encode("utf-8")
    changed = encoded[:start] + expression.encode("utf-8") + encoded[end:]
    return changed.decode("utf-8")


def candidates(source, functions):
    tree = ast.parse(source)
    for node in ast.walk(tree):
        expression = replacement(node)
        if expression is None:
            continue
        owners = [
            function
            for function in functions
            if function["body-line"] <= node.lineno
            and node.end_lineno <= function["end-line"]
        ]
        if not owners:
            continue
        owner = min(
            owners, key=lambda function: function["end-line"] - function["body-line"]
        )
        changed = replace_expression(source, node, expression)
        fingerprint = hashlib.sha256(changed.encode()).hexdigest()[:16]
        yield {
            "id": fingerprint,
            "namespace": owner["namespace"],
            "name": owner["name"],
            "line": node.lineno,
            "operator": type(node).__name__,
            "replacement": expression,
            "changed-source": changed,
        }
