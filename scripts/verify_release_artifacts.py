#!/usr/bin/env python3
"""Fail-closed verification for Android and iOS release artifacts.

This script deliberately does not build, sign, upload, or mutate an artifact.
It only verifies an already-produced artifact with the platform signing tools.
"""

from __future__ import annotations

import argparse
import json
import os
import plistlib
import re
import shutil
import struct
import subprocess
import sys
import tempfile
import zipfile
import xml.etree.ElementTree as ET
from pathlib import Path
from typing import Any, Dict, List, Optional, Sequence, Tuple


PASS = "PASS"
FAIL = "FAIL"
NOT_VERIFIED = "NOT_VERIFIED"

# This verifier is intentionally bound to the release identities of this
# repository. A valid signature from another application must never be enough
# to produce PASS.
EXPECTED_ANDROID_PACKAGE = "jp.miyamibu.urlalbum"
EXPECTED_IOS_BUNDLE_ID = "com.mibu.codebridge.ios"
EXPECTED_IOS_SHARE_EXTENSION_BUNDLE_ID = "com.mibu.codebridge.ios.share"


def compact(text: str, limit: int = 600) -> str:
    value = " ".join(text.split())
    if len(value) <= limit:
        return value
    return value[: limit - 3] + "..."


def command_text(command: Sequence[str]) -> str:
    return " ".join(shlex_quote(part) for part in command)


def shlex_quote(value: str) -> str:
    if re.fullmatch(r"[A-Za-z0-9_./:=+-]+", value):
        return value
    return "'" + value.replace("'", "'\\''") + "'"


def result(
    kind: str,
    path: Path,
    status: str,
    reason: str,
    *,
    command: Optional[Sequence[str]] = None,
    details: Optional[Dict[str, Any]] = None,
) -> Dict[str, Any]:
    item: Dict[str, Any] = {
        "kind": kind,
        "path": str(path),
        "status": status,
        "reason": reason,
    }
    if command is not None:
        item["command"] = command_text(command)
    if details:
        item["details"] = details
    return item


def annotate(item: Dict[str, Any], details: Dict[str, Any]) -> Dict[str, Any]:
    item.setdefault("details", {}).update(details)
    return item


def _u16(data: bytes, offset: int) -> int:
    return struct.unpack_from("<H", data, offset)[0]


def _u32(data: bytes, offset: int) -> int:
    return struct.unpack_from("<I", data, offset)[0]


def _decode_android_string(data: bytes, offset: int, utf8: bool) -> str:
    if utf8:
        first = data[offset]
        offset += 1
        if first & 0x80:
            first = ((first & 0x7F) << 8) | data[offset]
            offset += 1
        byte_length = data[offset]
        offset += 1
        if byte_length & 0x80:
            byte_length = ((byte_length & 0x7F) << 8) | data[offset]
            offset += 1
        return data[offset : offset + byte_length].decode("utf-8", errors="replace")

    length = _u16(data, offset)
    offset += 2
    if length & 0x8000:
        length = ((length & 0x7FFF) << 16) | _u16(data, offset)
        offset += 2
    return data[offset : offset + (length * 2)].decode("utf-16le", errors="replace")


def _android_string_pool(data: bytes) -> Tuple[List[str], int]:
    if len(data) < 28 or _u16(data, 0) != 0x0001:
        raise ValueError("Android string pool is missing")
    header_size = _u16(data, 2)
    chunk_size = _u32(data, 4)
    string_count = _u32(data, 8)
    flags = _u32(data, 16)
    strings_start = _u32(data, 20)
    if header_size < 28 or chunk_size > len(data) or strings_start > len(data):
        raise ValueError("Android string pool header is invalid")
    offsets_start = header_size
    offsets_end = offsets_start + (string_count * 4)
    if offsets_end > len(data):
        raise ValueError("Android string pool offsets are invalid")
    utf8 = bool(flags & 0x00000100)
    strings: List[str] = []
    for index in range(string_count):
        string_offset = _u32(data, offsets_start + (index * 4))
        absolute = strings_start + string_offset
        if absolute >= len(data):
            raise ValueError("Android string pool string offset is invalid")
        strings.append(_decode_android_string(data, absolute, utf8))
    return strings, chunk_size


def _android_binary_manifest_package(data: bytes) -> Optional[str]:
    offset = 0
    if len(data) >= 8 and _u16(data, 0) == 0x0003:  # RES_XML_TYPE
        root_header_size = _u16(data, 2)
        root_chunk_size = _u32(data, 4)
        if root_header_size < 8 or root_chunk_size > len(data):
            raise ValueError("Android XML root chunk is invalid")
        offset = root_header_size
    strings, string_pool_size = _android_string_pool(data[offset:])
    offset += string_pool_size
    while offset + 8 <= len(data):
        chunk_type = _u16(data, offset)
        header_size = _u16(data, offset + 2)
        chunk_size = _u32(data, offset + 4)
        if header_size < 8 or chunk_size < header_size or offset + chunk_size > len(data):
            raise ValueError("Android manifest chunk is invalid")
        if chunk_type == 0x0102:  # RES_XML_START_ELEMENT_TYPE
            node_start = offset + 8
            if node_start + 36 > offset + chunk_size:
                raise ValueError("Android manifest element is truncated")
            element_name_index = _u32(data, node_start + 12)
            element_name = strings[element_name_index] if element_name_index < len(strings) else ""
            if element_name == "manifest":
                attribute_start = _u16(data, node_start + 16)
                attribute_size = _u16(data, node_start + 18)
                attribute_count = _u16(data, node_start + 20)
                if attribute_size < 20:
                    raise ValueError("Android manifest attribute size is invalid")
                # attributeStart is relative to the ResXMLTree_attrExt
                # structure, which begins after the line/comment fields.
                attributes_start = node_start + 8 + attribute_start
                for index in range(attribute_count):
                    attribute = attributes_start + (index * attribute_size)
                    if attribute + 20 > offset + chunk_size:
                        raise ValueError("Android manifest attribute is truncated")
                    name_index = _u32(data, attribute + 4)
                    name = strings[name_index] if name_index < len(strings) else ""
                    if name != "package":
                        continue
                    raw_value = _u32(data, attribute + 8)
                    if raw_value != 0xFFFFFFFF and raw_value < len(strings):
                        return strings[raw_value]
                    value_type = data[attribute + 15]
                    value_data = _u32(data, attribute + 16)
                    if value_type == 0x03 and value_data < len(strings):
                        return strings[value_data]
                    return None
        offset += chunk_size
    return None


