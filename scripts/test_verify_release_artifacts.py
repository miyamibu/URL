#!/usr/bin/env python3
"""Fixture tests for verify_release_artifacts.py; no SDK, signing key, or network is required."""

from __future__ import annotations

import json
import os
import plistlib
import stat
import subprocess
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
SCRIPT = ROOT / "scripts" / "verify_release_artifacts.py"


def write_executable(path: Path, body: str) -> Path:
    path.write_text(body, encoding="utf-8")
    path.chmod(path.stat().st_mode | stat.S_IXUSR)
    return path


def write_android_manifest(archive: zipfile.ZipFile, package: str, name: str = "AndroidManifest.xml") -> None:
    archive.writestr(
        name,
        f'<?xml version="1.0"?><manifest xmlns:android="http://schemas.android.com/apk/res/android" package="{package}" />',
    )


def write_signed_aab(path: Path, package: str = "jp.miyamibu.urlalbum") -> None:
    with zipfile.ZipFile(path, "w") as archive:
        archive.writestr("BundleConfig.pb", "fixture")
        write_android_manifest(archive, package, "base/manifest/AndroidManifest.xml")
        archive.writestr("META-INF/TEST.SF", "fixture")
        archive.writestr("META-INF/TEST.RSA", "fixture")


def colon_fingerprint(value: str) -> str:
    return ":".join(value[index : index + 2] for index in range(0, len(value), 2))


def protobuf_field(field_number: int, value: bytes) -> bytes:
    tag = bytes([(field_number << 3) | 2])
    return tag + bytes([len(value)]) + value


def write_android_proto_manifest(archive: zipfile.ZipFile, package: str) -> None:
    attribute = protobuf_field(2, b"package") + protobuf_field(3, package.encode("utf-8"))
    element = protobuf_field(3, b"manifest") + protobuf_field(4, attribute)
    archive.writestr("base/manifest/AndroidManifest.xml", protobuf_field(1, element))


def write_ios_bundle(bundle: Path, bundle_id: str, *, team_id: str = "FIXTURETEAM") -> None:
    info = {
        "CFBundleIdentifier": bundle_id,
        "CFBundlePackageType": "APPL",
    }
    (bundle / "_CodeSignature").mkdir(parents=True)
    (bundle / "_CodeSignature" / "CodeResources").write_text("fixture", encoding="utf-8")
    (bundle / "Info.plist").write_bytes(plistlib.dumps(info))


