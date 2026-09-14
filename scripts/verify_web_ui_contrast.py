#!/usr/bin/env python3
"""Verify non-text control contrast and status colors for public/admin web UI."""

from __future__ import annotations

import re
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]


def linear(value: int) -> float:
    channel = value / 255
    return channel / 12.92 if channel <= 0.04045 else ((channel + 0.055) / 1.055) ** 2.4


def luminance(color: str) -> float:
    value = color.removeprefix("#")
    if len(value) != 6 or not re.fullmatch(r"[0-9a-fA-F]{6}", value):
        raise AssertionError(f"unsupported color: {color}")
    red, green, blue = (int(value[index:index + 2], 16) for index in (0, 2, 4))
    return 0.2126 * linear(red) + 0.7152 * linear(green) + 0.0722 * linear(blue)


def contrast(foreground: str, background: str) -> float:
    lighter, darker = sorted((luminance(foreground), luminance(background)), reverse=True)
    return (lighter + 0.05) / (darker + 0.05)


def require(condition: bool, message: str) -> None:
    if not condition:
        raise SystemExit(f"FAIL {message}")
    print(f"PASS {message}")


def require_contrast(foreground: str, background: str, minimum: float, label: str) -> None:
    ratio = contrast(foreground, background)
    require(ratio >= minimum, f"{label}: {ratio:.2f}:1 >= {minimum:.1f}:1")


def main() -> int:
    admin_css = (ROOT / "web" / "admin" / "app" / "styles.css").read_text(encoding="utf-8")
    reset_css = (ROOT / "web" / "invite-link" / "auth" / "reset-password" / "styles.css").read_text(encoding="utf-8")

    require("border: 1px solid var(--control-line);" in admin_css, "admin inputs/selects use the control border token")
    require("input:focus-visible" in admin_css and "select:focus-visible" in admin_css, "admin keyboard focus covers all form controls")
    require("outline: 3px solid var(--primary);" in admin_css, "admin focus ring has a visible 3px outline")
    require("@media (forced-colors: active)" in admin_css, "admin has a Forced Colors override")
    require("outline-color: Highlight;" in admin_css and "border: 1px solid CanvasText;" in admin_css, "admin Forced Colors uses system colors")

    require_contrast("#7b8797", "#ffffff", 3.0, "admin control boundary on white")
    require_contrast("#2463eb", "#ffffff", 3.0, "admin focus ring on white")
    require_contrast("#2463eb", "#f6f7f9", 3.0, "admin focus ring on page background")
    for foreground, background, label in [
        ("#047857", "#ecfdf3", "success notice"),
        ("#b42318", "#fef3f2", "error notice"),
        ("#075985", "#e0f2fe", "sent status"),
        ("#166534", "#dcfce7", "delivered/redeemed/active status"),
        ("#92400e", "#fef3c7", "delayed/suspended status"),
        ("#991b1b", "#fee4e2", "failed/revoked/expired status"),
        ("#253041", "#edf2f8", "neutral status"),
    ]:
        require_contrast(foreground, background, 4.5, f"admin {label} text")

    require("border: 1px solid var(--control-line);" in reset_css, "reset input uses the accessible control border token")
    require("@media (forced-colors: active)" in reset_css, "reset page has a Forced Colors override")
    require_contrast("#7b8797", "#ffffff", 3.0, "reset input boundary on white")
    require_contrast("#2463eb", "#ffffff", 3.0, "reset focus ring on white")

    print("PASS web UI contrast verification")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
