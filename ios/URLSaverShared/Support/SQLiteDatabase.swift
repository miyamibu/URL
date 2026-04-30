import Foundation
import SQLite3

enum RepositoryError: Error {
    case openDatabase(String)
    case sqlite(String)
}

final class SQLiteDatabase: @unchecked Sendable {
    private let databaseURL: URL
    private var db: OpaquePointer?
    private let lock = NSRecursiveLock()

    init(databaseURL: URL) throws {
        self.databaseURL = databaseURL
        try open()
        try configureConnection()
    }

    deinit {
        sqlite3_close(db)
    }

    func execute(_ sql: String, binds: [SQLiteValue] = []) throws {
        try lock.withLock {
            let statement = try prepare(sql: sql)
            defer { sqlite3_finalize(statement) }
            try bind(binds, to: statement)
            try stepUntilDone(statement)
        }
    }

    func insert(_ sql: String, binds: [SQLiteValue] = []) throws -> Int64 {
        try lock.withLock {
            let statement = try prepare(sql: sql)
            defer { sqlite3_finalize(statement) }
            try bind(binds, to: statement)
            try stepUntilDone(statement)
            return sqlite3_last_insert_rowid(db)
        }
    }

    func executeBatch(_ sql: String) throws {
        try lock.withLock {
            try exec(sql)
        }
    }

    func fetchMany<T>(
        sql: String,
        binds: [SQLiteValue] = [],
        decode: (OpaquePointer?) throws -> T
    ) throws -> [T] {
        try lock.withLock {
            let statement = try prepare(sql: sql)
            defer { sqlite3_finalize(statement) }
            try bind(binds, to: statement)

            var rows: [T] = []
            while true {
                let result = sqlite3_step(statement)
                if result == SQLITE_ROW {
                    rows.append(try decode(statement))
                    continue
                }
                if result == SQLITE_DONE {
                    return rows
                }
                throw RepositoryError.sqlite(lastErrorMessage())
            }
        }
    }

    func fetchOne<T>(
        sql: String,
        binds: [SQLiteValue] = [],
        decode: (OpaquePointer?) throws -> T
    ) throws -> T? {
        try lock.withLock {
            let statement = try prepare(sql: sql)
            defer { sqlite3_finalize(statement) }
            try bind(binds, to: statement)

            let result = sqlite3_step(statement)
            if result == SQLITE_ROW {
                return try decode(statement)
            }
            if result == SQLITE_DONE {
                return nil
            }
            throw RepositoryError.sqlite(lastErrorMessage())
        }
    }

    func fetchString(_ sql: String, binds: [SQLiteValue] = []) throws -> String? {
        let value: String?? = try fetchOne(sql: sql, binds: binds) { statement in
            guard sqlite3_column_type(statement, 0) != SQLITE_NULL,
                  let value = sqlite3_column_text(statement, 0) else {
                return nil
            }
            return String(cString: value)
        }
        return value ?? nil
    }

    func fetchInt(_ sql: String, binds: [SQLiteValue] = []) throws -> Int? {
        let value: Int?? = try fetchOne(sql: sql, binds: binds) { statement in
            guard sqlite3_column_type(statement, 0) != SQLITE_NULL else {
                return nil
            }
            return Int(sqlite3_column_int(statement, 0))
        }
        return value ?? nil
    }

    func hasColumn(table: String, column: String) throws -> Bool {
        let escapedTable = table.replacingOccurrences(of: "'", with: "''")
        let pragma = "PRAGMA table_info('\(escapedTable)');"
        let columns = try fetchMany(sql: pragma) { statement in
            guard let value = sqlite3_column_text(statement, 1) else { return "" }
            return String(cString: value)
        }
        return columns.contains(column)
    }

    func addColumnIfMissing(table: String, column: String, definition: String) throws {
        guard try !hasColumn(table: table, column: column) else { return }
        try execute("ALTER TABLE \(table) ADD COLUMN \(definition);")
    }

    func transaction<T>(_ body: () throws -> T) throws -> T {
        try lock.withLock {
            try exec("BEGIN IMMEDIATE;")
            do {
                let result = try body()
                try exec("COMMIT;")
                return result
            } catch {
                try? exec("ROLLBACK;")
                throw error
            }
        }
    }

    func currentJournalMode() throws -> String? {
        try fetchString("PRAGMA journal_mode;")
    }

