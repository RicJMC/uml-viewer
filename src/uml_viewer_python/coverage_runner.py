"""Run pytest with per-test coverage contexts in the isolated checkout."""

import os
import sys
from pathlib import Path

import coverage
import pytest


class TestContexts:
    def __init__(self, measurement):
        self.measurement = measurement

    def pytest_sessionstart(self, session):
        print("Coverage baseline: collecting tests", flush=True)

    def pytest_collection_finish(self, session):
        print(f"Coverage baseline: {len(session.items)} collected tests", flush=True)

    @pytest.hookimpl(hookwrapper=True)
    def pytest_runtest_protocol(self, item):
        self.measurement.switch_context(item.nodeid)
        yield
        self.measurement.switch_context("")


def main():
    sys.path.insert(0, str(Path.cwd()))
    measurement = coverage.Coverage(
        data_file=None,
        source=[os.environ["UML_SOURCE"]],
        branch=True,
        config_file=False,
    )
    measurement.start()
    try:
        return pytest.main(sys.argv[1:], plugins=[TestContexts(measurement)])
    finally:
        measurement.stop()
        # Per-test SQLite transactions on shared storage dominate test time.
        serialized = measurement.get_data().dumps()
        destination = coverage.CoverageData(basename=os.environ["COVERAGE_FILE"])
        destination.erase()
        destination.loads(serialized)
        destination.write()


if __name__ == "__main__":
    sys.exit(main())
