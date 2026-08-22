import Foundation
import XCTest
@testable import URLSaveriOS

final class AiTransparencyTests: XCTestCase {
    private var repository: URLRepository!

    override func setUpWithError() throws {
        let directory = FileManager.default.temporaryDirectory
            .appendingPathComponent("URLSaverAiTransparencyTests-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        repository = try URLRepository(databaseURL: directory.appendingPathComponent("test.sqlite"))
    }

    override func tearDownWithError() throws {
        repository = nil
    }

    func testPreviewExcludesSharedArchivedAndPendingDeleteSources() throws {
        let active = try saveEntry("https://example.com/active")
        let shared = try saveEntry("https://example.com/shared")
        let archived = try saveEntry("https://example.com/archived")
        let pending = try saveEntry("https://example.com/pending")
        _ = try repository.archive(entryID: archived.id)
        _ = try repository.markPendingDelete(entryID: pending.id)

        let preview = AiTransparencyPolicy.buildPreview(
            actionKind: .mcpFetch,
            destination: "internal-preview",
            sources: [
                AiTransparencyPolicy.source(for: try XCTUnwrap(repository.loadEntry(id: active.id)), publicSafeID: "safe"),
                AiTransparencyPolicy.source(for: try XCTUnwrap(repository.loadEntry(id: shared.id)), publicSafeID: "shared", containsSharedTag: true),
                AiTransparencyPolicy.source(for: try XCTUnwrap(repository.loadEntry(id: archived.id)), publicSafeID: "archived"),
                AiTransparencyPolicy.source(for: try XCTUnwrap(repository.loadEntry(id: pending.id)), publicSafeID: "pending"),
            ]
        )

        XCTAssertEqual(preview.eligibleCount, 1)
        XCTAssertEqual(preview.blockedCount, 3)
        XCTAssertEqual(preview.sources.first { $0.publicSafeID == "shared" }?.exclusionReasons, ["shared_tag_default_excluded"])
        XCTAssertEqual(preview.sources.first { $0.publicSafeID == "archived" }?.exclusionReasons, ["archived_default_excluded"])
        XCTAssertEqual(preview.sources.first { $0.publicSafeID == "pending" }?.exclusionReasons, ["pending_delete_excluded"])
    }

    func testReceiptPersistsMetadataOnlyWithSizeBuckets() throws {
        let record = try saveEntry("https://example.com/safe")
        let preview = AiTransparencyPolicy.buildPreview(
            actionKind: .export,
            destination: "internal-preview",
            sources: [AiTransparencyPolicy.source(for: record, publicSafeID: "safe")]
        )

        let receipt = try repository.saveAiReceipt(
            preview: preview,
            requestBytes: 1_337,
            responseBytes: 2_000_000,
            generatedAt: Date(timeIntervalSince1970: 1_714_000_000)
        )
        let loaded = try XCTUnwrap(repository.loadAiReceipt(receiptID: receipt.receiptID))

        XCTAssertEqual(loaded.sentSourceIDs, ["safe"])
        XCTAssertEqual(loaded.requestSizeBucket, .small)
        XCTAssertEqual(loaded.responseSizeBucket, .huge)
        XCTAssertFalse(loaded.rawBodyIncluded)
        XCTAssertFalse(loaded.rawPromptIncluded)
    }

    func testDraftPersistsSeparatelyAndMockProviderIsDeterministic() throws {
        let record = try saveEntry("https://example.com/draft")
        let preview = AiTransparencyPolicy.buildPreview(
            actionKind: .export,
            destination: "internal-preview",
            sources: [AiTransparencyPolicy.source(for: record, publicSafeID: "safe")]
        )
        let receipt = try repository.saveAiReceipt(preview: preview, generatedAt: Date(timeIntervalSince1970: 1_714_000_000))

        let draft = try repository.generateAiDraftWithFallback(
            preview: preview,
            receipt: receipt,
            generatedAt: Date(timeIntervalSince1970: 1_714_000_001)
        )
        let loaded = try XCTUnwrap(repository.loadAiDraft(draftID: draft.draftID))

        XCTAssertEqual(loaded.receiptID, receipt.receiptID)
        XCTAssertEqual(loaded.citedSourceIDs, ["safe"])
        XCTAssertTrue(loaded.body.contains("deterministic-mock-provider"))
    }

    func testDiffDoesNotApplyWithoutConfirmationAndAppliesAllowedFieldsWithConfirmation() throws {
        let record = try saveEntry("https://example.com/apply")
        let publicSafeID = AiTransparencyPolicy.publicSafeID(for: record)
        let preview = AiTransparencyPolicy.buildPreview(
            actionKind: .export,
            destination: "internal-preview",
            sources: [AiTransparencyPolicy.source(for: record, publicSafeID: publicSafeID)]
        )
        let receipt = try repository.saveAiReceipt(preview: preview, generatedAt: Date(timeIntervalSince1970: 1_714_000_000))
        let draft = try repository.saveAiDraft(
            receipt: receipt,
            title: "候補",
            body: "本文",
            citedSourceIDs: [publicSafeID],
            generatedAt: Date(timeIntervalSince1970: 1_714_000_001)
        )
        let proposal = try repository.saveAiDiffProposal(
            draft: draft,
            operations: [
                AiDiffOperation(targetPublicSafeID: publicSafeID, field: "userTitle", before: nil, after: "新しいタイトル"),
                AiDiffOperation(targetPublicSafeID: publicSafeID, field: "memo", before: "", after: "新しいメモ"),
            ],
            generatedAt: Date(timeIntervalSince1970: 1_714_000_002)
        )

        XCTAssertFalse(try repository.applyAiDiffProposal(proposalID: proposal.proposalID, confirm: false))
        XCTAssertNil(try repository.loadEntry(id: record.id)?.userTitle)

        XCTAssertTrue(try repository.applyAiDiffProposal(proposalID: proposal.proposalID, confirm: true))
        let updated = try XCTUnwrap(repository.loadEntry(id: record.id))
        XCTAssertEqual(updated.userTitle, "新しいタイトル")
        XCTAssertEqual(updated.memo, "新しいメモ")
        XCTAssertEqual(updated.normalizedURL, "https://example.com/apply")
        XCTAssertEqual(try repository.loadAiDiffProposal(proposalID: proposal.proposalID)?.applied, true)
    }

    func testInvalidDiffDoesNotPartiallyApply() throws {
        let record = try saveEntry("https://example.com/invalid-diff")
        let publicSafeID = AiTransparencyPolicy.publicSafeID(for: record)
        let preview = AiTransparencyPolicy.buildPreview(
            actionKind: .export,
            destination: "internal-preview",
            sources: [AiTransparencyPolicy.source(for: record, publicSafeID: publicSafeID)]
        )
        let receipt = try repository.saveAiReceipt(preview: preview)
        let draft = try repository.saveAiDraft(
            receipt: receipt,
            title: "候補",
            body: "本文",
            citedSourceIDs: [publicSafeID]
        )
        let proposal = try repository.saveAiDiffProposal(
            draft: draft,
            operations: [
                AiDiffOperation(targetPublicSafeID: publicSafeID, field: "memo", before: "", after: "変更されない"),
                AiDiffOperation(targetPublicSafeID: publicSafeID, field: "normalizedURL", before: nil, after: "https://evil.example"),
            ]
        )

        XCTAssertFalse(try repository.applyAiDiffProposal(proposalID: proposal.proposalID, confirm: true))
        XCTAssertEqual(try repository.loadEntry(id: record.id)?.memo, "")
        XCTAssertEqual(try repository.loadAiDiffProposal(proposalID: proposal.proposalID)?.applied, false)
    }

    func testDiffRejectsStaleBeforeValue() throws {
        let record = try saveEntry("https://example.com/stale-before")
        let publicSafeID = AiTransparencyPolicy.publicSafeID(for: record)
        let preview = AiTransparencyPolicy.buildPreview(
            actionKind: .export,
            destination: "internal-preview",
            sources: [AiTransparencyPolicy.source(for: record, publicSafeID: publicSafeID)]
        )
        let receipt = try repository.saveAiReceipt(preview: preview)
        let draft = try repository.saveAiDraft(
            receipt: receipt,
            title: "候補",
            body: "本文",
            citedSourceIDs: [publicSafeID]
        )
        let proposal = try repository.saveAiDiffProposal(
            draft: draft,
            operations: [
                AiDiffOperation(targetPublicSafeID: publicSafeID, field: "userTitle", before: nil, after: "候補タイトル")
            ]
        )

        _ = try repository.saveUserTitle(entryID: record.id, rawTitle: "先に変更済み")

        XCTAssertFalse(try repository.applyAiDiffProposal(proposalID: proposal.proposalID, confirm: true))
        XCTAssertEqual(try repository.loadEntry(id: record.id)?.userTitle, "先に変更済み")
        XCTAssertEqual(try repository.loadAiDiffProposal(proposalID: proposal.proposalID)?.applied, false)
    }

    func testDiffRevalidatesAllEntriesAndDoesNotPartiallyApplyWhenOneBecomesIneligible() throws {
        let first = try saveEntry("https://example.com/revalidate-first")
        let second = try saveEntry("https://example.com/revalidate-second")
        let firstID = AiTransparencyPolicy.publicSafeID(for: first)
        let secondID = AiTransparencyPolicy.publicSafeID(for: second)
        let preview = AiTransparencyPolicy.buildPreview(
            actionKind: .export,
            destination: "internal-preview",
            sources: [
                AiTransparencyPolicy.source(for: first, publicSafeID: firstID),
                AiTransparencyPolicy.source(for: second, publicSafeID: secondID),
            ]
        )
        let receipt = try repository.saveAiReceipt(preview: preview)
        let draft = try repository.saveAiDraft(
            receipt: receipt,
            title: "候補",
            body: "本文",
            citedSourceIDs: [firstID, secondID]
        )
        let proposal = try repository.saveAiDiffProposal(
            draft: draft,
            operations: [
                AiDiffOperation(targetPublicSafeID: firstID, field: "memo", before: "", after: "先頭だけは更新しない"),
                AiDiffOperation(targetPublicSafeID: secondID, field: "memo", before: "", after: "pendingなら更新しない"),
            ]
        )

        XCTAssertNotNil(try repository.markPendingDelete(entryID: second.id))

        XCTAssertFalse(try repository.applyAiDiffProposal(proposalID: proposal.proposalID, confirm: true))
        XCTAssertEqual(try repository.loadEntry(id: first.id)?.memo, "")
        XCTAssertEqual(try repository.loadEntry(id: second.id)?.memo, "")
        XCTAssertEqual(try repository.loadAiDiffProposal(proposalID: proposal.proposalID)?.applied, false)
    }

    func testDiffRejectsOperationCountOverLimit() throws {
        let record = try saveEntry("https://example.com/too-many-operations")
        let publicSafeID = AiTransparencyPolicy.publicSafeID(for: record)
        let preview = AiTransparencyPolicy.buildPreview(
            actionKind: .export,
            destination: "internal-preview",
            sources: [AiTransparencyPolicy.source(for: record, publicSafeID: publicSafeID)]
        )
        let receipt = try repository.saveAiReceipt(preview: preview)
        let draft = try repository.saveAiDraft(
            receipt: receipt,
            title: "候補",
            body: "本文",
            citedSourceIDs: [publicSafeID]
        )
        let proposal = try repository.saveAiDiffProposal(
            draft: draft,
            operations: (0..<51).map { index in
                AiDiffOperation(
                    targetPublicSafeID: publicSafeID,
                    field: "memo",
                    before: "",
                    after: "変更\(index)"
                )
            }
        )

        XCTAssertFalse(try repository.applyAiDiffProposal(proposalID: proposal.proposalID, confirm: true))
        XCTAssertEqual(try repository.loadEntry(id: record.id)?.memo, "")
        XCTAssertEqual(try repository.loadAiDiffProposal(proposalID: proposal.proposalID)?.applied, false)
    }

    func testDiffRejectsOverlongAllowedField() throws {
        let record = try saveEntry("https://example.com/too-long")
        let publicSafeID = AiTransparencyPolicy.publicSafeID(for: record)
        let preview = AiTransparencyPolicy.buildPreview(
            actionKind: .export,
            destination: "internal-preview",
            sources: [AiTransparencyPolicy.source(for: record, publicSafeID: publicSafeID)]
        )
        let receipt = try repository.saveAiReceipt(preview: preview)
        let draft = try repository.saveAiDraft(
            receipt: receipt,
            title: "候補",
            body: "本文",
            citedSourceIDs: [publicSafeID]
        )
        let proposal = try repository.saveAiDiffProposal(
            draft: draft,
            operations: [
                AiDiffOperation(
                    targetPublicSafeID: publicSafeID,
                    field: "userTitle",
                    before: nil,
                    after: String(repeating: "長", count: 121)
                )
            ]
        )

        XCTAssertFalse(try repository.applyAiDiffProposal(proposalID: proposal.proposalID, confirm: true))
        XCTAssertNil(try repository.loadEntry(id: record.id)?.userTitle)
        XCTAssertEqual(try repository.loadAiDiffProposal(proposalID: proposal.proposalID)?.applied, false)
    }

    func testClearLocalAiDataRemovesReceiptDraftAndDiff() throws {
        let record = try saveEntry("https://example.com/delete")
        let preview = AiTransparencyPolicy.buildPreview(
            actionKind: .export,
            destination: "internal-preview",
            sources: [AiTransparencyPolicy.source(for: record, publicSafeID: "safe")]
        )
        let receipt = try repository.saveAiReceipt(preview: preview)
        let draft = try repository.saveAiDraft(receipt: receipt, title: "候補", body: "本文", citedSourceIDs: ["safe"])
        let proposal = try repository.saveAiDiffProposal(
            draft: draft,
            operations: [AiDiffOperation(targetPublicSafeID: "safe", field: "memo", before: "", after: "x")]
        )

        try repository.clearLocalAiData()

        XCTAssertNil(try repository.loadAiReceipt(receiptID: receipt.receiptID))
        XCTAssertNil(try repository.loadAiDraft(draftID: draft.draftID))
        XCTAssertNil(try repository.loadAiDiffProposal(proposalID: proposal.proposalID))
    }

    func testAccountDeletionSuccessClearsLocalAiData() async throws {
        URLProtocol.registerClass(AiTransparencyDeletionURLProtocol.self)
        defer { URLProtocol.unregisterClass(AiTransparencyDeletionURLProtocol.self) }
        AiTransparencyDeletionURLProtocol.resetDeleteAccountRequestCount()

        let fixture = try saveAiFixture(publicSafeID: "safe-delete-success")
        let cleanupStateStore = InMemorySharedTagAccountLocalCleanupStateStore()
        let (service, sessionStore) = try makeAccountDeletionService(
            host: "ai-delete-success.test",
            cleanupStateStore: cleanupStateStore
        )

        let result = await service.deleteAccount()
        XCTAssertEqual(result, .success)
        XCTAssertNil(try repository.loadAiReceipt(receiptID: fixture.receipt.receiptID))
        XCTAssertNil(try repository.loadAiDraft(draftID: fixture.draft.draftID))
        XCTAssertNil(try repository.loadAiDiffProposal(proposalID: fixture.proposal.proposalID))
        XCTAssertNil(try sessionStore.load())
        XCTAssertNil(cleanupStateStore.load())
        XCTAssertEqual(AiTransparencyDeletionURLProtocol.deleteAccountRequestCount, 1)
    }

    func testAccountDeletionFailureKeepsLocalAiData() async throws {
        URLProtocol.registerClass(AiTransparencyDeletionURLProtocol.self)
        defer { URLProtocol.unregisterClass(AiTransparencyDeletionURLProtocol.self) }
        AiTransparencyDeletionURLProtocol.resetDeleteAccountRequestCount()

        let fixture = try saveAiFixture(publicSafeID: "safe-delete-failure")
        let cleanupStateStore = InMemorySharedTagAccountLocalCleanupStateStore()
        let (service, sessionStore) = try makeAccountDeletionService(
            host: "ai-delete-failure.test",
            cleanupStateStore: cleanupStateStore
        )

        let result = await service.deleteAccount()
        guard case .failure = result else {
            XCTFail("remote削除失敗はlocal cleanupへ進まずfailureを返す必要があります: \(result)")
            return
        }
        XCTAssertNotNil(try repository.loadAiReceipt(receiptID: fixture.receipt.receiptID))
        XCTAssertNotNil(try repository.loadAiDraft(draftID: fixture.draft.draftID))
        XCTAssertNotNil(try repository.loadAiDiffProposal(proposalID: fixture.proposal.proposalID))
        XCTAssertNotNil(try sessionStore.load())
        XCTAssertNil(cleanupStateStore.load())
        XCTAssertEqual(AiTransparencyDeletionURLProtocol.deleteAccountRequestCount, 1)
    }

    func testAccountDeletionLocalCleanupRetryNeverRepeatsRemoteDelete() async throws {
        URLProtocol.registerClass(AiTransparencyDeletionURLProtocol.self)
        defer { URLProtocol.unregisterClass(AiTransparencyDeletionURLProtocol.self) }
        AiTransparencyDeletionURLProtocol.resetDeleteAccountRequestCount()

        let fixture = try saveAiFixture(publicSafeID: "safe-delete-local-retry")
        let clearer = FailOnceAiDataClearer(repository: repository)
        let cleanupStateStore = InMemorySharedTagAccountLocalCleanupStateStore()
        let (service, sessionStore) = try makeAccountDeletionService(
            host: "ai-delete-success.test",
            clearLocalAiData: {
                try clearer.clear()
            },
            cleanupStateStore: cleanupStateStore
        )

        let firstResult = await service.deleteAccount()

        XCTAssertEqual(
            firstResult,
            .localCleanupRequired(
                SharedTagAccountLocalCleanupState(
                    authUserID: "ai-test-user",
                    aiDataCleanupPending: true,
                    signOutCleanupPending: false
                )
            )
        )
        XCTAssertNotNil(try repository.loadAiReceipt(receiptID: fixture.receipt.receiptID))
        XCTAssertNotNil(try repository.loadAiDraft(draftID: fixture.draft.draftID))
        XCTAssertNotNil(try repository.loadAiDiffProposal(proposalID: fixture.proposal.proposalID))
        XCTAssertNil(try sessionStore.load())
        XCTAssertEqual(
            cleanupStateStore.load(),
            SharedTagAccountLocalCleanupState(
                authUserID: "ai-test-user",
                aiDataCleanupPending: true,
                signOutCleanupPending: false
            )
        )
        XCTAssertEqual(AiTransparencyDeletionURLProtocol.deleteAccountRequestCount, 1)

        let regeneratedService = SharedTagCloudService(
            config: SharedTagCloudConfig(
                enabled: true,
                supabaseURL: "https://ai-delete-success.test",
                anonKey: "ai-test-anon-key"
            ),
            sessionStore: sessionStore,
            store: try SharedTagStore(database: repository.database),
            repository: repository,
            clearLocalAiData: {
                try clearer.clear()
            },
            cleanupStateStore: cleanupStateStore
        )
        XCTAssertEqual(
            regeneratedService.localAccountCleanupState,
            SharedTagAccountLocalCleanupState(
                authUserID: "ai-test-user",
                aiDataCleanupPending: true,
                signOutCleanupPending: false
            )
        )

        let retryResult = await regeneratedService.retryLocalAccountCleanup()

        XCTAssertEqual(retryResult, .success)
        XCTAssertNil(try repository.loadAiReceipt(receiptID: fixture.receipt.receiptID))
        XCTAssertNil(try repository.loadAiDraft(draftID: fixture.draft.draftID))
        XCTAssertNil(try repository.loadAiDiffProposal(proposalID: fixture.proposal.proposalID))
        XCTAssertNil(try sessionStore.load())
        XCTAssertNil(cleanupStateStore.load())
        XCTAssertEqual(AiTransparencyDeletionURLProtocol.deleteAccountRequestCount, 1)
    }

    func testAccountDeletionRequestProtocolPersistsRecordAndPassesServerIssuedRequestID() async throws {
        URLProtocol.registerClass(AiTransparencyDeletionURLProtocol.self)
        defer { URLProtocol.unregisterClass(AiTransparencyDeletionURLProtocol.self) }
        AiTransparencyDeletionURLProtocol.resetDeleteAccountRequestCount()

        let cleanupStateStore = InMemorySharedTagAccountLocalCleanupStateStore()
        let requestStore = InMemorySharedTagAccountDeletionRequestStore()
        let (service, sessionStore) = try makeAccountDeletionService(
            host: "ai-delete-success.test",
            clearSharedTagData: { _ in
                struct ForcedStageFailure: Error {}
                throw ForcedStageFailure()
            },
            cleanupStateStore: cleanupStateStore,
            deletionRequestStore: requestStore
        )

        let result = await service.deleteAccount()

        guard case .localCleanupRequired = result else {
            XCTFail("stage failure must retain localCleanupRequired: \(result)")
            return
        }
        XCTAssertEqual(AiTransparencyDeletionURLProtocol.createDeletionRequestCount, 1)
        XCTAssertEqual(AiTransparencyDeletionURLProtocol.deleteAccountRequestCount, 1)
        let body = try XCTUnwrap(AiTransparencyDeletionURLProtocol.lastDeleteRequestBody)
        let json = try XCTUnwrap(JSONSerialization.jsonObject(with: body) as? [String: Any])
        XCTAssertEqual(json["p_request_id"] as? String, "test-request-id")
        let storedRecord = try XCTUnwrap(requestStore.load())
        XCTAssertEqual(storedRecord.requestID, "test-request-id")
        XCTAssertEqual(storedRecord.token, "test-status-token")
        XCTAssertEqual(storedRecord.authUserID, "ai-test-user")
        // Cleanup already signed the committed-deletion account out; only the
        // failed shared-data stage keeps its durable marker and request record.
        XCTAssertNil(try sessionStore.load())
    }

    func testAccountDeletionCrashWindowWithoutSessionConvergesViaCompletedStatus() async throws {
        URLProtocol.registerClass(AiTransparencyDeletionURLProtocol.self)
        defer { URLProtocol.unregisterClass(AiTransparencyDeletionURLProtocol.self) }
        AiTransparencyDeletionURLProtocol.resetDeleteAccountRequestCount()

        let requestStore = InMemorySharedTagAccountDeletionRequestStore()
        try requestStore.save(
            SharedTagAccountDeletionRequestRecord(
                authUserID: "ai-test-user",
                requestID: "orphan-rid",
                token: "orphan-token"
            )
        )
        AiTransparencyDeletionURLProtocol.setStatusResponseOverride(
            #"{"status":"completed","user_id":"ai-test-user"}"#
        )
        let clearedUsers = LockedUserCollector()
        let (service, sessionStore) = try makeAccountDeletionService(
            host: "ai-delete-success.test",
            clearSharedTagData: { userID in
                clearedUsers.append(userID)
            },
            deletionRequestStore: requestStore,
            seedSession: false
        )

        let result = await service.deleteAccount()

        XCTAssertEqual(result, .success)
        XCTAssertEqual(AiTransparencyDeletionURLProtocol.deleteAccountRequestCount, 0)
        XCTAssertEqual(AiTransparencyDeletionURLProtocol.createDeletionRequestCount, 0)
        XCTAssertEqual(AiTransparencyDeletionURLProtocol.statusQueryCount, 1)
        XCTAssertEqual(clearedUsers.values, ["ai-test-user"])
        XCTAssertNil(try sessionStore.load())
        XCTAssertNil(requestStore.load())
    }

    func testAccountDeletionPendingStatusWithoutSessionStallsWithoutRepeatingRemote() async throws {
        URLProtocol.registerClass(AiTransparencyDeletionURLProtocol.self)
        defer { URLProtocol.unregisterClass(AiTransparencyDeletionURLProtocol.self) }
        AiTransparencyDeletionURLProtocol.resetDeleteAccountRequestCount()

        let requestStore = InMemorySharedTagAccountDeletionRequestStore()
        try requestStore.save(
            SharedTagAccountDeletionRequestRecord(
                authUserID: "ai-test-user",
                requestID: "pending-rid",
                token: "pending-token"
            )
        )
        AiTransparencyDeletionURLProtocol.setStatusResponseOverride(#"{"status":"pending"}"#)
        let (service, _) = try makeAccountDeletionService(
            host: "ai-delete-success.test",
            deletionRequestStore: requestStore,
            seedSession: false
        )

        let result = await service.deleteAccount()

        guard case .failure = result else {
            XCTFail("pending status without a session must stall fail-closed: \(result)")
            return
        }
        XCTAssertEqual(AiTransparencyDeletionURLProtocol.deleteAccountRequestCount, 0)
        XCTAssertEqual(AiTransparencyDeletionURLProtocol.statusQueryCount, 1)
        XCTAssertEqual(requestStore.load()?.requestID, "pending-rid")
    }

    func testAccountDeletionLostResponseWithLiveSessionReplaysSameRequestOnce() async throws {
        URLProtocol.registerClass(AiTransparencyDeletionURLProtocol.self)
        defer { URLProtocol.unregisterClass(AiTransparencyDeletionURLProtocol.self) }
        AiTransparencyDeletionURLProtocol.resetDeleteAccountRequestCount()

        let requestStore = InMemorySharedTagAccountDeletionRequestStore()
        try requestStore.save(
            SharedTagAccountDeletionRequestRecord(
                authUserID: "ai-test-user",
                requestID: "replay-rid",
                token: "replay-token"
            )
        )
        let (service, _) = try makeAccountDeletionService(
            host: "ai-delete-success.test",
            deletionRequestStore: requestStore
        )

        let result = await service.deleteAccount()

        XCTAssertEqual(result, .success)
        XCTAssertEqual(AiTransparencyDeletionURLProtocol.deleteAccountRequestCount, 1)
        XCTAssertEqual(AiTransparencyDeletionURLProtocol.createDeletionRequestCount, 0)
        XCTAssertEqual(AiTransparencyDeletionURLProtocol.statusQueryCount, 0)
        let body = try XCTUnwrap(AiTransparencyDeletionURLProtocol.lastDeleteRequestBody)
        let json = try XCTUnwrap(JSONSerialization.jsonObject(with: body) as? [String: Any])
        XCTAssertEqual(json["p_request_id"] as? String, "replay-rid")
        XCTAssertNil(requestStore.load())
    }

    func testAccountDeletionOwnerTransferRequiredKeepsRequestForRetry() async throws {
        URLProtocol.registerClass(AiTransparencyDeletionURLProtocol.self)
        defer { URLProtocol.unregisterClass(AiTransparencyDeletionURLProtocol.self) }
        AiTransparencyDeletionURLProtocol.resetDeleteAccountRequestCount()
        AiTransparencyDeletionURLProtocol.setFailDeleteWithOwnerTransfer(true)

        let requestStore = InMemorySharedTagAccountDeletionRequestStore()
        let (service, sessionStore) = try makeAccountDeletionService(
            host: "ai-delete-success.test",
            deletionRequestStore: requestStore
        )

        let result = await service.deleteAccount()

        XCTAssertEqual(result, .ownerTransferRequired)
        XCTAssertEqual(AiTransparencyDeletionURLProtocol.createDeletionRequestCount, 1)
        XCTAssertEqual(requestStore.load()?.authUserID, "ai-test-user")
        XCTAssertNotNil(try sessionStore.load())
    }

    func testAccountDeletionTracksEachAccountLinkedCleanupFailureAndRetriesLocally() async throws {
        URLProtocol.registerClass(AiTransparencyDeletionURLProtocol.self)
        defer { URLProtocol.unregisterClass(AiTransparencyDeletionURLProtocol.self) }
        AiTransparencyDeletionURLProtocol.resetDeleteAccountRequestCount()
        let sharedDataCleanup = FailOnceAccountCleanupAction()
        let entitlementCleanup = FailOnceAccountCleanupAction()
        let personalLinkSettingsCleanup = FailOnceAccountCleanupAction()
        let cleanupStateStore = InMemorySharedTagAccountLocalCleanupStateStore()
        let (service, _) = try makeAccountDeletionService(
            host: "ai-delete-success.test",
            clearSharedTagData: { _ in try sharedDataCleanup.run() },
            clearEntitlementCache: { _ in try entitlementCleanup.run() },
            clearPersonalLinkSettings: { _ in try personalLinkSettingsCleanup.run() },
            cleanupStateStore: cleanupStateStore
        )

        let firstResult = await service.deleteAccount()

        XCTAssertEqual(
            firstResult,
            .localCleanupRequired(
                SharedTagAccountLocalCleanupState(
                    authUserID: "ai-test-user",
                    aiDataCleanupPending: false,
                    signOutCleanupPending: false,
                    syncCancellationCleanupPending: false,
                    sharedDataCleanupPending: true,
                    pendingInviteCleanupPending: false,
                    entitlementCleanupPending: true,
                    personalLinkSettingsCleanupPending: true
                )
            )
        )
        XCTAssertEqual(sharedDataCleanup.callCount, 1)
        XCTAssertEqual(entitlementCleanup.callCount, 1)
        XCTAssertEqual(personalLinkSettingsCleanup.callCount, 1)

        let retryResult = await service.retryLocalAccountCleanup()

        XCTAssertEqual(retryResult, .success)
        XCTAssertNil(cleanupStateStore.load())
        XCTAssertEqual(sharedDataCleanup.callCount, 2)
        XCTAssertEqual(entitlementCleanup.callCount, 2)
        XCTAssertEqual(personalLinkSettingsCleanup.callCount, 2)
        XCTAssertEqual(AiTransparencyDeletionURLProtocol.deleteAccountRequestCount, 1)
    }

    func testAccountDeletionSyncCancellationFailureBlocksDataCleanupUntilRetry() async throws {
        URLProtocol.registerClass(AiTransparencyDeletionURLProtocol.self)
        defer { URLProtocol.unregisterClass(AiTransparencyDeletionURLProtocol.self) }
        AiTransparencyDeletionURLProtocol.resetDeleteAccountRequestCount()
        let cancellation = FailOnceAccountCleanupAction()
        let sharedDataCleanup = CountingAccountCleanupAction()
        let cleanupStateStore = InMemorySharedTagAccountLocalCleanupStateStore()
        let (service, _) = try makeAccountDeletionService(
            host: "ai-delete-success.test",
            clearSharedTagData: { _ in sharedDataCleanup.run() },
            cancelInFlightSync: { try cancellation.run() },
            cleanupStateStore: cleanupStateStore
        )

        let firstResult = await service.deleteAccount()

        guard case .localCleanupRequired(let firstState) = firstResult else {
            XCTFail("sync cancellation failure must remain retryable: \(firstResult)")
            return
        }
        XCTAssertTrue(firstState.syncCancellationCleanupPending)
        XCTAssertTrue(firstState.sharedDataCleanupPending)
        XCTAssertEqual(sharedDataCleanup.callCount, 0)
        XCTAssertEqual(cancellation.callCount, 1)

        let retryResult = await service.retryLocalAccountCleanup()
        XCTAssertEqual(retryResult, .success)
        XCTAssertEqual(cancellation.callCount, 2)
        XCTAssertEqual(sharedDataCleanup.callCount, 1)
        XCTAssertEqual(AiTransparencyDeletionURLProtocol.deleteAccountRequestCount, 1)
    }

    func testAccountDeletionSessionFailureDoesNotStartAccountDataCleanup() async throws {
        URLProtocol.registerClass(AiTransparencyDeletionURLProtocol.self)
        defer { URLProtocol.unregisterClass(AiTransparencyDeletionURLProtocol.self) }
        AiTransparencyDeletionURLProtocol.resetDeleteAccountRequestCount()
        let authStorage = FailOnceClearAuthStorage()
        let cancellation = CountingAccountCleanupAction()
        let sharedDataCleanup = CountingAccountCleanupAction()
        let cleanupStateStore = InMemorySharedTagAccountLocalCleanupStateStore()
        let (service, sessionStore) = try makeAccountDeletionService(
            host: "ai-delete-success.test",
            authStorage: authStorage,
            clearSharedTagData: { _ in sharedDataCleanup.run() },
            cancelInFlightSync: { cancellation.run() },
            cleanupStateStore: cleanupStateStore
        )

        let firstResult = await service.deleteAccount()

        guard case .localCleanupRequired(let firstState) = firstResult else {
            XCTFail("session cleanup failure must remain retryable: \(firstResult)")
            return
        }
        XCTAssertTrue(firstState.signOutCleanupPending)
        XCTAssertTrue(firstState.syncCancellationCleanupPending)
        XCTAssertTrue(firstState.sharedDataCleanupPending)
        XCTAssertNotNil(try sessionStore.load())
        XCTAssertEqual(cancellation.callCount, 0)
        XCTAssertEqual(sharedDataCleanup.callCount, 0)

        let retryResult = await service.retryLocalAccountCleanup()
        XCTAssertEqual(retryResult, .success)
        XCTAssertNil(try sessionStore.load())
        XCTAssertEqual(cancellation.callCount, 1)
        XCTAssertEqual(sharedDataCleanup.callCount, 1)
        XCTAssertEqual(AiTransparencyDeletionURLProtocol.deleteAccountRequestCount, 1)
    }

    func testAccountDeletionCleanupMarkerPersistsAllStagesAndAuthUserID() {
        let suiteName = "AccountDeletionCleanupMarker-\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suiteName)!
        defer { defaults.removePersistentDomain(forName: suiteName) }
        let keyPrefix = "account.cleanup.\(UUID().uuidString)"
        let marker = SharedTagAccountLocalCleanupState(
            authUserID: "deleted-user",
            aiDataCleanupPending: true,
            signOutCleanupPending: true,
            syncCancellationCleanupPending: true,
            sharedDataCleanupPending: true,
            pendingInviteCleanupPending: true,
            entitlementCleanupPending: true,
            personalLinkSettingsCleanupPending: true
        )
        UserDefaultsSharedTagAccountLocalCleanupStateStore(
            userDefaults: defaults,
            keyPrefix: keyPrefix
        ).save(marker)

        let recreated = UserDefaultsSharedTagAccountLocalCleanupStateStore(
            userDefaults: defaults,
            keyPrefix: keyPrefix
        )
        var expectedMarker = marker
        expectedMarker.pendingInviteCleanupPending = false
        XCTAssertEqual(recreated.load(), expectedMarker)

        recreated.clear()
        XCTAssertNil(recreated.load())
    }

    func testAccountDeletionCleanupMarkerMigratesTornLegacyKeysFailClosedIntoOneBlob() throws {
        let suiteName = "AccountDeletionLegacyMarker-\(UUID().uuidString)"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suiteName))
        defer { defaults.removePersistentDomain(forName: suiteName) }
        let keyPrefix = "account.cleanup.\(UUID().uuidString)"
        // Simulate process loss before the old leading marker key was written.
        defaults.set("legacy-user", forKey: "\(keyPrefix).auth_user_id")
        defaults.set(false, forKey: "\(keyPrefix).ai_data_pending")
        defaults.set(true, forKey: "\(keyPrefix).pending_invite_pending")

        let store = UserDefaultsSharedTagAccountLocalCleanupStateStore(
            userDefaults: defaults,
            keyPrefix: keyPrefix
        )
        let migrated = try XCTUnwrap(store.load())

        XCTAssertEqual(migrated.authUserID, "legacy-user")
        XCTAssertFalse(migrated.aiDataCleanupPending)
        XCTAssertTrue(migrated.signOutCleanupPending)
        XCTAssertTrue(migrated.syncCancellationCleanupPending)
        XCTAssertTrue(migrated.sharedDataCleanupPending)
        XCTAssertFalse(migrated.pendingInviteCleanupPending)
        XCTAssertTrue(migrated.entitlementCleanupPending)
        XCTAssertTrue(migrated.personalLinkSettingsCleanupPending)
        XCTAssertNotNil(defaults.data(forKey: "\(keyPrefix).state.v2"))
        XCTAssertNil(defaults.object(forKey: "\(keyPrefix).pending"))
        XCTAssertEqual(
            UserDefaultsSharedTagAccountLocalCleanupStateStore(
                userDefaults: defaults,
                keyPrefix: keyPrefix
            ).load(),
            migrated
        )
    }

