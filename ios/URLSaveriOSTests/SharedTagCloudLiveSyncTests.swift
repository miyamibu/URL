import Foundation
import XCTest

final class SharedTagCloudLiveSyncTests: XCTestCase {
    func testOwnerCanSyncAndroidMigratedSharedTagFromLiveCloud() async throws {
        let env = try LiveSharedTagTestEnvironment.requireConfigured()
        let ownerEmail = try env.requireOwnerEmail()
        let ownerPassword = try env.requireOwnerPassword()
        let expectedTagName = try env.requireExpectedAndroidTagName()

        let harness = try TestSharedTagCloudHarness.make(baseURL: env.baseURL)
        let signInResult = await harness.service.signIn(email: ownerEmail, password: ownerPassword)
        guard case .success = signInResult else {
            XCTFail("Owner sign-in failed: \(signInResult)")
            return
        }

        let synced = await harness.service.syncCurrentSession()
        XCTAssertTrue(synced)

        let tags = try harness.service.loadVisibleTags()
        XCTAssertTrue(tags.contains(where: { $0.name == expectedTagName }))

        let tag = try XCTUnwrap(tags.first(where: { $0.name == expectedTagName }))
        XCTAssertGreaterThanOrEqual(tag.activeURLCount, 1)

        let entries = try harness.service.loadEntriesForTag(remoteTagID: tag.remoteTagID)
        XCTAssertFalse(entries.isEmpty)
        XCTAssertTrue(entries.contains(where: { !$0.normalizedURL.isEmpty }))
    }

    func testInviteFlowLetsSecondIOSClientJoinAndReadSharedURL() async throws {
        let env = try LiveSharedTagTestEnvironment.requireConfigured()
        let ownerHarness = try TestSharedTagCloudHarness.make(baseURL: env.baseURL)
        let collaboratorHarness = try TestSharedTagCloudHarness.make(baseURL: env.baseURL)

        let testID = UUID().uuidString.lowercased()
        let ownerEmail = "ios-owner-\(testID.prefix(8))@example.com"
        let ownerPassword = "pass12345"
        let collaboratorEmail = "ios-collab-\(testID.prefix(8))@example.com"
        let collaboratorPassword = "pass12345"
        let tagName = "Live Sync \(testID.prefix(6))"
        let rawURL = "https://example.com/live-sync-\(testID)"

        let ownerSignUpResult = await ownerHarness.service.signUp(email: ownerEmail, password: ownerPassword)
        guard case .success = ownerSignUpResult else {
            XCTFail("Owner sign-up failed: \(ownerSignUpResult)")
            return
        }
        let ownerSynced = await ownerHarness.service.syncCurrentSession()
        XCTAssertTrue(ownerSynced)

        let saveResult = try ownerHarness.repository.saveFromResolvedURL(rawURL)
        let entryID = try XCTUnwrap(saveResult.entryID)

        let createTagResult = await ownerHarness.service.createTag(name: tagName)
        guard case .success = createTagResult else {
            XCTFail("Tag creation failed: \(createTagResult)")
            return
        }

        let ownerTag = try XCTUnwrap(try ownerHarness.service.loadVisibleTags().first(where: { $0.name == tagName }))
        let assignResult = await ownerHarness.service.assignEntry(remoteTagID: ownerTag.remoteTagID, entryID: entryID)
        guard case .success = assignResult else {
            XCTFail("Entry assign failed: \(assignResult)")
            return
        }

        let inviteResult = await ownerHarness.service.createInvite(remoteTagID: ownerTag.remoteTagID)
        let inviteURL: String
        switch inviteResult {
        case .success(let value, _):
            inviteURL = value
        default:
            XCTFail("Invite creation failed: \(inviteResult)")
            return
        }

        let collaboratorSignUpResult = await collaboratorHarness.service.signUp(
            email: collaboratorEmail,
            password: collaboratorPassword
        )
        guard case .success = collaboratorSignUpResult else {
            XCTFail("Collaborator sign-up failed: \(collaboratorSignUpResult)")
            return
        }

        let inviteToken = try XCTUnwrap(inviteURL.components(separatedBy: "/").last)
        print("URLSAVER_LIVE_INVITE_TOKEN=\(inviteToken)")
        let acceptResult = await collaboratorHarness.service.acceptInvite(inviteToken: inviteToken)
        switch acceptResult {
        case .accepted(let acceptedTagName, let role):
            XCTAssertEqual(acceptedTagName, tagName)
            XCTAssertEqual(role, .editor)
        default:
            XCTFail("Invite acceptance failed: \(acceptResult)")
            return
        }

        let collaboratorTags = try collaboratorHarness.service.loadVisibleTags()
        let collaboratorTag = try XCTUnwrap(collaboratorTags.first(where: { $0.name == tagName }))
        XCTAssertEqual(collaboratorTag.activeURLCount, 1)

        let collaboratorEntries = try collaboratorHarness.service.loadEntriesForTag(remoteTagID: collaboratorTag.remoteTagID)
        XCTAssertEqual(collaboratorEntries.count, 1)
        XCTAssertEqual(collaboratorEntries.first?.normalizedURL, rawURL)
        XCTAssertEqual(collaboratorEntries.first?.sharedReferenceCount, 1)
    }

