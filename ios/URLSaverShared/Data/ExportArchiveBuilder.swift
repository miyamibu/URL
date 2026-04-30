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
                now: now
            )
        case .json:
            return try buildJSONExport(
                manifest: manifest,
                documents: documents,
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
        now: Date
    ) throws -> PreparedExportArchive {
        var zip = SimpleZipWriter()
        zip.addFile(path: "manifest.json", data: try prettyEncoder().encode(manifest))
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
        now: Date
    ) throws -> PreparedExportArchive {
        let payload = ExportJSONDocument(manifest: manifest, entries: documents)
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
        "originalUrl",
        "normalizedUrl",
        "displayUrl",
        "openUrl",
        "serviceType",
        "contentContext",
        "recordState",
        "createdAt",
        "updatedAt",
        "archivedAt",
        "userTitle",
        "fetchedTitle",
        "fetchedBody",
        "bodySummary",
        "description",
        "memo",
        "thumbnailUrl",
        "badgeImageUrl",
        "canonicalId",
        "normalizedHost",
        "rawSourceHost",
        "metadataState",
        "metadataError",
        "collection",
        "tags",
        "tagScopes",
    ]
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
        ExportEntryDocument(
            id: entry.id,
            originalUrl: entry.originalURL,
            normalizedUrl: entry.normalizedURL,
            displayUrl: entry.displayURL,
            openUrl: entry.openURL,
            serviceType: entry.serviceType.rawValue.uppercased(),
            contentContext: entry.contentContext.rawValue.uppercased(),
            recordState: entry.recordState.rawValue,
            createdAt: URLExportArchiveBuilder.isoString(entry.createdAt),
            updatedAt: URLExportArchiveBuilder.isoString(entry.updatedAt),
            archivedAt: entry.archivedAt.map(URLExportArchiveBuilder.isoString(_:)),
            userTitle: entry.userTitle,
            fetchedTitle: entry.fetchedTitle,
            fetchedBody: entry.fetchedBody,
            bodySummary: entry.bodySummary,
            description: entry.description,
            memo: entry.memo,
            thumbnailUrl: entry.thumbnailURL,
            badgeImageUrl: entry.badgeImageURL,
            canonicalId: entry.canonicalID,
            normalizedHost: entry.normalizedHost,
            rawSourceHost: entry.rawSourceHost,
            metadataState: entry.metadataState.rawValue,
            metadataError: entry.metadataError?.rawValue,
            collection: nil,
            tags: tags.sorted { $0.name.localizedStandardCompare($1.name) == .orderedAscending },
            effectiveTitle: entry.effectiveTitle
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
}

private struct ExportTagSummary: Codable {
    let id: String
    let name: String
    let scope: String
}

private struct ExportEntryDocument: Codable {
    let id: Int64
    let originalUrl: String
    let normalizedUrl: String
    let displayUrl: String
    let openUrl: String
    let serviceType: String
    let contentContext: String
    let recordState: String
    let createdAt: String
    let updatedAt: String
    let archivedAt: String?
    let userTitle: String?
    let fetchedTitle: String?
    let fetchedBody: String?
    let bodySummary: String?
    let description: String?
    let memo: String
    let thumbnailUrl: String?
    let badgeImageUrl: String?
    let canonicalId: String?
    let normalizedHost: String
    let rawSourceHost: String
    let metadataState: String
    let metadataError: String?
    let collection: String?
    let tags: [ExportTagSummary]
    let effectiveTitle: String

    func toMarkdown() -> String {
        var lines: [String] = []
        lines.append("# \(effectiveTitle)")
        lines.append("")
        lines.append("- URL: \(normalizedUrl)")
        lines.append("- Original URL: \(originalUrl)")
        lines.append("- Display URL: \(displayUrl)")
        lines.append("- Open URL: \(openUrl)")
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
        if let bodySummary { lines.append("- Summary: \(bodySummary)") }
        if let description { lines.append("- Description: \(description)") }
        if !memo.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            lines.append("- Memo: \(memo)")
        }
        if let thumbnailUrl { lines.append("- Thumbnail URL: \(thumbnailUrl)") }
        if let badgeImageUrl { lines.append("- Badge Image URL: \(badgeImageUrl)") }
        if let canonicalId { lines.append("- Canonical ID: \(canonicalId)") }
        lines.append("- Metadata State: \(metadataState)")
        if let metadataError { lines.append("- Metadata Error: \(metadataError)") }
        lines.append("- Normalized Host: \(normalizedHost)")
        lines.append("- Raw Source Host: \(rawSourceHost)")
        if let fetchedBody, !fetchedBody.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            lines.append("")
            lines.append("## Body")
            lines.append("")
            lines.append(fetchedBody)
        }
        return lines.joined(separator: "\n")
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
