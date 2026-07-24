import CryptoKit
import Foundation

enum URLExportScope: String, CaseIterable, Codable, Sendable {
    case all = "ALL"
    case singleTag = "SINGLE_TAG"
    case multipleTags = "MULTIPLE_TAGS"
    case sharedTagsOnly = "SHARED_TAGS_ONLY"

    var displayName: String {
        switch self {
        case .all: return "すべて"
        case .singleTag: return "タグを1つ選択"
        case .multipleTags: return "複数タグを選択"
        case .sharedTagsOnly: return "共有タグのみ"
        }
    }
}

enum URLExportRecordStateFilter: String, CaseIterable, Codable, Sendable {
    case active = "ACTIVE"
    case archived = "ARCHIVED"
    case both = "BOTH"

    var displayName: String {
        switch self {
        case .active: return "保存中"
        case .archived: return "アーカイブ"
        case .both: return "両方"
        }
    }
}

enum URLExportTagScope: String, Codable, Sendable {
    case local = "LOCAL"
    case synced = "SYNCED"

    var displayName: String {
        switch self {
        case .local: return "通常タグ"
        case .synced: return "共有タグ"
        }
    }
}

struct URLExportTagOption: Identifiable, Equatable, Sendable {
    let id: String
    let name: String
    let scope: URLExportTagScope
    let urlCount: Int
}

enum URLExportOutputFormat: String, CaseIterable, Codable, Sendable {
    case zip = "ZIP"
    case json = "JSON"
}

struct URLExportRequest: Equatable, Sendable {
    let scope: URLExportScope
    let selectedTagIDs: Set<String>
    let recordStateFilter: URLExportRecordStateFilter
    let serviceType: ServiceType?
    let onlyWithMemo: Bool
    let dateFrom: Date?
    let dateTo: Date?
    let outputFormat: URLExportOutputFormat
}

struct PreparedExportArchive: Equatable, Sendable {
    let fileName: String
    let bytes: Data
    let entryCount: Int
    let mimeType: String
}

enum URLExportArchiveBuilder {
    static func buildAvailableTags(
        localTags: [LocalTagSummary],
        localTagAssignments: [Int64: Set<Int64>],
        sharedTags: [SharedTagSummary]
    ) -> [URLExportTagOption] {
        let localOptions = localTags.map { tag in
            URLExportTagOption(
                id: localTagKey(tag.id),
                name: tag.name,
                scope: .local,
                urlCount: localTagAssignments.values.filter { $0.contains(tag.id) }.count
            )
        }
        let sharedOptions = sharedTags.map { tag in
            URLExportTagOption(
                id: sharedTagKey(tag.remoteTagID),
                name: tag.name,
                scope: .synced,
                urlCount: tag.activeURLCount
            )
        }
        return (localOptions + sharedOptions).sorted {
            if $0.scope != $1.scope {
                return $0.scope.rawValue < $1.scope.rawValue
            }
            return $0.name.localizedStandardCompare($1.name) == .orderedAscending
        }
    }

