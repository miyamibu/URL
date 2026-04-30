import Foundation

actor ShareHandoffStore {
    private let fileURL: URL
    private let encoder = JSONEncoder()
    private let decoder = JSONDecoder()

    init(fileURL: URL = SharedContainer.handoffReportURL()) {
        self.fileURL = fileURL
        encoder.dateEncodingStrategy = .iso8601
        decoder.dateDecodingStrategy = .iso8601
    }

    func write(_ report: ShareHandoffReport) throws {
        let data = try encoder.encode(report)
        try data.write(to: fileURL, options: .atomic)
    }

    func consume() throws -> ShareHandoffReport? {
        guard FileManager.default.fileExists(atPath: fileURL.path) else { return nil }
        let data = try Data(contentsOf: fileURL)
        try FileManager.default.removeItem(at: fileURL)
        return try decoder.decode(ShareHandoffReport.self, from: data)
    }
}
