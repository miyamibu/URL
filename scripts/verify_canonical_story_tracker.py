#!/usr/bin/env python3
"""Verify the canonical Rinbam story tracker workbook.

This script intentionally uses only the Python standard library so it can run
on a clean macOS development machine without installing spreadsheet packages.
"""

from __future__ import annotations

import argparse
import csv
from collections import Counter
from pathlib import Path
from xml.etree import ElementTree as ET
from zipfile import ZipFile


EXPECTED_COLUMNS = [
    "story_id",
    "role",
    "platform",
    "feature_area",
    "feature",
    "code_basis",
    "expected_story",
    "acceptance_criteria",
    "validation_method",
    "production_equivalent_check",
    "status",
    "status_code",
    "remaining_gate",
    "first_test_result",
    "documented_errors",
    "fix_status",
    "retest_result",
    "android_device_result",
    "iphone_device_result",
    "notes",
    "last_updated",
]

SPREADSHEET_NS = {"m": "http://schemas.openxmlformats.org/spreadsheetml/2006/main"}


def read_csv_rows(path: Path) -> list[list[str]]:
    with path.open("r", encoding="utf-8-sig", newline="") as handle:
        return list(csv.reader(handle))


def read_shared_strings(zip_file: ZipFile) -> list[str]:
    if "xl/sharedStrings.xml" not in zip_file.namelist():
        return []
    root = ET.fromstring(zip_file.read("xl/sharedStrings.xml"))
    values: list[str] = []
    for item in root.findall("m:si", SPREADSHEET_NS):
        texts = [text_el.text or "" for text_el in item.findall(".//m:t", SPREADSHEET_NS)]
        values.append("".join(texts))
    return values


def read_inline_sheet(zip_file: ZipFile, sheet_path: str) -> list[list[str]]:
    shared_strings = read_shared_strings(zip_file)
    root = ET.fromstring(zip_file.read(sheet_path))
    rows: list[list[str]] = []
    for row_el in root.findall(".//m:sheetData/m:row", SPREADSHEET_NS):
        values: list[str] = []
        for cell in row_el.findall("m:c", SPREADSHEET_NS):
            inline_text = cell.find("m:is/m:t", SPREADSHEET_NS)
            value_text = cell.find("m:v", SPREADSHEET_NS)
            if inline_text is not None:
                values.append(inline_text.text or "")
            elif value_text is not None:
                raw_value = value_text.text or ""
                if cell.get("t") == "s":
                    values.append(shared_strings[int(raw_value)])
                else:
                    values.append(raw_value)
            else:
                values.append("")
        rows.append(values)
    return rows


def row_dicts(table: list[list[str]]) -> list[dict[str, str]]:
    header = table[0]
    return [dict(zip(header, row)) for row in table[1:]]


def row_text(row: dict[str, str], *columns: str) -> str:
    return "".join(row.get(column, "") for column in columns)


