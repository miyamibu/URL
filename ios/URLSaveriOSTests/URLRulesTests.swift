import XCTest

final class URLRulesTests: XCTestCase {
    func testSharedShareSelectionSurvivesRecreationAndLocksForRetry() throws {
        let now = Date(timeIntervalSince1970: 1_800_000_000)
        var operation = ShareExtensionPendingOperation(
            createdAt: now,
            updatedAt: now,
            originalURLs: ["https://example.com/shared"],
            memo: nil,
            degradationNotice: nil
        )
        operation.updateTagSelection([7], sharedTagRemoteIDs: ["shared-b", "shared-a"], at: now)
        let data = try JSONEncoder().encode(operation)
        var restored = try JSONDecoder().decode(ShareExtensionPendingOperation.self, from: data)
        XCTAssertEqual(restored.selectedTagIDs, [7])
        XCTAssertEqual(restored.selectedSharedTagRemoteIDs, ["shared-a", "shared-b"])
        restored.lockTagSelection([7], sharedTagRemoteIDs: ["shared-a", "shared-b"], at: now)
        restored.updateTagSelection([8], sharedTagRemoteIDs: ["shared-c"], at: now.addingTimeInterval(1))
        XCTAssertEqual(restored.selectedTagIDs, [7])
        XCTAssertEqual(restored.selectedSharedTagRemoteIDs, ["shared-a", "shared-b"])
    }

