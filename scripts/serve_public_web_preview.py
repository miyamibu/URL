#!/usr/bin/env python3
"""Serve web/invite-link locally with its Vercel rewrites and security headers."""

from __future__ import annotations

import argparse
import json
import mimetypes
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import unquote, urlsplit


ROOT = Path(__file__).resolve().parents[1]
WEB_ROOT = ROOT / "web" / "invite-link"
VERCEL_CONFIG = json.loads((WEB_ROOT / "vercel.json").read_text(encoding="utf-8"))


def response_headers(path: str) -> dict[str, str]:
    headers: dict[str, str] = {}
    for rule in VERCEL_CONFIG.get("headers", []):
        source = rule.get("source", "")
        prefix = source.removesuffix(":path*").rstrip("/")
        if path == prefix or path.startswith(f"{prefix}/"):
            headers.update(
                {
                    item["key"]: item["value"]
                    for item in rule.get("headers", [])
                    if "key" in item and "value" in item
                }
            )
    return headers


def resolve_request_path(request_path: str) -> Path:
    path = unquote(urlsplit(request_path).path)
    static_candidate = (WEB_ROOT / path.lstrip("/")).resolve()
    if static_candidate.is_relative_to(WEB_ROOT) and static_candidate.is_file():
        return static_candidate
    if path in {"/auth/reset-password", "/auth/reset-password/"}:
        return WEB_ROOT / "auth" / "reset-password" / "index.html"
    if path == "/invite" or path == "/invite/" or path.startswith("/invite/"):
        return WEB_ROOT / "invite" / "index.html"
    if path in {"/promo", "/promo/"}:
        return WEB_ROOT / "promo" / "index.html"
    if path == "/":
        return WEB_ROOT / "index.html"
    return static_candidate


class PreviewHandler(SimpleHTTPRequestHandler):
    server_version = "RinbamPublicWebPreview/1.0"

    def translate_path(self, path: str) -> str:
        return str(resolve_request_path(path))

    def end_headers(self) -> None:
        for key, value in response_headers(urlsplit(self.path).path).items():
            self.send_header(key, value)
        self.send_header("Cache-Control", "no-store")
        super().end_headers()

    def guess_type(self, path: str) -> str:
        return mimetypes.guess_type(path)[0] or "application/octet-stream"

    def log_message(self, format: str, *args: object) -> None:
        if getattr(self.server, "quiet", False):
            return
        super().log_message(format, *args)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--bind", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=4178)
    parser.add_argument("--quiet", action="store_true")
    args = parser.parse_args()

    server = ThreadingHTTPServer((args.bind, args.port), PreviewHandler)
    server.quiet = args.quiet
    print(f"Serving public web preview on http://{args.bind}:{server.server_port}", flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