    func testCreateInviteForAndroidDeviceAcceptance() async throws {
        let env = try LiveSharedTagTestEnvironment.requireConfigured()
        let ownerHarness = try TestSharedTagCloudHarness.make(baseURL: env.baseURL)

        let testID = UUID().uuidString.lowercased()
        let ownerEmail = "ios-owner-\(testID.prefix(8))@example.com"
        let ownerPassword = "pass12345"
        let tagName = "Android Join \(testID.prefix(6))"
        let rawURL = "https://example.com/android-join-\(testID)"

        let ownerSignUpResult = await ownerHarness.service.signUp(email: ownerEmail, password: ownerPassword)
        guard case .success = ownerSignUpResult else {
            XCTFail("Owner sign-up failed: \(ownerSignUpResult)")
            return
        }
        let ownerSynced = await ownerHarness.service.syncCurrentSession()
        XCTAssertTrue(ownerSynced)

        let saveResult = try ownerHarness.repository.saveFromResolvedURL(rawURL)
        let entryID = try XCTUnwrap(saveResult.entryID)

        let createTagResult = await ownerHarness.service.createTag(name: tagName)
        guard case .success = createTagResult else {
            XCTFail("Tag creation failed: \(createTagResult)")
            return
        }

        let ownerTag = try XCTUnwrap(try ownerHarness.service.loadVisibleTags().first(where: { $0.name == tagName }))
        let assignResult = await ownerHarness.service.assignEntry(remoteTagID: ownerTag.remoteTagID, entryID: entryID)
        guard case .success = assignResult else {
            XCTFail("Entry assign failed: \(assignResult)")
            return
        }

        let inviteResult = await ownerHarness.service.createInvite(remoteTagID: ownerTag.remoteTagID)
        switch inviteResult {
        case .success(let inviteURL, _):
            let inviteToken = try XCTUnwrap(inviteURL.components(separatedBy: "/").last)
            print("URLSAVER_ANDROID_ACCEPT_TAG_NAME=\(tagName)")
            print("URLSAVER_ANDROID_ACCEPT_INVITE_TOKEN=\(inviteToken)")
            print("URLSAVER_ANDROID_ACCEPT_EXPECTED_URL=\(rawURL)")
        default:
            XCTFail("Invite creation failed: \(inviteResult)")
        }
    }

}

private struct LiveSharedTagTestEnvironment {
    let baseURL: String
    let ownerEmail: String?
    let ownerPassword: String?
    let expectedAndroidTagName: String?