    func testAccountDeletionCleanupMarkerCorruptBlobRemainsFailClosedAcrossRecreation() throws {
        let suiteName = "AccountDeletionCorruptMarker-\(UUID().uuidString)"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suiteName))
        defer { defaults.removePersistentDomain(forName: suiteName) }
        let keyPrefix = "account.cleanup.\(UUID().uuidString)"
        defaults.set(Data([0x00]), forKey: "\(keyPrefix).state.v2")

        let first = UserDefaultsSharedTagAccountLocalCleanupStateStore(
            userDefaults: defaults,
            keyPrefix: keyPrefix
        )
        let state = try XCTUnwrap(first.load())

        XCTAssertNil(state.authUserID)
        XCTAssertTrue(state.aiDataCleanupPending)
        XCTAssertTrue(state.signOutCleanupPending)
        XCTAssertTrue(state.syncCancellationCleanupPending)
        XCTAssertTrue(state.sharedDataCleanupPending)
        XCTAssertFalse(state.pendingInviteCleanupPending)
        XCTAssertTrue(state.entitlementCleanupPending)
        XCTAssertTrue(state.personalLinkSettingsCleanupPending)
        let regeneratedBlob = try XCTUnwrap(defaults.data(forKey: "\(keyPrefix).state.v2"))
        XCTAssertEqual(
            try JSONDecoder().decode(SharedTagAccountLocalCleanupState.self, from: regeneratedBlob),
            state
        )
        XCTAssertEqual(
            UserDefaultsSharedTagAccountLocalCleanupStateStore(
                userDefaults: defaults,
                keyPrefix: keyPrefix
            ).load(),
            state
        )
    }

    func testAccountDeletionCleanupMarkerConcurrentSavesNeverProduceTornState() throws {
        let suiteName = "AccountDeletionConcurrentMarker-\(UUID().uuidString)"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suiteName))
        defer { defaults.removePersistentDomain(forName: suiteName) }
        let keyPrefix = "account.cleanup.\(UUID().uuidString)"
        let firstStore = UserDefaultsSharedTagAccountLocalCleanupStateStore(
            userDefaults: defaults,
            keyPrefix: keyPrefix
        )
        let secondStore = UserDefaultsSharedTagAccountLocalCleanupStateStore(
            userDefaults: defaults,
            keyPrefix: keyPrefix
        )
        let first = SharedTagAccountLocalCleanupState(
            authUserID: "user-a",
            aiDataCleanupPending: true,
            signOutCleanupPending: false,
            sharedDataCleanupPending: true
        )
        let second = SharedTagAccountLocalCleanupState(
            authUserID: "user-b",
            aiDataCleanupPending: false,
            signOutCleanupPending: true,
            entitlementCleanupPending: true,
            personalLinkSettingsCleanupPending: true
        )

        DispatchQueue.concurrentPerform(iterations: 100) { index in
            if index.isMultiple(of: 2) {
                firstStore.save(first)
            } else {
                secondStore.save(second)
            }
        }

        let loaded = try XCTUnwrap(
            UserDefaultsSharedTagAccountLocalCleanupStateStore(
                userDefaults: defaults,
                keyPrefix: keyPrefix
            ).load()
        )
        XCTAssertTrue(loaded == first || loaded == second)
    }

    func testPersonalLinkSettingsAreAccountScopedLegacyOwnerlessFailsClosedAndDeletionIsTargeted() throws {
        let suiteName = "PersonalLinkSettingsScope-\(UUID().uuidString)"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suiteName))
        defer { defaults.removePersistentDomain(forName: suiteName) }
        defaults.set(true, forKey: "chatgpt_personal_link_sync.enabled")
        let store = UserDefaultsChatGptPersonalLinkSettingsStore(
            userDefaults: defaults,
            keyPrefix: "personal-link.\(UUID().uuidString)"
        )

        XCTAssertEqual(store.snapshot(authUserID: nil), ChatGptPersonalLinkLocalSettings())
        XCTAssertEqual(store.snapshot(authUserID: "legacy-new-user"), ChatGptPersonalLinkLocalSettings())
        store.setEnabled(authUserID: "user-a", enabled: true)
        store.markSyncSuccess(authUserID: "user-a", at: Date(timeIntervalSince1970: 123))
        XCTAssertTrue(store.snapshot(authUserID: "user-a").enabled)
        XCTAssertFalse(store.snapshot(authUserID: "user-b").enabled)

        store.setEnabled(authUserID: "user-b", enabled: true)
        store.clear(authUserID: "user-a")

        XCTAssertEqual(store.snapshot(authUserID: "user-a"), ChatGptPersonalLinkLocalSettings())
        XCTAssertTrue(store.snapshot(authUserID: "user-b").enabled)
    }

    func testPersonalLinkSettingsFollowSessionScopeSurviveSignOutAndDeleteOnlyTargetAccount() async throws {
        URLProtocol.registerClass(AiTransparencyDeletionURLProtocol.self)
        defer { URLProtocol.unregisterClass(AiTransparencyDeletionURLProtocol.self) }
        AiTransparencyDeletionURLProtocol.resetDeleteAccountRequestCount()
        let suiteName = "PersonalLinkSettingsLifecycle-\(UUID().uuidString)"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suiteName))
        defer { defaults.removePersistentDomain(forName: suiteName) }
        let settingsStore = UserDefaultsChatGptPersonalLinkSettingsStore(
            userDefaults: defaults,
            keyPrefix: "personal-link.\(UUID().uuidString)"
        )
        let savedURL = try saveEntry("https://example.com/personal-link-account-scope")
        let (service, sessionStore) = try makeAccountDeletionService(
            host: "ai-delete-success.test",
            personalLinkSettingsStore: settingsStore
        )
        settingsStore.setEnabled(authUserID: "ai-test-user", enabled: true)

        try await service.signOut()
        try sessionStore.save(
            SharedTagAuthSession(
                authUserID: "user-b",
                accessToken: "user-b-token",
                refreshToken: nil,
                userEmail: "user-b@example.com"
            )
        )
        XCTAssertFalse(service.chatGptPersonalLinkLocalSettings().enabled)
        settingsStore.setEnabled(authUserID: "user-b", enabled: true)
        try await service.signOut()
        try sessionStore.save(
            SharedTagAuthSession(
                authUserID: "ai-test-user",
                accessToken: "ai-test-access-token",
                refreshToken: nil,
                userEmail: "ai-test@example.com"
            )
        )
        XCTAssertTrue(service.chatGptPersonalLinkLocalSettings().enabled)

        let deletionResult = await service.deleteAccount()

        XCTAssertEqual(deletionResult, .success)
        XCTAssertEqual(settingsStore.snapshot(authUserID: "ai-test-user"), ChatGptPersonalLinkLocalSettings())
        XCTAssertTrue(settingsStore.snapshot(authUserID: "user-b").enabled)
        XCTAssertNotNil(try repository.loadEntry(id: savedURL.id))
        XCTAssertEqual(AiTransparencyDeletionURLProtocol.deleteAccountRequestCount, 1)
    }

    func testAccountOperationGateWaitsForStartedWorkThenBlocksDeletedAccountWork() async throws {
        let cleanupStore = InMemorySharedTagAccountLocalCleanupStateStore()
        let gate = SharedTagAccountOperationGate(cleanupStateStore: cleanupStore)
        let operationStarted = AsyncTestLatch()
        let allowOperationToFinish = AsyncTestLatch()
        let deletionEntered = AsyncTestLatch()

        let operation = Task {
            await gate.withAccountOperation(authUserID: "user-a", blocked: "blocked") {
                await operationStarted.signal()
                await allowOperationToFinish.wait()
                return "completed"
            }
        }
        await operationStarted.wait()
        let deletion = Task {
            await gate.withExclusiveOperation {
                await deletionEntered.signal()
                gate.markRemoteAccountDeleted(authUserID: "user-a")
                return "deleted"
            }
        }
        try await Task.sleep(for: .milliseconds(50))
        let didEnterDeletionEarly = await deletionEntered.isSignaled
        XCTAssertFalse(didEnterDeletionEarly)

        await allowOperationToFinish.signal()
        let operationResult = await operation.value
        let deletionResult = await deletion.value
        XCTAssertEqual(operationResult, "completed")
        XCTAssertEqual(deletionResult, "deleted")
        let lateResult = await gate.withAccountOperation(
            authUserID: "user-a",
            blocked: "blocked",
            operation: { "unexpected" }
        )
        XCTAssertEqual(lateResult, "blocked")
    }

    func testAccountDeletionPreservesUnboundPendingInviteIntent() async throws {
        URLProtocol.registerClass(AiTransparencyDeletionURLProtocol.self)
        defer { URLProtocol.unregisterClass(AiTransparencyDeletionURLProtocol.self) }
        AiTransparencyDeletionURLProtocol.resetDeleteAccountRequestCount()
        let pendingInviteStore = PendingInviteStore(storage: AiTransparencyPendingInviteStorage())
        try pendingInviteStore.save(inviteToken: "unbound-invite", now: Date(timeIntervalSince1970: 100))
        let (service, _) = try makeAccountDeletionService(host: "ai-delete-success.test")

        let deletionResult = await service.deleteAccount()
        XCTAssertEqual(deletionResult, .success)

        XCTAssertEqual(try pendingInviteStore.load()?.inviteToken, "unbound-invite")
        XCTAssertEqual(AiTransparencyDeletionURLProtocol.deleteAccountRequestCount, 1)
    }

    func testFeatureFlagDefaultsOffForNormalUi() {
        XCTAssertFalse(AiTransparencyFeature.isEnabled)
    }

    private func saveAiFixture(publicSafeID: String) throws -> (
        receipt: AiSendReceipt,
        draft: AiDraft,
        proposal: AiDiffProposal
    ) {
        let record = try saveEntry("https://example.com/\(publicSafeID)")
        let preview = AiTransparencyPolicy.buildPreview(
            actionKind: .export,
            destination: "internal-preview",
            sources: [AiTransparencyPolicy.source(for: record, publicSafeID: publicSafeID)]
        )
        let receipt = try repository.saveAiReceipt(preview: preview)
        let draft = try repository.saveAiDraft(
            receipt: receipt,
            title: "候補",
            body: "本文",
            citedSourceIDs: [publicSafeID]
        )
        let proposal = try repository.saveAiDiffProposal(
            draft: draft,
            operations: [
                AiDiffOperation(targetPublicSafeID: publicSafeID, field: "memo", before: "", after: "変更")
            ]
        )
        return (receipt, draft, proposal)
    }

    private func makeAccountDeletionService(host: String) throws -> (
        service: SharedTagCloudService,
        sessionStore: SharedTagAuthSessionStore
    ) {
        try makeAccountDeletionService(host: host, clearLocalAiData: nil)
    }

    private func makeAccountDeletionService(
        host: String,
        clearLocalAiData: (@Sendable () throws -> Void)? = nil,
        authStorage: (any SharedTagAuthSecureStorage)? = nil,
        clearSharedTagData: @escaping @Sendable (String) throws -> Void = { _ in },
        clearEntitlementCache: @escaping @Sendable (String) throws -> Void = { _ in },
        clearPersonalLinkSettings: (@Sendable (String) throws -> Void)? = nil,
        cancelInFlightSync: @escaping @Sendable () async throws -> Void = {},
        cleanupStateStore: (any SharedTagAccountLocalCleanupStateStore)? = nil,
        deletionRequestStore: (any SharedTagAccountDeletionRequestStore)? = nil,
        personalLinkSettingsStore: (any ChatGptPersonalLinkSettingsStore)? = nil,
        seedSession: Bool = true
    ) throws -> (
        service: SharedTagCloudService,
        sessionStore: SharedTagAuthSessionStore
    ) {
        let sessionStore = SharedTagAuthSessionStore(storage: authStorage ?? AiTransparencyAuthStorage())
        if seedSession {
            try sessionStore.save(
                SharedTagAuthSession(
                    authUserID: "ai-test-user",
                    accessToken: "ai-test-access-token",
                    refreshToken: nil,
                    userEmail: "ai-test@example.com"
                )
            )
        }
        let store = try SharedTagStore(database: repository.database)
        let resolvedDeletionRequestStore = deletionRequestStore ?? InMemorySharedTagAccountDeletionRequestStore()
        let service = SharedTagCloudService(
            config: SharedTagCloudConfig(
                enabled: true,
                supabaseURL: "https://\(host)",
                anonKey: "ai-test-anon-key"
            ),
            sessionStore: sessionStore,
            store: store,
            repository: repository,
            clearLocalAiData: clearLocalAiData,
            clearSharedTagData: clearSharedTagData,
            clearEntitlementCache: clearEntitlementCache,
            clearPersonalLinkSettings: clearPersonalLinkSettings,
            cancelInFlightSync: cancelInFlightSync,
            cleanupStateStore: cleanupStateStore,
            deletionRequestStore: resolvedDeletionRequestStore,
            personalLinkSettingsStore: personalLinkSettingsStore
        )
        return (service, sessionStore)
    }

    private func saveEntry(_ url: String) throws -> URLRecord {
        let result = try repository.saveFromManualInput(url)
        return try XCTUnwrap(repository.loadEntry(id: try XCTUnwrap(result.entryID)))
    }
}