    static func prepareExport(
        request: URLExportRequest,
        entries: [URLRecord],
        localTags: [LocalTagSummary],
        localTagAssignments: [Int64: Set<Int64>],
        sharedTagsByEntryID: [Int64: [SharedTagSummary]],
        appVersion: String,
        now: Date = Date(),
        calendar: Calendar = .current
    ) throws -> PreparedExportArchive {
        try validate(request)

        let tagOptions = buildAvailableTags(
            localTags: localTags,
            localTagAssignments: localTagAssignments,
            sharedTags: Array(Set(sharedTagsByEntryID.values.flatMap { $0 }.map(\.remoteTagID))).compactMap { remoteID in
                sharedTagsByEntryID.values.flatMap { $0 }.first { $0.remoteTagID == remoteID }
            }
        )
        let tagNamesByID = Dictionary(uniqueKeysWithValues: tagOptions.map { ($0.id, $0.name) })
        let localTagsByID = Dictionary(uniqueKeysWithValues: localTags.map { ($0.id, $0) })

        let documents = entries
            .map { entry in
                let localTagSummaries = (localTagAssignments[entry.id] ?? [])
                    .compactMap { localTagsByID[$0] }
                    .map { ExportTagSummary(id: Self.localTagKey($0.id), name: $0.name, scope: URLExportTagScope.local.rawValue) }
                let sharedTagSummaries = (sharedTagsByEntryID[entry.id] ?? [])
                    .map { ExportTagSummary(id: Self.sharedTagKey($0.remoteTagID), name: $0.name, scope: URLExportTagScope.synced.rawValue) }
                return ExportSourceEntry(entry: entry, tags: localTagSummaries + sharedTagSummaries)
            }
            .filter { $0.matches(request: request, calendar: calendar) }
            .map { $0.toDocument() }

        let generatedAt = isoString(now)
        let redactionReport = ExportRedactionReport.from(generatedAt: generatedAt, documents: documents)
        let selectedNames = request.selectedTagIDs.compactMap { tagNamesByID[$0] }.sorted()
        let manifest = ExportManifest(
            generatedAt: generatedAt,
            appVersion: appVersion,
            entryCount: documents.count,
            exportScope: request.scope.rawValue,
            selectedTagIds: request.selectedTagIDs.sorted(),
            selectedTagNames: selectedNames,
            recordStateFilter: request.recordStateFilter.rawValue,
            serviceFilter: request.serviceType.map { exportName($0.rawValue) },
            onlyWithMemo: request.onlyWithMemo,
            dateFrom: request.dateFrom.map(dateString(_:)),
            dateTo: request.dateTo.map(dateString(_:)),
            fields: exportFields
        )

        switch request.outputFormat {
        case .zip:
            return try buildZipExport(
                manifest: manifest,
                documents: documents,
                redactionReport: redactionReport,
                now: now
            )
        case .json:
            return try buildJSONExport(
                manifest: manifest,
                documents: documents,
                redactionReport: redactionReport,
                now: now
            )
        }
    }

    static func localTagKey(_ id: Int64) -> String {
        "local:\(id)"
    }

    static func sharedTagKey(_ id: String) -> String {
        "shared:\(id)"
    }

    private static func validate(_ request: URLExportRequest) throws {
        switch request.scope {
        case .all, .sharedTagsOnly:
            break
        case .singleTag:
            if request.selectedTagIDs.count != 1 {
                throw URLExportError.invalidRequest("単一タグエクスポートでは1つのタグを選択してください。")
            }
        case .multipleTags:
            if request.selectedTagIDs.isEmpty {
                throw URLExportError.invalidRequest("複数タグエクスポートでは1つ以上のタグを選択してください。")
            }
        }
        if let dateFrom = request.dateFrom, let dateTo = request.dateTo, dateFrom > dateTo {
            throw URLExportError.invalidRequest("開始日は終了日以前にしてください。")
        }
    }

    private static func buildArchiveFileName(_ date: Date, format: URLExportOutputFormat) -> String {
        let timestamp = fileTimestampString(date)
        switch format {
        case .zip:
            return "urlsaver-export-\(timestamp).zip"
        case .json:
            return "urlsaver-export-\(timestamp).json"
        }
    }

    private static func buildEntryFileName(index: Int, document: ExportEntryDocument) -> String {
        let seed = document.effectiveTitle.isEmpty ? (document.normalizedHost.isEmpty ? "entry" : document.normalizedHost) : document.effectiveTitle
        let slug = seed
            .lowercased()
            .replacingOccurrences(of: "[^a-z0-9]+", with: "-", options: .regularExpression)
            .trimmingCharacters(in: CharacterSet(charactersIn: "-"))
            .prefix(48)
        let safeSlug = slug.isEmpty ? "entry" : String(slug)
        return "\(String(format: "%04d", index))-\(document.id)-\(safeSlug)"
    }

    static func isoString(_ date: Date) -> String {
        ISO8601DateFormatter().string(from: date)
    }

    private static func dateString(_ date: Date) -> String {
        let formatter = DateFormatter()
        formatter.calendar = Calendar(identifier: .gregorian)
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.dateFormat = "yyyy-MM-dd"
        return formatter.string(from: date)
    }

    private static func fileTimestampString(_ date: Date) -> String {
        let formatter = DateFormatter()
        formatter.calendar = Calendar(identifier: .gregorian)
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.dateFormat = "yyyyMMdd-HHmmss"
        return formatter.string(from: date)
    }

    private static func exportName(_ rawValue: String) -> String {
        rawValue.uppercased()
    }

