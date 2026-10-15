import Foundation
import XCTest

final class SharedTagStoreTests: XCTestCase {
    private var databaseURL: URL!
    private var repository: URLRepository!
    private var store: SharedTagStore!

    override func setUpWithError() throws {
        let directory = FileManager.default.temporaryDirectory
            .appendingPathComponent(UUID().uuidString, isDirectory: true)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        databaseURL = directory.appendingPathComponent("test.sqlite")
        repository = try URLRepository(databaseURL: databaseURL)
        store = try SharedTagStore(database: repository.database)
    }

    override func tearDownWithError() throws {
        store = nil
        repository = nil
        if let databaseURL {
            try? FileManager.default.removeItem(at: databaseURL.deletingLastPathComponent())
        }
    }

    func testApplySnapshotCreatesHiddenSharedOnlyRowsAndVisibleTagSummary() throws {
        try store.applySnapshot(
            authUserID: "user-1",
            snapshot: PullSharedTagSnapshotResponse(
                pulledAt: "2026-04-23T10:00:00Z",
                normalizationVersion: 1,
                tags: [
                    RemoteSharedTag(
                        id: "tag-1",
                        name: "設計メモ",
                        createdAt: "2026-04-23T09:00:00Z",
                        updatedAt: "2026-04-23T09:30:00Z",
                        deletedAt: nil
                    )
                ],
                members: [
                    RemoteSharedTagMember(
                        tagID: "tag-1",
                        userID: "user-1",
                        displayName: nil,
                        role: "owner",
                        status: "active",
                        createdAt: "2026-04-23T09:00:00Z",
                        updatedAt: "2026-04-23T09:30:00Z"
                    )
                ],
                urls: [
                    RemoteSharedTagURL(
                        id: "url-1",
                        tagID: "tag-1",
                        rawURL: "https://example.com/shared-article",
                        normalizedURL: "https://example.com/shared-article",
                        addedBy: "collaborator-1",
                        deletedAt: nil
                    )
                ]
            )
        )

        XCTAssertTrue(try repository.observeActiveSnapshot().isEmpty)

        let entry = try XCTUnwrap(repository.loadEntry(id: 1))
        XCTAssertEqual(entry.normalizedURL, "https://example.com/shared-article")
        XCTAssertEqual(entry.localProvenanceCount, 0)
        XCTAssertEqual(entry.sharedReferenceCount, 1)

        let tags = try store.loadVisibleTags(authUserID: "user-1")
        XCTAssertEqual(tags.count, 1)
        XCTAssertEqual(tags.first?.name, "設計メモ")
        XCTAssertEqual(tags.first?.currentUserRole, .owner)
        XCTAssertEqual(tags.first?.activeURLCount, 1)

        XCTAssertEqual(
            try store.loadSharedTagURLNotificationEvents(authUserID: "user-1"),
            [
                SharedTagURLNotificationEvent(
                    remoteTagID: "tag-1",
                    tagName: "設計メモ",
                    remoteURLID: "url-1",
                    addedBy: "collaborator-1"
                )
            ]
        )
    }

    func testSharedTagNotificationCandidatesExcludeSelfReplayAndInitialSync() {
        let knownTag = SharedTagSummary(
            remoteTagID: "tag-known",
            name: "共有",
            currentUserRole: .editor,
            activeURLCount: 1,
            lastSyncedAt: nil
        )
        let replay = SharedTagURLNotificationEvent(
            remoteTagID: "tag-known",
            tagName: "共有",
            remoteURLID: "url-replay",
            addedBy: "other-user"
        )
        let own = SharedTagURLNotificationEvent(
            remoteTagID: "tag-known",
            tagName: "共有",
            remoteURLID: "url-own",
            addedBy: "current-user"
        )
        let external = SharedTagURLNotificationEvent(
            remoteTagID: "tag-known",
            tagName: "共有",
            remoteURLID: "url-external",
            addedBy: "other-user"
        )
        let initialSync = SharedTagURLNotificationEvent(
            remoteTagID: "tag-new",
            tagName: "新しい共有",
            remoteURLID: "url-initial",
            addedBy: "other-user"
        )
        let unknownActor = SharedTagURLNotificationEvent(
            remoteTagID: "tag-known",
            tagName: "共有",
            remoteURLID: "url-unknown-actor",
            addedBy: nil
        )

        XCTAssertEqual(
            sharedTagNotificationCandidates(
                beforeTags: [knownTag],
                beforeEvents: [replay],
                afterEvents: [replay, own, external, initialSync, unknownActor],
                authUserID: "current-user"
            ),
            [external]
        )
    }

    func testClearLocalSharedStateDropsHiddenCacheRows() throws {
        try store.applySnapshot(
            authUserID: "user-1",
            snapshot: PullSharedTagSnapshotResponse(
                pulledAt: "2026-04-23T10:00:00Z",
                normalizationVersion: 1,
                tags: [
                    RemoteSharedTag(
                        id: "tag-1",
                        name: "設計メモ",
                        createdAt: "2026-04-23T09:00:00Z",
                        updatedAt: "2026-04-23T09:30:00Z",
                        deletedAt: nil
                    )
                ],
                members: [
                    RemoteSharedTagMember(
                        tagID: "tag-1",
                        userID: "user-1",
                        displayName: nil,
                        role: "owner",
                        status: "active",
                        createdAt: "2026-04-23T09:00:00Z",
                        updatedAt: "2026-04-23T09:30:00Z"
                    )
                ],
                urls: [
                    RemoteSharedTagURL(
                        id: "url-1",
                        tagID: "tag-1",
                        rawURL: "https://example.com/shared-article",
                        normalizedURL: "https://example.com/shared-article",
                        deletedAt: nil
                    )
                ]
            )
        )

        try store.clearLocalSharedState()

        XCTAssertTrue(try store.loadVisibleTags(authUserID: "user-1").isEmpty)
        XCTAssertNil(try repository.loadEntry(id: 1))
    }

