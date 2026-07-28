import Foundation
import XCTest
@testable import URLSaveriOS

final class SharedTagOAuthHardeningTests: XCTestCase {
    func testCallbackParametersPreserveDuplicatesAndRejectQueryFragmentMixing() throws {
        let queryURL = try XCTUnwrap(URL(string: "urlsaver://auth/callback?code=one&code=two"))
        let queryParameters = try SharedTagAuthRemoteDataSource.callbackParameters(from: queryURL)
        XCTAssertEqual(queryParameters["code"], ["one", "two"])

        let fragmentURL = try XCTUnwrap(URL(string: "urlsaver://auth/callback#code=one&code=two"))
        let fragmentParameters = try SharedTagAuthRemoteDataSource.callbackParameters(from: fragmentURL)
        XCTAssertEqual(fragmentParameters["code"], ["one", "two"])

        let mixedURL = try XCTUnwrap(URL(string: "urlsaver://auth/callback?code=one#error=denied"))
        XCTAssertThrowsError(try SharedTagAuthRemoteDataSource.callbackParameters(from: mixedURL))

        let emptyFragmentURL = try XCTUnwrap(URL(string: "urlsaver://auth/callback?code=one#"))
        XCTAssertThrowsError(try SharedTagAuthRemoteDataSource.callbackParameters(from: emptyFragmentURL))

        let emptyNameURL = try XCTUnwrap(URL(string: "urlsaver://auth/callback?=value&code=one"))
        XCTAssertThrowsError(try SharedTagAuthRemoteDataSource.callbackParameters(from: emptyNameURL))
    }

    func testCallbackBaseMustMatchExactly() throws {
        XCTAssertTrue(
            SharedTagAuthRemoteDataSource.isAllowedAuthCallback(
                URL(string: "urlsaver://auth/callback?code=one")
            )
        )
        XCTAssertFalse(
            SharedTagAuthRemoteDataSource.isAllowedAuthCallback(
                URL(string: "urlsaver://auth/callback.evil?code=one")
            )
        )
        XCTAssertFalse(
            SharedTagAuthRemoteDataSource.isAllowedAuthCallback(
                URL(string: "urlsaver://auth:443/callback?code=one")
            )
        )
        XCTAssertFalse(
            SharedTagAuthRemoteDataSource.isAllowedAuthCallback(
                URL(string: "urlsaver://user:pass@auth/callback?code=one")
            )
        )
    }

    func testPendingStateIsNotClearedUntilDecodeAndIdentityValidationPass() throws {
        let now = Date(timeIntervalSince1970: 1_000)
        let storage = OAuthTestStorage()
        let store = SharedTagOAuthStateStore(storage: storage, nowProvider: { now })
        let pending = SharedTagOAuthPendingState(
            provider: "google",
            codeVerifier: "verifier",
            redirectTo: "urlsaver://auth/callback",
            expiresAt: now.addingTimeInterval(60)
        )
        try store.save(pending)

        XCTAssertThrowsError(try store.consume(provider: "apple", redirectTo: pending.redirectTo))
        XCTAssertNotNil(try storage.load())

        XCTAssertThrowsError(try store.consume(provider: pending.provider, redirectTo: "urlsaver://wrong"))
        XCTAssertNotNil(try storage.load())

        let replacement = SharedTagOAuthPendingState(
            provider: pending.provider,
            codeVerifier: "replacement-verifier",
            redirectTo: pending.redirectTo,
            expiresAt: now.addingTimeInterval(60)
        )
        try store.save(replacement)
        XCTAssertThrowsError(try store.consume(pending))
        XCTAssertEqual(
            try store.loadValidated(provider: replacement.provider, redirectTo: replacement.redirectTo),
            replacement
        )

        try storage.save(Data("not-json".utf8))
        XCTAssertThrowsError(try store.consume(provider: pending.provider, redirectTo: pending.redirectTo))
        XCTAssertNotNil(try storage.load())

        try store.save(pending)
        let consumed = try store.consume(provider: pending.provider, redirectTo: pending.redirectTo)
        XCTAssertEqual(consumed, pending)
        XCTAssertNil(try storage.load())
    }

    func testExpiredPendingStateIsNotCleared() throws {
        let now = Date(timeIntervalSince1970: 1_000)
        let storage = OAuthTestStorage()
        let store = SharedTagOAuthStateStore(storage: storage, nowProvider: { now })
        try store.save(
            SharedTagOAuthPendingState(
                provider: "google",
                codeVerifier: "verifier",
                redirectTo: "urlsaver://auth/callback",
                expiresAt: now.addingTimeInterval(-1)
            )
        )

        XCTAssertThrowsError(try store.consume(provider: "google", redirectTo: "urlsaver://auth/callback"))
        XCTAssertNotNil(try storage.load())
    }

