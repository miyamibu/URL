import Foundation

enum SharedContainer {
    static let appGroupIdentifier = "group.jp.mimac.urlsaver"

    static func hasAppGroupAccess() -> Bool {
        FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: appGroupIdentifier) != nil
    }

    static func baseURL() -> URL {
        if let appGroupURL = FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: appGroupIdentifier) {
            return appGroupURL
        }

        let support = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first
            ?? FileManager.default.temporaryDirectory
        let fallback = support.appendingPathComponent("URLSaveriOS", isDirectory: true)
        try? FileManager.default.createDirectory(at: fallback, withIntermediateDirectories: true)
        return fallback
    }

    static func databaseURL() -> URL {
        let directory = baseURL().appendingPathComponent("Database", isDirectory: true)
        try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        return directory.appendingPathComponent("url_saver_ios.sqlite")
    }

    static func handoffReportURL() -> URL {
        let directory = baseURL().appendingPathComponent("ShareHandoff", isDirectory: true)
        try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        return directory.appendingPathComponent("latest-share-report.json")
    }
}