    private static func prettyEncoder() -> JSONEncoder {
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.prettyPrinted, .sortedKeys, .withoutEscapingSlashes]
        return encoder
    }

    private static func lineEncoder() -> JSONEncoder {
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.sortedKeys, .withoutEscapingSlashes]
        return encoder
    }

    private static func buildZipExport(
        manifest: ExportManifest,
        documents: [ExportEntryDocument],
        redactionReport: ExportRedactionReport,
        now: Date
    ) throws -> PreparedExportArchive {
        var zip = SimpleZipWriter()
        zip.addFile(path: "manifest.json", data: try prettyEncoder().encode(manifest))
        zip.addFile(path: "schema.json", data: Data(aiSafeSchemaJSON.utf8))
        zip.addFile(path: "README_FOR_AI.md", data: Data(buildReadmeForAi(manifest: manifest, report: redactionReport).utf8))
        zip.addFile(path: "redaction_report.json", data: try prettyEncoder().encode(redactionReport))
        let encoder = lineEncoder()
        let jsonl = try documents.map { document in
            String(data: try encoder.encode(document), encoding: .utf8) ?? "{}"
        }.joined(separator: "\n")
        zip.addFile(path: "entries.jsonl", data: Data(jsonl.utf8))
        for (index, document) in documents.enumerated() {
            let fileName = buildEntryFileName(index: index + 1, document: document)
            zip.addFile(path: "entries/\(fileName).md", data: Data(document.toMarkdown().utf8))
        }

        return PreparedExportArchive(
            fileName: buildArchiveFileName(now, format: .zip),
            bytes: zip.finalize(),
            entryCount: documents.count,
            mimeType: "application/zip"
        )
    }

    private static func buildJSONExport(
        manifest: ExportManifest,
        documents: [ExportEntryDocument],
        redactionReport: ExportRedactionReport,
        now: Date
    ) throws -> PreparedExportArchive {
        let payload = ExportJSONDocument(
            manifest: manifest,
            entries: documents,
            readmeForAi: buildReadmeForAi(manifest: manifest, report: redactionReport),
            redactionReport: redactionReport
        )
        let bytes = try prettyEncoder().encode(payload)
        return PreparedExportArchive(
            fileName: buildArchiveFileName(now, format: .json),
            bytes: bytes,
            entryCount: documents.count,
            mimeType: "application/json"
        )
    }

    private static let exportFields = [
        "id",
        "publicSafeId",
        "originalUrl",
        "normalizedUrl",
        "displayUrl",
        "openUrl",
        "providerPermalink",
        "providerCanonicalId",
        "serviceType",
        "contentContext",
        "recordState",
        "createdAt",
        "updatedAt",
        "archivedAt",
        "userTitle",
        "fetchedTitle",
        "fetchedAuthorName",
        "fetchedBodyKind",
        "bodySummary",
        "bodyExcerpt",
        "description",
        "memoExcerpt",
        "thumbnailUrl",
        "badgeImageUrl",
        "canonicalId",
        "normalizedHost",
        "rawSourceHost",
        "metadataState",
        "metadataError",
        "metadataFetchedAt",
        "metadataSource",
        "savedSnapshotNotice",
        "collection",
        "tags",
        "tagScopes",
        "sharedTagBoundary",
        "aiEligible",
        "aiExclusionReason",
        "redactionApplied",
    ]

    fileprivate static let bodyExcerptMaxCharacters = 1_000
    fileprivate static let memoExcerptMaxCharacters = 1_000

    fileprivate static func publicSafeId(entryID: Int64, normalizedURL: String) -> String {
        let digest = SHA256.hash(data: Data("ios-ai-export-v1:\(entryID):\(normalizedURL)".utf8))
        return digest.map { String(format: "%02x", $0) }.joined().prefix(32).description
    }

    fileprivate static func clipText(_ value: String, maxCharacters: Int) -> String {
        let trimmed = value.trimmingCharacters(in: .whitespacesAndNewlines)
        guard trimmed.count > maxCharacters else { return trimmed }
        return String(trimmed.prefix(maxCharacters - 1)) + "…"
    }

    fileprivate static func providerPermalink(for entry: URLRecord) -> String {
        guard let canonicalID = entry.canonicalID, !canonicalID.isEmpty else { return entry.openURL }
        switch entry.serviceType {
        case .youtube:
            return "https://www.youtube.com/watch?v=\(canonicalID)"
        case .x:
            return "https://x.com/i/web/status/\(canonicalID)"
        default:
            return entry.openURL
        }
    }

    fileprivate static func metadataSource(for entry: URLRecord) -> String {
        if entry.metadataFetchedAt != nil { return "metadata_fetcher" }
        if entry.fetchedTitle?.isEmpty == false || entry.bodySummary?.isEmpty == false {
            return "metadata_cache"
        }
        return "user_saved_url"
    }

    fileprivate static func savedSnapshotNotice(for entry: URLRecord) -> String? {
        let hasSavedMetadata = entry.metadataFetchedAt != nil ||
            entry.fetchedTitle?.isEmpty == false ||
            entry.fetchedAuthorName?.isEmpty == false ||
            entry.bodySummary?.isEmpty == false ||
            entry.description?.isEmpty == false ||
            entry.thumbnailURL?.isEmpty == false
        return hasSavedMetadata ? AiTransparencyPolicy.savedSnapshotNotice : nil
    }

    fileprivate static func redact(_ value: String?) -> RedactedText {
        guard var output = value?.trimmingCharacters(in: .whitespacesAndNewlines), !output.isEmpty else {
            return RedactedText(value: nil, redactions: [])
        }
        var redactions: Set<String> = []
        func apply(_ pattern: String, label: String) {
            guard let regex = try? NSRegularExpression(pattern: pattern, options: [.caseInsensitive]) else { return }
            let range = NSRange(output.startIndex..<output.endIndex, in: output)
            guard regex.firstMatch(in: output, range: range) != nil else { return }
            output = regex.stringByReplacingMatches(in: output, range: range, withTemplate: "[redacted:\(label)]")
            redactions.insert(label)
        }
        apply("[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}", label: "email")
        apply("(?<!\\d)(?:\\+?\\d[\\d\\s().-]{8,}\\d)(?!\\d)", label: "phone")
        apply("\\b(?:refresh_token|access_token|service_role|sb_secret|invite[_-]?token|token)\\s*[:=]\\s*[\"']?[A-Za-z0-9._~+/=-]{8,}", label: "token")
        apply("https://[a-z0-9-]+\\.supabase\\.co|eyJ[A-Za-z0-9_-]{20,}\\.[A-Za-z0-9_-]{20,}\\.[A-Za-z0-9_-]{10,}", label: "supabase")
        apply("(?:/Users/|/private/var/|file://)[^\\s)]+", label: "local_path")
        return RedactedText(value: output, redactions: redactions)
    }

    private static let aiSafeSchemaJSON = """
    {
      "schemaVersion": "ai-safe-v1",
      "fetchedBodyDefault": "excluded",
      "requiredFiles": ["manifest.json", "entries.jsonl", "schema.json", "README_FOR_AI.md", "redaction_report.json"],
      "entryRequiredFields": ["publicSafeId", "originalUrl", "normalizedUrl", "openUrl", "effectiveTitle", "recordState", "aiEligible", "sharedTagBoundary", "redactionApplied"],
      "privacyDefaults": {
        "sharedTags": "excluded_from_ai_by_default",
        "pendingDelete": "excluded_from_ai",
        "archived": "excluded_from_ai_by_default",
        "body": "summary_or_excerpt_only"
      }
    }
    """

    private static func buildReadmeForAi(manifest: ExportManifest, report: ExportRedactionReport) -> String {
        """
        # りんばむ AI-safe Export

        Generated at: \(manifest.generatedAt)
        App version: \(manifest.appVersion)
        Entry count: \(manifest.entryCount)

        This archive is intended for AI-assisted review of user-selected saved links.
        It is not a restore backup. Full fetched bodies are excluded by default.

        ## Files
        - `manifest.json`: export metadata and selected filters.
        - `entries.jsonl`: one AI-safe JSON document per saved link.
        - `entries/*.md`: readable Markdown summaries.
        - `schema.json`: compact schema contract.
        - `redaction_report.json`: redaction profile and counts.

        ## Privacy defaults
        - Shared-tag entries are marked `aiEligible=false` by default.
        - Pending-delete entries are marked `aiEligible=false`.
        - Archived entries are marked `aiEligible=false` unless a future explicit flow opts them in.
        - `bodyExcerpt` and `memoExcerpt` are capped at \(report.bodyExcerptMaxChars) and \(report.memoExcerptMaxChars) characters.
        - `savedSnapshotNotice` means title/author/summary/excerpt/thumbnail/metadata are saved-time data and may differ from the current live URL.
        - Raw fetched body, raw prompt text, tokens, local paths, and secrets are not intended to appear in this archive.
        """
    }
}