def _protobuf_fields(data: bytes) -> List[Tuple[int, int, Any]]:
    fields: List[Tuple[int, int, Any]] = []
    offset = 0
    while offset < len(data):
        tag, offset = _protobuf_varint(data, offset)
        field_number = tag >> 3
        wire_type = tag & 0x07
        if field_number <= 0:
            raise ValueError("protobuf field number is invalid")
        if wire_type == 0:
            value, offset = _protobuf_varint(data, offset)
        elif wire_type == 1:
            if offset + 8 > len(data):
                raise ValueError("protobuf fixed64 field is truncated")
            value = data[offset : offset + 8]
            offset += 8
        elif wire_type == 2:
            length, offset = _protobuf_varint(data, offset)
            if length > len(data) - offset:
                raise ValueError("protobuf length-delimited field is truncated")
            value = data[offset : offset + length]
            offset += length
        elif wire_type == 5:
            if offset + 4 > len(data):
                raise ValueError("protobuf fixed32 field is truncated")
            value = data[offset : offset + 4]
            offset += 4
        else:
            raise ValueError("protobuf wire type is unsupported")
        fields.append((field_number, wire_type, value))
    return fields


def _protobuf_varint(data: bytes, offset: int) -> Tuple[int, int]:
    value = 0
    shift = 0
    while offset < len(data) and shift <= 63:
        byte = data[offset]
        offset += 1
        value |= (byte & 0x7F) << shift
        if byte < 0x80:
            return value, offset
        shift += 7
    raise ValueError("protobuf varint is invalid")


def _protobuf_text(value: Any) -> Optional[str]:
    if not isinstance(value, bytes):
        return None
    try:
        text = value.decode("utf-8")
    except UnicodeDecodeError:
        return None
    if not text or "\x00" in text:
        return None
    return text


def _android_proto_manifest_package(data: bytes) -> Optional[str]:
    """Read the package from the Android App Bundle protobuf manifest."""

    def walk(message: bytes, depth: int = 0) -> Optional[str]:
        if depth > 12:
            return None
        try:
            fields = _protobuf_fields(message)
        except ValueError:
            return None
        if any(field == 3 and wire == 2 and _protobuf_text(value) == "manifest" for field, wire, value in fields):
            for field, wire, value in fields:
                if field != 4 or wire != 2:
                    continue
                try:
                    attribute_fields = _protobuf_fields(value)
                except ValueError:
                    continue
                name = next(
                    (_protobuf_text(item) for number, wire_type, item in attribute_fields if number == 2 and wire_type == 2),
                    None,
                )
                package = next(
                    (_protobuf_text(item) for number, wire_type, item in attribute_fields if number == 3 and wire_type == 2),
                    None,
                )
                if name == "package" and package:
                    return package
        for _, wire, value in fields:
            if wire == 2:
                package = walk(value, depth + 1)
                if package:
                    return package
        return None

    return walk(data)


def _xml_manifest_package(data: bytes) -> Optional[str]:
    root = ET.fromstring(data)
    package = root.attrib.get("package")
    if package:
        return package
    return None


def _manifest_package(data: bytes) -> Optional[str]:
    stripped = data.lstrip()
    if stripped.startswith(b"<"):
        return _xml_manifest_package(data)
    try:
        package = _android_proto_manifest_package(data)
        if package:
            return package
    except (ValueError, struct.error):
        pass
    return _android_binary_manifest_package(data)


def read_android_package_identity(path: Path) -> Tuple[Optional[str], str]:
    """Read the package identity without trusting the signing tool output."""
    try:
        with zipfile.ZipFile(path) as archive:
            names = archive.namelist()
            candidates = []
            for name in names:
                if name == "AndroidManifest.xml" or name == "base/manifest/AndroidManifest.xml":
                    candidates.append(name)
            if not candidates:
                candidates = sorted(
                    name for name in names if name.endswith("/manifest/AndroidManifest.xml")
                )
            if not candidates:
                return None, "AndroidManifest.xml is missing"
            packages: List[Tuple[str, str]] = []
            for name in candidates:
                package = _manifest_package(archive.read(name))
                if package:
                    packages.append((name, package))
            if not packages:
                return None, "AndroidManifest.xml package attribute could not be read"
            unique_packages = {package for _, package in packages}
            if len(unique_packages) != 1:
                return None, "AndroidManifest.xml package identities disagree"
            name, package = packages[0]
            return package, f"manifest={name}"
    except (OSError, zipfile.BadZipFile, ET.ParseError, struct.error, ValueError) as error:
        return None, compact(str(error))


