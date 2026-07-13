import CryptoKit
import Foundation
import Security
import SQLite3

struct SharedTagCloudConfig: Sendable {
    let enabled: Bool
    let supabaseURL: String
    let anonKey: String
    let inviteLinkBaseURL: String
    var personalLinkSyncEnabled: Bool = false

    init(
        enabled: Bool,
        supabaseURL: String,
        anonKey: String,
        inviteLinkBaseURL: String = "",
        personalLinkSyncEnabled: Bool = false
    ) {
        self.enabled = enabled
        self.supabaseURL = supabaseURL
        self.anonKey = anonKey
        self.inviteLinkBaseURL = Self.normalizedInviteLinkBaseURL(inviteLinkBaseURL)
        self.personalLinkSyncEnabled = personalLinkSyncEnabled
    }

    init(
        bundle: Bundle = .main,
        environment: [String: String] = ProcessInfo.processInfo.environment
    ) {
        let envEnabled = environment["URLSAVER_SHARED_TAG_CLOUD_ENABLED"]?.trimmingCharacters(in: .whitespacesAndNewlines)
        let envURL = environment["URLSAVER_SUPABASE_URL"]?.trimmingCharacters(in: .whitespacesAndNewlines)
        let envAnonKey = environment["URLSAVER_SUPABASE_ANON_KEY"]?.trimmingCharacters(in: .whitespacesAndNewlines)
        let envInviteLinkBaseURL = environment["URLSAVER_INVITE_LINK_BASE_URL"]?.trimmingCharacters(in: .whitespacesAndNewlines)
        let envPersonalLinkSyncEnabled = environment["URLSAVER_CHATGPT_PERSONAL_LINK_SYNC_ENABLED"]?.trimmingCharacters(in: .whitespacesAndNewlines)
        let bundleEnabled = bundle.object(forInfoDictionaryKey: "SharedTagCloudEnabled") as? Bool
        let bundleEnabledString = bundle.object(forInfoDictionaryKey: "SharedTagCloudEnabled") as? String
        let bundleURL = bundle.object(forInfoDictionaryKey: "SupabaseURL") as? String
        let bundleAnonKey = bundle.object(forInfoDictionaryKey: "SupabaseAnonKey") as? String
        let bundleInviteLinkBaseURL = bundle.object(forInfoDictionaryKey: "InviteLinkBaseURL") as? String
        let bundlePersonalLinkSyncEnabled = bundle.object(forInfoDictionaryKey: "ChatGptPersonalLinkSyncEnabled")

        enabled = envEnabled.map(Self.parseEnabledFlag)
            ?? bundleEnabled
            ?? bundleEnabledString.map(Self.parseEnabledFlag)
            ?? false
        supabaseURL = envURL ?? bundleURL?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        anonKey = envAnonKey ?? bundleAnonKey?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        inviteLinkBaseURL = Self.normalizedInviteLinkBaseURL(envInviteLinkBaseURL ?? bundleInviteLinkBaseURL)
        personalLinkSyncEnabled = envPersonalLinkSyncEnabled.map(Self.parseEnabledFlag)
            ?? Self.parseEnabledFlag(bundlePersonalLinkSyncEnabled)
            ?? false
    }

    var isConfigured: Bool {
        enabled && !supabaseURL.isEmpty && !anonKey.isEmpty
    }

    var isPersonalLinkSyncConfigured: Bool {
        isConfigured && personalLinkSyncEnabled
    }

    private static func parseEnabledFlag(_ raw: String) -> Bool {
        switch raw.lowercased() {
        case "1", "true", "yes", "on":
            return true
        default:
            return false
        }
    }

    private static func parseEnabledFlag(_ value: Any?) -> Bool? {
        if let value = value as? Bool {
            return value
        }
        guard let value = value as? String else { return nil }
        return parseEnabledFlag(value)
    }

    private static func normalizedInviteLinkBaseURL(_ raw: String?) -> String {
        let trimmed = raw?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        let value = trimmed.isEmpty ? "https://miyamibu.xyz" : trimmed
        return value.trimmingTrailingSlashes()
    }
}

struct ContactSupportConfig: Sendable {
    let endpointURL: String

    init(
        bundle: Bundle = .main,
        environment: [String: String] = ProcessInfo.processInfo.environment
    ) {
        let envURL = environment["URLSAVER_CONTACT_SUPPORT_ENDPOINT_URL"]?.trimmingCharacters(in: .whitespacesAndNewlines)
        let bundleURL = bundle.object(forInfoDictionaryKey: "ContactSupportEndpointURL") as? String
        endpointURL = envURL ?? bundleURL?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
    }

    var isConfigured: Bool {
        !endpointURL.isEmpty
    }
}

struct ContactSupportRequest: Codable, Equatable {
    let email: String
    let name: String
    let message: String
    let platform: String
    let appVersion: String
    let buildType: String
    let isSignedIn: Bool
    let authUserId: String?
}

enum ContactSupportResult: Equatable {
    case success(String)
    case failure(String)
}

final class ContactSupportClient: @unchecked Sendable {
    private let config: ContactSupportConfig
    private let session: URLSession

    init(
        config: ContactSupportConfig,
        session: URLSession = .shared
    ) {
        self.config = config
        self.session = session
    }

    func send(_ payload: ContactSupportRequest) async -> ContactSupportResult {
        guard config.isConfigured,
              let url = URL(string: config.endpointURL) else {
            return .failure("問い合わせ送信先が設定されていません")
        }

        do {
            var request = URLRequest(url: url)
            request.httpMethod = "POST"
            request.timeoutInterval = 30
            request.setValue("application/json", forHTTPHeaderField: "Content-Type")
            request.httpBody = try JSONEncoder().encode(payload)

            let (data, response) = try await session.data(for: request)
            guard let httpResponse = response as? HTTPURLResponse else {
                return .failure("問い合わせを送信できませんでした")
            }
            if 200..<300 ~= httpResponse.statusCode {
                let accepted = try? JSONDecoder().decode(ContactSupportAcceptedResponse.self, from: data)
                return .success(accepted?.requestId ?? "")
            }
            let serverError = (try? JSONDecoder().decode(ContactSupportErrorResponse.self, from: data))?.error
            return .failure(Self.normalizeErrorMessage(statusCode: httpResponse.statusCode, serverError: serverError))
        } catch {
            return .failure("通信に失敗しました。接続を確認して再度お試しください。")
        }
    }

    private static func normalizeErrorMessage(statusCode: Int, serverError: String?) -> String {
        let normalized = serverError?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        if statusCode == 429 || normalized.caseInsensitiveCompare("rate_limited") == .orderedSame {
            return "短時間に問い合わせが多すぎます。少し時間をおいて再度お試しください。"
        }
        if normalized.caseInsensitiveCompare("missing_required_fields") == .orderedSame {
            return "メールアドレス、氏名、問い合わせ内容を入力してください。"
        }
        if normalized.caseInsensitiveCompare("invalid_email") == .orderedSame {
            return "メールアドレスの形式を確認してください。"
        }
        if normalized.caseInsensitiveCompare("message_too_long") == .orderedSame {
            return "問い合わせ内容が長すぎます。短くして再度お試しください。"
        }
        if normalized.range(of: "resend", options: .caseInsensitive) != nil {
            return "問い合わせを送信できませんでした。時間をおいて再度お試しください。"
        }
        if !normalized.isEmpty && normalized.contains(where: { !$0.isASCII }) {
            return normalized
        }
        switch statusCode {
        case 400:
            return "入力内容を確認してください。"
        case 502:
            return "問い合わせを送信できませんでした。時間をおいて再度お試しください。"
        default:
            return "問い合わせを送信できませんでした。時間をおいて再度お試しください。"
        }
    }
}

private extension Character {
    var isASCII: Bool {
        unicodeScalars.allSatisfy(\.isASCII)
    }
}

final class ContactSupportService: @unchecked Sendable {
    private let client: ContactSupportClient
    private let sessionStore: SharedTagAuthSessionStore

    init(
        config: ContactSupportConfig,
        sessionStore: SharedTagAuthSessionStore,
        client: ContactSupportClient? = nil
    ) {
        self.sessionStore = sessionStore
        self.client = client ?? ContactSupportClient(config: config)
    }

    func send(email: String, name: String, message: String, isSignedIn: Bool) async -> ContactSupportResult {
        let session = try? sessionStore.load()
        let appVersion = Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "unknown"
        #if DEBUG
        let buildType = "debug"
        #else
        let buildType = "release"
        #endif
        return await client.send(
            ContactSupportRequest(
                email: email.trimmingCharacters(in: .whitespacesAndNewlines),
                name: name.trimmingCharacters(in: .whitespacesAndNewlines),
                message: message.trimmingCharacters(in: .whitespacesAndNewlines),
                platform: "ios",
                appVersion: appVersion,
                buildType: buildType,
                isSignedIn: isSignedIn,
                authUserId: session?.authUserID
            )
        )
    }
}

private struct ContactSupportAcceptedResponse: Decodable {
    let requestId: String
    let status: String?
}

private struct ContactSupportErrorResponse: Decodable {
    let error: String
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

struct SharedTagOAuthPendingState: Codable, Equatable, Sendable {
    let provider: String
    let codeVerifier: String
    let redirectTo: String
    let expiresAt: Date
}

final class SharedTagOAuthStateStore: @unchecked Sendable {
    private let storage: any SharedTagAuthSecureStorage
    private let nowProvider: @Sendable () -> Date

