#!/usr/bin/env python3
"""Verify public-web CSP, fallback routes, branding, and local HTTP responses."""

from __future__ import annotations

import json
import re
import socket
import subprocess
import sys
import time
from pathlib import Path
from urllib.error import URLError
from urllib.request import urlopen


ROOT = Path(__file__).resolve().parents[1]
WEB_ROOT = ROOT / "web" / "invite-link"


def require(condition: bool, message: str) -> None:
    if not condition:
        raise SystemExit(f"FAIL {message}")
    print(f"PASS {message}")


def csp_for(config: dict[str, object], source: str) -> str:
    for rule in config.get("headers", []):
        if isinstance(rule, dict) and rule.get("source") == source:
            for header in rule.get("headers", []):
                if isinstance(header, dict) and header.get("key") == "Content-Security-Policy":
                    return str(header.get("value", ""))
    return ""


def assert_no_inline_code(html: str, label: str) -> None:
    require(not re.search(r"<style\b", html, flags=re.IGNORECASE), f"{label} has no inline style block")
    scripts = re.findall(r"<script\b([^>]*)>(.*?)</script>", html, flags=re.IGNORECASE | re.DOTALL)
    require(bool(scripts), f"{label} declares scripts explicitly")
    require(
        all(re.search(r"\bsrc\s*=", attributes, flags=re.IGNORECASE) and not body.strip() for attributes, body in scripts),
        f"{label} has no inline executable script",
    )


def free_port() -> int:
    with socket.socket() as sock:
        sock.bind(("127.0.0.1", 0))
        return int(sock.getsockname()[1])


def fetch(url: str) -> tuple[int, dict[str, str], str]:
    with urlopen(url, timeout=5) as response:
        headers = {key.lower(): value for key, value in response.headers.items()}
        return response.status, headers, response.read().decode("utf-8")


def response_tests(expected_reset_csp: str, expected_invite_csp: str) -> None:
    port = free_port()
    process = subprocess.Popen(
        [sys.executable, "scripts/serve_public_web_preview.py", "--port", str(port), "--quiet"],
        cwd=ROOT,
        stdout=subprocess.DEVNULL,
        stderr=subprocess.PIPE,
        text=True,
    )
    try:
        base_url = f"http://127.0.0.1:{port}"
        deadline = time.monotonic() + 5
        while True:
            try:
                fetch(f"{base_url}/")
                break
            except URLError:
                if time.monotonic() >= deadline:
                    stderr = process.stderr.read() if process.stderr else ""
                    raise SystemExit(f"FAIL local preview did not start: {stderr}")
                time.sleep(0.05)

        status, headers, body = fetch(f"{base_url}/auth/reset-password")
        require(status == 200, "reset route responds HTTP 200 locally")
        require(headers.get("content-security-policy") == expected_reset_csp, "reset response carries exact CSP")
        require("りんばむ パスワード再設定" in body, "reset rewrite serves the branded page")

        status, headers, body = fetch(f"{base_url}/auth/reset-password/reset-password.js")
        require(status == 200 and "javascript" in headers.get("content-type", ""), "reset JavaScript asset responds")
        require(headers.get("content-security-policy") == expected_reset_csp, "reset asset carries exact CSP")

        status, headers, body = fetch(f"{base_url}/invite/contract-smoke-token")
        require(status == 200, "invite route responds HTTP 200 locally")
        require(headers.get("content-security-policy") == expected_invite_csp, "invite response carries exact CSP")
        require("招待リンクをコピー" in body, "invite rewrite serves recovery controls")

        status, headers, _ = fetch(f"{base_url}/invite/invite.js")
        require(status == 200 and "javascript" in headers.get("content-type", ""), "invite JavaScript asset is not shadowed by rewrite")
    finally:
        process.terminate()
        try:
            process.wait(timeout=3)
        except subprocess.TimeoutExpired:
            process.kill()
            process.wait(timeout=3)


def reset_recovery_runtime_tests() -> None:
    result = subprocess.run(
        ["node", "--test", "scripts/reset-password-recovery.test.mjs"],
        cwd=ROOT,
        check=False,
    )
    require(result.returncode == 0, "reset recovery runtime contract passes")


