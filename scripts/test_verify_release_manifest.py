#!/usr/bin/env python3
"""Tests for the release manifest verifier."""

from __future__ import annotations

import json
import tempfile
import unittest
from pathlib import Path

from scripts import verify_release_manifest


ROOT = Path(__file__).resolve().parents[1]
MANIFEST = ROOT / "docs/release/release-manifest.json"


class ReleaseManifestTests(unittest.TestCase):
    def test_current_manifest_matches_sources(self) -> None:
        self.assertEqual(verify_release_manifest.verify_manifest(ROOT, MANIFEST), [])

    def test_migration_head_is_latest_timestamp(self) -> None:
        self.assertEqual(
            verify_release_manifest.parse_migration_head(ROOT / "supabase/migrations"),
            "20260731100000_restore_shared_url_normalization_v1.sql",
        )

    def test_stale_manifest_version_is_reported(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "release-manifest.json"
            data = json.loads(MANIFEST.read_text(encoding="utf-8"))
            data["android"]["versionCode"] = 18
            path.write_text(json.dumps(data), encoding="utf-8")

            failures = verify_release_manifest.verify_manifest(ROOT, path)

        self.assertTrue(any("Android versionCode mismatch" in failure for failure in failures))

    def test_current_documentation_requires_manifest_backed_block(self) -> None:
        self.assertIsNotNone(
            verify_release_manifest.current_snapshot_block(
                (ROOT / "docs/release/repo-go-evidence.md").read_text(encoding="utf-8")
            )
        )
        self.assertIsNone(verify_release_manifest.current_snapshot_block("## Current source snapshot\n"))


if __name__ == "__main__":
    unittest.main()
