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
    }

    func testOutputFormatListOnlyExposesZipAndJson() {
        XCTAssertEqual(URLExportOutputFormat.allCases, [.zip, .json])
        let rawValues = Set(URLExportOutputFormat.allCases.map(\.rawValue))
        XCTAssertEqual(rawValues, Set(["ZIP", "JSON"]))
        XCTAssertFalse(rawValues.contains("CSV"))
        XCTAssertFalse(rawValues.contains("HTML"))
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

    private func makeRecord(id: Int64, host: String, memo: String = "") -> URLSaveriOS.URLRecord {
        URLSaveriOS.URLRecord(
            id: id,
            originalURL: "https://\(host)/",
            normalizedURL: "https://\(host)/",
            displayURL: "\(host)/",
            openURL: "https://\(host)/",
            normalizedHost: host,
            rawSourceHost: host,
            collectionID: 1,
            serviceType: .web,
            contentContext: .standard,
            userTitle: nil,
            fetchedTitle: nil,
            fetchedBody: nil,
            fetchedBodyKind: nil,
            bodySummary: nil,
            description: nil,
            memo: memo,
            thumbnailURL: nil,
            badgeImageURL: nil,
            canonicalID: nil,
            metadataState: .ready,
            metadataError: nil,
            metadataRequestedAt: nil,
            metadataFetchedAt: nil,
            recordState: .active,
            localProvenanceCount: 1,
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