private final class AiTransparencyAuthStorage: SharedTagAuthSecureStorage, @unchecked Sendable {
    private let lock = NSLock()
    private var payload: Data?

    func load() throws -> Data? {
        lock.lock()
        defer { lock.unlock() }
        return payload
    }

    func save(_ data: Data) throws {
        lock.lock()
        payload = data
        lock.unlock()
    }

    func clear() throws {
        lock.lock()
        payload = nil
        lock.unlock()
    }
}

private actor AsyncTestLatch {
    private(set) var isSignaled = false
    private var waiters: [CheckedContinuation<Void, Never>] = []

    func wait() async {
        if isSignaled { return }
        await withCheckedContinuation { continuation in
            waiters.append(continuation)
        }
    }

    func signal() {
        guard !isSignaled else { return }
        isSignaled = true
        let pendingWaiters = waiters
        waiters.removeAll()
        pendingWaiters.forEach { $0.resume() }
    }
}

private final class AiTransparencyPendingInviteStorage: PendingInviteSecureStorage, @unchecked Sendable {
    private let lock = NSLock()
    private var payload: Data?

    func load() throws -> Data? {
        lock.lock()
        defer { lock.unlock() }
        return payload
    }

    func save(_ data: Data) throws {
        lock.lock()
        payload = data
        lock.unlock()
    }

