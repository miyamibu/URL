import XCTest
@testable import URLSaveriOS

final class ServiceFilterTests: XCTestCase {
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
        XCTAssertGreaterThan(archive.bytes.count, 0)
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
        XCTAssertEqual(Array(archive.bytes.prefix(2)), [0x50, 0x4b]) // ZIP local file header prefix (PK)
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

        let json = try XCTUnwrap(try JSONSerialization.jsonObject(with: archive.bytes) as? [String: Any])
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
        URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent()
            .deletingLastPathComponent()
            .appendingPathComponent("URLSaveriOS/UI/ExportSheet.swift")
    }
}
