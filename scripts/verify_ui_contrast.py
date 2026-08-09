#!/usr/bin/env python3
"""Fail when the core mobile action/chip color pairs fall below WCAG AA."""


def luminance(hex_color: str) -> float:
    channels = [int(hex_color[index:index + 2], 16) / 255 for index in (1, 3, 5)]
    linear = [value / 12.92 if value <= 0.04045 else ((value + 0.055) / 1.055) ** 2.4 for value in channels]
    return 0.2126 * linear[0] + 0.7152 * linear[1] + 0.0722 * linear[2]


def contrast(foreground: str, background: str) -> float:
    lighter, darker = sorted((luminance(foreground), luminance(background)), reverse=True)
    return (lighter + 0.05) / (darker + 0.05)


PAIRS = {
    "iOS primary action": ("#07111F", "#65B0FF"),
    "iOS selected chip light": ("#0B5CAB", "#DCEBFA"),
    "iOS selected chip dark": ("#A9D1FF", "#143558"),
    "Android primary action light": ("#FFFFFF", "#1F6FD1"),
    "Android selected chip light": ("#0B4D8A", "#DCEBFA"),
    "Android selected chip dark": ("#A9D1FF", "#143558"),
}


def main() -> int:
    failures = []
    for label, (foreground, background) in PAIRS.items():
        ratio = contrast(foreground, background)
        print(f"{label}: {ratio:.2f}:1")
        if ratio < 4.5:
            failures.append(f"{label} ({ratio:.2f}:1)")
    if failures:
        print("Contrast check FAILED: " + ", ".join(failures))
        return 1
    print("Contrast check passed.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
