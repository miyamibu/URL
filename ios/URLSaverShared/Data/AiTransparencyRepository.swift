import Foundation
import SQLite3

enum AiTransparencyRepositoryError: Error {
    case unsafeRawPayload
}

extension URLRepository {
    func saveAiReceipt(
        preview: AiSendPreview,
        requestBytes: Int? = nil,
        responseBytes: Int? = nil,
        redactionProfile: String = "ai-safe-v1",
        generatedAt: Date = Date()
    ) throws -> AiSendReceipt {
        guard !preview.rawBodyIncluded, !preview.rawPromptIncluded else {
            throw AiTransparencyRepositoryError.unsafeRawPayload
        }
        let receipt = AiTransparencyPolicy.buildReceipt(
            preview: preview,
            generatedAtISO: Self.aiISODateString(generatedAt),
            redactionProfile: redactionProfile,
            requestBytes: requestBytes,
            responseBytes: responseBytes
        )
        try database.transaction {
            try database.execute(
                """
                INSERT OR REPLACE INTO ai_receipts (
                    receipt_id, action_kind, destination, generated_at_iso, redaction_profile,
                    request_size_bucket, response_size_bucket, raw_body_included, raw_prompt_included
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?);
                """,
                binds: [
                    sql(receipt.receiptID),
                    sql(receipt.actionKind.rawValue),
                    sql(receipt.destination),
                    sql(receipt.generatedAtISO),
                    sql(receipt.redactionProfile),
                    sql(receipt.requestSizeBucket.rawValue),
                    sql(receipt.responseSizeBucket.rawValue),
                    sql(receipt.rawBodyIncluded ? 1 : 0),
                    sql(receipt.rawPromptIncluded ? 1 : 0),
                ]
            )
            for source in preview.sources {
                try database.execute(
                    """
                    INSERT OR REPLACE INTO ai_receipt_sources (
                        receipt_id, public_safe_id, entry_id, title, normalized_url, tag_names_json,
                        shared_tag_boundary, ai_eligible, exclusion_reasons_json
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?);
                    """,
                    binds: [
                        sql(receipt.receiptID),
                        sql(source.publicSafeID),
                        sql(source.localEntryID),
                        sql(source.title),
                        sql(source.normalizedURL),
                        sql(Self.aiEncode(source.tagNames)),
                        sql(source.sharedTagBoundary.rawValue),
                        sql(source.aiEligible ? 1 : 0),
                        sql(Self.aiEncode(source.exclusionReasons)),
                    ]
                )
            }
        }
        return receipt
    }

    func loadAiReceipt(receiptID: String) throws -> AiSendReceipt? {
        guard let row = try database.fetchOne(
            sql: """
            SELECT receipt_id, action_kind, destination, generated_at_iso, redaction_profile,
                   request_size_bucket, response_size_bucket, raw_body_included, raw_prompt_included
            FROM ai_receipts
            WHERE receipt_id = ?
            LIMIT 1;
            """,
            binds: [sql(receiptID)],
            decode: { statement in
                AiReceiptRow(statement: statement)
            }
        ) else {
            return nil
        }
        let sources = try loadAiReceiptSources(receiptID: receiptID)
        return AiSendReceipt(
            receiptID: row.receiptID,
            actionKind: row.actionKind,
            destination: row.destination,
            generatedAtISO: row.generatedAtISO,
            sentSourceIDs: sources.filter(\.aiEligible).map(\.publicSafeID).sorted(),
            blockedSourceIDs: sources.filter { !$0.aiEligible }.map(\.publicSafeID).sorted(),
            redactionProfile: row.redactionProfile,
            requestSizeBucket: row.requestSizeBucket,
            responseSizeBucket: row.responseSizeBucket,
            rawBodyIncluded: row.rawBodyIncluded,
            rawPromptIncluded: row.rawPromptIncluded
        )
    }

    func loadAiReceiptSources(receiptID: String) throws -> [AiTransparencySource] {
        try database.fetchMany(
            sql: """
            SELECT public_safe_id, entry_id, title, normalized_url, tag_names_json,
                   shared_tag_boundary, ai_eligible, exclusion_reasons_json
            FROM ai_receipt_sources
            WHERE receipt_id = ?
            ORDER BY public_safe_id ASC;
            """,
            binds: [sql(receiptID)]
        ) { statement in
            AiTransparencySource(
                publicSafeID: Self.aiText(statement, 0) ?? "",
                localEntryID: Self.aiInt64(statement, 1),
                title: Self.aiText(statement, 2) ?? "",
                normalizedURL: Self.aiText(statement, 3) ?? "",
                tagNames: Self.aiDecodeStringArray(Self.aiText(statement, 4)),
                sharedTagBoundary: AiSharedTagBoundary(rawValue: Self.aiText(statement, 5) ?? "") ?? .localOrUntagged,
                aiEligible: sqlite3_column_int(statement, 6) != 0,
                exclusionReasons: Self.aiDecodeStringArray(Self.aiText(statement, 7))
            )
        }
    }

