#!/usr/bin/env python3
"""Regression tests for canonical story tracker semantic validation."""

from __future__ import annotations

import csv
import tempfile
import unittest
from datetime import datetime
from pathlib import Path
from xml.etree import ElementTree as ET
from zipfile import ZipFile

import sync_canonical_story_tracker as sync_tracker
import verify_canonical_story_tracker as tracker


AS_OF = datetime.fromisoformat("2026-08-11T23:59:59+09:00")


def row(**overrides: str) -> dict[str, str]:
    value = {column: "evidence" for column in tracker.EXPECTED_COLUMNS}
    value.update(
        {
            "story_id": "T-001",
            "feature": "fixture",
            "acceptance_criteria": "fixture acceptance",
            "validation_method": "fixture method",
            "status": "PASS fixture",
            "status_code": "PASS",
            "remaining_gate": "none",
            "documented_errors": "解消済み",
            "retest_result": "PASS fixture retest",
            "last_updated": "2026-08-11 12:00:00 +0900",
        }
    )
    value.update(overrides)
    return value


class StorySemanticTests(unittest.TestCase):
    def validate(self, fixture: dict[str, str], max_age: int | None = None) -> list[str]:
        return tracker.validate_story_semantics(
            [fixture],
            as_of=AS_OF,
            max_pass_age_days=max_age,
        )

    def test_valid_pass_has_no_gate_and_fresh_evidence(self) -> None:
        self.assertEqual(self.validate(row(), max_age=1), [])

    def test_pass_with_remaining_gate_fails(self) -> None:
        errors = self.validate(row(remaining_gate="public_web"))
        self.assertTrue(any("PASS cannot retain remaining gates" in error for error in errors))

    def test_partial_without_remaining_gate_fails(self) -> None:
        errors = self.validate(
            row(status="PARTIAL fixture", status_code="PARTIAL", remaining_gate="none")
        )
        self.assertTrue(any("PARTIAL requires at least one concrete remaining gate" in error for error in errors))

    def test_unknown_gate_fails(self) -> None:
        errors = self.validate(
            row(
                status="BLOCKED fixture",
                status_code="BLOCKED_EXTERNAL",
                remaining_gate="made_up_gate",
                documented_errors="live check is unresolved",
            )
        )
        self.assertTrue(any("unsupported remaining_gate" in error for error in errors))

    def test_future_evidence_fails(self) -> None:
        errors = self.validate(row(last_updated="2026-08-12 00:00:00 +0900"))
        self.assertTrue(any("is after --as-of" in error for error in errors))

    def test_stale_pass_fails_when_release_age_limit_is_requested(self) -> None:
        errors = self.validate(row(last_updated="2026-08-01 12:00:00 +0900"), max_age=7)
        self.assertTrue(any("PASS evidence is" in error for error in errors))

    def test_gate_summary_includes_new_gate_taxonomy(self) -> None:
        rows = [
            row(
                story_id="T-002",
                status="PARTIAL fixture",
                status_code="PARTIAL",
                remaining_gate="android_device;distribution_signing;public_privacy",
                documented_errors="device, signing, and public privacy checks unresolved",
            )
        ]
        summary = tracker.expected_gate_rows(rows)
        self.assertEqual(
            summary,
            [
                ["remaining_gate", "count", "story_ids"],
                ["android_device", "1", "T-002"],
                ["distribution_signing", "1", "T-002"],
                ["public_privacy", "1", "T-002"],
            ],
        )

    def test_csv_shape_detects_truncated_rows(self) -> None:
        errors = tracker.validate_table_shape([tracker.EXPECTED_COLUMNS, ["T-003"]])
        self.assertEqual(len(errors), 1)
        self.assertIn("expected 21", errors[0])


class StoryWorkbookSyncTests(unittest.TestCase):
    def test_excel_column_name_handles_tracker_width(self) -> None:
        self.assertEqual(sync_tracker.excel_column_name(1), "A")
        self.assertEqual(sync_tracker.excel_column_name(21), "U")
        self.assertEqual(sync_tracker.excel_column_name(27), "AA")

    def test_synchronize_updates_both_sheets_and_ranges(self) -> None:
        fixture_row = row(story_id="T-004")
        with tempfile.TemporaryDirectory() as temporary_directory:
            temp = Path(temporary_directory)
            csv_path = temp / "tracker.csv"
            xlsx_path = temp / "tracker.xlsx"
            with csv_path.open("w", encoding="utf-8-sig", newline="") as handle:
                writer = csv.writer(handle, lineterminator="\n")
                writer.writerow(tracker.EXPECTED_COLUMNS)
                writer.writerow([fixture_row[column] for column in tracker.EXPECTED_COLUMNS])

            worksheet = (
                '<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">'
                '<dimension ref="A1:A1"/><sheetData><row r="1"><c r="A1" t="inlineStr">'
                '<is><t>stale</t></is></c></row></sheetData><autoFilter ref="A1:A1"/>'
                "</worksheet>"
            )
            summary = (
                '<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">'
                '<dimension ref="A1:A1"/><sheetData><row r="1"><c r="A1" t="inlineStr">'
                '<is><t>stale</t></is></c></row></sheetData></worksheet>'
            )
            workbook = (
                '<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">'
                '<definedNames><definedName name="_xlnm._FilterDatabase" localSheetId="0">'
                "'stories'!$A$1:$A$1"
                "</definedName></definedNames></workbook>"
            )
            with ZipFile(xlsx_path, "w") as archive:
                archive.writestr("xl/worksheets/sheet1.xml", worksheet)
                archive.writestr("xl/worksheets/sheet2.xml", summary)
                archive.writestr("xl/workbook.xml", workbook)
                archive.writestr("preserved.txt", "unchanged")

            sync_tracker.synchronize(csv_path, xlsx_path)

            expected_story_table = [
                tracker.EXPECTED_COLUMNS,
                [fixture_row[column] for column in tracker.EXPECTED_COLUMNS],
            ]
            expected_summary_table = tracker.expected_summary_rows([fixture_row])
            with ZipFile(xlsx_path) as archive:
                self.assertIsNone(archive.testzip())
                self.assertEqual(archive.read("preserved.txt"), b"unchanged")
                self.assertEqual(
                    tracker.read_inline_sheet(archive, "xl/worksheets/sheet1.xml"),
                    expected_story_table,
                )
                self.assertEqual(
                    tracker.read_inline_sheet(archive, "xl/worksheets/sheet2.xml"),
                    expected_summary_table,
                )
                story_root = ET.fromstring(archive.read("xl/worksheets/sheet1.xml"))
                workbook_root = ET.fromstring(archive.read("xl/workbook.xml"))

            self.assertEqual(story_root.find("m:dimension", tracker.SPREADSHEET_NS).get("ref"), "A1:U2")
            self.assertEqual(story_root.find("m:autoFilter", tracker.SPREADSHEET_NS).get("ref"), "A1:U2")
            defined_name = workbook_root.find(
                "m:definedNames/m:definedName", tracker.SPREADSHEET_NS
            )
            self.assertEqual(defined_name.text, "'stories'!$A$1:$U$2")


if __name__ == "__main__":
    unittest.main()