    func testClearLocalSharedStateForAccountPreservesLocalRecordsAndOtherAccountCache() throws {
        let localResult = try repository.saveFromManualInput("https://example.com/local-kept")
        let localEntryID = try XCTUnwrap(localResult.entryID)
        _ = try repository.saveMemo(entryID: localEntryID, rawMemo: "keep this memo")
        XCTAssertTrue(try repository.archive(entryID: localEntryID))
        try store.applySnapshot(
            authUserID: "user-a",
            snapshot: PullSharedTagSnapshotResponse(
                pulledAt: "2026-08-21T01:00:00Z",
                normalizationVersion: 1,
                tags: [
                    RemoteSharedTag(
                        id: "tag-a",
                        name: "user-a-tag",
                        createdAt: "2026-08-21T00:00:00Z",
                        updatedAt: "2026-08-21T00:30:00Z",
                        deletedAt: nil
                    )
                ],
                members: [],
                urls: [
                    RemoteSharedTagURL(
                        id: "url-a-only",
                        tagID: "tag-a",
                        rawURL: "https://example.com/user-a-only",
                        normalizedURL: "https://example.com/user-a-only",
                        deletedAt: nil
                    ),
                    RemoteSharedTagURL(
                        id: "url-a-local",
                        tagID: "tag-a",
                        rawURL: "https://example.com/local-kept",
                        normalizedURL: "https://example.com/local-kept",
                        deletedAt: nil
                    ),
                ]
            )
        )
        try store.applySnapshot(
            authUserID: "user-b",
            snapshot: PullSharedTagSnapshotResponse(
                pulledAt: "2026-08-21T02:00:00Z",
                normalizationVersion: 1,
                tags: [
                    RemoteSharedTag(
                        id: "tag-b",
                        name: "user-b-tag",
                        createdAt: "2026-08-21T00:00:00Z",
                        updatedAt: "2026-08-21T00:30:00Z",
                        deletedAt: nil
                    )
                ],
                members: [],
                urls: [
                    RemoteSharedTagURL(
                        id: "url-b-only",
                        tagID: "tag-b",
                        rawURL: "https://example.com/user-b-only",
                        normalizedURL: "https://example.com/user-b-only",
                        deletedAt: nil
                    ),
                    RemoteSharedTagURL(
                        id: "url-b-local",
                        tagID: "tag-b",
                        rawURL: "https://example.com/local-kept",
                        normalizedURL: "https://example.com/local-kept",
                        deletedAt: nil
                    ),
                ]
            )
        )

        try store.clearLocalSharedState(authUserID: "user-a")

        XCTAssertTrue(try store.loadVisibleTags(authUserID: "user-a").isEmpty)
        XCTAssertEqual(try store.loadVisibleTags(authUserID: "user-b").map(\.name), ["user-b-tag"])
        let records = try repository.loadChatGptPersonalLinkSnapshot()
        XCTAssertFalse(records.contains { $0.normalizedURL == "https://example.com/user-a-only" })
        let userBOnly = try XCTUnwrap(records.first { $0.normalizedURL == "https://example.com/user-b-only" })
        XCTAssertEqual(userBOnly.localProvenanceCount, 0)
        XCTAssertEqual(userBOnly.sharedReferenceCount, 1)
        let localKept = try XCTUnwrap(repository.loadEntry(id: localEntryID))
        XCTAssertEqual(localKept.localProvenanceCount, 1)
        XCTAssertEqual(localKept.sharedReferenceCount, 1)
        XCTAssertEqual(localKept.memo, "keep this memo")
        XCTAssertEqual(localKept.recordState, .archived)
    }

    func testNormalSignOutClearsSessionWithoutDeletingSharedCache() async throws {
        try store.applySnapshot(
            authUserID: "sign-out-user",
            snapshot: makeSnapshot(
                pulledAt: "2026-08-21T03:00:00Z",
                tagID: "sign-out-tag",
                tagName: "sign-out-cache",
                urlID: "sign-out-url",
                normalizedURL: "https://example.com/sign-out-cache"
            )
        )
        let sessionStore = SharedTagAuthSessionStore(storage: SignOutTestAuthStorage())
        try sessionStore.save(
            SharedTagAuthSession(
                authUserID: "sign-out-user",
                accessToken: "access-token",
                refreshToken: nil,
                userEmail: nil
            )
        )
        let service = SharedTagCloudService(
            config: SharedTagCloudConfig(enabled: false, supabaseURL: "", anonKey: ""),
            sessionStore: sessionStore,
            store: store,
            repository: repository
        )

        try await service.signOut()

        XCTAssertNil(try sessionStore.load())
        XCTAssertEqual(try store.loadVisibleTags(authUserID: "sign-out-user").map(\.name), ["sign-out-cache"])
        XCTAssertNotNil(
            try repository.loadChatGptPersonalLinkSnapshot().first {
                $0.normalizedURL == "https://example.com/sign-out-cache"
            }
        )
    }

