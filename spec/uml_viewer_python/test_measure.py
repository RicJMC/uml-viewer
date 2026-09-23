"""End-to-end checks for truthful Python quality reports."""

import ast
import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

from uml_viewer_python.mutations import candidates
from uml_viewer_python.reports import metrics_status
from uml_viewer_python.workspace import copy_ignore, select_hashes, source_hashes


class MeasurementTest(unittest.TestCase):
    def test_absolute_source_is_resolved_inside_the_copy(self):
        from argparse import Namespace

        from uml_viewer_python.measure import validate_paths

        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            project = root / "project"
            source = project / "sample"
            source.mkdir(parents=True)
            options = Namespace(
                project=project,
                source=str(source),
                work=root / "work",
                output=root / "metrics",
                tests=["tests"],
            )
            validate_paths(options)
            self.assertEqual("sample", options.source)

    def test_agent_waits_for_first_report_publication(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "updating").write_text("publishing")
            completed = subprocess.run(
                [
                    sys.executable,
                    "-B",
                    "-m",
                    "uml_viewer_python",
                    "metrics",
                    "--metrics",
                    str(root),
                    "--root",
                    str(root),
                    "--namespace",
                    "sample.logic",
                ],
                capture_output=True,
                text=True,
            )
            self.assertEqual(0, completed.returncode, completed.stderr)
            self.assertEqual("updating", json.loads(completed.stdout)["status"])

    def test_measured_scores_and_real_mutation_results(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            # A surrounding checkout must not change the recorded pytest IDs.
            (root / "pyproject.toml").write_text("[tool.pytest.ini_options]\n")
            project = root / "project with spaces"
            package = project / "sample"
            package.mkdir(parents=True)
            (package / "__init__.py").write_text("")
            source = (
                "def checked(value):\n"
                "    if value > 0:\n"
                "        return True\n"
                "    return False\n\n"
                "def unchecked(value):\n"
                "    return value + 1\n\n"
                "def unused(value):\n"
                "    return value - 1\n\n"
                "def boot():\n"
                "    return True\n"
                "flag = boot()\n"
            )
            filename = package / "logic.py"
            filename.write_text(source)
            (project / "test_logic.py").write_text(
                "from sample.logic import checked, unchecked\n"
                "def test_checked():\n"
                "    assert checked(1) is True\n"
                "    assert checked(0) is False\n"
                "def test_unchecked():\n"
                "    unchecked(3)\n"
            )
            output = root / "metrics"
            command = [
                sys.executable,
                "-B",
                "-m",
                "uml_viewer_python.measure",
                "--project",
                str(project),
                "--source",
                "sample",
                "--prefix",
                "sample",
                "--work",
                str(root / "work"),
                "--output",
                str(output),
                "--tests",
                "test_logic.py",
                "--mutation-limit",
                "0",
            ]
            completed = subprocess.run(command, capture_output=True, text=True)
            self.assertEqual(
                0, completed.returncode, completed.stdout + completed.stderr
            )
            self.assertEqual(source, filename.read_text())
            report = json.loads((output / "report.json").read_text())
            functions = {function["name"]: function for function in report["functions"]}
            self.assertEqual(2, functions["checked"]["complexity"])
            self.assertEqual(100, functions["checked"]["coverage"])
            self.assertEqual(2, functions["checked"]["crap"])
            self.assertEqual(0, functions["unused"]["coverage"])
            states = {mutant["status"] for mutant in report["mutants"]}
            self.assertEqual(
                {"killed", "survived", "uncovered", "unattributed"}, states
            )
            self.assertEqual("current", metrics_status(output, package)["status"])
            marker = output / "updating"
            marker.write_text("publishing")
            self.assertEqual("updating", metrics_status(output, package)["status"])
            marker.unlink()
            added = package / "new.py"
            added.write_text("value = 1\n")
            self.assertEqual("stale", metrics_status(output, package)["status"])
            added.unlink()
            filename.write_text(source + "\n")
            self.assertEqual("stale", metrics_status(output, package)["status"])

    def test_nested_class_complexity_and_non_utf8_source(self):
        from uml_viewer_python.metrics import decode_source, function_records

        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = "class Outer:\n    class Inner:\n        def run(self, x):\n            if x:\n                return 1\n            return 0\n"
            (root / "__init__.py").write_text(source)
            records = function_records(root, "sample")
            self.assertEqual("sample.Outer.Inner", records[0]["namespace"])
            self.assertEqual(2, records[0]["complexity"])
        encoded = '# coding: latin-1\r\ndef run():\r\n    label = "é"; return 3 + 2\r\n'.encode(
            "latin-1"
        )
        text, encoding = decode_source(encoded)
        records = [
            {"namespace": "sample", "name": "run", "body-line": 3, "end-line": 3}
        ]
        mutant = next(candidates(text, records))
        changed = mutant["changed-source"].encode(encoding)
        self.assertEqual(encoded.replace(b"3 + 2", b"3 - 2"), changed)

    def test_utf8_offsets_leave_neighboring_code_intact(self):
        source = 'def action():\n    label = "é"; return 3 + 2\n'
        records = [
            {"namespace": "sample", "name": "action", "body-line": 2, "end-line": 2}
        ]
        mutant = next(candidates(source, records))
        changed = mutant["changed-source"]
        ast.parse(changed)
        self.assertIn('label = "é"; return 3 - 2', changed)


class WorkspaceTest(unittest.TestCase):
    def test_source_hashes_skips_symlinks_and_viewer_runtime(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            project = root / "project"
            (project / "sample").mkdir(parents=True)
            (project / "sample" / "logic.py").write_text("x = 1\n")
            outside = root / "outside"
            outside.mkdir()
            (outside / "big.py").write_text("y = 2\n")
            (project / ".metrics").symlink_to(outside, target_is_directory=True)
            (project / ".uml-viewer").mkdir()
            (project / ".uml-viewer" / "to-agent.edn").write_text("{}\n")
            (project / "link.py").symlink_to(outside / "big.py")
            hashes = source_hashes(project)
            self.assertIn("sample/logic.py", hashes)
            self.assertNotIn("link.py", hashes)
            self.assertFalse(any(key.startswith(".metrics") for key in hashes))
            self.assertFalse(any(key.startswith(".uml-viewer") for key in hashes))

    def test_copy_ignore_drops_runtime_state_and_symlinks(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "sample").mkdir()
            (root / "keep.py").write_text("\n")
            (root / "outside").mkdir()
            (root / ".metrics").symlink_to(root / "outside", target_is_directory=True)
            (root / "linked.py").symlink_to(root / "keep.py")
            ignored = copy_ignore(
                root, ["sample", ".metrics", ".uml-viewer", "linked.py", "keep.py"]
            )
            self.assertEqual([".metrics", ".uml-viewer", "linked.py"], ignored)
            self.assertNotIn("sample", ignored)
            self.assertNotIn("keep.py", ignored)

    def test_select_hashes_limits_to_measured_inputs(self):
        hashes = {
            "src/app/logic.py": "a",
            "src/app/util.py": "b",
            "tests/test_app.py": "c",
            "notes.md": "d",
        }
        self.assertEqual(
            {"src/app/logic.py": "a", "src/app/util.py": "b"},
            select_hashes(hashes, ["src/app"]),
        )
        self.assertEqual(
            {"tests/test_app.py": "c"},
            select_hashes(hashes, ["tests/test_app.py"]),
        )


if __name__ == "__main__":
    unittest.main()
