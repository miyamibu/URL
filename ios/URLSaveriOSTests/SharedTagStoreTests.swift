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
                        displayName: nil,
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
                        addedBy: "collaborator-1",
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

        XCTAssertEqual(
            try store.loadSharedTagURLNotificationEvents(authUserID: "user-1"),
            [
                SharedTagURLNotificationEvent(
                    remoteTagID: "tag-1",
                    tagName: "設計メモ",
                    remoteURLID: "url-1",
                    addedBy: "collaborator-1"
                )
            ]
        )
    }

    func testSharedTagNotificationCandidatesExcludeSelfReplayAndInitialSync() {
        let knownTag = SharedTagSummary(
            remoteTagID: "tag-known",
            name: "共有",
            currentUserRole: .editor,
            activeURLCount: 1,
            lastSyncedAt: nil
        )
        let replay = SharedTagURLNotificationEvent(
            remoteTagID: "tag-known",
            tagName: "共有",
            remoteURLID: "url-replay",
            addedBy: "other-user"
        )
        let own = SharedTagURLNotificationEvent(
            remoteTagID: "tag-known",
            tagName: "共有",
            remoteURLID: "url-own",
            addedBy: "current-user"
        )
        let external = SharedTagURLNotificationEvent(
            remoteTagID: "tag-known",
            tagName: "共有",
            remoteURLID: "url-external",
            addedBy: "other-user"
        )
        let initialSync = SharedTagURLNotificationEvent(
            remoteTagID: "tag-new",
            tagName: "新しい共有",
            remoteURLID: "url-initial",
            addedBy: "other-user"
        )
        let unknownActor = SharedTagURLNotificationEvent(
            remoteTagID: "tag-known",
            tagName: "共有",
            remoteURLID: "url-unknown-actor",
            addedBy: nil
        )

        XCTAssertEqual(
            sharedTagNotificationCandidates(
                beforeTags: [knownTag],
                beforeEvents: [replay],
                afterEvents: [replay, own, external, initialSync, unknownActor],
                authUserID: "current-user"
            ),
            [external]
        )
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
                        displayName: nil,
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
                        displayName: nil,
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

    func testChatGptPersonalLinkPolicyKeepsLocalLinksAndExcludesSharedOnlyLinks() throws {
        let local = try repository.saveFromResolvedURL("https://example.com/local-link")
        let localEntryID = try XCTUnwrap(local.entryID)
        let localEntry = try XCTUnwrap(repository.loadEntry(id: localEntryID))

        try store.applySnapshot(
            authUserID: "user-1",
            snapshot: PullSharedTagSnapshotResponse(
                pulledAt: "2026-04-23T10:00:00Z",
                normalizationVersion: 1,
                tags: [
                    RemoteSharedTag(
                        id: "tag-1",
                        name: "共有リンク",
                        createdAt: "2026-04-23T09:00:00Z",
                        updatedAt: "2026-04-23T09:30:00Z",
                        deletedAt: nil
                    )
                ],
                members: [
                    RemoteSharedTagMember(
                        tagID: "tag-1",
                        userID: "user-1",
                        displayName: nil,
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
                        rawURL: "https://example.com/shared-link",
                        normalizedURL: "https://example.com/shared-link",
                        deletedAt: nil
                    )
                ]
            )
        )

        let sharedEntry = try XCTUnwrap(
            try repository.loadChatGptPersonalLinkSnapshot().first {
                $0.normalizedURL == "https://example.com/shared-link"
            }
        )

        let localEligibility = ChatGptPersonalLinkSyncPolicy.eligibility(for: localEntry)
        XCTAssertTrue(localEligibility.eligible)
        XCTAssertTrue(localEligibility.reasons.isEmpty)

        let sharedEligibility = ChatGptPersonalLinkSyncPolicy.eligibility(for: sharedEntry)
        XCTAssertFalse(sharedEligibility.eligible)
        XCTAssertTrue(sharedEligibility.reasons.contains("local_provenance_required"))
        XCTAssertTrue(sharedEligibility.reasons.contains("shared_tag_allocation"))
    }

    func testOlderOrEqualSnapshotCannotReplaceNewerSnapshot() throws {
        let newer = makeSnapshot(
            pulledAt: "2026-04-23T10:00:00Z",
            tagID: "new-tag",
            tagName: "新しい状態",
            urlID: "new-url",
            normalizedURL: "https://example.com/new-state"
        )
        XCTAssertTrue(try store.applySnapshot(authUserID: "user-1", snapshot: newer))

        let older = makeSnapshot(
            pulledAt: "2026-04-23T09:00:00Z",
            tagID: "old-tag",
            tagName: "古い状態",
            urlID: "old-url",
            normalizedURL: "https://example.com/old-state"
        )
        XCTAssertFalse(try store.applySnapshot(authUserID: "user-1", snapshot: older))
        XCTAssertFalse(try store.applySnapshot(authUserID: "user-1", snapshot: newer))

        XCTAssertEqual(try store.loadVisibleTags(authUserID: "user-1").map(\.name), ["新しい状態"])
        XCTAssertNotNil(try repository.loadEntry(id: 1))
        XCTAssertEqual(try repository.loadEntry(id: 1)?.normalizedURL, "https://example.com/new-state")
        XCTAssertTrue(try repository.loadChatGptPersonalLinkSnapshot().allSatisfy { $0.normalizedURL != "https://example.com/old-state" })
    }

    private func makeSnapshot(
        pulledAt: String,
        tagID: String,
        tagName: String,
        urlID: String,
        normalizedURL: String
    ) -> PullSharedTagSnapshotResponse {
        PullSharedTagSnapshotResponse(
            pulledAt: pulledAt,
            normalizationVersion: 1,
            tags: [
                RemoteSharedTag(
                    id: tagID,
                    name: tagName,
                    createdAt: pulledAt,
                    updatedAt: pulledAt,
                    deletedAt: nil
                )
            ],
            members: [],
            urls: [
                RemoteSharedTagURL(
                    id: urlID,
                    tagID: tagID,
                    rawURL: normalizedURL,
                    normalizedURL: normalizedURL,
                    deletedAt: nil
                )
            ]
        )
    }
}