    func testGoogleIdentityLinkUsesAuthenticatedPKCEAndKeepsTheSameAccount() async throws {
        let (service, sessionStore, oauthStateStore) = try makeIdentityLinkService()
        let linkedSessionResponse = try authSessionResponse(
            userID: "link-owner-a",
            accessToken: "linked-access-token",
            refreshToken: "linked-refresh-token"
        )
        AuthLinkMockURLProtocol.install { request in
            request.url?.path == "/auth/v1/user/identities/authorize"
                ? .json(#"{"url":"https://accounts.google.com/o/oauth2/v2/auth"}"#)
                : .data(linkedSessionResponse)
        }
        defer { AuthLinkMockURLProtocol.reset() }

        let authorizationResult = await service.googleIdentityLinkURL()
        let authorizationURL: URL
        if case .success(let url) = authorizationResult {
            authorizationURL = url
        } else {
            return XCTFail("Google identity linking did not produce an authorization URL")
        }
        XCTAssertEqual(authorizationURL.host, "accounts.google.com")
        let authorizationRequest = try XCTUnwrap(AuthLinkMockURLProtocol.takeRequests().first)
        XCTAssertEqual(authorizationRequest.httpMethod, "GET")
        XCTAssertEqual(authorizationRequest.value(forHTTPHeaderField: "Authorization"), "Bearer old-access-token")
        XCTAssertEqual(authorizationRequest.value(forHTTPHeaderField: "apikey"), "test-publishable-key")

        let authorizationRequestURL = try XCTUnwrap(authorizationRequest.url)
        let query = URLComponents(url: authorizationRequestURL, resolvingAgainstBaseURL: false)?.queryItems ?? []
        XCTAssertEqual(query.first(where: { $0.name == "provider" })?.value, "google")
        XCTAssertEqual(query.first(where: { $0.name == "scopes" })?.value, "openid email")
        XCTAssertEqual(query.first(where: { $0.name == "redirect_to" })?.value, "urlsaver://auth/callback")
        XCTAssertEqual(query.first(where: { $0.name == "code_challenge_method" })?.value, "S256")
        XCTAssertEqual(query.first(where: { $0.name == "skip_http_redirect" })?.value, "true")
        XCTAssertFalse(query.first(where: { $0.name == "code_challenge" })?.value?.isEmpty ?? true)

        let pending = try XCTUnwrap(oauthStateStore.loadPending())
        XCTAssertEqual(pending.flow, .some(.identityLink))
        XCTAssertEqual(pending.expectedAuthUserID, "link-owner-a")

        let callbackURL = try XCTUnwrap(URL(string: "urlsaver://auth/callback?code=link-code&provider=google"))
        let result = await service.handleOAuthCallback(url: callbackURL)
        XCTAssertEqual(result, .identityLink(.success(provider: "google")))

        let exchangeRequest = try XCTUnwrap(AuthLinkMockURLProtocol.takeRequests().first)
        XCTAssertEqual(exchangeRequest.httpMethod, "POST")
        XCTAssertEqual(exchangeRequest.value(forHTTPHeaderField: "Authorization"), nil)
        let exchangeBody = try XCTUnwrap(
            JSONSerialization.jsonObject(with: XCTUnwrap(exchangeRequest.httpBody)) as? [String: String]
        )
        XCTAssertEqual(exchangeBody["auth_code"], "link-code")
        XCTAssertEqual(exchangeBody["code_verifier"], pending.codeVerifier)

        let savedSession = try XCTUnwrap(sessionStore.load())
        XCTAssertEqual(savedSession.authUserID, "link-owner-a")
        XCTAssertEqual(savedSession.accessToken, "linked-access-token")
        XCTAssertEqual(savedSession.refreshToken, "linked-refresh-token")
    }

    func testGoogleIdentityLinkCancellationAndMismatchedCallbackPreserveSessionAndCache() async throws {
        let (service, sessionStore, _) = try makeIdentityLinkService()
        try seedSharedTagCache(authUserID: "link-owner-a", tagID: "cached-tag-a", name: "keep-a")
        let originalSession = try XCTUnwrap(sessionStore.load())
        let linkedSessionResponse = try authSessionResponse(
            userID: "different-owner",
            accessToken: "wrong-access-token",
            refreshToken: "wrong-refresh-token"
        )
        AuthLinkMockURLProtocol.install { request in
            request.url?.path == "/auth/v1/user/identities/authorize"
                ? .json(#"{"url":"https://accounts.google.com/o/oauth2/v2/auth"}"#)
                : .data(linkedSessionResponse)
        }
        defer { AuthLinkMockURLProtocol.reset() }

        _ = await service.googleIdentityLinkURL()
        let cancelURL = try XCTUnwrap(URL(string: "urlsaver://auth/callback?error=access_denied&provider=google"))
        guard case .identityLink(.failure) = await service.handleOAuthCallback(url: cancelURL) else {
            return XCTFail("OAuth cancellation should be reported as an identity-link failure")
        }
        XCTAssertEqual(try sessionStore.load(), originalSession)
        XCTAssertEqual(try store.loadVisibleTags(authUserID: "link-owner-a").map(\.name), ["keep-a"])
        XCTAssertEqual(AuthLinkMockURLProtocol.takeRequests().count, 1)

        _ = await service.googleIdentityLinkURL()
        let mismatchURL = try XCTUnwrap(URL(string: "urlsaver://auth/callback?code=wrong-user-code&provider=google"))
        guard case .identityLink(.failure) = await service.handleOAuthCallback(url: mismatchURL) else {
            return XCTFail("OAuth linking must reject a session for a different user")
        }
        XCTAssertEqual(try sessionStore.load(), originalSession)
        XCTAssertEqual(try store.loadVisibleTags(authUserID: "link-owner-a").map(\.name), ["keep-a"])
    }

    func testGoogleIdentityLinkCallbackDoesNotSwitchAnAccountChangedDuringOAuth() async throws {
        let (service, sessionStore, _) = try makeIdentityLinkService()
        let expectedSessionResponse = try authSessionResponse(
            userID: "link-owner-a",
            accessToken: "link-owner-a-linked-access",
            refreshToken: "link-owner-a-linked-refresh"
        )
        AuthLinkMockURLProtocol.install { request in
            request.url?.path == "/auth/v1/user/identities/authorize"
                ? .json(#"{"url":"https://accounts.google.com/o/oauth2/v2/auth"}"#)
                : .data(expectedSessionResponse)
        }
        defer { AuthLinkMockURLProtocol.reset() }

        _ = await service.googleIdentityLinkURL()
        let otherSession = SharedTagAuthSession(
            authUserID: "link-owner-b",
            accessToken: "owner-b-access",
            refreshToken: "owner-b-refresh",
            userEmail: "owner-b@example.com"
        )
        try sessionStore.save(otherSession)

        let callbackURL = try XCTUnwrap(URL(string: "urlsaver://auth/callback?code=link-owner-a-code&provider=google"))
        guard case .identityLink(.failure) = await service.handleOAuthCallback(url: callbackURL) else {
            return XCTFail("OAuth linking must not switch back after the active account changes")
        }
        XCTAssertEqual(try sessionStore.load(), otherSession)
    }

    func testExplicitlyCancelingGoogleIdentityLinkPreservesSessionAndSharedCache() async throws {
        let (service, sessionStore, oauthStateStore) = try makeIdentityLinkService()
        try seedSharedTagCache(authUserID: "link-owner-a", tagID: "cached-tag-a", name: "keep-a")
        let originalSession = try XCTUnwrap(sessionStore.load())
        AuthLinkMockURLProtocol.install { _ in
            .json(#"{"url":"https://accounts.google.com/o/oauth2/v2/auth"}"#)
        }
        defer { AuthLinkMockURLProtocol.reset() }

        let authorizationResult = await service.googleIdentityLinkURL()
        guard case .success = authorizationResult else {
            return XCTFail("Google identity linking did not produce an authorization URL")
        }
        let pending = try XCTUnwrap(oauthStateStore.loadPending())
        XCTAssertEqual(pending.expectedAuthUserID, "link-owner-a")
        XCTAssertNil(service.googleOAuthURL(), "A sign-in flow must not overwrite a pending identity link")
        XCTAssertEqual(try oauthStateStore.loadPending(), pending)
        let cancelled = await service.cancelPendingIdentityLink()

        XCTAssertTrue(cancelled)
        XCTAssertNil(try oauthStateStore.loadPending())
        XCTAssertEqual(try sessionStore.load(), originalSession)
        XCTAssertEqual(try store.loadVisibleTags(authUserID: "link-owner-a").map(\.name), ["keep-a"])
    }

    func testAppleIdentityLinkSavesRotatedSessionForTheSameUser() async throws {
        let (service, sessionStore, _) = try makeIdentityLinkService()
        let linkedSessionResponse = try authSessionResponse(
            userID: "link-owner-a",
            accessToken: "apple-linked-access",
            refreshToken: "apple-linked-refresh"
        )
        AuthLinkMockURLProtocol.install { _ in .data(linkedSessionResponse) }
        defer { AuthLinkMockURLProtocol.reset() }

        let result = await service.linkAppleIdentity(idToken: "test-apple-id-token", nonce: "test-raw-nonce")

        XCTAssertEqual(result, .success(provider: "apple"))
        let savedSession = try XCTUnwrap(sessionStore.load())
        XCTAssertEqual(savedSession.authUserID, "link-owner-a")
        XCTAssertEqual(savedSession.accessToken, "apple-linked-access")
        XCTAssertEqual(savedSession.refreshToken, "apple-linked-refresh")
    }

    func testAppleIdentityLinkUsesCurrentBearerAndDoesNotReplaceAnotherUserSession() async throws {
        let (service, sessionStore, _) = try makeIdentityLinkService()
        try seedSharedTagCache(authUserID: "link-owner-a", tagID: "cached-tag-a", name: "keep-a")
        let originalSession = try XCTUnwrap(sessionStore.load())
        let mismatchedResponse = try authSessionResponse(
            userID: "different-owner",
            accessToken: "wrong-access-token",
            refreshToken: "wrong-refresh-token"
        )
        AuthLinkMockURLProtocol.install { _ in .data(mismatchedResponse) }
        defer { AuthLinkMockURLProtocol.reset() }

        let result = await service.linkAppleIdentity(idToken: "test-apple-id-token", nonce: "test-raw-nonce")
        guard case .failure = result else {
            return XCTFail("Apple linking must reject a response for a different user")
        }

        let request = try XCTUnwrap(AuthLinkMockURLProtocol.takeRequests().first)
        XCTAssertEqual(request.httpMethod, "POST")
        XCTAssertEqual(request.value(forHTTPHeaderField: "Authorization"), "Bearer old-access-token")
        XCTAssertEqual(request.url?.path, "/auth/v1/token")
        XCTAssertEqual(URLComponents(url: try XCTUnwrap(request.url), resolvingAgainstBaseURL: false)?.queryItems?.first(where: { $0.name == "grant_type" })?.value, "id_token")
        let body = try XCTUnwrap(JSONSerialization.jsonObject(with: XCTUnwrap(request.httpBody)) as? [String: Any])
        XCTAssertEqual(body["provider"] as? String, "apple")
        XCTAssertEqual(body["id_token"] as? String, "test-apple-id-token")
        XCTAssertEqual(body["nonce"] as? String, "test-raw-nonce")
        XCTAssertEqual(body["link_identity"] as? Bool, true)
        XCTAssertEqual(try sessionStore.load(), originalSession)
        XCTAssertEqual(try store.loadVisibleTags(authUserID: "link-owner-a").map(\.name), ["keep-a"])
    }

    func testAppleIdentityLinkRejectsOwnerChangedWhileNativeAuthorizationIsOpen() async throws {
        let (service, sessionStore, _) = try makeIdentityLinkService()
        try seedSharedTagCache(authUserID: "link-owner-a", tagID: "cached-tag-a", name: "keep-a")
        try seedSharedTagCache(authUserID: "link-owner-b", tagID: "cached-tag-b", name: "keep-b")
        let switchedSession = SharedTagAuthSession(
            authUserID: "link-owner-b", accessToken: "owner-b-access",
            refreshToken: "owner-b-refresh", userEmail: "owner-b@example.com"
        )
        try sessionStore.save(switchedSession)
        AuthLinkMockURLProtocol.install { _ in
            .json(#"{"message":"Unexpected network call"}"#, statusCode: 500)
        }
        defer { AuthLinkMockURLProtocol.reset() }

        let result = await service.linkAppleIdentity(
            idToken: "test-apple-id-token", nonce: "test-raw-nonce", expectedAuthUserID: "link-owner-a"
        )
        guard case .failure(let message) = result else {
            return XCTFail("Apple linking must retain the account selected when authorization began")
        }
        XCTAssertTrue(message.contains("開始後にアカウントが変更"))
        XCTAssertTrue(AuthLinkMockURLProtocol.takeRequests().isEmpty)
        XCTAssertEqual(try sessionStore.load(), switchedSession)
        XCTAssertEqual(try store.loadVisibleTags(authUserID: "link-owner-a").map(\.name), ["keep-a"])
        XCTAssertEqual(try store.loadVisibleTags(authUserID: "link-owner-b").map(\.name), ["keep-b"])
    }

    func testHandoffAssignmentRejectsAccountSwitchInsideOperationGate() async throws {
        let firstStorage = SignOutTestAuthStorage()
        let secondStorage = SignOutTestAuthStorage()
        try SharedTagAuthSessionStore(storage: firstStorage).save(
            SharedTagAuthSession(authUserID: "handoff-owner-a", accessToken: "test-a", refreshToken: nil, userEmail: nil)
        )
        try SharedTagAuthSessionStore(storage: secondStorage).save(
            SharedTagAuthSession(authUserID: "handoff-owner-b", accessToken: "test-b", refreshToken: nil, userEmail: nil)
        )
        let switchingStorage = HandoffAccountSwitchAuthStorage(
            first: try XCTUnwrap(firstStorage.load()),
            subsequent: try XCTUnwrap(secondStorage.load())
        )
        let sessionStore = SharedTagAuthSessionStore(storage: switchingStorage)
        let service = SharedTagCloudService(
            config: SharedTagCloudConfig(enabled: false, supabaseURL: "", anonKey: ""),
            sessionStore: sessionStore, store: store, repository: repository,
            accountOperationGate: SharedTagAccountOperationGate()
        )
        let switched = await service.assignEntry(remoteTagID: "missing-tag", entryID: 1, expectedAuthUserID: "handoff-owner-a")
        XCTAssertEqual(switched, .authRequired)
        XCTAssertGreaterThanOrEqual(switchingStorage.loadCount, 2)
        let sameOwner = await service.assignEntry(remoteTagID: "missing-tag", entryID: 1, expectedAuthUserID: "handoff-owner-b")
        XCTAssertEqual(sameOwner, .failure("共有タグが見つかりませんでした"))
        let existingCaller = await service.assignEntry(remoteTagID: "missing-tag", entryID: 1)
        XCTAssertEqual(existingCaller, .failure("共有タグが見つかりませんでした"))
    }

    func testLoadVisibleTagsForEntryAndEntryIDsForTag() throws {
        let local = try repository.saveFromResolvedURL("https://example.com/shared-article")
        XCTAssertEqual(local.entryID, 1)

        try store.applySnapshot(
            authUserID: "user-1",
            snapshot: PullSharedTagSnapshotResponse(
                pulledAt: "2026-04-23T10:00:00Z",
                normalizationVersion: 1,
                tags: [
                    RemoteSharedTag(
                        id: "tag-1",
                        name: "設計メモ",
                        createdAt: "2026-04-23T09:00:00Z",
                        updatedAt: "2026-04-23T09:30:00Z",
                        deletedAt: nil
                    )
                ],
                members: [
                    RemoteSharedTagMember(
                        tagID: "tag-1",
                        userID: "user-1",
                        displayName: nil,
                        role: "editor",
                        status: "active",
                        createdAt: "2026-04-23T09:00:00Z",
                        updatedAt: "2026-04-23T09:30:00Z"
                    )
                ],
                urls: [
                    RemoteSharedTagURL(
                        id: "url-1",
                        tagID: "tag-1",
                        rawURL: "https://example.com/shared-article",
                        normalizedURL: "https://example.com/shared-article",
                        deletedAt: nil
                    )
                ]
            )
        )

        let tagsForEntry = try store.loadVisibleTagsForEntry(
            authUserID: "user-1",
            normalizedURL: "https://example.com/shared-article"
        )
        XCTAssertEqual(tagsForEntry.map(\.name), ["設計メモ"])
        XCTAssertEqual(tagsForEntry.first?.currentUserRole, .editor)

        let entryIDs = try store.loadEntryIDsForTag(authUserID: "user-1", remoteTagID: "tag-1")
        XCTAssertEqual(entryIDs, [1])
    }

    func testChatGptPersonalLinkPolicyKeepsLocalLinksAndExcludesSharedOnlyLinks() throws {
        let local = try repository.saveFromResolvedURL("https://example.com/local-link")
        let localEntryID = try XCTUnwrap(local.entryID)
        let localEntry = try XCTUnwrap(repository.loadEntry(id: localEntryID))

        try store.applySnapshot(
            authUserID: "user-1",
            snapshot: PullSharedTagSnapshotResponse(
                pulledAt: "2026-04-23T10:00:00Z",
                normalizationVersion: 1,
                tags: [
                    RemoteSharedTag(
                        id: "tag-1",
                        name: "共有リンク",
                        createdAt: "2026-04-23T09:00:00Z",
                        updatedAt: "2026-04-23T09:30:00Z",
                        deletedAt: nil
                    )
                ],
                members: [
                    RemoteSharedTagMember(
                        tagID: "tag-1",
                        userID: "user-1",
                        displayName: nil,
                        role: "owner",
                        status: "active",
                        createdAt: "2026-04-23T09:00:00Z",
                        updatedAt: "2026-04-23T09:30:00Z"
                    )
                ],
                urls: [
                    RemoteSharedTagURL(
                        id: "url-1",
                        tagID: "tag-1",
                        rawURL: "https://example.com/shared-link",
                        normalizedURL: "https://example.com/shared-link",
                        deletedAt: nil
                    )
                ]
            )
        )

        let sharedEntry = try XCTUnwrap(
            try repository.loadChatGptPersonalLinkSnapshot().first {
                $0.normalizedURL == "https://example.com/shared-link"
            }
        )

        let localEligibility = ChatGptPersonalLinkSyncPolicy.eligibility(for: localEntry)
        XCTAssertTrue(localEligibility.eligible)
        XCTAssertTrue(localEligibility.reasons.isEmpty)

        let sharedEligibility = ChatGptPersonalLinkSyncPolicy.eligibility(for: sharedEntry)
        XCTAssertFalse(sharedEligibility.eligible)
        XCTAssertTrue(sharedEligibility.reasons.contains("local_provenance_required"))
        XCTAssertTrue(sharedEligibility.reasons.contains("shared_tag_allocation"))
    }

    func testOlderOrEqualSnapshotCannotReplaceNewerSnapshot() throws {
        let newer = makeSnapshot(
            pulledAt: "2026-04-23T10:00:00Z",
            tagID: "new-tag",
            tagName: "新しい状態",
            urlID: "new-url",
            normalizedURL: "https://example.com/new-state"
        )
        XCTAssertTrue(try store.applySnapshot(authUserID: "user-1", snapshot: newer))

        let older = makeSnapshot(
            pulledAt: "2026-04-23T09:00:00Z",
            tagID: "old-tag",
            tagName: "古い状態",
            urlID: "old-url",
            normalizedURL: "https://example.com/old-state"
        )
        XCTAssertFalse(try store.applySnapshot(authUserID: "user-1", snapshot: older))
        XCTAssertFalse(try store.applySnapshot(authUserID: "user-1", snapshot: newer))

        XCTAssertEqual(try store.loadVisibleTags(authUserID: "user-1").map(\.name), ["新しい状態"])
        XCTAssertNotNil(try repository.loadEntry(id: 1))
        XCTAssertEqual(try repository.loadEntry(id: 1)?.normalizedURL, "https://example.com/new-state")
        XCTAssertTrue(try repository.loadChatGptPersonalLinkSnapshot().allSatisfy { $0.normalizedURL != "https://example.com/old-state" })
    }

    func testLegacySessionDoesNotInventProviderKnowledge() throws {
        let data = Data(#"{"authUserID":"legacy-test","accessToken":"test-token"}"#.utf8)
        let session = try JSONDecoder().decode(SharedTagAuthSession.self, from: data)
        XCTAssertNil(session.verifiedLinkedProviders)
    }

    func testProviderRefreshReadsOnlyAuthAndPersistsVerifiedIdentities() async throws {
        let (service, sessionStore, _) = try makeIdentityLinkService()
        AuthLinkMockURLProtocol.install { _ in
            .json(#"{"id":"link-owner-a","identities":[{"provider":"google"},{"provider":"apple"},{"provider":"google"},{"provider":"email"}]}"#)
        }
        defer { AuthLinkMockURLProtocol.reset() }
        let result = await service.refreshLinkedProviders()
        guard case .success(let providers) = result else { return XCTFail("Expected verified providers") }
        XCTAssertEqual(providers, ["apple", "google"])
        XCTAssertEqual(try sessionStore.load()?.verifiedLinkedProviders, providers)
        let requests = AuthLinkMockURLProtocol.takeRequests()
        XCTAssertEqual(requests.count, 1)
        XCTAssertEqual(requests.first?.httpMethod, "GET")
        XCTAssertEqual(requests.first?.url?.path, "/auth/v1/user")
        XCTAssertNil(requests.first?.httpBody)
        XCTAssertEqual(try sessionStore.load()?.accessToken, "old-access-token")
    }

    func testAppleLinkTokenResponsePersistsActualProviderIdentities() async throws {
        let (service, sessionStore, _) = try makeIdentityLinkService()
        AuthLinkMockURLProtocol.install { _ in
            .json(#"{"access_token":"rotated-test-token","refresh_token":"rotated-test-refresh","user":{"id":"link-owner-a","identities":[{"provider":"apple"},{"provider":"google"}]}}"#)
        }
        defer { AuthLinkMockURLProtocol.reset() }
        let result = await service.linkAppleIdentity(idToken: "synthetic-token", nonce: "synthetic-nonce")
        XCTAssertEqual(result, .success(provider: "apple"))
        XCTAssertEqual(try sessionStore.load()?.verifiedLinkedProviders, ["apple", "google"])
    }

    func testProviderRefreshRejectsAnotherUserAndMissingIdentityKnowledge() async throws {
        let (service, sessionStore, _) = try makeIdentityLinkService()
        for response in [#"{"id":"other-test-user","identities":[{"provider":"apple"}]}"#, #"{"id":"link-owner-a"}"#] {
            AuthLinkMockURLProtocol.install { _ in .json(response) }
            let result = await service.refreshLinkedProviders()
            if case .success = result { XCTFail("Unexpected verified provider result") }
            XCTAssertNil(try sessionStore.load()?.verifiedLinkedProviders)
            XCTAssertEqual(try sessionStore.load()?.authUserID, "link-owner-a")
            AuthLinkMockURLProtocol.reset()
        }
    }

    func testProviderRefreshCannotOverwriteAnAccountChangedDuringRequest() async throws {
        let (service, sessionStore, _) = try makeIdentityLinkService()
        AuthLinkMockURLProtocol.install { _ in
            try? sessionStore.save(SharedTagAuthSession(authUserID: "other-test-user", accessToken: "new-test-token", refreshToken: nil, userEmail: nil))
            return .json(#"{"id":"link-owner-a","identities":[{"provider":"apple"}]}"#)
        }
        defer { AuthLinkMockURLProtocol.reset() }
        let result = await service.refreshLinkedProviders()
        if case .success = result { XCTFail("Unexpected stale refresh success") }
        XCTAssertEqual(try sessionStore.load()?.authUserID, "other-test-user")
        XCTAssertNil(try sessionStore.load()?.verifiedLinkedProviders)
    }

    private func makeIdentityLinkService() throws -> (
        SharedTagCloudService,
        SharedTagAuthSessionStore,
        SharedTagOAuthStateStore
    ) {
        let sessionStore = SharedTagAuthSessionStore(storage: SignOutTestAuthStorage())
        try sessionStore.save(
            SharedTagAuthSession(
                authUserID: "link-owner-a",
                accessToken: "old-access-token",
                refreshToken: "old-refresh-token",
                userEmail: "owner-a@example.com"
            )
        )
        let oauthStateStore = SharedTagOAuthStateStore(storage: SignOutTestAuthStorage())
        let sessionConfiguration = URLSessionConfiguration.ephemeral
        sessionConfiguration.protocolClasses = [AuthLinkMockURLProtocol.self]
        let urlSession = URLSession(configuration: sessionConfiguration)
        let service = SharedTagCloudService(
            config: SharedTagCloudConfig(
                enabled: true,
                supabaseURL: "https://supabase.test",
                anonKey: "test-publishable-key"
            ),
            sessionStore: sessionStore,
            store: store,
            repository: repository,
            oauthStateStore: oauthStateStore,
            authURLSession: urlSession
        )
        return (service, sessionStore, oauthStateStore)
    }

    private func seedSharedTagCache(authUserID: String, tagID: String, name: String) throws {
        try store.applySnapshot(
            authUserID: authUserID,
            snapshot: PullSharedTagSnapshotResponse(
                pulledAt: "2026-10-13T00:00:00Z",
                normalizationVersion: 1,
                tags: [
                    RemoteSharedTag(
                        id: tagID,
                        name: name,
                        createdAt: "2026-10-13T00:00:00Z",
                        updatedAt: "2026-10-13T00:00:00Z",
                        deletedAt: nil
                    )
                ],
                members: [
                    RemoteSharedTagMember(
                        tagID: tagID,
                        userID: authUserID,
                        displayName: nil,
                        role: "owner",
                        status: "active",
                        createdAt: "2026-10-13T00:00:00Z",
                        updatedAt: "2026-10-13T00:00:00Z"
                    )
                ],
                urls: []
            )
        )
    }

    private func authSessionResponse(userID: String, accessToken: String, refreshToken: String) throws -> Data {
        try JSONSerialization.data(withJSONObject: [
            "access_token": accessToken,
            "refresh_token": refreshToken,
            "user": [
                "id": userID,
                "email": "\(userID)@example.com",
            ],
        ])
    }

    private func makeSnapshot(
        pulledAt: String,
        tagID: String,
        tagName: String,
        urlID: String,
        normalizedURL: String
    ) -> PullSharedTagSnapshotResponse {
        PullSharedTagSnapshotResponse(
            pulledAt: pulledAt,
            normalizationVersion: 1,
            tags: [
                RemoteSharedTag(
                    id: tagID,
                    name: tagName,
                    createdAt: pulledAt,
                    updatedAt: pulledAt,
                    deletedAt: nil
                )
            ],
            members: [],
            urls: [
                RemoteSharedTagURL(
                    id: urlID,
                    tagID: tagID,
                    rawURL: normalizedURL,
                    normalizedURL: normalizedURL,
                    deletedAt: nil
                )
            ]
        )
    }
}

private final class SignOutTestAuthStorage: SharedTagAuthSecureStorage, @unchecked Sendable {
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


private final class HandoffAccountSwitchAuthStorage: SharedTagAuthSecureStorage, @unchecked Sendable {
    private let lock = NSLock()
    private let first: Data
    private var subsequent: Data?
    private(set) var loadCount = 0

    init(first: Data, subsequent: Data) {
        self.first = first
        self.subsequent = subsequent
    }

    func load() throws -> Data? {
        lock.lock()
        defer { lock.unlock() }
        loadCount += 1
        return loadCount == 1 ? first : subsequent
    }

    func save(_ data: Data) throws {
        lock.lock()
        defer { lock.unlock() }
        subsequent = data
    }

    func clear() throws {
        lock.lock()
        defer { lock.unlock() }
        subsequent = nil
    }
}

private struct AuthLinkStubResponse: Sendable {
    let statusCode: Int
    let body: Data

    static func json(_ value: String, statusCode: Int = 200) -> AuthLinkStubResponse {
        AuthLinkStubResponse(statusCode: statusCode, body: Data(value.utf8))
    }

    static func data(_ value: Data, statusCode: Int = 200) -> AuthLinkStubResponse {
        AuthLinkStubResponse(statusCode: statusCode, body: value)
    }
}

private final class AuthLinkMockURLProtocolState: @unchecked Sendable {
    private let lock = NSLock()
    private var responseHandler: (@Sendable (URLRequest) -> AuthLinkStubResponse)?
    private var recordedRequests: [URLRequest] = []

    func install(_ handler: @escaping @Sendable (URLRequest) -> AuthLinkStubResponse) {
        lock.lock()
        responseHandler = handler
        recordedRequests = []
        lock.unlock()
    }

    func reset() {
        lock.lock()
        responseHandler = nil
        recordedRequests = []
        lock.unlock()
    }

    func response(for request: URLRequest) -> AuthLinkStubResponse {
        lock.lock()
        recordedRequests.append(request)
        let handler = responseHandler
        lock.unlock()
        return handler?(request) ?? .json(#"{"message":"No auth link mock response was registered"}"#, statusCode: 500)
    }

    func takeRequests() -> [URLRequest] {
        lock.lock()
        defer { lock.unlock() }
        let requests = recordedRequests
        recordedRequests = []
        return requests
    }
}

private final class AuthLinkMockURLProtocol: URLProtocol, @unchecked Sendable {
    private static let state = AuthLinkMockURLProtocolState()

    static func install(_ handler: @escaping @Sendable (URLRequest) -> AuthLinkStubResponse) {
        state.install(handler)
    }

    static func reset() {
        state.reset()
    }

    static func takeRequests() -> [URLRequest] {
        state.takeRequests()
    }

    override class func canInit(with request: URLRequest) -> Bool {
        request.url?.host == "supabase.test"
    }

    override class func canonicalRequest(for request: URLRequest) -> URLRequest {
        request
    }

    override func startLoading() {
        guard let requestURL = request.url else {
            client?.urlProtocol(self, didFailWithError: URLError(.badURL))
            return
        }
        let capturedRequest: URLRequest
        do {
            capturedRequest = try Self.capturingBody(of: request)
        } catch {
            client?.urlProtocol(self, didFailWithError: error)
            return
        }
        let stub = Self.state.response(for: capturedRequest)
        guard let response = HTTPURLResponse(
            url: requestURL,
            statusCode: stub.statusCode,
            httpVersion: "HTTP/1.1",
            headerFields: ["Content-Type": "application/json"]
        ) else {
            client?.urlProtocol(self, didFailWithError: URLError(.badServerResponse))
            return
        }
        client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: stub.body)
        client?.urlProtocolDidFinishLoading(self)
    }

    private static func capturingBody(of request: URLRequest) throws -> URLRequest {
        guard request.httpBody == nil, let stream = request.httpBodyStream else { return request }
        stream.open()
        defer { stream.close() }
        var body = Data()
        var buffer = [UInt8](repeating: 0, count: 4096)
        while true {
            let count = stream.read(&buffer, maxLength: 4096)
            if count == 0 { break }
            if count < 0 { throw stream.streamError ?? URLError(.cannotDecodeRawData) }
            body.append(contentsOf: buffer.prefix(count))
        }
        var captured = request
        captured.httpBodyStream = nil
        captured.httpBody = body
        return captured
    }

    override func stopLoading() {}
}
