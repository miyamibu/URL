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

    func testContactSupportClientSuccess() async throws {
        ContactSupportURLProtocol.reset()
        ContactSupportURLProtocol.response = (
            202,
            Data(#"{"requestId":"req-1","status":"accepted"}"#.utf8)
        )
        let client = ContactSupportClient(
            config: ContactSupportConfig(
                bundle: .main,
                environment: ["URLSAVER_CONTACT_SUPPORT_ENDPOINT_URL": "https://example.com/contact-support"]
            ),
            session: ContactSupportURLProtocol.session()
        )

        let result = await client.send(validContactSupportRequest())

        XCTAssertEqual(result, .success("req-1"))
        XCTAssertEqual(ContactSupportURLProtocol.lastRequest?.httpMethod, "POST")
        XCTAssertFalse(ContactSupportURLProtocol.lastRequest?.value(forHTTPHeaderField: "Idempotency-Key")?.isEmpty ?? true)
    }

    func testContactSupportClientLegacySuccess() async throws {
        ContactSupportURLProtocol.reset()
        ContactSupportURLProtocol.response = (
            200,
            Data(#"{"status":"sent"}"#.utf8)
        )
        let client = ContactSupportClient(
            config: ContactSupportConfig(
                bundle: .main,
                environment: ["URLSAVER_CONTACT_SUPPORT_ENDPOINT_URL": "https://example.com/contact-support"]
            ),
            session: ContactSupportURLProtocol.session()
        )

        let result = await client.send(validContactSupportRequest())

        XCTAssertEqual(result, .success(""))
    }

    func testContactSupportClientFailureMessage() async throws {
        ContactSupportURLProtocol.reset()
        ContactSupportURLProtocol.response = (
            429,
            Data(#"{"error":"rate_limited"}"#.utf8)
        )
        let client = ContactSupportClient(
            config: ContactSupportConfig(
                bundle: .main,
                environment: ["URLSAVER_CONTACT_SUPPORT_ENDPOINT_URL": "https://example.com/contact-support"]
            ),
            session: ContactSupportURLProtocol.session()
        )

        let result = await client.send(validContactSupportRequest())

        XCTAssertEqual(result, .failure("短時間に問い合わせが多すぎます。少し時間をおいて再度お試しください。"))
    }

    func testContactSupportClientUnconfigured() async throws {
        let client = ContactSupportClient(
            config: ContactSupportConfig(
                bundle: .main,
                environment: ["URLSAVER_CONTACT_SUPPORT_ENDPOINT_URL": ""]
            ),
            session: ContactSupportURLProtocol.session()
        )

        let result = await client.send(validContactSupportRequest())

        XCTAssertEqual(result, .failure("問い合わせ送信先が設定されていません"))
    }

    private func validContactSupportRequest() -> ContactSupportRequest {
        ContactSupportRequest(
            email: "user@example.com",
            name: "User",
            message: "hello",
            platform: "ios",
            appVersion: "1.0",
            buildType: "debug",
            isSignedIn: false,
            authUserId: nil
        )
    }
}

private final class ContactSupportURLProtocol: URLProtocol {
    nonisolated(unsafe) static var response: (status: Int, data: Data) = (202, Data())
    nonisolated(unsafe) static var lastRequest: URLRequest?

    static func reset() {
        response = (202, Data())
        lastRequest = nil
    }

    static func session() -> URLSession {
        let config = URLSessionConfiguration.ephemeral
        config.protocolClasses = [ContactSupportURLProtocol.self]
        return URLSession(configuration: config)
    }

    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }

    override func startLoading() {
        Self.lastRequest = request
        let http = HTTPURLResponse(
            url: request.url!,
            statusCode: Self.response.status,
            httpVersion: nil,
            headerFields: ["Content-Type": "application/json"]
        )!
        client?.urlProtocol(self, didReceive: http, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: Self.response.data)
        client?.urlProtocolDidFinishLoading(self)
    }

    override func stopLoading() {}
}