enum URLExportError: LocalizedError {
    case invalidRequest(String)
    case fileWriteFailed

    var errorDescription: String? {
        switch self {
        case .invalidRequest(let message): return message
        case .fileWriteFailed: return "エクスポートファイルを作成できませんでした。"
        }
    }
}

private struct ExportSourceEntry {
    let entry: URLRecord
    let tags: [ExportTagSummary]

    func matches(request: URLExportRequest, calendar: Calendar) -> Bool {
        let matchesScope: Bool
        switch request.scope {
        case .all:
            matchesScope = entry.localProvenanceCount > 0 || tags.contains { $0.scope == URLExportTagScope.synced.rawValue }
        case .sharedTagsOnly:
            matchesScope = tags.contains { $0.scope == URLExportTagScope.synced.rawValue }
        case .singleTag, .multipleTags:
            matchesScope = tags.contains { request.selectedTagIDs.contains($0.id) }
        }
        guard matchesScope else { return false }

        switch request.recordStateFilter {
        case .active where entry.recordState != .active:
            return false
        case .archived where entry.recordState != .archived:
            return false
        case .both where entry.recordState != .active && entry.recordState != .archived:
            return false
        default:
            break
        }

        if let serviceType = request.serviceType, entry.serviceType != serviceType {
            return false
        }
        if request.onlyWithMemo && entry.memo.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            return false
        }

