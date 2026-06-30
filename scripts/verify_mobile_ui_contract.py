#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def read(rel_path: str) -> str:
    path = ROOT / rel_path
    if not path.exists():
        raise AssertionError(f"missing file: {rel_path}")
    return path.read_text(encoding="utf-8")


def require(rel_path: str, needle: str, reason: str) -> None:
    haystack = read(rel_path)
    if needle not in haystack:
        raise AssertionError(f"{rel_path}: missing {needle!r} ({reason})")


def forbid(rel_path: str, needle: str, reason: str) -> None:
    haystack = read(rel_path)
    if needle in haystack:
        raise AssertionError(f"{rel_path}: forbidden {needle!r} ({reason})")


def main() -> int:
    checks = [
        lambda: require(
            "docs/mobile-ui-regression-contract.md",
            "Local Tags On Cards",
            "single source of truth for card/tag regression behavior",
        ),
        lambda: require(
            "app/src/main/java/jp/mimac/urlsaver/data/AppDatabase.kt",
            "MIGRATION_19_17",
            "Android real-device schema 19 must open safely after restoring schema 17 code",
        ),
        lambda: require(
            "app/src/main/java/jp/mimac/urlsaver/data/AppDatabase.kt",
            'dropColumnIfPresent(db, "url_entries", "pendingDeleteOriginState")',
            "schema downgrade must preserve URL/tag data and only remove the post-plan extra column",
        ),
        lambda: forbid(
            "app/src/main/java/jp/mimac/urlsaver/ui/UrlSaverRoot.kt",
            "Icons.Outlined.Info",
            "home top-bar Info icon must not be reintroduced in the root UI",
        ),
        lambda: require(
            "app/src/main/java/jp/mimac/urlsaver/ui/UrlSaverRoot.kt",
            "localTagNamesByEntryId",
            "main cards must receive local/custom tag assignments",
        ),
        lambda: require(
            "app/src/main/java/jp/mimac/urlsaver/ui/UrlSaverRoot.kt",
            'Text("+")',
            "home local-tag creation/management route must remain visible as + only",
        ),
        lambda: require(
            "app/src/main/java/jp/mimac/urlsaver/ui/UrlSaverRoot.kt",
            'title = "自作タグ"',
            "detail local tag heading must be 自作タグ",
        ),
        lambda: require(
            "app/src/main/java/jp/mimac/urlsaver/ui/UrlSaverRoot.kt",
            'title = "共有タグ"',
            "detail shared tag heading must remain 共有タグ",
        ),
        lambda: require(
            "app/src/main/java/jp/mimac/urlsaver/ui/components/EntryCard.kt",
            "localTagNames: List<String>",
            "Android entry cards must accept local tag names",
        ),
        lambda: require(
            "app/src/main/java/jp/mimac/urlsaver/ui/components/EntryCard.kt",
            "entryCardVisibleLocalTagNames",
            "Android entry cards must normalize visible local tag chips",
        ),
        lambda: require(
            "app/src/main/java/jp/mimac/urlsaver/ui/components/EntryCard.kt",
            "visibleLocalTagNames.isEmpty()",
            "Android entry cards must hide service/time header when local tags exist",
        ),
        lambda: forbid(
            "ios/URLSaveriOS/UI/RootView.swift",
            'icon: "info.circle"',
            "home top-bar Info icon must not be reintroduced on iPhone",
        ),
        lambda: require(
            "ios/URLSaveriOS/UI/RootView.swift",
            "localTagAssignments",
            "iPhone main/archive cards must receive local tag assignments",
        ),
        lambda: require(
            "ios/URLSaveriOS/UI/RootView.swift",
            "localTagNames(for entry:",
            "iPhone cards must map local tag IDs to visible names",
        ),
        lambda: forbid(
            "ios/URLSaveriOS/UI/RootView.swift",
            "ShareLink(item: localLinkText)",
            "local tag management must not show the old link share control",
        ),
        lambda: forbid(
            "ios/URLSaveriOS/UI/RootView.swift",
            "payloadText",
            "local tag management must not show JSON payload sharing",
        ),
        lambda: forbid(
            "ios/URLSaveriOS/UI/RootView.swift",
            'Label("JSON"',
            "local tag management must not show JSON button text",
        ),
        lambda: forbid(
            "ios/URLSaveriOS/UI/RootView.swift",
            'Label("リンク"',
            "local tag management must not show link button text",
        ),
        lambda: forbid(
            "ios/URLSaveriOS/UI/RootView.swift",
            'Label("削除", systemImage: "trash")',
            "local tag management must not restore the old large delete label",
        ),
        lambda: require(
            "ios/URLSaveriOS/UI/AppChrome.swift",
            "localTagNames: [String]",
            "iPhone entry cards must accept local tag names",
        ),
        lambda: require(
            "ios/URLSaveriOS/UI/AppChrome.swift",
            "entryCardVisibleLocalTagNames",
            "iPhone entry cards must normalize visible local tag chips",
        ),
        lambda: require(
            "ios/URLSaveriOS/UI/AppChrome.swift",
            "if visibleLocalTagNames.isEmpty",
            "iPhone entry cards must hide service/time header when local tags exist",
        ),
        lambda: require(
            "ios/URLSaveriOS/UI/DetailView.swift",
            'title: "自作タグ"',
            "iPhone detail local tag heading must be 自作タグ",
        ),
        lambda: require(
            "ios/URLSaveriOS/UI/DetailView.swift",
            'title: "共有タグ"',
            "iPhone detail shared tag heading must remain 共有タグ",
        ),
    ]

    failures: list[str] = []
    for check in checks:
        try:
            check()
        except AssertionError as exc:
            failures.append(str(exc))

    if failures:
        print("Mobile UI contract check FAILED:")
        for failure in failures:
            print(f"- {failure}")
        return 1

    print("Mobile UI contract check passed.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