    func testCompletedShareRemainsRecoverableUntilHostHandoffIsSaved() {
        let now = Date(timeIntervalSince1970: 1_800_000_000)
        let url = "https://example.com/shared"
        let operation = ShareExtensionPendingOperation(
            createdAt: now,
            updatedAt: now,
            originalURLs: [url],
            memo: nil,
            degradationNotice: nil,
            pendingItems: [],
            completedItems: [ShareExtensionCompletedItem(url: url, result: .created, entryID: 7)]
        )
        XCTAssertTrue(operation.isRecoverable(at: now))
        XCTAssertFalse(operation.isRecoverable(at: now.addingTimeInterval(ShareExtensionPendingOperation.recoveryWindow + 1)))
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

    func testNormalizeAllowsLoopbackHTTP() {
        XCTAssertEqual(
            URLRules.normalize("http://127.0.0.1/path"),
            "http://127.0.0.1/path"
        )
    }

    func testNormalizeMatchesSharedContractVectors() throws {
        let fixtureURL = URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent()
            .deletingLastPathComponent()
            .deletingLastPathComponent()
            .appendingPathComponent("contracts/shared-tag-sync/url-normalization-v1.json")
        let vectors = try JSONDecoder().decode(
            [URLNormalizationVector].self,
            from: Data(contentsOf: fixtureURL)
        )

        XCTAssertEqual(URLRules.normalizationContractVersion, sharedTagNormalizationVersion)
        for vector in vectors {
            let parsed = URLRules.parseURL(vector.input)
            XCTAssertEqual(
                URLRules.normalize(vector.input),
                vector.expectedNormalizedURL,
                vector.input
            )
            XCTAssertEqual(parsed?.normalizedURL, vector.expectedNormalizedURL, vector.input)
            XCTAssertEqual(parsed?.openURL, vector.expectedNormalizedURL, vector.input)
        }
    }

    func testDisplayURLKeepsOnlyYouTubeVQuery() {
        let display = URLRules.toDisplayURL(
            normalizedURL: "https://www.youtube.com/watch?v=abc123&t=9",
            service: .youtube
        )
        XCTAssertEqual(display, "www.youtube.com/watch?v=abc123")
    }

    func testClassifiesTheThirtyAndroidMetadataLinkKindsForIOS() {
        let cases: [(String, ContentContext)] = [
            ("https://www.youtube.com/watch?v=MAZyQ-38b8M", .video),
            ("https://www.youtube.com/shorts/2wjdNG_sBnw", .shorts),
            ("https://www.youtube.com/live/iYmvCUonukw", .live),
            ("https://youtube.com/clip/UgkxU2HSeGL_NvmDJ-nQJrlLwllwMDBdGZFs", .video),
            ("https://www.youtube.com/playlist?list=PLbpi6ZahtOH6Blw3RGYpWkSByi_T7Rygb", .standard),
            ("https://www.youtube.com/post/UgkxspDXAu888uYflWUcy9sq7Ok0tkke0Ys_", .post),
            ("https://www.youtube.com/@OpenAI", .profile),
            ("https://youtu.be/MAZyQ-38b8M", .standard),
            ("https://www.youtube.com/embed/MAZyQ-38b8M", .video),
            ("https://x.com/sama/status/2097410967978324010", .post),
            ("https://x.com/OpenAI", .standard),
            ("https://x.com/i/lists/84839422", .list),
            ("https://x.com/i/communities/1493446837214187523", .standard),
            ("https://x.com/i/article/2044111546129756383", .standard),
            ("https://www.instagram.com/p/DWx_-qMFAzY", .post),
            ("https://www.instagram.com/reel/DcMXl1IPNtB", .reel),
            ("https://www.instagram.com/stories/highlights/18195781759377100", .highlight),
            ("https://www.instagram.com/channel/AbZ19cL4AVphbig9", .channel),
            ("https://www.instagram.com/reels/audio/28532324479739746", .sound),
            ("https://www.instagram.com/nasa", .profile),
            ("https://www.instagram.com/explore/tags/nasa", .hashtag),
            ("https://www.tiktok.com/@scout2015/video/6718335390845095173", .video),
            ("https://www.tiktok.com/@tiktok", .profile),
            ("https://www.tiktok.com/@tiktok/playlist/In-The-Mix-7516638364301265695", .playlist),
            ("https://www.tiktok.com/share/music/7120258950676154369", .music),
            ("https://www.tiktok.com/music/original-sound-7536694526191127327", .music),
            ("https://www.tiktok.com/tag/nasa", .hashtag),
            ("https://www.tiktok.com/sticker/MGS-Alert-274011806", .standard),
            ("https://www.tiktok.com/player/v1/6718335390845095173", .video),
            ("https://www.tiktok.com/t/ZSun1U11T", .shortURL),
        ]

        XCTAssertEqual(cases.count, 30)
        for (url, expectedContext) in cases {
            XCTAssertEqual(URLRules.parseURL(url)?.contentContext, expectedContext, url)
        }
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

    func testExtractAcceptsInstagramReelFromSharedProseWithTrailingPunctuation() {
        let groups = ShareCandidateGroups(
            extraCandidates: [
                "このリールをチェックしてください https://www.instagram.com/reel/ABC123/?igsh=share_token。"
            ]
        )

        XCTAssertEqual(
            URLRules.extractFromCandidateGroups(groups),
            .found("https://www.instagram.com/reel/ABC123/?igsh=share_token")
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

    func testEntitlementResolverFallsBackToProWithoutGrant() {
        let resolver = EntitlementResolver(grantsProvider: { [] })

        let resolved = resolver.resolve(at: Date(timeIntervalSince1970: 1_000))

        XCTAssertEqual(resolved.planType, .pro)
        XCTAssertEqual(resolved.limits.personalURLLimit, 10_000)
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

        XCTAssertEqual(resolved.planType, .pro)
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

    func testEntitlementResolverDefaultsToPro() {
        let planType = EntitlementResolver().resolve().planType

        XCTAssertEqual(planType, .pro)
        XCTAssertTrue(planType.isPaidCourse)
    }

    func testPaidCourseOptionsDoNotOfferRedundantOrDowngradePurchases() {
        XCTAssertTrue(availablePaidCoursePurchaseOptions(currentPlan: .pro).isEmpty)
        XCTAssertTrue(availablePaidCoursePurchaseOptions(currentPlan: .promoPro).isEmpty)
        XCTAssertEqual(
            availablePaidCoursePurchaseOptions(currentPlan: .standard),
            [
                PaidCoursePurchaseOption(planType: .pro, billingPeriod: .monthly),
                PaidCoursePurchaseOption(planType: .pro, billingPeriod: .yearly),
            ]
        )
        XCTAssertEqual(availablePaidCoursePurchaseOptions(currentPlan: .free).count, 4)
        XCTAssertEqual(availablePaidCoursePurchaseOptions(currentPlan: .launchStandard).count, 4)
    }

    func testEntitlementResolverDoesNotDowngradeProDefault() {
        let resolver = EntitlementResolver(
            grantsProvider: {
                [
                    EntitlementGrant(
                        planType: .free,
                        source: .adminGrant,
                        startsAt: Date(timeIntervalSince1970: 0)
                    )
                ]
            }
        )

        XCTAssertEqual(resolver.resolve(at: Date(timeIntervalSince1970: 1_000)).planType, .pro)
    }

    func testLimitCheckerAllowsUnlimitedNormalTagsForEveryPlan() {
        let result = LimitChecker(entitlements: FreePlan.entitlements).checkCanCreateNormalTag(
            UsageSummary(
                personalURLCount: 0,
                normalTagCount: .max,
                sharedTagCount: 0,
                sharedTagUsages: []
            )
        )

        XCTAssertEqual(result, .allowed)
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
                message: "現在のプランの保存上限に達しました。不要なURLを整理してから追加してください。"
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

    func testEntitlementCacheClearIsLimitedToMatchingAccount() {
        let suiteName = "EntitlementCacheClearTests-\(UUID().uuidString)"
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

        cache.clear(authUserID: "user-2")
        XCTAssertEqual(cache.load(authUserID: "user-1", now: Date(timeIntervalSince1970: 1_001)), [grant])

        cache.clear(authUserID: "user-1")
        XCTAssertEqual(cache.load(authUserID: "user-1", now: Date(timeIntervalSince1970: 1_001)), [])
    }

    func testEntitlementCacheDeletionFenceRejectsLateOldUserSaveAcrossStoreRecreation() {
        let suiteName = "EntitlementCacheDeletionFence-\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suiteName)!
        defer { defaults.removePersistentDomain(forName: suiteName) }
        let key = "entitlement.cache.\(UUID().uuidString)"
        let staleOperationCache = EntitlementGrantCache(userDefaults: defaults, key: key)
        let cleanupCache = EntitlementGrantCache(userDefaults: defaults, key: key)
        let grant = EntitlementGrant(
            planType: .pro,
            source: .storeSubscription,
            startsAt: Date(timeIntervalSince1970: 0)
        )

        cleanupCache.clear(authUserID: "user-a")
        let lateSaveAccepted = staleOperationCache.save(
            authUserID: "user-a",
            grants: [grant],
            fetchedAt: Date(timeIntervalSince1970: 1_000)
        )
        let recreatedCache = EntitlementGrantCache(userDefaults: defaults, key: key)

        XCTAssertFalse(lateSaveAccepted)
        XCTAssertEqual(
            recreatedCache.load(authUserID: "user-a", now: Date(timeIntervalSince1970: 1_001)),
            []
        )
        XCTAssertTrue(
            recreatedCache.save(
                authUserID: "user-b",
                grants: [grant],
                fetchedAt: Date(timeIntervalSince1970: 1_000)
            )
        )
        XCTAssertEqual(
            recreatedCache.load(authUserID: "user-b", now: Date(timeIntervalSince1970: 1_001)),
            [grant]
        )
    }

    func testEntitlementCacheConcurrentClearAndOldSaveAlwaysEndsInvalidated() {
        let suiteName = "EntitlementCacheConcurrentFence-\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suiteName)!
        defer { defaults.removePersistentDomain(forName: suiteName) }
        let key = "entitlement.cache.\(UUID().uuidString)"
        let staleOperationCache = EntitlementGrantCache(userDefaults: defaults, key: key)
        let cleanupCache = EntitlementGrantCache(userDefaults: defaults, key: key)
        let grant = EntitlementGrant(
            planType: .pro,
            source: .storeSubscription,
            startsAt: Date(timeIntervalSince1970: 0)
        )

        DispatchQueue.concurrentPerform(iterations: 100) { index in
            if index.isMultiple(of: 2) {
                _ = staleOperationCache.save(
                    authUserID: "user-a",
                    grants: [grant],
                    fetchedAt: Date(timeIntervalSince1970: TimeInterval(index))
                )
            } else {
                cleanupCache.clear(authUserID: "user-a")
            }
        }

        XCTAssertEqual(
            staleOperationCache.load(authUserID: "user-a", now: Date(timeIntervalSince1970: 200)),
            []
        )
        XCTAssertEqual(
            EntitlementGrantCache(userDefaults: defaults, key: key)
                .load(authUserID: "user-a", now: Date(timeIntervalSince1970: 200)),
            []
        )
    }

    func testSupabaseEntitlementTimestampParserAcceptsFractionalSeconds() {
        XCTAssertNotNil(parseSupabaseISO8601Date("2026-05-01T04:05:06.789123Z"))
        XCTAssertNotNil(parseSupabaseISO8601Date("2026-05-01T04:05:06Z"))
    }

    func testShareExtensionPendingOperationSurvivesStoreRecreationAndClearsOnlyMatchingOperation() throws {
        let directory = FileManager.default.temporaryDirectory
            .appendingPathComponent("share-pending-\(UUID().uuidString)", isDirectory: true)
        let fileURL = directory.appendingPathComponent("pending.json")
        defer { try? FileManager.default.removeItem(at: directory) }
        let now = Date(timeIntervalSince1970: 2_000_000)
        let operationID = UUID()
        let firstURL = "https://example.com/first"
        let secondURL = "https://example.com/second"
        let thirdURL = "https://example.com/third"
        let operation = ShareExtensionPendingOperation(
            operationID: operationID,
            createdAt: now,
            updatedAt: now,
            originalURLs: [firstURL, secondURL, thirdURL],
            memo: "共有メモ",
            degradationNotice: nil,
            selectedTagIDs: [8, 7, 8],
            tagSelectionLockedAt: now,
            pendingItems: [
                ShareExtensionPendingItem(
                    url: firstURL,
                    needsURLSave: false,
                    entryID: 101,
                    normalizedURL: firstURL,
                    pendingTagIDs: [8],
                    savedResult: .created
                ),
                ShareExtensionPendingItem(url: thirdURL),
            ],
            completedItems: [
                ShareExtensionCompletedItem(
                    url: secondURL,
                    result: .duplicateActive,
                    entryID: 102,
                    normalizedURL: secondURL
                ),
            ]
        )
        let firstStore = ShareExtensionPendingOperationStore(fileURL: fileURL)
        try firstStore.write(operation)

        let relaunchedStore = ShareExtensionPendingOperationStore(fileURL: fileURL)
        let restored = try XCTUnwrap(relaunchedStore.load(now: now.addingTimeInterval(1)))

        XCTAssertEqual(restored.operationID, operationID)
        XCTAssertEqual(restored.selectedTagIDs, [7, 8])
        XCTAssertTrue(restored.isTagSelectionLocked)
        XCTAssertEqual(restored.completedItems.map(\.url), [secondURL])
        XCTAssertEqual(restored.pendingItems.map(\.url), [firstURL, thirdURL])
        XCTAssertFalse(restored.pendingItems[0].needsURLSave)
        XCTAssertEqual(restored.pendingItems[0].pendingTagIDs, [8])

        try relaunchedStore.clear(operationID: UUID())
        XCTAssertNotNil(try relaunchedStore.load(now: now.addingTimeInterval(2)))
        try relaunchedStore.clear(operationID: operationID)
        XCTAssertNil(try relaunchedStore.load(now: now.addingTimeInterval(2)))
    }

    func testShareExtensionOperationFreezesTagsAndRetriesOnlyUnfinishedWork() {
        let now = Date(timeIntervalSince1970: 2_000_000)
        let firstURL = "https://example.com/tag-retry"
        let secondURL = "https://example.com/completed"
        let firstItem = ShareExtensionPendingItem(url: firstURL)
        let secondItem = ShareExtensionPendingItem(url: secondURL)
        var operation = ShareExtensionPendingOperation(
            createdAt: now,
            updatedAt: now,
            originalURLs: [firstURL, secondURL],
            memo: nil,
            degradationNotice: nil,
            pendingItems: [firstItem, secondItem]
        )

        operation.lockTagSelection([7], at: now.addingTimeInterval(1))
        operation.lockTagSelection([8], at: now.addingTimeInterval(2))
        operation.recordAttempt(
            for: firstItem,
            retryItem: ShareExtensionPendingItem(
                url: firstURL,
                needsURLSave: false,
                entryID: 101,
                normalizedURL: firstURL,
                pendingTagIDs: [7],
                savedResult: .created
            ),
            completed: [],
            at: now.addingTimeInterval(3)
        )
        operation.recordAttempt(
            for: secondItem,
            retryItem: nil,
            completed: [
                ShareExtensionCompletedItem(
                    url: secondURL,
                    result: .duplicateActive,
                    entryID: 102,
                    normalizedURL: secondURL
                ),
            ],
            at: now.addingTimeInterval(4)
        )

        XCTAssertTrue(operation.isTagSelectionLocked)
        XCTAssertEqual(operation.selectedTagIDs, [7])
        XCTAssertEqual(operation.pendingItems.map(\.url), [firstURL])
        XCTAssertFalse(operation.pendingItems[0].needsURLSave)
        XCTAssertEqual(operation.pendingItems[0].pendingTagIDs, [7])
        XCTAssertEqual(operation.completedItems.map(\.url), [secondURL])
        XCTAssertFalse(operation.pendingItems.map(\.url).contains(secondURL))
    }

    func testShareExtensionPendingOperationRejectsExpiredOrDifferentPayloadRecovery() throws {
        let now = Date(timeIntervalSince1970: 2_000_000)
        let operation = ShareExtensionPendingOperation(
            createdAt: now,
            updatedAt: now,
            originalURLs: ["https://example.com/first"],
            memo: "共有メモ",
            degradationNotice: .truncatedToFirstURL,
            selectedTagIDs: [7]
        )

        XCTAssertTrue(
            operation.matches(
                urls: ["https://example.com/first"],
                memo: "共有メモ",
                degradationNotice: .truncatedToFirstURL
            )
        )
        XCTAssertFalse(
            operation.matches(
                urls: ["https://example.com/other"],
                memo: "共有メモ",
                degradationNotice: .truncatedToFirstURL
            )
        )
        XCTAssertTrue(operation.isRecoverable(at: now.addingTimeInterval(ShareExtensionPendingOperation.recoveryWindow)))
        XCTAssertFalse(operation.isRecoverable(at: now.addingTimeInterval(ShareExtensionPendingOperation.recoveryWindow + 1)))
    }

    func testShareExtensionHostHandoffDoesNotCancelSaveOnViewDisappearance() {
        var lifecycle = ShareExtensionLifecycle()
        XCTAssertTrue(lifecycle.shouldCancelTasksWhenViewDisappears)

        lifecycle.beginHostHandoff()
        XCTAssertEqual(lifecycle.phase, .handingOffToHost)
        XCTAssertFalse(lifecycle.shouldCancelTasksWhenViewDisappears)

        lifecycle.beginCompletion()
        XCTAssertEqual(lifecycle.phase, .completing)
        XCTAssertFalse(lifecycle.shouldCancelTasksWhenViewDisappears)

        var failedHandoff = ShareExtensionLifecycle()
        failedHandoff.beginHostHandoff()
        failedHandoff.hostHandoffFailed()
        XCTAssertEqual(failedHandoff.phase, .active)
        XCTAssertTrue(failedHandoff.shouldCancelTasksWhenViewDisappears)
    }

    /// App Group 不通時は urlsaver://save?url=... 平文引き継ぎを行わず fail-closed する。
    /// ShareHostHandoffOutcome は URL を運ばないため、この経路から route URL は構築不能。
    func testShareHandoffWithoutAppGroupFailsClosedWithRecoveryMessage() {
        guard case .failClosed(let message) = ShareHostHandoffPolicy.outcome(hasAppGroupAccess: false) else {
            XCTFail("App Group 不通時は fail-closed であること")
            return
        }
        XCTAssertTrue(message.contains("保存できませんでした"))
        XCTAssertTrue(message.contains("「＋」ボタン"))
        XCTAssertTrue(message.contains("貼り付けて保存"))
        XCTAssertFalse(message.lowercased().contains("urlsaver://"))
    }

    func testShareHandoffWithAppGroupProceedsWithRepositoryAccess() {
        XCTAssertEqual(
            ShareHostHandoffPolicy.outcome(hasAppGroupAccess: true),
            .proceedWithRepositoryAccess
        )
    }

    func testCancelledPendingDeleteTimerNeverAllowsFinalize() async {
        let waiter = Task {
            await PendingDeleteTimerWaiter.wait(seconds: 60)
        }
        waiter.cancel()

        let shouldFinalize = await waiter.value

        XCTAssertFalse(shouldFinalize)
    }

    func testFirstRunOnboardingMigrationShowsOnlyForFreshInstall() {
        let fresh = FirstRunOnboardingStore.resolve(
            migrationAlreadyCompleted: false,
            legacySeen: false,
            hadExistingDatabaseBeforeStartup: false
        )
        let existing = FirstRunOnboardingStore.resolve(
            migrationAlreadyCompleted: false,
            legacySeen: false,
            hadExistingDatabaseBeforeStartup: true
        )
        let completedFresh = FirstRunOnboardingStore.resolve(
            migrationAlreadyCompleted: true,
            legacySeen: true,
            hadExistingDatabaseBeforeStartup: false
        )

        XCTAssertFalse(fresh.hasSeenOnboarding)
        XCTAssertTrue(existing.hasSeenOnboarding)
        XCTAssertTrue(completedFresh.hasSeenOnboarding)
    }

    func testFirstRunOnboardingStorePersistsFreshAndExistingInstallBehavior() throws {
        let freshSuite = "FirstRunFresh-\(UUID().uuidString)"
        let existingSuite = "FirstRunExisting-\(UUID().uuidString)"
        let freshDefaults = try XCTUnwrap(UserDefaults(suiteName: freshSuite))
        let existingDefaults = try XCTUnwrap(UserDefaults(suiteName: existingSuite))
        defer {
            freshDefaults.removePersistentDomain(forName: freshSuite)
            existingDefaults.removePersistentDomain(forName: existingSuite)
        }

        FirstRunOnboardingStore.initialize(
            defaults: freshDefaults,
            hadExistingDatabaseBeforeStartup: false
        )
        XCTAssertTrue(FirstRunOnboardingStore.shouldShow(defaults: freshDefaults))
        FirstRunOnboardingStore.markSeen(defaults: freshDefaults)
        XCTAssertFalse(FirstRunOnboardingStore.shouldShow(defaults: freshDefaults))

        FirstRunOnboardingStore.initialize(
            defaults: existingDefaults,
            hadExistingDatabaseBeforeStartup: true
        )
        XCTAssertFalse(FirstRunOnboardingStore.shouldShow(defaults: existingDefaults))
    }
    func testShareHandoffQueuePreservesConsecutiveSharesAndOnlyAcknowledgesSnapshot() async throws {
        let directory = try makeHandoffTestDirectory()
        defer { try? FileManager.default.removeItem(at: directory) }
        let file = directory.appendingPathComponent("legacy.json")
        let store = ShareHandoffStore(fileURL: file)
        let first = makeHandoffReport(entryID: 11)
        let second = makeHandoffReport(entryID: 22)
        try await store.write(first)
        let firstSnapshots = try await store.pendingReports()
        let firstSnapshot = try XCTUnwrap(firstSnapshots.first)
        try await store.write(second)
        let acknowledged = try await store.acknowledge(firstSnapshot)
        XCTAssertTrue(acknowledged)
        let remaining = try await ShareHandoffStore(fileURL: file).pendingReports()
        XCTAssertEqual(remaining.map(\.report.entryID), [22])
        let acknowledgedAgain = try await store.acknowledge(firstSnapshot)
        XCTAssertFalse(acknowledgedAgain)
        XCTAssertEqual(remaining.first?.report.operationID, second.operationID)
    }

    func testShareHandoffPartialFailureSurvivesRecreationAndOldAckCannotDeleteProgress() async throws {
        let directory = try makeHandoffTestDirectory()
        defer { try? FileManager.default.removeItem(at: directory) }
        let file = directory.appendingPathComponent("legacy.json")
        let store = ShareHandoffStore(fileURL: file)
        let report = makeHandoffReport(entryID: 11, tags: ["tag-a", "tag-b"])
        try await store.write(report)
        let snapshots = try await store.pendingReports()
        let pending = try XCTUnwrap(snapshots.first)
        let failed = [ShareHandoffAssignment(entryID: 11, remoteTagID: "tag-b")]
        let retained = try await store.retain(pending, assignments: failed)
        XCTAssertTrue(retained)
        let staleAck = try await store.acknowledge(pending)
        XCTAssertFalse(staleAck)
        let recreated = ShareHandoffStore(fileURL: file)
        let restored = try await recreated.pendingReports()
        XCTAssertEqual(restored.first?.report.sharedTagAssignments, failed)
        XCTAssertEqual(restored.first?.report.entryID, 11)
        XCTAssertEqual(restored.first?.report.operationID, report.operationID)
        let finalSnapshot = try XCTUnwrap(restored.first)
        let acknowledged = try await recreated.acknowledge(finalSnapshot)
        XCTAssertTrue(acknowledged)
        let empty = try await recreated.pendingReports()
        XCTAssertTrue(empty.isEmpty)
    }

    func testShareHandoffLegacyDecodeIsNonDestructiveAndChangedLegacyCannotBeAcknowledged() async throws {
        let directory = try makeHandoffTestDirectory()
        defer { try? FileManager.default.removeItem(at: directory) }
        let file = directory.appendingPathComponent("legacy.json")
        var report = makeHandoffReport(entryID: 11)
        report.operationID = nil
        report.authUserID = nil
        let encoder = JSONEncoder()
        encoder.dateEncodingStrategy = .iso8601
        try encoder.encode(report).write(to: file)
        let store = ShareHandoffStore(fileURL: file)
        let snapshots = try await store.pendingReports()
        let legacy = try XCTUnwrap(snapshots.first)
        XCTAssertNil(legacy.report.operationID)
        XCTAssertTrue(FileManager.default.fileExists(atPath: file.path))
        // A producer replaced the legacy singleton after the consumer read it.
        let replacement = makeHandoffReport(entryID: 22)
        try encoder.encode(replacement).write(to: file, options: .atomic)
        let acknowledged = try await store.acknowledge(legacy)
        XCTAssertFalse(acknowledged)
        let remaining = try await store.pendingReports()
        XCTAssertEqual(remaining.first?.report.entryID, 22)
    }

    func testShareHandoffConflictingOperationNeverOverwritesUnfinishedSelection() async throws {
        let directory = try makeHandoffTestDirectory()
        defer { try? FileManager.default.removeItem(at: directory) }
        let store = ShareHandoffStore(fileURL: directory.appendingPathComponent("legacy.json"))
        let original = makeHandoffReport(entryID: 11, tags: ["tag-a"])
        try await store.write(original)
        try await store.write(original)
        var conflicting = makeHandoffReport(entryID: 22, tags: ["tag-b"])
        conflicting.operationID = original.operationID
        do {
            try await store.write(conflicting)
            XCTFail("Conflicting operation must fail without replacing the original")
        } catch ShareHandoffStoreError.operationConflict {
            // Expected: extension keeps its pending operation for recovery.
        }
        let pending = try await store.pendingReports()
        XCTAssertEqual(pending.count, 1)
        XCTAssertEqual(pending.first?.report, original)
    }

    func testShareHandoffAccountBindingSurvivesRecreationAndLegacySharedSelectionFailsClosed() throws {
        var operation = ShareExtensionPendingOperation(
            originalURLs: ["https://example.com/shared"], memo: nil, degradationNotice: nil
        )
        operation.selectedSharedTagAuthUserID = "owner-a"
        operation.lockTagSelection([], sharedTagRemoteIDs: ["tag-a"])
        let restored = try JSONDecoder().decode(ShareExtensionPendingOperation.self, from: JSONEncoder().encode(operation))
        XCTAssertEqual(restored.selectedSharedTagAuthUserID, "owner-a")
        var report = makeHandoffReport(entryID: 11, tags: ["tag-a"])
        XCTAssertTrue(report.canAssignSharedTags(authUserID: "owner-a"))
        XCTAssertFalse(report.canAssignSharedTags(authUserID: "owner-b"))
        XCTAssertFalse(report.canAssignSharedTags(authUserID: nil))
        report.authUserID = nil
        XCTAssertFalse(report.canAssignSharedTags(authUserID: "owner-a"))
        XCTAssertTrue(makeHandoffReport(entryID: 22).canAssignSharedTags(authUserID: nil))
    }

    func testUnreadableShareHandoffRemainsIntactForRecovery() async throws {
        let directory = try makeHandoffTestDirectory()
        defer { try? FileManager.default.removeItem(at: directory) }
        let file = directory.appendingPathComponent("legacy.json")
        let bytes = Data("invalid report".utf8)
        try bytes.write(to: file)
        do {
            _ = try await ShareHandoffStore(fileURL: file).pendingReports()
            XCTFail("Unreadable report must be surfaced rather than consumed")
        } catch {
            XCTAssertEqual(try Data(contentsOf: file), bytes)
        }
    }

    private func makeHandoffTestDirectory() throws -> URL {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent("RinbamHandoffTests-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        return directory
    }

    private func makeHandoffReport(entryID: Int64, tags: [String] = []) -> ShareHandoffReport {
        ShareHandoffReport(
            operationID: UUID(), authUserID: "owner-a", result: .created,
            entryID: entryID, normalizedURL: "https://example.com/\(entryID)",
            sharedTagRemoteIDs: tags, degradationNotice: nil, batchSummary: nil,
            createdAt: Date(timeIntervalSince1970: 1_800_000_000)
        )
    }

}

private struct URLNormalizationVector: Decodable {
    let input: String
    let expectedNormalizedURL: String?

    enum CodingKeys: String, CodingKey {
        case input
        case expectedNormalizedURL = "expectedNormalizedUrl"
    }
}
