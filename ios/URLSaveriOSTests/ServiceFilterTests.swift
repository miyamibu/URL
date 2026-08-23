import XCTest
@testable import URLSaveriOS

final class ServiceFilterTests: XCTestCase {
    func testUserVisibleMobileLabelsUseRinbamBrand() throws {
        let sourceRoot = URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent()
            .deletingLastPathComponent()
        let appInfoData = try Data(contentsOf: sourceRoot.appendingPathComponent("URLSaveriOS/Info.plist"))
        let shareInfoData = try Data(contentsOf: sourceRoot.appendingPathComponent("URLSaverShareExtension/Info.plist"))
        let appInfo = try XCTUnwrap(
            PropertyListSerialization.propertyList(from: appInfoData, format: nil) as? [String: Any]
        )
        let shareInfo = try XCTUnwrap(
            PropertyListSerialization.propertyList(from: shareInfoData, format: nil) as? [String: Any]
        )
        let visibleSourceURLs = [
            "URLSaveriOS/UI/AppChrome.swift",
            "URLSaveriOS/UI/ExportSheet.swift",
            "URLSaveriOS/UI/RootView.swift",
            "URLSaveriOS/UI/SharedTagCloudSheet.swift",
            "URLSaverShareExtension/ShareViewController.swift",
        ].map(sourceRoot.appendingPathComponent)
        let visibleSources = try visibleSourceURLs
            .map { try String(contentsOf: $0, encoding: .utf8) }
            .joined(separator: "\n")
        let quotedLegacyBrand = try NSRegularExpression(
            pattern: #"\"[^\"\n]*(URL Saver|UrlSaver)[^\"\n]*\""#
        )
        let sourceRange = NSRange(visibleSources.startIndex..., in: visibleSources)

        XCTAssertEqual(appInfo["CFBundleDisplayName"] as? String, "りんばむ")
        XCTAssertEqual(appInfo["CFBundleName"] as? String, "りんばむ")
        XCTAssertEqual(shareInfo["CFBundleDisplayName"] as? String, "りんばむ共有")
        XCTAssertEqual(shareInfo["CFBundleName"] as? String, "りんばむ共有")
        XCTAssertNil(quotedLegacyBrand.firstMatch(in: visibleSources, range: sourceRange))
    }

    func testServiceFilterOrderIncludesTikTokLikeAndroid() {
        XCTAssertEqual(serviceFilterOrder.map(\.rawValue), ["all", "youtube", "x", "instagram", "tiktok", "web"])
    }

    func testFilteredEntriesIncludesTikTokWhenTikTokSelected() {
        let tiktok = makeRecord(id: 1, serviceType: .tiktok, host: "www.tiktok.com")
        let web = makeRecord(id: 2, serviceType: .web, host: "example.com")

        XCTAssertEqual(filteredEntries([tiktok, web], selectedService: .tiktok).map(\.id), [1])
    }

    func testSearchFilteredEntriesMatchesOnlyAssignedLocalTagName() {
        let first = makeRecord(id: 1, serviceType: .web, host: "example.com")
        let second = makeRecord(id: 2, serviceType: .web, host: "travel.example.com")
        let travel = URLSaveriOS.LocalTagSummary(
            id: 10,
            name: "旅行",
            activeURLCount: 1,
            createdAt: .distantPast,
            updatedAt: .distantPast
        )

        let result = searchFilteredEntries(
            [first, second],
            query: "旅行",
            localTags: [travel],
            localTagAssignments: [2: [10]]
        )

        XCTAssertEqual(result.map(\.id), [2])
    }

    func testSearchFilteredEntriesDoesNotMatchUnassignedLocalTagName() {
        let first = makeRecord(id: 1, serviceType: .web, host: "example.com")
        let travel = URLSaveriOS.LocalTagSummary(
            id: 10,
            name: "旅行",
            activeURLCount: 0,
            createdAt: .distantPast,
            updatedAt: .distantPast
        )

        let result = searchFilteredEntries(
            [first],
            query: "旅行",
            localTags: [travel],
            localTagAssignments: [:]
        )

        XCTAssertTrue(result.isEmpty)
    }

