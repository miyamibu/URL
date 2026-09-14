import Foundation

enum SharedContainer {
    static let appGroupIdentifier = "group.jp.mimac.urlsaver"

    static func hasAppGroupAccess() -> Bool {
        guard !isRunningXCTest else { return false }
        return FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: appGroupIdentifier) != nil
    }

    static func baseURL() -> URL {
        if isRunningXCTest {
            return fallbackBaseURL()
        }
        if let appGroupURL = FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: appGroupIdentifier) {
            return appGroupURL
        }

        return fallbackBaseURL()
    }

    private static var isRunningXCTest: Bool {
        ProcessInfo.processInfo.environment["XCTestConfigurationFilePath"] != nil
    }

    private static func fallbackBaseURL() -> URL {
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

    static func recoveryDatabaseURL() -> URL {
        let directory = baseURL().appendingPathComponent("Database", isDirectory: true)
        try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        return directory.appendingPathComponent("url_saver_ios.recovery.sqlite")
    }

    static func handoffReportURL() -> URL {
        let directory = baseURL().appendingPathComponent("ShareHandoff", isDirectory: true)
        try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        return directory.appendingPathComponent("latest-share-report.json")
    }

    static func pendingShareOperationURL() -> URL {
        let directory = baseURL().appendingPathComponent("SharePending", isDirectory: true)
        try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        return directory.appendingPathComponent("pending-share-operation.json")
    }
}

struct ShareExtensionPendingOperation: Codable, Equatable, Sendable {
    static let currentVersion = 1
    static let recoveryWindow: TimeInterval = 15 * 60

    let version: Int
    let operationID: UUID
    let createdAt: Date
    var updatedAt: Date
    let originalURLs: [String]
    let memo: String?
    let degradationNotice: ShareDegradationNotice?
    var selectedTagIDs: [Int64]
    var tagSelectionLockedAt: Date?
    var pendingItems: [ShareExtensionPendingItem]
    var completedItems: [ShareExtensionCompletedItem]

    init(
        operationID: UUID = UUID(),
        createdAt: Date = Date(),
        updatedAt: Date = Date(),
        originalURLs: [String],
        memo: String?,
        degradationNotice: ShareDegradationNotice?,
        selectedTagIDs: [Int64] = [],
        tagSelectionLockedAt: Date? = nil,
        pendingItems: [ShareExtensionPendingItem]? = nil,
        completedItems: [ShareExtensionCompletedItem] = []
    ) {
        self.version = Self.currentVersion
        self.operationID = operationID
        self.createdAt = createdAt
        self.updatedAt = updatedAt
        self.originalURLs = originalURLs
        self.memo = memo
        self.degradationNotice = degradationNotice
        self.selectedTagIDs = Array(Set(selectedTagIDs)).sorted()
        self.tagSelectionLockedAt = tagSelectionLockedAt
        self.pendingItems = pendingItems ?? originalURLs.map { ShareExtensionPendingItem(url: $0) }
        self.completedItems = completedItems
    }

    func matches(urls: [String], memo: String?, degradationNotice: ShareDegradationNotice?) -> Bool {
        originalURLs == urls && self.memo == memo && self.degradationNotice == degradationNotice
    }

    var isTagSelectionLocked: Bool {
        tagSelectionLockedAt != nil
    }

    mutating func lockTagSelection(_ tagIDs: Set<Int64>, at now: Date = Date()) {
        guard tagSelectionLockedAt == nil else { return }
        selectedTagIDs = tagIDs.sorted()
        tagSelectionLockedAt = now
        updatedAt = now
    }

    mutating func recordAttempt(
        for item: ShareExtensionPendingItem,
        retryItem: ShareExtensionPendingItem?,
        completed newCompletedItems: [ShareExtensionCompletedItem],
        at now: Date = Date()
    ) {
        guard let pendingIndex = pendingItems.firstIndex(where: { $0.url == item.url }) else { return }
        if let retryItem {
            pendingItems[pendingIndex] = retryItem
        } else {
            pendingItems.remove(at: pendingIndex)
        }
        for completedItem in newCompletedItems {
            completedItems.removeAll { $0.url == completedItem.url }
            completedItems.append(completedItem)
        }
        updatedAt = now
    }

    func isRecoverable(at now: Date = Date()) -> Bool {
        guard version == Self.currentVersion,
              !pendingItems.isEmpty,
              now.timeIntervalSince(updatedAt) >= 0,
              now.timeIntervalSince(updatedAt) <= Self.recoveryWindow else {
            return false
        }
        let originalSet = Set(originalURLs)
        let pendingURLs = pendingItems.map(\.url)
        let completedURLs = completedItems.map(\.url)
        return !originalURLs.isEmpty &&
            originalURLs.count <= URLRules.maxBatchSaveURLsPerIntake &&
            Set(pendingURLs).count == pendingURLs.count &&
            Set(completedURLs).count == completedURLs.count &&
            Set(pendingURLs).isSubset(of: originalSet) &&
            Set(completedURLs).isSubset(of: originalSet) &&
            Set(pendingURLs).isDisjoint(with: Set(completedURLs)) &&
            originalURLs.allSatisfy { $0.lengthOfBytes(using: .utf8) <= URLRules.maxInputTextBytes }
    }
}