    func generateAiDraftWithFallback(
        preview: AiSendPreview,
        receipt: AiSendReceipt,
        provider: AiProvider = MockAiProvider(),
        generatedAt: Date = Date()
    ) throws -> AiDraft {
        let result = try provider.generateDraft(preview: preview)
        return try saveAiDraft(
            receipt: receipt,
            title: result.title,
            body: result.body,
            citedSourceIDs: result.citedSourceIDs,
            generatedAt: generatedAt
        )
    }

    func saveAiDraft(
        receipt: AiSendReceipt,
        title: String,
        body: String,
        citedSourceIDs: [String],
        generatedAt: Date = Date()
    ) throws -> AiDraft {
        let draft = AiTransparencyPolicy.buildDraft(
            receipt: receipt,
            generatedAtISO: Self.aiISODateString(generatedAt),
            title: title,
            body: body,
            citedSourceIDs: citedSourceIDs
        )
        try database.execute(
            """
            INSERT OR REPLACE INTO ai_drafts (
                draft_id, receipt_id, generated_at_iso, title, body, cited_source_ids_json, status
            ) VALUES (?, ?, ?, ?, ?, ?, ?);
            """,
            binds: [
                sql(draft.draftID),
                sql(draft.receiptID),
                sql(draft.generatedAtISO),
                sql(draft.title),
                sql(draft.body),
                sql(Self.aiEncode(draft.citedSourceIDs)),
                sql(draft.status.rawValue),
            ]
        )
        return draft
    }

    func loadAiDraft(draftID: String) throws -> AiDraft? {
        try database.fetchOne(
            sql: """
            SELECT draft_id, receipt_id, generated_at_iso, title, body, cited_source_ids_json, status
            FROM ai_drafts
            WHERE draft_id = ?
            LIMIT 1;
            """,
            binds: [sql(draftID)]
        ) { statement in
            AiDraft(
                draftID: Self.aiText(statement, 0) ?? "",
                receiptID: Self.aiText(statement, 1) ?? "",
                generatedAtISO: Self.aiText(statement, 2) ?? "",
                title: Self.aiText(statement, 3) ?? "",
                body: Self.aiText(statement, 4) ?? "",
                citedSourceIDs: Self.aiDecodeStringArray(Self.aiText(statement, 5)),
                status: AiDraftStatus(rawValue: Self.aiText(statement, 6) ?? "") ?? .proposed
            )
        }
    }

    func saveAiDiffProposal(
        draft: AiDraft,
        operations: [AiDiffOperation],
        generatedAt: Date = Date()
    ) throws -> AiDiffProposal {
        let proposal = AiTransparencyPolicy.buildDiffProposal(
            draft: draft,
            generatedAtISO: Self.aiISODateString(generatedAt),
            operations: operations
        )
        try database.execute(
            """
            INSERT OR REPLACE INTO ai_diff_proposals (
                proposal_id, draft_id, generated_at_iso, operations_json, applied
            ) VALUES (?, ?, ?, ?, ?);
            """,
            binds: [
                sql(proposal.proposalID),
                sql(proposal.draftID),
                sql(proposal.generatedAtISO),
                sql(Self.aiEncode(proposal.operations)),
                sql(proposal.applied ? 1 : 0),
            ]
        )
        return proposal
    }

    func loadAiDiffProposal(proposalID: String) throws -> AiDiffProposal? {
        try database.fetchOne(
            sql: """
            SELECT proposal_id, draft_id, generated_at_iso, operations_json, applied
            FROM ai_diff_proposals
            WHERE proposal_id = ?
            LIMIT 1;
            """,
            binds: [sql(proposalID)]
        ) { statement in
            AiDiffProposal(
                proposalID: Self.aiText(statement, 0) ?? "",
                draftID: Self.aiText(statement, 1) ?? "",
                generatedAtISO: Self.aiText(statement, 2) ?? "",
                operations: Self.aiDecodeOperations(Self.aiText(statement, 3)),
                applied: sqlite3_column_int(statement, 4) != 0
            )
        }
    }

