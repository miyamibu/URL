import Foundation
import XCTest
@testable import URLSaveriOS

final class ExportArchiveBuilderTests: XCTestCase {
    func testZipOutputUsesZipExtensionAndContainsExpectedFiles() throws {
        let archive = try URLSaveriOS.URLExportArchiveBuilder.prepareExport(
            request: makeRequest(outputFormat: .zip),
            entries: [makeRecord(id: 42, host: "example.com", memo: "memo")],
            localTags: [],
            localTagAssignments: [:],
            sharedTagsByEntryID: [:],
            appVersion: "test",
            now: Date(timeIntervalSince1970: 1_714_000_000)
        )

        XCTAssertTrue(archive.fileName.hasSuffix(".zip"))
        XCTAssertEqual(archive.mimeType, "application/zip")

        let files = try extractZIPFiles(from: archive.bytes)
        XCTAssertNotNil(files["manifest.json"])
        XCTAssertNotNil(files["entries.jsonl"])
        XCTAssertNotNil(files["schema.json"])
        XCTAssertNotNil(files["README_FOR_AI.md"])
        XCTAssertNotNil(files["redaction_report.json"])
        XCTAssertTrue(files.keys.contains(where: { $0.hasPrefix("entries/") && $0.hasSuffix(".md") }))
    }

    func testJSONOutputUsesJsonExtensionAndContainsManifestAndEntries() throws {
        let archive = try URLSaveriOS.URLExportArchiveBuilder.prepareExport(
            request: makeRequest(outputFormat: .json),
            entries: [makeRecord(id: 7, host: "example.com")],
            localTags: [],
            localTagAssignments: [:],
            sharedTagsByEntryID: [:],
            appVersion: "test",
            now: Date(timeIntervalSince1970: 1_714_000_000)
        )

        XCTAssertTrue(archive.fileName.hasSuffix(".json"))
        XCTAssertEqual(archive.mimeType, "application/json")

        let payload = try XCTUnwrap(JSONSerialization.jsonObject(with: archive.bytes) as? [String: Any])
        let manifest = try XCTUnwrap(payload["manifest"] as? [String: Any])
        let entries = try XCTUnwrap(payload["entries"] as? [[String: Any]])

        XCTAssertEqual(manifest["entryCount"] as? Int, 1)
        XCTAssertEqual(entries.count, 1)
        XCTAssertEqual(entries.first?["normalizedUrl"] as? String, "https://example.com/")
        XCTAssertNotNil(payload["readmeForAi"] as? String)
        XCTAssertNotNil(payload["redactionReport"] as? [String: Any])
    }

    func testZipOutputIsAiSafeAndDoesNotExportRawFetchedBody() throws {
        let archive = try URLSaveriOS.URLExportArchiveBuilder.prepareExport(
            request: makeRequest(outputFormat: .zip),
            entries: [
                makeRecord(
                    id: 12,
                    host: "x.com",
                    memo: "memo path /Users/mimac/private.txt",
                    serviceType: .x,
                    fetchedAuthorName: "Author",
                    fetchedBody: "Email alice@example.com token=abcdef1234567890. This is raw body.",
                    fetchedBodyKind: .xPostText,
                    bodySummary: "Summary alice@example.com",
                    canonicalID: "123456789",
                    metadataFetchedAt: Date(timeIntervalSince1970: 1_714_000_100)
                )
            ],
            localTags: [],
            localTagAssignments: [:],
            sharedTagsByEntryID: [:],
            appVersion: "test",
            now: Date(timeIntervalSince1970: 1_714_000_000)
        )

        let files = try extractZIPFiles(from: archive.bytes)
        let entriesText = try XCTUnwrap(String(data: try XCTUnwrap(files["entries.jsonl"]), encoding: .utf8))
        let entry = try XCTUnwrap(JSONSerialization.jsonObject(with: Data(entriesText.utf8)) as? [String: Any])
        let entryMarkdownData = try XCTUnwrap(
            files.first { path, _ in path.hasPrefix("entries/") && path.hasSuffix(".md") }?.value
        )
        let markdown = try XCTUnwrap(String(data: entryMarkdownData, encoding: .utf8))

        XCTAssertEqual(entry["aiEligible"] as? Bool, true)
        XCTAssertEqual(entry["fetchedAuthorName"] as? String, "Author")
        XCTAssertEqual(entry["fetchedBodyKind"] as? String, "X_POST_TEXT")
        XCTAssertEqual(entry["providerPermalink"] as? String, "https://x.com/i/web/status/123456789")
        XCTAssertEqual(
            entry["savedSnapshotNotice"] as? String,
            "保存時点の情報であり、現在の内容とは異なる可能性があります"
        )
        XCTAssertNil(entry["fetchedBody"])
        XCTAssertFalse(entriesText.contains("alice@example.com"))
        XCTAssertFalse(markdown.contains("## Body"))
        XCTAssertTrue(markdown.contains("Author: Author"))
        XCTAssertTrue(markdown.contains("Body Kind: X_POST_TEXT"))
        XCTAssertTrue(markdown.contains("Saved Snapshot Notice:"))
        XCTAssertTrue(markdown.contains("Redaction Note:"))
        XCTAssertTrue(markdown.contains("email"))
        XCTAssertTrue(markdown.contains("local_path"))
        XCTAssertTrue(markdown.contains("token"))
    }