def expected_gate_rows(rows: list[dict[str, str]]) -> list[list[str]]:
    def physical_android(row: dict[str, str]) -> bool:
        value = row.get("android_device_result", "")
        return bool(value) and ("未実機" in value or "NOT VERIFIED" in value)

    def physical_iphone(row: dict[str, str]) -> bool:
        value = row.get("iphone_device_result", "")
        return bool(value) and ("未実機" in value or "NOT VERIFIED" in value)

    def supabase_or_auth(row: dict[str, str]) -> bool:
        if row.get("story_id") == "ES-002":
            return False
        text = row_text(row, "documented_errors", "retest_result", "notes")
        return any(
            marker in text
            for marker in ["Supabase", "auth", "Auth", "live RPC", "本番メール", "live auth"]
        )

    def store_or_public(row: dict[str, str]) -> bool:
        text = row_text(row, "documented_errors", "fix_status", "retest_result", "notes")
        return any(
            marker in text
            for marker in ["Play Console", "App Store", "store console", "public privacy", "公開", "ストア"]
        )

    def resend_live(row: dict[str, str]) -> bool:
        text = row_text(row, "documented_errors", "retest_result", "notes")
        return any(marker in text for marker in ["Resend live", "live Resend", "Resend API"])

    def connected_android(row: dict[str, str]) -> bool:
        text = row_text(row, "documented_errors", "first_test_result", "retest_result")
        if "connectedDebugAndroidTestはurlsaverParityApi35 AVDでPASS" in text:
            return False
        if "connectedDebugAndroidTest 成功" in text:
            return False
        return "connectedDebugAndroidTest" in text and any(
            marker in text
            for marker in ["未実行", "未検証", "CONNECTED_TEST_GAP", "connected instrumentation gap"]
        )

    if "remaining_gate" in rows[0]:
        gate_names = [
            "physical_android",
            "physical_iphone",
            "render_media",
            "supabase_auth",
            "store_console",
            "resend_live",
            "public_web",
            "design_required",
        ]
        output: list[list[str]] = [["remaining_gate", "count", "story_ids"]]
        for gate_name in gate_names:
            story_ids = []
            for row in rows:
                if row.get("story_id") == "ES-001":
                    continue
                gates_for_row = {
                    gate.strip()
                    for gate in row.get("remaining_gate", "").split(";")
                    if gate.strip() and gate.strip() != "none"
                }
                if gate_name in gates_for_row:
                    story_ids.append(row["story_id"])
            output.append([gate_name, str(len(story_ids)), ",".join(story_ids)])
        return output

    gates = [
        ("physical_Android", physical_android),
        ("physical_iPhone", physical_iphone),
        ("supabase_or_auth_live", supabase_or_auth),
        ("store_or_public_console", store_or_public),
        ("resend_live", resend_live),
        ("connected_android_instrumentation", connected_android),
    ]
    output: list[list[str]] = [["remaining_gate", "count", "story_ids"]]
    for gate_name, predicate in gates:
        story_ids = [
            row["story_id"]
            for row in rows
            if row.get("story_id") != "ES-001" and predicate(row)
        ]
        output.append([gate_name, str(len(story_ids)), ",".join(story_ids)])
    return output


def expected_summary_rows(rows: list[dict[str, str]]) -> list[list[str]]:
    status_column = "status_code" if "status_code" in rows[0] else "status"
    status_counts = Counter(row.get(status_column, "") for row in rows)
    summary = [[status_column, "count"]]
    for status, count in sorted(status_counts.items()):
        summary.append([status, str(count)])
    summary.extend(expected_gate_rows(rows))
    return summary


def assert_equal(label: str, actual: object, expected: object) -> None:
    if actual != expected:
        raise SystemExit(f"FAIL {label}: actual={actual!r} expected={expected!r}")
    print(f"PASS {label}")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--csv",
        default="docs/qa/rinbam_canonical_story_status.csv",
        type=Path,
        help="Path to the canonical story tracker CSV.",
    )
    parser.add_argument(
        "--xlsx",
        default="docs/qa/rinbam_canonical_story_status.xlsx",
        type=Path,
        help="Path to the canonical story tracker XLSX.",
    )
    args = parser.parse_args()

    csv_table = read_csv_rows(args.csv)
    if not csv_table:
        raise SystemExit("FAIL CSV is empty")
    assert_equal("CSV header", csv_table[0], EXPECTED_COLUMNS)

    rows = row_dicts(csv_table)
    story_ids = [row["story_id"] for row in rows]
    assert_equal("story row count", len(rows), len(story_ids))
    duplicate_ids = sorted({story_id for story_id in story_ids if story_ids.count(story_id) > 1})
    assert_equal("duplicate story IDs", duplicate_ids, [])
    missing_ids = [index + 2 for index, story_id in enumerate(story_ids) if not story_id]
    assert_equal("missing story IDs", missing_ids, [])

    with ZipFile(args.xlsx) as workbook:
        broken_member = workbook.testzip()
        assert_equal("XLSX zip integrity", broken_member, None)
        xlsx_story_table = read_inline_sheet(workbook, "xl/worksheets/sheet1.xml")
        xlsx_summary_table = read_inline_sheet(workbook, "xl/worksheets/sheet2.xml")

    assert_equal("CSV/XLSX story sheet", xlsx_story_table, csv_table)
    assert_equal("XLSX summary sheet", xlsx_summary_table, expected_summary_rows(rows))

    gate_rows = expected_gate_rows(rows)[1:]
    print("Remaining gates:")
    for gate, count, story_ids_csv in gate_rows:
        print(f"- {gate}: {count} ({story_ids_csv})")

    print("PASS canonical story tracker verification")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