    static func fromEnvironment(
        environment: [String: String] = ProcessInfo.processInfo.environment
    ) -> LiveSharedTagTestEnvironment? {
        let fileConfig = loadFromRepositoryConfig()
        let baseURL = environment["URLSAVER_LIVE_SHARED_TAG_BASE_URL"]?.trimmingCharacters(in: .whitespacesAndNewlines)
            ?? fileConfig?["base_url"]
            ?? (Bundle.main.object(forInfoDictionaryKey: "SupabaseURL") as? String)?.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let trimmedBaseURL = baseURL?.trimmingCharacters(in: .whitespacesAndNewlines),
              !trimmedBaseURL.isEmpty else {
            return nil
        }
        return LiveSharedTagTestEnvironment(
            baseURL: trimmedBaseURL,
            ownerEmail: environment["URLSAVER_LIVE_SHARED_TAG_OWNER_EMAIL"]?.trimmingCharacters(in: .whitespacesAndNewlines)
                ?? fileConfig?["owner_email"],
            ownerPassword: environment["URLSAVER_LIVE_SHARED_TAG_OWNER_PASSWORD"]?.trimmingCharacters(in: .whitespacesAndNewlines)
                ?? fileConfig?["owner_password"],
            expectedAndroidTagName: environment["URLSAVER_LIVE_SHARED_TAG_EXPECTED_NAME"]?.trimmingCharacters(in: .whitespacesAndNewlines)
                ?? fileConfig?["expected_android_tag_name"]
        )
    }

    static func requireConfigured() throws -> LiveSharedTagTestEnvironment {
        guard let environment = fromEnvironment() else {
            throw XCTSkip("Live Supabase shared-tag test environment is not configured.")
        }
        return environment
    }

    func requireOwnerEmail() throws -> String {
        guard let ownerEmail, !ownerEmail.isEmpty else {
            throw XCTSkip("URLSAVER_LIVE_SHARED_TAG_OWNER_EMAIL is required for this live shared-tag test.")
        }
        return ownerEmail
    }

    func requireOwnerPassword() throws -> String {
        guard let ownerPassword, !ownerPassword.isEmpty else {
            throw XCTSkip("URLSAVER_LIVE_SHARED_TAG_OWNER_PASSWORD is required for this live shared-tag test.")
        }
        return ownerPassword
    }

    func requireExpectedAndroidTagName() throws -> String {
        guard let expectedAndroidTagName, !expectedAndroidTagName.isEmpty else {
            throw XCTSkip("URLSAVER_LIVE_SHARED_TAG_EXPECTED_NAME is required for this live shared-tag test.")
        }
        return expectedAndroidTagName
    }

    private static func loadFromRepositoryConfig() -> [String: String]? {
        let configURL = URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent()
            .deletingLastPathComponent()
            .deletingLastPathComponent()
            .appendingPathComponent("tmp/live-shared-tag-test-config.json")
        guard let data = try? Data(contentsOf: configURL),
              let object = try? JSONSerialization.jsonObject(with: data) as? [String: String] else {
            return nil
        }
        return object
    }
}

private final class TestSharedTagCloudHarness {
    let repository: URLRepository
    let service: SharedTagCloudService

    private init(repository: URLRepository, service: SharedTagCloudService) {
        self.repository = repository
        self.service = service
    }

    static func make(baseURL: String) throws -> TestSharedTagCloudHarness {
        let directoryURL = FileManager.default.temporaryDirectory
            .appendingPathComponent(UUID().uuidString, isDirectory: true)
        try FileManager.default.createDirectory(at: directoryURL, withIntermediateDirectories: true)
        let databaseURL = directoryURL.appendingPathComponent("urlsaver.sqlite")
        let repository = try URLRepository(databaseURL: databaseURL)
        let store = try SharedTagStore(database: repository.database)
        let sessionStore = SharedTagAuthSessionStore(storage: InMemorySharedTagAuthSecureStorage())
        let service = SharedTagCloudService(
            config: SharedTagCloudConfig(
                enabled: true,
                supabaseURL: baseURL,
                anonKey: "dev-anon-key"
            ),
            sessionStore: sessionStore,
            store: store,
            repository: repository
        )
        return TestSharedTagCloudHarness(repository: repository, service: service)
    }
}

private final class InMemorySharedTagAuthSecureStorage: SharedTagAuthSecureStorage, @unchecked Sendable {
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

private extension SharedTagCloudConfig {
    init(
        enabled: Bool,
        supabaseURL: String,
        anonKey: String,
        inviteLinkBaseURL: String = "https://miyamibu.xyz"
    ) {
        self.enabled = enabled
        self.supabaseURL = supabaseURL
        self.anonKey = anonKey
        self.inviteLinkBaseURL = inviteLinkBaseURL
    }
}