        let createdDate = calendar.startOfDay(for: entry.createdAt)
        if let dateFrom = request.dateFrom, createdDate < calendar.startOfDay(for: dateFrom) {
            return false
        }
        if let dateTo = request.dateTo, createdDate > calendar.startOfDay(for: dateTo) {
            return false
        }
        return true
    }

    func toDocument() -> ExportEntryDocument {
        let hasSharedTag = tags.contains { $0.scope == URLExportTagScope.synced.rawValue }
        var exclusionReasons: [String] = []
        if hasSharedTag { exclusionReasons.append("shared_tag_default_excluded") }
        if entry.recordState == .archived { exclusionReasons.append("archived_default_excluded") }
        if entry.recordState == .pendingDelete || entry.pendingDeletionUntil != nil {
            exclusionReasons.append("pending_delete_excluded")
        }
        let externalURLPolicy = ExternalDataPolicy.inspect(url: entry.normalizedURL)
        exclusionReasons.append(contentsOf: externalURLPolicy.reasons.map { "external_data_\($0)" })
        let externalTextPolicy = ExternalDataPolicy.inspect(
            url: nil,
            texts: [
                entry.userTitle,
                entry.fetchedTitle,
                entry.fetchedAuthorName,
                entry.bodySummary,
                entry.fetchedBody,
                entry.description,
                entry.memo,
            ] + tags.map { $0.name }
        )
        let bodySummary = URLExportArchiveBuilder.redact(entry.bodySummary)
        let bodyExcerpt = URLExportArchiveBuilder.redact(
            entry.fetchedBody.map {
                URLExportArchiveBuilder.clipText($0, maxCharacters: URLExportArchiveBuilder.bodyExcerptMaxCharacters)
            }
        )
        let description = URLExportArchiveBuilder.redact(entry.description)
        let memoExcerpt = URLExportArchiveBuilder.redact(
            entry.memo.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? nil :
                URLExportArchiveBuilder.clipText(entry.memo, maxCharacters: URLExportArchiveBuilder.memoExcerptMaxCharacters)
        )
        let redactionApplied = Array(
            bodySummary.redactions
                .union(bodyExcerpt.redactions)
                .union(description.redactions)
                .union(memoExcerpt.redactions)
                .union(externalURLPolicy.reasons)
                .union(externalTextPolicy.reasons)
        ).sorted()
        let safeTags = tags.map { tag in
            ExportTagSummary(
                id: tag.id,
                name: ExternalDataPolicy.sanitizeText(tag.name) ?? "[redacted:tag]",
                scope: tag.scope
            )
        }

        return ExportEntryDocument(
            id: entry.id,
            publicSafeId: URLExportArchiveBuilder.publicSafeId(entryID: entry.id, normalizedURL: entry.normalizedURL),
            originalUrl: ExternalDataPolicy.safeURL(entry.originalURL) ?? ExternalDataPolicy.excludedURLMarker,
            normalizedUrl: ExternalDataPolicy.safeURL(entry.normalizedURL) ?? ExternalDataPolicy.excludedURLMarker,
            displayUrl: ExternalDataPolicy.safeURL(entry.displayURL) ?? ExternalDataPolicy.excludedURLMarker,
            openUrl: ExternalDataPolicy.safeURL(entry.openURL) ?? ExternalDataPolicy.excludedURLMarker,
            providerPermalink: ExternalDataPolicy.safeURL(URLExportArchiveBuilder.providerPermalink(for: entry)) ?? ExternalDataPolicy.excludedURLMarker,
            providerCanonicalId: ExternalDataPolicy.sanitizeText(entry.canonicalID),
            serviceType: entry.serviceType.rawValue.uppercased(),
            contentContext: entry.contentContext.rawValue.uppercased(),
            recordState: entry.recordState.rawValue,
            createdAt: URLExportArchiveBuilder.isoString(entry.createdAt),
            updatedAt: URLExportArchiveBuilder.isoString(entry.updatedAt),
            archivedAt: entry.archivedAt.map(URLExportArchiveBuilder.isoString(_:)),
            userTitle: ExternalDataPolicy.sanitizeText(entry.userTitle),
            fetchedTitle: ExternalDataPolicy.sanitizeText(entry.fetchedTitle),
            fetchedAuthorName: ExternalDataPolicy.sanitizeText(entry.fetchedAuthorName),
            fetchedBodyKind: entry.fetchedBodyKind?.rawValue.uppercased(),
            bodySummary: bodySummary.value,
            bodyExcerpt: bodyExcerpt.value,
            description: description.value,
            memoExcerpt: memoExcerpt.value,
            thumbnailUrl: ExternalDataPolicy.safeURL(entry.thumbnailURL),
            badgeImageUrl: ExternalDataPolicy.safeURL(entry.badgeImageURL),
            canonicalId: ExternalDataPolicy.sanitizeText(entry.canonicalID),
            normalizedHost: ExternalDataPolicy.sanitizeText(entry.normalizedHost) ?? "保存したリンク",
            rawSourceHost: ExternalDataPolicy.sanitizeText(entry.rawSourceHost) ?? "",
            metadataState: entry.metadataState.rawValue,
            metadataError: entry.metadataError?.rawValue,
            metadataFetchedAt: entry.metadataFetchedAt.map(URLExportArchiveBuilder.isoString(_:)),
            metadataSource: URLExportArchiveBuilder.metadataSource(for: entry),
            savedSnapshotNotice: URLExportArchiveBuilder.savedSnapshotNotice(for: entry),
            collection: nil,
            tags: safeTags.sorted { $0.name.localizedStandardCompare($1.name) == .orderedAscending },
            effectiveTitle: ExternalDataPolicy.sanitizeText(entry.effectiveTitle) ?? "保存したリンク",
            sharedTagBoundary: hasSharedTag ? "contains_shared_tag" : "local_or_untagged",
            aiEligible: Set(exclusionReasons).isEmpty,
            aiExclusionReason: Array(Set(exclusionReasons)).sorted(),
            redactionApplied: redactionApplied
        )
    }
}

