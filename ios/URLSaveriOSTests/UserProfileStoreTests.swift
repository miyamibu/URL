import Foundation
import XCTest

final class UserProfileStoreTests: XCTestCase {
    func testSaveAndLoadProfileWithAvatar() throws {
        let directory = FileManager.default.temporaryDirectory
            .appendingPathComponent(UUID().uuidString, isDirectory: true)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        let fileURL = directory.appendingPathComponent("profile.json")
        let store = UserProfileStore(fileURL: fileURL)

        let original = UserProfile(
            displayName: "Maco",
            avatarImageData: Data([0x01, 0x02, 0x03]),
            updatedAt: Date(timeIntervalSince1970: 1_776_900_000)
        )

        try store.save(original)
        let loaded = try store.load()

        XCTAssertEqual(loaded, original)

        try? FileManager.default.removeItem(at: directory)
    }
}
