#!/usr/bin/env python3
"""Verify the canonical Rinbam story tracker workbook.

This script intentionally uses only the Python standard library so it can run
on a clean macOS development machine without installing spreadsheet packages.
"""

from __future__ import annotations

import argparse
import csv
from collections import Counter
from datetime import datetime, time, timezone
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

ALLOWED_STATUS_CODES = {
    "BLOCKED_EXTERNAL",
    "DEFERRED",
    "LOCAL_ONLY",
    "PARTIAL",
    "PASS",
    "REMOVED",
}

GATE_ORDER = [
    "android_device",
    "iphone_device",
    "distribution_signing",
    "supabase_auth",
    "store_console",
    "resend_live",
    "public_web",
    "public_privacy",
    "render_media",
    "design_required",
    "production_data",
    "backup_restore",
]
ALLOWED_GATES = set(GATE_ORDER)
STATUSES_REQUIRING_NO_GATE = {"PASS", "REMOVED"}
STATUSES_REQUIRING_GATE = {"BLOCKED_EXTERNAL", "DEFERRED", "LOCAL_ONLY", "PARTIAL"}


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


def parse_gates(row: dict[str, str]) -> list[str]:
    value = row.get("remaining_gate", "").strip()
    if not value or value == "none":
        return []
    return [gate.strip() for gate in value.split(";") if gate.strip()]


def validate_table_shape(table: list[list[str]]) -> list[str]:
    errors: list[str] = []
    for line_number, row in enumerate(table, start=1):
        if len(row) != len(EXPECTED_COLUMNS):
            errors.append(
                f"CSV line {line_number} has {len(row)} columns; expected {len(EXPECTED_COLUMNS)}"
            )
    return errors


def validate_story_semantics(
    rows: list[dict[str, str]],
    *,
    as_of: datetime,
    max_pass_age_days: int | None,
) -> list[str]:
    errors: list[str] = []
    for row in rows:
        story_id = row.get("story_id", "<missing>")
        status_code = row.get("status_code", "").strip()
        gates = parse_gates(row)

        if status_code not in ALLOWED_STATUS_CODES:
            errors.append(f"{story_id}: unsupported status_code {status_code!r}")
        unknown_gates = sorted(set(gates) - ALLOWED_GATES)
        if unknown_gates:
            errors.append(f"{story_id}: unsupported remaining_gate values {unknown_gates}")
        duplicate_gates = sorted({gate for gate in gates if gates.count(gate) > 1})
        if duplicate_gates:
            errors.append(f"{story_id}: duplicate remaining_gate values {duplicate_gates}")
        if status_code in STATUSES_REQUIRING_NO_GATE and gates:
            errors.append(
                f"{story_id}: {status_code} cannot retain remaining gates {gates}; "
                "use a gated status until those checks are complete"
            )
        if status_code in STATUSES_REQUIRING_GATE and not gates:
            errors.append(f"{story_id}: {status_code} requires at least one concrete remaining gate")

        for required_column in [
            "feature",
            "acceptance_criteria",
            "validation_method",
            "status",
            "status_code",
            "remaining_gate",
            "retest_result",
            "last_updated",
        ]:
            if not row.get(required_column, "").strip():
                errors.append(f"{story_id}: missing required evidence column {required_column}")

        raw_updated = row.get("last_updated", "").strip()
        try:
            updated = datetime.strptime(raw_updated, "%Y-%m-%d %H:%M:%S %z")
        except ValueError:
            errors.append(
                f"{story_id}: invalid last_updated {raw_updated!r}; expected YYYY-MM-DD HH:MM:SS +ZZZZ"
            )
            continue
        if updated > as_of:
            errors.append(f"{story_id}: last_updated {raw_updated!r} is after --as-of")
        if max_pass_age_days is not None and status_code == "PASS":
            age_days = (as_of - updated).total_seconds() / 86_400
            if age_days > max_pass_age_days:
                errors.append(
                    f"{story_id}: PASS evidence is {age_days:.1f} days old; "
                    f"maximum is {max_pass_age_days} days"
                )

        if status_code == "PASS" and "PASS" not in row.get("retest_result", "").upper():
            errors.append(f"{story_id}: PASS requires a PASS retest_result")
        if status_code in STATUSES_REQUIRING_GATE:
            documented = row.get("documented_errors", "").strip()
            if not documented or documented in {"なし", "N/A", "none"}:
                errors.append(f"{story_id}: {status_code} requires a documented unresolved reason")
    return errors


def row_text(row: dict[str, str], *columns: str) -> str:
    return "".join(row.get(column, "") for column in columns)


def expected_gate_rows(rows: list[dict[str, str]]) -> list[list[str]]:
    active_gates = {gate for row in rows for gate in parse_gates(row)}
    output: list[list[str]] = [["remaining_gate", "count", "story_ids"]]
    for gate_name in [gate for gate in GATE_ORDER if gate in active_gates]:
        story_ids = [row["story_id"] for row in rows if gate_name in parse_gates(row)]
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
    parser.add_argument(
        "--as-of",
        type=datetime.fromisoformat,
        help="Evidence cutoff as ISO-8601 date/time. Defaults to the current UTC time.",
    )
    parser.add_argument(
        "--max-pass-age-days",
        type=int,
        help="Fail when PASS evidence is older than this many days.",
    )
    args = parser.parse_args()

    if args.max_pass_age_days is not None and args.max_pass_age_days < 0:
        raise SystemExit("FAIL --max-pass-age-days must be non-negative")
    as_of = args.as_of or datetime.now(timezone.utc)
    if as_of.tzinfo is None:
        as_of = datetime.combine(as_of.date(), time.max, tzinfo=timezone.utc)

    csv_table = read_csv_rows(args.csv)
    if not csv_table:
        raise SystemExit("FAIL CSV is empty")
    assert_equal("CSV header", csv_table[0], EXPECTED_COLUMNS)
    shape_errors = validate_table_shape(csv_table)
    if shape_errors:
        raise SystemExit("FAIL CSV shape:\n- " + "\n- ".join(shape_errors))

    rows = row_dicts(csv_table)
    story_ids = [row["story_id"] for row in rows]
    assert_equal("story row count", len(rows), len(story_ids))
    duplicate_ids = sorted({story_id for story_id in story_ids if story_ids.count(story_id) > 1})
    assert_equal("duplicate story IDs", duplicate_ids, [])
    missing_ids = [index + 2 for index, story_id in enumerate(story_ids) if not story_id]
    assert_equal("missing story IDs", missing_ids, [])
    semantic_errors = validate_story_semantics(
        rows,
        as_of=as_of,
        max_pass_age_days=args.max_pass_age_days,
    )
    if semantic_errors:
        raise SystemExit("FAIL story semantics:\n- " + "\n- ".join(semantic_errors))

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