def read_bundle_identifier(app_path: Path) -> Tuple[Optional[str], str]:
    info_path = app_path / "Info.plist"
    try:
        info = plistlib.loads(info_path.read_bytes())
    except (OSError, plistlib.InvalidFileException, ValueError) as error:
        return None, f"Info.plist could not be read: {compact(str(error))}"
    bundle_id = info.get("CFBundleIdentifier") if isinstance(info, dict) else None
    if not isinstance(bundle_id, str) or not bundle_id or "$" in bundle_id:
        return None, "Info.plist CFBundleIdentifier is missing or unresolved"
    return bundle_id, f"Info.plist={info_path}"


def team_identifier(display_output: str) -> Optional[str]:
    match = re.search(r"^TeamIdentifier=([^\s]+)\s*$", display_output, flags=re.MULTILINE)
    return match.group(1) if match else None


def normalize_sha256(value: str) -> Optional[str]:
    normalized = re.sub(r"[^0-9a-f]", "", value.lower())
    return normalized if re.fullmatch(r"[0-9a-f]{64}", normalized) else None


def certificate_sha256_digests(output: str) -> List[str]:
    values = re.findall(
        r"certificate\s+SHA-256\s+digest:\s*([0-9a-f: ]+)",
        output,
        flags=re.IGNORECASE,
    )
    return sorted({digest for value in values if (digest := normalize_sha256(value))})


def keytool_certificate_sha256_digests(output: str) -> List[str]:
    """Read SHA256/SHA-256 certificate fingerprints from keytool output."""
    values = re.findall(
        r"^\s*SHA-?256\s*:\s*([0-9a-f: ]+)\s*$",
        output,
        flags=re.IGNORECASE | re.MULTILINE,
    )
    return sorted({digest for value in values if (digest := normalize_sha256(value))})


def run_tool(command: Sequence[str], timeout: int = 120) -> Tuple[Optional[int], str]:
    try:
        completed = subprocess.run(
            list(command),
            capture_output=True,
            text=True,
            encoding="utf-8",
            errors="replace",
            timeout=timeout,
            check=False,
        )
    except (OSError, subprocess.TimeoutExpired) as error:
        return None, str(error)
    return completed.returncode, (completed.stdout or "") + (completed.stderr or "")


def tool_runtime_missing(output: str) -> bool:
    lower_output = output.lower()
    return any(
        marker in lower_output
        for marker in (
            "unable to locate a java runtime",
            "no java runtime",
            "could not find java",
            "java runtime is not installed",
        )
    )


def executable(path_or_name: Optional[str]) -> Optional[str]:
    if not path_or_name:
        return None
    candidate = Path(path_or_name).expanduser()
    if candidate.is_file() and os.access(candidate, os.X_OK):
        return str(candidate)
    return shutil.which(path_or_name)


def resolve_apksigner(explicit: Optional[str]) -> Optional[str]:
    if explicit:
        return executable(explicit)
    direct = executable("apksigner")
    if direct:
        return direct
    roots = [os.environ.get("ANDROID_SDK_ROOT"), os.environ.get("ANDROID_HOME")]
    candidates: List[Path] = []
    for root in roots:
        if not root:
            continue
        candidates.extend(Path(root).glob("build-tools/*/apksigner"))
    candidates = [path for path in candidates if path.is_file() and os.access(path, os.X_OK)]
    if not candidates:
        return None

    def version_key(path: Path) -> Tuple[int, ...]:
        parts = re.findall(r"\d+", path.parent.name)
        return tuple(int(part) for part in parts) or (0,)

    return str(max(candidates, key=version_key))


def resolve_tool(explicit: Optional[str], name: str) -> Optional[str]:
    if explicit:
        return executable(explicit)
    return executable(name)


