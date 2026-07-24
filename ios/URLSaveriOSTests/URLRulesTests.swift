import XCTest
@testable import URLSaveriOS

final class URLRulesTests: XCTestCase {
    func testIncomingURLRouteAcceptsOnlyCanonicalInviteShape() {
        let token = String(repeating: "a", count: 48)
        guard let url = URL(string: "https://miyamibu.xyz/invite/\(token)") else {
            return XCTFail("valid invite URL could not be constructed")
        }

        guard case .invite(let parsedToken) = IncomingURLRoute(url: url) else {
            return XCTFail("canonical invite URL was rejected")
        }
        XCTAssertEqual(parsedToken, token)
    }

    func testIncomingURLRouteRejectsUntrustedInviteVariants() {
        let token = String(repeating: "a", count: 48)
        let urls = [
            "http://miyamibu.xyz/invite/\(token)",
            "https://evil.example/invite/\(token)",
            "https://miyamibu.xyz/invite/\(token)?next=1",
            "https://miyamibu.xyz/invite/\(token)#fragment",
            "https://miyamibu.xyz/invite/%61\(String(repeating: "a", count: 47))",
            "urlsaver://invite/\(token)?next=1",
            "urlsaver://invite/not-a-token",
        ]

        for rawURL in urls {
            guard let url = URL(string: rawURL) else {
                return XCTFail("invalid test URL: \(rawURL)")
            }
            if case .invite = IncomingURLRoute(url: url) {
                XCTFail("untrusted invite URL was accepted: \(rawURL)")
            }
        }
    }

    func testNormalizeAppliesPhaseRules() {
        XCTAssertEqual(
            URLRules.normalize("HTTPS://Example.COM:443/path/?a=1#frag"),
            "https://example.com/path?a=1"
        )
        XCTAssertEqual(
            URLRules.normalize("HTTPS://Example.com:443/?q=1"),
            "https://example.com/?q=1"
        )
    }

    func testNormalizeRejectsMissingSchemeAndNonHTTPS() {
        XCTAssertNil(URLRules.normalize("example.com/path"))
        XCTAssertNil(URLRules.normalize("http://example.com/path"))
        XCTAssertNil(URLRules.normalize("ftp://example.com/path"))
    }

    func testNormalizeRejectsURLUserInfo() {
        XCTAssertNil(URLRules.normalize("https://user:password@example.com/private"))
    }

    func testNormalizeAllowsLoopbackHTTP() {
        XCTAssertEqual(
            URLRules.normalize("http://127.0.0.1/path"),
            "http://127.0.0.1/path"
        )
    }

    func testDisplayURLKeepsOnlyYouTubeVQuery() {
        let display = URLRules.toDisplayURL(
            normalizedURL: "https://www.youtube.com/watch?v=abc123&t=9",
            service: .youtube
        )
        XCTAssertEqual(display, "www.youtube.com/watch?v=abc123")
    }

    func testExtractFromCandidateGroupsFallsBackByPriority() {
        let groups = ShareCandidateGroups(
            extraCandidates: ["broken https:///oops"],
            providerTextCandidates: ["https://example.com/path"],
            streamCandidates: ["https://ignored.example"],
            directURLCandidates: []
        )

        XCTAssertEqual(
            URLRules.extractFromCandidateGroups(groups),
            .found("https://example.com/path")
        )
    }

    func testExtractAllDeduplicatesNormalizedURLs() {
        let groups = ShareCandidateGroups(
            extraCandidates: [
                "HTTPS://Example.com:443/path?x=1",
                "https://example.com/path?x=1",
                "https://example.com/next",
            ]
        )

        XCTAssertEqual(
            URLRules.extractAllFromCandidateGroups(groups).urls,
            ["https://example.com/path?x=1", "https://example.com/next"]
        )
    }