    func applyAiDiffProposal(proposalID: String, confirm: Bool, now: Date = Date()) throws -> Bool {
        guard confirm,
              var proposal = try loadAiDiffProposal(proposalID: proposalID),
              !proposal.applied,
              let draft = try loadAiDraft(draftID: proposal.draftID)
        else {
            return false
        }

        var changed = false
        try database.transaction {
            for operation in proposal.operations where operation.field == "userTitle" || operation.field == "memo" {
                guard let source = try loadAiReceiptSources(receiptID: draft.receiptID)
                    .first(where: { $0.publicSafeID == operation.targetPublicSafeID }),
                    let entryID = source.localEntryID
                else {
                    continue
                }
                switch operation.field {
                case "userTitle":
                    let titleValue: String? = operation.after?.isEmpty == false ? operation.after : nil
                    try database.execute(
                        "UPDATE url_entries SET user_title = ?, updated_at = ? WHERE id = ?;",
                        binds: [sql(titleValue), sql(now.timeIntervalSince1970), sql(entryID)]
                    )
                    changed = true
                case "memo":
                    try database.execute(
                        "UPDATE url_entries SET memo = ?, updated_at = ? WHERE id = ?;",
                        binds: [sql(operation.after ?? ""), sql(now.timeIntervalSince1970), sql(entryID)]
                    )
                    changed = true
                default:
                    break
                }
            }
            if changed {
                proposal.applied = true
                try database.execute(
                    "UPDATE ai_diff_proposals SET applied = 1 WHERE proposal_id = ?;",
                    binds: [sql(proposal.proposalID)]
                )
                try database.execute(
                    "UPDATE ai_drafts SET status = ? WHERE draft_id = ?;",
                    binds: [sql(AiDraftStatus.accepted.rawValue), sql(draft.draftID)]
                )
            }
        }
        return changed
    }

    func clearLocalAiData() throws {
        try database.transaction {
            try database.execute("DELETE FROM ai_diff_proposals;")
            try database.execute("DELETE FROM ai_drafts;")
            try database.execute("DELETE FROM ai_receipts;")
        }
    }

    fileprivate static func aiISODateString(_ date: Date) -> String {
        let formatter = ISO8601DateFormatter()
        formatter.formatOptions = [.withInternetDateTime]
        return formatter.string(from: date)
    }

    fileprivate static func aiEncode<T: Encodable>(_ value: T) -> String {
        guard let data = try? JSONEncoder().encode(value),
              let output = String(data: data, encoding: .utf8) else {
            return "[]"
        }
        return output
    }

    fileprivate static func aiDecodeStringArray(_ value: String?) -> [String] {
        guard let data = value?.data(using: .utf8),
              let output = try? JSONDecoder().decode([String].self, from: data) else {
            return []
        }
        return output
    }

    fileprivate static func aiDecodeOperations(_ value: String?) -> [AiDiffOperation] {
        guard let data = value?.data(using: .utf8),
              let output = try? JSONDecoder().decode([AiDiffOperation].self, from: data) else {
            return []
        }
        return output
    }

    fileprivate static func aiText(_ statement: OpaquePointer?, _ index: Int32) -> String? {
        guard sqlite3_column_type(statement, index) != SQLITE_NULL,
              let value = sqlite3_column_text(statement, index) else {
            return nil
        }
        return String(cString: value)
    }

    fileprivate static func aiInt64(_ statement: OpaquePointer?, _ index: Int32) -> Int64? {
        guard sqlite3_column_type(statement, index) != SQLITE_NULL else { return nil }
        return sqlite3_column_int64(statement, index)
    }
}

private struct AiReceiptRow {
    let receiptID: String
    let actionKind: AiActionKind
    let destination: String
    let generatedAtISO: String
    let redactionProfile: String
    let requestSizeBucket: AiSizeBucket
    let responseSizeBucket: AiSizeBucket
    let rawBodyIncluded: Bool
    let rawPromptIncluded: Bool

    init(statement: OpaquePointer?) {
        receiptID = URLRepository.aiText(statement, 0) ?? ""
        actionKind = AiActionKind(rawValue: URLRepository.aiText(statement, 1) ?? "") ?? .export
        destination = URLRepository.aiText(statement, 2) ?? ""
        generatedAtISO = URLRepository.aiText(statement, 3) ?? ""
        redactionProfile = URLRepository.aiText(statement, 4) ?? "ai-safe-v1"
        requestSizeBucket = AiSizeBucket(rawValue: URLRepository.aiText(statement, 5) ?? "") ?? .zero
        responseSizeBucket = AiSizeBucket(rawValue: URLRepository.aiText(statement, 6) ?? "") ?? .zero
        rawBodyIncluded = sqlite3_column_int(statement, 7) != 0
        rawPromptIncluded = sqlite3_column_int(statement, 8) != 0
    }
}