def verify_apk(
    path: Path,
    apksigner: Optional[str],
    dry_run: bool,
    expected_package: str,
    expected_certificate_sha256: Optional[str],
    require_signing_identity: bool,
) -> Dict[str, Any]:
    command = [apksigner or "apksigner", "verify", "--verbose", "--print-certs", str(path)]
    if dry_run:
        return result("APK", path, NOT_VERIFIED, "dry-run; verification was not executed", command=command)
    if not path.is_file():
        return result("APK", path, NOT_VERIFIED, "artifact is missing")

    actual_package, package_diagnostic = read_android_package_identity(path)
    identity_details = {
        "expected_package": expected_package,
        "package": actual_package,
        "package_diagnostic": package_diagnostic,
    }
    if actual_package is None:
        return annotate(
            result("APK", path, FAIL, "APK package identity could not be verified"),
            identity_details,
        )
    if actual_package != expected_package:
        return annotate(
            result(
                "APK",
                path,
                FAIL,
                f"APK package identity mismatch: expected {expected_package}, found {actual_package}",
            ),
            identity_details,
        )
    if not apksigner:
        try:
            signature_entries, has_signing_block = apk_signature_material(path)
        except (OSError, zipfile.BadZipFile, struct.error) as error:
            return annotate(
                result(
                    "APK",
                    path,
                    FAIL,
                    "APK signature material could not be inspected",
                    details={"diagnostic": compact(str(error))},
                ),
                identity_details,
            )
        if not signature_entries and not has_signing_block:
            return annotate(
                result("APK", path, FAIL, "APK is unsigned: no APK signature material was found"),
                {
                    **identity_details,
                    "signature_entries": [],
                    "apk_signing_block": False,
                },
            )
        if require_signing_identity and not expected_certificate_sha256:
            return annotate(
                result(
                    "APK",
                    path,
                    NOT_VERIFIED,
                    "expected Android signing certificate SHA-256 is required for release verification",
                ),
                identity_details,
            )
        return annotate(
            result(
                "APK",
                path,
                NOT_VERIFIED,
                "apksigner is unavailable; APK signing cannot be verified",
                command=command,
            ),
            {
                **identity_details,
                "signature_entries": signature_entries,
                "apk_signing_block": has_signing_block,
            },
        )

    return_code, output = run_tool(command)
    if return_code is None:
        return annotate(
            result(
                "APK",
                path,
                NOT_VERIFIED,
                "apksigner could not be executed",
                command=command,
                details={"diagnostic": compact(output)},
            ),
            identity_details,
        )
    if tool_runtime_missing(output):
        return annotate(
            result(
                "APK",
                path,
                NOT_VERIFIED,
                "apksigner could not run because the Java runtime is unavailable",
                command=command,
                details={"diagnostic": compact(output)},
            ),
            identity_details,
        )
    if return_code != 0:
        return annotate(
            result(
                "APK",
                path,
                FAIL,
                "APK signature verification failed or APK is unsigned",
                command=command,
                details={"diagnostic": compact(output)},
            ),
            identity_details,
        )

    verified_schemes = re.findall(
        r"Verified using v([1-4]) scheme[^:]*:\s*true",
        output,
        flags=re.IGNORECASE,
    )
    if not verified_schemes:
        return annotate(
            result(
                "APK",
                path,
                FAIL,
                "apksigner returned success without a verified signature scheme",
                command=command,
                details={"diagnostic": compact(output)},
            ),
            identity_details,
        )
    certificate_digests = certificate_sha256_digests(output)
    if require_signing_identity and not expected_certificate_sha256:
        return annotate(
            result(
                "APK",
                path,
                NOT_VERIFIED,
                "expected Android signing certificate SHA-256 is required for release verification",
                command=command,
            ),
            {**identity_details, "certificate_sha256_digests": certificate_digests},
        )
    if expected_certificate_sha256:
        if not certificate_digests:
            return annotate(
                result(
                    "APK",
                    path,
                    NOT_VERIFIED,
                    "apksigner did not expose a certificate SHA-256 digest",
                    command=command,
                ),
                {**identity_details, "certificate_sha256_digests": certificate_digests},
            )
        if expected_certificate_sha256 not in certificate_digests:
            return annotate(
                result(
                    "APK",
                    path,
                    FAIL,
                    "APK signing certificate SHA-256 mismatch",
                    command=command,
                ),
                {
                    **identity_details,
                    "certificate_sha256_digests": certificate_digests,
                    "expected_certificate_sha256": expected_certificate_sha256,
                },
            )
    return annotate(
        result(
            "APK",
            path,
            PASS,
            "APK signature and package identity verified",
            command=command,
            details={
                "verified_schemes": sorted(set("v" + scheme for scheme in verified_schemes)),
                "certificate_sha256_digests": certificate_digests,
            },
        ),
        identity_details,
    )


def aab_signature_entries(path: Path) -> List[str]:
    with zipfile.ZipFile(path) as archive:
        return sorted(
            name
            for name in archive.namelist()
            if re.fullmatch(r"META-INF/[^/]+\.(SF|RSA|DSA|EC)", name, flags=re.IGNORECASE)
        )


def apk_signature_material(path: Path) -> Tuple[List[str], bool]:
    """Return v1 signature entries and whether an APK Signing Block exists."""
    with zipfile.ZipFile(path) as archive:
        entries = sorted(
            name
            for name in archive.namelist()
            if re.fullmatch(r"META-INF/[^/]+\.(SF|RSA|DSA|EC)", name, flags=re.IGNORECASE)
        )
    data = path.read_bytes()
    eocd_start = max(0, len(data) - 65557)
    eocd_offset = data.rfind(b"PK\x05\x06", eocd_start)
    if eocd_offset < 0 or eocd_offset + 22 > len(data):
        return entries, False
    central_directory_offset = _u32(data, eocd_offset + 16)
    magic = b"APK Sig Block 42"
    footer_start = central_directory_offset - 24
    if footer_start < 0 or footer_start + 24 > len(data):
        return entries, False
    block_size = struct.unpack_from("<Q", data, footer_start)[0]
    if data[footer_start + 8 : footer_start + 24] != magic:
        return entries, False
    block_start = central_directory_offset - (block_size + 8)
    if block_start < 0 or block_start + 8 > len(data):
        return entries, False
    return entries, struct.unpack_from("<Q", data, block_start)[0] == block_size


