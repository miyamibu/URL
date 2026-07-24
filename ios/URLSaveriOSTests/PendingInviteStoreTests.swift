import Foundation
import XCTest

final class PendingInviteStoreTests: XCTestCase {
    func testSaveLoadAndClearPendingInviteRecord() throws {
        let storage = InMemoryPendingInviteSecureStorage()
        let store = PendingInviteStore(storage: storage)
        defer { try? store.clear() }

        XCTAssertNil(try store.load())

        let savedAt = Date(timeIntervalSince1970: 1234)
        try store.save(inviteToken: "token-123", now: savedAt)

        let restored = try XCTUnwrap(store.load(now: savedAt.addingTimeInterval(60)))
        XCTAssertEqual(restored.inviteToken, "token-123")
        XCTAssertEqual(restored.savedAt, savedAt)

        try store.clear()
        XCTAssertNil(try store.load())
    }

    func testExpiredPendingInviteIsCleared() throws {
        let storage = InMemoryPendingInviteSecureStorage()
        let store = PendingInviteStore(storage: storage)
        let savedAt = Date(timeIntervalSince1970: 1234)
        try store.save(inviteToken: "token-expired", now: savedAt)

        XCTAssertNil(try store.load(now: savedAt.addingTimeInterval(24 * 60 * 60 + 1)))
        XCTAssertNil(try storage.load())
    }

    func testFuturePendingInviteIsCleared() throws {
        let storage = InMemoryPendingInviteSecureStorage()
        let store = PendingInviteStore(storage: storage)
        let savedAt = Date(timeIntervalSince1970: 1234)
        try store.save(inviteToken: "token-future", now: savedAt)

        XCTAssertNil(try store.load(now: savedAt.addingTimeInterval(-1)))
        XCTAssertNil(try storage.load())
    }

    func testSavingTheSamePendingInviteDoesNotExtendItsOriginalTTL() throws {
        let storage = InMemoryPendingInviteSecureStorage()
        let store = PendingInviteStore(storage: storage)
        let firstSavedAt = Date(timeIntervalSince1970: 1234)
        try store.save(inviteToken: "token-stable", now: firstSavedAt)

        try store.save(
            inviteToken: "token-stable",
            now: firstSavedAt.addingTimeInterval(12 * 60 * 60)
        )

        let restored = try store.load(now: firstSavedAt.addingTimeInterval(24 * 60 * 60 + 1))
        XCTAssertNil(restored)
        XCTAssertNil(try storage.load())
    }
}

private final class InMemoryPendingInviteSecureStorage: PendingInviteSecureStorage, @unchecked Sendable {
    private let lock = NSLock()
    private var payload: Data?

    func load() throws -> Data? {
        lock.lock()
        defer { lock.unlock() }
        return payload
    }

    func save(_ data: Data) throws {
        lock.lock()
        payload = data
        lock.unlock()
    }

    func clear() throws {
        lock.lock()
        payload = nil
        lock.unlock()
    }
}