def main() -> int:
    config = json.loads((WEB_ROOT / "vercel.json").read_text(encoding="utf-8"))
    reset_html = (WEB_ROOT / "auth" / "reset-password" / "index.html").read_text(encoding="utf-8")
    invite_html = (WEB_ROOT / "invite" / "index.html").read_text(encoding="utf-8")
    privacy_html = (WEB_ROOT / "privacy" / "index.html").read_text(encoding="utf-8")

    reset_csp = csp_for(config, "/auth/reset-password/:path*")
    invite_csp = csp_for(config, "/invite/:path*")
    require(bool(reset_csp), "reset route declares a CSP")
    require("script-src 'self' https://cdn.jsdelivr.net" in reset_csp, "reset CSP allows only self and pinned SDK host scripts")
    require("style-src 'self'" in reset_csp, "reset CSP allows self-hosted styles")
    require(not re.search(r"unsafe-inline|unsafe-eval|sha256-|nonce-", reset_csp), "reset CSP cannot drift through inline hashes or unsafe directives")
    assert_no_inline_code(reset_html, "reset page")
    reset_script = (WEB_ROOT / "auth" / "reset-password" / "reset-password.js").read_text(encoding="utf-8")
    require("SUPABASE_SDK_INTEGRITY" in reset_script and 'script.crossOrigin = "anonymous"' in reset_script, "reset SDK keeps SRI and anonymous CORS")
    require("if (!hasRecoveryInput)" in reset_script, "reset rejects tokenless links before loading the SDK")
    require('implicitRecoveryType === "recovery"' in reset_script, "reset accepts only recovery implicit fragments")
    require("implicitAccessToken &&" in reset_script and "implicitRefreshToken" in reset_script, "reset requires both implicit recovery tokens")
    require("persistSession: false" in reset_script and "autoRefreshToken: false" in reset_script, "reset recovery session is memory-only")
    require("detectSessionInUrl: false" in reset_script, "reset disables SDK URL auto-detection")
    require("clearRecoveryCredentialsFromUrl();" in reset_script, "reset removes recovery credentials after session establishment")
    require("recoveryInitialization = performRecoveryInitialization()" in reset_script, "reset shares one initialization promise")
    require((WEB_ROOT / "auth" / "reset-password" / "styles.css").is_file(), "reset stylesheet exists")
    require((WEB_ROOT / "auth" / "reset-password" / "reset-password.js").is_file(), "reset JavaScript exists")

    require(bool(invite_csp), "invite route declares a CSP")
    require("script-src 'self'" in invite_csp and "style-src 'self'" in invite_csp, "invite CSP uses self-hosted code")
    assert_no_inline_code(invite_html, "invite page")
    for expected in [
        "https://apps.apple.com/app/id6771251450",
        "https://play.google.com/store/apps/details?id=jp.miyamibu.urlalbum",
        "招待リンクをコピー",
        "このページに戻ります",
    ]:
        require(expected in invite_html, f"invite fallback contains {expected}")
    require("setTimeout" not in (WEB_ROOT / "invite" / "invite.js").read_text(encoding="utf-8"), "invite page does not auto-open the app")

    branded_files = [
        WEB_ROOT / "index.html",
        WEB_ROOT / "privacy" / "index.html",
        WEB_ROOT / "account-deletion" / "index.html",
        WEB_ROOT / "promo" / "index.html",
        WEB_ROOT / "invite" / "index.html",
        WEB_ROOT / "auth" / "reset-password" / "index.html",
    ]
    legacy_brand = re.compile(r"URL\s*Saver|UrlSaver")
    require(
        all(not legacy_brand.search(path.read_text(encoding="utf-8")) for path in branded_files),
        "public user-facing pages use the りんばむ brand",
    )

    privacy_disclosure_contract = [
        "同期元を区別するためのランダムな識別子",
        "作成・名称変更・削除",
        "URL の追加・解除",
        "参加者の追加・削除",
        "権限の変更",
        "操作種別と対象をクラウドへ送信",
        "操作種別、対象、送信状態を端末内に保持",
        "同期元の識別子、適用結果",
        "同じ操作の二重適用を防ぐ",
        "一時的な処理だけで破棄される情報ではありません",
        "対象アカウントに結び付く共有タグ同期識別子",
        "ChatGPT連携設定を消去します",
        "所有アカウントを特定できない保留中の招待は削除しません",
        "クラウド削除を繰り返さず再試行できるよう",
        "削除の二重実行や他人のアカウントに対する操作は発生しない",
        "ハッシュ化したメールアドレス",
        "ハッシュ化した IP アドレス",
        "ハッシュ化したサインイン中のユーザー識別子",
        "仮名化された識別子",
        "問い合わせ本文の消去とは別に保持",
        "アカウントを削除しただけでは必ずしも消去されません",
    ]
    require(
        all(disclosure in privacy_html for disclosure in privacy_disclosure_contract),
        "privacy discloses persistent sync identifiers, operation results, pseudonymous support identifiers, purposes, and deletion boundaries",
    )

    account_deletion_html = (WEB_ROOT / "account-deletion" / "index.html").read_text(encoding="utf-8")
    account_deletion_contract = [
        "端末内のデータと中断時の扱い",
        "送信待ち・再試行・重複防止状態",
        "ChatGPT連携設定を消去します",
        "所有アカウントを特定できない保留中の招待は削除しません",
        "クラウド削除を繰り返さず再試行できるよう",
        "同じ削除要求の状態を安全に確認できます",
        "通常のサインアウトはログイン情報だけを消去するもので、アカウント削除ではありません",
    ]
    require(
        all(disclosure in account_deletion_html for disclosure in account_deletion_contract),
        "account-deletion page discloses on-device cleanup, interruption handling, and sign-out boundary",
    )

    reset_recovery_runtime_tests()
    response_tests(reset_csp, invite_csp)
    print("PASS public web contract verification")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