def verify_aab(
    path: Path,
    jarsigner: Optional[str],
    dry_run: bool,
    expected_package: str,
    expected_certificate_sha256: Optional[str],
    require_signing_identity: bool,
    keytool: Optional[str] = None,
) -> Dict[str, Any]:
    command = [jarsigner or "jarsigner", "-verify", "-strict", "-verbose:certs", str(path)]
    keytool_command = [keytool or "keytool", "-printcert", "-jarfile", str(path)]
    if dry_run:
        return result(
            "AAB",
            path,
            NOT_VERIFIED,
            "dry-run; verification was not executed",
            command=command,
            details={
                "jarsigner_command": command_text(command),
                "keytool": keytool,
                "keytool_command": command_text(keytool_command),
                "keytool_required": bool(expected_certificate_sha256),
                "expected_certificate_sha256": expected_certificate_sha256,
            },
        )
    if not path.is_file():
        return result("AAB", path, NOT_VERIFIED, "artifact is missing")
    actual_package, package_diagnostic = read_android_package_identity(path)
    identity_details = {
        "expected_package": expected_package,
        "package": actual_package,
        "package_diagnostic": package_diagnostic,
    }
    if actual_package is None:
        return annotate(
            result("AAB", path, FAIL, "AAB package identity could not be verified"),
            identity_details,
        )
    if actual_package != expected_package:
        return annotate(
            result(
                "AAB",
                path,
                FAIL,
                f"AAB package identity mismatch: expected {expected_package}, found {actual_package}",
            ),
            identity_details,
        )
    try:
        signatures = aab_signature_entries(path)
    except (OSError, zipfile.BadZipFile) as error:
        return annotate(
            result(
                "AAB",
                path,
                FAIL,
                "AAB is not a readable ZIP/JAR artifact",
                details={"diagnostic": compact(str(error))},
            ),
            identity_details,
        )
    if not signatures:
        return annotate(
            result("AAB", path, FAIL, "AAB is unsigned: no META-INF signature entries were found"),
            identity_details,
        )
    if require_signing_identity and not expected_certificate_sha256:
        return annotate(
            result(
                "AAB",
                path,
                NOT_VERIFIED,
                "expected Android signing certificate SHA-256 is required for release verification",
            ),
            {**identity_details, "signature_entries": len(signatures)},
        )
    if not jarsigner:
        return annotate(
            result(
                "AAB",
                path,
                NOT_VERIFIED,
                "jarsigner is unavailable; AAB signing cannot be verified",
                command=command,
                details={"signature_entries": len(signatures)},
            ),
            identity_details,
        )

    return_code, output = run_tool(command)
    lower_output = output.lower()
    if return_code is None:
        return annotate(
            result(
                "AAB",
                path,
                NOT_VERIFIED,
                "jarsigner could not be executed",
                command=command,
                details={"diagnostic": compact(output), "signature_entries": len(signatures)},
            ),
            identity_details,
        )
    if tool_runtime_missing(output):
        return annotate(
            result(
                "AAB",
                path,
                NOT_VERIFIED,
                "jarsigner could not run because the Java runtime is unavailable",
                command=command,
                details={"diagnostic": compact(output), "signature_entries": len(signatures)},
            ),
            identity_details,
        )
    if return_code != 0 or any(marker in lower_output for marker in ("jar is unsigned", "not signed", "does not verify", "signature invalid")):
        return annotate(
            result(
                "AAB",
                path,
                FAIL,
                "AAB JAR signature verification failed",
                command=command,
                details={"diagnostic": compact(output), "signature_entries": len(signatures)},
            ),
            identity_details,
        )
    certificate_digests = certificate_sha256_digests(output)
    verification_details: Dict[str, Any] = {
        "signature_entries": len(signatures),
        "certificate_sha256_digests": certificate_digests,
        "jarsigner_certificate_sha256_digests": certificate_digests,
    }
    if expected_certificate_sha256:
        keytool_details: Dict[str, Any] = {
            "keytool": keytool,
            "keytool_command": command_text(keytool_command),
            "keytool_certificate_sha256_digests": [],
            "keytool_fingerprint_source": "keytool -printcert -jarfile",
            "expected_certificate_sha256": expected_certificate_sha256,
        }
        if not keytool:
            return annotate(
                result(
                    "AAB",
                    path,
                    NOT_VERIFIED,
                    "keytool is unavailable; AAB certificate fingerprint cannot be verified",
                    command=command,
                    details={**verification_details, **keytool_details},
                ),
                identity_details,
            )

        keytool_return_code, keytool_output = run_tool(keytool_command)
        if keytool_return_code is None:
            return annotate(
                result(
                    "AAB",
                    path,
                    NOT_VERIFIED,
                    "keytool could not be executed",
                    command=command,
                    details={
                        **verification_details,
                        **keytool_details,
                        "diagnostic": compact(keytool_output),
                    },
                ),
                identity_details,
            )
        if tool_runtime_missing(keytool_output):
            return annotate(
                result(
                    "AAB",
                    path,
                    NOT_VERIFIED,
                    "keytool could not run because the Java runtime is unavailable",
                    command=command,
                    details={
                        **verification_details,
                        **keytool_details,
                        "diagnostic": compact(keytool_output),
                    },
                ),
                identity_details,
            )
        if keytool_return_code != 0:
            return annotate(
                result(
                    "AAB",
                    path,
                    NOT_VERIFIED,
                    "keytool certificate inspection failed",
                    command=command,
                    details={
                        **verification_details,
                        **keytool_details,
                        "diagnostic": compact(keytool_output),
                    },
                ),
                identity_details,
            )

        keytool_digests = keytool_certificate_sha256_digests(keytool_output)
        keytool_details["keytool_certificate_sha256_digests"] = keytool_digests
        if not keytool_digests:
            return annotate(
                result(
                    "AAB",
                    path,
                    NOT_VERIFIED,
                    "keytool did not expose a SHA-256 certificate fingerprint",
                    command=command,
                    details={**verification_details, **keytool_details},
                ),
                identity_details,
            )
        if expected_certificate_sha256 not in keytool_digests:
            return annotate(
                result(
                    "AAB",
                    path,
                    FAIL,
                    "AAB signing certificate SHA-256 mismatch",
                    command=command,
                    details={
                        **verification_details,
                        **keytool_details,
                    },
                ),
                identity_details,
            )
        verification_details.update(keytool_details)
    return annotate(
        result(
            "AAB",
            path,
            PASS,
            (
                "AAB signature and package identity verified with jarsigner -strict and keytool"
                if expected_certificate_sha256
                else "AAB signature and package identity verified with jarsigner -strict"
            ),
            command=command,
            details=verification_details,
        ),
        identity_details,
    )


