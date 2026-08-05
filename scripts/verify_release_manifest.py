#!/usr/bin/env python3
"""Verify release versions, migration head, and current release documents."""

from __future__ import annotations

import argparse
import json
import plistlib
import re
import sys
from pathlib import Path
from typing import Any


ROOT = Path(__file__).resolve().parents[1]
DEFAULT_MANIFEST = ROOT / "docs/release/release-manifest.json"
MIGRATION_RE = re.compile(r"^(?P<version>\d{14})_[a-z0-9_]+\.sql$")


def _read_json(path: Path) -> dict[str, Any]:
    with path.open(encoding="utf-8") as handle:
        value = json.load(handle)
    if not isinstance(value, dict):
        raise ValueError(f"manifest must contain a JSON object: {path}")
    return value


def _required_string(value: Any, name: str) -> str:
    if not isinstance(value, str) or not value:
        raise ValueError(f"manifest field must be a non-empty string: {name}")
    return value


def _required_int(value: Any, name: str) -> int:
    if isinstance(value, bool) or not isinstance(value, int) or value < 1:
        raise ValueError(f"manifest field must be a positive integer: {name}")
    return value


def _relative_path(root: Path, value: Any, name: str) -> Path:
    relative = _required_string(value, name)
    path = root / relative
    if not path.is_file() and not path.is_dir():
        raise ValueError(f"manifest path does not exist: {name}={relative}")
    return path


def parse_android_source(path: Path) -> dict[str, Any]:
    text = path.read_text(encoding="utf-8")
    patterns = {
        "applicationId": r'applicationId\s*=\s*"([^"]+)"',
        "versionName": r'versionName\s*=\s*"([^"]+)"',
        "versionCode": r"versionCode\s*=\s*(\d+)",
    }
    values: dict[str, Any] = {}
    for name, pattern in patterns.items():
        match = re.search(pattern, text)
        if not match:
            raise ValueError(f"Android source field not found: {name} in {path}")
        values[name] = int(match.group(1)) if name == "versionCode" else match.group(1)
    return values


def parse_plist(path: Path) -> dict[str, Any]:
    with path.open("rb") as handle:
        values = plistlib.load(handle)
    if not isinstance(values, dict):
        raise ValueError(f"Info.plist must contain a dictionary: {path}")
    result: dict[str, Any] = {}
    for name in ("CFBundleShortVersionString", "CFBundleVersion"):
        value = values.get(name)
        if not isinstance(value, (str, int)) or not str(value):
            raise ValueError(f"Info.plist field not found: {name} in {path}")
        result[name] = str(value)
    return result


def parse_migration_head(directory: Path) -> str:
    migrations = sorted(path.name for path in directory.glob("*.sql"))
    if not migrations:
        raise ValueError(f"no migration files found: {directory}")

    invalid = [name for name in migrations if MIGRATION_RE.fullmatch(name) is None]
    if invalid:
        raise ValueError(f"migration filenames must start with a 14-digit timestamp: {', '.join(invalid)}")

    timestamps = [MIGRATION_RE.fullmatch(name).group("version") for name in migrations]  # type: ignore[union-attr]
    duplicate_timestamps = sorted({timestamp for timestamp in timestamps if timestamps.count(timestamp) > 1})
    if duplicate_timestamps:
        raise ValueError(f"migration timestamps must be unique: {', '.join(duplicate_timestamps)}")

    return migrations[-1]


def current_snapshot_block(text: str) -> str | None:
    match = re.search(
        r"^##\s+Current source snapshot \(manifest-backed\)\s*$([\s\S]*?)(?=^##\s|\Z)",
        text,
        flags=re.MULTILINE | re.IGNORECASE,
    )
    return match.group(1) if match else None


