#!/usr/bin/env python3
"""Synchronize the canonical Rinbam story tracker XLSX from its CSV source.

The CSV is the editable story data source. The existing XLSX is retained as the
workbook template so sheet names, column widths, frozen panes, page margins, and
other presentation metadata remain stable. Only the two sheet data blocks and
their exact ranges are regenerated.
"""

from __future__ import annotations

import argparse
import os
import tempfile
from pathlib import Path
from xml.etree import ElementTree as ET
from zipfile import ZIP_DEFLATED, ZipFile

import verify_canonical_story_tracker as tracker


SPREADSHEET_NS = "http://schemas.openxmlformats.org/spreadsheetml/2006/main"
XML_NS = "http://www.w3.org/XML/1998/namespace"
NS = {"m": SPREADSHEET_NS}

ET.register_namespace("", SPREADSHEET_NS)


def qualified(tag: str) -> str:
    return f"{{{SPREADSHEET_NS}}}{tag}"


def excel_column_name(column_number: int) -> str:
    if column_number < 1:
        raise ValueError("column_number must be positive")
    letters = ""
    value = column_number
    while value:
        value, remainder = divmod(value - 1, 26)
        letters = chr(ord("A") + remainder) + letters
    return letters


def replace_sheet_data(root: ET.Element, table: list[list[str]]) -> str:
    if not table or not table[0]:
        raise ValueError("sheet table must not be empty")
    column_count = max(len(row) for row in table)

    old_sheet_data = root.find("m:sheetData", NS)
    if old_sheet_data is None:
        raise ValueError("workbook template is missing sheetData")
    sheet_data_index = list(root).index(old_sheet_data)
    root.remove(old_sheet_data)

    sheet_data = ET.Element(qualified("sheetData"))
    for row_number, values in enumerate(table, start=1):
        row = ET.SubElement(sheet_data, qualified("row"), {"r": str(row_number)})
        for column_number, value in enumerate(values, start=1):
            cell_reference = f"{excel_column_name(column_number)}{row_number}"
            cell = ET.SubElement(
                row,
                qualified("c"),
                {"r": cell_reference, "t": "inlineStr"},
            )
            inline_string = ET.SubElement(cell, qualified("is"))
            text = ET.SubElement(inline_string, qualified("t"))
            if value[:1].isspace() or value[-1:].isspace():
                text.set(f"{{{XML_NS}}}space", "preserve")
            text.text = value
    root.insert(sheet_data_index, sheet_data)

    last_column = excel_column_name(column_count)
    range_reference = f"A1:{last_column}{len(table)}"
    dimension = root.find("m:dimension", NS)
    if dimension is None:
        raise ValueError("workbook template is missing dimension")
    dimension.set("ref", range_reference)
    return range_reference


def update_story_sheet(xml_bytes: bytes, table: list[list[str]]) -> bytes:
    root = ET.fromstring(xml_bytes)
    range_reference = replace_sheet_data(root, table)
    auto_filter = root.find("m:autoFilter", NS)
    if auto_filter is None:
        raise ValueError("stories sheet is missing autoFilter")
    auto_filter.set("ref", range_reference)
    return ET.tostring(root, encoding="utf-8", xml_declaration=True)


def update_summary_sheet(xml_bytes: bytes, table: list[list[str]]) -> bytes:
    root = ET.fromstring(xml_bytes)
    replace_sheet_data(root, table)
    return ET.tostring(root, encoding="utf-8", xml_declaration=True)


def update_filter_database(xml_bytes: bytes, story_range: str) -> bytes:
    root = ET.fromstring(xml_bytes)
    defined_names = root.find("m:definedNames", NS)
    if defined_names is None:
        raise ValueError("workbook template is missing definedNames")
    filter_names = [
        item
        for item in defined_names.findall("m:definedName", NS)
        if item.get("name") == "_xlnm._FilterDatabase"
    ]
    if len(filter_names) != 1:
        raise ValueError("workbook template must contain exactly one _FilterDatabase")
    last_cell = story_range.rsplit(":", 1)[-1]
    last_column = "".join(character for character in last_cell if character.isalpha())
    last_row = "".join(character for character in last_cell if character.isdigit())
    absolute_range = f"$A$1:${last_column}${last_row}"
    filter_names[0].text = f"'stories'!{absolute_range}"
    return ET.tostring(root, encoding="utf-8", xml_declaration=True)