    func testExtractAllCollectsMultipleURLsFromShareExtensionCandidateGroups() {
        let groups = ShareCandidateGroups(
            extraCandidates: [
                """
                あとで読む
                https://example.com/first
                https://example.com/second#fragment
                """
            ],
            providerTextCandidates: [
                "共有本文 https://example.com/third?x=1"
            ],
            streamCandidates: [
                "https://example.com/second"
            ]
        )

        XCTAssertEqual(
            URLRules.extractAllFromCandidateGroups(groups).urls,
            [
                "https://example.com/first",
                "https://example.com/second",
                "https://example.com/third?x=1",
            ]
        )
    }

    func testManualInputDifferentiatesNoURLAndInvalidURL() {
        XCTAssertEqual(URLRules.extractForManualInput("hello"), .noURLFound)
        XCTAssertEqual(URLRules.extractForManualInput("https:///broken"), .invalidURL)
    }

    func testManualInputAcceptsUppercaseHTTPSScheme() {
        XCTAssertEqual(
            URLRules.extractForManualInput("HTTPS://Example.COM:443/path/#frag"),
            .found("HTTPS://Example.COM:443/path/#frag")
        )
    }

    func testExtractMemoWithoutURLsRemovesValidURLsAndKeepsSharedText() {
        let memo = URLRules.extractMemoWithoutURLs(
            """
            あとで読む
            https://example.com/a
            メモ本文
            https://example.com/b?x=1
            """
        )

        XCTAssertEqual(memo, "あとで読む\nメモ本文")
    }

    func testManualInputTooLargeReturnsExplicitError() {
        let oversized = String(repeating: "a", count: URLRules.maxInputTextBytes + 1)
        XCTAssertEqual(URLRules.extractForManualInput(oversized), .inputTooLarge)
    }

    func testExtractAllCapsToMaxBatchSize() {
        let payload = (0..<(URLRules.maxBatchSaveURLsPerIntake + 5))
            .map { "https://example.com/item-\($0)" }
            .joined(separator: "\n")
        let groups = ShareCandidateGroups(extraCandidates: [payload])

        let extracted = URLRules.extractAllFromCandidateGroups(groups)

        XCTAssertEqual(extracted.urls.count, URLRules.maxBatchSaveURLsPerIntake)
        XCTAssertTrue(extracted.truncatedToMaxURLs)
        XCTAssertEqual(extracted.urls.first, "https://example.com/item-0")
    }

    func testOversizedCandidateGroupReturnsExplicitError() {
        let oversized = String(repeating: "a", count: URLRules.maxInputTextBytes + 1)
        let groups = ShareCandidateGroups(extraCandidates: [oversized])
        XCTAssertEqual(URLRules.extractFromCandidateGroups(groups), .inputTooLarge)
    }

    func testEntitlementResolverFallsBackWithoutGrant() {
        let resolver = EntitlementResolver(grantsProvider: { [] })

        let resolved = resolver.resolve(at: Date(timeIntervalSince1970: 1_000))

        XCTAssertEqual(resolved.planType, .launchStandard)
        XCTAssertEqual(resolved.limits.personalURLLimit, 200)
    }

    func testEntitlementResolverReturnsProForActiveGrant() {
        let resolver = EntitlementResolver(
            grantsProvider: {
                [
                    EntitlementGrant(
                        planType: .pro,
                        source: .storeSubscription,
                        startsAt: Date(timeIntervalSince1970: 0)
                    )
                ]
            }
        )

        let resolved = resolver.resolve(at: Date(timeIntervalSince1970: 1_000))

        XCTAssertEqual(resolved.planType, .pro)
        XCTAssertEqual(resolved.limits.personalURLLimit, 10_000)
    }

    func testEntitlementResolverIgnoresRevokedExpiredAndPendingGrants() {
        let now = Date(timeIntervalSince1970: 1_000)
        let resolver = EntitlementResolver(
            grantsProvider: {
                [
                    EntitlementGrant(
                        planType: .pro,
                        source: .storeSubscription,
                        status: .revoked,
                        startsAt: Date(timeIntervalSince1970: 0)
                    ),
                    EntitlementGrant(
                        planType: .promoPro,
                        source: .adminGrant,
                        startsAt: Date(timeIntervalSince1970: 0),
                        endsAt: Date(timeIntervalSince1970: 999)
                    ),
                    EntitlementGrant(
                        planType: .pro,
                        source: .storePromoCode,
                        status: .pending,
                        startsAt: Date(timeIntervalSince1970: 0)
                    )
                ]
            }
        )

        let resolved = resolver.resolve(at: now)

        XCTAssertEqual(resolved.planType, .launchStandard)
    }

