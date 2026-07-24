import CryptoKit
import Foundation

enum AiActionKind: String, Codable {
    case export = "EXPORT"
    case mcpSearch = "MCP_SEARCH"
    case mcpFetch = "MCP_FETCH"
    case personalLinkSync = "PERSONAL_LINK_SYNC"
}

enum AiSharedTagBoundary: String, Codable {
    case localOrUntagged = "LOCAL_OR_UNTAGGED"
    case containsSharedTag = "CONTAINS_SHARED_TAG"
}

enum AiDraftStatus: String, Codable {
    case proposed = "PROPOSED"
    case accepted = "ACCEPTED"
    case rejected = "REJECTED"
}

enum AiSizeBucket: String, Codable {
    case zero = "ZERO"
    case tiny = "TINY"
    case small = "SMALL"
    case medium = "MEDIUM"
    case large = "LARGE"
    case huge = "HUGE"
}

struct AiTransparencySource: Codable, Equatable {
    let publicSafeID: String
    let localEntryID: Int64?
    let title: String
    let normalizedURL: String
    let tagNames: [String]
    let sharedTagBoundary: AiSharedTagBoundary
    let aiEligible: Bool
    let exclusionReasons: [String]
}

struct AiSendPreview: Codable, Equatable {
    let actionKind: AiActionKind
    let destination: String
    let sources: [AiTransparencySource]
    let rawBodyIncluded: Bool
    let rawPromptIncluded: Bool

    var sourceCount: Int { sources.count }
    var eligibleCount: Int { sources.filter(\.aiEligible).count }
    var blockedCount: Int { sourceCount - eligibleCount }
    var canSend: Bool { eligibleCount > 0 && !rawBodyIncluded && !rawPromptIncluded }
}

struct AiSendReceipt: Codable, Equatable {
    let receiptID: String
    let actionKind: AiActionKind
    let destination: String
    let generatedAtISO: String
    let sentSourceIDs: [String]
    let blockedSourceIDs: [String]
    let redactionProfile: String
    let requestSizeBucket: AiSizeBucket
    let responseSizeBucket: AiSizeBucket
    let rawBodyIncluded: Bool
    let rawPromptIncluded: Bool
}

struct AiDraft: Codable, Equatable {
    let draftID: String
    let receiptID: String
    let generatedAtISO: String
    let title: String
    let body: String
    let citedSourceIDs: [String]
    var status: AiDraftStatus
}

struct AiDiffProposal: Codable, Equatable {
    let proposalID: String
    let draftID: String
    let generatedAtISO: String
    let operations: [AiDiffOperation]
    var applied: Bool
}

struct AiDiffOperation: Codable, Equatable {
    let targetPublicSafeID: String
    let field: String
    let before: String?
    let after: String?
}

struct AiProviderResult: Equatable {
    let title: String
    let body: String
    let responseBytes: Int?
    let citedSourceIDs: [String]
}

protocol AiProvider {
    func generateDraft(preview: AiSendPreview) throws -> AiProviderResult
}

struct MockAiProvider: AiProvider {
    func generateDraft(preview: AiSendPreview) throws -> AiProviderResult {
        let ids = preview.sources.filter(\.aiEligible).map(\.publicSafeID).sorted()
        let body = (["deterministic-mock-provider"] + ids.map { "- \($0)" }).joined(separator: "\n")
        return AiProviderResult(
            title: ids.isEmpty ? "AI下書き候補なし" : "AI下書き候補 \(ids.count)件",
            body: body,
            responseBytes: body.data(using: .utf8)?.count,
            citedSourceIDs: ids
        )
    }
}

enum AiTransparencyFeature {
    static var isEnabled: Bool {
        #if DEBUG
        return parseFlag(Bundle.main.object(forInfoDictionaryKey: "AiTransparencyEnabled"))
        #else
        return false
        #endif
    }

    private static func parseFlag(_ value: Any?) -> Bool {
        if let value = value as? Bool {
            return value
        }
        guard let value = value as? String else { return false }
        switch value.trimmingCharacters(in: .whitespacesAndNewlines).lowercased() {
        case "1", "true", "yes", "on":
            return true
        default:
            return false
        }
    }
}

