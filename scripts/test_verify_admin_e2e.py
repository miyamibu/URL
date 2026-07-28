#!/usr/bin/env python3
"""Fail-closed fixture tests for the optional admin E2E runner."""

from __future__ import annotations

import json
import os
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from typing import Dict, Optional

from scripts import verify_admin_e2e


ROOT = Path(__file__).resolve().parents[1]
SCRIPT = ROOT / "scripts" / "verify_admin_e2e.py"


class AdminE2ERunnerTests(unittest.TestCase):
    def run_runner(self, *args: str, env: Optional[Dict[str, str]] = None) -> subprocess.CompletedProcess[str]:
        merged = os.environ.copy()
        for key in (
            "RINBAM_ADMIN_E2E_BASE_URL",
            "RINBAM_ADMIN_E2E_STORAGE_STATE",
            "RINBAM_ADMIN_E2E_ROLE_SELECTOR",
            "RINBAM_ADMIN_E2E_EXPECTED_ROLE",
            "RINBAM_ADMIN_E2E_STEP_UP_SELECTOR",
            "RINBAM_ADMIN_E2E_SUBMIT_SELECTOR",
            "RINBAM_ADMIN_E2E_ERROR_SELECTOR",
            "RINBAM_ADMIN_E2E_MUTATION_ROUTE",
        ):
            merged.pop(key, None)
        if env:
            merged.update(env)
        return subprocess.run(
            [sys.executable, str(SCRIPT), "--json", *args],
            cwd=ROOT,
            env=merged,
            capture_output=True,
            text=True,
            check=False,
        )

    def test_dry_run_never_passes_without_execution(self) -> None:
        completed = self.run_runner("--dry-run")
        self.assertEqual(completed.returncode, 2)
        report = json.loads(completed.stdout)
        self.assertEqual(report["status"], "NOT_VERIFIED")
        self.assertIn("dry-run", report["reason"])

    def test_missing_playwright_or_configuration_is_not_verified(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            completed = self.run_runner(
                "--project-root",
                temporary,
                "--admin-root",
                temporary,
                env={"RINBAM_ADMIN_E2E_BASE_URL": "http://127.0.0.1:8787"},
            )
        self.assertEqual(completed.returncode, 2)
        report = json.loads(completed.stdout)
        self.assertEqual(report["status"], "NOT_VERIFIED")

    def test_admin_screen_defaults_to_root_path(self) -> None:
        args = verify_admin_e2e.parse_args(["--dry-run"])
        values, _ = verify_admin_e2e.configuration(args)
        self.assertEqual(values["RINBAM_ADMIN_E2E_PROTECTED_PATH"], "/")
        self.assertEqual(values["RINBAM_ADMIN_E2E_SENSITIVE_PATH"], "/")
        spec = (ROOT / "scripts" / "admin_e2e.spec.cjs").read_text(encoding="utf-8")
        self.assertIn('RINBAM_ADMIN_E2E_PROTECTED_PATH || "/"', spec)


if __name__ == "__main__":
    unittest.main()
