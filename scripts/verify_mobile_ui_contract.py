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


def require_order(rel_path: str, first: str, second: str, reason: str) -> None:
    haystack = read(rel_path)
    first_index = haystack.find(first)
    second_index = haystack.find(second)
    if first_index == -1 or second_index == -1 or first_index >= second_index:
        raise AssertionError(f"{rel_path}: expected {first!r} before {second!r} ({reason})")


def main() -> int:
    checks = [
        lambda: require(
            "docs/mobile-ui-regression-contract.md",
            "Local Tags On Cards",
            "single source of truth for card/tag regression behavior",
        ),
        lambda: require(
            "app/src/main/java/jp/mimac/urlsaver/data/AppDatabase.kt",
            "MIGRATION_19_20",
            "Android real-device schema 19 must migrate forward without data loss",
        ),
        lambda: require(
            "app/src/main/java/jp/mimac/urlsaver/data/AppDatabase.kt",
            'dropColumnIfPresent(db, "url_entries", "pendingDeleteOriginState")',
            "schema migration must preserve URL/tag data and only remove the post-plan extra column",
        ),
        lambda: forbid(
            "app/src/main/java/jp/mimac/urlsaver/ui/UrlSaverRoot.kt",
            "Icons.Outlined.Info",
            "home top-bar Info icon must not be reintroduced in the root UI",
        ),
        lambda: require(
            "app/src/main/java/jp/mimac/urlsaver/ui/UrlSaverRoot.kt",
            "IconButton(onClick = { showProfileSheet = true })",
            "Android home top bar must keep the profile button",
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
            "selectedMainLocalTagId",
            "Android home must track local tag filter selection in the main top row",
        ),
        lambda: require(
            "app/src/main/java/jp/mimac/urlsaver/ui/UrlSaverRoot.kt",
            "localTags = localSaveTags",
            "Android home must pass local tags into the service filter row",
        ),
        lambda: require(
            "app/src/main/java/jp/mimac/urlsaver/ui/components/ServiceFilterRow.kt",
            "localTags: List<TagWithCount>",
            "Android service filter row must own local tag chips in the same row",
        ),
        lambda: require(
            "app/src/main/java/jp/mimac/urlsaver/ui/components/ServiceFilterRow.kt",
            "onCreateLocalTag",
            "Android service filter row must keep local tag + in the same row",
        ),
        lambda: require(
            "app/src/main/java/jp/mimac/urlsaver/ui/components/ServiceFilterRow.kt",
            "TopFilterItem.LocalTag",
            "Android local tags must be part of the same top-row movable chip model",
        ),
        lambda: require_order(
            "app/src/main/java/jp/mimac/urlsaver/ui/components/ServiceFilterRow.kt",
            "localTags.forEach",
            "add(TopFilterItem.All)",
            "Android local tags must be inserted before service filters in the same top row",
        ),
        lambda: require(
            "app/src/main/java/jp/mimac/urlsaver/ui/components/ServiceFilterRow.kt",
            "missingLocalTags + currentItems",
            "new Android local tags must appear before existing service filters even when a saved order exists",
        ),
        lambda: require(
            "app/src/main/java/jp/mimac/urlsaver/ui/UrlSaverRoot.kt",
            "viewModel.selectedLocalTagIdFlow.collectAsStateWithLifecycle()",
            "Android archive screen must also track local tag filter selection",
        ),
        lambda: require(
            "app/src/main/java/jp/mimac/urlsaver/ui/UrlSaverRoot.kt",
            "localTags = localSaveTags",
            "Android archive screen must pass local tags into the same top service filter row",
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
            "app/src/main/java/jp/mimac/urlsaver/ui/UrlSaverRoot.kt",
            "horizontalArrangement = Arrangement.spacedBy(8.dp),\n                verticalAlignment = Alignment.CenterVertically,\n            ) {\n                DetailTagSectionLabel(",
            "Android detail tag edit button must be in the heading row, not stacked below",
        ),
        lambda: require(
            "app/src/main/java/jp/mimac/urlsaver/ui/UrlSaverRoot.kt",
            "private fun DetailTagSectionLabel(\n    text: String,\n    modifier: Modifier = Modifier,",
            "Android detail tag headings must use the compact one-line label",
        ),
        lambda: require(
            "app/src/main/java/jp/mimac/urlsaver/ui/UrlSaverRoot.kt",
            "maxLines = 1,\n        overflow = TextOverflow.Ellipsis,\n    )\n}",
            "Android detail tag headings must not wrap to two lines",
        ),
        lambda: require(
            "app/src/main/java/jp/mimac/urlsaver/ui/UrlSaverRoot.kt",
            "Modifier.width(78.dp).testTag(editButtonTestTag)",
            "Android detail tag edit button must stay compact on the right side of the heading",
        ),
        lambda: require(
            "app/src/main/java/jp/mimac/urlsaver/ui/UrlSaverRoot.kt",
            ".horizontalScroll(rememberScrollState()),\n                        ) {\n                            Text(\n                                text = title,",
            "Android media viewer title must stay one line and horizontally scrollable",
        ),
        lambda: require(
            "app/src/main/java/jp/mimac/urlsaver/ui/UrlSaverRoot.kt",
            "maxLines = 1,\n                                overflow = TextOverflow.Clip,\n                                color = MaterialTheme.colorScheme.onBackground,",
            "Android media viewer title must not wrap to two lines",
        ),
        lambda: forbid(
            "app/src/main/java/jp/mimac/urlsaver/ui/UrlSaverRoot.kt",
            "AppMediaFileName(",
            "Android media viewer must not show internal file names below the media",
        ),
        lambda: forbid(
            "app/src/main/java/jp/mimac/urlsaver/ui/UrlSaverRoot.kt",
            "middleEllipsizeFileName",
            "Android media viewer must not keep the old visible file-name label path",
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
            "app/src/main/java/jp/mimac/urlsaver/ui/components/EntryCard.kt",
            "MetadataStatusDot(entry.metadataState)",
            "Android cards without local tags must not show the right-side metadata status dot",
        ),
        lambda: forbid(
            "app/src/main/java/jp/mimac/urlsaver/ui/components/EntryCard.kt",
            'text = "$timestampLabel ${formatTimestamp(timestampMillis, ZoneId.of("Asia/Tokyo"))}"',
            "Android cards without local tags must not show saved/archive timestamp in the header",
        ),
        lambda: forbid(
            "ios/URLSaveriOS/UI/RootView.swift",
            'icon: "info.circle"',
            "home top-bar Info icon must not be reintroduced on iPhone",
        ),
        lambda: require(
            "ios/URLSaveriOS/UI/RootView.swift",
            'icon: "person.crop.circle"',
            "iPhone home header must keep the profile button",
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
        lambda: require_order(
            "ios/URLSaveriOS/UI/AppChrome.swift",
            "ForEach(localTags)",
            "ForEach(serviceFilterOrder",
            "iPhone local tags must appear in the same top row immediately after the + chip, not be pushed behind service filters",
        ),
        lambda: require(
            "ios/URLSaveriOS/UI/AppChrome.swift",
            "if visibleLocalTagNames.isEmpty",
            "iPhone entry cards must hide service/time header when local tags exist",
        ),
        lambda: forbid(
            "ios/URLSaveriOS/UI/AppChrome.swift",
            "Circle()\n                                        .fill(metadataDotColor(for: entry.metadataState))",
            "iPhone cards without local tags must not show the right-side metadata status dot",
        ),
        lambda: forbid(
            "ios/URLSaveriOS/UI/AppChrome.swift",
            'Text("\\(timestampLabel) \\(DateFormatters.listTimestamp.string(from: timestampDate(for: entry)))")',
            "iPhone cards without local tags must not show saved/archive timestamp in the header",
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
        lambda: require(
            "ios/URLSaveriOS/UI/DetailView.swift",
            "HStack(alignment: .center, spacing: 8) {\n                    DetailSectionLabel(text: title)",
            "iPhone detail tag edit button must be in the heading row, not stacked below",
        ),
        lambda: require(
            "ios/URLSaveriOS/UI/DetailView.swift",
            "DetailTagEditButton(action: onEdit)\n                        .frame(width: 72)",
            "iPhone detail tag edit button must stay compact on the right side of the heading",
        ),
        lambda: require(
            "ios/URLSaveriOS/UI/DetailView.swift",
            "ScrollView(.horizontal, showsIndicators: false) {\n                        Text(title)",
            "iPhone media viewer title must stay one line and horizontally scrollable",
        ),
        lambda: require(
            "ios/URLSaveriOS/UI/DetailView.swift",
            ".lineLimit(1)\n                            .fixedSize(horizontal: true, vertical: false)",
            "iPhone media viewer title must not wrap to two lines",
        ),
        lambda: forbid(
            "ios/URLSaveriOS/UI/DetailView.swift",
            "Text(item.fileName)",
            "iPhone media viewer must not show internal file names below the media",
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
