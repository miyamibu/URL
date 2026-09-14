import Foundation
import XCTest
@testable import URLSaveriOS

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

final class IncomingURLRouteTests: XCTestCase {
    func testCanonicalHTTPSInviteRoute() throws {
        switch try route("https://miyamibu.xyz/invite/invite-token") {
        case .invite(let token):
            XCTAssertEqual(token, "invite-token")
        default:
            XCTFail("canonical HTTPS invite URL must be routed as an invite")
        }
    }

    func testCanonicalHTTPSInviteWithoutTokenIsClassifiedAsEmptyInvite() throws {
        switch try route("https://miyamibu.xyz/invite") {
        case .invite(let token):
            XCTAssertEqual(token, "")
        default:
            XCTFail("canonical HTTPS invite without a token must preserve the invite invalid path")
        }
    }

    func testCanonicalHTTPSInviteWithTrailingSlashWithoutTokenIsClassifiedAsEmptyInvite() throws {
        switch try route("https://miyamibu.xyz/invite/") {
        case .invite(let token):
            XCTAssertEqual(token, "")
        default:
            XCTFail("canonical HTTPS invite with a trailing slash must preserve the invite invalid path")
        }
    }

    func testCanonicalHTTPSInviteWithMultipleTokenSegmentsRemainsUnknown() throws {
        if case .unknown = try route("https://miyamibu.xyz/invite/token/extra") {
            return
        }
        XCTFail("canonical HTTPS invite with multiple token segments must remain unknown")
    }

    func testCanonicalHTTPSInvitePathIsCaseSensitive() throws {
        if case .unknown = try route("https://miyamibu.xyz/INVITE/token") {
            return
        }
        XCTFail("uppercase HTTPS invite path must remain unknown")
    }

    func testCanonicalHTTPSHostComparisonIsCaseInsensitive() throws {
        switch try route("https://MIYAMIBU.XYZ/invite/uppercase-host-token") {
        case .invite(let token):
            XCTAssertEqual(token, "uppercase-host-token")
        default:
            XCTFail("HTTPS host comparison must be case-insensitive")
        }
    }

    func testCanonicalHTTPSPromoRouteFromQuery() throws {
        switch try route("https://miyamibu.xyz/promo?code=QUERY%20CODE") {
        case .promo(let code):
            XCTAssertEqual(code, "QUERY CODE")
        default:
            XCTFail("canonical HTTPS promo query must be routed as a promo")
        }
    }

    func testCanonicalHTTPSPromoRouteFromFragment() throws {
        switch try route("https://miyamibu.xyz/promo#code=FRAGMENT%20CODE") {
        case .promo(let code):
            XCTAssertEqual(code, "FRAGMENT CODE")
        default:
            XCTFail("canonical HTTPS promo fragment must be routed as a promo")
        }
    }

    func testCanonicalHTTPSPromoPathIsExactAndCaseSensitive() throws {
        let urls = [
            "https://miyamibu.xyz/promo/extra?code=X",
            "https://miyamibu.xyz/PROMO?code=X",
        ]

        for rawURL in urls {
            if case .unknown = try route(rawURL) {
                continue
            }
            XCTFail("non-exact or uppercase HTTPS promo path must remain unknown: \(rawURL)")
        }
    }

    func testCanonicalHTTPSPromoQueryCodeKeyWinsOverFragmentEvenWhenEmpty() throws {
        switch try route("https://miyamibu.xyz/promo?code=#code=FRAGMENT") {
        case .promo(let code):
            XCTAssertEqual(code, "")
        default:
            XCTFail("an empty query code must not fall through to the fragment")
        }
    }

    func testCanonicalHTTPSPromoDuplicateQueryCodeUsesFirstValue() throws {
        switch try route("https://miyamibu.xyz/promo?code=FIRST&code=SECOND") {
        case .promo(let code):
            XCTAssertEqual(code, "FIRST")
        default:
            XCTFail("the first duplicate query code must win")
        }
    }

    func testForeignSubdomainTrailingDotAndHTTPRoutesAreUnknown() throws {
        let urls = [
            "https://example.test/invite/foreign-token",
            "https://sub.miyamibu.xyz/invite/subdomain-token",
            "https://miyamibu.xyz.evil/invite/suffix-token",
            "https://evil-miyamibu.xyz/invite/prefix-token",
            "https://canonical.example.evil/invite/canonical-example-token",
            "https://miyamibu.xyz./invite/trailing-dot-token",
            "http://miyamibu.xyz/invite/http-token",
            "https://m\u{0456}yamibu.xyz/invite/unicode-lookalike-token",
            "https://miyamibu.xn--p1ai/invite/foreign-punycode-token",
        ]

        for rawURL in urls {
            if case .unknown = try route(rawURL) {
                continue
            }
            XCTFail("non-canonical web URL must be unknown: \(rawURL)")
        }
    }

    func testInjectedCanonicalHostReplacesProductionHost() throws {
        switch try route("https://future.example/invite/future-token", canonicalHTTPSHost: "future.example") {
        case .invite(let token):
            XCTAssertEqual(token, "future-token")
        default:
            XCTFail("injected canonical host must accept the injected host")
        }

        if case .unknown = try route(
            "https://miyamibu.xyz/invite/old-token",
            canonicalHTTPSHost: "future.example"
        ) {
            return
        }
        XCTFail("old host must be rejected after canonical host injection")
    }