    init(
        service: String = "jp.mimac.urlsaver.shared-tag-auth",
        account: String = "oauth-pending",
        nowProvider: @Sendable @escaping () -> Date = Date.init
    ) {
        self.storage = KeychainSharedTagAuthSecureStorage(service: service, account: account)
        self.nowProvider = nowProvider
    }

    init(
        storage: any SharedTagAuthSecureStorage,
        nowProvider: @Sendable @escaping () -> Date = Date.init
    ) {
        self.storage = storage
        self.nowProvider = nowProvider
    }

    func save(_ pending: SharedTagOAuthPendingState) throws {
        let encoder = JSONEncoder()
        encoder.dateEncodingStrategy = .iso8601
        try storage.save(try encoder.encode(pending))
    }

    func consume(provider: String, redirectTo: String) throws -> SharedTagOAuthPendingState {
        guard let data = try storage.load() else {
            throw SharedTagCloudError.message("OAuth state is missing.")
        }
        try storage.clear()
        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .iso8601
        let pending = try decoder.decode(SharedTagOAuthPendingState.self, from: data)
        guard pending.provider == provider,
              pending.redirectTo == redirectTo else {
            throw SharedTagCloudError.message("OAuth state is invalid.")
        }
        guard pending.expiresAt > nowProvider() else {
            throw SharedTagCloudError.message("OAuth state expired.")
        }
        return pending
    }

    func clear() throws {
        try storage.clear()
    }
}

private enum SharedTagAuthRemoteResult {
    case signedIn(SharedTagAuthSession)
    case needsEmailConfirmation
}

private struct SharedTagAuthRemoteDataSource {
    private let config: SharedTagCloudConfig
    private let oauthStateStore: SharedTagOAuthStateStore
    private static let authCallbackURL = "urlsaver://auth/callback"
    private static let oauthStateTTL: TimeInterval = 10 * 60

    init(
        config: SharedTagCloudConfig,
        oauthStateStore: SharedTagOAuthStateStore = SharedTagOAuthStateStore()
    ) {
        self.config = config
        self.oauthStateStore = oauthStateStore
    }

    func oauthURL(provider: String, redirectTo: String) throws -> URL {
        guard config.isConfigured else {
            throw SharedTagCloudError.message("共有タグクラウドの設定がありません")
        }
        guard Self.isAllowedAuthCallback(URL(string: redirectTo)) else {
            throw SharedTagCloudError.message("OAuth redirect URL is invalid.")
        }
        let codeVerifier = Self.randomURLSafeString(byteCount: 48)
        let codeChallenge = Self.pkceCodeChallenge(for: codeVerifier)
        try oauthStateStore.save(
            SharedTagOAuthPendingState(
                provider: provider,
                codeVerifier: codeVerifier,
                redirectTo: redirectTo,
                expiresAt: Date().addingTimeInterval(Self.oauthStateTTL)
            )
        )
        let query = [
            "provider=\(Self.percentEncodedQueryValue(provider))",
            "redirect_to=\(Self.percentEncodedQueryValue(redirectTo))",
            "code_challenge=\(Self.percentEncodedQueryValue(codeChallenge))",
            "code_challenge_method=S256",
        ].joined(separator: "&")
        guard let url = URL(string: config.supabaseURL.trimmingTrailingSlashes() + "/auth/v1/authorize?\(query)") else {
            throw SharedTagCloudError.message("GoogleサインインURLを作成できませんでした")
        }
        return url
    }

    func signInWithOAuthCallback(url: URL) async throws -> SharedTagAuthRemoteResult {
        guard Self.isAllowedAuthCallback(url) else {
            try? oauthStateStore.clear()
            throw SharedTagCloudError.message("OAuth callback URL is invalid.")
        }
        let params = callbackParameters(from: url)
        if let error = params["error_description"] ?? params["error"], !error.isEmpty {
            try? oauthStateStore.clear()
            throw SharedTagCloudError.message(error)
        }
        if params["access_token"]?.isEmpty == false || params["refresh_token"]?.isEmpty == false || url.fragment?.isEmpty == false {
            try? oauthStateStore.clear()
            throw SharedTagCloudError.message("OAuth token callback is not accepted.")
        }
        guard let code = params["code"], !code.isEmpty else {
            try? oauthStateStore.clear()
            throw SharedTagCloudError.message("OAuth code callback is missing.")
        }
        let pending = try oauthStateStore.consume(
            provider: params["provider"] ?? "google",
            redirectTo: Self.authCallbackURL
        )
        let response = try await executeAuthRequest(
            path: "/auth/v1/token?grant_type=pkce",
            body: SupabasePKCEAuthRequest(authCode: code, codeVerifier: pending.codeVerifier)
        )
        return .signedIn(try requireSession(from: response))
    }