struct ExternalDataPolicyResult: Equatable, Sendable {
    let allowed: Bool
    let reasons: [String]
}

/** Shared fail-closed boundary for values that may leave the device. */
enum ExternalDataPolicy {
    static let excludedURLMarker = "[excluded:sensitive_external_data]"

    private static let sensitiveQueryKeyPattern = "(?i)(token|access_token|refresh_token|code|state|nonce|signature|sig|expires|expiration|password|passwd|secret|api[_-]?key|auth|credential|invite)"
    private static let labeledSecretPattern = "(?i)(refresh_token|access_token|service_role|sb_secret|invite[_-]?token|password|secret|api[_-]?key|token)\\s*[:=]\\s*[\\\"']?[A-Za-z0-9._~+/=-]{8,}"
    private static let jwtPattern = "eyJ[A-Za-z0-9_-]{16,}\\.[A-Za-z0-9_-]{16,}\\.[A-Za-z0-9_-]{8,}"
    private static let knownKeyPattern = "(?i)(sk-[A-Za-z0-9]{16,}|gh[pousr]_[A-Za-z0-9]{16,}|glpat-[A-Za-z0-9_-]{16,})"
    private static let emailPattern = "(?i)[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}"
    private static let phonePattern = "(?<!\\d)(?:\\+?\\d[\\d\\s().-]{8,}\\d)(?!\\d)"
    private static let localPathPattern = "(?:/Users/|/private/var/|file://)[^\\s)]+"

    static func inspect(url: String?, texts: [String?] = []) -> ExternalDataPolicyResult {
        var reasons = Set<String>()
        if let url, !url.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            guard let components = URLComponents(string: url), components.user != nil || components.password != nil || components.host != nil else {
                reasons.insert("url_parse_failed")
                return ExternalDataPolicyResult(allowed: false, reasons: reasons.sorted())
            }
            if components.user != nil || components.password != nil {
                reasons.insert("url_userinfo")
            }
            if !["http", "https"].contains(components.scheme?.lowercased() ?? "") {
                reasons.insert("url_scheme")
            }
            for item in components.queryItems ?? [] {
                let key = item.name.lowercased()
                if matches(sensitiveQueryKeyPattern, in: key) {
                    reasons.insert("sensitive_query_key")
                }
                if let value = item.value, value.count >= 40, isHighEntropy(value) {
                    reasons.insert("high_entropy_query_value")
                }
            }
            if matches(labeledSecretPattern, in: url) || matches(jwtPattern, in: url) || matches(knownKeyPattern, in: url) {
                reasons.insert("sensitive_url_value")
            }
            if matches(emailPattern, in: url) { reasons.insert("email") }
            if matches(localPathPattern, in: url) { reasons.insert("local_path") }
        } else if url != nil {
            reasons.insert("url_parse_failed")
        }

        for text in texts.compactMap({ $0 }).filter({ !$0.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }) {
            if matches(labeledSecretPattern, in: text) || matches(jwtPattern, in: text) || matches(knownKeyPattern, in: text) {
                reasons.insert("sensitive_text")
            }
            if matches(emailPattern, in: text) { reasons.insert("email") }
            if matches(phonePattern, in: text) { reasons.insert("phone") }
            if matches(localPathPattern, in: text) { reasons.insert("local_path") }
        }
        return ExternalDataPolicyResult(allowed: reasons.isEmpty, reasons: reasons.sorted())
    }

    static func safeURL(_ value: String?) -> String? {
        guard let value, !value.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return nil }
        return inspect(url: value).allowed ? value : excludedURLMarker
    }

    static func sanitizeText(_ value: String?) -> String? {
        guard var output = value?.trimmingCharacters(in: .whitespacesAndNewlines), !output.isEmpty else { return nil }
        output = replace(labeledSecretPattern, in: output, with: "[redacted:token]")
        output = replace(jwtPattern, in: output, with: "[redacted:token]")
        output = replace(knownKeyPattern, in: output, with: "[redacted:key]")
        output = replace(emailPattern, in: output, with: "[redacted:email]")
        output = replace(phonePattern, in: output, with: "[redacted:phone]")
        output = replace(localPathPattern, in: output, with: "[redacted:local_path]")
        return output
    }

    private static func matches(_ pattern: String, in value: String) -> Bool {
        guard let expression = try? NSRegularExpression(pattern: pattern, options: []) else { return false }
        let range = NSRange(value.startIndex..<value.endIndex, in: value)
        return expression.firstMatch(in: value, options: [], range: range) != nil
    }

    private static func replace(_ pattern: String, in value: String, with replacement: String) -> String {
        guard let expression = try? NSRegularExpression(pattern: pattern, options: []) else { return value }
        let range = NSRange(value.startIndex..<value.endIndex, in: value)
        return expression.stringByReplacingMatches(in: value, options: [], range: range, withTemplate: replacement)
    }

    private static func isHighEntropy(_ value: String) -> Bool {
        let scalars = value.unicodeScalars
        let hasLower = scalars.contains { CharacterSet.lowercaseLetters.contains($0) }
        let hasUpper = scalars.contains { CharacterSet.uppercaseLetters.contains($0) }
        let hasDigit = scalars.contains { CharacterSet.decimalDigits.contains($0) }
        let hasSymbol = scalars.contains { !CharacterSet.alphanumerics.contains($0) }
        return Set(value).count >= 12 && [hasLower, hasUpper, hasDigit, hasSymbol].filter { $0 }.count >= 3
    }
}