private struct ExportManifest: Codable {
    let generatedAt: String
    let appVersion: String
    let entryCount: Int
    let exportScope: String
    let selectedTagIds: [String]
    let selectedTagNames: [String]
    let recordStateFilter: String
    let serviceFilter: String?
    let onlyWithMemo: Bool
    let dateFrom: String?
    let dateTo: String?
    let fields: [String]
}

private struct ExportJSONDocument: Codable {
    let manifest: ExportManifest
    let entries: [ExportEntryDocument]
    let readmeForAi: String
    let redactionReport: ExportRedactionReport
}

private struct ExportTagSummary: Codable {
    let id: String
    let name: String
    let scope: String
}

fileprivate struct ExportEntryDocument: Codable {
    let id: Int64
    let publicSafeId: String
    let originalUrl: String
    let normalizedUrl: String
    let displayUrl: String
    let openUrl: String
    let providerPermalink: String
    let providerCanonicalId: String?
    let serviceType: String
    let contentContext: String
    let recordState: String
    let createdAt: String
    let updatedAt: String
    let archivedAt: String?
    let userTitle: String?
    let fetchedTitle: String?
    let fetchedAuthorName: String?
    let fetchedBodyKind: String?
    let bodySummary: String?
    let bodyExcerpt: String?
    let description: String?
    let memoExcerpt: String?
    let thumbnailUrl: String?
    let badgeImageUrl: String?
    let canonicalId: String?
    let normalizedHost: String
    let rawSourceHost: String
    let metadataState: String
    let metadataError: String?
    let metadataFetchedAt: String?
    let metadataSource: String
    let savedSnapshotNotice: String?
    let collection: String?
    let tags: [ExportTagSummary]
    let effectiveTitle: String
    let sharedTagBoundary: String
    let aiEligible: Bool
    let aiExclusionReason: [String]
    let redactionApplied: [String]

    func toMarkdown() -> String {
        var lines: [String] = []
        lines.append("# \(effectiveTitle)")
        lines.append("")
        lines.append("- Public Safe ID: \(publicSafeId)")
        lines.append("- URL: \(normalizedUrl)")
        lines.append("- Original URL: \(originalUrl)")
        lines.append("- Display URL: \(displayUrl)")
        lines.append("- Open URL: \(openUrl)")
        lines.append("- Provider Permalink: \(providerPermalink)")
        if let providerCanonicalId { lines.append("- Provider Canonical ID: \(providerCanonicalId)") }
        lines.append("- Service: \(serviceType)")
        lines.append("- Context: \(contentContext)")
        lines.append("- State: \(recordState)")
        lines.append("- Created At: \(createdAt)")
        lines.append("- Updated At: \(updatedAt)")
        if let archivedAt { lines.append("- Archived At: \(archivedAt)") }
        if let collection { lines.append("- Collection: \(collection)") }
        if tags.isEmpty {
            lines.append("- Tags: none")
        } else {
            lines.append("- Tags: \(tags.map { "\($0.name) (\($0.scope))" }.joined(separator: ", "))")
        }
        if let userTitle { lines.append("- User Title: \(userTitle)") }
        if let fetchedTitle { lines.append("- Fetched Title: \(fetchedTitle)") }
        if let fetchedAuthorName { lines.append("- Author: \(fetchedAuthorName)") }
        if let fetchedBodyKind { lines.append("- Body Kind: \(fetchedBodyKind)") }
        if let bodySummary { lines.append("- Summary: \(bodySummary)") }
        if let bodyExcerpt { lines.append("- Body Excerpt: \(bodyExcerpt)") }
        if let description { lines.append("- Description: \(description)") }
        if let memoExcerpt { lines.append("- Memo Excerpt: \(memoExcerpt)") }
        if let thumbnailUrl { lines.append("- Thumbnail URL: \(thumbnailUrl)") }
        if let badgeImageUrl { lines.append("- Badge Image URL: \(badgeImageUrl)") }
        if let canonicalId { lines.append("- Canonical ID: \(canonicalId)") }
        lines.append("- Metadata State: \(metadataState)")
        if let metadataError { lines.append("- Metadata Error: \(metadataError)") }
        if let metadataFetchedAt { lines.append("- Metadata Fetched At: \(metadataFetchedAt)") }
        lines.append("- Metadata Source: \(metadataSource)")
        if let savedSnapshotNotice { lines.append("- Saved Snapshot Notice: \(savedSnapshotNotice)") }
        lines.append("- Normalized Host: \(normalizedHost)")
        lines.append("- Raw Source Host: \(rawSourceHost)")
        lines.append("- Shared Tag Boundary: \(sharedTagBoundary)")
        lines.append("- AI Eligible: \(aiEligible)")
        if !aiExclusionReason.isEmpty {
            lines.append("- AI Exclusion Reason: \(aiExclusionReason.joined(separator: ", "))")
        }
        if !redactionApplied.isEmpty {
            lines.append("- Redaction Note: \(redactionApplied.joined(separator: ", "))")
        }
        return lines.joined(separator: "\n")
    }
}