    func testInvalidInjectedCanonicalHostFailsClosed() throws {
        if case .unknown = try route(
            "https://miyamibu.xyz/invite/token",
            canonicalHTTPSHost: ""
        ) {
            return
        }
        XCTFail("invalid canonical host configuration must fail closed")
    }

    func testMalformedCanonicalHTTPSBaseURLFailsClosed() {
        XCTAssertNil(IncomingURLRoute.canonicalHTTPSHost(fromBaseURL: "https://[invalid"))
    }

    func testHTTPCanonicalHTTPSBaseURLFailsClosed() {
        XCTAssertNil(IncomingURLRoute.canonicalHTTPSHost(fromBaseURL: "http://miyamibu.xyz"))
    }

    func testHostlessCanonicalHTTPSBaseURLFailsClosed() {
        XCTAssertNil(IncomingURLRoute.canonicalHTTPSHost(fromBaseURL: "https:///invite"))
    }

    func testEmptyCanonicalHTTPSBaseURLFailsClosed() {
        XCTAssertNil(IncomingURLRoute.canonicalHTTPSHost(fromBaseURL: ""))
    }

    func testTerminalDotCanonicalHTTPSBaseURLFailsClosed() {
        XCTAssertNil(IncomingURLRoute.canonicalHTTPSHost(fromBaseURL: "https://miyamibu.xyz."))
    }

    func testTerminalDotInjectedCanonicalHostFailsClosed() throws {
        if case .unknown = try route(
            "https://miyamibu.xyz/invite/token",
            canonicalHTTPSHost: "miyamibu.xyz."
        ) {
            return
        }
        XCTFail("terminal-dot injected canonical host must fail closed")
    }

    func testHTTPSUserInfoAndExplicitPortsRemainUnknown() throws {
        let urls = [
            "https://user:pass@miyamibu.xyz/invite/userinfo-token",
            "https://@miyamibu.xyz/invite/empty-userinfo-token",
            "https://miyamibu.xyz:443/invite/default-port-token",
            "https://miyamibu.xyz:8443/invite/explicit-port-token",
        ]

        for rawURL in urls {
            if case .unknown = try route(rawURL) {
                continue
            }
            XCTFail("HTTPS URL with userinfo or explicit port must be unknown: \(rawURL)")
        }
    }

    func testHTTPSAmbiguousPathEncodingsRemainUnknown() throws {
        let urls = [
            "https://miyamibu.xyz/invite/token%2Fpart",
            "https://miyamibu.xyz/invite/token%2fpart",
            "https://miyamibu.xyz/invite/token%5Cpart",
            "https://miyamibu.xyz/invite/token%5cpart",
            "https://miyamibu.xyz/invite\\token",
            "https://miyamibu.xyz/invite//token",
            "https://miyamibu.xyz/promo/%2F?code=encoded-slash-upper",
            "https://miyamibu.xyz/promo/%2f?code=encoded-slash-lower",
            "https://miyamibu.xyz/promo/%5C?code=encoded-backslash-upper",
            "https://miyamibu.xyz/promo/%5c?code=encoded-backslash-lower",
            "https://miyamibu.xyz/promo\\token?code=raw-backslash",
            "https://miyamibu.xyz/promo//token?code=double-slash",
        ]

        for rawURL in urls {
            if case .unknown = try route(rawURL) {
                continue
            }
            XCTFail("ambiguous HTTPS path must be unknown: \(rawURL)")
        }
    }

    func testCanonicalHTTPSBaseURLRejectsUserInfoExplicitPortsAndForeignHosts() {
        let urls = [
            "https://user:pass@miyamibu.xyz",
            "https://@miyamibu.xyz",
            "https://miyamibu.xyz:443",
            "https://miyamibu.xyz:8443",
            "https://m\u{0456}yamibu.xyz",
            "https://miyamibu.xn--p1ai",
        ]

        for rawURL in urls {
            XCTAssertNil(
                IncomingURLRoute.canonicalHTTPSHost(fromBaseURL: rawURL),
                "base URL must fail closed: \(rawURL)"
            )
        }
    }

    func testCustomSchemeInviteAndPromoRoutesRemainSupported() throws {
        switch try route("urlsaver://invite/custom-token") {
        case .invite(let token):
            XCTAssertEqual(token, "custom-token")
        default:
            XCTFail("custom-scheme invite URL must remain supported")
        }

        switch try route("urlsaver://promo?code=CUSTOM%20CODE") {
        case .promo(let code):
            XCTAssertEqual(code, "CUSTOM CODE")
        default:
            XCTFail("custom-scheme promo URL must remain supported")
        }
    }

    func testEmptyCustomSchemeInviteAndPromoRemainClassifiedForExistingGuards() throws {
        switch try route("urlsaver://invite") {
        case .invite(let token):
            XCTAssertEqual(token, "")
        default:
            XCTFail("empty custom-scheme invite must preserve its existing classification")
        }

        switch try route("urlsaver://promo?code=") {
        case .promo(let code):
            XCTAssertEqual(code, "")
        default:
            XCTFail("empty custom-scheme promo must preserve its existing classification")
        }
    }

    private func route(_ rawURL: String, canonicalHTTPSHost: String? = nil) throws -> IncomingURLRoute {
        let url = try XCTUnwrap(URL(string: rawURL))
        return IncomingURLRoute(url: url, canonicalHTTPSHost: canonicalHTTPSHost)
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