struct ShareExtensionPendingItem: Codable, Equatable, Sendable {
    let url: String
    var needsURLSave: Bool
    var entryID: Int64?
    var normalizedURL: String?
    var pendingTagIDs: [Int64]
    var savedResult: ShareSaveResult?

    init(
        url: String,
        needsURLSave: Bool = true,
        entryID: Int64? = nil,
        normalizedURL: String? = nil,
        pendingTagIDs: [Int64] = [],
        savedResult: ShareSaveResult? = nil
    ) {
        self.url = url
        self.needsURLSave = needsURLSave
        self.entryID = entryID
        self.normalizedURL = normalizedURL
        self.pendingTagIDs = Array(Set(pendingTagIDs)).sorted()
        self.savedResult = savedResult
    }
}

struct ShareExtensionCompletedItem: Codable, Equatable, Sendable {
    let url: String
    let result: ShareSaveResult
    let entryID: Int64?
    let normalizedURL: String?

    init(url: String, result: ShareSaveResult, entryID: Int64? = nil, normalizedURL: String? = nil) {
        self.url = url
        self.result = result
        self.entryID = entryID
        self.normalizedURL = normalizedURL
    }
}

struct ShareExtensionPendingOperationStore {
    private let fileURL: URL
    private let encoder: JSONEncoder
    private let decoder: JSONDecoder

    init(fileURL: URL = SharedContainer.pendingShareOperationURL()) {
        self.fileURL = fileURL
        self.encoder = JSONEncoder()
        self.decoder = JSONDecoder()
        self.encoder.dateEncodingStrategy = .iso8601
        self.decoder.dateDecodingStrategy = .iso8601
    }

    func load(now: Date = Date()) throws -> ShareExtensionPendingOperation? {
        guard FileManager.default.fileExists(atPath: fileURL.path) else { return nil }
        let data = try Data(contentsOf: fileURL, options: [.mappedIfSafe])
        let operation = try decoder.decode(ShareExtensionPendingOperation.self, from: data)
        return operation.isRecoverable(at: now) ? operation : nil
    }

    func write(_ operation: ShareExtensionPendingOperation) throws {
        let directory = fileURL.deletingLastPathComponent()
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        let data = try encoder.encode(operation)
        try data.write(to: fileURL, options: [.atomic])
    }

    func clear(operationID: UUID) throws {
        guard FileManager.default.fileExists(atPath: fileURL.path) else { return }
        let data = try Data(contentsOf: fileURL, options: [.mappedIfSafe])
        let stored = try decoder.decode(ShareExtensionPendingOperation.self, from: data)
        guard stored.operationID == operationID else { return }
        try FileManager.default.removeItem(at: fileURL)
    }
}

struct ShareExtensionLifecycle: Equatable, Sendable {
    enum Phase: Equatable, Sendable {
        case active
        case handingOffToHost
        case completing
    }

    private(set) var phase: Phase = .active

    var shouldCancelTasksWhenViewDisappears: Bool {
        phase == .active
    }

    mutating func beginHostHandoff() {
        phase = .handingOffToHost
    }

    mutating func hostHandoffFailed() {
        guard phase == .handingOffToHost else { return }
        phase = .active
    }

    mutating func beginCompletion() {
        phase = .completing
    }
}

enum PendingDeleteTimerWaiter {
    static func wait(seconds: TimeInterval) async -> Bool {
        do {
            let clampedSeconds = max(0, min(seconds, TimeInterval(UInt64.max / 1_000_000_000)))
            try await Task.sleep(nanoseconds: UInt64(clampedSeconds * 1_000_000_000))
            return !Task.isCancelled
        } catch {
            return false
        }
    }
}

struct FirstRunOnboardingMigrationDecision: Equatable, Sendable {
    let hasSeenOnboarding: Bool
    let migrationCompleted: Bool
}

enum FirstRunOnboardingStore {
    static let seenKey = "hasSeenFirstRunOnboardingV2"
    private static let migrationKey = "firstRunOnboardingInstallClassifiedV3"

    static func resolve(
        migrationAlreadyCompleted: Bool,
        legacySeen: Bool,
        hadExistingDatabaseBeforeStartup: Bool
    ) -> FirstRunOnboardingMigrationDecision {
        FirstRunOnboardingMigrationDecision(
            hasSeenOnboarding: migrationAlreadyCompleted
                ? legacySeen
                : legacySeen || hadExistingDatabaseBeforeStartup,
            migrationCompleted: true
        )
    }

    static func initialize(
        defaults: UserDefaults = .standard,
        hadExistingDatabaseBeforeStartup: Bool
    ) {
        let decision = resolve(
            migrationAlreadyCompleted: defaults.bool(forKey: migrationKey),
            legacySeen: defaults.bool(forKey: seenKey),
            hadExistingDatabaseBeforeStartup: hadExistingDatabaseBeforeStartup
        )
        defaults.set(decision.hasSeenOnboarding, forKey: seenKey)
        defaults.set(decision.migrationCompleted, forKey: migrationKey)
    }

    static func shouldShow(defaults: UserDefaults = .standard) -> Bool {
        defaults.bool(forKey: migrationKey) && !defaults.bool(forKey: seenKey)
    }

    static func markSeen(defaults: UserDefaults = .standard) {
        defaults.set(true, forKey: seenKey)
    }
}
