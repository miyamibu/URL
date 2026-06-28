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

    func testSavedEntriesDefaultToInboxCollectionID() throws {
        let created = try repository.saveFromManualInput("https://example.com/collection-default")
        let entry = try XCTUnwrap(repository.loadEntry(id: created.entryID!))

        XCTAssertEqual(entry.collectionID, 1)
    }

    func testCollectionLifecycleCreateAssignReorderAndDelete() throws {
        let defaultCollection = try XCTUnwrap(try repository.loadCollections().first)
        XCTAssertEqual(defaultCollection.id, 1)
        XCTAssertEqual(defaultCollection.name, "受信箱")

        let work = try XCTUnwrap(try repository.createCollection(name: " work "))
        let later = try XCTUnwrap(try repository.createCollection(name: "later"))
        let duplicateWork = try XCTUnwrap(try repository.createCollection(name: "work"))
        XCTAssertEqual(duplicateWork.id, work.id)

        let saved = try repository.saveFromManualInput("https://example.com/collection-work")
        XCTAssertTrue(try repository.assignCollection(entryID: saved.entryID!, collectionID: work.id))
        XCTAssertEqual(try repository.loadEntry(id: saved.entryID!)?.collectionID, work.id)

        XCTAssertTrue(try repository.reorderCollections(collectionIDs: [later.id, work.id]))
        let customOrder = try repository.loadCollections()
            .filter { $0.id != 1 }
            .map(\.id)
        XCTAssertEqual(customOrder, [later.id, work.id])

        XCTAssertTrue(try repository.deleteCollection(id: work.id))
        XCTAssertEqual(try repository.loadEntry(id: saved.entryID!)?.collectionID, 1)
        XCTAssertFalse(try repository.deleteCollection(id: 1))
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

    func testMultipleArchiveAndPendingDeleteStateTransitions() throws {
        let first = try repository.saveFromManualInput("https://example.com/batch-first")
        let second = try repository.saveFromManualInput("https://example.com/batch-second")
        let third = try repository.saveFromManualInput("https://example.com/batch-third")

        XCTAssertTrue(try repository.archive(entryID: first.entryID!))
        XCTAssertTrue(try repository.archive(entryID: second.entryID!))
        XCTAssertFalse(try repository.archive(entryID: first.entryID!))
        XCTAssertEqual(try repository.loadEntry(id: first.entryID!)?.recordState, .archived)
        XCTAssertEqual(try repository.loadEntry(id: second.entryID!)?.recordState, .archived)
        XCTAssertEqual(try repository.loadEntry(id: third.entryID!)?.recordState, .active)

        XCTAssertNotNil(try repository.markPendingDelete(entryID: third.entryID!, gracePeriod: 30))
        XCTAssertNil(try repository.markPendingDelete(entryID: second.entryID!, gracePeriod: 30))
        XCTAssertEqual(try repository.loadEntry(id: third.entryID!)?.recordState, .pendingDelete)
        XCTAssertNotNil(try repository.loadEntry(id: third.entryID!)?.pendingDeletionUntil)
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

    func testLocalTagPayloadExportImportTracksCreatedMergedAndSkipped() throws {
        let tag = try XCTUnwrap(repository.createLocalTag(name: "shared-import"))
        let alreadyTagged = try repository.saveFromResolvedURL("https://example.com/already-tagged")
        let mergeOnly = try repository.saveFromResolvedURL("https://example.com/merge-me")
        _ = try repository.saveUserTitle(entryID: mergeOnly.entryID!, rawTitle: "existing title")
        _ = try repository.saveMemo(entryID: mergeOnly.entryID!, rawMemo: "existing memo")
        XCTAssertTrue(try repository.assignLocalTag(entryID: alreadyTagged.entryID!, tagID: tag.id))

        let payload = TagSharePayload(
            urlsaverVersion: 1,
            tag: "shared-import",
            exportedAt: 1_234,
            urls: [
                TagShareURL(url: "https://example.com/new-entry", title: "Imported title", memo: "Imported memo"),
                TagShareURL(url: "https://example.com/merge-me", title: "Should not replace", memo: "Should not replace"),
                TagShareURL(url: "https://example.com/already-tagged", title: "Ignored title", memo: "Ignored memo"),
                TagShareURL(url: "not-a-url", title: "Broken", memo: "Broken"),
            ]
        )

        let result = try repository.importLocalTagPayload(payload)

        XCTAssertEqual(result.tagID, tag.id)
        XCTAssertEqual(result.tagName, "shared-import")
        XCTAssertEqual(result.created, 1)
        XCTAssertEqual(result.merged, 1)
        XCTAssertEqual(result.duplicateSkipped, 1)
        XCTAssertEqual(result.failed, 1)

        let exported = try XCTUnwrap(repository.exportLocalTag(tagID: tag.id))
        XCTAssertEqual(exported.urlsaverVersion, 1)
        XCTAssertEqual(exported.tag, "shared-import")
        XCTAssertTrue(exported.urls.contains { $0.url == "https://example.com/new-entry" && $0.title == "Imported title" })

        let existing = try XCTUnwrap(repository.loadEntry(id: mergeOnly.entryID!))
        XCTAssertEqual(existing.userTitle, "existing title")
        XCTAssertEqual(existing.memo, "existing memo")
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
