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

    func testUnavailableDatabaseIsDiagnosableWithoutOpeningOrDeletingIt() {
        let databaseURL = FileManager.default.temporaryDirectory
            .appendingPathComponent("unavailable-\(UUID().uuidString).sqlite")
        let database = SQLiteDatabase.unavailable(
            databaseURL: databaseURL,
            message: "test database is unavailable"
        )

        XCTAssertFalse(database.isAvailable)
        XCTAssertThrowsError(try database.execute("CREATE TABLE should_not_be_created (id INTEGER);")) { error in
            XCTAssertEqual((error as? RepositoryError)?.localizedDescription, "test database is unavailable")
        }
        XCTAssertFalse(FileManager.default.fileExists(atPath: databaseURL.path))
    }
}