    func signUp(email: String, password: String) async throws -> SharedTagAuthRemoteResult {
        let response = try await executeAuthRequest(
            path: authPathWithRedirect("/auth/v1/signup"),
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

    func resendEmailConfirmation(email: String) async throws {
        _ = try await executeAuthRequest(
            path: "/auth/v1/resend",
            body: SupabaseResendRequest(
                type: "signup",
                email: email.trimmingCharacters(in: .whitespacesAndNewlines),
                options: SupabaseEmailRedirectOptions(emailRedirectTo: Self.authCallbackURL)
            )
        )
    }

    func sendPasswordRecovery(email: String) async throws {
        _ = try await executeAuthRequest(
            path: authPathWithRedirect("/auth/v1/recover"),
            body: SupabasePasswordRecoveryRequest(email: email.trimmingCharacters(in: .whitespacesAndNewlines))
        )
    }

    func signInWithAppleIDToken(idToken: String, nonce: String) async throws -> SharedTagAuthRemoteResult {
        let response = try await executeAuthRequest(
            path: "/auth/v1/token?grant_type=id_token",
            body: SupabaseIDTokenAuthRequest(provider: "apple", idToken: idToken, nonce: nonce)
        )
        return try parseSessionResult(response)
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

    private func authPathWithRedirect(_ path: String) -> String {
        let encodedRedirect = Self.percentEncodedQueryValue(Self.authCallbackURL)
        let separator = path.contains("?") ? "&" : "?"
        return "\(path)\(separator)redirect_to=\(encodedRedirect)"
    }

    private static func percentEncodedQueryValue(_ value: String) -> String {
        var allowed = CharacterSet.urlQueryAllowed
        allowed.remove(charactersIn: ":#[]@!$&'()*+,;=/?")
        return value.addingPercentEncoding(withAllowedCharacters: allowed) ?? value
    }

    static func isAllowedAuthCallback(_ url: URL?) -> Bool {
        guard let url else { return false }
        return url.scheme == "urlsaver" &&
            url.host == "auth" &&
            url.path == "/callback"
    }

    static func pkceCodeChallenge(for verifier: String) -> String {
        let data = Data(SHA256.hash(data: Data(verifier.utf8)))
        return data.base64URLEncodedString()
    }

    private static func randomURLSafeString(byteCount: Int) -> String {
        var bytes = [UInt8](repeating: 0, count: byteCount)
        let status = SecRandomCopyBytes(kSecRandomDefault, byteCount, &bytes)
        if status == errSecSuccess {
            return Data(bytes).base64URLEncodedString()
        }
        return UUID().uuidString.replacingOccurrences(of: "-", with: "").lowercased()
    }

    private func callbackParameters(from url: URL) -> [String: String] {
        let queryItems = URLComponents(url: url, resolvingAgainstBaseURL: false)?.queryItems ?? []
        var params = Dictionary(uniqueKeysWithValues: queryItems.map { ($0.name, $0.value ?? "") })
        if let fragment = url.fragment,
           let fragmentComponents = URLComponents(string: "urlsaver://auth/callback?\(fragment)") {
            for item in fragmentComponents.queryItems ?? [] {
                params[item.name] = item.value ?? ""
            }
        }
        return params
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

    func previewInvite(inviteToken: String) async throws -> PreviewSharedTagInviteResponse {
        let data = try await executeUnauthenticatedRPC(
            path: "/rest/v1/rpc/preview_shared_invite",
            body: AcceptInvitePayload(token: inviteToken)
        )
        return try makeSharedTagCloudDecoder().decode(PreviewSharedTagInviteResponse.self, from: data)
    }

    func acceptInvite(session: SharedTagAuthSession, inviteToken: String) async throws -> AcceptSharedTagInviteResponse {
        let data = try await executeRPC(
            path: "/rest/v1/rpc/accept_shared_invite",
            session: session,
            body: AcceptInvitePayload(token: inviteToken)
        )
        return try makeSharedTagCloudDecoder().decode(AcceptSharedTagInviteResponse.self, from: data)
    }

    func createGroup(session: SharedTagAuthSession, name: String) async throws -> CreateSharedTagGroupResponse {
        let data = try await executeRPC(
            path: "/rest/v1/rpc/create_shared_tag_group",
            session: session,
            body: CreateGroupPayload(name: name)
        )
        return try makeSharedTagCloudDecoder().decode(CreateSharedTagGroupResponse.self, from: data)
    }

    func createGroupInvite(
        session: SharedTagAuthSession,
        remoteGroupID: String,
        role: String
    ) async throws -> CreateSharedTagGroupInviteResponse {
        let data = try await executeRPC(
            path: "/rest/v1/rpc/create_shared_tag_group_invite",
            session: session,
            body: CreateGroupInvitePayload(groupID: remoteGroupID, role: role)
        )
        return try makeSharedTagCloudDecoder().decode(CreateSharedTagGroupInviteResponse.self, from: data)
    }

    func addTagToGroup(
        session: SharedTagAuthSession,
        remoteGroupID: String,
        remoteTagID: String
    ) async throws {
        _ = try await executeRPC(
            path: "/rest/v1/rpc/add_shared_tag_to_group",
            session: session,
            body: GroupTagPayload(groupID: remoteGroupID, tagID: remoteTagID)
        )
    }

    func removeTagFromGroup(
        session: SharedTagAuthSession,
        remoteGroupID: String,
        remoteTagID: String
    ) async throws {
        _ = try await executeRPC(
            path: "/rest/v1/rpc/remove_shared_tag_from_group",
            session: session,
            body: GroupTagPayload(groupID: remoteGroupID, tagID: remoteTagID)
        )
    }

    func renameGroup(session: SharedTagAuthSession, remoteGroupID: String, name: String) async throws {
        _ = try await executeRPC(
            path: "/rest/v1/rpc/rename_shared_tag_group",
            session: session,
            body: RenameGroupPayload(groupID: remoteGroupID, name: name)
        )
    }

    func deleteGroup(session: SharedTagAuthSession, remoteGroupID: String) async throws {
        _ = try await executeRPC(
            path: "/rest/v1/rpc/delete_shared_tag_group",
            session: session,
            body: GroupPayload(groupID: remoteGroupID)
        )
    }

    func changeGroupMemberRole(
        session: SharedTagAuthSession,
        remoteGroupID: String,
        userID: String,
        role: String
    ) async throws {
        _ = try await executeRPC(
            path: "/rest/v1/rpc/change_shared_tag_group_member_role",
            session: session,
            body: GroupMemberRolePayload(groupID: remoteGroupID, userID: userID, role: role)
        )
    }

    func transferGroupOwnership(
        session: SharedTagAuthSession,
        remoteGroupID: String,
        newOwnerUserID: String
    ) async throws {
        _ = try await executeRPC(
            path: "/rest/v1/rpc/transfer_shared_tag_group_ownership",
            session: session,
            body: GroupOwnershipPayload(groupID: remoteGroupID, newOwnerUserID: newOwnerUserID)
        )
    }

    func removeGroupMember(session: SharedTagAuthSession, remoteGroupID: String, userID: String) async throws {
        _ = try await executeRPC(
            path: "/rest/v1/rpc/remove_shared_tag_group_member",
            session: session,
            body: GroupMemberPayload(groupID: remoteGroupID, userID: userID)
        )
    }

    func upsertSharedProfile(session: SharedTagAuthSession, displayName: String) async throws {
        _ = try await executeRPC(
            path: "/rest/v1/rpc/upsert_my_shared_profile",
            session: session,
            body: SharedProfilePayload(displayName: displayName)
        )
    }

    func transferOwnership(
        session: SharedTagAuthSession,
        remoteTagID: String,
        newOwnerUserID: String
    ) async throws -> TransferSharedTagOwnershipResponse {
        let data = try await executeRPC(
            path: "/rest/v1/rpc/transfer_shared_tag_ownership",
            session: session,
            body: TransferOwnershipPayload(tagID: remoteTagID, newOwnerUserID: newOwnerUserID)
        )
        return try makeSharedTagCloudDecoder().decode(TransferSharedTagOwnershipResponse.self, from: data)
    }

    func deleteAccount(session: SharedTagAuthSession) async throws {
        _ = try await executeRPC(
            path: "/rest/v1/rpc/delete_my_account",
            session: session,
            body: EmptyPayload()
        )
    }

    func setChatGptPersonalLinkSync(
        session: SharedTagAuthSession,
        enabled: Bool,
        contentFetchEnabled: Bool
    ) async throws {
        _ = try await executeRPC(
            path: "/rest/v1/rpc/set_personal_link_chatgpt_sync",
            session: session,
            body: SetPersonalLinkChatGptSyncPayload(
                enabled: enabled,
                contentFetchEnabled: contentFetchEnabled
            )
        )
    }

    func applyChatGptPersonalLinkOps(
        session: SharedTagAuthSession,
        operations: [ChatGptPersonalLinkOperation]
    ) async throws -> ChatGptPersonalLinkOpsResponse {
        let data = try await executeRPC(
            path: "/rest/v1/rpc/apply_personal_link_ops",
            session: session,
            body: ChatGptPersonalLinkOpsPayload(ops: operations)
        )
        return try makeSharedTagCloudDecoder().decode(ChatGptPersonalLinkOpsResponse.self, from: data)
    }

    private func executeUnauthenticatedRPC<T: Encodable>(
        path: String,
        body: T
    ) async throws -> Data {
        try await SharedTagRemoteRequestExecutor(config: config).execute(
            path: path,
            bearerToken: nil,
            body: body
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
    case featureDisabled
    case invalidInvite
    case ownerTransferRequired
    case httpStatus(Int, String)
    case message(String)

    var userMessage: String {
        switch self {
        case .authRequired:
            return "共有タグクラウドにサインインしてください"
        case .featureDisabled:
            return "現在は利用できません"
        case .invalidInvite:
            return "招待リンクが無効か期限切れです"
        case .ownerTransferRequired:
            return "共有タグ詳細の参加者からオーナー権限を移譲してから削除してください"
        case .httpStatus(_, let message):
            return Self.normalizedUserMessage(message, fallback: "通信に失敗しました")
        case .message(let message):
            return Self.normalizedUserMessage(message, fallback: "通信に失敗しました")
        }
    }

    private static func normalizedUserMessage(_ message: String, fallback: String) -> String {
        let trimmed = message.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return fallback }
        let lowercased = trimmed.lowercased()
        if lowercased.contains("invalid_or_expired_invite")
            || lowercased.contains("invalid invite")
            || lowercased.contains("expired invite") {
            return "招待リンクが無効か期限切れです"
        }
        return trimmed
    }
}

final class EntitlementGrantCache: @unchecked Sendable {
    static let cacheTTL: TimeInterval = 7 * 24 * 60 * 60

    private let userDefaults: UserDefaults
    private let key: String
    private let encoder: JSONEncoder
    private let decoder: JSONDecoder
    private let cacheTTL: TimeInterval
    private let lock = NSLock()
    private var snapshot: Snapshot?

    init(
        userDefaults: UserDefaults = .standard,
        key: String = "jp.mimac.urlsaver.entitlement_grants.last_known",
        cacheTTL: TimeInterval = EntitlementGrantCache.cacheTTL
    ) {
        self.userDefaults = userDefaults
        self.key = key
        self.cacheTTL = cacheTTL
        let encoder = JSONEncoder()
        encoder.dateEncodingStrategy = .iso8601
        self.encoder = encoder
        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .iso8601
        self.decoder = decoder
    }

    func load(authUserID: String, now: Date) -> [EntitlementGrant] {
        lock.lock()
        defer { lock.unlock() }
        if let snapshot, snapshot.isValid(authUserID: authUserID, now: now, cacheTTL: cacheTTL) {
            return snapshot.grants
        }
        guard let data = userDefaults.data(forKey: key),
              let loaded = try? decoder.decode(Snapshot.self, from: data) else {
            snapshot = nil
            return []
        }
        snapshot = loaded
        return loaded.isValid(authUserID: authUserID, now: now, cacheTTL: cacheTTL) ? loaded.grants : []
    }

    func save(authUserID: String, grants: [EntitlementGrant], fetchedAt: Date) {
        lock.lock()
        defer { lock.unlock() }
        let saved = Snapshot(authUserID: authUserID, fetchedAt: fetchedAt, grants: grants)
        if let data = try? encoder.encode(saved) {
            userDefaults.set(data, forKey: key)
        }
        snapshot = saved
    }

    func cachedSnapshot(authUserID: String?, now: Date) -> [EntitlementGrant] {
        guard let authUserID else { return [] }
        lock.lock()
        defer { lock.unlock() }
        return snapshot?.isValid(authUserID: authUserID, now: now, cacheTTL: cacheTTL) == true
            ? snapshot?.grants ?? []
            : []
    }

    struct Snapshot: Codable, Equatable, Sendable {
        let authUserID: String
        let fetchedAt: Date
        let grants: [EntitlementGrant]

        func isValid(authUserID currentAuthUserID: String, now: Date, cacheTTL: TimeInterval) -> Bool {
            authUserID == currentAuthUserID && now.timeIntervalSince(fetchedAt) <= cacheTTL
        }
    }
}

private struct EntitlementGrantRemoteDataSource {
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

    func fetchGrants(session: SharedTagAuthSession) async throws -> [EntitlementGrant] {
        let data = try await executeRPC(
            path: "/rest/v1/rpc/get_my_entitlement_grants",
            session: session,
            body: EmptyPayload()
        )
        return try makeSharedTagCloudDecoder()
            .decode([RemoteEntitlementGrant].self, from: data)
            .compactMap { $0.toDomain() }
    }

    func redeemPromoCode(session: SharedTagAuthSession, code: String) async throws -> [EntitlementGrant] {
        let data = try await executeRPC(
            path: "/rest/v1/rpc/redeem_promo_code",
            session: session,
            body: PromoCodeRequest(code: code)
        )
        return try decodeEntitlementGrantResponse(data)
    }

    private func decodeEntitlementGrantResponse(_ data: Data) throws -> [EntitlementGrant] {
        let decoder = makeSharedTagCloudDecoder()
        if let grants = try? decoder.decode([RemoteEntitlementGrant].self, from: data) {
            return grants.compactMap { $0.toDomain() }
        }
        return try [decoder.decode(RemoteEntitlementGrant.self, from: data)]
            .compactMap { $0.toDomain() }
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

private struct PromoCodeRequest: Encodable {
    let code: String

    enum CodingKeys: String, CodingKey {
        case code = "p_code"
    }
}

private struct RemoteEntitlementGrant: Decodable {
    let id: String?
    let grantID: String?
    let plan: String
    let source: String
    let storePlatform: String?
    let storeTransactionID: String?
    let startsAt: String?
    let claimedAt: String?
    let expiresAt: String?
    let status: String

    enum CodingKeys: String, CodingKey {
        case id
        case grantID = "grant_id"
        case plan
        case source
        case storePlatform = "store_platform"
        case storeTransactionID = "store_transaction_id"
        case startsAt = "starts_at"
        case claimedAt = "claimed_at"
        case expiresAt = "expires_at"
        case status
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        id = try container.decodeIfPresent(String.self, forKey: .id)
        grantID = try container.decodeIfPresent(String.self, forKey: .grantID)
        plan = try container.decode(String.self, forKey: .plan)
        source = try container.decodeIfPresent(String.self, forKey: .source) ?? "admin_grant"
        storePlatform = try container.decodeIfPresent(String.self, forKey: .storePlatform)
        storeTransactionID = try container.decodeIfPresent(String.self, forKey: .storeTransactionID)
        startsAt = try container.decodeIfPresent(String.self, forKey: .startsAt)
        claimedAt = try container.decodeIfPresent(String.self, forKey: .claimedAt)
        expiresAt = try container.decodeIfPresent(String.self, forKey: .expiresAt)
        status = try container.decodeIfPresent(String.self, forKey: .status) ?? "active"
    }

    func toDomain() -> EntitlementGrant? {
        guard let planType = PlanType(rawValue: plan),
              let sourceType = EntitlementSource(rawValue: source),
              let grantStatus = EntitlementGrantStatus(rawValue: status),
              let sourceID = storeTransactionID ?? id ?? grantID else {
            return nil
        }
        let startDate = (startsAt ?? claimedAt).flatMap(parseSupabaseISO8601Date) ?? Date()
        let endDate = expiresAt.flatMap(parseSupabaseISO8601Date)
        return EntitlementGrant(
            planType: planType,
            source: sourceType,
            status: grantStatus,
            startsAt: startDate,
            endsAt: endDate,
            sourceID: sourceID,
            note: storePlatform
        )
    }
}

final class EntitlementService: @unchecked Sendable {
    private let sessionStore: SharedTagAuthSessionStore
    private let remoteDataSource: EntitlementGrantRemoteDataSource
    private let cache: EntitlementGrantCache

    init(
        config: SharedTagCloudConfig,
        sessionStore: SharedTagAuthSessionStore,
        cache: EntitlementGrantCache = EntitlementGrantCache()
    ) {
        self.sessionStore = sessionStore
        self.cache = cache
        let authRemoteDataSource = SharedTagAuthRemoteDataSource(config: config)
        self.remoteDataSource = EntitlementGrantRemoteDataSource(
            config: config,
            authRemoteDataSource: authRemoteDataSource,
            sessionStore: sessionStore
        )
    }

    func currentEntitlements(now: Date = Date()) -> FeatureEntitlements {
        let authUserID = (try? sessionStore.load())?.authUserID
        let grants = cache.cachedSnapshot(authUserID: authUserID, now: now) +
            BuildVariantEntitlementOverrides.grants(at: now)
        return EntitlementResolver(grantsProvider: { grants }).resolve(at: now)
    }

    @discardableResult
    func refreshForCurrentSession(now: Date = Date()) async -> FeatureEntitlements {
        guard let session = try? sessionStore.load() else {
            return EntitlementResolver(
                grantsProvider: { BuildVariantEntitlementOverrides.grants(at: now) }
            ).resolve(at: now)
        }
        let grants = await fetchOrLoadCachedGrants(session: session, now: now)
        return EntitlementResolver(
            grantsProvider: { grants + BuildVariantEntitlementOverrides.grants(at: now) }
        ).resolve(at: now)
    }

    @discardableResult
    func redeemPromoCode(_ code: String, now: Date = Date()) async throws -> FeatureEntitlements {
        let trimmedCode = code.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmedCode.isEmpty else {
            throw SharedTagCloudError.message("優待コードを入力してください")
        }
        guard let session = try? sessionStore.load() else {
            throw SharedTagCloudError.authRequired
        }
        let remoteGrants = try await remoteDataSource.redeemPromoCode(session: session, code: trimmedCode)
        cache.save(authUserID: session.authUserID, grants: remoteGrants, fetchedAt: now)
        return EntitlementResolver(
            grantsProvider: { remoteGrants + BuildVariantEntitlementOverrides.grants(at: now) }
        ).resolve(at: now)
    }

    private func fetchOrLoadCachedGrants(session: SharedTagAuthSession, now: Date) async -> [EntitlementGrant] {
        do {
            let remoteGrants = try await remoteDataSource.fetchGrants(session: session)
            cache.save(authUserID: session.authUserID, grants: remoteGrants, fetchedAt: now)
            return remoteGrants
        } catch {
            return cache.load(authUserID: session.authUserID, now: now)
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

    func loadVisibleGroups(authUserID: String) throws -> [SharedTagGroupSummary] {
        try database.fetchMany(
            sql: """
            SELECT
                group_row.remote_group_id,
                group_row.name,
                group_row.current_user_role,
                (
                    SELECT COUNT(*)
                    FROM shared_tag_group_tags AS group_tag
                    WHERE group_tag.auth_user_id = group_row.auth_user_id
                      AND group_tag.group_remote_id = group_row.remote_group_id
                ) AS tag_count,
                (
                    SELECT COUNT(*)
                    FROM shared_tag_group_members AS member
                    WHERE member.auth_user_id = group_row.auth_user_id
                      AND member.group_remote_id = group_row.remote_group_id
                      AND member.status = 'active'
                ) AS member_count,
                group_row.last_synced_at
            FROM shared_tag_groups
            AS group_row
            WHERE auth_user_id = ?
              AND deleted_at IS NULL
            ORDER BY name COLLATE NOCASE ASC;
            """,
            binds: [sql(authUserID)]
        ) { statement in
            SharedTagGroupSummary(
                remoteGroupID: Self.textColumn(statement, index: 0) ?? "",
                name: Self.textColumn(statement, index: 1) ?? "",
                currentUserRole: Self.textColumn(statement, index: 2).flatMap(SharedTagMemberRole.init(rawValue:)),
                tagCount: Int(sqlite3_column_int(statement, 3)),
                memberCount: Int(sqlite3_column_int(statement, 4)),
                lastSyncedAt: Self.dateColumn(statement, index: 5)
            )
        }
    }

    func loadGroupMembers(authUserID: String, remoteGroupID: String) throws -> [SharedTagGroupMemberSummary] {
        try database.fetchMany(
            sql: """
            SELECT group_remote_id, user_id, display_name, role
            FROM shared_tag_group_members
            WHERE auth_user_id = ?
              AND group_remote_id = ?
              AND status = 'active'
            ORDER BY
              CASE role
                WHEN 'owner' THEN 0
                WHEN 'editor' THEN 1
                ELSE 2
              END ASC,
              CASE WHEN user_id = ? THEN 0 ELSE 1 END ASC,
              user_id ASC;
            """,
            binds: [sql(authUserID), sql(remoteGroupID), sql(authUserID)]
        ) { statement in
            let userID = Self.textColumn(statement, index: 1) ?? ""
            return SharedTagGroupMemberSummary(
                groupID: Self.textColumn(statement, index: 0) ?? "",
                userID: userID,
                displayName: Self.textColumn(statement, index: 2),
                role: Self.textColumn(statement, index: 3).flatMap(SharedTagMemberRole.init(rawValue:)) ?? .viewer,
                isCurrentUser: userID == authUserID
            )
        }
    }

    func loadGroupTags(authUserID: String, remoteGroupID: String) throws -> [SharedTagGroupTagSummary] {
        try database.fetchMany(
            sql: """
            SELECT group_tag.group_remote_id, group_tag.tag_remote_id, tag.name, tag.current_user_role
            FROM shared_tag_group_tags AS group_tag
            INNER JOIN shared_tags AS tag
              ON tag.auth_user_id = group_tag.auth_user_id
             AND tag.remote_tag_id = group_tag.tag_remote_id
             AND tag.deleted_at IS NULL
            WHERE group_tag.auth_user_id = ?
              AND group_tag.group_remote_id = ?
            ORDER BY tag.name COLLATE NOCASE ASC;
            """,
            binds: [sql(authUserID), sql(remoteGroupID)]
        ) { statement in
            SharedTagGroupTagSummary(
                groupID: Self.textColumn(statement, index: 0) ?? "",
                remoteTagID: Self.textColumn(statement, index: 1) ?? "",
                tagName: Self.textColumn(statement, index: 2) ?? "",
                currentUserRole: Self.textColumn(statement, index: 3).flatMap(SharedTagMemberRole.init(rawValue:))
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
            try database.execute("DELETE FROM shared_tag_group_members WHERE auth_user_id = ?;", binds: [sql(authUserID)])
            try database.execute("DELETE FROM shared_tag_group_tags WHERE auth_user_id = ?;", binds: [sql(authUserID)])
            try database.execute("DELETE FROM shared_tag_groups WHERE auth_user_id = ?;", binds: [sql(authUserID)])
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

            let groupRolesByID = Dictionary(
                uniqueKeysWithValues: snapshot.groupMembers
                    .filter { $0.userID == authUserID && $0.status.lowercased() == "active" }
                    .compactMap { member in
                        SharedTagMemberRole(rawValue: member.role.lowercased()).map { (member.groupID, $0.rawValue) }
                    }
            )

            for group in snapshot.groups {
                try database.execute(
                    """
                    INSERT INTO shared_tag_groups (
                        auth_user_id,
                        remote_group_id,
                        name,
                        current_user_role,
                        deleted_at,
                        last_synced_at
                    ) VALUES (?, ?, ?, ?, ?, ?);
                    """,
                    binds: [
                        sql(authUserID),
                        sql(group.id),
                        sql(group.name),
                        sql(groupRolesByID[group.id]),
                        sql(Self.parseISO8601(group.deletedAt)?.timeIntervalSince1970),
                        sql(Self.parseISO8601(snapshot.pulledAt)?.timeIntervalSince1970),
                    ]
                )
            }

            for member in snapshot.groupMembers {
                try database.execute(
                    """
                    INSERT INTO shared_tag_group_members (
                        auth_user_id,
                        group_remote_id,
                        user_id,
                        display_name,
                        role,
                        status,
                        created_at,
                        updated_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?);
                    """,
                    binds: [
                        sql(authUserID),
                        sql(member.groupID),
                        sql(member.userID),
                        sql(member.displayName),
                        sql(member.role.lowercased()),
                        sql(member.status.lowercased()),
                        sql(Self.parseISO8601(member.createdAt)?.timeIntervalSince1970),
                        sql(Self.parseISO8601(member.updatedAt)?.timeIntervalSince1970),
                    ]
                )
            }

            for groupTag in snapshot.groupTags {
                try database.execute(
                    """
                    INSERT INTO shared_tag_group_tags (
                        auth_user_id,
                        group_remote_id,
                        tag_remote_id,
                        added_by,
                        created_at
                    ) VALUES (?, ?, ?, ?, ?);
                    """,
                    binds: [
                        sql(authUserID),
                        sql(groupTag.groupID),
                        sql(groupTag.tagID),
                        sql(groupTag.addedBy),
                        sql(Self.parseISO8601(groupTag.createdAt)?.timeIntervalSince1970),
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
            try database.execute("DELETE FROM shared_tag_group_members;", binds: [])
            try database.execute("DELETE FROM shared_tag_group_tags;", binds: [])
            try database.execute("DELETE FROM shared_tag_groups;", binds: [])
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
                display_name TEXT,
                role TEXT NOT NULL,
                status TEXT NOT NULL,
                created_at REAL NOT NULL,
                updated_at REAL NOT NULL,
                PRIMARY KEY (auth_user_id, tag_remote_id, user_id)
            );
            CREATE TABLE IF NOT EXISTS shared_tag_groups (
                auth_user_id TEXT NOT NULL,
                remote_group_id TEXT NOT NULL,
                name TEXT NOT NULL,
                current_user_role TEXT,
                deleted_at REAL,
                last_synced_at REAL,
                PRIMARY KEY (auth_user_id, remote_group_id)
            );
            CREATE TABLE IF NOT EXISTS shared_tag_group_members (
                auth_user_id TEXT NOT NULL,
                group_remote_id TEXT NOT NULL,
                user_id TEXT NOT NULL,
                display_name TEXT,
                role TEXT NOT NULL,
                status TEXT NOT NULL,
                created_at REAL NOT NULL,
                updated_at REAL NOT NULL,
                PRIMARY KEY (auth_user_id, group_remote_id, user_id)
            );
            CREATE TABLE IF NOT EXISTS shared_tag_group_tags (
                auth_user_id TEXT NOT NULL,
                group_remote_id TEXT NOT NULL,
                tag_remote_id TEXT NOT NULL,
                added_by TEXT NOT NULL,
                created_at REAL NOT NULL,
                PRIMARY KEY (auth_user_id, group_remote_id, tag_remote_id)
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
            CREATE INDEX IF NOT EXISTS idx_shared_tag_groups_visible ON shared_tag_groups(auth_user_id, deleted_at, name);
            CREATE INDEX IF NOT EXISTS idx_shared_tag_urls_lookup ON shared_tag_urls(auth_user_id, normalized_url, deleted_at);
            """
        )
        try database.addColumnIfMissing(
            table: "shared_tag_members",
            column: "display_name",
            definition: "display_name TEXT"
        )
        try database.addColumnIfMissing(
            table: "shared_tag_group_members",
            column: "display_name",
            definition: "display_name TEXT"
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

protocol SharedTagAccountLocalCleanupStateStore: Sendable {
    func load() -> SharedTagAccountLocalCleanupState?
    func save(_ state: SharedTagAccountLocalCleanupState)
    func clear()
}

final class UserDefaultsSharedTagAccountLocalCleanupStateStore: @unchecked Sendable, SharedTagAccountLocalCleanupStateStore {
    private let userDefaults: UserDefaults
    private let markerKey: String
    private let aiDataCleanupPendingKey: String
    private let signOutCleanupPendingKey: String

    init(
        userDefaults: UserDefaults = .standard,
        keyPrefix: String = "jp.mimac.urlsaver.shared_tag.account_local_cleanup"
    ) {
        self.userDefaults = userDefaults
        markerKey = "\(keyPrefix).pending"
        aiDataCleanupPendingKey = "\(keyPrefix).ai_data_pending"
        signOutCleanupPendingKey = "\(keyPrefix).sign_out_pending"
    }

    func load() -> SharedTagAccountLocalCleanupState? {
        guard userDefaults.bool(forKey: markerKey) else { return nil }
        return SharedTagAccountLocalCleanupState(
            aiDataCleanupPending: userDefaults.object(forKey: aiDataCleanupPendingKey) == nil
                || userDefaults.bool(forKey: aiDataCleanupPendingKey),
            signOutCleanupPending: userDefaults.object(forKey: signOutCleanupPendingKey) == nil
                || userDefaults.bool(forKey: signOutCleanupPendingKey)
        )
    }

    func save(_ state: SharedTagAccountLocalCleanupState) {
        userDefaults.set(state.aiDataCleanupPending, forKey: aiDataCleanupPendingKey)
        userDefaults.set(state.signOutCleanupPending, forKey: signOutCleanupPendingKey)
        userDefaults.set(true, forKey: markerKey)
    }

    func clear() {
        userDefaults.removeObject(forKey: markerKey)
        userDefaults.removeObject(forKey: aiDataCleanupPendingKey)
        userDefaults.removeObject(forKey: signOutCleanupPendingKey)
    }
}

final class SharedTagCloudService: @unchecked Sendable {
    let config: SharedTagCloudConfig
    private let sessionStore: SharedTagAuthSessionStore
    private let authRemoteDataSource: SharedTagAuthRemoteDataSource
    private let syncRemoteDataSource: SharedTagSyncRemoteDataSource
    private let store: SharedTagStore
    private let repository: URLRepository
    private let clearLocalAiData: @Sendable () throws -> Void
    private let localCleanupStateStore: any SharedTagAccountLocalCleanupStateStore

    init(
        config: SharedTagCloudConfig,
        sessionStore: SharedTagAuthSessionStore,
        store: SharedTagStore,
        repository: URLRepository,
        clearLocalAiData: (@Sendable () throws -> Void)? = nil,
        cleanupStateStore: (any SharedTagAccountLocalCleanupStateStore)? = nil
    ) {
        self.config = config
        self.sessionStore = sessionStore
        self.store = store
        self.repository = repository
        self.clearLocalAiData = clearLocalAiData ?? {
            try repository.clearLocalAiData()
        }
        self.localCleanupStateStore = cleanupStateStore ?? UserDefaultsSharedTagAccountLocalCleanupStateStore()
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

    var localAccountCleanupState: SharedTagAccountLocalCleanupState? {
        localCleanupStateStore.load()
    }

    func currentSession() -> SharedTagAuthSession? {
        try? sessionStore.load()
    }

    func googleOAuthURL() -> URL? {
        try? authRemoteDataSource.oauthURL(provider: "google", redirectTo: "urlsaver://auth/callback")
    }

    func loadVisibleTags() throws -> [SharedTagSummary] {
        guard let session = try sessionStore.load() else { return [] }
        return try store.loadVisibleTags(authUserID: session.authUserID)
    }

    func loadVisibleGroups() throws -> [SharedTagGroupSummary] {
        guard let session = try sessionStore.load() else { return [] }
        return try store.loadVisibleGroups(authUserID: session.authUserID)
    }

    func loadGroupMembers(remoteGroupID: String) throws -> [SharedTagGroupMemberSummary] {
        guard let session = try sessionStore.load() else { return [] }
        return try store.loadGroupMembers(authUserID: session.authUserID, remoteGroupID: remoteGroupID)
    }

    func loadGroupTags(remoteGroupID: String) throws -> [SharedTagGroupTagSummary] {
        guard let session = try sessionStore.load() else { return [] }
        return try store.loadGroupTags(authUserID: session.authUserID, remoteGroupID: remoteGroupID)
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

    func handleOAuthCallback(url: URL) async -> SharedTagAuthResult {
        guard config.isConfigured else {
            return .failure("共有タグクラウドの設定がありません")
        }
        do {
            switch try await authRemoteDataSource.signInWithOAuthCallback(url: url) {
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

    func signInWithAppleIDToken(idToken: String, nonce: String) async -> SharedTagAuthResult {
        guard config.isConfigured else {
            return .failure("共有タグクラウドの設定がありません")
        }
        do {
            switch try await authRemoteDataSource.signInWithAppleIDToken(idToken: idToken, nonce: nonce) {
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

    func resendEmailConfirmation(email: String) async -> SharedTagAuthResult {
        guard config.isConfigured else {
            return .failure("共有タグクラウドの設定がありません")
        }
        do {
            try await authRemoteDataSource.resendEmailConfirmation(email: email)
            return .needsEmailConfirmation
        } catch let error as SharedTagCloudError {
            return .failure(error.userMessage)
        } catch {
            return .failure(error.localizedDescription)
        }
    }

    func sendPasswordRecovery(email: String) async -> SharedTagAuthResult {
        guard config.isConfigured else {
            return .failure("共有タグクラウドの設定がありません")
        }
        do {
            try await authRemoteDataSource.sendPasswordRecovery(email: email)
            return .needsEmailConfirmation
        } catch let error as SharedTagCloudError {
            return .failure(error.userMessage)
        } catch {
            return .failure(error.localizedDescription)
        }
    }

    func signOut() throws {
        var firstError: Error?
        do {
            try sessionStore.clear()
        } catch {
            firstError = error
        }
        do {
            try store.clearLocalSharedState()
        } catch {
            if firstError == nil {
                firstError = error
            }
        }
        if let firstError {
            throw firstError
        }
    }

    func setChatGptPersonalLinkSync(enabled: Bool) async throws -> ChatGptPersonalLinkSyncResult {
        guard config.isPersonalLinkSyncConfigured else { throw SharedTagCloudError.featureDisabled }
        guard let session = try? sessionStore.load() else { throw SharedTagCloudError.authRequired }
        try await syncRemoteDataSource.setChatGptPersonalLinkSync(
            session: session,
            enabled: enabled,
            contentFetchEnabled: false
        )
        if enabled {
            return try await syncChatGptPersonalLinks()
        }
        let eligibility = try chatGptPersonalLinkEligibility()
        return ChatGptPersonalLinkSyncResult(
            attemptedCount: 0,
            appliedCount: 0,
            excludedCount: eligibility.excludedCount,
            exclusionReasons: eligibility.exclusionReasons
        )
    }

    func chatGptPersonalLinkEligibility() throws -> ChatGptPersonalLinkEligibility {
        let records = try repository.loadChatGptPersonalLinkSnapshot()
        var eligibleCount = 0
        var excludedCount = 0
        var exclusionReasons: [String: Int] = [:]
        for record in records {
            let result = ChatGptPersonalLinkSyncPolicy.eligibility(for: record)
            if result.eligible {
                eligibleCount += 1
            } else {
                excludedCount += 1
                for reason in result.reasons {
                    exclusionReasons[reason, default: 0] += 1
                }
            }
        }
        return ChatGptPersonalLinkEligibility(
            eligibleCount: eligibleCount,
            excludedCount: excludedCount,
            exclusionReasons: exclusionReasons
        )
    }

    func syncChatGptPersonalLinks() async throws -> ChatGptPersonalLinkSyncResult {
        guard config.isPersonalLinkSyncConfigured else { throw SharedTagCloudError.featureDisabled }
        guard let session = try? sessionStore.load() else { throw SharedTagCloudError.authRequired }
        let records = try repository.loadChatGptPersonalLinkSnapshot()
        var eligibleRecords: [URLRecord] = []
        var exclusionReasons: [String: Int] = [:]
        for record in records {
            let result = ChatGptPersonalLinkSyncPolicy.eligibility(for: record)
            guard result.eligible else {
                for reason in result.reasons {
                    exclusionReasons[reason, default: 0] += 1
                }
                continue
            }
            eligibleRecords.append(record)
        }
        let operations = try eligibleRecords.map { record in
            try makeChatGptPersonalLinkOperation(
                record: record,
                tags: repository.loadLocalTagsForEntry(entryID: record.id).map(\.name)
            )
        }
        guard !operations.isEmpty else {
            return ChatGptPersonalLinkSyncResult(
                attemptedCount: 0,
                appliedCount: 0,
                excludedCount: records.count,
                exclusionReasons: exclusionReasons
            )
        }
        let response = try await syncRemoteDataSource.applyChatGptPersonalLinkOps(
            session: session,
            operations: operations
        )
        let appliedCount = response.results.isEmpty
            ? min(response.appliedCount, operations.count)
            : response.results.filter { $0.status == "ok" || $0.status == "skipped" }.count
        return ChatGptPersonalLinkSyncResult(
            attemptedCount: operations.count,
            appliedCount: appliedCount,
            excludedCount: records.count - operations.count,
            exclusionReasons: exclusionReasons
        )
    }

    func makeChatGptPersonalLinkOperation(
        record: URLRecord,
        tags: [String]
    ) throws -> ChatGptPersonalLinkOperation {
        ChatGptPersonalLinkOperation(
            opID: UUID().uuidString.lowercased(),
            clientEntryID: AiTransparencyPolicy.publicSafeID(for: record),
            url: record.normalizedURL,
            title: record.userTitle ?? record.fetchedTitle,
            memo: record.memo.isEmpty ? nil : record.memo,
            updatedAt: Self.chatGptISO8601String(record.updatedAt),
            tags: tags,
            metadata: ChatGptPersonalLinkMetadata(contentFetchAllowed: false)
        )
    }

    private static func chatGptISO8601String(_ date: Date) -> String {
        let formatter = ISO8601DateFormatter()
        formatter.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        return formatter.string(from: date)
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

    func createGroup(name: String) async -> SharedTagMutationResult {
        let normalizedName = Self.normalizeTagName(name)
        guard !normalizedName.isEmpty else {
            return .failure("グループ名を入力してください")
        }
        guard normalizedName.count <= maxSharedTagNameLength else {
            return .failure("グループ名は\(maxSharedTagNameLength)文字以内で入力してください")
        }
        guard let session = try? sessionStore.load() else {
            return .authRequired
        }
        do {
            _ = try await syncRemoteDataSource.createGroup(session: session, name: normalizedName)
            try await refreshLocalState(session: session)
            return .success
        } catch let error as SharedTagCloudError {
            return .failure(error.userMessage)
        } catch {
            return .failure(error.localizedDescription)
        }
    }

    func addTagToGroup(remoteGroupID: String, remoteTagID: String) async -> SharedTagMutationResult {
        guard let session = try? sessionStore.load() else {
            return .authRequired
        }
        do {
            try await syncRemoteDataSource.addTagToGroup(
                session: session,
                remoteGroupID: remoteGroupID,
                remoteTagID: remoteTagID
            )
            try await refreshLocalState(session: session)
            return .success
        } catch let error as SharedTagCloudError {
            return .failure(error.userMessage)
        } catch {
            return .failure(error.localizedDescription)
        }
    }

    func removeTagFromGroup(remoteGroupID: String, remoteTagID: String) async -> SharedTagMutationResult {
        guard let session = try? sessionStore.load() else {
            return .authRequired
        }
        do {
            try await syncRemoteDataSource.removeTagFromGroup(
                session: session,
                remoteGroupID: remoteGroupID,
                remoteTagID: remoteTagID
            )
            try await refreshLocalState(session: session)
            return .success
        } catch let error as SharedTagCloudError {
            return .failure(error.userMessage)
        } catch {
            return .failure(error.localizedDescription)
        }
    }

    func renameGroup(remoteGroupID: String, name: String) async -> SharedTagMutationResult {
        let normalizedName = Self.normalizeTagName(name)
        guard !normalizedName.isEmpty else {
            return .failure("グループ名を入力してください")
        }
        guard let session = try? sessionStore.load() else {
            return .authRequired
        }
        do {
            try await syncRemoteDataSource.renameGroup(session: session, remoteGroupID: remoteGroupID, name: normalizedName)
            try await refreshLocalState(session: session)
            return .success
        } catch let error as SharedTagCloudError {
            return .failure(error.userMessage)
        } catch {
            return .failure(error.localizedDescription)
        }
    }

    func deleteGroup(remoteGroupID: String) async -> SharedTagMutationResult {
        guard let session = try? sessionStore.load() else {
            return .authRequired
        }
        do {
            try await syncRemoteDataSource.deleteGroup(session: session, remoteGroupID: remoteGroupID)
            try await refreshLocalState(session: session)
            return .success
        } catch let error as SharedTagCloudError {
            return .failure(error.userMessage)
        } catch {
            return .failure(error.localizedDescription)
        }
    }

    func changeGroupMemberRole(remoteGroupID: String, userID: String, role: SharedTagMemberRole) async -> SharedTagMutationResult {
        guard let session = try? sessionStore.load() else {
            return .authRequired
        }
        do {
            try await syncRemoteDataSource.changeGroupMemberRole(
                session: session,
                remoteGroupID: remoteGroupID,
                userID: userID,
                role: role.rawValue
            )
            try await refreshLocalState(session: session)
            return .success
        } catch let error as SharedTagCloudError {
            return .failure(error.userMessage)
        } catch {
            return .failure(error.localizedDescription)
        }
    }

    func transferGroupOwnership(remoteGroupID: String, newOwnerUserID: String) async -> SharedTagMutationResult {
        guard let session = try? sessionStore.load() else {
            return .authRequired
        }
        do {
            try await syncRemoteDataSource.transferGroupOwnership(
                session: session,
                remoteGroupID: remoteGroupID,
                newOwnerUserID: newOwnerUserID
            )
            try await refreshLocalState(session: session)
            return .success
        } catch let error as SharedTagCloudError {
            return .failure(error.userMessage)
        } catch {
            return .failure(error.localizedDescription)
        }
    }

    func removeGroupMember(remoteGroupID: String, userID: String) async -> SharedTagMutationResult {
        guard let session = try? sessionStore.load() else {
            return .authRequired
        }
        do {
            try await syncRemoteDataSource.removeGroupMember(session: session, remoteGroupID: remoteGroupID, userID: userID)
            try await refreshLocalState(session: session)
            return .success
        } catch let error as SharedTagCloudError {
            return .failure(error.userMessage)
        } catch {
            return .failure(error.localizedDescription)
        }
    }

    func upsertSharedProfile(displayName: String) async {
        guard let session = try? sessionStore.load() else { return }
        try? await syncRemoteDataSource.upsertSharedProfile(
            session: session,
            displayName: String(displayName.trimmingCharacters(in: .whitespacesAndNewlines).prefix(40))
        )
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

    func transferOwnership(remoteTagID: String, newOwnerUserID: String) async -> SharedTagMutationResult {
        guard let session = try? sessionStore.load() else {
            return .authRequired
        }
        let targetUserID = newOwnerUserID.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !targetUserID.isEmpty, targetUserID != session.authUserID else {
            return .failure("移譲先は参加中の別メンバーを選んでください")
        }
        do {
            guard let tag = try store.loadTag(authUserID: session.authUserID, remoteTagID: remoteTagID) else {
                return .failure("共有タグが見つかりませんでした")
            }
            guard tag.currentUserRole == .owner else {
                return .failure("オーナー権限を移譲できるのは現在のオーナーだけです")
            }
            guard let target = try store.loadActiveMembersForTag(authUserID: session.authUserID, remoteTagID: remoteTagID)
                .first(where: { $0.userID == targetUserID && !$0.isCurrentUser && $0.role != .owner })
            else {
                return .failure("移譲先は参加中のメンバーを選んでください")
            }
            _ = target
            _ = try await syncRemoteDataSource.transferOwnership(
                session: session,
                remoteTagID: remoteTagID,
                newOwnerUserID: targetUserID
            )
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
                inviteURL: makeInviteURL(invite.inviteToken),
                expiresAt: SharedTagStore.parseISO8601(invite.expiresAt)
            )
        } catch let error as SharedTagCloudError {
            return .failure(error.userMessage)
        } catch {
            return .failure(error.localizedDescription)
        }
    }

    func createGroupInvite(remoteGroupID: String, role: SharedTagMemberRole) async -> SharedTagInviteCreationResult {
        guard config.isConfigured else {
            return .failure("共有タグクラウドの設定がありません")
        }
        guard let session = try? sessionStore.load() else {
            return .authRequired
        }
        do {
            guard let group = try store.loadVisibleGroups(authUserID: session.authUserID)
                .first(where: { $0.remoteGroupID == remoteGroupID })
            else {
                return .failure("グループが見つかりませんでした")
            }
            guard group.currentUserRole == .owner else {
                return .ownerOnly
            }
            let inviteRole = role == .viewer ? "viewer" : "editor"
            let invite = try await syncRemoteDataSource.createGroupInvite(
                session: session,
                remoteGroupID: remoteGroupID,
                role: inviteRole
            )
            return .success(
                inviteURL: makeInviteURL(invite.inviteToken),
                expiresAt: SharedTagStore.parseISO8601(invite.expiresAt)
            )
        } catch let error as SharedTagCloudError {
            return .failure(error.userMessage)
        } catch {
            return .failure(error.localizedDescription)
        }
    }

    private func makeInviteURL(_ inviteToken: String) -> String {
        "\(config.inviteLinkBaseURL)/invite/\(inviteToken)"
    }

    func previewInvite(inviteToken: String) async -> SharedTagInvitePreviewResult {
        guard config.isConfigured else {
            return .failure("共有タグクラウドの設定がありません")
        }
        let token = inviteToken.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !token.isEmpty else {
            return .invalidInvite
        }
        do {
            let preview = try await syncRemoteDataSource.previewInvite(inviteToken: token)
            let inviteType = SharedInviteType(rawValue: preview.inviteType) ?? .tag
            return .success(
                displayName: preview.groupName ?? preview.tagName ?? "共有招待",
                inviteType: inviteType
            )
        } catch let error as SharedTagCloudError {
            switch error {
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
            let inviteType = SharedInviteType(rawValue: response.inviteType) ?? .tag
            return .accepted(
                displayName: response.groupName ?? response.tagName ?? "共有招待",
                inviteType: inviteType,
                role: SharedTagMemberRole(rawValue: response.role.lowercased())
            )
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
        if let pendingState = localAccountCleanupState {
            return .localCleanupRequired(pendingState)
        }
        guard config.isConfigured else {
            return .failure("共有タグクラウドの設定がありません")
        }
        guard let session = try? sessionStore.load() else {
            return .authRequired
        }
        do {
            try await syncRemoteDataSource.deleteAccount(session: session)
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
        localCleanupStateStore.save(
            SharedTagAccountLocalCleanupState(
                aiDataCleanupPending: true,
                signOutCleanupPending: true
            )
        )
        return performLocalAccountCleanup()
    }

    func retryLocalAccountCleanup() -> SharedTagAccountDeletionResult {
        performLocalAccountCleanup()
    }

    private func performLocalAccountCleanup() -> SharedTagAccountDeletionResult {
        guard var state = localCleanupStateStore.load() else { return .success }
        if state.aiDataCleanupPending {
            do {
                try clearLocalAiData()
                state = SharedTagAccountLocalCleanupState(
                    aiDataCleanupPending: false,
                    signOutCleanupPending: state.signOutCleanupPending
                )
                localCleanupStateStore.save(state)
            } catch {
                // 保留AIデータ清掃待ちのmarker。signOutは独立して続行する。
            }
        }
        if state.signOutCleanupPending {
            do {
                try signOut()
                state = SharedTagAccountLocalCleanupState(
                    aiDataCleanupPending: state.aiDataCleanupPending,
                    signOutCleanupPending: false
                )
                localCleanupStateStore.save(state)
            } catch {
                // 保留中の項目だけをmarkerに残す。
            }
        }
        guard state.requiresCleanup else {
            localCleanupStateStore.clear()
            return .success
        }
        localCleanupStateStore.save(state)
        return .localCleanupRequired(state)
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

private struct TransferOwnershipPayload: Encodable {
    let pTagID: String
    let pNewOwnerUserID: String

    enum CodingKeys: String, CodingKey {
        case pTagID = "p_tag_id"
        case pNewOwnerUserID = "p_new_owner_user_id"
    }

    init(tagID: String, newOwnerUserID: String) {
        self.pTagID = tagID
        self.pNewOwnerUserID = newOwnerUserID
    }
}

private struct CreateGroupPayload: Encodable {
    let pName: String

    enum CodingKeys: String, CodingKey {
        case pName = "p_name"
    }

    init(name: String) {
        self.pName = name
    }
}

private struct CreateGroupInvitePayload: Encodable {
    let pGroupID: String
    let pRole: String

    enum CodingKeys: String, CodingKey {
        case pGroupID = "p_group_id"
        case pRole = "p_role"
    }

    init(groupID: String, role: String) {
        self.pGroupID = groupID
        self.pRole = role
    }
}

private struct GroupTagPayload: Encodable {
    let pGroupID: String
    let pTagID: String

    enum CodingKeys: String, CodingKey {
        case pGroupID = "p_group_id"
        case pTagID = "p_tag_id"
    }

    init(groupID: String, tagID: String) {
        self.pGroupID = groupID
        self.pTagID = tagID
    }
}

private struct GroupPayload: Encodable {
    let pGroupID: String

    enum CodingKeys: String, CodingKey {
        case pGroupID = "p_group_id"
    }

    init(groupID: String) {
        self.pGroupID = groupID
    }
}

private struct RenameGroupPayload: Encodable {
    let pGroupID: String
    let pName: String

    enum CodingKeys: String, CodingKey {
        case pGroupID = "p_group_id"
        case pName = "p_name"
    }

    init(groupID: String, name: String) {
        self.pGroupID = groupID
        self.pName = name
    }
}

private struct GroupMemberPayload: Encodable {
    let pGroupID: String
    let pUserID: String

    enum CodingKeys: String, CodingKey {
        case pGroupID = "p_group_id"
        case pUserID = "p_user_id"
    }

    init(groupID: String, userID: String) {
        self.pGroupID = groupID
        self.pUserID = userID
    }
}

private struct GroupMemberRolePayload: Encodable {
    let pGroupID: String
    let pUserID: String
    let pRole: String

    enum CodingKeys: String, CodingKey {
        case pGroupID = "p_group_id"
        case pUserID = "p_user_id"
        case pRole = "p_role"
    }

    init(groupID: String, userID: String, role: String) {
        self.pGroupID = groupID
        self.pUserID = userID
        self.pRole = role
    }
}

private struct GroupOwnershipPayload: Encodable {
    let pGroupID: String
    let pNewOwnerUserID: String

    enum CodingKeys: String, CodingKey {
        case pGroupID = "p_group_id"
        case pNewOwnerUserID = "p_new_owner_user_id"
    }

    init(groupID: String, newOwnerUserID: String) {
        self.pGroupID = groupID
        self.pNewOwnerUserID = newOwnerUserID
    }
}

private struct SharedProfilePayload: Encodable {
    let pDisplayName: String

    enum CodingKeys: String, CodingKey {
        case pDisplayName = "p_display_name"
    }

    init(displayName: String) {
        self.pDisplayName = displayName
    }
}

private struct SupabasePasswordAuthRequest: Encodable {
    let email: String
    let password: String
}

private struct SupabasePKCEAuthRequest: Encodable {
    let authCode: String
    let codeVerifier: String

    enum CodingKeys: String, CodingKey {
        case authCode = "auth_code"
        case codeVerifier = "code_verifier"
    }
}

private struct SupabaseIDTokenAuthRequest: Encodable {
    let provider: String
    let idToken: String
    let nonce: String

    enum CodingKeys: String, CodingKey {
        case provider
        case idToken = "id_token"
        case nonce
    }
}

private struct SupabaseEmailRedirectOptions: Encodable {
    let emailRedirectTo: String

    enum CodingKeys: String, CodingKey {
        case emailRedirectTo = "email_redirect_to"
    }
}

private struct SupabaseResendRequest: Encodable {
    let type: String
    let email: String
    let options: SupabaseEmailRedirectOptions
}

private struct SupabasePasswordRecoveryRequest: Encodable {
    let email: String
}

private struct SetPersonalLinkChatGptSyncPayload: Encodable {
    let enabled: Bool
    let contentFetchEnabled: Bool

    enum CodingKeys: String, CodingKey {
        case enabled = "p_enabled"
        case contentFetchEnabled = "p_content_fetch_enabled"
    }
}

private struct ChatGptPersonalLinkOpsPayload: Encodable {
    let ops: [ChatGptPersonalLinkOperation]
}

struct ChatGptPersonalLinkOperation: Encodable, Equatable, Sendable {
    let opID: String
    let clientEntryID: String
    let operation = "upsert_link"
    let url: String
    let title: String?
    let memo: String?
    let updatedAt: String
    let tags: [String]
    let metadata: ChatGptPersonalLinkMetadata

    enum CodingKeys: String, CodingKey {
        case opID = "op_id"
        case clientEntryID = "client_entry_id"
        case operation
        case url
        case title
        case memo
        case updatedAt = "updated_at"
        case tags
        case metadata
    }
}

struct ChatGptPersonalLinkMetadata: Encodable, Equatable, Sendable {
    let contentFetchAllowed: Bool

    enum CodingKeys: String, CodingKey {
        case contentFetchAllowed = "content_fetch_allowed"
    }
}

private struct ChatGptPersonalLinkOpsResponse: Decodable {
    let status: String?
    let results: [ChatGptPersonalLinkOpResult]
    let appliedCount: Int

    enum CodingKeys: String, CodingKey {
        case status
        case results
        case appliedCount = "applied_count"
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        status = try container.decodeIfPresent(String.self, forKey: .status)
        results = try container.decodeIfPresent([ChatGptPersonalLinkOpResult].self, forKey: .results) ?? []
        appliedCount = try container.decodeIfPresent(Int.self, forKey: .appliedCount) ?? 0
    }
}

private struct ChatGptPersonalLinkOpResult: Decodable {
    let status: String
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
    let groups: [RemoteSharedTagGroup]
    let groupMembers: [RemoteSharedTagGroupMember]
    let groupTags: [RemoteSharedTagGroupTag]

    enum CodingKeys: String, CodingKey {
        case pulledAt = "pulled_at"
        case normalizationVersion = "normalization_version"
        case tags
        case members
        case urls
        case groups
        case groupMembers = "group_members"
        case groupTags = "group_tags"
    }

    init(
        pulledAt: String,
        normalizationVersion: Int,
        tags: [RemoteSharedTag],
        members: [RemoteSharedTagMember],
        urls: [RemoteSharedTagURL],
        groups: [RemoteSharedTagGroup] = [],
        groupMembers: [RemoteSharedTagGroupMember] = [],
        groupTags: [RemoteSharedTagGroupTag] = []
    ) {
        self.pulledAt = pulledAt
        self.normalizationVersion = normalizationVersion
        self.tags = tags
        self.members = members
        self.urls = urls
        self.groups = groups
        self.groupMembers = groupMembers
        self.groupTags = groupTags
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        pulledAt = try container.decode(String.self, forKey: .pulledAt)
        normalizationVersion = try container.decode(Int.self, forKey: .normalizationVersion)
        tags = try container.decode([RemoteSharedTag].self, forKey: .tags)
        members = try container.decode([RemoteSharedTagMember].self, forKey: .members)
        urls = try container.decode([RemoteSharedTagURL].self, forKey: .urls)
        groups = try container.decodeIfPresent([RemoteSharedTagGroup].self, forKey: .groups) ?? []
        groupMembers = try container.decodeIfPresent([RemoteSharedTagGroupMember].self, forKey: .groupMembers) ?? []
        groupTags = try container.decodeIfPresent([RemoteSharedTagGroupTag].self, forKey: .groupTags) ?? []
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
    let displayName: String?
    let role: String
    let status: String
    let createdAt: String
    let updatedAt: String

    enum CodingKeys: String, CodingKey {
        case tagID = "tag_id"
        case userID = "user_id"
        case displayName = "display_name"
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

struct RemoteSharedTagGroup: Decodable, Sendable {
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

struct RemoteSharedTagGroupMember: Decodable, Sendable {
    let groupID: String
    let userID: String
    let displayName: String?
    let role: String
    let status: String
    let createdAt: String
    let updatedAt: String

    enum CodingKeys: String, CodingKey {
        case groupID = "group_id"
        case userID = "user_id"
        case displayName = "display_name"
        case role
        case status
        case createdAt = "created_at"
        case updatedAt = "updated_at"
    }
}

struct RemoteSharedTagGroupTag: Decodable, Sendable {
    let groupID: String
    let tagID: String
    let addedBy: String
    let createdAt: String

    enum CodingKeys: String, CodingKey {
        case groupID = "group_id"
        case tagID = "tag_id"
        case addedBy = "added_by"
        case createdAt = "created_at"
    }
}

private struct AcceptSharedTagInviteResponse: Decodable {
    let inviteType: String
    let tagName: String?
    let groupName: String?
    let role: String

    enum CodingKeys: String, CodingKey {
        case inviteType = "invite_type"
        case tagName = "tag_name"
        case groupName = "group_name"
        case role
    }
}

private struct TransferSharedTagOwnershipResponse: Decodable {
    let tagID: String
    let previousOwnerUserID: String
    let newOwnerUserID: String

    enum CodingKeys: String, CodingKey {
        case tagID = "tag_id"
        case previousOwnerUserID = "previous_owner_user_id"
        case newOwnerUserID = "new_owner_user_id"
    }
}

private struct PreviewSharedTagInviteResponse: Decodable {
    let inviteType: String
    let tagName: String?
    let groupName: String?
    let role: String?

    enum CodingKeys: String, CodingKey {
        case inviteType = "invite_type"
        case tagName = "tag_name"
        case groupName = "group_name"
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

private struct CreateSharedTagGroupResponse: Decodable {
    let groupID: String
    let groupName: String
    let role: String

    enum CodingKeys: String, CodingKey {
        case groupID = "group_id"
        case groupName = "group_name"
        case role
    }
}

private struct CreateSharedTagGroupInviteResponse: Decodable {
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

func parseSupabaseISO8601Date(_ value: String) -> Date? {
    let fractionalFormatter = ISO8601DateFormatter()
    fractionalFormatter.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
    if let date = fractionalFormatter.date(from: value) {
        return date
    }
    return ISO8601DateFormatter().date(from: value)
}

private extension String {
    func trimmingTrailingSlashes() -> String {
        var value = self
        while value.hasSuffix("/") {
            value.removeLast()
        }
        return value
    }
}

private extension Data {
    func base64URLEncodedString() -> String {
        base64EncodedString()
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
    }
}
