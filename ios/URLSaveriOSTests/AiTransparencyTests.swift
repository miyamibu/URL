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

    func testExternalDataPolicyExcludesSensitiveURLAndRedactsText() {
        let result = ExternalDataPolicy.inspect(
            url: "https://example.com/download?X-Amz-Signature=abcdef0123456789abcdef0123456789",
            texts: ["contact@example.com token=sk-12345678901234567890", "/Users/mimac/private.txt"]
        )

        XCTAssertFalse(result.allowed)
        XCTAssertTrue(result.reasons.contains("sensitive_query_key"))
        XCTAssertTrue(result.reasons.contains("email"))
        XCTAssertEqual(
            ExternalDataPolicy.safeURL("https://example.com/download?token=abcdef0123456789"),
            ExternalDataPolicy.excludedURLMarker
        )
        XCTAssertTrue(ExternalDataPolicy.sanitizeText("token=sk-12345678901234567890")?.contains("[redacted:token]") == true)
    }

    func testExternalDataPolicyRejectsNonWebAndPathSecretsInURLs() {
        let fileResult = ExternalDataPolicy.inspect(url: "file:///Users/mimac/private.txt")
        let pathResult = ExternalDataPolicy.inspect(url: "https://example.com/contact@example.com")

        XCTAssertFalse(fileResult.allowed)
        XCTAssertTrue(fileResult.reasons.contains("url_scheme") || fileResult.reasons.contains("url_parse_failed"))
        XCTAssertFalse(pathResult.allowed)
        XCTAssertTrue(pathResult.reasons.contains("email"))
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
            SharedTagAccountLocalCleanupState(aiDataCleanupPending: true, signOutCleanupPending: false)
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
            SharedTagAccountLocalCleanupState(aiDataCleanupPending: true, signOutCleanupPending: false)
        )

        let retryResult = regeneratedService.retryLocalAccountCleanup()

        XCTAssertEqual(retryResult, .success)
        XCTAssertNil(try repository.loadAiReceipt(receiptID: fixture.receipt.receiptID))
        XCTAssertNil(try repository.loadAiDraft(draftID: fixture.draft.draftID))
        XCTAssertNil(try repository.loadAiDiffProposal(proposalID: fixture.proposal.proposalID))
        XCTAssertNil(try sessionStore.load())
        XCTAssertNil(cleanupStateStore.load())
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
        cleanupStateStore: (any SharedTagAccountLocalCleanupStateStore)? = nil
    ) throws -> (
        service: SharedTagCloudService,
        sessionStore: SharedTagAuthSessionStore
    ) {
        let sessionStore = SharedTagAuthSessionStore(storage: AiTransparencyAuthStorage())
        try sessionStore.save(
            SharedTagAuthSession(
                authUserID: "ai-test-user",
                accessToken: "ai-test-access-token",
                refreshToken: nil,
                userEmail: "ai-test@example.com"
            )
        )
        let store = try SharedTagStore(database: repository.database)
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
            cleanupStateStore: cleanupStateStore
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

private final class AiTransparencyDeletionURLProtocol: URLProtocol, @unchecked Sendable {
    private static let requestCountLock = NSLock()
    nonisolated(unsafe) private static var storedDeleteAccountRequestCount = 0

    static var deleteAccountRequestCount: Int {
        requestCountLock.lock()
        defer { requestCountLock.unlock() }
        return storedDeleteAccountRequestCount
    }

    static func resetDeleteAccountRequestCount() {
        requestCountLock.lock()
        storedDeleteAccountRequestCount = 0
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
        if url.path == "/rest/v1/rpc/delete_my_account" {
            Self.requestCountLock.lock()
            Self.storedDeleteAccountRequestCount += 1
            Self.requestCountLock.unlock()
        }
        let statusCode = url.host == "ai-delete-failure.test" ? 500 : 200
        let response = HTTPURLResponse(
            url: url,
            statusCode: statusCode,
            httpVersion: nil,
            headerFields: ["Content-Type": "application/json"]
        )!
        client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: Data("{}".utf8))
        client?.urlProtocolDidFinishLoading(self)
    }

    override func stopLoading() {}
}

private enum AiTransparencyInjectedTestError: Error {
    case localAiCleanupFailed
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