    func testSearchFilteredEntriesMatchesCanonicalContentFields() {
        let bodyMatch = makeRecord(
            id: 1,
            serviceType: .instagram,
            host: "instagram.com",
            fetchedBody: "投稿内容に沖縄旅行の記録があります"
        )
        let memoMatch = makeRecord(
            id: 2,
            serviceType: .web,
            host: "memo.example.com",
            memo: "あとで精算する"
        )
        let authorMatch = makeRecord(
            id: 3,
            serviceType: .tiktok,
            host: "tiktok.com",
            fetchedAuthorName: "OpenAI Research"
        )
        let serviceMatch = makeRecord(id: 4, serviceType: .youtube, host: "youtube.com")
        let entries = [bodyMatch, memoMatch, authorMatch, serviceMatch]

        XCTAssertEqual(searchFilteredEntries(entries, query: "沖縄旅行").map(\.id), [1])
        XCTAssertEqual(searchFilteredEntries(entries, query: "精算").map(\.id), [2])
        XCTAssertEqual(searchFilteredEntries(entries, query: "research").map(\.id), [3])
        XCTAssertEqual(searchFilteredEntries(entries, query: "youtube").map(\.id), [4])
    }

    func testSwipeActionTriggerMatchesAndroidThreshold() {
        XCTAssertEqual(swipeActionTriggerWidth(containerWidth: 360), 144)
    }

    func testAllExportScopeIncludesSharedOnlyEntries() throws {
        let sharedOnly = makeRecord(id: 1, serviceType: .web, host: "example.com", localProvenanceCount: 0)
        let archive = try URLSaveriOS.URLExportArchiveBuilder.prepareExport(
            request: URLSaveriOS.URLExportRequest(
                scope: .all,
                selectedTagIDs: [],
                recordStateFilter: .both,
                serviceType: nil,
                onlyWithMemo: false,
                dateFrom: nil,
                dateTo: nil,
                outputFormat: .zip
            ),
            entries: [sharedOnly],
            localTags: [],
            localTagAssignments: [:],
            sharedTagsByEntryID: [
                1: [
                    URLSaveriOS.SharedTagSummary(
                        remoteTagID: "remote-export",
                        name: "shared export",
                        currentUserRole: .owner,
                        activeURLCount: 1,
                        lastSyncedAt: nil
                    )
                ]
            ],
            appVersion: "test"
        )

        XCTAssertEqual(archive.entryCount, 1)
        XCTAssertEqual(archive.byteCount, Int64(try Data(contentsOf: archive.fileURL).count))
        XCTAssertGreaterThan(archive.byteCount, 0)
    }

    func testZipExportUsesZipFileNameAndMimeType() throws {
        let record = makeRecord(id: 10, serviceType: .web, host: "example.com")
        let archive = try URLSaveriOS.URLExportArchiveBuilder.prepareExport(
            request: URLSaveriOS.URLExportRequest(
                scope: .all,
                selectedTagIDs: [],
                recordStateFilter: .both,
                serviceType: nil,
                onlyWithMemo: false,
                dateFrom: nil,
                dateTo: nil,
                outputFormat: .zip
            ),
            entries: [record],
            localTags: [],
            localTagAssignments: [:],
            sharedTagsByEntryID: [:],
            appVersion: "test"
        )

        XCTAssertTrue(archive.fileName.hasSuffix(".zip"))
        XCTAssertEqual(archive.mimeType, "application/zip")
        let handle = try FileHandle(forReadingFrom: archive.fileURL)
        defer { try? handle.close() }
        let prefix = try handle.read(upToCount: 2) ?? Data()
        XCTAssertEqual(Array(prefix), [0x50, 0x4b]) // ZIP local file header prefix (PK)
    }

