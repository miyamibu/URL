#!/usr/bin/env python3
"""Syntax checks for the new shell/Python validation entrypoints."""

from __future__ import annotations

import subprocess
import sys
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]


class ValidationEntrypointTests(unittest.TestCase):
    def test_python_sources_compile(self) -> None:
        sources = [
            ROOT / "scripts" / "verify_release_artifacts.py",
            ROOT / "scripts" / "verify_admin_e2e.py",
            ROOT / "scripts" / "test_verify_release_artifacts.py",
            ROOT / "scripts" / "test_verify_admin_e2e.py",
            ROOT / "scripts" / "test_validation_entrypoints.py",
        ]
        completed = subprocess.run(
            [sys.executable, "-m", "py_compile", *(str(path) for path in sources)],
            cwd=ROOT,
            check=False,
        )
        self.assertEqual(completed.returncode, 0)

    def test_shell_sources_pass_bash_n(self) -> None:
        for path in (ROOT / "scripts" / "verify_release_artifacts.sh", ROOT / "scripts" / "verify_admin_e2e.sh"):
            completed = subprocess.run(["bash", "-n", str(path)], cwd=ROOT, check=False)
            self.assertEqual(completed.returncode, 0, str(path))


if __name__ == "__main__":
    unittest.main()