    func clear() throws {
        lock.lock()
        payload = nil
        lock.unlock()
    }
}

private final class InMemorySharedTagAccountLocalCleanupStateStore: @unchecked Sendable, SharedTagAccountLocalCleanupStateStore {
    private let lock = NSLock()
    private var state: SharedTagAccountLocalCleanupState?

    func load() -> SharedTagAccountLocalCleanupState? {
        lock.lock()
        defer { lock.unlock() }
        return state
    }

    func save(_ state: SharedTagAccountLocalCleanupState) {
        lock.lock()
        self.state = state
        lock.unlock()
    }

    func clear() {
        lock.lock()
        state = nil
        lock.unlock()
    }
}

private final class InMemorySharedTagAccountDeletionRequestStore: @unchecked Sendable, SharedTagAccountDeletionRequestStore {
    private let lock = NSLock()
    private var record: SharedTagAccountDeletionRequestRecord?

    func load() -> SharedTagAccountDeletionRequestRecord? {
        lock.lock()
        defer { lock.unlock() }
        return record
    }

    func save(_ record: SharedTagAccountDeletionRequestRecord) throws {
        lock.lock()
        self.record = record
        lock.unlock()
    }

    func clear() throws {
        lock.lock()
        self.record = nil
        lock.unlock()
    }
}