    func testSharedTagEntryIsMarkedAiIneligibleByDefault() throws {
        let shared = URLSaveriOS.SharedTagSummary(
            remoteTagID: "remote-tag",
            name: "共有",
            currentUserRole: .owner,
            activeURLCount: 1,
            lastSyncedAt: Date(timeIntervalSince1970: 1_714_000_000)
        )
        let archive = try URLSaveriOS.URLExportArchiveBuilder.prepareExport(
            request: URLSaveriOS.URLExportRequest(
                scope: .sharedTagsOnly,
                selectedTagIDs: [],
                recordStateFilter: .both,
                serviceType: nil,
                onlyWithMemo: false,
                dateFrom: nil,
                dateTo: nil,
                outputFormat: .json
            ),
            entries: [makeRecord(id: 33, host: "example.com", localProvenanceCount: 0)],
            localTags: [],
            localTagAssignments: [:],
            sharedTagsByEntryID: [33: [shared]],
            appVersion: "test"
        )

        let payload = try XCTUnwrap(JSONSerialization.jsonObject(with: archive.bytes) as? [String: Any])
        let entries = try XCTUnwrap(payload["entries"] as? [[String: Any]])
        let entry = try XCTUnwrap(entries.first)
        XCTAssertEqual(entry["aiEligible"] as? Bool, false)
        XCTAssertTrue((entry["aiExclusionReason"] as? [String])?.contains("shared_tag_default_excluded") == true)
    }

    func testOutputFormatListOnlyExposesZipAndJson() {
        XCTAssertEqual(URLExportOutputFormat.allCases, [.zip, .json])
        let rawValues = Set(URLExportOutputFormat.allCases.map(\.rawValue))
        XCTAssertEqual(rawValues, Set(["ZIP", "JSON"]))
        XCTAssertFalse(rawValues.contains("CSV"))
        XCTAssertFalse(rawValues.contains("HTML"))
    }

    func testExportTodayDateInputFormatsProvidedDateWithoutStaleFixtureDate() throws {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = try XCTUnwrap(TimeZone(secondsFromGMT: 0))
        let date = try XCTUnwrap(DateComponents(
            calendar: calendar,
            timeZone: calendar.timeZone,
            year: 2026,
            month: 7,
            day: 9
        ).date)

        XCTAssertEqual(exportTodayDateInput(now: date, calendar: calendar), "2026-07-09")

        let source = try String(contentsOf: exportSheetSourceURL(), encoding: .utf8)
        XCTAssertFalse(source.contains("2026-04-30"))
    }

