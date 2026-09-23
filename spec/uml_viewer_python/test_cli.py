"""Local agent commands share the parser and source lookup used by the viewer."""

import contextlib
import io
import json
import os
import subprocess
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from uml_viewer_python.cli import main


class CommandTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        (self.root / "__init__.py").write_text(
            "class Reader:\n    def read(self): return 7\n"
        )

    def command(self, *arguments):
        output = io.StringIO()
        with contextlib.redirect_stdout(output):
            status = main([*arguments, "--root", str(self.root), "--prefix", "sample"])
        self.assertEqual(0, status)
        return json.loads(output.getvalue())

    def test_inspection_and_source_lookup_agree(self):
        graph = self.command("scan")
        component = next(
            member for member in graph["classes"] if member["ns"] == "sample.Reader"
        )
        source = self.command(
            "source", "--namespace", component["ns"], "--name", "read"
        )
        self.assertEqual("    def read(self): return 7", source["source"])
        self.assertEqual(2, source["line"])
        summary = self.command("inspect")
        self.assertEqual(1, summary["functions"])
        self.assertEqual([], summary["warnings"])

    def test_missing_metrics_are_explicit(self):
        result = self.command("metrics", "--metrics", str(self.root / "metrics"))
        self.assertEqual("missing", result["status"])

    def test_refresh_uses_the_policy_generator(self):
        completed = subprocess.CompletedProcess([], 0, "Wrote target/library.edn", "")
        with patch.dict(os.environ, {"UML_CLOJURE": "clojure"}):
            with patch(
                "uml_viewer_python.cli.capture_process", return_value=completed
            ) as run:
                output = io.StringIO()
                with contextlib.redirect_stdout(output):
                    status = main(["refresh", "--policy", "examples/python.policy.edn"])
        self.assertEqual(0, status)
        run.assert_called_once_with(
            ["clojure", "-M:ir", "examples/python.policy.edn"], 120
        )
        self.assertEqual("refreshed", json.loads(output.getvalue())["status"])