def verify_generated_workbook(
    path: Path,
    story_table: list[list[str]],
    summary_table: list[list[str]],
) -> None:
    with ZipFile(path) as workbook:
        broken_member = workbook.testzip()
        if broken_member is not None:
            raise ValueError(f"generated XLSX has a broken member: {broken_member}")
        actual_story_table = tracker.read_inline_sheet(
            workbook, "xl/worksheets/sheet1.xml"
        )
        actual_summary_table = tracker.read_inline_sheet(
            workbook, "xl/worksheets/sheet2.xml"
        )
    if actual_story_table != story_table:
        raise ValueError("generated stories sheet does not match CSV")
    if actual_summary_table != summary_table:
        raise ValueError("generated summary sheet does not match expected counts")


def synchronize(csv_path: Path, xlsx_path: Path) -> None:
    story_table = tracker.read_csv_rows(csv_path)
    if not story_table:
        raise ValueError("CSV is empty")
    if story_table[0] != tracker.EXPECTED_COLUMNS:
        raise ValueError("CSV header does not match the canonical tracker contract")
    shape_errors = tracker.validate_table_shape(story_table)
    if shape_errors:
        raise ValueError("; ".join(shape_errors))

    story_rows = tracker.row_dicts(story_table)
    summary_table = tracker.expected_summary_rows(story_rows)
    story_range = (
        f"A1:{excel_column_name(len(story_table[0]))}{len(story_table)}"
    )

    replacements: dict[str, bytes]
    with ZipFile(xlsx_path) as source:
        required_members = {
            "xl/worksheets/sheet1.xml",
            "xl/worksheets/sheet2.xml",
            "xl/workbook.xml",
        }
        missing_members = required_members - set(source.namelist())
        if missing_members:
            raise ValueError(f"workbook template is missing: {sorted(missing_members)}")
        replacements = {
            "xl/worksheets/sheet1.xml": update_story_sheet(
                source.read("xl/worksheets/sheet1.xml"), story_table
            ),
            "xl/worksheets/sheet2.xml": update_summary_sheet(
                source.read("xl/worksheets/sheet2.xml"), summary_table
            ),
            "xl/workbook.xml": update_filter_database(
                source.read("xl/workbook.xml"), story_range
            ),
        }
        source_entries = [
            (info, source.read(info.filename)) for info in source.infolist()
        ]

    file_mode = xlsx_path.stat().st_mode
    temporary_path: Path | None = None
    try:
        with tempfile.NamedTemporaryFile(
            prefix=f".{xlsx_path.name}.",
            suffix=".tmp",
            dir=xlsx_path.parent,
            delete=False,
        ) as temporary_file:
            temporary_path = Path(temporary_file.name)
        with ZipFile(temporary_path, "w", compression=ZIP_DEFLATED) as target:
            for info, original_bytes in source_entries:
                target.writestr(info, replacements.get(info.filename, original_bytes))
        verify_generated_workbook(temporary_path, story_table, summary_table)
        os.chmod(temporary_path, file_mode)
        os.replace(temporary_path, xlsx_path)
        temporary_path = None
    finally:
        if temporary_path is not None and temporary_path.exists():
            temporary_path.unlink()

    print(
        "SYNCED canonical story tracker: "
        f"stories={len(story_rows)}, summary_rows={len(summary_table)}, "
        f"range={story_range}"
    )


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--csv",
        default="docs/qa/rinbam_canonical_story_status.csv",
        type=Path,
    )
    parser.add_argument(
        "--xlsx",
        default="docs/qa/rinbam_canonical_story_status.xlsx",
        type=Path,
    )
    args = parser.parse_args()
    synchronize(args.csv, args.xlsx)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