fileprivate struct RedactedText {
    let value: String?
    let redactions: Set<String>
}

fileprivate struct ExportRedactionReport: Codable {
    let generatedAt: String
    let profile: String
    let fetchedBodyExported: Bool
    let bodyExcerptMaxChars: Int
    let memoExcerptMaxChars: Int
    let entryCount: Int
    let redactedEntryCount: Int
    let redactionTypes: [String: Int]

    static func from(generatedAt: String, documents: [ExportEntryDocument]) -> ExportRedactionReport {
        var counts: [String: Int] = [:]
        for redaction in documents.flatMap(\.redactionApplied) {
            counts[redaction, default: 0] += 1
        }
        return ExportRedactionReport(
            generatedAt: generatedAt,
            profile: "ai-safe-v1",
            fetchedBodyExported: false,
            bodyExcerptMaxChars: URLExportArchiveBuilder.bodyExcerptMaxCharacters,
            memoExcerptMaxChars: URLExportArchiveBuilder.memoExcerptMaxCharacters,
            entryCount: documents.count,
            redactedEntryCount: documents.filter { !$0.redactionApplied.isEmpty }.count,
            redactionTypes: counts
        )
    }
}

private struct SimpleZipWriter {
    private struct CentralDirectoryEntry {
        let path: String
        let crc32: UInt32
        let size: UInt32
        let localHeaderOffset: UInt32
    }