    func testJSONExportProducesManifestAndEntries() throws {
        let record = makeRecord(id: 11, serviceType: .web, host: "example.com")
        let archive = try URLSaveriOS.URLExportArchiveBuilder.prepareExport(
            request: URLSaveriOS.URLExportRequest(
                scope: .all,
                selectedTagIDs: [],
                recordStateFilter: .both,
                serviceType: nil,
                onlyWithMemo: false,
                dateFrom: nil,
                dateTo: nil,
                outputFormat: .json
            ),
            entries: [record],
            localTags: [],
            localTagAssignments: [:],
            sharedTagsByEntryID: [:],
            appVersion: "test"
        )

        XCTAssertTrue(archive.fileName.hasSuffix(".json"))
        XCTAssertEqual(archive.mimeType, "application/json")

        let json = try XCTUnwrap(
            try JSONSerialization.jsonObject(with: Data(contentsOf: archive.fileURL)) as? [String: Any]
        )
        let manifest = try XCTUnwrap(json["manifest"] as? [String: Any])
        XCTAssertEqual(manifest["entryCount"] as? Int, 1)
        let entries = try XCTUnwrap(json["entries"] as? [[String: Any]])
        XCTAssertEqual(entries.count, 1)
        XCTAssertEqual(entries.first?["normalizedUrl"] as? String, "https://example.com/")
    }

    func testOutputFormatListOnlyExposesZipAndJson() {
        XCTAssertEqual(URLExportOutputFormat.allCases, [.zip, .json])
        let rawValues = Set(URLExportOutputFormat.allCases.map(\.rawValue))
        XCTAssertEqual(rawValues, Set(["ZIP", "JSON"]))
        XCTAssertFalse(rawValues.contains("CSV"))
        XCTAssertFalse(rawValues.contains("HTML"))
    }

