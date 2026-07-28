#!/usr/bin/env python3
"""Run the optional admin Playwright E2E contract without installing anything.

The runner never logs in, creates credentials, or installs npm packages. An
operator must provide a pre-authenticated Playwright storage state and a test
environment configuration. Missing tooling or configuration is NOT_VERIFIED,
never a successful no-op.
"""

from __future__ import annotations

import argparse
import json
import os
import shutil
import subprocess
import sys
from pathlib import Path
from typing import Any, Dict, List, Optional, Sequence, Tuple
from urllib.parse import urlparse


PASS = "PASS"
FAIL = "FAIL"
NOT_VERIFIED = "NOT_VERIFIED"


def compact(text: str, limit: int = 1000) -> str:
    value = " ".join(text.split())
    if len(value) <= limit:
        return value
    return value[: limit - 3] + "..."


def result(status: str, reason: str, **extra: Any) -> Dict[str, Any]:
    payload: Dict[str, Any] = {"status": status, "reason": reason}
    payload.update(extra)
    return payload


def node_path(explicit: Optional[str]) -> Optional[str]:
    if explicit:
        candidate = Path(explicit).expanduser()
        if candidate.is_file() and candidate.stat().st_mode & 0o111:
            return str(candidate)
        return shutil.which(explicit)
    return shutil.which("node")


def resolve_playwright_test(node: str, roots: Sequence[Path]) -> Tuple[Optional[str], Optional[str], Optional[str]]:
    """Return module path, package root, and CLI path without npm installation."""
    query = (
        "const root=process.argv[1];"
        "try {"
        " const entry=require.resolve('@playwright/test',{paths:[root]});"
        " const path=require('path');"
        " const packageRoot=path.dirname(entry);"
        " const cli=path.join(packageRoot,'cli.js');"
        " process.stdout.write([entry,packageRoot,cli].join('\\n'));"
        "} catch (_) { process.exit(2); }"
    )
    for root in roots:
        try:
            completed = subprocess.run(
                [node, "-e", query, str(root)],
                capture_output=True,
                text=True,
                encoding="utf-8",
                errors="replace",
                timeout=10,
                check=False,
            )
        except (OSError, subprocess.TimeoutExpired):
            continue
        if completed.returncode != 0:
            continue
        lines = completed.stdout.strip().splitlines()
        if len(lines) != 3:
            continue
        module_path, package_root, cli_path = (Path(line) for line in lines)
        if module_path.is_file() and cli_path.is_file():
            return str(module_path), str(package_root), str(cli_path)
    return None, None, None


def parse_args(argv: Optional[Sequence[str]] = None) -> argparse.Namespace:
    project_root = Path(__file__).resolve().parents[1]
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--project-root", default=str(project_root), metavar="PATH")
    parser.add_argument("--admin-root", default=str(project_root / "web" / "admin"), metavar="PATH")
    parser.add_argument("--spec", default=str(project_root / "scripts" / "admin_e2e.spec.cjs"), metavar="PATH")
    parser.add_argument("--node", metavar="PATH")
    parser.add_argument("--base-url", default=os.environ.get("RINBAM_ADMIN_E2E_BASE_URL"), metavar="URL")
    parser.add_argument("--storage-state", default=os.environ.get("RINBAM_ADMIN_E2E_STORAGE_STATE"), metavar="PATH")
    parser.add_argument("--headed", action="store_true")
    parser.add_argument("--dry-run", action="store_true")
    parser.add_argument("--json", action="store_true", dest="as_json")
    return parser.parse_args(argv)


def configuration(args: argparse.Namespace) -> Tuple[Dict[str, str], List[str]]:
    values = {
        "RINBAM_ADMIN_E2E_BASE_URL": args.base_url or "",
        "RINBAM_ADMIN_E2E_STORAGE_STATE": args.storage_state or "",
        "RINBAM_ADMIN_E2E_PROTECTED_PATH": os.environ.get("RINBAM_ADMIN_E2E_PROTECTED_PATH", "/"),
        "RINBAM_ADMIN_E2E_PROTECTED_API_PATH": os.environ.get("RINBAM_ADMIN_E2E_PROTECTED_API_PATH", "/api/admin/audit"),
        "RINBAM_ADMIN_E2E_SENSITIVE_PATH": os.environ.get("RINBAM_ADMIN_E2E_SENSITIVE_PATH", "/"),
        "RINBAM_ADMIN_E2E_ROLE_SELECTOR": os.environ.get("RINBAM_ADMIN_E2E_ROLE_SELECTOR", ""),
        "RINBAM_ADMIN_E2E_EXPECTED_ROLE": os.environ.get("RINBAM_ADMIN_E2E_EXPECTED_ROLE", ""),
        "RINBAM_ADMIN_E2E_STEP_UP_SELECTOR": os.environ.get("RINBAM_ADMIN_E2E_STEP_UP_SELECTOR", ""),
        "RINBAM_ADMIN_E2E_SUBMIT_SELECTOR": os.environ.get("RINBAM_ADMIN_E2E_SUBMIT_SELECTOR", ""),
        "RINBAM_ADMIN_E2E_ERROR_SELECTOR": os.environ.get("RINBAM_ADMIN_E2E_ERROR_SELECTOR", ""),
        "RINBAM_ADMIN_E2E_MUTATION_ROUTE": os.environ.get("RINBAM_ADMIN_E2E_MUTATION_ROUTE", ""),
        "RINBAM_ADMIN_E2E_A11Y_SCOPE": os.environ.get("RINBAM_ADMIN_E2E_A11Y_SCOPE", "body"),
        "RINBAM_ADMIN_E2E_DUPLICATE_STATUS": os.environ.get("RINBAM_ADMIN_E2E_DUPLICATE_STATUS", "409"),
        "RINBAM_ADMIN_E2E_ERROR_STATUS": os.environ.get("RINBAM_ADMIN_E2E_ERROR_STATUS", "500"),
    }
    missing = [
        name
        for name in (
            "RINBAM_ADMIN_E2E_BASE_URL",
            "RINBAM_ADMIN_E2E_STORAGE_STATE",
            "RINBAM_ADMIN_E2E_ROLE_SELECTOR",
            "RINBAM_ADMIN_E2E_EXPECTED_ROLE",
            "RINBAM_ADMIN_E2E_STEP_UP_SELECTOR",
            "RINBAM_ADMIN_E2E_SUBMIT_SELECTOR",
            "RINBAM_ADMIN_E2E_ERROR_SELECTOR",
            "RINBAM_ADMIN_E2E_MUTATION_ROUTE",
        )
        if not values[name]
    ]
    if values["RINBAM_ADMIN_E2E_BASE_URL"]:
        parsed = urlparse(values["RINBAM_ADMIN_E2E_BASE_URL"])
        if parsed.scheme not in {"http", "https"} or not parsed.netloc:
            missing.append("RINBAM_ADMIN_E2E_BASE_URL(valid http/https URL)")
    if values["RINBAM_ADMIN_E2E_STORAGE_STATE"] and not Path(values["RINBAM_ADMIN_E2E_STORAGE_STATE"]).expanduser().is_file():
        missing.append("RINBAM_ADMIN_E2E_STORAGE_STATE(existing file)")
    return values, missing