    private func open() throws {
        try FileManager.default.createDirectory(
            at: databaseURL.deletingLastPathComponent(),
            withIntermediateDirectories: true
        )
        if sqlite3_open(databaseURL.path, &db) != SQLITE_OK {
            throw RepositoryError.openDatabase(lastErrorMessage())
        }
    }

    private func configureConnection() throws {
        try exec("PRAGMA journal_mode=WAL;")
        try exec("PRAGMA foreign_keys=ON;")
        sqlite3_busy_timeout(db, 3_000)
    }

    private func prepare(sql: String) throws -> OpaquePointer? {
        var statement: OpaquePointer?
        guard sqlite3_prepare_v2(db, sql, -1, &statement, nil) == SQLITE_OK else {
            throw RepositoryError.sqlite(lastErrorMessage())
        }
        return statement
    }

    private func bind(_ binds: [SQLiteValue], to statement: OpaquePointer?) throws {
        for (index, value) in binds.enumerated() {
            value.bind(to: statement, index: Int32(index + 1))
        }
    }

    private func stepUntilDone(_ statement: OpaquePointer?) throws {
        var attempt = 0
        while true {
            let result = sqlite3_step(statement)
            if result == SQLITE_DONE {
                return
            }
            if result == SQLITE_BUSY || result == SQLITE_LOCKED {
                guard attempt < Self.maxBusyRetryCount else {
                    throw RepositoryError.sqlite(lastErrorMessage())
                }
                attempt += 1
                sqlite3_reset(statement)
                try sleepForRetry(attempt: attempt)
                continue
            }
            throw RepositoryError.sqlite(lastErrorMessage())
        }
    }

    private func exec(_ sql: String) throws {
        var attempt = 0
        while true {
            let result = sqlite3_exec(db, sql, nil, nil, nil)
            if result == SQLITE_OK {
                return
            }
            if result == SQLITE_BUSY || result == SQLITE_LOCKED {
                guard attempt < Self.maxBusyRetryCount else {
                    throw RepositoryError.sqlite(lastErrorMessage())
                }
                attempt += 1
                try sleepForRetry(attempt: attempt)
                continue
            }
            throw RepositoryError.sqlite(lastErrorMessage())
        }
    }

    private func sleepForRetry(attempt: Int) throws {
        let cappedAttempt = min(attempt, 5)
        let delayMillis = 20 * Int(pow(2.0, Double(cappedAttempt - 1)))
        Thread.sleep(forTimeInterval: Double(delayMillis) / 1_000.0)
    }

    private func lastErrorMessage() -> String {
        if let db, let cString = sqlite3_errmsg(db) {
            return String(cString: cString)
        }
        return "Unknown SQLite error"
    }

    private static let maxBusyRetryCount = 4
}

enum SQLiteValue {
    case int64(Int64)
    case int(Int)
    case double(Double)
    case text(String)
    case null

    func bind(to statement: OpaquePointer?, index: Int32) {
        switch self {
        case .int64(let value):
            sqlite3_bind_int64(statement, index, value)
        case .int(let value):
            sqlite3_bind_int(statement, index, Int32(value))
        case .double(let value):
            sqlite3_bind_double(statement, index, value)
        case .text(let value):
            sqlite3_bind_text(statement, index, value, -1, SQLITE_TRANSIENT)
        case .null:
            sqlite3_bind_null(statement, index)
        }
    }
}

func sql(_ value: Int64) -> SQLiteValue { .int64(value) }
func sql(_ value: Int) -> SQLiteValue { .int(value) }
func sql(_ value: Double) -> SQLiteValue { .double(value) }
func sql(_ value: String) -> SQLiteValue { .text(value) }
func sql(_ value: String?) -> SQLiteValue { value.map(SQLiteValue.text) ?? .null }
func sql(_ value: Double?) -> SQLiteValue { value.map(SQLiteValue.double) ?? .null }
func sql(_ value: Int64?) -> SQLiteValue { value.map(SQLiteValue.int64) ?? .null }
func sql(_ value: Int?) -> SQLiteValue { value.map(SQLiteValue.int) ?? .null }

private let SQLITE_TRANSIENT = unsafeBitCast(-1, to: sqlite3_destructor_type.self)

private extension NSRecursiveLock {
    func withLock<T>(_ body: () throws -> T) throws -> T {
        lock()
        defer { unlock() }
        return try body()
    }
}
