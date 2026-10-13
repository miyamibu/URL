import Foundation

struct PendingShareHandoff: Sendable {
    let report: ShareHandoffReport
    fileprivate let fileURL: URL
    fileprivate let data: Data
}

enum ShareHandoffStoreError: Error {
    case operationConflict
}

actor ShareHandoffStore {
    private let fileURL: URL
    private let queueURL: URL
    private let encoder = JSONEncoder()
    private let decoder = JSONDecoder()

    init(fileURL: URL = SharedContainer.handoffReportURL()) {
        self.fileURL = fileURL
        self.queueURL = fileURL.deletingLastPathComponent().appendingPathComponent("share-handoff-queue", isDirectory: true)
        encoder.outputFormatting = .sortedKeys
        encoder.dateEncodingStrategy = .iso8601
        decoder.dateDecodingStrategy = .iso8601
    }

    func write(_ report: ShareHandoffReport) throws {
        let data = try encoder.encode(report)
        try FileManager.default.createDirectory(at: queueURL, withIntermediateDirectories: true)
        let target = queueURL.appendingPathComponent("\((report.operationID ?? UUID()).uuidString).json")
        if FileManager.default.fileExists(atPath: target.path) {
            guard try Data(contentsOf: target) == data else { throw ShareHandoffStoreError.operationConflict }
            return
        }
        // Publish complete bytes with an exclusive link; another process cannot replace this operation.
        let staging = queueURL.appendingPathComponent("\(UUID().uuidString).pending")
        try data.write(to: staging, options: .atomic)
        defer { try? FileManager.default.removeItem(at: staging) }
        do {
            try FileManager.default.linkItem(at: staging, to: target)
        } catch {
            if FileManager.default.fileExists(atPath: target.path) {
                guard try Data(contentsOf: target) == data else { throw ShareHandoffStoreError.operationConflict }
                return
            }
            throw error
        }
    }

    func pendingReports() throws -> [PendingShareHandoff] {
        var urls: [URL] = []
        if FileManager.default.fileExists(atPath: fileURL.path) { urls.append(fileURL) }
        if FileManager.default.fileExists(atPath: queueURL.path) {
            urls += try FileManager.default.contentsOfDirectory(at: queueURL, includingPropertiesForKeys: nil)
                .filter { $0.pathExtension == "json" }
        }
        // Decode every snapshot before any acknowledgement; unreadable reports remain on disk.
        return try urls.map { url in
            let data = try Data(contentsOf: url)
            return PendingShareHandoff(report: try decoder.decode(ShareHandoffReport.self, from: data), fileURL: url, data: data)
        }.sorted { $0.report.createdAt < $1.report.createdAt }
    }

    func consume() throws -> ShareHandoffReport? {
        try pendingReports().first?.report
    }

    @discardableResult
    func acknowledge(_ pending: PendingShareHandoff) throws -> Bool {
        guard try matchesSnapshot(pending) else { return false }
        try FileManager.default.removeItem(at: pending.fileURL)
        return true
    }

    @discardableResult
    func retain(_ pending: PendingShareHandoff, assignments: [ShareHandoffAssignment]) throws -> Bool {
        guard try matchesSnapshot(pending) else { return false }
        var report = pending.report
        report.pendingSharedTagAssignments = assignments
        try encoder.encode(report).write(to: pending.fileURL, options: .atomic)
        return true
    }

    private func matchesSnapshot(_ pending: PendingShareHandoff) throws -> Bool {
        guard FileManager.default.fileExists(atPath: pending.fileURL.path) else { return false }
        return try Data(contentsOf: pending.fileURL) == pending.data
    }
}
