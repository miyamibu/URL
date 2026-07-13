import Foundation
import XCTest
@testable import URLSaveriOS

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

    func testManualInputUppercaseSchemeAndDefaultPortDeduplicates() throws {
        let created = try repository.saveFromManualInput("HTTPS://Example.COM:443/manual-normalize/#frag")
        XCTAssertEqual(created.result, .created)

        let duplicate = try repository.saveFromManualInput("https://example.com/manual-normalize")
        XCTAssertEqual(duplicate.result, .duplicateActive)
        XCTAssertEqual(duplicate.entryID, created.entryID)

        let snapshot = try repository.observeActiveSnapshot()
            .filter { $0.normalizedURL == "https://example.com/manual-normalize" }
        XCTAssertEqual(snapshot.count, 1)
        XCTAssertEqual(snapshot.first?.normalizedURL, "https://example.com/manual-normalize")
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

    func testManualInputURLWithTextStoresTextAsMemo() throws {
        let created = try repository.saveFromManualInput(
            """
            あとで読む
            https://example.com/with-memo
            メモ本文
            """
        )
        let entry = try XCTUnwrap(repository.loadEntry(id: created.entryID!))

        XCTAssertEqual(entry.normalizedURL, "https://example.com/with-memo")
        XCTAssertEqual(entry.memo, "あとで読む\nメモ本文")
    }

    func testManualInputWithoutURLCreatesTextCard() throws {
        let body = """
        投稿メモのタイトル
        これはURLなしで保存する本文です。
        あとでカードとして読み返します。
        """

        let created = try repository.saveFromManualInput(body)
        XCTAssertEqual(created.result, .created)

        let entry = try XCTUnwrap(repository.loadEntry(id: created.entryID!))
        XCTAssertTrue(entry.normalizedURL.hasPrefix("https://text.rinbam.local/note/"))
        XCTAssertEqual(entry.displayURL, "テキスト")
        XCTAssertEqual(entry.fetchedTitle, "投稿メモのタイトル")
        XCTAssertEqual(entry.fetchedBody, body)
        XCTAssertEqual(entry.fetchedBodyKind, .webExcerpt)
        XCTAssertEqual(entry.bodySummary, "投稿メモのタイトル")
        XCTAssertEqual(entry.metadataState, .ready)
    }

    func testLegacyCollectionSchemaAndDefaultDecodeRemain() throws {
        XCTAssertEqual(try repository.database.fetchInt("SELECT id FROM collections WHERE id = 1;"), 1)
        XCTAssertEqual(try repository.database.fetchString("SELECT name FROM collections WHERE id = 1;"), "受信箱")

        let saved = try repository.saveFromManualInput("https://example.com/legacy-collection")
        let entryID = try XCTUnwrap(saved.entryID)
        try repository.database.execute(
            """
            INSERT INTO collections (id, name, normalized_name, sort_order, created_at, updated_at)
            VALUES (20, '旧コレクション', '旧コレクション', 1, 1, 1);
            """
        )
        try repository.database.execute(
            "UPDATE url_entries SET collection_id = 20 WHERE id = ?;",
            binds: [sql(entryID)]
        )

        XCTAssertEqual(try repository.loadEntry(id: entryID)?.collectionID, 20)

        let newEntry = try repository.saveFromManualInput("https://example.com/legacy-collection-default")
        XCTAssertEqual(try repository.loadEntry(id: try XCTUnwrap(newEntry.entryID))?.collectionID, 1)
    }

    func testLocalTagsCreateAtFrontAndReorder() throws {
        let first = try XCTUnwrap(try repository.createLocalTag(name: "first"))
        let second = try XCTUnwrap(try repository.createLocalTag(name: "second"))
        let third = try XCTUnwrap(try repository.createLocalTag(name: "third"))

        XCTAssertEqual(try repository.loadLocalTags().map(\.id), [third.id, second.id, first.id])

        XCTAssertTrue(try repository.reorderLocalTags(tagIDs: [first.id, third.id, second.id]))
        XCTAssertEqual(try repository.loadLocalTags().map(\.id), [first.id, third.id, second.id])

        let fourth = try XCTUnwrap(try repository.createLocalTag(name: "fourth"))
        XCTAssertEqual(try repository.loadLocalTags().map(\.id).prefix(4), [fourth.id, first.id, third.id, second.id])
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
        XCTAssertNotNil(try repository.markPendingDelete(entryID: second.entryID!, gracePeriod: 30))
        XCTAssertEqual(try repository.loadEntry(id: third.entryID!)?.recordState, .pendingDelete)
        XCTAssertNotNil(try repository.loadEntry(id: third.entryID!)?.pendingDeletionUntil)
        XCTAssertEqual(try repository.loadEntry(id: second.entryID!)?.recordState, .pendingDelete)

        XCTAssertTrue(try repository.restore(entryID: second.entryID!))
        XCTAssertEqual(try repository.loadEntry(id: second.entryID!)?.recordState, .archived)
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

    func testRetryMetadataAcceptsFailedUnavailableAndReadyWithoutFetchedContent() throws {
        let failed = try repository.saveFromManualInput("https://example.com/retry-failed")
        try repository.applyMetadataUpdate(
            entryID: failed.entryID!,
            metadata: MetadataUpdate(
                fetchedTitle: nil,
                fetchedBody: nil,
                fetchedBodyKind: nil,
                bodySummary: nil,
                description: nil,
                thumbnailURL: nil,
                badgeImageURL: nil,
                metadataState: .failed,
                metadataFetchedAt: Date(timeIntervalSince1970: 500),
                metadataError: .timeout,
                canonicalID: nil,
                normalizedHost: nil,
                rawSourceHost: nil
            )
        )
        XCTAssertTrue(try repository.retryMetadata(entryID: failed.entryID!))
        let failedAfter = try XCTUnwrap(repository.loadEntry(id: failed.entryID!))
        XCTAssertEqual(failedAfter.metadataState, .pending)
        XCTAssertNil(failedAfter.metadataError)
        XCTAssertNotNil(failedAfter.metadataRequestedAt)

        let unavailable = try repository.saveFromManualInput("https://example.com/retry-unavailable")
        try repository.applyMetadataUpdate(
            entryID: unavailable.entryID!,
            metadata: MetadataUpdate(
                fetchedTitle: nil,
                fetchedBody: nil,
                fetchedBodyKind: nil,
                bodySummary: nil,
                description: nil,
                thumbnailURL: nil,
                badgeImageURL: nil,
                metadataState: .unavailable,
                metadataFetchedAt: Date(timeIntervalSince1970: 600),
                metadataError: .nonHTML,
                canonicalID: nil,
                normalizedHost: nil,
                rawSourceHost: nil
            )
        )
        XCTAssertTrue(try repository.retryMetadata(entryID: unavailable.entryID!))
        let unavailableAfter = try XCTUnwrap(repository.loadEntry(id: unavailable.entryID!))
        XCTAssertEqual(unavailableAfter.metadataState, .pending)
        XCTAssertNil(unavailableAfter.metadataError)
        XCTAssertNotNil(unavailableAfter.metadataRequestedAt)

        let readyWithoutContent = try repository.saveFromManualInput("https://example.com/retry-ready")
        try repository.applyMetadataUpdate(
            entryID: readyWithoutContent.entryID!,
            metadata: MetadataUpdate(
                fetchedTitle: nil,
                fetchedBody: nil,
                fetchedBodyKind: nil,
                bodySummary: nil,
                description: nil,
                thumbnailURL: nil,
                badgeImageURL: nil,
                metadataState: .ready,
                metadataFetchedAt: Date(timeIntervalSince1970: 700),
                metadataError: nil,
                canonicalID: nil,
                normalizedHost: nil,
                rawSourceHost: nil
            )
        )
        XCTAssertTrue(try repository.retryMetadata(entryID: readyWithoutContent.entryID!))
        XCTAssertEqual(try repository.loadEntry(id: readyWithoutContent.entryID!)?.metadataState, .pending)

        let readyWithContent = try repository.saveFromManualInput("https://example.com/retry-ready-with-content")
        try repository.applyMetadataUpdate(
            entryID: readyWithContent.entryID!,
            metadata: MetadataUpdate(
                fetchedTitle: "title",
                fetchedBody: "body",
                fetchedBodyKind: .webExcerpt,
                bodySummary: "summary",
                description: nil,
                thumbnailURL: nil,
                badgeImageURL: nil,
                metadataState: .ready,
                metadataFetchedAt: Date(timeIntervalSince1970: 800),
                metadataError: nil,
                canonicalID: nil,
                normalizedHost: nil,
                rawSourceHost: nil
            )
        )
        XCTAssertFalse(try repository.retryMetadata(entryID: readyWithContent.entryID!))

        let readySocialMissingBadge = try repository.saveFromManualInput("https://x.com/openai/status/123")
        try repository.applyMetadataUpdate(
            entryID: readySocialMissingBadge.entryID!,
            metadata: MetadataUpdate(
                fetchedTitle: "post",
                fetchedBody: "body",
                fetchedBodyKind: .xPostText,
                bodySummary: "summary",
                description: nil,
                thumbnailURL: nil,
                badgeImageURL: nil,
                metadataState: .ready,
                metadataFetchedAt: Date(timeIntervalSince1970: 900),
                metadataError: nil,
                canonicalID: nil,
                normalizedHost: nil,
                rawSourceHost: nil
            )
        )
        XCTAssertTrue(try repository.retryMetadata(entryID: readySocialMissingBadge.entryID!))
        XCTAssertEqual(try repository.loadEntry(id: readySocialMissingBadge.entryID!)?.metadataState, .pending)

        let pending = try repository.saveFromManualInput("https://example.com/retry-pending")
        XCTAssertFalse(try repository.retryMetadata(entryID: pending.entryID!))
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
        XCTAssertTrue(exported.urls.contains { $0.url == "https://example.com/new-entry" && $0.title == nil && $0.memo == nil })

        let exportedData = try JSONEncoder().encode(exported)
        let exportedJSON = try XCTUnwrap(String(data: exportedData, encoding: .utf8))
        XCTAssertFalse(exportedJSON.contains("\"title\""))
        XCTAssertFalse(exportedJSON.contains("\"memo\""))

        let existing = try XCTUnwrap(repository.loadEntry(id: mergeOnly.entryID!))
        XCTAssertEqual(existing.userTitle, "existing title")
        XCTAssertEqual(existing.memo, "existing memo")
    }

    func testManualTagImportPreparationDoesNotWriteBeforeConfirmationAndStripsLegacyFields() throws {
        let legacyJSON = """
        {
          "urlsaver_version": 1,
          "tag": "  manual gate  ",
          "exported_at": 1234,
          "urls": [
            {"url": "https://example.com/manual-tag-import", "title": "Legacy title", "memo": "Legacy memo"},
            {"url": "not-a-url", "title": "Ignored title", "memo": "Ignored memo"}
          ]
        }
        """

        let appRepository = try URLSaveriOS.URLRepository(databaseURL: databaseURL)

        guard case .ready(let preview) = ManualTagImportPreparation.prepare(input: legacyJSON) else {
            XCTFail("legacy tag JSON should be prepared for confirmation")
            return
        }

        XCTAssertEqual(preview.tagName, "manual gate")
        XCTAssertEqual(preview.validURLCount, 1)
        XCTAssertTrue(try appRepository.observeActiveSnapshot().isEmpty)
        XCTAssertTrue(try appRepository.loadLocalTags().isEmpty)
        XCTAssertEqual(preview.payload.urls.first?.title, nil)
        XCTAssertEqual(preview.payload.urls.first?.memo, nil)

        let result = try ManualTagImportPreparation.importConfirmed(preview, repository: appRepository)

        XCTAssertEqual(result.created, 1)
        let entry = try XCTUnwrap(try appRepository.observeActiveSnapshot().first {
            $0.normalizedURL == "https://example.com/manual-tag-import"
        })
        XCTAssertNil(entry.userTitle)
        XCTAssertEqual(entry.memo, "")
    }

    func testManualTagImportCancellationLeavesDatabaseUnchanged() throws {
        let payload = TagSharePayload(
            urlsaverVersion: 1,
            tag: "cancelled-import",
            exportedAt: 1,
            urls: [TagShareURL(url: "https://example.com/cancelled-import")]
        )
        let input = String(data: try JSONEncoder().encode(payload), encoding: .utf8)!

        guard case .ready = ManualTagImportPreparation.prepare(input: input) else {
            XCTFail("tag JSON should be prepared for confirmation")
            return
        }

        XCTAssertTrue(try repository.observeActiveSnapshot().isEmpty)
        XCTAssertTrue(try repository.loadLocalTags().isEmpty)
    }

    func testManualTagImportPreparationValidatesURLCountAndSizeBeforeConfirmation() throws {
        let tooManyURLs = (0...URLRules.maxBatchSaveURLsPerIntake).map {
            TagShareURL(url: "https://example.com/too-many-\($0)")
        }
        let tooManyPayload = TagSharePayload(
            urlsaverVersion: 1,
            tag: "too-many",
            exportedAt: 1,
            urls: tooManyURLs
        )
        let tooManyInput = String(data: try JSONEncoder().encode(tooManyPayload), encoding: .utf8)!

        guard case .invalid(let countMessage) = ManualTagImportPreparation.prepare(input: tooManyInput) else {
            XCTFail("too many URLs should stop before confirmation")
            return
        }
        XCTAssertTrue(countMessage.contains("件以内"))

        let oversizedInput = "{" + String(repeating: "a", count: URLRules.maxInputTextBytes) + "}"
        guard case .invalid(let sizeMessage) = ManualTagImportPreparation.prepare(input: oversizedInput) else {
            XCTFail("oversized JSON should stop before confirmation")
            return
        }
        XCTAssertTrue(sizeMessage.contains("256KB"))
        XCTAssertTrue(try repository.observeActiveSnapshot().isEmpty)
        XCTAssertTrue(try repository.loadLocalTags().isEmpty)
    }

    func testManualURLInputStillCreatesEntryNormally() throws {
        let result = try repository.saveFromManualInput("https://example.com/manual-url-still-works")

        XCTAssertEqual(result.result, .created)
        XCTAssertNotNil(result.entryID)
        XCTAssertEqual(try repository.observeActiveSnapshot().count, 1)
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