enum AiTransparencyPolicy {
    static let savedSnapshotNotice = "保存時点の情報であり、現在の内容とは異なる可能性があります"

    static func buildPreview(
        actionKind: AiActionKind,
        destination: String,
        sources: [AiTransparencySource],
        rawBodyIncluded: Bool = false,
        rawPromptIncluded: Bool = false
    ) -> AiSendPreview {
        let normalizedSources = sources.map { source in
            AiTransparencySource(
                publicSafeID: source.publicSafeID,
                localEntryID: source.localEntryID,
                title: source.title,
                normalizedURL: source.normalizedURL,
                tagNames: Array(Set(source.tagNames)).sorted(),
                sharedTagBoundary: source.sharedTagBoundary,
                aiEligible: source.aiEligible,
                exclusionReasons: Array(Set(source.exclusionReasons)).sorted()
            )
        }
        return AiSendPreview(
            actionKind: actionKind,
            destination: destination,
            sources: normalizedSources,
            rawBodyIncluded: rawBodyIncluded,
            rawPromptIncluded: rawPromptIncluded
        )
    }

    static func buildReceipt(
        preview: AiSendPreview,
        generatedAtISO: String,
        redactionProfile: String = "ai-safe-v1",
        requestBytes: Int? = nil,
        responseBytes: Int? = nil
    ) -> AiSendReceipt {
        let sentIDs = preview.sources.filter(\.aiEligible).map(\.publicSafeID).sorted()
        let blockedIDs = preview.sources.filter { !$0.aiEligible }.map(\.publicSafeID).sorted()
        return AiSendReceipt(
            receiptID: stableID(prefix: "receipt", parts: [preview.actionKind.rawValue, preview.destination, generatedAtISO, sentIDs.joined(separator: ",")]),
            actionKind: preview.actionKind,
            destination: preview.destination,
            generatedAtISO: generatedAtISO,
            sentSourceIDs: sentIDs,
            blockedSourceIDs: blockedIDs,
            redactionProfile: redactionProfile,
            requestSizeBucket: sizeBucket(bytes: requestBytes),
            responseSizeBucket: sizeBucket(bytes: responseBytes),
            rawBodyIncluded: preview.rawBodyIncluded,
            rawPromptIncluded: preview.rawPromptIncluded
        )
    }

    static func buildDraft(
        receipt: AiSendReceipt,
        generatedAtISO: String,
        title: String,
        body: String,
        citedSourceIDs: [String]
    ) -> AiDraft {
        let allowed = Array(Set(citedSourceIDs.filter { receipt.sentSourceIDs.contains($0) })).sorted()
        return AiDraft(
            draftID: stableID(prefix: "draft", parts: [receipt.receiptID, generatedAtISO, title, allowed.joined(separator: ",")]),
            receiptID: receipt.receiptID,
            generatedAtISO: generatedAtISO,
            title: redactLocalText(title),
            body: redactLocalText(body),
            citedSourceIDs: allowed,
            status: .proposed
        )
    }

