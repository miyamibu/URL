import Foundation
import XCTest

final class URLRepositoryTests: XCTestCase {
    private var databaseURL: URL!
    private var repository: URLRepository!

    override func setUpWithError() throws {
        let directory = FileManager.default.temporaryDirectory
            .appendingPathComponent(UUID().uuidString, isDirectory: true)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        databaseURL = directory.appendingPathComponent("test.sqlite")
        repository = try URLRepository(databaseURL: databaseURL)
    }

    override func tearDownWithError() throws {
        repository = nil
        if let databaseURL {
            try? FileManager.default.removeItem(at: databaseURL.deletingLastPathComponent())
        }
    }

    func testCreateAndDuplicateLifecycle() async throws {
        let created = try repository.saveFromManualInput("https://example.com/path")
        XCTAssertEqual(created.result, .created)

        let duplicateActive = try repository.saveFromManualInput("https://example.com/path")
        XCTAssertEqual(duplicateActive.result, .duplicateActive)
        XCTAssertEqual(duplicateActive.entryID, created.entryID)

        XCTAssertTrue(try repository.archive(entryID: created.entryID!))
        let duplicateArchived = try repository.saveFromManualInput("https://example.com/path")
        XCTAssertEqual(duplicateArchived.result, .duplicateArchived)
        XCTAssertEqual(duplicateArchived.entryID, created.entryID)
    }

    func testResolvedURLSaveCreatesEntryForSharePath() throws {
        let created = try repository.saveFromResolvedURL("https://example.com/shared")
        XCTAssertEqual(created.result, .created)

        let entry = try repository.loadEntry(id: created.entryID!)
        XCTAssertEqual(entry?.normalizedURL, "https://example.com/shared")
        XCTAssertEqual(entry?.openURL, entry?.normalizedURL)
    }

    func testPendingDeleteRestorePreservesEntryAndSchedulesMetadataWhenNeeded() async throws {
        let created = try repository.saveFromManualInput("https://example.com/path")
        XCTAssertEqual(created.result, .created)
        XCTAssertNotNil(try repository.markPendingDelete(entryID: created.entryID!))

        let restored = try repository.saveFromManualInput("https://example.com/path")
        XCTAssertEqual(restored.result, .restoredFromPendingDelete)
        XCTAssertEqual(restored.entryID, created.entryID)
        XCTAssertTrue(restored.shouldScheduleMetadata)
    }

    func testMetadataUpdateDoesNotChangeUpdatedAt() async throws {
        let created = try repository.saveFromManualInput("https://example.com/meta")
        let before = try repository.loadEntry(id: created.entryID!)!

        try repository.applyMetadataUpdate(
            entryID: created.entryID!,
            metadata: MetadataUpdate(
                fetchedTitle: "title",
                fetchedBody: nil,
                fetchedBodyKind: nil,
                bodySummary: nil,
                description: nil,
                thumbnailURL: nil,
                badgeImageURL: nil,
                metadataState: .ready,
                metadataFetchedAt: Date(timeIntervalSince1970: 500),
                metadataError: nil,
                canonicalID: nil,
                normalizedHost: nil,
                rawSourceHost: nil
            )
        )

        let after = try repository.loadEntry(id: created.entryID!)!
        XCTAssertEqual(before.updatedAt, after.updatedAt)
        XCTAssertEqual(after.metadataFetchedAt, Date(timeIntervalSince1970: 500))
        XCTAssertEqual(after.fetchedTitle, "title")
    }

    func testTitleAndMemoNormalization() async throws {
        let created = try repository.saveFromManualInput("https://example.com/notes")
        _ = try repository.saveMemo(entryID: created.entryID!, rawMemo: "   ")
        _ = try repository.saveUserTitle(entryID: created.entryID!, rawTitle: "   ")

        let entry = try repository.loadEntry(id: created.entryID!)!
        XCTAssertEqual(entry.memo, "")
        XCTAssertNil(entry.userTitle)
    }

    func testManualInputTooLargeReturnsExplicitResult() throws {
        let oversized = String(repeating: "a", count: URLRules.maxInputTextBytes + 1) + " https://example.com/oversized"
        let result = try repository.saveFromManualInput(oversized)
        XCTAssertEqual(result.result, .inputTooLarge)
        let snapshot = try repository.observeActiveSnapshot()
        XCTAssertTrue(snapshot.isEmpty)
    }

    func testSharedOnlyCacheRowStaysHiddenUntilPromotedToLocalProvenance() throws {
        let database = try SQLiteDatabase(databaseURL: databaseURL)
        let now = Date(timeIntervalSince1970: 100)

        _ = try database.insert(
            """
            INSERT INTO url_entries (
                original_url,
                normalized_url,
                display_url,
                open_url,
                normalized_host,
                raw_source_host,
                service_type,
                content_context,
                metadata_state,
                metadata_requested_at,
                record_state,
                local_provenance_count,
                shared_reference_count,
                created_at,
                updated_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'READY', ?, 'ACTIVE', 0, 1, ?, ?);
            """,
            binds: [
                sql("https://example.com/shared-only"),
                sql("https://example.com/shared-only"),
                sql("example.com/shared-only"),
                sql("https://example.com/shared-only"),
                sql("example.com"),
                sql("example.com"),
                sql(ServiceType.web.rawValue),
                sql(ContentContext.standard.rawValue),
                sql(now.timeIntervalSince1970),
                sql(now.timeIntervalSince1970),
                sql(now.timeIntervalSince1970),
            ]
        )

        XCTAssertTrue(try repository.observeActiveSnapshot().isEmpty)

        let promoted = try repository.saveFromResolvedURL("https://example.com/shared-only")
        XCTAssertEqual(promoted.result, .created)

        let visible = try repository.observeActiveSnapshot()
        XCTAssertEqual(visible.count, 1)
        XCTAssertEqual(visible.first?.normalizedURL, "https://example.com/shared-only")
        XCTAssertEqual(visible.first?.localProvenanceCount, 1)
        XCTAssertEqual(visible.first?.sharedReferenceCount, 1)
    }

    func testCleanupExpiredPendingDeleteKeepsSharedBackedCacheRow() throws {
        let created = try repository.saveFromResolvedURL("https://example.com/keep-shared")
        let entryID = try XCTUnwrap(created.entryID)
        let database = try SQLiteDatabase(databaseURL: databaseURL)

        try database.execute(
            """
            UPDATE url_entries
            SET shared_reference_count = 1
            WHERE id = ?;
            """,
            binds: [sql(entryID)]
        )

        XCTAssertNotNil(try repository.markPendingDelete(entryID: entryID, gracePeriod: 1))
        try repository.cleanupExpiredPendingDeletes(now: Date(timeIntervalSinceNow: 2))

        XCTAssertTrue(try repository.observeActiveSnapshot().isEmpty)

        let retained = try XCTUnwrap(repository.loadEntry(id: entryID))
        XCTAssertEqual(retained.localProvenanceCount, 0)
        XCTAssertEqual(retained.sharedReferenceCount, 1)
        XCTAssertEqual(retained.recordState, .active)
    }
}