class ReleaseArtifactVerifierTests(unittest.TestCase):
    def run_verifier(self, *args: str, package_only: bool = True, env=None) -> subprocess.CompletedProcess[str]:
        mode = ("--allow-android-signing-identity",) if package_only else ()
        return subprocess.run(
            [sys.executable, str(SCRIPT), "--json", *mode, *args],
            cwd=ROOT,
            capture_output=True,
            text=True,
            check=False,
            env=env,
        )

    def report(self, completed: subprocess.CompletedProcess[str]) -> dict:
        return json.loads(completed.stdout)

    def test_dry_run_is_not_verified_and_nonzero(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            keytool = write_executable(
                Path(temporary) / "keytool",
                "#!/usr/bin/env python3\nprint('dry-run fixture')\n",
            )
            completed = self.run_verifier(
                "--dry-run",
                "--aab",
                "/tmp/does-not-matter.aab",
                "--expected-android-cert-sha256",
                "aa" * 32,
                "--keytool",
                str(keytool),
                package_only=False,
            )
        self.assertEqual(completed.returncode, 2)
        report = self.report(completed)
        self.assertEqual(report["status"], "NOT_VERIFIED")
        self.assertIn("dry-run", report["artifacts"][0]["reason"])
        details = report["artifacts"][0]["details"]
        self.assertTrue(details["keytool_required"])
        self.assertEqual(details["keytool"], str(keytool))
        self.assertIn("-printcert", details["keytool_command"])

    def test_unsigned_aab_is_fail(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "unsigned.aab"
            with zipfile.ZipFile(path, "w") as archive:
                archive.writestr("BundleConfig.pb", "fixture")
                write_android_manifest(archive, "jp.miyamibu.urlalbum", "base/manifest/AndroidManifest.xml")
            completed = self.run_verifier("--aab", str(path))
            self.assertEqual(completed.returncode, 1)
            self.assertEqual(self.report(completed)["status"], "FAIL")
            self.assertIn("unsigned", self.report(completed)["artifacts"][0]["reason"])

    def test_signed_aab_fixture_requires_signature_entries_and_jarsigner(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            path = root / "signed.aab"
            with zipfile.ZipFile(path, "w") as archive:
                archive.writestr("BundleConfig.pb", "fixture")
                write_android_manifest(archive, "jp.miyamibu.urlalbum", "base/manifest/AndroidManifest.xml")
                archive.writestr("META-INF/TEST.SF", "fixture")
                archive.writestr("META-INF/TEST.RSA", "fixture")
            jarsigner = write_executable(root / "jarsigner", "#!/usr/bin/env python3\nprint('jar verified.')\n")
            completed = self.run_verifier("--aab", str(path), "--jarsigner", str(jarsigner))
            self.assertEqual(completed.returncode, 0, completed.stdout + completed.stderr)
            self.assertEqual(self.report(completed)["status"], "PASS")

    def test_aab_protobuf_manifest_identity_is_verified(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            path = root / "protobuf-manifest.aab"
            with zipfile.ZipFile(path, "w") as archive:
                write_android_proto_manifest(archive, "jp.miyamibu.urlalbum")
                archive.writestr("META-INF/TEST.SF", "fixture")
                archive.writestr("META-INF/TEST.RSA", "fixture")
            jarsigner = write_executable(root / "jarsigner", "#!/usr/bin/env python3\nprint('jar verified.')\n")
            completed = self.run_verifier("--aab", str(path), "--jarsigner", str(jarsigner))
            self.assertEqual(completed.returncode, 0, completed.stdout + completed.stderr)
            artifact = self.report(completed)["artifacts"][0]
            self.assertEqual(artifact["details"]["package"], "jp.miyamibu.urlalbum")

    def test_missing_java_runtime_is_not_verified(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            path = root / "signed.aab"
            with zipfile.ZipFile(path, "w") as archive:
                archive.writestr("BundleConfig.pb", "fixture")
                write_android_manifest(archive, "jp.miyamibu.urlalbum", "base/manifest/AndroidManifest.xml")
                archive.writestr("META-INF/TEST.SF", "fixture")
                archive.writestr("META-INF/TEST.RSA", "fixture")
            jarsigner = write_executable(
                root / "jarsigner",
                "#!/usr/bin/env python3\n"
                "import sys\n"
                "print('Unable to locate a Java Runtime.', file=sys.stderr)\n"
                "sys.exit(1)\n",
            )
            completed = self.run_verifier("--aab", str(path), "--jarsigner", str(jarsigner))
            self.assertEqual(completed.returncode, 2)
            self.assertEqual(self.report(completed)["status"], "NOT_VERIFIED")

    def test_aab_identity_mismatch_fails_even_when_jarsigner_passes(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            path = root / "other-app.aab"
            with zipfile.ZipFile(path, "w") as archive:
                write_android_manifest(archive, "com.example.other", "base/manifest/AndroidManifest.xml")
                archive.writestr("META-INF/TEST.SF", "fixture")
                archive.writestr("META-INF/TEST.RSA", "fixture")
            jarsigner = write_executable(root / "jarsigner", "#!/usr/bin/env python3\nprint('jar verified.')\n")
            completed = self.run_verifier("--aab", str(path), "--jarsigner", str(jarsigner))
            self.assertEqual(completed.returncode, 1)
            artifact = self.report(completed)["artifacts"][0]
            self.assertEqual(artifact["status"], "FAIL")
            self.assertIn("identity mismatch", artifact["reason"])

    def test_aab_jarsigner_unavailable_is_not_verified_after_identity_match(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            path = root / "signed.aab"
            with zipfile.ZipFile(path, "w") as archive:
                write_android_manifest(archive, "jp.miyamibu.urlalbum", "base/manifest/AndroidManifest.xml")
                archive.writestr("META-INF/TEST.SF", "fixture")
                archive.writestr("META-INF/TEST.RSA", "fixture")
            completed = self.run_verifier("--aab", str(path), "--jarsigner", str(root / "missing-jarsigner"))
            self.assertEqual(completed.returncode, 2)
            self.assertEqual(self.report(completed)["status"], "NOT_VERIFIED")

    def test_aab_keytool_fingerprint_passes(self) -> None:
        expected = "aa" * 32
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            path = root / "signed.aab"
            write_signed_aab(path)
            jarsigner = write_executable(root / "jarsigner", "#!/usr/bin/env python3\nprint('jar verified.')\n")
            keytool = write_executable(
                root / "keytool",
                "#!/usr/bin/env python3\n"
                "print('Certificate fingerprints:')\n"
                f"print('    SHA256: {colon_fingerprint(expected)}')\n",
            )
            completed = self.run_verifier(
                "--aab",
                str(path),
                "--jarsigner",
                str(jarsigner),
                "--keytool",
                str(keytool),
                "--expected-android-cert-sha256",
                expected,
                package_only=False,
            )
            self.assertEqual(completed.returncode, 0, completed.stdout + completed.stderr)
            artifact = self.report(completed)["artifacts"][0]
            self.assertEqual(artifact["status"], "PASS")
            self.assertEqual(artifact["details"]["keytool_certificate_sha256_digests"], [expected])
            self.assertEqual(artifact["details"]["keytool_fingerprint_source"], "keytool -printcert -jarfile")
            self.assertIn("-strict", artifact["command"])

    def test_aab_keytool_is_resolved_from_path(self) -> None:
        expected = "aa" * 32
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            path = root / "signed.aab"
            write_signed_aab(path)
            jarsigner = write_executable(root / "jarsigner", "#!/usr/bin/env python3\nprint('jar verified.')\n")
            keytool = write_executable(
                root / "keytool",
                "#!/usr/bin/env python3\n"
                f"print('SHA256: {colon_fingerprint(expected)}')\n",
            )
            environment = os.environ.copy()
            environment["PATH"] = str(root) + os.pathsep + environment.get("PATH", "")
            completed = self.run_verifier(
                "--aab",
                str(path),
                "--jarsigner",
                str(jarsigner),
                "--expected-android-cert-sha256",
                expected,
                package_only=False,
                env=environment,
            )
            self.assertEqual(completed.returncode, 0, completed.stdout + completed.stderr)
            artifact = self.report(completed)["artifacts"][0]
            self.assertEqual(artifact["details"]["keytool"], str(keytool))

    def test_aab_keytool_sha256_fingerprint_mismatch_fails(self) -> None:
        expected = "aa" * 32
        actual = "bb" * 32
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            path = root / "signed.aab"
            write_signed_aab(path)
            jarsigner = write_executable(root / "jarsigner", "#!/usr/bin/env python3\nprint('jar verified.')\n")
            keytool = write_executable(
                root / "keytool",
                "#!/usr/bin/env python3\n"
                f"print('SHA-256: {colon_fingerprint(actual)}')\n",
            )
            completed = self.run_verifier(
                "--aab",
                str(path),
                "--jarsigner",
                str(jarsigner),
                "--keytool",
                str(keytool),
                "--expected-android-cert-sha256",
                expected,
                package_only=False,
            )
            self.assertEqual(completed.returncode, 1)
            artifact = self.report(completed)["artifacts"][0]
            self.assertEqual(artifact["status"], "FAIL")
            self.assertIn("certificate SHA-256 mismatch", artifact["reason"])
            self.assertEqual(artifact["details"]["keytool_certificate_sha256_digests"], [actual])
            self.assertEqual(artifact["details"]["expected_certificate_sha256"], expected)

    def test_aab_keytool_missing_is_not_verified(self) -> None:
        expected = "aa" * 32
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            path = root / "signed.aab"
            write_signed_aab(path)
            jarsigner = write_executable(root / "jarsigner", "#!/usr/bin/env python3\nprint('jar verified.')\n")
            completed = self.run_verifier(
                "--aab",
                str(path),
                "--jarsigner",
                str(jarsigner),
                "--keytool",
                str(root / "missing-keytool"),
                "--expected-android-cert-sha256",
                expected,
                package_only=False,
            )
            self.assertEqual(completed.returncode, 2)
            artifact = self.report(completed)["artifacts"][0]
            self.assertEqual(artifact["status"], "NOT_VERIFIED")
            self.assertIn("keytool is unavailable", artifact["reason"])
            self.assertIsNone(artifact["details"]["keytool"])
            self.assertEqual(artifact["details"]["keytool_certificate_sha256_digests"], [])

    def test_aab_keytool_empty_fingerprint_is_not_verified(self) -> None:
        expected = "aa" * 32
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            path = root / "signed.aab"
            write_signed_aab(path)
            jarsigner = write_executable(root / "jarsigner", "#!/usr/bin/env python3\nprint('jar verified.')\n")
            keytool = write_executable(
                root / "keytool",
                "#!/usr/bin/env python3\nprint('Certificate fingerprints:')\n",
            )
            completed = self.run_verifier(
                "--aab",
                str(path),
                "--jarsigner",
                str(jarsigner),
                "--keytool",
                str(keytool),
                "--expected-android-cert-sha256",
                expected,
                package_only=False,
            )
            self.assertEqual(completed.returncode, 2)
            artifact = self.report(completed)["artifacts"][0]
            self.assertEqual(artifact["status"], "NOT_VERIFIED")
            self.assertIn("did not expose", artifact["reason"])
            self.assertEqual(artifact["details"]["keytool_certificate_sha256_digests"], [])

    def test_aab_keytool_execution_failure_is_not_verified(self) -> None:
        expected = "aa" * 32
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            path = root / "signed.aab"
            write_signed_aab(path)
            jarsigner = write_executable(root / "jarsigner", "#!/usr/bin/env python3\nprint('jar verified.')\n")
            keytool = write_executable(
                root / "keytool",
                "#!/usr/bin/env python3\n"
                "import sys\n"
                "print('keytool fixture failed', file=sys.stderr)\n"
                "sys.exit(7)\n",
            )
            completed = self.run_verifier(
                "--aab",
                str(path),
                "--jarsigner",
                str(jarsigner),
                "--keytool",
                str(keytool),
                "--expected-android-cert-sha256",
                expected,
                package_only=False,
            )
            self.assertEqual(completed.returncode, 2)
            artifact = self.report(completed)["artifacts"][0]
            self.assertEqual(artifact["status"], "NOT_VERIFIED")
            self.assertIn("inspection failed", artifact["reason"])
            self.assertIn("keytool fixture failed", artifact["details"]["diagnostic"])

    def test_unsigned_apk_does_not_pass(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            path = root / "unsigned.apk"
            path.write_bytes(b"not-an-apk")
            apksigner = write_executable(root / "apksigner", "#!/usr/bin/env python3\nimport sys\nsys.exit(1)\n")
            completed = self.run_verifier("--apk", str(path), "--apksigner", str(apksigner))
            self.assertEqual(completed.returncode, 1)
            self.assertEqual(self.report(completed)["status"], "FAIL")

    def test_signed_apk_identity_match_passes(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            path = root / "signed.apk"
            with zipfile.ZipFile(path, "w") as archive:
                write_android_manifest(archive, "jp.miyamibu.urlalbum")
            apksigner = write_executable(
                root / "apksigner",
                "#!/usr/bin/env python3\nprint('Verified using v2 scheme: true')\n",
            )
            completed = self.run_verifier("--apk", str(path), "--apksigner", str(apksigner))
            self.assertEqual(completed.returncode, 0, completed.stdout + completed.stderr)
            self.assertEqual(self.report(completed)["status"], "PASS")

    def test_android_signing_identity_requires_expected_certificate(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            path = root / "signed.apk"
            with zipfile.ZipFile(path, "w") as archive:
                write_android_manifest(archive, "jp.miyamibu.urlalbum")
            apksigner = write_executable(
                root / "apksigner",
                "#!/usr/bin/env python3\n"
                "print('Verified using v2 scheme: true')\n"
                "print('Signer #1 certificate SHA-256 digest: ' + ':'.join(['AA'] * 32))\n",
            )
            completed = self.run_verifier(
                "--apk",
                str(path),
                "--apksigner",
                str(apksigner),
                "--require-android-signing-identity",
                package_only=False,
            )
            self.assertEqual(completed.returncode, 2)
            self.assertEqual(self.report(completed)["status"], "NOT_VERIFIED")

            completed = self.run_verifier(
                "--apk",
                str(path),
                "--apksigner",
                str(apksigner),
                "--expected-android-cert-sha256",
                "bb" * 32,
            )
            self.assertEqual(completed.returncode, 1)
            self.assertIn("certificate SHA-256 mismatch", self.report(completed)["artifacts"][0]["reason"])

    def test_android_signing_identity_is_required_by_default(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            path = root / "signed.apk"
            with zipfile.ZipFile(path, "w") as archive:
                write_android_manifest(archive, "jp.miyamibu.urlalbum")
            apksigner = write_executable(
                root / "apksigner",
                "#!/usr/bin/env python3\n"
                "print('Verified using v2 scheme: true')\n"
                "print('Signer #1 certificate SHA-256 digest: ' + ':'.join(['AA'] * 32))\n",
            )
            completed = self.run_verifier(
                "--apk",
                str(path),
                "--apksigner",
                str(apksigner),
                package_only=False,
            )
            self.assertEqual(completed.returncode, 2)
            self.assertEqual(self.report(completed)["status"], "NOT_VERIFIED")

    def test_signed_apk_identity_mismatch_fails_even_when_apksigner_passes(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            path = root / "other-app.apk"
            with zipfile.ZipFile(path, "w") as archive:
                write_android_manifest(archive, "com.example.other")
            apksigner = write_executable(
                root / "apksigner",
                "#!/usr/bin/env python3\nprint('Verified using v2 scheme: true')\n",
            )
            completed = self.run_verifier("--apk", str(path), "--apksigner", str(apksigner))
            self.assertEqual(completed.returncode, 1)
            artifact = self.report(completed)["artifacts"][0]
            self.assertEqual(artifact["status"], "FAIL")
            self.assertIn("identity mismatch", artifact["reason"])

    def test_apksigner_unavailable_is_not_verified_after_identity_match(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            path = root / "signed.apk"
            with zipfile.ZipFile(path, "w") as archive:
                write_android_manifest(archive, "jp.miyamibu.urlalbum")
                archive.writestr("META-INF/TEST.SF", "fixture")
                archive.writestr("META-INF/TEST.RSA", "fixture")
            completed = self.run_verifier("--apk", str(path), "--apksigner", str(root / "missing-apksigner"))
            self.assertEqual(completed.returncode, 2)
            self.assertEqual(self.report(completed)["status"], "NOT_VERIFIED")

    def test_unsigned_apk_is_fail_without_apksigner(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            path = root / "unsigned.apk"
            with zipfile.ZipFile(path, "w") as archive:
                write_android_manifest(archive, "jp.miyamibu.urlalbum")
            completed = self.run_verifier("--apk", str(path), "--apksigner", str(root / "missing-apksigner"))
            self.assertEqual(completed.returncode, 1)
            artifact = self.report(completed)["artifacts"][0]
            self.assertEqual(artifact["status"], "FAIL")
            self.assertIn("unsigned", artifact["reason"])

    def test_unsigned_ios_archive_does_not_pass(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            archive = Path(temporary) / "Unsigned.xcarchive" / "Products" / "Applications" / "Fixture.app"
            archive.mkdir(parents=True)
            completed = self.run_verifier("--xcarchive", str(archive.parents[2]))
            self.assertEqual(completed.returncode, 1)
            self.assertEqual(self.report(completed)["status"], "FAIL")

    def test_signed_ios_archive_can_pass_with_fake_codesign(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            archive = root / "Signed.xcarchive"
            app = archive / "Products" / "Applications" / "Fixture.app"
            write_ios_bundle(app, "com.mibu.codebridge.ios")
            extension = app / "PlugIns" / "Fixture.appex"
            write_ios_bundle(extension, "com.mibu.codebridge.ios.share")
            codesign = write_executable(
                root / "codesign",
                "#!/usr/bin/env python3\n"
                "import sys\n"
                "if '--display' in sys.argv:\n"
                "    print('Authority=Apple Distribution: Fixture', file=sys.stderr)\n"
                "    print('TeamIdentifier=FIXTURETEAM', file=sys.stderr)\n"
                "sys.exit(0)\n",
            )
            completed = self.run_verifier(
                "--xcarchive",
                str(archive),
                "--codesign",
                str(codesign),
                "--require-ios-distribution",
                "--expected-ios-team-id",
                "FIXTURETEAM",
                "--require-ios-share-extension",
            )
            self.assertEqual(completed.returncode, 0, completed.stdout + completed.stderr)
            self.assertEqual(self.report(completed)["status"], "PASS")

    def test_ios_bundle_identity_mismatch_fails_even_when_codesign_passes(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            archive = root / "Other.xcarchive"
            app = archive / "Products" / "Applications" / "Fixture.app"
            write_ios_bundle(app, "com.example.other")
            codesign = write_executable(
                root / "codesign",
                "#!/usr/bin/env python3\n"
                "if '--display' in __import__('sys').argv:\n"
                "    print('Authority=Apple Distribution: Fixture', file=__import__('sys').stderr)\n"
                "    print('TeamIdentifier=FIXTURETEAM', file=__import__('sys').stderr)\n"
                "sys.exit(0)\n",
            )
            completed = self.run_verifier("--xcarchive", str(archive), "--codesign", str(codesign))
            self.assertEqual(completed.returncode, 1)
            artifact = self.report(completed)["artifacts"][0]
            self.assertIn("identity mismatch", artifact["reason"])

    def test_ios_share_extension_identity_and_team_mismatch_fail(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            archive = root / "WrongExtension.xcarchive"
            app = archive / "Products" / "Applications" / "Fixture.app"
            write_ios_bundle(app, "com.mibu.codebridge.ios")
            extension = app / "PlugIns" / "Fixture.appex"
            write_ios_bundle(extension, "com.example.wrong.share")
            codesign = write_executable(
                root / "codesign",
                "#!/usr/bin/env python3\n"
                "import sys\n"
                "if '--display' in sys.argv:\n"
                "    print('Authority=Apple Distribution: Fixture', file=sys.stderr)\n"
                "    print('TeamIdentifier=FIXTURETEAM', file=sys.stderr)\n"
                "sys.exit(0)\n",
            )
            completed = self.run_verifier(
                "--xcarchive",
                str(archive),
                "--codesign",
                str(codesign),
                "--expected-ios-team-id",
                "FIXTURETEAM",
                "--require-ios-share-extension",
            )
            self.assertEqual(completed.returncode, 1)
            artifact = self.report(completed)["artifacts"][0]
            self.assertIn("share extension checks", artifact["reason"])
            self.assertIn("identity mismatch", artifact["reason"])

    def test_codesign_unavailable_is_not_verified_after_bundle_identity_match(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            archive = root / "Signed.xcarchive"
            app = archive / "Products" / "Applications" / "Fixture.app"
            write_ios_bundle(app, "com.mibu.codebridge.ios")
            completed = self.run_verifier(
                "--xcarchive",
                str(archive),
                "--codesign",
                str(root / "missing-codesign"),
            )
            self.assertEqual(completed.returncode, 2)
            self.assertEqual(self.report(completed)["status"], "NOT_VERIFIED")

    def test_distribution_archive_requires_expected_team_id(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            archive = root / "Signed.xcarchive"
            app = archive / "Products" / "Applications" / "Fixture.app"
            write_ios_bundle(app, "com.mibu.codebridge.ios")
            codesign = write_executable(
                root / "codesign",
                "#!/usr/bin/env python3\n"
                "import sys\n"
                "if '--display' in sys.argv:\n"
                "    print('Authority=Apple Distribution: Fixture', file=sys.stderr)\n"
                "    print('TeamIdentifier=FIXTURETEAM', file=sys.stderr)\n"
                "sys.exit(0)\n",
            )
            completed = self.run_verifier(
                "--xcarchive",
                str(archive),
                "--codesign",
                str(codesign),
                "--require-ios-distribution",
            )
            self.assertEqual(completed.returncode, 2)
            self.assertEqual(self.report(completed)["status"], "NOT_VERIFIED")


if __name__ == "__main__":
    unittest.main()
