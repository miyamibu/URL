import XCTest

final class SQLiteDatabaseTests: XCTestCase {
    func testDatabaseUsesWALJournalMode() throws {
        let directory = FileManager.default.temporaryDirectory
            .appendingPathComponent(UUID().uuidString, isDirectory: true)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: directory) }

        let database = try SQLiteDatabase(databaseURL: directory.appendingPathComponent("wal.sqlite"))
        XCTAssertEqual(try database.currentJournalMode()?.lowercased(), "wal")
    }
}