def verify_manifest(root: Path = ROOT, manifest_path: Path = DEFAULT_MANIFEST) -> list[str]:
    failures: list[str] = []
    try:
        manifest = _read_json(manifest_path)
        if manifest.get("schemaVersion") != 1:
            raise ValueError("manifest schemaVersion must be 1")
        if "generatedAt" in manifest or "capturedAt" in manifest:
            raise ValueError("manifest must not contain a hand-maintained current timestamp")

        android = manifest["android"]
        ios = manifest["ios"]
        supabase = manifest["supabase"]
        if not isinstance(android, dict) or not isinstance(ios, dict) or not isinstance(supabase, dict):
            raise ValueError("android, ios, and supabase manifest sections are required objects")

        android_source = _relative_path(root, android.get("source"), "android.source")
        expected_android = {
            "applicationId": _required_string(android.get("applicationId"), "android.applicationId"),
            "versionName": _required_string(android.get("versionName"), "android.versionName"),
            "versionCode": _required_int(android.get("versionCode"), "android.versionCode"),
        }
        actual_android = parse_android_source(android_source)
        for name, expected in expected_android.items():
            if actual_android[name] != expected:
                failures.append(f"Android {name} mismatch: manifest={expected!r}, source={actual_android[name]!r}")

        app_plist = _relative_path(root, ios.get("appPlist"), "ios.appPlist")
        share_plist = _relative_path(root, ios.get("shareExtensionPlist"), "ios.shareExtensionPlist")
        project = _relative_path(root, ios.get("project"), "ios.project")
        expected_ios = {
            "CFBundleShortVersionString": _required_string(ios.get("shortVersion"), "ios.shortVersion"),
            "CFBundleVersion": str(_required_int(ios.get("build"), "ios.build")),
        }
        for plist_name, plist_path in (("app", app_plist), ("share extension", share_plist)):
            actual_plist = parse_plist(plist_path)
            for name, expected in expected_ios.items():
                if actual_plist[name] != expected:
                    failures.append(
                        f"iOS {plist_name} {name} mismatch: manifest={expected!r}, source={actual_plist[name]!r}"
                    )

        project_text = project.read_text(encoding="utf-8")
        expected_bundle_ids = {
            "ios.bundleId": _required_string(ios.get("bundleId"), "ios.bundleId"),
            "ios.shareExtensionBundleId": _required_string(
                ios.get("shareExtensionBundleId"), "ios.shareExtensionBundleId"
            ),
        }
        for name, expected in expected_bundle_ids.items():
            if f"PRODUCT_BUNDLE_IDENTIFIER = {expected};" not in project_text:
                failures.append(f"iOS {name} is missing from {project}")

        migration_directory = _relative_path(
            root, supabase.get("migrationDirectory"), "supabase.migrationDirectory"
        )
        expected_head = _required_string(supabase.get("migrationHead"), "supabase.migrationHead")
        actual_head = parse_migration_head(migration_directory)
        if actual_head != expected_head:
            failures.append(f"Supabase migration head mismatch: manifest={expected_head!r}, source={actual_head!r}")

        current_documents = manifest.get("currentDocumentation")
        if not isinstance(current_documents, list) or not current_documents:
            raise ValueError("currentDocumentation must be a non-empty array")
        expected_lines = (
            f"Android: `{expected_android['applicationId']}`, "
            f"`versionName={expected_android['versionName']}`, `versionCode={expected_android['versionCode']}`",
            f"iOS: `{expected_bundle_ids['ios.bundleId']}`, `shortVersion={expected_ios['CFBundleShortVersionString']}`, "
            f"`build={expected_ios['CFBundleVersion']}`; share extension `"
            f"{expected_bundle_ids['ios.shareExtensionBundleId']}`",
            f"Supabase migration head: `{expected_head}`",
        )
        for document in current_documents:
            document_path = _relative_path(root, document, "currentDocumentation[]")
            block = current_snapshot_block(document_path.read_text(encoding="utf-8"))
            if block is None:
                failures.append(f"current documentation lacks manifest-backed snapshot heading: {document}")
                continue
            for expected_line in expected_lines:
                if expected_line not in block:
                    failures.append(f"current documentation is inconsistent: {document}: {expected_line}")

        historical_documents = manifest.get("historicalDocumentation")
        if not isinstance(historical_documents, list):
            raise ValueError("historicalDocumentation must be an array")
        for document in historical_documents:
            document_path = _relative_path(root, document, "historicalDocumentation[]")
            first_lines = "\n".join(document_path.read_text(encoding="utf-8").splitlines()[:40])
            if re.search(r"\bhistorical\b", first_lines, flags=re.IGNORECASE) is None:
                failures.append(f"historical documentation is not explicitly marked Historical: {document}")
    except (KeyError, TypeError, ValueError, OSError, json.JSONDecodeError) as error:
        failures.append(str(error))
    return failures


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--manifest",
        type=Path,
        default=DEFAULT_MANIFEST,
        help="manifest path (default: docs/release/release-manifest.json)",
    )
    args = parser.parse_args()
    manifest_path = args.manifest if args.manifest.is_absolute() else ROOT / args.manifest
    failures = verify_manifest(ROOT, manifest_path)
    if failures:
        for failure in failures:
            print(f"FAIL {failure}")
        return 1
    print("OK release manifest matches Android/iOS sources, migration head, and release documents")
    return 0


if __name__ == "__main__":
    sys.exit(main())