def run(args: argparse.Namespace) -> Dict[str, Any]:
    project_root = Path(args.project_root).expanduser().resolve()
    admin_root = Path(args.admin_root).expanduser().resolve()
    spec = Path(args.spec).expanduser().resolve()
    values, missing = configuration(args)
    node = node_path(args.node)
    module_path: Optional[str] = None
    package_root: Optional[str] = None
    cli_path: Optional[str] = None
    if node:
        module_path, package_root, cli_path = resolve_playwright_test(node, [admin_root, project_root])

    if args.dry_run:
        return result(
            NOT_VERIFIED,
            "dry-run; Playwright was not executed",
            node=node,
            playwright_test=module_path,
            missing_configuration=missing,
            spec=str(spec),
        )
    if not node:
        return result(NOT_VERIFIED, "node is unavailable; no E2E was executed", missing_configuration=missing, spec=str(spec))
    if not module_path or not cli_path:
        return result(NOT_VERIFIED, "@playwright/test is unavailable; no npm install was attempted", node=node, missing_configuration=missing, spec=str(spec))
    if missing:
        return result(NOT_VERIFIED, "required admin E2E configuration or storage state is missing", node=node, playwright_test=module_path, missing_configuration=missing, spec=str(spec))
    if not spec.is_file():
        return result(NOT_VERIFIED, "admin E2E spec is missing", spec=str(spec), node=node, playwright_test=module_path)

    command = [node, cli_path, "test", str(spec), "--reporter=line", "--workers=1"]
    if args.headed:
        command.append("--headed")
    environment = os.environ.copy()
    environment.update(values)
    environment["RINBAM_PLAYWRIGHT_TEST_MODULE"] = module_path
    try:
        completed = subprocess.run(
            command,
            cwd=project_root,
            env=environment,
            capture_output=True,
            text=True,
            encoding="utf-8",
            errors="replace",
            timeout=15 * 60,
            check=False,
        )
    except subprocess.TimeoutExpired as error:
        return result(NOT_VERIFIED, "Playwright execution timed out; result is not verified", command=" ".join(command), diagnostic=compact(str(error)))
    except OSError as error:
        return result(NOT_VERIFIED, "Playwright could not be executed", command=" ".join(command), diagnostic=compact(str(error)))

    output = (completed.stdout or "") + (completed.stderr or "")
    lower_output = output.lower()
    browser_missing = any(
        marker in lower_output
        for marker in (
            "executable doesn't exist",
            "browsertype.launch: executable",
            "please run the following command to download new browsers",
        )
    )
    if completed.returncode == 0:
        return result(PASS, "admin authentication/role/step-up, duplicate-submit/error, and accessibility E2E passed", command=" ".join(command), diagnostic=compact(output))
    if browser_missing:
        return result(NOT_VERIFIED, "Playwright is installed but its browser executable is unavailable", command=" ".join(command), diagnostic=compact(output))
    return result(FAIL, "admin E2E reported a failure", command=" ".join(command), exit_code=completed.returncode, diagnostic=compact(output))


def print_report(report: Dict[str, Any], as_json: bool) -> None:
    if as_json:
        print(json.dumps(report, ensure_ascii=False, indent=2))
        return
    print(f"{report['status']} admin E2E verification")
    print(f"- {report['reason']}")
    if report.get("missing_configuration"):
        print("- missing: " + ", ".join(report["missing_configuration"]))
    if report.get("diagnostic"):
        print(f"- diagnostic: {report['diagnostic']}")


def main(argv: Optional[Sequence[str]] = None) -> int:
    args = parse_args(argv)
    report = run(args)
    print_report(report, args.as_json)
    return 0 if report["status"] == PASS else 1 if report["status"] == FAIL else 2


if __name__ == "__main__":
    sys.exit(main())