    func testExportSheetSourceDoesNotContainLegacyCSVHtmlOrCopyOptions() throws {
        let source = try String(contentsOf: exportSheetSourceURL(), encoding: .utf8)
        XCTAssertNil(source.range(of: #"\bcase\s+csv\b"#, options: .regularExpression))
        XCTAssertNil(source.range(of: #"\bcase\s+html\b"#, options: .regularExpression))
        XCTAssertNil(source.range(of: #"\bcase\s+copy\b"#, options: .regularExpression))
    }

    func testCardSwipeAxisStartsOnlyAfterIntentionalMovement() {
        XCTAssertNil(cardSwipeAxis(horizontal: 10, vertical: 2))
    }

    func testCardSwipeRecognizerDoesNotBeginForTapJitter() {
        XCTAssertFalse(cardSwipeShouldBegin(horizontal: 0, vertical: 0, velocityX: 0, velocityY: 0))
        XCTAssertFalse(cardSwipeShouldBegin(horizontal: 6, vertical: 4, velocityX: 35, velocityY: 20))
    }

    func testCardSwipeAxisRequiresHorizontalDominance() {
        XCTAssertEqual(cardSwipeAxis(horizontal: 30, vertical: 8), .horizontal)
        XCTAssertEqual(cardSwipeAxis(horizontal: 24, vertical: 22), .vertical)
        XCTAssertEqual(cardSwipeAxis(horizontal: 8, vertical: 30), .vertical)
    }

    func testCardSwipeRecognizerDoesNotBeginForVerticalScroll() {
        XCTAssertFalse(cardSwipeShouldBegin(horizontal: 4, vertical: 30, velocityX: 20, velocityY: 600))
        XCTAssertFalse(cardSwipeShouldBegin(horizontal: 20, vertical: 28, velocityX: 80, velocityY: 500))
    }

    func testCardSwipeRecognizerBeginsForIntentionalHorizontalSwipe() {
        XCTAssertTrue(cardSwipeShouldBegin(horizontal: 32, vertical: 6, velocityX: 160, velocityY: 30))
        XCTAssertTrue(cardSwipeShouldBegin(horizontal: 5, vertical: 2, velocityX: 500, velocityY: 80))
    }

    func testEntryListLoadStateDistinguishesEmptyAndContent() {
        XCTAssertEqual(entryListLoadState(for: []), .empty)

        let record = makeRecord(id: 20, serviceType: .web, host: "example.com")
        XCTAssertEqual(entryListLoadState(for: [record]), .content)
    }

    func testSearchRaceGuardRejectsCancelledAndStaleCompletions() {
        XCTAssertTrue(shouldApplySearchResult(
            requestedQuery: "旅行",
            currentQuery: " 旅行 ",
            isCancelled: false
        ))
        XCTAssertFalse(shouldApplySearchResult(
            requestedQuery: "旅行",
            currentQuery: "仕事",
            isCancelled: false
        ))
        XCTAssertFalse(shouldApplySearchResult(
            requestedQuery: "旅行",
            currentQuery: "旅行",
            isCancelled: true
        ))
    }

    func testMobileRemediationVisualAccessibilityAndBatchContractsRemainPresent() throws {
        let rootSource = try String(contentsOf: rootViewSourceURL(), encoding: .utf8)
        XCTAssertTrue(rootSource.contains("title: \"URLをあとで開くために保存\""))
        XCTAssertTrue(rootSource.contains("title: \"使い方はいつでも確認\""))
        XCTAssertTrue(rootSource.contains("@ScaledMetric(relativeTo: .body) private var mainBottomContentPadding"))
        XCTAssertTrue(rootSource.contains(".padding(.bottom, min(mainBottomContentPadding, 280))"))
        XCTAssertTrue(rootSource.contains("ScrollView(.vertical, showsIndicators: dynamicTypeSize.isAccessibilitySize)"))
        XCTAssertTrue(rootSource.contains("ViewThatFits(in: .horizontal)"))
        XCTAssertTrue(rootSource.contains(".font(.system(.body, design: .default).weight(.medium))"))
        XCTAssertTrue(rootSource.contains("layout: .stacked"))
        XCTAssertTrue(rootSource.contains("dynamicTypeSize.isAccessibilitySize"))
        XCTAssertTrue(rootSource.contains("result.failedEntryIDs"))
        XCTAssertTrue(rootSource.contains("isBatchMutationInFlight"))
        XCTAssertFalse(rootSource.contains("ローンチ版"))

        let chromeSource = try String(contentsOf: appChromeSourceURL(), encoding: .utf8)
        XCTAssertTrue(chromeSource.contains("selectedSurface"))
        XCTAssertTrue(chromeSource.contains("Image(systemName: \"checkmark\")"))
        XCTAssertTrue(chromeSource.contains(".accessibilityAddTraits(selected ? .isSelected : [])"))
        XCTAssertTrue(chromeSource.contains("minWidth: 44"))
        XCTAssertTrue(chromeSource.contains("entryCardDistinctHeaderText"))

        let purchaseSource = try String(contentsOf: sharedTagCloudSheetSourceURL(), encoding: .utf8)
        XCTAssertTrue(purchaseSource.contains("availablePaidCoursePurchaseOptions"))
        XCTAssertTrue(purchaseSource.contains("追加購入は必要ありません"))
    }

    func testAdjustedSelectionAndWarningColorsMeetTextContrast() {
        XCTAssertGreaterThanOrEqual(contrastRatio(foreground: 0x1F6FD1, background: 0xF2F7FF), 4.5)
        XCTAssertGreaterThanOrEqual(contrastRatio(foreground: 0x8BC3FF, background: 0x173A5E), 4.5)
        XCTAssertGreaterThanOrEqual(contrastRatio(foreground: 0x9B2C20, background: 0xFFFFFF), 4.5)
        XCTAssertGreaterThanOrEqual(contrastRatio(foreground: 0x9A3E00, background: 0xFFF7ED), 4.5)
    }

    func testIOSListGuideRetrySaveFailureAndVoiceOverContractsRemainPresent() throws {
        let rootSource = try String(contentsOf: rootViewSourceURL(), encoding: .utf8)
        XCTAssertTrue(rootSource.contains("isShowingUsageGuide = true"))
        XCTAssertTrue(rootSource.contains("EntryListStateCard("))
        XCTAssertTrue(rootSource.contains("onRetryLoad"))
        XCTAssertTrue(rootSource.contains("ViewThatFits(in: .horizontal)"))
        XCTAssertTrue(rootSource.contains("saveResult.result == .saveFailed"))
        XCTAssertTrue(rootSource.contains("Web版の詳しい使い方"))

        let modelSource = try String(contentsOf: appModelSourceURL(), encoding: .utf8)
        XCTAssertTrue(modelSource.contains("activeEntriesLoadState"))
        XCTAssertTrue(modelSource.contains("activeEntriesLoadState = .error(message)"))
        XCTAssertTrue(modelSource.contains("case retryTitle(Int64, String)"))
        XCTAssertTrue(modelSource.contains("case retryMemo(Int64, String)"))
        XCTAssertTrue(modelSource.contains("入力内容は保持されています"))

        let chromeSource = try String(contentsOf: appChromeSourceURL(), encoding: .utf8)
        XCTAssertTrue(chromeSource.contains("entryCardAccessibilityLabel"))
        XCTAssertTrue(chromeSource.contains("accessibilityAction(named: Text(\"アーカイブ\"))"))
        XCTAssertTrue(chromeSource.contains("accessibilityAction(named: Text(\"削除\"))"))
        XCTAssertTrue(chromeSource.contains("accessibilityAction(named: Text(\"外す\"))"))

        let detailSource = try String(contentsOf: detailViewSourceURL(), encoding: .utf8)
        XCTAssertTrue(detailSource.contains("isSavingTitle"))
        XCTAssertTrue(detailSource.contains("memoSaveError"))
        XCTAssertTrue(detailSource.contains("タイトルを保存できませんでした。入力内容は保持されています。"))
        XCTAssertTrue(detailSource.contains("メモを保存できませんでした。入力内容は保持されています。"))
        XCTAssertTrue(detailSource.contains("Text(titleSaveError == nil ? \"保存\" : \"再試行\")"))
        XCTAssertTrue(detailSource.contains("Text(saveError == nil ? \"保存\" : \"再試行\")"))
    }

    func testDuplicateSaveNotificationsUseContentNeutralWordingsInBothSources() throws {
        let appModelSource = try String(contentsOf: appModelSourceURL(), encoding: .utf8)
        let shareExtensionSource = try String(contentsOf: shareExtensionSourceURL(), encoding: .utf8)

        for source in [appModelSource, shareExtensionSource] {
            XCTAssertTrue(source.contains("この内容はすでに保存済みです"))
            XCTAssertTrue(source.contains("この内容はアーカイブ済みです"))
            XCTAssertFalse(source.contains("このURLはすでに保存済みです"))
            XCTAssertFalse(source.contains("このURLはアーカイブ済みです"))
        }
    }

    func testMediaSortIndexParsesZeroPaddedPrefix() {
        XCTAssertEqual(rinbamMediaSortIndex(from: "000_shortcode_item_0.jpg"), 0)
        XCTAssertEqual(rinbamMediaSortIndex(from: "001_shortcode_item_1.mp4"), 1)
        XCTAssertEqual(rinbamMediaSortIndex(from: "010_shortcode_item_10.jpg"), 10)
    }

    func testMediaSortIndexRejectsLegacyNames() {
        XCTAssertNil(rinbamMediaSortIndex(from: "1_shortcode_item_0.jpg"))
        XCTAssertNil(rinbamMediaSortIndex(from: "instagram_shortcode_item_0.jpg"))
        XCTAssertNil(rinbamMediaSortIndex(from: "000-shortcode-item.jpg"))
    }

    func testMediaFileNamesSortNumericallyByPrefix() {
        let names = [
            "010_shortcode_item_10.jpg",
            "001_shortcode_item_1.mp4",
            "legacy_name.jpg",
            "000_shortcode_item_0.jpg",
        ]

        XCTAssertEqual(names.sorted(by: rinbamMediaFileNamePrecedes), [
            "000_shortcode_item_0.jpg",
            "001_shortcode_item_1.mp4",
            "010_shortcode_item_10.jpg",
            "legacy_name.jpg",
        ])
    }

    private func makeRecord(
        id: Int64,
        serviceType: URLSaveriOS.ServiceType,
        host: String,
        collectionID: Int64 = 1,
        localProvenanceCount: Int = 1,
        fetchedAuthorName: String? = nil,
        fetchedBody: String? = nil,
        bodySummary: String? = nil,
        description: String? = nil,
        memo: String = ""
    ) -> URLSaveriOS.URLRecord {
        URLSaveriOS.URLRecord(
            id: id,
            originalURL: "https://\(host)/",
            normalizedURL: "https://\(host)/",
            displayURL: "\(host)/",
            openURL: "https://\(host)/",
            normalizedHost: host,
            rawSourceHost: host,
            collectionID: collectionID,
            serviceType: serviceType,
            contentContext: .standard,
            userTitle: nil,
            fetchedTitle: nil,
            fetchedAuthorName: fetchedAuthorName,
            fetchedBody: fetchedBody,
            fetchedBodyKind: nil,
            bodySummary: bodySummary,
            description: description,
            memo: memo,
            thumbnailURL: nil,
            badgeImageURL: nil,
            canonicalID: nil,
            metadataState: .ready,
            metadataError: nil,
            metadataRequestedAt: nil,
            metadataFetchedAt: nil,
            recordState: .active,
            localProvenanceCount: localProvenanceCount,
            sharedReferenceCount: 0,
            createdAt: .distantPast,
            updatedAt: .distantPast,
            archivedAt: nil,
            pendingDeletionUntil: nil
        )
    }

    private func exportSheetSourceURL() -> URL {
        sourceURL("URLSaveriOS/UI/ExportSheet.swift")
    }

    private func rootViewSourceURL() -> URL {
        sourceURL("URLSaveriOS/UI/RootView.swift")
    }

    private func appModelSourceURL() -> URL {
        sourceURL("URLSaveriOS/App/URLSaverAppModel.swift")
    }

    private func shareExtensionSourceURL() -> URL {
        sourceURL("URLSaverShareExtension/ShareViewController.swift")
    }

    private func appChromeSourceURL() -> URL {
        sourceURL("URLSaveriOS/UI/AppChrome.swift")
    }

    private func detailViewSourceURL() -> URL {
        sourceURL("URLSaveriOS/UI/DetailView.swift")
    }

    private func sharedTagCloudSheetSourceURL() -> URL {
        sourceURL("URLSaveriOS/UI/SharedTagCloudSheet.swift")
    }

    private func contrastRatio(foreground: Int, background: Int) -> Double {
        func luminance(_ color: Int) -> Double {
            let components = [16, 8, 0].map { shift -> Double in
                let value = Double((color >> shift) & 0xFF) / 255
                return value <= 0.04045 ? value / 12.92 : pow((value + 0.055) / 1.055, 2.4)
            }
            return 0.2126 * components[0] + 0.7152 * components[1] + 0.0722 * components[2]
        }
        let first = luminance(foreground)
        let second = luminance(background)
        return (max(first, second) + 0.05) / (min(first, second) + 0.05)
    }

    private func sourceURL(_ relativePath: String) -> URL {
        URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent()
            .deletingLastPathComponent()
            .appendingPathComponent(relativePath)
    }
}