def verify_ios_app(
    app_path: Path,
    codesign: Optional[str],
    dry_run: bool,
    require_distribution: bool,
    kind: str,
    artifact_path: Path,
    expected_bundle_id: str,
    expected_team_id: Optional[str],
    expected_share_extension_bundle_id: str,
    require_share_extension: bool,
    inspect_share_extensions: bool = True,
) -> Dict[str, Any]:
    verify_command = [codesign or "codesign", "--verify", "--deep", "--strict", "--verbose=2", str(app_path)]
    display_command = [codesign or "codesign", "--display", "--verbose=4", str(app_path)]
    if dry_run:
        return result(kind, artifact_path, NOT_VERIFIED, "dry-run; codesign verification was not executed", command=verify_command, details={"app": str(app_path), "display_command": command_text(display_command)})
    if not app_path.is_dir():
        return result(kind, artifact_path, FAIL, "iOS application bundle is missing", details={"app": str(app_path)})

    actual_bundle_id, bundle_diagnostic = read_bundle_identifier(app_path)
    identity_details: Dict[str, Any] = {
        "app": str(app_path),
        "expected_bundle_id": expected_bundle_id,
        "bundle_id": actual_bundle_id,
        "bundle_diagnostic": bundle_diagnostic,
    }
    if actual_bundle_id is None:
        return annotate(
            result(kind, artifact_path, FAIL, "iOS bundle identity could not be verified"),
            identity_details,
        )
    if actual_bundle_id != expected_bundle_id:
        return annotate(
            result(
                kind,
                artifact_path,
                FAIL,
                f"iOS bundle identity mismatch: expected {expected_bundle_id}, found {actual_bundle_id}",
            ),
            identity_details,
        )
    if not expected_team_id:
        return annotate(
            result(
                kind,
                artifact_path,
                NOT_VERIFIED,
                "expected iOS TeamIdentifier is required for release verification",
            ),
            identity_details,
        )
    code_resources = app_path / "_CodeSignature" / "CodeResources"
    if not code_resources.is_file():
        return annotate(
            result(kind, artifact_path, FAIL, "iOS application is unsigned: _CodeSignature/CodeResources is missing"),
            identity_details,
        )
    if not codesign:
        return annotate(
            result(
                kind,
                artifact_path,
                NOT_VERIFIED,
                "codesign is unavailable; iOS signing cannot be verified",
                command=verify_command,
            ),
            identity_details,
        )

    return_code, verify_output = run_tool(verify_command)
    if return_code is None:
        return annotate(
            result(
                kind,
                artifact_path,
                NOT_VERIFIED,
                "codesign could not be executed",
                command=verify_command,
                details={"diagnostic": compact(verify_output)},
            ),
            identity_details,
        )
    if return_code != 0:
        return annotate(
            result(
                kind,
                artifact_path,
                FAIL,
                "iOS code signature verification failed",
                command=verify_command,
                details={"diagnostic": compact(verify_output)},
            ),
            identity_details,
        )

    display_code, display_output = run_tool(display_command)
    if display_code is None:
        return annotate(
            result(
                kind,
                artifact_path,
                NOT_VERIFIED,
                "codesign identity could not be inspected",
                command=display_command,
                details={"diagnostic": compact(display_output)},
            ),
            identity_details,
        )
    if display_code != 0 or not re.search(r"^Authority=", display_output, flags=re.MULTILINE):
        return annotate(
            result(
                kind,
                artifact_path,
                FAIL,
                "iOS code signature has no certificate authority; ad-hoc or unverifiable signing is rejected",
                command=display_command,
                details={"diagnostic": compact(display_output)},
            ),
            identity_details,
        )
    if not re.search(r"^TeamIdentifier=", display_output, flags=re.MULTILINE):
        return annotate(
            result(
                kind,
                artifact_path,
                FAIL,
                "iOS code signature has no TeamIdentifier",
                command=display_command,
                details={"diagnostic": compact(display_output)},
            ),
            identity_details,
        )
    actual_team_id = team_identifier(display_output)
    if expected_team_id and actual_team_id != expected_team_id:
        return annotate(
            result(
                kind,
                artifact_path,
                FAIL,
                f"iOS TeamIdentifier mismatch: expected {expected_team_id}, found {actual_team_id}",
                command=display_command,
            ),
            {**identity_details, "team_identifier": actual_team_id, "expected_team_identifier": expected_team_id},
        )
    if require_distribution and not re.search(r"Authority=(?:Apple Distribution|iPhone Distribution)", display_output):
        return annotate(
            result(
                kind,
                artifact_path,
                FAIL,
                "iOS distribution signing was required but the inspected authority is not Apple/iPhone Distribution",
                command=display_command,
                details={"diagnostic": compact(display_output)},
            ),
            {**identity_details, "team_identifier": actual_team_id},
        )

    share_results: List[Dict[str, Any]] = []
    if inspect_share_extensions:
        share_extensions = sorted((app_path / "PlugIns").rglob("*.appex")) if (app_path / "PlugIns").is_dir() else []
        if require_share_extension and not share_extensions:
            return annotate(
                result(
                    kind,
                    artifact_path,
                    FAIL,
                    f"expected iOS share extension {expected_share_extension_bundle_id} is missing",
                ),
                {
                    **identity_details,
                    "team_identifier": actual_team_id,
                    "share_extensions": [],
                },
            )
        for extension in share_extensions:
            share_results.append(
                verify_ios_app(
                    extension,
                    codesign,
                    False,
                    require_distribution,
                    "IOS_SHARE_EXTENSION",
                    artifact_path,
                    expected_share_extension_bundle_id,
                    actual_team_id,
                    expected_share_extension_bundle_id,
                    False,
                    inspect_share_extensions=False,
                )
            )
    share_statuses = {item["status"] for item in share_results}
    if FAIL in share_statuses:
        status = FAIL
    elif NOT_VERIFIED in share_statuses:
        status = NOT_VERIFIED
    else:
        status = PASS
    share_reason = "; ".join(item["reason"] for item in share_results)
    reason = "iOS code signature, bundle identity, and TeamIdentifier verified"
    if share_results:
        reason += f"; share extension checks: {share_reason}"
    return result(
        kind,
        artifact_path,
        status,
        reason,
        command=verify_command,
        details={
            **identity_details,
            "team_identifier": actual_team_id,
            "distribution_required": require_distribution,
            "diagnostic": compact(display_output),
            "share_extensions": share_results,
        },
    )


