import XCTest

final class URLRulesTests: XCTestCase {
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
}

private struct URLNormalizationVector: Decodable {
    let input: String
    let expectedNormalizedURL: String?

    enum CodingKeys: String, CodingKey {
        case input
        case expectedNormalizedURL = "expectedNormalizedUrl"
    }
}
