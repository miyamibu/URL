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
        let preview = AiTransparencyPolicy.buildPreview(
            actionKind: .export,
            destination: "internal-preview",
            sources: [AiTransparencyPolicy.source(for: record, publicSafeID: "safe")]
        )
        let receipt = try repository.saveAiReceipt(preview: preview, generatedAt: Date(timeIntervalSince1970: 1_714_000_000))
        let draft = try repository.saveAiDraft(
            receipt: receipt,
            title: "候補",
            body: "本文",
            citedSourceIDs: ["safe"],
            generatedAt: Date(timeIntervalSince1970: 1_714_000_001)
        )
        let proposal = try repository.saveAiDiffProposal(
            draft: draft,
            operations: [
                AiDiffOperation(targetPublicSafeID: "safe", field: "userTitle", before: nil, after: "新しいタイトル"),
                AiDiffOperation(targetPublicSafeID: "safe", field: "memo", before: "", after: "新しいメモ"),
                AiDiffOperation(targetPublicSafeID: "safe", field: "normalizedURL", before: nil, after: "https://evil.example"),
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

    func testFeatureFlagDefaultsOffForNormalUi() {
        XCTAssertFalse(AiTransparencyFeature.isEnabled)
    }

    private func saveEntry(_ url: String) throws -> URLRecord {
        let result = try repository.saveFromManualInput(url)
        return try XCTUnwrap(repository.loadEntry(id: try XCTUnwrap(result.entryID)))
    }
}