private final class AiTransparencyDeletionURLProtocol: URLProtocol, @unchecked Sendable {
    private static let requestCountLock = NSLock()
    nonisolated(unsafe) private static var storedDeleteAccountRequestCount = 0
    nonisolated(unsafe) private static var storedCreateDeletionRequestCount = 0
    nonisolated(unsafe) private static var storedStatusQueryCount = 0
    nonisolated(unsafe) private static var storedLastDeleteRequestBody: Data?
    nonisolated(unsafe) private static var storedStatusResponseOverride: String?
    nonisolated(unsafe) private static var storedFailDeleteWithOwnerTransfer = false

    static func setFailDeleteWithOwnerTransfer(_ value: Bool) {
        requestCountLock.lock()
        storedFailDeleteWithOwnerTransfer = value
        requestCountLock.unlock()
    }

    static var deleteAccountRequestCount: Int {
        requestCountLock.lock()
        defer { requestCountLock.unlock() }
        return storedDeleteAccountRequestCount
    }

    static var createDeletionRequestCount: Int {
        requestCountLock.lock()
        defer { requestCountLock.unlock() }
        return storedCreateDeletionRequestCount
    }

    static var statusQueryCount: Int {
        requestCountLock.lock()
        defer { requestCountLock.unlock() }
        return storedStatusQueryCount
    }

