"""Encode the viewer protocol without importing a third-party serializer."""

import json


class Keyword(str):
    pass


def edn(value):
    if isinstance(value, Keyword):
        return ":" + value
    if value is None:
        return "nil"
    if value is True:
        return "true"
    if value is False:
        return "false"
    if isinstance(value, dict):
        entries = [":" + key + " " + edn(member) for key, member in value.items()]
        return "{" + " ".join(entries) + "}"
    if isinstance(value, (list, tuple)):
        return "[" + " ".join(edn(member) for member in value) + "]"
    return json.dumps(value, ensure_ascii=False)
