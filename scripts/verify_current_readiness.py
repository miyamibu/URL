#!/usr/bin/env python3
"""Fail closed when the current readiness artifact drifts from source or HEAD."""

from __future__ import annotations

import re
import subprocess
import hashlib
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
ARTIFACT = ROOT / "docs/release/current-readiness-2026-07-24.yaml"
SUMMARY = ROOT / "docs/release/current-readiness-2026-07-24.md"
ARTIFACT_RELATIVE = ARTIFACT.relative_to(ROOT).as_posix()


def fail(message: str) -> None:
    print(f"FAIL {message}")
    raise SystemExit(1)


def require(text: str, pattern: str, label: str) -> None:
    if not re.search(pattern, text, re.MULTILINE):
        fail(f"readiness artifact missing {label}")


def current_worktree_digest() -> str:
    """Hash tracked diff plus untracked files, excluding this artifact itself."""
    digest = hashlib.sha256()
    diff = subprocess.check_output(
        [
            "git",
            "diff",
            "--no-ext-diff",
            "--binary",
            "HEAD",
            "--",
            ".",
            f":(exclude){ARTIFACT_RELATIVE}",
        ],
        cwd=ROOT,
    )
    digest.update(b"tracked-diff\0")
    digest.update(diff)

    untracked = subprocess.check_output(
        [
            "git",
            "ls-files",
            "--others",
            "--exclude-standard",
            "-z",
            "--",
            ".",
            f":(exclude){ARTIFACT_RELATIVE}",
        ],
        cwd=ROOT,
    )
    for raw_path in untracked.split(b"\0"):
        if not raw_path:
            continue
        relative_path = Path(raw_path.decode("utf-8"))
        digest.update(b"untracked\0")
        digest.update(raw_path)
        digest.update(b"\0")
        digest.update((ROOT / relative_path).read_bytes())
    return digest.hexdigest()


def main() -> None:
    if not ARTIFACT.is_file():
        fail(f"missing {ARTIFACT.relative_to(ROOT)}")
    if not SUMMARY.is_file():
        fail(f"missing {SUMMARY.relative_to(ROOT)}")

    artifact = ARTIFACT.read_text(encoding="utf-8")
    required_top_level = (
        "generatedAt",
        "repository",
        "commit",
        "worktree",
        "status",
        "verificationStatus",
        "android",
        "ios",
        "web",
        "supabase",
        "resolver",
        "mcp",
        "privacy",
        "screenshots",
        "external",
        "owners",
        "evidence",
        "blockers",
    )
    for key in required_top_level:
        require(artifact, rf"^{re.escape(key)}:", f"top-level key {key}")

    expected_commit = subprocess.check_output(
        ["git", "rev-parse", "HEAD"], cwd=ROOT, text=True
    ).strip()
    commit_match = re.search(r'^commit:\s*"([0-9a-f]{40})"$', artifact, re.MULTILINE)
    if not commit_match:
        fail("commit must be a quoted 40-character SHA-1")
    if commit_match.group(1) != expected_commit:
        fail(
            "readiness artifact commit does not match current HEAD "
            f"({commit_match.group(1)} != {expected_commit})"
        )

    diff_match = re.search(
        r'^worktreeDiffSha256:\s*"([0-9a-f]{64})"$', artifact, re.MULTILINE
    )
    if not diff_match:
        fail("readiness artifact must bind the current dirty worktree digest")
    current_digest = current_worktree_digest()
    if diff_match.group(1) != current_digest:
        fail(
            "readiness artifact worktree digest does not match current files "
            f"({diff_match.group(1)} != {current_digest})"
        )

    status_match = re.search(r'^status:\s*"([A-Z_]+)"$', artifact, re.MULTILINE)
    verification_match = re.search(
        r'^verificationStatus:\s*"([A-Z_]+)"$', artifact, re.MULTILINE
    )
    if not status_match or status_match.group(1) != "NO_GO_INTERNAL":
        fail("current readiness status must remain NO_GO_INTERNAL until all gates close")
    if not verification_match or verification_match.group(1) != "NOT_VERIFIED_FOR_RELEASE":
        fail("current verification status must remain NOT_VERIFIED_FOR_RELEASE")

    gradle = (ROOT / "app/build.gradle.kts").read_text(encoding="utf-8")
    plist = (ROOT / "ios/URLSaveriOS/Info.plist").read_text(encoding="utf-8")
    xcodeproj = (ROOT / "ios/URLSaveriOS.xcodeproj/project.pbxproj").read_text(encoding="utf-8")
    require(artifact, r'^  applicationId:\s*"jp\.miyamibu\.urlalbum"$', "Android applicationId")
    require(artifact, r'^  versionName:\s*"1\.0\.15"$', "Android versionName")
    require(artifact, r'^  build:\s*17$', "Android versionCode/build")
    if 'applicationId = "jp.miyamibu.urlalbum"' not in gradle:
        fail("Android applicationId drifted from the canonical identity")
    if 'versionName = "1.0.15"' not in gradle or "versionCode = 17" not in gradle:
        fail("Android version drifted from the readiness artifact")
    require(artifact, r'^  bundleId:\s*"com\.mibu\.codebridge\.ios"$', "iOS bundleId")
    require(artifact, r'^  version:\s*"1\.0\.15"$', "iOS version")
    require(artifact, r'^  build:\s*17$', "iOS build")
    if "PRODUCT_BUNDLE_IDENTIFIER = com.mibu.codebridge.ios;" not in xcodeproj:
        fail("iOS bundle ID is not the canonical identity")
    if "<string>1.0.15</string>" not in plist or "<string>17</string>" not in plist:
        fail("iOS version/build drifted from the readiness artifact")

    require(artifact, r"personalDataEnabled:\s*false", "MCP personal-data gate")
    require(artifact, r"dualFlagRequired:\s*true", "MCP dual flag gate")
    if not re.search(r"SNAPSHOT_PROTOCOL_ENABLED\s*=\s*false", (ROOT / "app/src/main/java/jp/mimac/urlsaver/data/ChatGptPersonalLinkSync.kt").read_text(encoding="utf-8")):
        fail("Android personal-link snapshot gate is not fail-closed")
    if not re.search(r"personalLinkSnapshotProtocolEnabled\s*=\s*false", (ROOT / "ios/URLSaverShared/Data/SharedTagCloud.swift").read_text(encoding="utf-8")):
        fail("iOS personal-link snapshot gate is not fail-closed")
    if not re.search(r"ALLOW_LOCAL_MEDIA_DOWNLOADS.*false", gradle):
        fail("Android release local-media gate is not visibly disabled")

    print("OK current readiness artifact matches current HEAD, identities, and fail-closed gates")


if __name__ == "__main__":
    main()
