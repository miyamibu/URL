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

        let restored = try XCTUnwrap(store.load())
        XCTAssertEqual(restored.inviteToken, "token-123")
        XCTAssertEqual(restored.savedAt, savedAt)

        try store.clear()
        XCTAssertNil(try store.load())
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