    func testExportSheetSourceDoesNotContainLegacyCSVHtmlOrCopyOptions() throws {
        let source = try String(contentsOf: exportSheetSourceURL(), encoding: .utf8)

        XCTAssertNil(source.range(of: #"\bcase\s+csv\b"#, options: .regularExpression))
        XCTAssertNil(source.range(of: #"\bcase\s+html\b"#, options: .regularExpression))
        XCTAssertNil(source.range(of: #"\bcase\s+copy\b"#, options: .regularExpression))
    }

    private func makeRequest(outputFormat: URLSaveriOS.URLExportOutputFormat) -> URLSaveriOS.URLExportRequest {
        URLSaveriOS.URLExportRequest(
            scope: .all,
            selectedTagIDs: [],
            recordStateFilter: .both,
            serviceType: nil,
            onlyWithMemo: false,
            dateFrom: nil,
            dateTo: nil,
            outputFormat: outputFormat
        )
    }

    private func makeRecord(
        id: Int64,
        host: String,
        memo: String = "",
        serviceType: URLSaveriOS.ServiceType = .web,
        localProvenanceCount: Int = 1,
        fetchedAuthorName: String? = nil,
        fetchedBody: String? = nil,
        fetchedBodyKind: URLSaveriOS.MetadataBodyKind? = nil,
        bodySummary: String? = nil,
        canonicalID: String? = nil,
        metadataFetchedAt: Date? = nil
    ) -> URLSaveriOS.URLRecord {
        URLSaveriOS.URLRecord(
            id: id,
            originalURL: "https://\(host)/",
            normalizedURL: "https://\(host)/",
            displayURL: "\(host)/",
            openURL: "https://\(host)/",
            normalizedHost: host,
            rawSourceHost: host,
            collectionID: 1,
            serviceType: serviceType,
            contentContext: .standard,
            userTitle: nil,
            fetchedTitle: nil,
            fetchedAuthorName: fetchedAuthorName,
            fetchedBody: fetchedBody,
            fetchedBodyKind: fetchedBodyKind,
            bodySummary: bodySummary,
            description: nil,
            memo: memo,
            thumbnailURL: nil,
            badgeImageURL: nil,
            canonicalID: canonicalID,
            metadataState: .ready,
            metadataError: nil,
            metadataRequestedAt: nil,
            metadataFetchedAt: metadataFetchedAt,
            recordState: .active,
            localProvenanceCount: localProvenanceCount,
            sharedReferenceCount: 0,
            createdAt: .distantPast,
            updatedAt: .distantPast,
            archivedAt: nil,
            pendingDeletionUntil: nil
        )
    }

    private func exportSheetSourceURL() -> URL {
        URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent()
            .deletingLastPathComponent()
            .appendingPathComponent("URLSaveriOS/UI/ExportSheet.swift")
    }

    private func extractZIPFiles(from data: Data) throws -> [String: Data] {
        var offset = 0
        var files: [String: Data] = [:]

        while offset + 4 <= data.count {
            let signature = try readUInt32LE(from: data, at: offset)
            if signature == 0x02014b50 || signature == 0x06054b50 {
                break
            }
            guard signature == 0x04034b50 else {
                throw ZIPParseError.invalidSignature(signature)
            }
            guard offset + 30 <= data.count else {
                throw ZIPParseError.truncatedHeader
            }

            let compressedSize = Int(try readUInt32LE(from: data, at: offset + 18))
            let fileNameLength = Int(try readUInt16LE(from: data, at: offset + 26))
            let extraFieldLength = Int(try readUInt16LE(from: data, at: offset + 28))

            let fileNameStart = offset + 30
            let fileNameEnd = fileNameStart + fileNameLength
            let payloadStart = fileNameEnd + extraFieldLength
            let payloadEnd = payloadStart + compressedSize
            guard payloadEnd <= data.count else {
                throw ZIPParseError.truncatedPayload
            }

            let path = String(decoding: data[fileNameStart..<fileNameEnd], as: UTF8.self)
            files[path] = Data(data[payloadStart..<payloadEnd])
            offset = payloadEnd
        }
        return files
    }

    private func readUInt16LE(from data: Data, at offset: Int) throws -> UInt16 {
        guard offset + 2 <= data.count else {
            throw ZIPParseError.truncatedHeader
        }
        let b0 = UInt16(data[offset])
        let b1 = UInt16(data[offset + 1]) << 8
        return b0 | b1
    }

    private func readUInt32LE(from data: Data, at offset: Int) throws -> UInt32 {
        guard offset + 4 <= data.count else {
            throw ZIPParseError.truncatedHeader
        }
        let b0 = UInt32(data[offset])
        let b1 = UInt32(data[offset + 1]) << 8
        let b2 = UInt32(data[offset + 2]) << 16
        let b3 = UInt32(data[offset + 3]) << 24
        return b0 | b1 | b2 | b3
    }
}

private enum ZIPParseError: Error {
    case invalidSignature(UInt32)
    case truncatedHeader
    case truncatedPayload
}