    static var lastDeleteRequestBody: Data? {
        requestCountLock.lock()
        defer { requestCountLock.unlock() }
        return storedLastDeleteRequestBody
    }

    static func setStatusResponseOverride(_ value: String?) {
        requestCountLock.lock()
        storedStatusResponseOverride = value
        requestCountLock.unlock()
    }

    static func resetDeleteAccountRequestCount() {
        requestCountLock.lock()
        storedDeleteAccountRequestCount = 0
        storedCreateDeletionRequestCount = 0
        storedStatusQueryCount = 0
        storedLastDeleteRequestBody = nil
        storedStatusResponseOverride = nil
        storedFailDeleteWithOwnerTransfer = false
        requestCountLock.unlock()
    }

    override class func canInit(with request: URLRequest) -> Bool {
        request.url?.host == "ai-delete-success.test" || request.url?.host == "ai-delete-failure.test"
    }

    override class func canonicalRequest(for request: URLRequest) -> URLRequest {
        request
    }

    override func startLoading() {
        guard let url = request.url else {
            client?.urlProtocol(self, didFailWithError: URLError(.badURL))
            return
        }
        var responseBody = "{}"
        switch url.path {
        case "/rest/v1/rpc/delete_my_account":
            Self.requestCountLock.lock()
            Self.storedDeleteAccountRequestCount += 1
            Self.storedLastDeleteRequestBody = request.httpBodyStream.map(Self.data(from:)) ?? request.httpBody
            let failOwnerTransfer = Self.storedFailDeleteWithOwnerTransfer
            Self.requestCountLock.unlock()
            if failOwnerTransfer {
                let ownerTransferResponse = HTTPURLResponse(
                    url: url,
                    statusCode: 400,
                    httpVersion: nil,
                    headerFields: ["Content-Type": "application/json"]
                )!
                client?.urlProtocol(self, didReceive: ownerTransferResponse, cacheStoragePolicy: .notAllowed)
                client?.urlProtocol(self, didLoad: Data(#"{"message":"owner_transfer_required"}"#.utf8))
                client?.urlProtocolDidFinishLoading(self)
                return
            }
        case "/rest/v1/rpc/create_account_deletion_request":
            Self.requestCountLock.lock()
            Self.storedCreateDeletionRequestCount += 1
            Self.requestCountLock.unlock()
            responseBody = #"{"request_id":"test-request-id","token":"test-status-token"}"#
        case "/rest/v1/rpc/get_account_deletion_status":
            Self.requestCountLock.lock()
            Self.storedStatusQueryCount += 1
            responseBody = Self.storedStatusResponseOverride ?? #"{"status":"completed","user_id":"ai-test-user"}"#
            Self.requestCountLock.unlock()
        default:
            break
        }
        // Only the deletion RPC honors the failure host; request/status RPCs
        // must succeed so tests exercise the full idempotent protocol.
        let isDeletePath = url.path == "/rest/v1/rpc/delete_my_account"
        let statusCode = isDeletePath && url.host == "ai-delete-failure.test" ? 500 : 200
        let response = HTTPURLResponse(
            url: url,
            statusCode: statusCode,
            httpVersion: nil,
            headerFields: ["Content-Type": "application/json"]
        )!
        client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: Data(responseBody.utf8))
        client?.urlProtocolDidFinishLoading(self)
    }

    private static func data(from stream: InputStream) -> Data {
        stream.open()
        defer { stream.close() }
        var data = Data()
        let bufferSize = 4096
        let buffer = UnsafeMutablePointer<UInt8>.allocate(capacity: bufferSize)
        defer { buffer.deallocate() }
        while stream.hasBytesAvailable {
            let read = stream.read(buffer, maxLength: bufferSize)
            if read <= 0 { break }
            data.append(buffer, count: read)
        }
        return data
    }

    override func stopLoading() {}
}

private final class LockedUserCollector: @unchecked Sendable {
    private let lock = NSLock()
    private var users: [String] = []

