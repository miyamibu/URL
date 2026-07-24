import Foundation
import Security

struct PendingInviteRecord: Codable, Equatable, Sendable {
    let inviteToken: String
    let savedAt: Date
}

protocol PendingInviteSecureStorage: Sendable {
    func load() throws -> Data?
    func save(_ data: Data) throws
    func clear() throws
}

final class PendingInviteStore: @unchecked Sendable {
    private static let maxAge: TimeInterval = 24 * 60 * 60
    private let storage: any PendingInviteSecureStorage

    init(
        service: String = "jp.mimac.urlsaver.pending-invite",
        account: String = "shared-tag-invite"
    ) {
        self.storage = KeychainPendingInviteSecureStorage(service: service, account: account)
    }

    init(storage: any PendingInviteSecureStorage) {
        self.storage = storage
    }

    func load(now: Date = Date()) throws -> PendingInviteRecord? {
        guard let data = try storage.load() else { return nil }
        let record = try JSONDecoder().decode(PendingInviteRecord.self, from: data)
        guard !record.inviteToken.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
              record.savedAt <= now,
              now.timeIntervalSince(record.savedAt) <= Self.maxAge else {
            try storage.clear()
            return nil
        }
        return record
    }

    func save(inviteToken: String, now: Date = Date()) throws {
        let savedAt = try load(now: now)
            .flatMap { $0.inviteToken == inviteToken ? $0.savedAt : nil }
            ?? now
        let record = PendingInviteRecord(inviteToken: inviteToken, savedAt: savedAt)
        let payload = try JSONEncoder().encode(record)
        try storage.save(payload)
    }

    func clear() throws {
        try storage.clear()
    }
}

private struct KeychainPendingInviteSecureStorage: PendingInviteSecureStorage {
    private let service: String
    private let account: String

    init(service: String, account: String) {
        self.service = service
        self.account = account
    }

    func load() throws -> Data? {
        var item: CFTypeRef?
        let status = SecItemCopyMatching(baseQuery(returnData: true) as CFDictionary, &item)
        if status == errSecItemNotFound {
            return nil
        }
        guard status == errSecSuccess else {
            throw PendingInviteStoreError.keychainFailure(status)
        }
        guard let data = item as? Data else {
            throw PendingInviteStoreError.corruptPayload
        }
        return data
    }

    func save(_ data: Data) throws {
        let status = SecItemAdd(
            baseQuery(returnData: false).merging(
                [
                    kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly,
                    kSecValueData as String: data,
                ],
                uniquingKeysWith: { _, new in new }
            ) as CFDictionary,
            nil
        )
        if status == errSecDuplicateItem {
            let updateStatus = SecItemUpdate(
                baseQuery(returnData: false) as CFDictionary,
                [
                    kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly,
                    kSecValueData as String: data,
                ] as CFDictionary
            )
            guard updateStatus == errSecSuccess else {
                throw PendingInviteStoreError.keychainFailure(updateStatus)
            }
            return
        }
        guard status == errSecSuccess else {
            throw PendingInviteStoreError.keychainFailure(status)
        }
    }

    func clear() throws {
        let status = SecItemDelete(baseQuery(returnData: false) as CFDictionary)
        guard status == errSecSuccess || status == errSecItemNotFound else {
            throw PendingInviteStoreError.keychainFailure(status)
        }
    }

    private func baseQuery(returnData: Bool) -> [String: Any] {
        var query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
        ]
        if returnData {
            query[kSecReturnData as String] = true
            query[kSecMatchLimit as String] = kSecMatchLimitOne
        }
        return query
    }
}

enum PendingInviteStoreError: Error {
    case keychainFailure(OSStatus)
    case corruptPayload
}
