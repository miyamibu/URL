#!/usr/bin/env python3
"""Install Deno in Nixpacks without relying on unzip/7z."""

from __future__ import annotations

import os
import pathlib
import platform
import stat
import sys
import tempfile
import urllib.request
import zipfile


VERSION = os.environ.get("DENO_VERSION", "v2.4.1")


def _target() -> str:
    system = platform.system().lower()
    machine = platform.machine().lower()
    if system == "linux" and machine in {"x86_64", "amd64"}:
        return "x86_64-unknown-linux-gnu"
    if system == "linux" and machine in {"aarch64", "arm64"}:
        return "aarch64-unknown-linux-gnu"
    if system == "darwin" and machine in {"aarch64", "arm64"}:
        return "aarch64-apple-darwin"
    if system == "darwin" and machine in {"x86_64", "amd64"}:
        return "x86_64-apple-darwin"
    raise SystemExit(f"Unsupported Deno target: {system}/{machine}")


def main() -> int:
    install_root = pathlib.Path(os.environ.get("DENO_INSTALL", ".deno")).resolve()
    bin_dir = install_root / "bin"
    deno_path = bin_dir / "deno"
    if deno_path.exists():
        print(f"Deno already installed at {deno_path}")
        return 0

    bin_dir.mkdir(parents=True, exist_ok=True)
    target = _target()
    url = f"https://github.com/denoland/deno/releases/download/{VERSION}/deno-{target}.zip"
    with tempfile.TemporaryDirectory() as temp_dir:
        archive = pathlib.Path(temp_dir) / "deno.zip"
        print(f"Downloading {url}")
        urllib.request.urlretrieve(url, archive)
        with zipfile.ZipFile(archive) as zf:
            zf.extract("deno", bin_dir)

    deno_path.chmod(deno_path.stat().st_mode | stat.S_IXUSR | stat.S_IXGRP | stat.S_IXOTH)
    print(f"Installed Deno at {deno_path}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