    func append(_ user: String) {
        lock.lock()
        users.append(user)
        lock.unlock()
    }

    var values: [String] {
        lock.lock()
        defer { lock.unlock() }
        return users
    }
}

private enum AiTransparencyInjectedTestError: Error {
    case localAiCleanupFailed
    case accountCleanupFailed
    case sessionCleanupFailed
}

private final class FailOnceAccountCleanupAction: @unchecked Sendable {
    private let lock = NSLock()
    private var shouldFail = true
    private(set) var callCount = 0

    func run() throws {
        lock.lock()
        callCount += 1
        let fail = shouldFail
        shouldFail = false
        lock.unlock()
        if fail {
            throw AiTransparencyInjectedTestError.accountCleanupFailed
        }
    }
}

private final class CountingAccountCleanupAction: @unchecked Sendable {
    private let lock = NSLock()
    private(set) var callCount = 0

    func run() {
        lock.lock()
        callCount += 1
        lock.unlock()
    }
}

private final class FailOnceClearAuthStorage: SharedTagAuthSecureStorage, @unchecked Sendable {
    private let lock = NSLock()
    private var payload: Data?
    private var shouldFailClear = true

    func load() throws -> Data? {
        lock.lock()
        defer { lock.unlock() }
        return payload
    }

    func save(_ data: Data) throws {
        lock.lock()
        payload = data
        lock.unlock()
    }

    func clear() throws {
        lock.lock()
        let fail = shouldFailClear
        shouldFailClear = false
        if !fail {
            payload = nil
        }
        lock.unlock()
        if fail {
            throw AiTransparencyInjectedTestError.sessionCleanupFailed
        }
    }
}

private final class FailOnceAiDataClearer: @unchecked Sendable {
    private let lock = NSLock()
    private let repository: URLRepository
    private var didFail = false

    init(repository: URLRepository) {
        self.repository = repository
    }

    func clear() throws {
        lock.lock()
        let shouldFail = !didFail
        didFail = true
        lock.unlock()
        if shouldFail {
            throw AiTransparencyInjectedTestError.localAiCleanupFailed
        }
        try repository.clearLocalAiData()
    }
}