def verify_xcarchive(
    path: Path,
    codesign: Optional[str],
    dry_run: bool,
    require_distribution: bool,
    expected_team_id: Optional[str],
    require_share_extension: bool,
) -> Dict[str, Any]:
    if dry_run:
        command = [codesign or "codesign", "--verify", "--deep", "--strict", "--verbose=2", "<archive Products/Applications/*.app>"]
        return result("XCARCHIVE", path, NOT_VERIFIED, "dry-run; codesign verification was not executed", command=command)
    if not path.is_dir():
        return result("XCARCHIVE", path, NOT_VERIFIED, "archive is missing")
    apps = sorted((path / "Products" / "Applications").glob("*.app"))
    if not apps:
        return result("XCARCHIVE", path, FAIL, "archive contains no Products/Applications/*.app")
    results = [
        verify_ios_app(
            app,
            codesign,
            dry_run,
            require_distribution,
            "XCARCHIVE",
            path,
            EXPECTED_IOS_BUNDLE_ID,
            expected_team_id,
            EXPECTED_IOS_SHARE_EXTENSION_BUNDLE_ID,
            require_share_extension,
        )
        for app in apps
    ]
    statuses = {item["status"] for item in results}
    if FAIL in statuses:
        status = FAIL
    elif NOT_VERIFIED in statuses:
        status = NOT_VERIFIED
    else:
        status = PASS
    reasons = "; ".join(item["reason"] for item in results)
    return result("XCARCHIVE", path, status, reasons, details={"apps": [str(app) for app in apps], "app_results": results})


def verify_ipa(
    path: Path,
    codesign: Optional[str],
    dry_run: bool,
    require_distribution: bool,
    expected_team_id: Optional[str],
    require_share_extension: bool,
) -> Dict[str, Any]:
    if dry_run:
        command = [codesign or "codesign", "--verify", "--deep", "--strict", "--verbose=2", "<extracted Payload/*.app>"]
        return result("IPA", path, NOT_VERIFIED, "dry-run; IPA codesign verification was not executed", command=command)
    if not path.is_file():
        return result("IPA", path, NOT_VERIFIED, "artifact is missing")
    try:
        with zipfile.ZipFile(path) as archive:
            members = archive.namelist()
            if any(name.startswith("../") or "/../" in name for name in members):
                return result("IPA", path, FAIL, "IPA contains a traversal path")
            apps = [name for name in members if re.fullmatch(r"Payload/[^/]+\.app/", name)]
    except (OSError, zipfile.BadZipFile) as error:
        return result("IPA", path, FAIL, "IPA is not a readable ZIP artifact", details={"diagnostic": compact(str(error))})
    if not apps:
        return result("IPA", path, FAIL, "IPA contains no Payload/*.app bundle")
    with tempfile.TemporaryDirectory(prefix="rinbam-ipa-verify-") as temporary:
        extract_root = Path(temporary)
        with zipfile.ZipFile(path) as archive:
            archive.extractall(extract_root)
        app_results = [
            verify_ios_app(
                extract_root / app.rstrip("/"),
                codesign,
                False,
                require_distribution,
                "IPA",
                path,
                EXPECTED_IOS_BUNDLE_ID,
                expected_team_id,
                EXPECTED_IOS_SHARE_EXTENSION_BUNDLE_ID,
                require_share_extension,
            )
            for app in apps
        ]
    statuses = {item["status"] for item in app_results}
    status = FAIL if FAIL in statuses else NOT_VERIFIED if NOT_VERIFIED in statuses else PASS
    return result("IPA", path, status, "; ".join(item["reason"] for item in app_results), details={"app_results": app_results})


def default_paths() -> List[Tuple[str, Path]]:
    candidates = [
        ("APK", Path("app/build/outputs/apk/release/app-release.apk")),
        ("AAB", Path("app/build/outputs/bundle/release/app-release.aab")),
    ]
    for pattern in ("build/archives/*.xcarchive", "ios/build/archives/*.xcarchive"):
        candidates.extend(("XCARCHIVE", path) for path in sorted(Path(".").glob(pattern)))
    return [(kind, path) for kind, path in candidates if path.exists()]


