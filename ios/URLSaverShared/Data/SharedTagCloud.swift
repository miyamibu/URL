import Foundation
import Security
import SQLite3

struct SharedTagCloudConfig: Sendable {
    let enabled: Bool
    let supabaseURL: String
    let anonKey: String

    init(
        bundle: Bundle = .main,
        environment: [String: String] = ProcessInfo.processInfo.environment
    ) {
        let envEnabled = environment["URLSAVER_SHARED_TAG_CLOUD_ENABLED"]?.trimmingCharacters(in: .whitespacesAndNewlines)
        let envURL = environment["URLSAVER_SUPABASE_URL"]?.trimmingCharacters(in: .whitespacesAndNewlines)
        let envAnonKey = environment["URLSAVER_SUPABASE_ANON_KEY"]?.trimmingCharacters(in: .whitespacesAndNewlines)
        let bundleEnabled = bundle.object(forInfoDictionaryKey: "SharedTagCloudEnabled") as? Bool
        let bundleEnabledString = bundle.object(forInfoDictionaryKey: "SharedTagCloudEnabled") as? String
        let bundleURL = bundle.object(forInfoDictionaryKey: "SupabaseURL") as? String
        let bundleAnonKey = bundle.object(forInfoDictionaryKey: "SupabaseAnonKey") as? String

        enabled = envEnabled.map(Self.parseEnabledFlag)
            ?? bundleEnabled
            ?? bundleEnabledString.map(Self.parseEnabledFlag)
            ?? false
        supabaseURL = envURL ?? bundleURL?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        anonKey = envAnonKey ?? bundleAnonKey?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
    }

    var isConfigured: Bool {
        enabled && !supabaseURL.isEmpty && !anonKey.isEmpty
    }

    private static func parseEnabledFlag(_ raw: String) -> Bool {
        switch raw.lowercased() {
        case "1", "true", "yes", "on":
            return true
        default:
            return false
        }
    }
}

protocol SharedTagAuthSecureStorage: Sendable {
    func load() throws -> Data?
    func save(_ data: Data) throws
    func clear() throws
}

final class SharedTagAuthSessionStore: @unchecked Sendable {
    private let storage: any SharedTagAuthSecureStorage

    init(
        service: String = "jp.mimac.urlsaver.shared-tag-auth",
        account: String = "session"
    ) {
        self.storage = KeychainSharedTagAuthSecureStorage(service: service, account: account)
    }

    init(storage: any SharedTagAuthSecureStorage) {
        self.storage = storage
    }

    func load() throws -> SharedTagAuthSession? {
        guard let data = try storage.load() else { return nil }
        return try JSONDecoder().decode(SharedTagAuthSession.self, from: data)
    }

    func save(_ session: SharedTagAuthSession) throws {
        let payload = try JSONEncoder().encode(session)
        try storage.save(payload)
    }

    func clear() throws {
        try storage.clear()
    }
}