    func testInvalidExternalCallbackDoesNotConsumePending() async throws {
        let storage = OAuthTestStorage()
        let dataSource = makeDataSource(storage: storage)
        _ = try dataSource.oauthURL(provider: "google", redirectTo: "urlsaver://auth/callback")

        do {
            _ = try await dataSource.signInWithOAuthCallback(
                url: XCTUnwrap(URL(string: "urlsaver://other/callback?code=one"))
            )
            XCTFail("invalid callback unexpectedly succeeded")
        } catch {
            // The callback is rejected before any token exchange or pending-state consume.
        }

        XCTAssertNotNil(try storage.load())
    }

    func testSuccessfulTokenExchangeConsumesPendingAfterSessionValidation() async throws {
        OAuthMockURLProtocol.setResponseData(Data(
            """
            {
              "access_token": "access-token",
              "refresh_token": "refresh-token",
              "user": { "id": "user-id", "email": "user@example.com" }
            }
            """.utf8
        ))
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [OAuthMockURLProtocol.self]
        let session = URLSession(configuration: configuration)
        let storage = OAuthTestStorage()
        let dataSource = makeDataSource(storage: storage, session: session)
        _ = try dataSource.oauthURL(provider: "google", redirectTo: "urlsaver://auth/callback")

        let result = try await dataSource.signInWithOAuthCallback(
            url: XCTUnwrap(URL(string: "urlsaver://auth/callback?code=authorization-code"))
        )

        guard case .signedIn(let session) = result else {
            return XCTFail("OAuth callback did not return a signed-in session")
        }
        XCTAssertEqual(session.authUserID, "user-id")
        XCTAssertNil(try storage.load())
    }

    func testFailedTokenExchangeKeepsPendingForRetry() async throws {
        OAuthMockURLProtocol.setResponse(
            statusCode: 400,
            data: Data(#"{"message":"invalid code"}"#.utf8)
        )
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [OAuthMockURLProtocol.self]
        let session = URLSession(configuration: configuration)
        let storage = OAuthTestStorage()
        let dataSource = makeDataSource(storage: storage, session: session)
        _ = try dataSource.oauthURL(provider: "google", redirectTo: "urlsaver://auth/callback")

        do {
            _ = try await dataSource.signInWithOAuthCallback(
                url: XCTUnwrap(URL(string: "urlsaver://auth/callback?code=authorization-code"))
            )
            XCTFail("failed OAuth exchange unexpectedly succeeded")
        } catch {
            // The pending state must remain available for a retry.
        }

        XCTAssertNotNil(try storage.load())
    }

    func testConcurrentOAuthStartDoesNotReplacePendingState() throws {
        let storage = OAuthTestStorage()
        let dataSource = makeDataSource(storage: storage)
        _ = try dataSource.oauthURL(provider: "google", redirectTo: "urlsaver://auth/callback")
        let firstPending = try XCTUnwrap(try storage.load())

        XCTAssertThrowsError(
            try dataSource.oauthURL(provider: "google", redirectTo: "urlsaver://auth/callback")
        )
        XCTAssertEqual(try storage.load(), firstPending)
    }

    private func makeDataSource(
        storage: OAuthTestStorage,
        session: URLSession = .shared
    ) -> SharedTagAuthRemoteDataSource {
        SharedTagAuthRemoteDataSource(
            config: SharedTagCloudConfig(
                enabled: true,
                supabaseURL: "https://example.supabase.co",
                anonKey: "anon-key"
            ),
            oauthStateStore: SharedTagOAuthStateStore(storage: storage),
            session: session
        )
    }
}

private final class OAuthTestStorage: SharedTagAuthSecureStorage, @unchecked Sendable {
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

private final class OAuthMockURLProtocol: URLProtocol {
    private static let responseStorage = OAuthResponseStorage()

    static func setResponseData(_ data: Data) {
        responseStorage.set(data)
    }

    static func setResponse(statusCode: Int, data: Data) {
        responseStorage.set(statusCode: statusCode, data: data)
    }

    override class func canInit(with request: URLRequest) -> Bool {
        true
    }

    override class func canonicalRequest(for request: URLRequest) -> URLRequest {
        request
    }

    override func startLoading() {
        guard let url = request.url,
              let response = HTTPURLResponse(
                  url: url,
                  statusCode: Self.responseStorage.statusCode(),
                  httpVersion: nil,
                  headerFields: ["Content-Type": "application/json"]
              ) else {
            client?.urlProtocol(self, didFailWithError: URLError(.badURL))
            return
        }
        client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: Self.responseStorage.load())
        client?.urlProtocolDidFinishLoading(self)
    }

    override func stopLoading() {}
}

private final class OAuthResponseStorage: @unchecked Sendable {
    private let lock = NSLock()
    private var status = 200
    private var data = Data()

    func set(_ data: Data) {
        set(statusCode: 200, data: data)
    }

    func set(statusCode: Int, data: Data) {
        lock.lock()
        status = statusCode
        self.data = data
        lock.unlock()
    }

    func statusCode() -> Int {
        lock.lock()
        defer { lock.unlock() }
        return status
    }

    func load() -> Data {
        lock.lock()
        defer { lock.unlock() }
        return data
    }
}
