"""Static graph and source-navigation contracts, using isolated Python packages."""

import tempfile
import unittest
from pathlib import Path

from uml_viewer_python import graph as adapter
from uml_viewer_python.source import locate


class PythonAdapterTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.root = Path(self.directory.name)
        self.add("__init__.py", "")

    def tearDown(self):
        self.directory.cleanup()

    def add(self, relative, text):
        path = self.root / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text)
        return path

    def test_fields_have_text_for_the_hierarchical_renderer(self):
        self.add(
            "fields.py",
            "class Sample:\n    size: int = 3\n    def __init__(self): self.ready = True\n",
        )
        graph = adapter.scan(self.root, "demo")
        sample = next(
            record for record in graph["classes"] if record["id"] == "fields.Sample"
        )
        self.assertEqual(
            [field["text"] for field in sample["fields"]], ["size : int", "ready"]
        )

    def test_scan_never_executes_module(self):
        self.add(
            "danger.py", "raise RuntimeError('Must not execute')\nclass Safe: pass\n"
        )
        graph = adapter.scan(self.root, "demo")
        self.assertIn("danger.Safe", [record["id"] for record in graph["classes"]])

    def test_relative_import_alias_and_reexport_resolve_inheritance(self):
        self.add("base.py", "class Base: pass\n")
        self.add("__init__.py", "from .base import Base as Public\n")
        self.add("nested/__init__.py", "")
        self.add(
            "nested/child.py",
            "from .. import Public as Parent\nclass Child(Parent): pass\n",
        )
        graph = adapter.scan(self.root, "demo")
        inheritance = [edge for edge in graph["edges"] if edge["kind"] == "inheritance"]
        self.assertEqual(inheritance[0]["from"], "nested.child.Child")
        self.assertEqual(inheritance[0]["to"], "base.Base")

    def test_decorated_async_method_preserves_signature_and_source(self):
        self.add(
            "sample.py",
            "class Reader:\n    @staticmethod\n    async def read(size: int = 3) -> str:\n        return str(size)\n",
        )
        graph = adapter.scan(self.root, "demo")
        reader = next(
            record for record in graph["classes"] if record["id"] == "sample.Reader"
        )
        self.assertEqual(reader["ops"][0]["text"], "async read(size: int=3) -> str")
        span = locate(self.root, "demo", "demo.sample.Reader", "read")
        self.assertEqual((span["line"], span["end-line"]), (2, 4))

    def test_property_getter_and_setter_have_distinct_source_targets(self):
        self.add(
            "sample.py",
            "class Device:\n    @property\n    def value(self): return 1\n    @value.setter\n    def value(self, amount): pass\n",
        )
        graph = adapter.scan(self.root, "demo")
        device = next(
            record for record in graph["classes"] if record["id"] == "sample.Device"
        )
        self.assertEqual(
            [method["name"] for method in device["ops"]], ["value@3", "value@5"]
        )
        self.assertEqual(
            locate(self.root, "demo", "demo.sample.Device", "value@5")["line"], 4
        )

    def test_nested_class_keeps_full_source_identity(self):
        self.add(
            "sample.py", "class Outer:\n    class Inner:\n        def run(self): pass\n"
        )
        span = locate(self.root, "demo", "demo.sample.Outer.Inner", "run")
        self.assertEqual(span["line"], 3)

    def test_linked_root_preserves_source_lookup_and_rejects_outside_files(self):
        from uml_viewer_python.syntax import load_modules

        self.add("sample.py", "class Sample:\n    def run(self): pass\n")
        with tempfile.TemporaryDirectory() as directory:
            alias = Path(directory) / "linked package"
            try:
                alias.symlink_to(self.root, target_is_directory=True)
            except OSError as error:
                self.skipTest(f"Directory symlinks are unavailable: {error}")
            modules = load_modules(alias, "demo")
            self.assertIn("demo.sample", modules)
            span = locate(alias, "demo", "demo.sample.Sample", "run")
            self.assertEqual(span["line"], 2)
            outside = Path(directory) / "outside.py"
            outside.write_text("class Outside: pass\n")
            (self.root / "outside.py").symlink_to(outside)
            with self.assertRaisesRegex(ValueError, "symlink leaves scan root"):
                load_modules(alias, "demo")
            self.assertIsNone(locate(alias, "demo", "demo.outside.Outside", ""))

    def test_external_dependency_cannot_collide_with_local_module(self):
        self.add("json.py", "import json\n")
        graph = adapter.scan(self.root, "demo")
        ids = [record["id"] for record in graph["classes"]]
        self.assertIn("json", ids)
        self.assertIn("external.json", ids)
        self.assertEqual(len(ids), len(set(ids)))

    def test_unknown_dynamic_import_is_reported_without_guessed_edge(self):
        self.add(
            "sample.py",
            "from importlib import import_module\nimport_module(plugin_name)\n",
        )
        graph = adapter.scan(self.root, "demo")
        self.assertIn("dynamic import target not resolved", graph["warnings"][0])
        self.assertFalse(
            any(edge["to"] == "external.plugin_name" for edge in graph["edges"])
        )

    def test_bad_syntax_fails_instead_of_silently_omitting_a_file(self):
        self.add("broken.py", "class Broken(\n")
        with self.assertRaises(SyntaxError):
            adapter.scan(self.root, "demo")

    def test_source_encoding_cookie_is_respected(self):
        path = self.root / "encoded.py"
        path.write_bytes(b"# coding: latin-1\nclass Caf\xe9: pass\n")
        graph = adapter.scan(self.root, "demo")
        self.assertIn(
            "encoded.Caf\u00e9", [record["id"] for record in graph["classes"]]
        )

    def test_star_import_warns_and_module_with_no_functions_is_kept(self):
        self.add("sample.py", "from .base import *\n")
        self.add("base.py", "VALUE = 4\n")
        graph = adapter.scan(self.root, "demo")
        self.assertIn("base", [record["id"] for record in graph["classes"]])
        self.assertIn("wildcard import not expanded", graph["warnings"][0])


if __name__ == "__main__":
    unittest.main()