private struct KeychainSharedTagAuthSecureStorage: SharedTagAuthSecureStorage {
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
                    kSecValueData as String: data,
                    kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly,
                ],
                uniquingKeysWith: { _, new in new }
            ) as CFDictionary,
            nil
        )
        if status == errSecDuplicateItem {
            let updateStatus = SecItemUpdate(
                baseQuery(returnData: false) as CFDictionary,
                [kSecValueData as String: data] as CFDictionary
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

private enum SharedTagAuthRemoteResult {
    case signedIn(SharedTagAuthSession)
    case needsEmailConfirmation
}

private struct SharedTagAuthRemoteDataSource {
    private let config: SharedTagCloudConfig

    init(config: SharedTagCloudConfig) {
        self.config = config
    }

    func signUp(email: String, password: String) async throws -> SharedTagAuthRemoteResult {
        let response = try await executeAuthRequest(
            path: "/auth/v1/signup",
            body: SupabasePasswordAuthRequest(email: email, password: password)
        )
        return try parseSessionResult(response)
    }

    func signIn(email: String, password: String) async throws -> SharedTagAuthRemoteResult {
        let response = try await executeAuthRequest(
            path: "/auth/v1/token?grant_type=password",
            body: SupabasePasswordAuthRequest(email: email, password: password)
        )
        return try parseSessionResult(response)
    }

    func refreshSession(refreshToken: String) async throws -> SharedTagAuthSession {
        let response = try await executeAuthRequest(
            path: "/auth/v1/token?grant_type=refresh_token",
            body: SupabaseRefreshTokenRequest(refreshToken: refreshToken)
        )
        return try requireSession(from: response)
    }

    private func parseSessionResult(_ data: Data) throws -> SharedTagAuthRemoteResult {
        let response = try makeSharedTagCloudDecoder().decode(SupabaseAuthSessionResponse.self, from: data)
        if response.accessToken?.isEmpty != false || response.refreshToken?.isEmpty != false {
            return .needsEmailConfirmation
        }
        return .signedIn(try requireSession(from: data))
    }

    private func requireSession(from data: Data) throws -> SharedTagAuthSession {
        let response = try makeSharedTagCloudDecoder().decode(SupabaseAuthSessionResponse.self, from: data)
        guard
            let accessToken = response.accessToken,
            !accessToken.isEmpty,
            let user = response.user
        else {
            throw SharedTagCloudError.message("Supabase Auth response did not contain a session.")
        }
        return SharedTagAuthSession(
            authUserID: user.id,
            accessToken: accessToken,
            refreshToken: response.refreshToken,
            userEmail: user.email
        )
    }

    private func executeAuthRequest<T: Encodable>(
        path: String,
        body: T
    ) async throws -> Data {
        try await SharedTagRemoteRequestExecutor(config: config).execute(
            path: path,
            bearerToken: nil,
            body: body
        )
    }
}

private struct SharedTagSyncRemoteDataSource {
    private let config: SharedTagCloudConfig
    private let authRemoteDataSource: SharedTagAuthRemoteDataSource
    private let sessionStore: SharedTagAuthSessionStore

    init(
        config: SharedTagCloudConfig,
        authRemoteDataSource: SharedTagAuthRemoteDataSource,
        sessionStore: SharedTagAuthSessionStore
    ) {
        self.config = config
        self.authRemoteDataSource = authRemoteDataSource
        self.sessionStore = sessionStore
    }

    func pullSnapshot(session: SharedTagAuthSession) async throws -> PullSharedTagSnapshotResponse {
        let data = try await executeRPC(
            path: "/rest/v1/rpc/pull_shared_tag_snapshot",
            session: session,
            body: EmptyPayload()
        )
        return try makeSharedTagCloudDecoder().decode(PullSharedTagSnapshotResponse.self, from: data)
    }

    func applyOperations(
        session: SharedTagAuthSession,
        operations: [SharedTagSyncOperation]
    ) async throws -> ApplySharedTagOpsResponse {
        let data = try await executeRPC(
            path: "/rest/v1/rpc/apply_shared_tag_ops",
            session: session,
            body: ApplySharedTagOpsPayload(payload: operations)
        )
        return try makeSharedTagCloudDecoder().decode(ApplySharedTagOpsResponse.self, from: data)
    }

    func createInvite(
        session: SharedTagAuthSession,
        remoteTagID: String,
        role: String
    ) async throws -> CreateSharedTagInviteResponse {
        let data = try await executeRPC(
            path: "/rest/v1/rpc/create_shared_tag_invite",
            session: session,
            body: CreateInvitePayload(tagID: remoteTagID, role: role)
        )
        return try makeSharedTagCloudDecoder().decode(CreateSharedTagInviteResponse.self, from: data)
    }

    func acceptInvite(session: SharedTagAuthSession, inviteToken: String) async throws -> AcceptSharedTagInviteResponse {
        let data = try await executeRPC(
            path: "/rest/v1/rpc/accept_shared_tag_invite",
            session: session,
            body: AcceptInvitePayload(token: inviteToken)
        )
        return try makeSharedTagCloudDecoder().decode(AcceptSharedTagInviteResponse.self, from: data)
    }

    func deleteAccount(session: SharedTagAuthSession) async throws {
        _ = try await executeRPC(
            path: "/rest/v1/rpc/delete_my_account",
            session: session,
            body: EmptyPayload()
        )
    }

    private func executeRPC<T: Encodable>(
        path: String,
        session: SharedTagAuthSession,
        body: T
    ) async throws -> Data {
        do {
            return try await SharedTagRemoteRequestExecutor(config: config).execute(
                path: path,
                bearerToken: session.accessToken,
                body: body
            )
        } catch SharedTagCloudError.httpStatus(let code, _) where code == 401 {
            guard let refreshToken = session.refreshToken, !refreshToken.isEmpty else {
                throw SharedTagCloudError.authRequired
            }
            let refreshed = try await authRemoteDataSource.refreshSession(refreshToken: refreshToken)
            try sessionStore.save(refreshed)
            return try await SharedTagRemoteRequestExecutor(config: config).execute(
                path: path,
                bearerToken: refreshed.accessToken,
                body: body
            )
        }
    }
}

private struct SharedTagRemoteRequestExecutor {
    private let config: SharedTagCloudConfig

    init(config: SharedTagCloudConfig) {
        self.config = config
    }

    func execute<T: Encodable>(
        path: String,
        bearerToken: String?,
        body: T
    ) async throws -> Data {
        guard config.isConfigured else {
            throw SharedTagCloudError.message("Shared tag cloud is not configured.")
        }

        guard let url = URL(string: config.supabaseURL.trimmingCharacters(in: CharacterSet(charactersIn: "/")) + path) else {
            throw SharedTagCloudError.message("Supabase URL is invalid.")
        }

        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.timeoutInterval = 30
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.setValue(config.anonKey, forHTTPHeaderField: "apikey")
        if let bearerToken {
            request.setValue("Bearer \(bearerToken)", forHTTPHeaderField: "Authorization")
        }
        request.httpBody = try makeSharedTagCloudEncoder().encode(body)

        let (data, response) = try await URLSession.shared.data(for: request)
        guard let httpResponse = response as? HTTPURLResponse else {
            throw SharedTagCloudError.message("Supabase response was invalid.")
        }
        guard (200...299).contains(httpResponse.statusCode) else {
            let message = SharedTagRemoteErrorParser.message(from: data) ?? String(data: data, encoding: .utf8) ?? ""
            throw SharedTagCloudError.httpStatus(httpResponse.statusCode, message)
        }
        return data
    }
}

private enum SharedTagRemoteErrorParser {
    static func message(from data: Data) -> String? {
        if let parsed = try? makeSharedTagCloudDecoder().decode(SupabaseAuthErrorResponse.self, from: data) {
            return parsed.message
        }
        if let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any] {
            if let message = object["message"] as? String, !message.isEmpty {
                return message
            }
            if let hint = object["hint"] as? String, !hint.isEmpty {
                return hint
            }
        }
        return nil
    }
}

enum SharedTagCloudError: Error {
    case authRequired
    case invalidInvite
    case ownerTransferRequired
    case httpStatus(Int, String)
    case message(String)

    var userMessage: String {
        switch self {
        case .authRequired:
            return "共有タグクラウドにサインインしてください"
        case .invalidInvite:
            return "招待リンクが無効か期限切れです"
        case .ownerTransferRequired:
            return "他のメンバーがいる共有タグのオーナー権限を移譲してから削除してください"
        case .httpStatus(_, let message):
            return message.isEmpty ? "通信に失敗しました" : message
        case .message(let message):
            return message
        }
    }
}

final class SharedTagStore: @unchecked Sendable {
    private let database: SQLiteDatabase

    init(database: SQLiteDatabase) throws {
        self.database = database
        try migrateIfNeeded()
    }

    func loadVisibleTags(authUserID: String) throws -> [SharedTagSummary] {
        try database.fetchMany(
            sql: """
            SELECT
                remote_tag_id,
                name,
                current_user_role,
                last_synced_at,
                (
                    SELECT COUNT(*)
                    FROM shared_tag_urls url
                    WHERE url.auth_user_id = shared_tags.auth_user_id
                      AND url.tag_remote_id = shared_tags.remote_tag_id
                      AND url.deleted_at IS NULL
                ) AS active_url_count
            FROM shared_tags
            WHERE auth_user_id = ?
              AND deleted_at IS NULL
            ORDER BY name COLLATE NOCASE ASC;
            """,
            binds: [sql(authUserID)]
        ) { statement in
            SharedTagSummary(
                remoteTagID: Self.textColumn(statement, index: 0) ?? "",
                name: Self.textColumn(statement, index: 1) ?? "",
                currentUserRole: Self.textColumn(statement, index: 2).flatMap(SharedTagMemberRole.init(rawValue:)),
                activeURLCount: Int(sqlite3_column_int(statement, 4)),
                lastSyncedAt: Self.dateColumn(statement, index: 3)
            )
        }
    }

    func loadTag(authUserID: String, remoteTagID: String) throws -> SharedTagSummary? {
        try database.fetchOne(
            sql: """
            SELECT
                remote_tag_id,
                name,
                current_user_role,
                last_synced_at,
                (
                    SELECT COUNT(*)
                    FROM shared_tag_urls url
                    WHERE url.auth_user_id = shared_tags.auth_user_id
                      AND url.tag_remote_id = shared_tags.remote_tag_id
                      AND url.deleted_at IS NULL
                ) AS active_url_count
            FROM shared_tags
            WHERE auth_user_id = ?
              AND remote_tag_id = ?
              AND deleted_at IS NULL
            LIMIT 1;
            """,
            binds: [sql(authUserID), sql(remoteTagID)]
        ) { statement in
            SharedTagSummary(
                remoteTagID: Self.textColumn(statement, index: 0) ?? "",
                name: Self.textColumn(statement, index: 1) ?? "",
                currentUserRole: Self.textColumn(statement, index: 2).flatMap(SharedTagMemberRole.init(rawValue:)),
                activeURLCount: Int(sqlite3_column_int(statement, 4)),
                lastSyncedAt: Self.dateColumn(statement, index: 3)
            )
        }
    }

    func loadVisibleTagsForEntry(authUserID: String, normalizedURL: String) throws -> [SharedTagSummary] {
        try database.fetchMany(
            sql: """
            SELECT
                shared_tags.remote_tag_id,
                shared_tags.name,
                shared_tags.current_user_role,
                shared_tags.last_synced_at,
                (
                    SELECT COUNT(*)
                    FROM shared_tag_urls all_urls
                    WHERE all_urls.auth_user_id = shared_tags.auth_user_id
                      AND all_urls.tag_remote_id = shared_tags.remote_tag_id
                      AND all_urls.deleted_at IS NULL
                ) AS active_url_count
            FROM shared_tags
            INNER JOIN shared_tag_urls
                ON shared_tag_urls.auth_user_id = shared_tags.auth_user_id
               AND shared_tag_urls.tag_remote_id = shared_tags.remote_tag_id
            WHERE shared_tags.auth_user_id = ?
              AND shared_tags.deleted_at IS NULL
              AND shared_tag_urls.deleted_at IS NULL
              AND shared_tag_urls.normalized_url = ?
            ORDER BY shared_tags.name COLLATE NOCASE ASC;
            """,
            binds: [sql(authUserID), sql(normalizedURL)]
        ) { statement in
            SharedTagSummary(
                remoteTagID: Self.textColumn(statement, index: 0) ?? "",
                name: Self.textColumn(statement, index: 1) ?? "",
                currentUserRole: Self.textColumn(statement, index: 2).flatMap(SharedTagMemberRole.init(rawValue:)),
                activeURLCount: Int(sqlite3_column_int(statement, 4)),
                lastSyncedAt: Self.dateColumn(statement, index: 3)
            )
        }
    }

    func loadEntryIDsForTag(authUserID: String, remoteTagID: String) throws -> [Int64] {
        try database.fetchMany(
            sql: """
            SELECT url_entries.id
            FROM shared_tag_urls
            INNER JOIN url_entries
                ON url_entries.normalized_url = shared_tag_urls.normalized_url
            WHERE shared_tag_urls.auth_user_id = ?
              AND shared_tag_urls.tag_remote_id = ?
              AND shared_tag_urls.deleted_at IS NULL
            ORDER BY
                CASE url_entries.record_state
                    WHEN 'ACTIVE' THEN 0
                    WHEN 'ARCHIVED' THEN 1
                    ELSE 2
                END,
                COALESCE(url_entries.archived_at, url_entries.created_at) DESC,
                url_entries.created_at DESC;
            """,
            binds: [sql(authUserID), sql(remoteTagID)]
        ) { statement in
            sqlite3_column_int64(statement, 0)
        }
    }

    func loadActiveMembersForTag(authUserID: String, remoteTagID: String) throws -> [SharedTagMemberSummary] {
        try database.fetchMany(
            sql: """
            SELECT
                tag_remote_id,
                user_id,
                role
            FROM shared_tag_members
            WHERE auth_user_id = ?
              AND tag_remote_id = ?
              AND status = 'active'
            ORDER BY
                CASE WHEN user_id = ? THEN 0 ELSE 1 END,
                CASE role
                    WHEN 'owner' THEN 0
                    WHEN 'editor' THEN 1
                    ELSE 2
                END,
                user_id COLLATE NOCASE ASC;
            """,
            binds: [sql(authUserID), sql(remoteTagID), sql(authUserID)]
        ) { statement in
            let userID = Self.textColumn(statement, index: 1) ?? ""
            return SharedTagMemberSummary(
                tagID: Self.textColumn(statement, index: 0) ?? "",
                userID: userID,
                role: Self.textColumn(statement, index: 2).flatMap(SharedTagMemberRole.init(rawValue:)) ?? .viewer,
                isCurrentUser: userID == authUserID
            )
        }
    }

    func applySnapshot(authUserID: String, snapshot: PullSharedTagSnapshotResponse) throws {
        try database.transaction {
            try database.execute("DELETE FROM shared_tag_members WHERE auth_user_id = ?;", binds: [sql(authUserID)])
            try database.execute("DELETE FROM shared_tag_urls WHERE auth_user_id = ?;", binds: [sql(authUserID)])
            try database.execute("DELETE FROM shared_tags WHERE auth_user_id = ?;", binds: [sql(authUserID)])

            let activeMemberships = snapshot.members.filter { $0.status.lowercased() == "active" }
            let activeRolesByTagID = Dictionary(
                uniqueKeysWithValues: activeMemberships
                    .filter { $0.userID == authUserID }
                    .compactMap { member in
                        SharedTagMemberRole(rawValue: member.role.lowercased()).map { (member.tagID, $0) }
                    }
            )
            let deletedTagIDs = Set(snapshot.tags.compactMap { $0.deletedAt == nil ? nil : $0.id })

            for tag in snapshot.tags {
                let currentUserRole = activeRolesByTagID[tag.id]?.rawValue
                try database.execute(
                    """
                    INSERT INTO shared_tags (
                        auth_user_id,
                        remote_tag_id,
                        name,
                        current_user_role,
                        deleted_at,
                        last_synced_at
                    ) VALUES (?, ?, ?, ?, ?, ?);
                    """,
                    binds: [
                        sql(authUserID),
                        sql(tag.id),
                        sql(tag.name),
                        sql(currentUserRole),
                        sql(Self.parseISO8601(tag.deletedAt)?.timeIntervalSince1970),
                        sql(Self.parseISO8601(snapshot.pulledAt)?.timeIntervalSince1970),
                    ]
                )
            }

            for member in snapshot.members {
                try database.execute(
                    """
                    INSERT INTO shared_tag_members (
                        auth_user_id,
                        tag_remote_id,
                        user_id,
                        role,
                        status,
                        created_at,
                        updated_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?);
                    """,
                    binds: [
                        sql(authUserID),
                        sql(member.tagID),
                        sql(member.userID),
                        sql(member.role.lowercased()),
                        sql(member.status.lowercased()),
                        sql(Self.parseISO8601(member.createdAt)?.timeIntervalSince1970),
                        sql(Self.parseISO8601(member.updatedAt)?.timeIntervalSince1970),
                    ]
                )
            }

            let activeURLs = snapshot.urls.filter { remoteURL in
                remoteURL.deletedAt == nil && !deletedTagIDs.contains(remoteURL.tagID)
            }

            for remoteURL in snapshot.urls {
                try database.execute(
                    """
                    INSERT INTO shared_tag_urls (
                        auth_user_id,
                        tag_remote_id,
                        remote_url_id,
                        normalized_url,
                        raw_url,
                        deleted_at
                    ) VALUES (?, ?, ?, ?, ?, ?);
                    """,
                    binds: [
                        sql(authUserID),
                        sql(remoteURL.tagID),
                        sql(remoteURL.id),
                        sql(remoteURL.normalizedURL),
                        sql(remoteURL.rawURL),
                        sql(Self.parseISO8601(remoteURL.deletedAt)?.timeIntervalSince1970),
                    ]
                )
            }

            try database.execute(
                "UPDATE url_entries SET shared_reference_count = 0 WHERE shared_reference_count != 0;",
                binds: []
            )

            let grouped = Dictionary(grouping: activeURLs, by: \.normalizedURL)
            for (normalizedURL, urls) in grouped {
                guard let sample = urls.first else { continue }
                let count = urls.count
                let entryID = try ensureEntry(rawURL: sample.rawURL, normalizedURL: normalizedURL)
                try database.execute(
                    "UPDATE url_entries SET shared_reference_count = ? WHERE id = ?;",
                    binds: [sql(count), sql(entryID)]
                )
            }

            try database.execute(
                "DELETE FROM url_entries WHERE local_provenance_count = 0 AND shared_reference_count = 0;",
                binds: []
            )

            try database.execute(
                """
                INSERT INTO shared_tag_sync_state (auth_user_id, last_pulled_at, last_error_message)
                VALUES (?, ?, NULL)
                ON CONFLICT(auth_user_id) DO UPDATE SET
                    last_pulled_at = excluded.last_pulled_at,
                    last_error_message = NULL;
                """,
                binds: [
                    sql(authUserID),
                    sql(Self.parseISO8601(snapshot.pulledAt)?.timeIntervalSince1970),
                ]
            )
        }
    }

    func clearLocalSharedState() throws {
        try database.transaction {
            try database.execute("DELETE FROM shared_tag_members;", binds: [])
            try database.execute("DELETE FROM shared_tag_urls;", binds: [])
            try database.execute("DELETE FROM shared_tags;", binds: [])
            try database.execute("DELETE FROM shared_tag_sync_state;", binds: [])
            try database.execute("UPDATE url_entries SET shared_reference_count = 0 WHERE shared_reference_count != 0;", binds: [])
            try database.execute("DELETE FROM url_entries WHERE local_provenance_count = 0 AND shared_reference_count = 0;", binds: [])
        }
    }

    private func ensureEntry(rawURL: String, normalizedURL: String) throws -> Int64 {
        if let existingID = try database.fetchOne(
            sql: "SELECT id FROM url_entries WHERE normalized_url = ? LIMIT 1;",
            binds: [sql(normalizedURL)],
            decode: { statement in sqlite3_column_int64(statement, 0) }
        ) {
            return existingID
        }

        let parsed = URLRules.parseURL(rawURL) ?? URLRules.parseURL(normalizedURL)
        guard let parsed else {
            throw SharedTagCloudError.message("共有URLを解析できませんでした。")
        }
        let now = Date().timeIntervalSince1970
        return try database.insert(
            """
            INSERT INTO url_entries (
                original_url,
                normalized_url,
                display_url,
                open_url,
                normalized_host,
                raw_source_host,
                service_type,
                content_context,
                metadata_state,
                metadata_requested_at,
                record_state,
                local_provenance_count,
                shared_reference_count,
                created_at,
                updated_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'PENDING', ?, 'ACTIVE', 0, 0, ?, ?);
            """,
            binds: [
                sql(rawURL),
                sql(parsed.normalizedURL),
                sql(parsed.displayURL),
                sql(parsed.openURL),
                sql(parsed.normalizedHost),
                sql(parsed.rawSourceHost),
                sql(parsed.serviceType.rawValue),
                sql(parsed.contentContext.rawValue),
                sql(now),
                sql(now),
                sql(now),
            ]
        )
    }

    private func migrateIfNeeded() throws {
        try database.executeBatch(
            """
            CREATE TABLE IF NOT EXISTS shared_tags (
                auth_user_id TEXT NOT NULL,
                remote_tag_id TEXT NOT NULL,
                name TEXT NOT NULL,
                current_user_role TEXT,
                deleted_at REAL,
                last_synced_at REAL,
                PRIMARY KEY (auth_user_id, remote_tag_id)
            );
            CREATE TABLE IF NOT EXISTS shared_tag_members (
                auth_user_id TEXT NOT NULL,
                tag_remote_id TEXT NOT NULL,
                user_id TEXT NOT NULL,
                role TEXT NOT NULL,
                status TEXT NOT NULL,
                created_at REAL NOT NULL,
                updated_at REAL NOT NULL,
                PRIMARY KEY (auth_user_id, tag_remote_id, user_id)
            );
            CREATE TABLE IF NOT EXISTS shared_tag_urls (
                auth_user_id TEXT NOT NULL,
                tag_remote_id TEXT NOT NULL,
                remote_url_id TEXT NOT NULL,
                normalized_url TEXT NOT NULL,
                raw_url TEXT NOT NULL,
                deleted_at REAL,
                PRIMARY KEY (auth_user_id, tag_remote_id, remote_url_id)
            );
            CREATE TABLE IF NOT EXISTS shared_tag_sync_state (
                auth_user_id TEXT PRIMARY KEY,
                last_pulled_at REAL,
                last_error_message TEXT
            );
            CREATE INDEX IF NOT EXISTS idx_shared_tags_visible ON shared_tags(auth_user_id, deleted_at, name);
            CREATE INDEX IF NOT EXISTS idx_shared_tag_urls_lookup ON shared_tag_urls(auth_user_id, normalized_url, deleted_at);
            """
        )
    }

    static func parseISO8601(_ value: String?) -> Date? {
        guard let value, !value.isEmpty else { return nil }
        let fractional = ISO8601DateFormatter()
        fractional.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        if let parsed = fractional.date(from: value) {
            return parsed
        }
        let fallback = ISO8601DateFormatter()
        fallback.formatOptions = [.withInternetDateTime]
        return fallback.date(from: value)
    }

    private static func textColumn(_ statement: OpaquePointer?, index: Int32) -> String? {
        guard sqlite3_column_type(statement, index) != SQLITE_NULL,
              let value = sqlite3_column_text(statement, index) else {
            return nil
        }
        return String(cString: value)
    }

    private static func dateColumn(_ statement: OpaquePointer?, index: Int32) -> Date? {
        guard sqlite3_column_type(statement, index) != SQLITE_NULL else { return nil }
        return Date(timeIntervalSince1970: sqlite3_column_double(statement, index))
    }
}

final class SharedTagCloudService: @unchecked Sendable {
    let config: SharedTagCloudConfig
    private let sessionStore: SharedTagAuthSessionStore
    private let authRemoteDataSource: SharedTagAuthRemoteDataSource
    private let syncRemoteDataSource: SharedTagSyncRemoteDataSource
    private let store: SharedTagStore
    private let repository: URLRepository

    init(
        config: SharedTagCloudConfig,
        sessionStore: SharedTagAuthSessionStore,
        store: SharedTagStore,
        repository: URLRepository
    ) {
        self.config = config
        self.sessionStore = sessionStore
        self.store = store
        self.repository = repository
        self.authRemoteDataSource = SharedTagAuthRemoteDataSource(config: config)
        self.syncRemoteDataSource = SharedTagSyncRemoteDataSource(
            config: config,
            authRemoteDataSource: authRemoteDataSource,
            sessionStore: sessionStore
        )
    }

    var state: SharedTagCloudState {
        let session = try? sessionStore.load()
        return SharedTagCloudState(
            isConfigured: config.isConfigured,
            isSignedIn: session != nil && config.isConfigured,
            signedInEmail: session?.userEmail
        )
    }

    func currentSession() -> SharedTagAuthSession? {
        try? sessionStore.load()
    }

    func loadVisibleTags() throws -> [SharedTagSummary] {
        guard let session = try sessionStore.load() else { return [] }
        return try store.loadVisibleTags(authUserID: session.authUserID)
    }

    func loadTag(remoteTagID: String) throws -> SharedTagSummary? {
        guard let session = try sessionStore.load() else { return nil }
        return try store.loadTag(authUserID: session.authUserID, remoteTagID: remoteTagID)
    }

    func loadVisibleTagsForEntry(entryID: Int64) throws -> [SharedTagSummary] {
        guard let session = try sessionStore.load(),
              let entry = try repository.loadEntry(id: entryID) else {
            return []
        }
        return try store.loadVisibleTagsForEntry(
            authUserID: session.authUserID,
            normalizedURL: entry.normalizedURL
        )
    }

    func loadEntriesForTag(remoteTagID: String) throws -> [URLRecord] {
        guard let session = try sessionStore.load() else { return [] }
        let ids = try store.loadEntryIDsForTag(authUserID: session.authUserID, remoteTagID: remoteTagID)
        return try ids.compactMap { try repository.loadEntry(id: $0) }
    }

    func loadMembersForTag(remoteTagID: String) throws -> [SharedTagMemberSummary] {
        guard let session = try sessionStore.load() else { return [] }
        return try store.loadActiveMembersForTag(authUserID: session.authUserID, remoteTagID: remoteTagID)
    }

    func signIn(email: String, password: String) async -> SharedTagAuthResult {
        guard config.isConfigured else {
            return .failure("共有タグクラウドの設定がありません")
        }
        do {
            switch try await authRemoteDataSource.signIn(email: email, password: password) {
            case .signedIn(let session):
                try sessionStore.save(session)
                return .success(email: session.userEmail)
            case .needsEmailConfirmation:
                return .needsEmailConfirmation
            }
        } catch let error as SharedTagCloudError {
            return .failure(error.userMessage)
        } catch {
            return .failure(error.localizedDescription)
        }
    }

    func signUp(email: String, password: String) async -> SharedTagAuthResult {
        guard config.isConfigured else {
            return .failure("共有タグクラウドの設定がありません")
        }
        do {
            switch try await authRemoteDataSource.signUp(email: email, password: password) {
            case .signedIn(let session):
                try sessionStore.save(session)
                return .success(email: session.userEmail)
            case .needsEmailConfirmation:
                return .needsEmailConfirmation
            }
        } catch let error as SharedTagCloudError {
            return .failure(error.userMessage)
        } catch {
            return .failure(error.localizedDescription)
        }
    }

    func signOut() throws {
        try sessionStore.clear()
        try store.clearLocalSharedState()
    }

    func syncCurrentSession() async -> Bool {
        guard config.isConfigured, let session = try? sessionStore.load() else { return true }
        do {
            try await refreshLocalState(session: session)
            return true
        } catch {
            return false
        }
    }

    func createTag(name: String) async -> SharedTagMutationResult {
        let normalizedName = Self.normalizeTagName(name)
        guard !normalizedName.isEmpty else {
            return .failure("共有タグ名を入力してください")
        }
        guard normalizedName.count <= maxSharedTagNameLength else {
            return .failure("共有タグ名は\(maxSharedTagNameLength)文字以内で入力してください")
        }
        guard let session = try? sessionStore.load() else {
            return .authRequired
        }
        do {
            let operation = SharedTagSyncOperation(
                opID: UUID().uuidString.lowercased(),
                clientID: UUID().uuidString.lowercased(),
                type: .createTag,
                submittedAt: Self.currentSubmittedAt(),
                tagID: UUID().uuidString.lowercased(),
                name: normalizedName
            )
            _ = try await syncRemoteDataSource.applyOperations(session: session, operations: [operation])
            try await refreshLocalState(session: session)
            return .success
        } catch let error as SharedTagCloudError {
            return .failure(error.userMessage)
        } catch {
            return .failure(error.localizedDescription)
        }
    }

    func renameTag(remoteTagID: String, name: String) async -> SharedTagMutationResult {
        let normalizedName = Self.normalizeTagName(name)
        guard !normalizedName.isEmpty else {
            return .failure("共有タグ名を入力してください")
        }
        guard normalizedName.count <= maxSharedTagNameLength else {
            return .failure("共有タグ名は\(maxSharedTagNameLength)文字以内で入力してください")
        }
        guard let session = try? sessionStore.load() else {
            return .authRequired
        }
        do {
            guard let tag = try store.loadTag(authUserID: session.authUserID, remoteTagID: remoteTagID) else {
                return .failure("共有タグが見つかりませんでした")
            }
            guard tag.currentUserRole == .owner || tag.currentUserRole == .editor else {
                return .failure("この共有タグは編集できません")
            }
            let operation = SharedTagSyncOperation(
                opID: UUID().uuidString.lowercased(),
                clientID: UUID().uuidString.lowercased(),
                type: .renameTag,
                submittedAt: Self.currentSubmittedAt(),
                tagID: remoteTagID,
                name: normalizedName
            )
            _ = try await syncRemoteDataSource.applyOperations(session: session, operations: [operation])
            try await refreshLocalState(session: session)
            return .success
        } catch let error as SharedTagCloudError {
            return .failure(error.userMessage)
        } catch {
            return .failure(error.localizedDescription)
        }
    }

    func deleteTag(remoteTagID: String) async -> SharedTagMutationResult {
        guard let session = try? sessionStore.load() else {
            return .authRequired
        }
        do {
            guard let tag = try store.loadTag(authUserID: session.authUserID, remoteTagID: remoteTagID) else {
                return .failure("共有タグが見つかりませんでした")
            }
            guard tag.currentUserRole == .owner else {
                return .failure("共有タグを削除できるのはオーナーだけです")
            }
            let operation = SharedTagSyncOperation(
                opID: UUID().uuidString.lowercased(),
                clientID: UUID().uuidString.lowercased(),
                type: .deleteTag,
                submittedAt: Self.currentSubmittedAt(),
                tagID: remoteTagID
            )
            _ = try await syncRemoteDataSource.applyOperations(session: session, operations: [operation])
            try await refreshLocalState(session: session)
            return .success
        } catch let error as SharedTagCloudError {
            return .failure(error.userMessage)
        } catch {
            return .failure(error.localizedDescription)
        }
    }

    func leaveTag(remoteTagID: String) async -> SharedTagMutationResult {
        guard let session = try? sessionStore.load() else {
            return .authRequired
        }
        do {
            guard let tag = try store.loadTag(authUserID: session.authUserID, remoteTagID: remoteTagID) else {
                return .failure("共有タグが見つかりませんでした")
            }
            guard let role = tag.currentUserRole else {
                return .failure("この共有タグの参加状態を確認できません")
            }
            guard role != .owner else {
                return .failure("オーナーは共有タグ自体の削除を使ってください")
            }
            let operation = SharedTagSyncOperation(
                opID: UUID().uuidString.lowercased(),
                clientID: UUID().uuidString.lowercased(),
                type: .removeMember,
                submittedAt: Self.currentSubmittedAt(),
                tagID: remoteTagID,
                userID: session.authUserID
            )
            _ = try await syncRemoteDataSource.applyOperations(session: session, operations: [operation])
            try await refreshLocalState(session: session)
            return .success
        } catch let error as SharedTagCloudError {
            return .failure(error.userMessage)
        } catch {
            return .failure(error.localizedDescription)
        }
    }

    func assignEntry(remoteTagID: String, entryID: Int64) async -> SharedTagMutationResult {
        guard let session = try? sessionStore.load() else {
            return .authRequired
        }
        do {
            guard let tag = try store.loadTag(authUserID: session.authUserID, remoteTagID: remoteTagID) else {
                return .failure("共有タグが見つかりませんでした")
            }
            guard tag.currentUserRole == .owner || tag.currentUserRole == .editor else {
                return .failure("この共有タグには追加できません")
            }
            guard let entry = try repository.loadEntry(id: entryID) else {
                return .failure("URLが見つかりませんでした")
            }
            let operation = SharedTagSyncOperation(
                opID: UUID().uuidString.lowercased(),
                clientID: UUID().uuidString.lowercased(),
                type: .addURLToTag,
                submittedAt: Self.currentSubmittedAt(),
                tagID: remoteTagID,
                urlID: UUID().uuidString.lowercased(),
                rawURL: entry.originalURL,
                normalizedURL: entry.normalizedURL,
                normalizationVersion: sharedTagNormalizationVersion
            )
            _ = try await syncRemoteDataSource.applyOperations(session: session, operations: [operation])
            try await refreshLocalState(session: session)
            return .success
        } catch let error as SharedTagCloudError {
            return .failure(error.userMessage)
        } catch {
            return .failure(error.localizedDescription)
        }
    }

    func removeEntry(remoteTagID: String, entryID: Int64) async -> SharedTagMutationResult {
        guard let session = try? sessionStore.load() else {
            return .authRequired
        }
        do {
            guard let tag = try store.loadTag(authUserID: session.authUserID, remoteTagID: remoteTagID) else {
                return .failure("共有タグが見つかりませんでした")
            }
            guard tag.currentUserRole == .owner || tag.currentUserRole == .editor else {
                return .failure("このURLを共有タグから外す権限がありません")
            }
            guard let entry = try repository.loadEntry(id: entryID) else {
                return .failure("URLが見つかりませんでした")
            }
            let operation = SharedTagSyncOperation(
                opID: UUID().uuidString.lowercased(),
                clientID: UUID().uuidString.lowercased(),
                type: .removeURLFromTag,
                submittedAt: Self.currentSubmittedAt(),
                tagID: remoteTagID,
                normalizedURL: entry.normalizedURL
            )
            _ = try await syncRemoteDataSource.applyOperations(session: session, operations: [operation])
            try await refreshLocalState(session: session)
            return .success
        } catch let error as SharedTagCloudError {
            return .failure(error.userMessage)
        } catch {
            return .failure(error.localizedDescription)
        }
    }

    func createInvite(remoteTagID: String) async -> SharedTagInviteCreationResult {
        guard config.isConfigured else {
            return .failure("共有タグクラウドの設定がありません")
        }
        guard let session = try? sessionStore.load() else {
            return .authRequired
        }
        do {
            guard let tag = try store.loadTag(authUserID: session.authUserID, remoteTagID: remoteTagID) else {
                return .failure("共有タグが見つかりませんでした")
            }
            guard tag.lastSyncedAt != nil else {
                return .syncPending
            }
            guard tag.currentUserRole == .owner else {
                return .ownerOnly
            }
            let invite = try await syncRemoteDataSource.createInvite(
                session: session,
                remoteTagID: remoteTagID,
                role: sharedTagInviteRole
            )
            return .success(
                inviteURL: "urlsaver://invite/\(invite.inviteToken)",
                expiresAt: SharedTagStore.parseISO8601(invite.expiresAt)
            )
        } catch let error as SharedTagCloudError {
            return .failure(error.userMessage)
        } catch {
            return .failure(error.localizedDescription)
        }
    }

    func acceptInvite(inviteToken: String) async -> SharedTagInviteAcceptanceResult {
        guard config.isConfigured else {
            return .failure("共有タグクラウドの設定がありません")
        }
        guard let session = try? sessionStore.load() else {
            return .authRequired
        }
        do {
            let response = try await syncRemoteDataSource.acceptInvite(session: session, inviteToken: inviteToken)
            _ = await syncCurrentSession()
            return .accepted(tagName: response.tagName, role: SharedTagMemberRole(rawValue: response.role.lowercased()))
        } catch let error as SharedTagCloudError {
            switch error {
            case .authRequired:
                return .authRequired
            case .invalidInvite:
                return .invalidInvite
            default:
                return .failure(error.userMessage)
            }
        } catch {
            let message = error.localizedDescription.lowercased()
            if message.contains("invalid") || message.contains("expired") || message.contains("token") {
                return .invalidInvite
            }
            return .failure(error.localizedDescription)
        }
    }

    func deleteAccount() async -> SharedTagAccountDeletionResult {
        guard config.isConfigured else {
            return .failure("共有タグクラウドの設定がありません")
        }
        guard let session = try? sessionStore.load() else {
            return .authRequired
        }
        do {
            try await syncRemoteDataSource.deleteAccount(session: session)
            try signOut()
            return .success
        } catch let error as SharedTagCloudError {
            switch error {
            case .authRequired:
                return .authRequired
            case .ownerTransferRequired:
                return .ownerTransferRequired
            case .httpStatus(_, let message):
                if message.contains("owner_transfer_required") {
                    return .ownerTransferRequired
                }
                return .failure(error.userMessage)
            default:
                return .failure(error.userMessage)
            }
        } catch {
            if error.localizedDescription.contains("owner_transfer_required") {
                return .ownerTransferRequired
            }
            return .failure(error.localizedDescription)
        }
    }

    private func refreshLocalState(session: SharedTagAuthSession) async throws {
        let snapshot = try await syncRemoteDataSource.pullSnapshot(session: session)
        try store.applySnapshot(authUserID: session.authUserID, snapshot: snapshot)
    }

    private static func normalizeTagName(_ value: String) -> String {
        value.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    private static func currentSubmittedAt() -> Int64 {
        Int64(Date().timeIntervalSince1970 * 1_000)
    }
}

private struct EmptyPayload: Encodable {}

private struct ApplySharedTagOpsPayload: Encodable {
    let payload: [SharedTagSyncOperation]
}

private struct AcceptInvitePayload: Encodable {
    let pToken: String

    enum CodingKeys: String, CodingKey {
        case pToken = "p_token"
    }

    init(token: String) {
        self.pToken = token
    }
}

private struct CreateInvitePayload: Encodable {
    let pTagID: String
    let pRole: String

    enum CodingKeys: String, CodingKey {
        case pTagID = "p_tag_id"
        case pRole = "p_role"
    }

    init(tagID: String, role: String) {
        self.pTagID = tagID
        self.pRole = role
    }
}

private struct SupabasePasswordAuthRequest: Encodable {
    let email: String
    let password: String
}

private struct SupabaseRefreshTokenRequest: Encodable {
    let refreshToken: String

    enum CodingKeys: String, CodingKey {
        case refreshToken = "refresh_token"
    }
}

private struct SupabaseAuthSessionResponse: Decodable {
    let accessToken: String?
    let refreshToken: String?
    let user: SupabaseAuthUser?

    enum CodingKeys: String, CodingKey {
        case accessToken = "access_token"
        case refreshToken = "refresh_token"
        case user
    }
}

private struct SupabaseAuthUser: Decodable {
    let id: String
    let email: String?
}

private struct SupabaseAuthErrorResponse: Decodable {
    let message: String
}

private struct SharedTagSyncOperation: Encodable {
    let opID: String
    let clientID: String
    let type: SharedTagSyncOperationType
    let submittedAt: Int64
    let tagID: String?
    let name: String?
    let urlID: String?
    let rawURL: String?
    let normalizedURL: String?
    let normalizationVersion: Int?
    let userID: String?
    let role: String?

    init(
        opID: String,
        clientID: String,
        type: SharedTagSyncOperationType,
        submittedAt: Int64,
        tagID: String? = nil,
        name: String? = nil,
        urlID: String? = nil,
        rawURL: String? = nil,
        normalizedURL: String? = nil,
        normalizationVersion: Int? = nil,
        userID: String? = nil,
        role: String? = nil
    ) {
        self.opID = opID
        self.clientID = clientID
        self.type = type
        self.submittedAt = submittedAt
        self.tagID = tagID
        self.name = name
        self.urlID = urlID
        self.rawURL = rawURL
        self.normalizedURL = normalizedURL
        self.normalizationVersion = normalizationVersion
        self.userID = userID
        self.role = role
    }

    enum CodingKeys: String, CodingKey {
        case opID = "op_id"
        case clientID = "client_id"
        case type
        case submittedAt = "submitted_at"
        case tagID = "tag_id"
        case name
        case urlID = "url_id"
        case rawURL = "raw_url"
        case normalizedURL = "normalized_url"
        case normalizationVersion = "normalization_version"
        case userID = "user_id"
        case role
    }
}

private enum SharedTagSyncOperationType: String, Encodable {
    case createTag = "create_tag"
    case renameTag = "rename_tag"
    case deleteTag = "delete_tag"
    case addURLToTag = "add_url_to_tag"
    case removeURLFromTag = "remove_url_from_tag"
    case inviteMember = "invite_member"
    case changeMemberRole = "change_member_role"
    case removeMember = "remove_member"
}

private struct ApplySharedTagOpsResponse: Decodable {
    let results: [SharedTagOpApplyResult]
}

private struct SharedTagOpApplyResult: Decodable {
    let opID: String
    let status: String
    let tagID: String?
    let urlID: String?
    let normalizedURL: String?
    let userID: String?

    enum CodingKeys: String, CodingKey {
        case opID = "op_id"
        case status
        case tagID = "tag_id"
        case urlID = "url_id"
        case normalizedURL = "normalized_url"
        case userID = "user_id"
    }
}

struct PullSharedTagSnapshotResponse: Decodable, Sendable {
    let pulledAt: String
    let normalizationVersion: Int
    let tags: [RemoteSharedTag]
    let members: [RemoteSharedTagMember]
    let urls: [RemoteSharedTagURL]

    enum CodingKeys: String, CodingKey {
        case pulledAt = "pulled_at"
        case normalizationVersion = "normalization_version"
        case tags
        case members
        case urls
    }
}

struct RemoteSharedTag: Decodable, Sendable {
    let id: String
    let name: String
    let createdAt: String
    let updatedAt: String
    let deletedAt: String?

    enum CodingKeys: String, CodingKey {
        case id
        case name
        case createdAt = "created_at"
        case updatedAt = "updated_at"
        case deletedAt = "deleted_at"
    }
}

struct RemoteSharedTagMember: Decodable, Sendable {
    let tagID: String
    let userID: String
    let role: String
    let status: String
    let createdAt: String
    let updatedAt: String

    enum CodingKeys: String, CodingKey {
        case tagID = "tag_id"
        case userID = "user_id"
        case role
        case status
        case createdAt = "created_at"
        case updatedAt = "updated_at"
    }
}

struct RemoteSharedTagURL: Decodable, Sendable {
    let id: String
    let tagID: String
    let rawURL: String
    let normalizedURL: String
    let deletedAt: String?

    enum CodingKeys: String, CodingKey {
        case id
        case tagID = "tag_id"
        case rawURL = "raw_url"
        case normalizedURL = "normalized_url"
        case deletedAt = "deleted_at"
    }
}

private struct AcceptSharedTagInviteResponse: Decodable {
    let tagName: String
    let role: String

    enum CodingKeys: String, CodingKey {
        case tagName = "tag_name"
        case role
    }
}

private struct CreateSharedTagInviteResponse: Decodable {
    let inviteToken: String
    let expiresAt: String

    enum CodingKeys: String, CodingKey {
        case inviteToken = "invite_token"
        case expiresAt = "expires_at"
    }
}

private func makeSharedTagCloudEncoder() -> JSONEncoder {
    JSONEncoder()
}

private func makeSharedTagCloudDecoder() -> JSONDecoder {
    JSONDecoder()
}