    func testEntitlementResolverUsesHighestPlanPriority() {
        let resolver = EntitlementResolver(
            grantsProvider: {
                [
                    EntitlementGrant(
                        planType: .free,
                        source: .adminGrant,
                        startsAt: Date(timeIntervalSince1970: 0)
                    ),
                    EntitlementGrant(
                        planType: .pro,
                        source: .storeSubscription,
                        startsAt: Date(timeIntervalSince1970: 0)
                    )
                ]
            }
        )

        let resolved = resolver.resolve(at: Date(timeIntervalSince1970: 1_000))

        XCTAssertEqual(resolved.planType, .pro)
    }

    func testLimitCheckerMatchesLaunchStandardLimits() {
        let checker = LimitChecker(entitlements: LaunchStandardPlan.entitlements)
        let result = checker.checkCanSavePersonalURL(
            UsageSummary(
                personalURLCount: LaunchStandardPlan.limits.personalURLLimit,
                normalTagCount: 0,
                sharedTagCount: 0,
                sharedTagUsages: []
            )
        )

        XCTAssertEqual(
            result,
            .blocked(
                target: .personalURL,
                message: "ローンチ版の保存上限に達しました。不要なURLを整理してから追加してください。"
            )
        )
    }

    func testLimitCheckerAllowsProBeyondLaunchStandardLimits() {
        let checker = LimitChecker(entitlements: ProPlan.entitlements)
        let result = checker.checkCanSavePersonalURL(
            UsageSummary(
                personalURLCount: LaunchStandardPlan.limits.personalURLLimit,
                normalTagCount: 0,
                sharedTagCount: 0,
                sharedTagUsages: []
            )
        )

        XCTAssertEqual(result, .allowed)
    }

    func testEntitlementCacheReturnsLastKnownWithinTTL() {
        let suiteName = "EntitlementCacheTests-\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suiteName)!
        defer { defaults.removePersistentDomain(forName: suiteName) }
        let cache = EntitlementGrantCache(userDefaults: defaults)
        let grant = EntitlementGrant(
            planType: .pro,
            source: .storeSubscription,
            startsAt: Date(timeIntervalSince1970: 0)
        )
        cache.save(
            authUserID: "user-1",
            grants: [grant],
            fetchedAt: Date(timeIntervalSince1970: 1_000)
        )

        let loaded = cache.load(
            authUserID: "user-1",
            now: Date(timeIntervalSince1970: 1_000 + EntitlementGrantCache.cacheTTL - 1)
        )

        XCTAssertEqual(loaded, [grant])
    }

    func testEntitlementCacheDropsWrongUserAndExpiredTTL() {
        let suiteName = "EntitlementCacheTests-\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suiteName)!
        defer { defaults.removePersistentDomain(forName: suiteName) }
        let cache = EntitlementGrantCache(userDefaults: defaults)
        cache.save(
            authUserID: "user-1",
            grants: [
                EntitlementGrant(
                    planType: .pro,
                    source: .storeSubscription,
                    startsAt: Date(timeIntervalSince1970: 0)
                )
            ],
            fetchedAt: Date(timeIntervalSince1970: 1_000)
        )

        XCTAssertEqual(cache.load(authUserID: "user-2", now: Date(timeIntervalSince1970: 1_000)), [])
        XCTAssertEqual(
            cache.load(
                authUserID: "user-1",
                now: Date(timeIntervalSince1970: 1_000 + EntitlementGrantCache.cacheTTL + 1)
            ),
            []
        )
    }

    func testSupabaseEntitlementTimestampParserAcceptsFractionalSeconds() {
        XCTAssertNotNil(parseSupabaseISO8601Date("2026-05-01T04:05:06.789123Z"))
        XCTAssertNotNil(parseSupabaseISO8601Date("2026-05-01T04:05:06Z"))
    }
}