    private var data = Data()
    private var entries: [CentralDirectoryEntry] = []

    mutating func addFile(path: String, data fileData: Data) {
        let pathData = Data(path.utf8)
        let crc = CRC32.checksum(fileData)
        let size = UInt32(fileData.count)
        let offset = UInt32(data.count)

        appendUInt32(0x04034b50)
        appendUInt16(20)
        appendUInt16(0x0800)
        appendUInt16(0)
        appendUInt16(0)
        appendUInt16(0)
        appendUInt32(crc)
        appendUInt32(size)
        appendUInt32(size)
        appendUInt16(UInt16(pathData.count))
        appendUInt16(0)
        data.append(pathData)
        data.append(fileData)

        entries.append(CentralDirectoryEntry(path: path, crc32: crc, size: size, localHeaderOffset: offset))
    }

    mutating func finalize() -> Data {
        let centralDirectoryOffset = UInt32(data.count)
        for entry in entries {
            let pathData = Data(entry.path.utf8)
            appendUInt32(0x02014b50)
            appendUInt16(20)
            appendUInt16(20)
            appendUInt16(0x0800)
            appendUInt16(0)
            appendUInt16(0)
            appendUInt16(0)
            appendUInt32(entry.crc32)
            appendUInt32(entry.size)
            appendUInt32(entry.size)
            appendUInt16(UInt16(pathData.count))
            appendUInt16(0)
            appendUInt16(0)
            appendUInt16(0)
            appendUInt16(0)
            appendUInt32(0)
            appendUInt32(entry.localHeaderOffset)
            data.append(pathData)
        }
        let centralDirectorySize = UInt32(data.count) - centralDirectoryOffset
        appendUInt32(0x06054b50)
        appendUInt16(0)
        appendUInt16(0)
        appendUInt16(UInt16(entries.count))
        appendUInt16(UInt16(entries.count))
        appendUInt32(centralDirectorySize)
        appendUInt32(centralDirectoryOffset)
        appendUInt16(0)
        return data
    }

    private mutating func appendUInt16(_ value: UInt16) {
        var littleEndian = value.littleEndian
        withUnsafeBytes(of: &littleEndian) { data.append(contentsOf: $0) }
    }

    private mutating func appendUInt32(_ value: UInt32) {
        var littleEndian = value.littleEndian
        withUnsafeBytes(of: &littleEndian) { data.append(contentsOf: $0) }
    }
}

private enum CRC32 {
    private static let table: [UInt32] = (0..<256).map { value in
        var crc = UInt32(value)
        for _ in 0..<8 {
            if crc & 1 == 1 {
                crc = 0xedb88320 ^ (crc >> 1)
            } else {
                crc >>= 1
            }
        }
        return crc
    }

    static func checksum(_ data: Data) -> UInt32 {
        var crc: UInt32 = 0xffffffff
        for byte in data {
            let index = Int((crc ^ UInt32(byte)) & 0xff)
            crc = table[index] ^ (crc >> 8)
        }
        return crc ^ 0xffffffff
    }
}
