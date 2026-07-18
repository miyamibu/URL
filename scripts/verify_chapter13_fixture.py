#!/usr/bin/env python3
"""Verify that the Android and iOS ChatGPT README lists share one Chapter 13 fixture."""

from __future__ import annotations

import re
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
FIXTURE = ROOT / "docs/ai/chapter13-chatgpt-use-cases.md"
ANDROID = ROOT / "app/src/main/java/jp/mimac/urlsaver/data/ExportRepository.kt"
IOS = ROOT / "ios/URLSaverShared/Data/ExportArchiveBuilder.swift"
ITEM_RE = re.compile(r"^\s*(\d{1,2})\.\s+(.+?)\s*$")


def numbered_items(path: Path) -> list[str]:
    items: dict[int, str] = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        match = ITEM_RE.match(line)
        if match:
            number = int(match.group(1))
            if 1 <= number <= 34:
                items[number] = match.group(2)
    expected_numbers = list(range(1, 35))
    if sorted(items) != expected_numbers:
        raise SystemExit(f"FAIL {path}: expected numbered items 1..34, got {sorted(items)}")
    return [items[number] for number in expected_numbers]


fixture = numbered_items(FIXTURE)
for label, path in (("Android", ANDROID), ("iOS", IOS)):
    actual = numbered_items(path)
    if actual != fixture:
        for index, (expected, observed) in enumerate(zip(fixture, actual), 1):
            if expected != observed:
                raise SystemExit(
                    f"FAIL {label} item {index}: expected={expected!r} observed={observed!r}"
                )
        raise SystemExit(f"FAIL {label}: item count differs")
    print(f"PASS {label} Chapter 13 list matches fixture")

print("PASS Chapter 13 fixture parity")