    static func buildDiffProposal(
        draft: AiDraft,
        generatedAtISO: String,
        operations: [AiDiffOperation]
    ) -> AiDiffProposal {
        let normalized = operations.map { operation in
            AiDiffOperation(
                targetPublicSafeID: operation.targetPublicSafeID,
                field: operation.field,
                before: operation.before.map(redactLocalText),
                after: operation.after.map(redactLocalText)
            )
        }.sorted {
            if $0.targetPublicSafeID == $1.targetPublicSafeID {
                return $0.field < $1.field
            }
            return $0.targetPublicSafeID < $1.targetPublicSafeID
        }
        let operationKey = normalized.map { "\($0.targetPublicSafeID):\($0.field):\($0.after ?? "")" }.joined(separator: "|")
        return AiDiffProposal(
            proposalID: stableID(prefix: "diff", parts: [draft.draftID, generatedAtISO, operationKey]),
            draftID: draft.draftID,
            generatedAtISO: generatedAtISO,
            operations: normalized,
            applied: false
        )
    }

    static func source(for entry: URLRecord, publicSafeID: String, tagNames: [String] = [], containsSharedTag: Bool? = nil) -> AiTransparencySource {
        let hasSharedTag = containsSharedTag ?? (entry.sharedReferenceCount > 0)
        var reasons: [String] = []
        if entry.localProvenanceCount <= 0 { reasons.append("local_provenance_required") }
        if hasSharedTag { reasons.append("shared_tag_default_excluded") }
        if entry.recordState == .archived { reasons.append("archived_default_excluded") }
        if entry.recordState == .pendingDelete { reasons.append("pending_delete_excluded") }
        if entry.pendingDeletionUntil != nil { reasons.append("pending_delete_excluded") }
        let externalData = ExternalDataPolicy.inspect(
            url: entry.normalizedURL,
            texts: [entry.userTitle, entry.fetchedTitle] + tagNames.map(Optional.some)
        )
        reasons.append(contentsOf: externalData.reasons.map { "external_data_\($0)" })
        return AiTransparencySource(
            publicSafeID: publicSafeID,
            localEntryID: entry.id,
            title: ExternalDataPolicy.sanitizeText(entry.userTitle ?? entry.fetchedTitle ?? entry.normalizedHost) ?? "保存したリンク",
            normalizedURL: entry.normalizedURL,
            tagNames: tagNames,
            sharedTagBoundary: hasSharedTag ? .containsSharedTag : .localOrUntagged,
            aiEligible: reasons.isEmpty,
            exclusionReasons: Array(Set(reasons)).sorted()
        )
    }

    static func sizeBucket(bytes: Int?) -> AiSizeBucket {
        guard let value = bytes else { return .zero }
        switch value {
        case ...0: return .zero
        case ...1_024: return .tiny
        case ...16_384: return .small
        case ...131_072: return .medium
        case ...1_048_576: return .large
        default: return .huge
        }
    }

    static func publicSafeID(for entry: URLRecord) -> String {
        stableID(prefix: "safe", parts: ["ios", String(entry.id), entry.normalizedURL])
    }

    private static func stableID(prefix: String, parts: [String]) -> String {
        let input = parts.joined(separator: "\u{1f}")
        let digest = SHA256.hash(data: Data(input.utf8))
        let hex = digest.map { String(format: "%02x", $0) }.joined()
        return "\(prefix)-" + String(hex.prefix(24))
    }

    static func redactLocalText(_ value: String) -> String {
        let pattern = "https?://[^\\s]+|(?:/Users/|/private/|/var/|[A-Za-z]:\\\\)[^\\s]+|eyJ[A-Za-z0-9_-]{20,}|(?<![A-Za-z0-9])[A-Za-z0-9_-]{40,}(?![A-Za-z0-9])"
        guard let expression = try? NSRegularExpression(pattern: pattern, options: [.caseInsensitive]) else {
            return value
        }
        let range = NSRange(value.startIndex..<value.endIndex, in: value)
        return expression.stringByReplacingMatches(
            in: value,
            options: [],
            range: range,
            withTemplate: "[URL非表示]"
        )
    }
}
