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

    func testFilteredEntriesMatchesSelectedCollectionOnly() {
        let inbox = makeRecord(id: 1, serviceType: .web, host: "example.com", collectionID: 1)
        let recipes = makeRecord(id: 2, serviceType: .web, host: "recipes.example.com", collectionID: 20)

        XCTAssertEqual(
            filteredEntries([inbox, recipes], selectedService: .all, selectedCollectionID: 20).map(\.id),
            [2]
        )
    }

    func testFilteredEntriesIncludesSameNameLocalTagForSelectedCollection() {
        let direct = makeRecord(id: 1, serviceType: .web, host: "recipes.example.com", collectionID: 20)
        let tagged = makeRecord(id: 2, serviceType: .web, host: "tagged.example.com", collectionID: 1)
        let other = makeRecord(id: 3, serviceType: .web, host: "other.example.com", collectionID: 1)
        let recipeCollection = URLSaveriOS.CollectionSummary(
            id: 20,
            name: "レシピ",
            sortOrder: 1,
            activeURLCount: 1,
            createdAt: .distantPast,
            updatedAt: .distantPast
        )
        let recipeTag = URLSaveriOS.LocalTagSummary(
            id: 30,
            name: "レシピ",
            activeURLCount: 1,
            createdAt: .distantPast,
            updatedAt: .distantPast
        )

        let result = filteredEntries(
            [direct, tagged, other],
            selectedService: .all,
            selectedCollectionID: 20,
            localTagAssignments: [2: [30]],
            localTags: [recipeTag],
            collections: [recipeCollection]
        )

        XCTAssertEqual(result.map(\.id), [1, 2])
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

    func testSearchFilteredEntriesMatchesOwnCollectionName() {
        let inbox = makeRecord(id: 1, serviceType: .web, host: "example.com", collectionID: 1)
        let recipes = makeRecord(id: 2, serviceType: .web, host: "recipes.example.com", collectionID: 20)
        let recipeCollection = URLSaveriOS.CollectionSummary(
            id: 20,
            name: "レシピ",
            sortOrder: 1,
            activeURLCount: 1,
            createdAt: .distantPast,
            updatedAt: .distantPast
        )

        let result = searchFilteredEntries(
            [inbox, recipes],
            query: "レシピ",
            collections: [recipeCollection]
        )

        XCTAssertEqual(result.map(\.id), [2])
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

    private func makeRecord(
        id: Int64,
        serviceType: URLSaveriOS.ServiceType,
        host: String,
        collectionID: Int64 = 1,
        localProvenanceCount: Int = 1
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
            fetchedBody: nil,
            fetchedBodyKind: nil,
            bodySummary: nil,
            description: nil,
            memo: "",
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
