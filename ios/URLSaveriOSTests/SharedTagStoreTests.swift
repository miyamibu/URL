import Foundation
import XCTest

final class SharedTagStoreTests: XCTestCase {
    private var databaseURL: URL!
    private var repository: URLRepository!
    private var store: SharedTagStore!

    override func setUpWithError() throws {
        let directory = FileManager.default.temporaryDirectory
            .appendingPathComponent(UUID().uuidString, isDirectory: true)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        databaseURL = directory.appendingPathComponent("test.sqlite")
        repository = try URLRepository(databaseURL: databaseURL)
        store = try SharedTagStore(database: repository.database)
    }

    override func tearDownWithError() throws {
        store = nil
        repository = nil
        if let databaseURL {
            try? FileManager.default.removeItem(at: databaseURL.deletingLastPathComponent())
        }
    }

    func testApplySnapshotCreatesHiddenSharedOnlyRowsAndVisibleTagSummary() throws {
        try store.applySnapshot(
            authUserID: "user-1",
            snapshot: PullSharedTagSnapshotResponse(
                pulledAt: "2026-04-23T10:00:00Z",
                normalizationVersion: 1,
                tags: [
                    RemoteSharedTag(
                        id: "tag-1",
                        name: "設計メモ",
                        createdAt: "2026-04-23T09:00:00Z",
                        updatedAt: "2026-04-23T09:30:00Z",
                        deletedAt: nil
                    )
                ],
                members: [
                    RemoteSharedTagMember(
                        tagID: "tag-1",
                        userID: "user-1",
                        role: "owner",
                        status: "active",
                        createdAt: "2026-04-23T09:00:00Z",
                        updatedAt: "2026-04-23T09:30:00Z"
                    )
                ],
                urls: [
                    RemoteSharedTagURL(
                        id: "url-1",
                        tagID: "tag-1",
                        rawURL: "https://example.com/shared-article",
                        normalizedURL: "https://example.com/shared-article",
                        deletedAt: nil
                    )
                ]
            )
        )

        XCTAssertTrue(try repository.observeActiveSnapshot().isEmpty)

        let entry = try XCTUnwrap(repository.loadEntry(id: 1))
        XCTAssertEqual(entry.normalizedURL, "https://example.com/shared-article")
        XCTAssertEqual(entry.localProvenanceCount, 0)
        XCTAssertEqual(entry.sharedReferenceCount, 1)

        let tags = try store.loadVisibleTags(authUserID: "user-1")
        XCTAssertEqual(tags.count, 1)
        XCTAssertEqual(tags.first?.name, "設計メモ")
        XCTAssertEqual(tags.first?.currentUserRole, .owner)
        XCTAssertEqual(tags.first?.activeURLCount, 1)
    }

    func testClearLocalSharedStateDropsHiddenCacheRows() throws {
        try store.applySnapshot(
            authUserID: "user-1",
            snapshot: PullSharedTagSnapshotResponse(
                pulledAt: "2026-04-23T10:00:00Z",
                normalizationVersion: 1,
                tags: [
                    RemoteSharedTag(
                        id: "tag-1",
                        name: "設計メモ",
                        createdAt: "2026-04-23T09:00:00Z",
                        updatedAt: "2026-04-23T09:30:00Z",
                        deletedAt: nil
                    )
                ],
                members: [
                    RemoteSharedTagMember(
                        tagID: "tag-1",
                        userID: "user-1",
                        role: "owner",
                        status: "active",
                        createdAt: "2026-04-23T09:00:00Z",
                        updatedAt: "2026-04-23T09:30:00Z"
                    )
                ],
                urls: [
                    RemoteSharedTagURL(
                        id: "url-1",
                        tagID: "tag-1",
                        rawURL: "https://example.com/shared-article",
                        normalizedURL: "https://example.com/shared-article",
                        deletedAt: nil
                    )
                ]
            )
        )

        try store.clearLocalSharedState()

        XCTAssertTrue(try store.loadVisibleTags(authUserID: "user-1").isEmpty)
        XCTAssertNil(try repository.loadEntry(id: 1))
    }

    func testLoadVisibleTagsForEntryAndEntryIDsForTag() throws {
        let local = try repository.saveFromResolvedURL("https://example.com/shared-article")
        XCTAssertEqual(local.entryID, 1)

        try store.applySnapshot(
            authUserID: "user-1",
            snapshot: PullSharedTagSnapshotResponse(
                pulledAt: "2026-04-23T10:00:00Z",
                normalizationVersion: 1,
                tags: [
                    RemoteSharedTag(
                        id: "tag-1",
                        name: "設計メモ",
                        createdAt: "2026-04-23T09:00:00Z",
                        updatedAt: "2026-04-23T09:30:00Z",
                        deletedAt: nil
                    )
                ],
                members: [
                    RemoteSharedTagMember(
                        tagID: "tag-1",
                        userID: "user-1",
                        role: "editor",
                        status: "active",
                        createdAt: "2026-04-23T09:00:00Z",
                        updatedAt: "2026-04-23T09:30:00Z"
                    )
                ],
                urls: [
                    RemoteSharedTagURL(
                        id: "url-1",
                        tagID: "tag-1",
                        rawURL: "https://example.com/shared-article",
                        normalizedURL: "https://example.com/shared-article",
                        deletedAt: nil
                    )
                ]
            )
        )

        let tagsForEntry = try store.loadVisibleTagsForEntry(
            authUserID: "user-1",
            normalizedURL: "https://example.com/shared-article"
        )
        XCTAssertEqual(tagsForEntry.map(\.name), ["設計メモ"])
        XCTAssertEqual(tagsForEntry.first?.currentUserRole, .editor)

        let entryIDs = try store.loadEntryIDsForTag(authUserID: "user-1", remoteTagID: "tag-1")
        XCTAssertEqual(entryIDs, [1])
    }
}