def parse_args(argv: Optional[Sequence[str]] = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apk", action="append", default=[], metavar="PATH")
    parser.add_argument("--aab", action="append", default=[], metavar="PATH")
    parser.add_argument("--xcarchive", action="append", default=[], metavar="PATH")
    parser.add_argument("--ipa", action="append", default=[], metavar="PATH")
    parser.add_argument("--apksigner", metavar="PATH")
    parser.add_argument("--jarsigner", metavar="PATH")
    parser.add_argument("--keytool", metavar="PATH")
    parser.add_argument("--codesign", metavar="PATH")
    parser.add_argument(
        "--expected-android-cert-sha256",
        metavar="FINGERPRINT",
        help="require this Android signing certificate SHA-256 fingerprint for APK/AAB",
    )
    parser.add_argument(
        "--require-android-signing-identity",
        action="store_true",
        default=True,
        help="require an expected Android signing certificate fingerprint (default)",
    )
    parser.add_argument(
        "--allow-android-signing-identity",
        action="store_false",
        dest="require_android_signing_identity",
        help="package-only verification; not valid as a release gate",
    )
    parser.add_argument("--require-ios-distribution", action="store_true")
    parser.add_argument(
        "--expected-ios-team-id",
        metavar="TEAM_ID",
        help="require this Apple TeamIdentifier in the app and share extension",
    )
    parser.add_argument(
        "--require-ios-share-extension",
        action="store_true",
        help=f"require the {EXPECTED_IOS_SHARE_EXTENSION_BUNDLE_ID} extension when present in the artifact",
    )
    parser.add_argument("--dry-run", action="store_true")
    parser.add_argument("--json", action="store_true", dest="as_json")
    args = parser.parse_args(argv)
    if args.expected_android_cert_sha256:
        normalized = normalize_sha256(args.expected_android_cert_sha256)
        if not normalized:
            parser.error("--expected-android-cert-sha256 must be a 64-hex SHA-256 fingerprint")
        args.expected_android_cert_sha256 = normalized
    return args


def collect_artifacts(args: argparse.Namespace) -> List[Tuple[str, Path]]:
    artifacts: List[Tuple[str, Path]] = []
    artifacts.extend(("APK", Path(path).expanduser()) for path in args.apk)
    artifacts.extend(("AAB", Path(path).expanduser()) for path in args.aab)
    artifacts.extend(("XCARCHIVE", Path(path).expanduser()) for path in args.xcarchive)
    artifacts.extend(("IPA", Path(path).expanduser()) for path in args.ipa)
    if artifacts:
        return artifacts
    return default_paths()


def verify(args: argparse.Namespace) -> Dict[str, Any]:
    artifacts = collect_artifacts(args)
    if not artifacts:
        items = [result("RELEASE_ARTIFACTS", Path("."), NOT_VERIFIED, "no artifact path was supplied or discovered")]
    else:
        apksigner = resolve_apksigner(args.apksigner)
        jarsigner = resolve_tool(args.jarsigner, "jarsigner")
        keytool = resolve_tool(args.keytool, "keytool")
        codesign = resolve_tool(args.codesign, "codesign")
        items = []
        for kind, path in artifacts:
            if kind == "APK":
                items.append(
                    verify_apk(
                        path,
                        apksigner,
                        args.dry_run,
                        EXPECTED_ANDROID_PACKAGE,
                        args.expected_android_cert_sha256,
                        args.require_android_signing_identity,
                    )
                )
            elif kind == "AAB":
                items.append(
                    verify_aab(
                        path,
                        jarsigner,
                        args.dry_run,
                        EXPECTED_ANDROID_PACKAGE,
                        args.expected_android_cert_sha256,
                        args.require_android_signing_identity,
                        keytool=keytool,
                    )
                )
            elif kind == "XCARCHIVE":
                items.append(
                    verify_xcarchive(
                        path,
                        codesign,
                        args.dry_run,
                        args.require_ios_distribution,
                        args.expected_ios_team_id,
                        args.require_ios_share_extension,
                    )
                )
            elif kind == "IPA":
                items.append(
                    verify_ipa(
                        path,
                        codesign,
                        args.dry_run,
                        args.require_ios_distribution,
                        args.expected_ios_team_id,
                        args.require_ios_share_extension,
                    )
                )
    statuses = {item["status"] for item in items}
    overall = FAIL if FAIL in statuses else NOT_VERIFIED if NOT_VERIFIED in statuses else PASS
    return {
        "status": overall,
        "artifacts": items,
        "dry_run": bool(args.dry_run),
        "identity_contract": {
            "android_package": EXPECTED_ANDROID_PACKAGE,
            "ios_bundle_id": EXPECTED_IOS_BUNDLE_ID,
            "ios_share_extension_bundle_id": EXPECTED_IOS_SHARE_EXTENSION_BUNDLE_ID,
            "android_certificate_sha256": args.expected_android_cert_sha256 or "required unless --allow-android-signing-identity is used",
            "ios_team_id": args.expected_ios_team_id or "required for release XCARCHIVE/IPA verification",
            "ios_distribution_team_required": bool(args.require_ios_distribution),
        },
    }


def print_report(report: Dict[str, Any], as_json: bool) -> None:
    if as_json:
        print(json.dumps(report, ensure_ascii=False, indent=2))
        return
    print(f"{report['status']} release artifact verification")
    for item in report["artifacts"]:
        print(f"- {item['status']} {item['kind']}: {item['path']} — {item['reason']}")
        if item.get("details", {}).get("diagnostic"):
            print(f"  diagnostic: {item['details']['diagnostic']}")


def main(argv: Optional[Sequence[str]] = None) -> int:
    args = parse_args(argv)
    report = verify(args)
    print_report(report, args.as_json)
    if report["status"] == PASS:
        return 0
    if report["status"] == FAIL:
        return 1
    return 2


if __name__ == "__main__":
    sys.exit(main())
