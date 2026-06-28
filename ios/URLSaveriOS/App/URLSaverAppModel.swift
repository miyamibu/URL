import Foundation
import SwiftUI

enum RootTab: Hashable {
    case main
    case archive
    case groups
}

struct AppNotification: Identifiable, Equatable {
    enum Action: Equatable {
        case openEntry(Int64)
        case openArchive
        case undoPendingDelete(Int64)
        case undoPendingDeleteBatch([Int64])
        case undoArchive(Int64)
        case undoArchiveBatch([Int64])
        case undoTitle(Int64, String?)
    }

    let id = UUID()
    let message: String
    let actionLabel: String?
    let action: Action?
    let autoDismissAfter: TimeInterval?
}

enum PromoCodeApplyResult: Equatable {
    case success
    case authRequired
    case invalidCode
    case failure(String)
}

enum ContactSupportSendResult: Equatable {
    case success
    case failure(String)
}

struct ChatGptPersonalLinkSettings: Equatable {
    var enabled: Bool = false
    var contentFetchEnabled: Bool = false
    var lastSyncedAt: Date?
    var lastErrorMessage: String?
}

@MainActor
final class URLSaverAppModel: ObservableObject {
    @Published private(set) var activeEntries: [URLRecord] = []
    @Published private(set) var archivedEntries: [URLRecord] = []
    @Published private(set) var profile: UserProfile = .empty
    @Published private(set) var pendingInviteRecord: PendingInviteRecord?
    @Published private(set) var incomingLocalTagID: Int64?
    @Published var pendingPromoCode: String?
    @Published var inviteConfirmationToken: String?
    @Published private(set) var sharedTagCloudState = SharedTagCloudState(isConfigured: false, isSignedIn: false, signedInEmail: nil)
    @Published private(set) var entitlements: FeatureEntitlements = LaunchStandardPlan.entitlements
    @Published private(set) var sharedTags: [SharedTagSummary] = []
    @Published private(set) var sharedTagGroups: [SharedTagGroupSummary] = []
    @Published private(set) var localTags: [LocalTagSummary] = []
    @Published private(set) var localTagAssignments: [Int64: Set<Int64>] = [:]
    @Published private(set) var collections: [CollectionSummary] = []
    @Published private(set) var chatGptPersonalLinkSettings = ChatGptPersonalLinkSettings()
    @Published private(set) var isUpdatingChatGptPersonalLinkSync = false
    @Published private(set) var profileStatusMessage: String?
    @Published var selectedTab: RootTab = .main
    @Published var navigationPath: [Int64] = []
    @Published var currentNotification: AppNotification?

    private let services: AppServices
    private var notificationQueue: [AppNotification] = []
    private var notificationDismissTask: Task<Void, Never>?
    private var deleteTimers: [Int64: Task<Void, Never>] = [:]
    private var hasBootstrapped = false

    init(services: AppServices) {
        self.services = services
    }

    func bootstrapIfNeeded() async {
        guard !hasBootstrapped else { return }
        hasBootstrapped = true
        profile = (try? services.profileStore.load()) ?? .empty
        pendingInviteRecord = try? services.pendingInviteStore.load()
        try? services.repository.cleanupExpiredPendingDeletes()
        await reload()
        await refreshSharedTagCloudState()
        await refreshEntitlements()
        refreshChatGptPersonalLinkSettings()
        await scheduleDeleteTimersForPersistedEntries()
        await consumeShareHandoffReport()
        startPostBootstrapRefresh()
    }

    private func startPostBootstrapRefresh() {
        Task { [weak self] in
            guard let self else { return }
            _ = await self.syncSharedTagCloud(showFailureNotification: false)
            await self.refreshEntitlements()
            await self.processMetadataBacklog()
        }
    }

    func reload() async {
        activeEntries = (try? services.repository.observeActiveSnapshot()) ?? []
        archivedEntries = (try? services.repository.observeArchiveSnapshot()) ?? []
        localTags = (try? services.repository.loadLocalTags()) ?? []
        localTagAssignments = (try? services.repository.loadLocalTagAssignments()) ?? [:]
        collections = (try? services.repository.loadCollections()) ?? []
    }

    func processMetadataBacklog() async {
        AppBackgroundScheduler.schedule()
        _ = await services.metadataCoordinator.processBacklog(limit: 60)
        await reload()
    }

    func refreshEntitlements() async {
        entitlements = await services.entitlementService.refreshForCurrentSession()
    }

    func refreshChatGptPersonalLinkSettings() {
        let defaults = UserDefaults.standard
        chatGptPersonalLinkSettings = ChatGptPersonalLinkSettings(
            enabled: defaults.bool(forKey: "chatgpt_personal_link_sync.enabled"),
            contentFetchEnabled: defaults.bool(forKey: "chatgpt_personal_link_sync.content_fetch_enabled"),
            lastSyncedAt: defaults.object(forKey: "chatgpt_personal_link_sync.last_synced_at") as? Date,
            lastErrorMessage: defaults.string(forKey: "chatgpt_personal_link_sync.last_error_message")
        )
    }

    func setChatGptPersonalLinkSync(enabled: Bool, contentFetchEnabled: Bool) async {
        guard !isUpdatingChatGptPersonalLinkSync else { return }
        isUpdatingChatGptPersonalLinkSync = true
        defer { isUpdatingChatGptPersonalLinkSync = false }
        do {
            try await services.sharedTagCloud.setChatGptPersonalLinkSync(
                enabled: enabled,
                contentFetchEnabled: contentFetchEnabled
            )
            let defaults = UserDefaults.standard
            defaults.set(enabled, forKey: "chatgpt_personal_link_sync.enabled")
            defaults.set(enabled && contentFetchEnabled, forKey: "chatgpt_personal_link_sync.content_fetch_enabled")
            defaults.set(Date(), forKey: "chatgpt_personal_link_sync.last_synced_at")
            defaults.removeObject(forKey: "chatgpt_personal_link_sync.last_error_message")
            refreshChatGptPersonalLinkSettings()
            enqueueNotification(AppNotification(message: enabled ? "ChatGPT連携を有効にしました" : "ChatGPT連携を無効にしました", actionLabel: nil, action: nil, autoDismissAfter: 4))
        } catch SharedTagCloudError.authRequired {
            enqueueNotification(AppNotification(message: "ChatGPT連携にはサインインが必要です", actionLabel: nil, action: nil, autoDismissAfter: 4))
        } catch {
            let message = error.localizedDescription
            UserDefaults.standard.set(message, forKey: "chatgpt_personal_link_sync.last_error_message")
            refreshChatGptPersonalLinkSettings()
            enqueueNotification(AppNotification(message: message, actionLabel: nil, action: nil, autoDismissAfter: 5))
        }
    }

    func purchasePaidCourse(planType: PlanType, billingPeriod: BillingPeriod) async {
        let result = await services.storePurchaseService.purchase(
            planType: planType,
            billingPeriod: billingPeriod
        )
        switch result {
        case .verified(let plan, let period):
            await refreshEntitlements()
            let planName = plan == .pro ? "Pro" : "Standard"
            let periodName = period == .yearly ? "年払い" : "月額"
            enqueueNotification(AppNotification(message: "\(planName) \(periodName) が有効になりました", actionLabel: nil, action: nil, autoDismissAfter: 4))
        case .authRequired:
            enqueueNotification(AppNotification(message: "購入前にサインインしてください", actionLabel: nil, action: nil, autoDismissAfter: 4))
        case .notConfigured:
            enqueueNotification(AppNotification(message: "購入機能はこのビルドでは設定されていません", actionLabel: nil, action: nil, autoDismissAfter: 4))
        case .cancelled:
            enqueueNotification(AppNotification(message: "購入をキャンセルしました", actionLabel: nil, action: nil, autoDismissAfter: 3))
        case .pending:
            enqueueNotification(AppNotification(message: "購入確認中です。完了後にプランが反映されます", actionLabel: nil, action: nil, autoDismissAfter: 5))
        case .failed(let message):
            enqueueNotification(AppNotification(message: message, actionLabel: nil, action: nil, autoDismissAfter: 5))
        }
    }

    func redeemPromoCode(_ code: String) async -> PromoCodeApplyResult {
        let trimmedCode = code.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmedCode.isEmpty else {
            return .invalidCode
        }
        do {
            entitlements = try await services.entitlementService.redeemPromoCode(trimmedCode)
            return .success
        } catch SharedTagCloudError.authRequired {
            return .authRequired
        } catch let error as SharedTagCloudError {
            return .failure(error.userMessage)
        } catch {
            return .failure("優待コードを適用できませんでした")
        }
    }

    func clearPendingPromoCode() {
        pendingPromoCode = nil
    }

    func consumeShareHandoffReport() async {
        guard let report = try? await services.handoffStore.consume() else { return }
        handleShareReport(report)
        if let entryID = report.entryID, report.result == .created || report.result == .restoredFromPendingDelete {
            if report.result == .created || report.result == .restoredFromPendingDelete {
                Task {
                    _ = await services.metadataCoordinator.enqueue(entryID: entryID)
                    await reload()
                }
            }
        }
        await reload()
    }

    func manualSave(input: String, localTagIDs: Set<Int64> = [], collectionID: Int64? = nil) async -> ShareSaveResult? {
        if let data = input.trimmingCharacters(in: .whitespacesAndNewlines).data(using: .utf8),
           (try? JSONDecoder().decode(TagSharePayload.self, from: data)) != nil {
            _ = await importLocalTagPayloadText(input)
            return nil
        }
        guard let saveResult = try? services.repository.saveFromManualInput(input, localTagIDs: Array(localTagIDs)) else {
            enqueueNotification(
                AppNotification(message: "保存できませんでした", actionLabel: nil, action: nil, autoDismissAfter: 3)
            )
            return nil
        }

        switch saveResult.result {
        case .inputTooLarge, .invalidURL, .noURLFound:
            return saveResult.result
        default:
            if let collectionID, let entryID = saveResult.entryID {
                _ = try? services.repository.assignCollection(entryID: entryID, collectionID: collectionID)
            }
            await handleSaveResult(saveResult, degradationNotice: nil)
            return nil
        }
    }

    func archive(entryID: Int64) async {
        guard (try? services.repository.archive(entryID: entryID)) == true else {
            enqueueNotification(AppNotification(message: "アーカイブできませんでした", actionLabel: nil, action: nil, autoDismissAfter: 3))
            return
        }
        await reload()
        enqueueNotification(
            AppNotification(
                message: "アーカイブしました",
                actionLabel: "元に戻す",
                action: .undoArchive(entryID),
                autoDismissAfter: 5
            )
        )
    }

    func archive(entryIDs: Set<Int64>) async {
        let ids = entryIDs.sorted()
        guard !ids.isEmpty else { return }
        var archivedIDs: [Int64] = []
        for entryID in ids where (try? services.repository.archive(entryID: entryID)) == true {
            archivedIDs.append(entryID)
        }

        guard !archivedIDs.isEmpty else {
            enqueueNotification(AppNotification(message: "アーカイブできませんでした", actionLabel: nil, action: nil, autoDismissAfter: 3))
            return
        }

        await reload()
        enqueueNotification(
            AppNotification(
                message: "\(archivedIDs.count)件をアーカイブしました",
                actionLabel: "元に戻す",
                action: .undoArchiveBatch(archivedIDs),
                autoDismissAfter: 5
            )
        )
    }

    func markPendingDelete(entryID: Int64) async {
        guard let pendingUntil = try? services.repository.markPendingDelete(entryID: entryID) else {
            enqueueNotification(AppNotification(message: "削除できませんでした", actionLabel: nil, action: nil, autoDismissAfter: 3))
            return
        }
        scheduleDeleteTimer(entryID: entryID, due: pendingUntil)
        await reload()
        enqueueNotification(
            AppNotification(
                message: "削除しました",
                actionLabel: "元に戻す",
                action: .undoPendingDelete(entryID),
                autoDismissAfter: 5
            )
        )
    }

    func markPendingDelete(entryIDs: Set<Int64>) async {
        let ids = entryIDs.sorted()
        guard !ids.isEmpty else { return }
        var deletedIDs: [Int64] = []

        for entryID in ids {
            guard let pendingUntil = try? services.repository.markPendingDelete(entryID: entryID) else {
                continue
            }
            scheduleDeleteTimer(entryID: entryID, due: pendingUntil)
            deletedIDs.append(entryID)
        }

        guard !deletedIDs.isEmpty else {
            enqueueNotification(AppNotification(message: "削除できませんでした", actionLabel: nil, action: nil, autoDismissAfter: 3))
            return
        }

        await reload()
        enqueueNotification(
            AppNotification(
                message: "\(deletedIDs.count)件を削除しました",
                actionLabel: "元に戻す",
                action: .undoPendingDeleteBatch(deletedIDs),
                autoDismissAfter: 5
            )
        )
    }

    func restoreFromArchive(entryID: Int64) async -> Bool {
        let restored = (try? services.repository.restore(entryID: entryID)) == true
        if restored {
            await reload()
        }
        return restored
    }

    func saveTitle(entryID: Int64, text: String) async -> Bool {
        guard let result = try? services.repository.saveUserTitle(entryID: entryID, rawTitle: text), result.success else {
            enqueueNotification(AppNotification(message: "タイトルを保存できませんでした", actionLabel: nil, action: nil, autoDismissAfter: 3))
            return false
        }
        await reload()
        enqueueNotification(
            AppNotification(
                message: "タイトルを保存しました",
                actionLabel: "元に戻す",
                action: .undoTitle(entryID, result.oldTitle),
                autoDismissAfter: 5
            )
        )
        return true
    }

    func saveMemo(entryID: Int64, text: String) async -> Bool {
        guard let result = try? services.repository.saveMemo(entryID: entryID, rawMemo: text), result.success else {
            enqueueNotification(AppNotification(message: "メモを保存できませんでした", actionLabel: nil, action: nil, autoDismissAfter: 3))
            return false
        }
        await reload()
        enqueueNotification(AppNotification(message: "メモを保存しました", actionLabel: nil, action: nil, autoDismissAfter: 3))
        return true
    }

    func createLocalTag(name: String) async -> LocalTagSummary? {
        guard let tag = try? services.repository.createLocalTag(name: name) else {
            enqueueNotification(AppNotification(message: "タグを作成できませんでした", actionLabel: nil, action: nil, autoDismissAfter: 3))
            return nil
        }
        await reload()
        enqueueNotification(AppNotification(message: "タグを作成しました", actionLabel: nil, action: nil, autoDismissAfter: 3))
        return tag
    }

    func createCollection(name: String) async -> CollectionSummary? {
        guard let collection = try? services.repository.createCollection(name: name) else {
            enqueueNotification(AppNotification(message: "コレクションを作成できませんでした", actionLabel: nil, action: nil, autoDismissAfter: 3))
            return nil
        }
        await reload()
        enqueueNotification(AppNotification(message: "コレクションを作成しました", actionLabel: nil, action: nil, autoDismissAfter: 3))
        return collection
    }

    func assignCollection(entryID: Int64, collectionID: Int64) async -> Bool {
        guard (try? services.repository.assignCollection(entryID: entryID, collectionID: collectionID)) == true else {
            enqueueNotification(AppNotification(message: "コレクションに移動できませんでした", actionLabel: nil, action: nil, autoDismissAfter: 3))
            return false
        }
        await reload()
        enqueueNotification(AppNotification(message: "コレクションに移動しました", actionLabel: nil, action: nil, autoDismissAfter: 3))
        return true
    }

    func assignCollectionAndCreateLocalTag(entryID: Int64, collection: CollectionSummary) async -> Bool {
        let previousCollection = entry(for: entryID).flatMap { entry in
            collections.first { $0.id == entry.collectionID && $0.id != collection.id && $0.id != 1 }
        }
        guard (try? services.repository.assignCollection(entryID: entryID, collectionID: collection.id)) == true else {
            enqueueNotification(AppNotification(message: "コレクションに移動できませんでした", actionLabel: nil, action: nil, autoDismissAfter: 3))
            return false
        }
        if let previousCollection,
           let tag = try? services.repository.createLocalTag(name: previousCollection.name) {
            _ = try? services.repository.assignLocalTag(entryID: entryID, tagID: tag.id)
        }
        if collection.id != 1,
           let tag = try? services.repository.createLocalTag(name: collection.name) {
            _ = try? services.repository.assignLocalTag(entryID: entryID, tagID: tag.id)
        }
        await reload()
        enqueueNotification(AppNotification(message: "コレクションに移動しました", actionLabel: nil, action: nil, autoDismissAfter: 3))
        return true
    }

    func reorderCollections(collectionIDs: [Int64]) async -> Bool {
        guard (try? services.repository.reorderCollections(collectionIDs: collectionIDs)) == true else {
            enqueueNotification(AppNotification(message: "コレクションを並び替えできませんでした", actionLabel: nil, action: nil, autoDismissAfter: 3))
            return false
        }
        await reload()
        enqueueNotification(AppNotification(message: "コレクションを並び替えました", actionLabel: nil, action: nil, autoDismissAfter: 3))
        return true
    }

    func deleteCollection(collectionID: Int64) async -> Bool {
        guard (try? services.repository.deleteCollection(id: collectionID)) == true else {
            enqueueNotification(AppNotification(message: "コレクションを削除できませんでした", actionLabel: nil, action: nil, autoDismissAfter: 3))
            return false
        }
        await reload()
        enqueueNotification(AppNotification(message: "コレクションを削除しました", actionLabel: nil, action: nil, autoDismissAfter: 3))
        return true
    }

    func loadLocalTagsForEntry(entryID: Int64) -> [LocalTagSummary] {
        (try? services.repository.loadLocalTagsForEntry(entryID: entryID)) ?? []
    }

    func addEntry(_ entryID: Int64, toLocalTag tagID: Int64) async -> Bool {
        guard (try? services.repository.assignLocalTag(entryID: entryID, tagID: tagID)) == true else {
            enqueueNotification(AppNotification(message: "タグに追加できませんでした", actionLabel: nil, action: nil, autoDismissAfter: 3))
            return false
        }
        await reload()
        enqueueNotification(AppNotification(message: "タグに追加しました", actionLabel: nil, action: nil, autoDismissAfter: 3))
        return true
    }

    func removeEntry(_ entryID: Int64, fromLocalTag tagID: Int64) async -> Bool {
        guard (try? services.repository.removeLocalTag(entryID: entryID, tagID: tagID)) == true else {
            enqueueNotification(AppNotification(message: "タグから外せませんでした", actionLabel: nil, action: nil, autoDismissAfter: 3))
            return false
        }
        await reload()
        enqueueNotification(AppNotification(message: "タグから外しました", actionLabel: nil, action: nil, autoDismissAfter: 3))
        return true
    }

    func deleteLocalTag(tagID: Int64) async -> Bool {
        guard (try? services.repository.deleteLocalTag(id: tagID)) == true else {
            enqueueNotification(AppNotification(message: "タグを削除できませんでした", actionLabel: nil, action: nil, autoDismissAfter: 3))
            return false
        }
        await reload()
        enqueueNotification(AppNotification(message: "タグを削除しました", actionLabel: nil, action: nil, autoDismissAfter: 3))
        return true
    }

    func localTagShareText(for tag: LocalTagSummary) -> String {
        """
        URL Saverの端末内タグ:
        \(tag.name)

        urlsaver://tag/\(tag.id)

        このリンクは同じ端末内のタグを開くためのものです。
        """
    }

    func localTagPayloadText(tagID: Int64) -> String? {
        guard let payload = try? services.repository.exportLocalTag(tagID: tagID),
              let data = try? JSONEncoder().encode(payload) else {
            return nil
        }
        return String(data: data, encoding: .utf8)
    }

    @discardableResult
    func importLocalTagPayloadText(_ text: String) async -> Bool {
        guard let data = text.trimmingCharacters(in: .whitespacesAndNewlines).data(using: .utf8),
              let payload = try? JSONDecoder().decode(TagSharePayload.self, from: data),
              let result = try? services.repository.importLocalTagPayload(payload) else {
            enqueueNotification(AppNotification(message: "タグデータを読み込めませんでした", actionLabel: nil, action: nil, autoDismissAfter: 4))
            return false
        }
        await reload()
        enqueueNotification(
            AppNotification(
                message: "タグ「\(result.tagName)」を読み込みました（新規\(result.created)件 / 追加\(result.merged)件）",
                actionLabel: nil,
                action: nil,
                autoDismissAfter: 5
            )
        )
        return true
    }

    func retryMetadata(entryID: Int64) async {
        guard (try? services.repository.retryMetadata(entryID: entryID)) == true else {
            enqueueNotification(AppNotification(message: "再取得できませんでした", actionLabel: nil, action: nil, autoDismissAfter: 3))
            return
        }
        AppBackgroundScheduler.schedule()
        Task {
            _ = await services.metadataCoordinator.enqueue(entryID: entryID)
            await reload()
        }
        await reload()
    }

    func refreshMetadata(entryID: Int64) async {
        guard (try? services.repository.refreshMetadata(entryID: entryID)) == true else {
            enqueueNotification(AppNotification(message: "読み込みできませんでした", actionLabel: nil, action: nil, autoDismissAfter: 3))
            return
        }
        AppBackgroundScheduler.schedule()
        Task {
            _ = await services.metadataCoordinator.enqueue(entryID: entryID)
            await reload()
        }
        await reload()
    }

    func handleIncomingURL(_ url: URL) async {
        switch IncomingURLRoute(url: url) {
        case .authCallback:
            await handleSharedTagAuthCallback(url)
        case .invite(let token):
            guard !token.isEmpty else {
                enqueueNotification(AppNotification(message: "共有招待リンクを開けませんでした", actionLabel: nil, action: nil, autoDismissAfter: 4))
                return
            }
            inviteConfirmationToken = token
        case .promo(let code):
            guard !code.isEmpty else {
                enqueueNotification(AppNotification(message: "優待コードリンクを開けませんでした", actionLabel: nil, action: nil, autoDismissAfter: 4))
                return
            }
            pendingPromoCode = code
            enqueueNotification(AppNotification(message: "優待コードを読み込みました", actionLabel: nil, action: nil, autoDismissAfter: 3))
        case .save(let rawURL, let degradationNotice):
            guard let saveResult = try? services.repository.saveFromResolvedURL(rawURL) else {
                enqueueNotification(AppNotification(message: "保存できませんでした", actionLabel: nil, action: nil, autoDismissAfter: 3))
                return
            }
            await handleSaveResult(saveResult, degradationNotice: degradationNotice)
        case .tag(let rawTagID):
            guard let tagID = Int64(rawTagID), ((try? services.repository.loadLocalTags().contains { $0.id == tagID }) == true) else {
                enqueueNotification(AppNotification(message: "タグを開けませんでした", actionLabel: nil, action: nil, autoDismissAfter: 4))
                return
            }
            incomingLocalTagID = tagID
        case .unknown:
            break
        }
    }

    func consumeIncomingLocalTagID() {
        incomingLocalTagID = nil
    }

    func googleOAuthURLForSharedTagCloud() -> URL? {
        services.sharedTagCloud.googleOAuthURL()
    }

    func signInWithAppleForSharedTagCloud(idToken: String, nonce: String) async {
        switch await services.sharedTagCloud.signInWithAppleIDToken(idToken: idToken, nonce: nonce) {
        case .success(let email):
            await refreshSharedTagCloudState()
            await refreshEntitlements()
            profileStatusMessage = "Appleでサインインしました。プロフィールと共有タグを使えます。"
            enqueueNotification(
                AppNotification(
                    message: email.map { "\($0) でAppleサインインしました" } ?? "Appleでサインインしました",
                    actionLabel: nil,
                    action: nil,
                    autoDismissAfter: 4
                )
            )
            _ = await services.sharedTagCloud.syncCurrentSession()
            await refreshSharedTagCloudState()
            await refreshEntitlements()
            await acceptPendingInviteIfPossible()
        case .needsEmailConfirmation:
            profileStatusMessage = "Appleサインインの確認後に再度サインインしてください。"
            enqueueNotification(AppNotification(message: "Appleサインイン確認後に共有タグクラウドへサインインしてください", actionLabel: nil, action: nil, autoDismissAfter: 5))
        case .failure(let message):
            profileStatusMessage = message
            enqueueNotification(AppNotification(message: message, actionLabel: nil, action: nil, autoDismissAfter: 4))
        }
    }

    private func handleSharedTagAuthCallback(_ url: URL) async {
        switch await services.sharedTagCloud.handleOAuthCallback(url: url) {
        case .success(let email):
            await refreshSharedTagCloudState()
            await refreshEntitlements()
            profileStatusMessage = "Googleでサインインしました。プロフィールと共有タグを使えます。"
            enqueueNotification(
                AppNotification(
                    message: email.map { "\($0) でGoogleサインインしました" } ?? "Googleでサインインしました",
                    actionLabel: nil,
                    action: nil,
                    autoDismissAfter: 4
                )
            )
            _ = await services.sharedTagCloud.syncCurrentSession()
            await refreshSharedTagCloudState()
            await refreshEntitlements()
            await acceptPendingInviteIfPossible()
        case .needsEmailConfirmation:
            profileStatusMessage = "メール確認後に再度サインインしてください。"
            enqueueNotification(AppNotification(message: "メール確認後に共有タグクラウドへサインインしてください", actionLabel: nil, action: nil, autoDismissAfter: 5))
        case .failure(let message):
            profileStatusMessage = message
            enqueueNotification(AppNotification(message: message, actionLabel: nil, action: nil, autoDismissAfter: 4))
        }
    }

    func clearPendingInvite() async {
        do {
            try services.pendingInviteStore.clear()
            pendingInviteRecord = nil
        } catch {
            enqueueNotification(AppNotification(message: "共有招待の状態を更新できませんでした", actionLabel: nil, action: nil, autoDismissAfter: 4))
        }
    }

    func clearInviteConfirmation() {
        inviteConfirmationToken = nil
    }

    func refreshSharedTagCloudState() async {
        sharedTagCloudState = services.sharedTagCloud.state
        sharedTags = (try? services.sharedTagCloud.loadVisibleTags()) ?? []
        sharedTagGroups = (try? services.sharedTagCloud.loadVisibleGroups()) ?? []
    }

    func clearProfileStatusMessage() {
        profileStatusMessage = nil
    }

    func showProfileStatusMessage(_ message: String) {
        profileStatusMessage = message
    }

    func sendContactSupport(email: String, name: String, message: String) async -> ContactSupportSendResult {
        let trimmedEmail = email.trimmingCharacters(in: .whitespacesAndNewlines)
        let trimmedName = name.trimmingCharacters(in: .whitespacesAndNewlines)
        let trimmedMessage = message.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmedEmail.isEmpty, !trimmedName.isEmpty, !trimmedMessage.isEmpty else {
            return .failure("メールアドレス、氏名、問い合わせ内容を入力してください。")
        }
        let result = await services.contactSupportService.send(
            email: trimmedEmail,
            name: trimmedName,
            message: trimmedMessage,
            isSignedIn: sharedTagCloudState.isSignedIn
        )
        switch result {
        case .success:
            profileStatusMessage = "問い合わせを送信完了しました"
            return .success
        case .failure(let message):
            return .failure(message)
        }
    }

    func saveProfile(displayName: String) {
        do {
            var updated = profile
            updated.displayName = String(displayName.trimmingCharacters(in: .whitespacesAndNewlines).prefix(40))
            updated.updatedAt = Date()
            try services.profileStore.save(updated)
            profile = updated
            profileStatusMessage = "プロフィールを保存しました"
            Task { await services.sharedTagCloud.upsertSharedProfile(displayName: updated.trimmedDisplayName) }
            enqueueNotification(AppNotification(message: "プロフィールを保存しました", actionLabel: nil, action: nil, autoDismissAfter: 3))
        } catch {
            profileStatusMessage = "プロフィールを保存できませんでした"
            enqueueNotification(AppNotification(message: "プロフィールを保存できませんでした", actionLabel: nil, action: nil, autoDismissAfter: 4))
        }
    }

    @discardableResult
    func saveProfile(displayName: String, avatarImageData: Data?, updatesAvatar: Bool) -> Bool {
        do {
            var updated = profile
            updated.displayName = String(displayName.trimmingCharacters(in: .whitespacesAndNewlines).prefix(40))
            if updatesAvatar {
                updated.avatarImageData = avatarImageData
            }
            updated.updatedAt = Date()
            try services.profileStore.save(updated)
            profile = updated
            profileStatusMessage = "プロフィールを保存しました"
            Task { await services.sharedTagCloud.upsertSharedProfile(displayName: updated.trimmedDisplayName) }
            enqueueNotification(AppNotification(message: "プロフィールを保存しました", actionLabel: nil, action: nil, autoDismissAfter: 3))
            return true
        } catch {
            profileStatusMessage = "プロフィールを保存できませんでした"
            enqueueNotification(AppNotification(message: "プロフィールを保存できませんでした", actionLabel: nil, action: nil, autoDismissAfter: 4))
            return false
        }
    }

    func saveProfileAvatar(imageData: Data?) {
        do {
            var updated = profile
            updated.avatarImageData = imageData
            updated.updatedAt = Date()
            try services.profileStore.save(updated)
            profile = updated
            profileStatusMessage = imageData == nil ? "プロフィール写真を削除しました" : "プロフィール写真を保存しました"
            enqueueNotification(
                AppNotification(
                    message: imageData == nil ? "プロフィール写真を削除しました" : "プロフィール写真を保存しました",
                    actionLabel: nil,
                    action: nil,
                    autoDismissAfter: 3
                )
            )
        } catch {
            profileStatusMessage = "プロフィール写真を保存できませんでした"
            enqueueNotification(AppNotification(message: "プロフィール写真を保存できませんでした", actionLabel: nil, action: nil, autoDismissAfter: 4))
        }
    }

    var profileDisplayName: String {
        if !profile.trimmedDisplayName.isEmpty {
            return profile.trimmedDisplayName
        }
        if let signedInEmail = sharedTagCloudState.signedInEmail,
           let localPart = signedInEmail.split(separator: "@").first,
           !localPart.isEmpty {
            return String(localPart)
        }
        return "プロフィール名"
    }

    func signInToSharedTagCloud(email: String, password: String) async {
        switch await services.sharedTagCloud.signIn(email: email, password: password) {
        case .success(let email):
            await refreshSharedTagCloudState()
            await refreshEntitlements()
            profileStatusMessage = "サインインしました。プロフィールと共有タグを使えます。"
            enqueueNotification(
                AppNotification(
                    message: email.map { "\($0) で共有タグクラウドに接続しました" } ?? "共有タグクラウドに接続しました",
                    actionLabel: nil,
                    action: nil,
                    autoDismissAfter: 4
                )
            )
            _ = await services.sharedTagCloud.syncCurrentSession()
            await refreshSharedTagCloudState()
            await refreshEntitlements()
            await acceptPendingInviteIfPossible()
        case .needsEmailConfirmation:
            enqueueNotification(
                AppNotification(
                    message: "メール確認後に共有タグクラウドへサインインしてください",
                    actionLabel: nil,
                    action: nil,
                    autoDismissAfter: 5
                )
            )
        case .failure(let message):
            enqueueNotification(AppNotification(message: message, actionLabel: nil, action: nil, autoDismissAfter: 4))
        }
    }

    func signUpForSharedTagCloud(email: String, password: String) async {
        switch await services.sharedTagCloud.signUp(email: email, password: password) {
        case .success(let email):
            if profile.trimmedDisplayName.isEmpty,
               let email,
               let localPart = email.split(separator: "@").first,
               !localPart.isEmpty {
                saveProfile(displayName: String(localPart))
            }
            await refreshSharedTagCloudState()
            await refreshEntitlements()
            profileStatusMessage = "新規登録が完了しました。プロフィールと共有タグを使えます。"
            enqueueNotification(
                AppNotification(
                    message: email.map { "\($0) で共有タグクラウドに接続しました" } ?? "共有タグクラウドに接続しました",
                    actionLabel: nil,
                    action: nil,
                    autoDismissAfter: 4
                )
            )
            _ = await services.sharedTagCloud.syncCurrentSession()
            await refreshSharedTagCloudState()
            await refreshEntitlements()
            await acceptPendingInviteIfPossible()
        case .needsEmailConfirmation:
            enqueueNotification(
                AppNotification(
                    message: "確認メールを送信しました。確認後に再度サインインしてください",
                    actionLabel: nil,
                    action: nil,
                    autoDismissAfter: 5
                )
            )
        case .failure(let message):
            enqueueNotification(AppNotification(message: message, actionLabel: nil, action: nil, autoDismissAfter: 4))
        }
    }

    func resendSharedTagEmailConfirmation(email: String) async {
        switch await services.sharedTagCloud.resendEmailConfirmation(email: email) {
        case .success:
            profileStatusMessage = "確認メールを再送しました。メール確認後にサインインしてください。"
            enqueueNotification(AppNotification(message: "確認メールを再送しました", actionLabel: nil, action: nil, autoDismissAfter: 4))
        case .needsEmailConfirmation:
            profileStatusMessage = "確認メールを再送しました。メール確認後にサインインしてください。"
            enqueueNotification(AppNotification(message: "確認メールを再送しました", actionLabel: nil, action: nil, autoDismissAfter: 4))
        case .failure(let message):
            profileStatusMessage = message
            enqueueNotification(AppNotification(message: message, actionLabel: nil, action: nil, autoDismissAfter: 4))
        }
    }

    func sendSharedTagPasswordRecovery(email: String) async {
        switch await services.sharedTagCloud.sendPasswordRecovery(email: email) {
        case .success:
            profileStatusMessage = "パスワード再設定メールを送信しました。"
            enqueueNotification(AppNotification(message: "パスワード再設定メールを送信しました", actionLabel: nil, action: nil, autoDismissAfter: 4))
        case .needsEmailConfirmation:
            profileStatusMessage = "パスワード再設定メールを送信しました。メールの案内に従ってください。"
            enqueueNotification(AppNotification(message: "パスワード再設定メールを送信しました", actionLabel: nil, action: nil, autoDismissAfter: 4))
        case .failure(let message):
            profileStatusMessage = message
            enqueueNotification(AppNotification(message: message, actionLabel: nil, action: nil, autoDismissAfter: 4))
        }
    }

    func signOutFromSharedTagCloud() async {
        do {
            try services.sharedTagCloud.signOut()
            await refreshSharedTagCloudState()
            await refreshEntitlements()
            profileStatusMessage = "サインアウトしました。プロフィールはこのiPhoneに残ります。"
            enqueueNotification(AppNotification(message: "共有タグクラウドからサインアウトしました", actionLabel: nil, action: nil, autoDismissAfter: 4))
            await reload()
        } catch {
            enqueueNotification(AppNotification(message: "共有タグクラウドからサインアウトできませんでした", actionLabel: nil, action: nil, autoDismissAfter: 4))
        }
    }

    @discardableResult
    func syncSharedTagCloud(showFailureNotification: Bool = true) async -> Bool {
        let success = await services.sharedTagCloud.syncCurrentSession()
        await refreshSharedTagCloudState()
        if success {
            await processMetadataBacklog()
        }
        if !success && showFailureNotification {
            enqueueNotification(AppNotification(message: "共有タグの同期に失敗しました", actionLabel: nil, action: nil, autoDismissAfter: 4))
        }
        return success
    }

    func acceptPendingInvite() async {
        guard let pendingInviteRecord else {
            enqueueNotification(AppNotification(message: "保留中の招待はありません", actionLabel: nil, action: nil, autoDismissAfter: 4))
            return
        }
        switch await services.sharedTagCloud.acceptInvite(inviteToken: pendingInviteRecord.inviteToken) {
        case .accepted(let displayName, let inviteType, let role):
            try? services.pendingInviteStore.clear()
            self.pendingInviteRecord = nil
            await refreshSharedTagCloudState()
            let roleText = role?.displayName ?? "メンバー"
            let targetText = inviteType == .group ? "グループ" : "共有タグ"
            enqueueNotification(
                AppNotification(
                    message: "\(targetText)「\(displayName)」に \(roleText) として参加しました",
                    actionLabel: nil,
                    action: nil,
                    autoDismissAfter: 5
                )
            )
        case .authRequired:
            try? services.pendingInviteStore.save(inviteToken: pendingInviteRecord.inviteToken)
            self.pendingInviteRecord = try? services.pendingInviteStore.load()
            enqueueNotification(AppNotification(message: "先に共有タグクラウドへサインインしてください", actionLabel: nil, action: nil, autoDismissAfter: 4))
        case .invalidInvite:
            try? services.pendingInviteStore.clear()
            self.pendingInviteRecord = nil
            enqueueNotification(AppNotification(message: "招待リンクが無効か期限切れでした", actionLabel: nil, action: nil, autoDismissAfter: 4))
        case .failure(let message):
            enqueueNotification(AppNotification(message: message, actionLabel: nil, action: nil, autoDismissAfter: 4))
        }
    }

    private func acceptPendingInviteIfPossible() async {
        guard pendingInviteRecord != nil else { return }
        await acceptPendingInvite()
    }

    func previewInvite(inviteToken: String) async -> SharedTagInvitePreviewResult {
        await services.sharedTagCloud.previewInvite(inviteToken: inviteToken)
    }

    func acceptInvite(inviteToken: String) async -> SharedTagInviteAcceptanceResult {
        let result = await services.sharedTagCloud.acceptInvite(inviteToken: inviteToken)
        switch result {
        case .accepted:
            inviteConfirmationToken = nil
            await refreshSharedTagCloudState()
            await reload()
        default:
            break
        }
        return result
    }

    func deleteSharedTagCloudAccount() async {
        switch await services.sharedTagCloud.deleteAccount() {
        case .success:
            try? services.pendingInviteStore.clear()
            pendingInviteRecord = nil
            await refreshSharedTagCloudState()
            await reload()
            profileStatusMessage = "共有タグアカウントを削除しました。"
            enqueueNotification(AppNotification(message: "共有タグクラウドのアカウントを削除しました", actionLabel: nil, action: nil, autoDismissAfter: 5))
        case .authRequired:
            enqueueNotification(AppNotification(message: "先に共有タグクラウドへサインインしてください", actionLabel: nil, action: nil, autoDismissAfter: 4))
        case .ownerTransferRequired:
            enqueueNotification(AppNotification(message: "共有タグ詳細の参加者からオーナー権限を移譲してから削除してください", actionLabel: nil, action: nil, autoDismissAfter: 5))
        case .failure(let message):
            enqueueNotification(AppNotification(message: message, actionLabel: nil, action: nil, autoDismissAfter: 4))
        }
    }

    func loadSharedTagsForEntry(entryID: Int64) -> [SharedTagSummary] {
        (try? services.sharedTagCloud.loadVisibleTagsForEntry(entryID: entryID)) ?? []
    }

    func prepareExportArchive(request: URLExportRequest) async throws -> PreparedExportArchive {
        _ = await syncSharedTagCloud(showFailureNotification: false)
        let entries = (try? services.repository.loadExportSnapshot()) ?? (activeEntries + archivedEntries)
        let sharedTagsByEntryID = Dictionary(
            uniqueKeysWithValues: entries.map { entry in
                (entry.id, loadSharedTagsForEntry(entryID: entry.id))
            }
        )
        let appVersion = Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "iOS"
        return try URLExportArchiveBuilder.prepareExport(
            request: request,
            entries: entries,
            localTags: localTags,
            localTagAssignments: localTagAssignments,
            sharedTagsByEntryID: sharedTagsByEntryID,
            appVersion: appVersion
        )
    }

    func loadEntriesForSharedTag(remoteTagID: String) -> [URLRecord] {
        (try? services.sharedTagCloud.loadEntriesForTag(remoteTagID: remoteTagID)) ?? []
    }

    func loadMembersForSharedTag(remoteTagID: String) -> [SharedTagMemberSummary] {
        (try? services.sharedTagCloud.loadMembersForTag(remoteTagID: remoteTagID)) ?? []
    }

    func loadSharedTag(remoteTagID: String) -> SharedTagSummary? {
        try? services.sharedTagCloud.loadTag(remoteTagID: remoteTagID)
    }

    func createSharedTag(name: String) async -> Bool {
        switch await services.sharedTagCloud.createTag(name: name) {
        case .success:
            await refreshSharedTagCloudState()
            enqueueNotification(AppNotification(message: "共有タグを作成しました", actionLabel: nil, action: nil, autoDismissAfter: 4))
            return true
        case .authRequired:
            enqueueNotification(AppNotification(message: "先に共有タグクラウドへサインインしてください", actionLabel: nil, action: nil, autoDismissAfter: 4))
            return false
        case .failure(let message):
            enqueueNotification(AppNotification(message: message, actionLabel: nil, action: nil, autoDismissAfter: 4))
            return false
        }
    }

    func createSharedTagGroup(name: String) async -> Bool {
        switch await services.sharedTagCloud.createGroup(name: name) {
        case .success:
            await refreshSharedTagCloudState()
            enqueueNotification(AppNotification(message: "グループを作成しました", actionLabel: nil, action: nil, autoDismissAfter: 4))
            return true
        case .authRequired:
            enqueueNotification(AppNotification(message: "先に共有タグクラウドへサインインしてください", actionLabel: nil, action: nil, autoDismissAfter: 4))
            return false
        case .failure(let message):
            enqueueNotification(AppNotification(message: message, actionLabel: nil, action: nil, autoDismissAfter: 4))
            return false
        }
    }

    func loadGroupMembers(remoteGroupID: String) -> [SharedTagGroupMemberSummary] {
        (try? services.sharedTagCloud.loadGroupMembers(remoteGroupID: remoteGroupID)) ?? []
    }

    func loadGroupTags(remoteGroupID: String) -> [SharedTagGroupTagSummary] {
        (try? services.sharedTagCloud.loadGroupTags(remoteGroupID: remoteGroupID)) ?? []
    }

    func addSharedTag(remoteTagID: String, toGroup remoteGroupID: String) async -> Bool {
        switch await services.sharedTagCloud.addTagToGroup(remoteGroupID: remoteGroupID, remoteTagID: remoteTagID) {
        case .success:
            await refreshSharedTagCloudState()
            enqueueNotification(AppNotification(message: "共有タグをグループに追加しました", actionLabel: nil, action: nil, autoDismissAfter: 4))
            return true
        case .authRequired:
            enqueueNotification(AppNotification(message: "先に共有タグクラウドへサインインしてください", actionLabel: nil, action: nil, autoDismissAfter: 4))
            return false
        case .failure(let message):
            enqueueNotification(AppNotification(message: message, actionLabel: nil, action: nil, autoDismissAfter: 4))
            return false
        }
    }

    func removeSharedTag(remoteTagID: String, fromGroup remoteGroupID: String) async -> Bool {
        switch await services.sharedTagCloud.removeTagFromGroup(remoteGroupID: remoteGroupID, remoteTagID: remoteTagID) {
        case .success:
            await refreshSharedTagCloudState()
            enqueueNotification(AppNotification(message: "共有タグをグループから外しました", actionLabel: nil, action: nil, autoDismissAfter: 4))
            return true
        case .authRequired:
            enqueueNotification(AppNotification(message: "先に共有タグクラウドへサインインしてください", actionLabel: nil, action: nil, autoDismissAfter: 4))
            return false
        case .failure(let message):
            enqueueNotification(AppNotification(message: message, actionLabel: nil, action: nil, autoDismissAfter: 4))
            return false
        }
    }

    func createInviteForSharedTagGroup(remoteGroupID: String, role: SharedTagMemberRole) async -> SharedTagInviteCreationResult {
        await services.sharedTagCloud.createGroupInvite(remoteGroupID: remoteGroupID, role: role)
    }

    func renameSharedTagGroup(remoteGroupID: String, name: String) async -> Bool {
        switch await services.sharedTagCloud.renameGroup(remoteGroupID: remoteGroupID, name: name) {
        case .success:
            await refreshSharedTagCloudState()
            enqueueNotification(AppNotification(message: "グループ名を更新しました", actionLabel: nil, action: nil, autoDismissAfter: 4))
            return true
        case .authRequired:
            enqueueNotification(AppNotification(message: "先に共有タグクラウドへサインインしてください", actionLabel: nil, action: nil, autoDismissAfter: 4))
            return false
        case .failure(let message):
            enqueueNotification(AppNotification(message: message, actionLabel: nil, action: nil, autoDismissAfter: 4))
            return false
        }
    }

    func deleteSharedTagGroup(remoteGroupID: String) async -> Bool {
        switch await services.sharedTagCloud.deleteGroup(remoteGroupID: remoteGroupID) {
        case .success:
            await refreshSharedTagCloudState()
            enqueueNotification(AppNotification(message: "グループを削除しました", actionLabel: nil, action: nil, autoDismissAfter: 4))
            return true
        case .authRequired:
            enqueueNotification(AppNotification(message: "先に共有タグクラウドへサインインしてください", actionLabel: nil, action: nil, autoDismissAfter: 4))
            return false
        case .failure(let message):
            enqueueNotification(AppNotification(message: message, actionLabel: nil, action: nil, autoDismissAfter: 4))
            return false
        }
    }

    func changeSharedTagGroupMemberRole(remoteGroupID: String, userID: String, role: SharedTagMemberRole) async -> Bool {
        switch await services.sharedTagCloud.changeGroupMemberRole(remoteGroupID: remoteGroupID, userID: userID, role: role) {
        case .success:
            await refreshSharedTagCloudState()
            enqueueNotification(AppNotification(message: "メンバー権限を変更しました", actionLabel: nil, action: nil, autoDismissAfter: 4))
            return true
        case .authRequired:
            enqueueNotification(AppNotification(message: "先に共有タグクラウドへサインインしてください", actionLabel: nil, action: nil, autoDismissAfter: 4))
            return false
        case .failure(let message):
            enqueueNotification(AppNotification(message: message, actionLabel: nil, action: nil, autoDismissAfter: 4))
            return false
        }
    }

    func transferSharedTagGroupOwnership(remoteGroupID: String, userID: String) async -> Bool {
        switch await services.sharedTagCloud.transferGroupOwnership(remoteGroupID: remoteGroupID, newOwnerUserID: userID) {
        case .success:
            await refreshSharedTagCloudState()
            enqueueNotification(AppNotification(message: "グループオーナーを移譲しました", actionLabel: nil, action: nil, autoDismissAfter: 4))
            return true
        case .authRequired:
            enqueueNotification(AppNotification(message: "先に共有タグクラウドへサインインしてください", actionLabel: nil, action: nil, autoDismissAfter: 4))
            return false
        case .failure(let message):
            enqueueNotification(AppNotification(message: message, actionLabel: nil, action: nil, autoDismissAfter: 4))
            return false
        }
    }

    func removeSharedTagGroupMember(remoteGroupID: String, userID: String) async -> Bool {
        switch await services.sharedTagCloud.removeGroupMember(remoteGroupID: remoteGroupID, userID: userID) {
        case .success:
            await refreshSharedTagCloudState()
            enqueueNotification(AppNotification(message: "メンバーを削除しました", actionLabel: nil, action: nil, autoDismissAfter: 4))
            return true
        case .authRequired:
            enqueueNotification(AppNotification(message: "先に共有タグクラウドへサインインしてください", actionLabel: nil, action: nil, autoDismissAfter: 4))
            return false
        case .failure(let message):
            enqueueNotification(AppNotification(message: message, actionLabel: nil, action: nil, autoDismissAfter: 4))
            return false
        }
    }

    func renameSharedTag(remoteTagID: String, name: String) async -> Bool {
        switch await services.sharedTagCloud.renameTag(remoteTagID: remoteTagID, name: name) {
        case .success:
            await refreshSharedTagCloudState()
            enqueueNotification(AppNotification(message: "共有タグ名を更新しました", actionLabel: nil, action: nil, autoDismissAfter: 4))
            return true
        case .authRequired:
            enqueueNotification(AppNotification(message: "先に共有タグクラウドへサインインしてください", actionLabel: nil, action: nil, autoDismissAfter: 4))
            return false
        case .failure(let message):
            enqueueNotification(AppNotification(message: message, actionLabel: nil, action: nil, autoDismissAfter: 4))
            return false
        }
    }

    func deleteSharedTag(remoteTagID: String) async -> Bool {
        switch await services.sharedTagCloud.deleteTag(remoteTagID: remoteTagID) {
        case .success:
            await refreshSharedTagCloudState()
            enqueueNotification(AppNotification(message: "共有タグを削除しました", actionLabel: nil, action: nil, autoDismissAfter: 4))
            return true
        case .authRequired:
            enqueueNotification(AppNotification(message: "先に共有タグクラウドへサインインしてください", actionLabel: nil, action: nil, autoDismissAfter: 4))
            return false
        case .failure(let message):
            enqueueNotification(AppNotification(message: message, actionLabel: nil, action: nil, autoDismissAfter: 4))
            return false
        }
    }

    func leaveSharedTag(remoteTagID: String) async -> Bool {
        switch await services.sharedTagCloud.leaveTag(remoteTagID: remoteTagID) {
        case .success:
            await refreshSharedTagCloudState()
            enqueueNotification(AppNotification(message: "共有タグから抜けました", actionLabel: nil, action: nil, autoDismissAfter: 4))
            return true
        case .authRequired:
            enqueueNotification(AppNotification(message: "先に共有タグクラウドへサインインしてください", actionLabel: nil, action: nil, autoDismissAfter: 4))
            return false
        case .failure(let message):
            enqueueNotification(AppNotification(message: message, actionLabel: nil, action: nil, autoDismissAfter: 4))
            return false
        }
    }

    func transferSharedTagOwnership(remoteTagID: String, newOwnerUserID: String) async -> Bool {
        switch await services.sharedTagCloud.transferOwnership(remoteTagID: remoteTagID, newOwnerUserID: newOwnerUserID) {
        case .success:
            await refreshSharedTagCloudState()
            enqueueNotification(AppNotification(message: "オーナー権限を移譲しました", actionLabel: nil, action: nil, autoDismissAfter: 4))
            return true
        case .authRequired:
            enqueueNotification(AppNotification(message: "先に共有タグクラウドへサインインしてください", actionLabel: nil, action: nil, autoDismissAfter: 4))
            return false
        case .failure(let message):
            enqueueNotification(AppNotification(message: message, actionLabel: nil, action: nil, autoDismissAfter: 4))
            return false
        }
    }

    func addEntry(_ entryID: Int64, toSharedTag remoteTagID: String) async -> Bool {
        switch await addEntryToSharedTag(entryID, remoteTagID: remoteTagID) {
        case .success:
            return true
        case .authRequired, .failure:
            return false
        }
    }

    func addEntryToSharedTag(_ entryID: Int64, remoteTagID: String) async -> SharedTagMutationResult {
        let result = await services.sharedTagCloud.assignEntry(remoteTagID: remoteTagID, entryID: entryID)
        switch result {
        case .success:
            await refreshSharedTagCloudState()
            await reload()
            enqueueNotification(AppNotification(message: "共有タグに追加しました", actionLabel: nil, action: nil, autoDismissAfter: 4))
            return result
        case .authRequired:
            enqueueNotification(AppNotification(message: "先に共有タグクラウドへサインインしてください", actionLabel: nil, action: nil, autoDismissAfter: 4))
            return result
        case .failure(let message):
            enqueueNotification(AppNotification(message: message, actionLabel: nil, action: nil, autoDismissAfter: 4))
            return result
        }
    }

    func removeEntry(_ entryID: Int64, fromSharedTag remoteTagID: String) async -> Bool {
        switch await services.sharedTagCloud.removeEntry(remoteTagID: remoteTagID, entryID: entryID) {
        case .success:
            await refreshSharedTagCloudState()
            await reload()
            enqueueNotification(AppNotification(message: "このURLを共有タグから外しました", actionLabel: nil, action: nil, autoDismissAfter: 4))
            return true
        case .authRequired:
            enqueueNotification(AppNotification(message: "先に共有タグクラウドへサインインしてください", actionLabel: nil, action: nil, autoDismissAfter: 4))
            return false
        case .failure(let message):
            enqueueNotification(AppNotification(message: message, actionLabel: nil, action: nil, autoDismissAfter: 4))
            return false
        }
    }

    func createInviteForSharedTag(remoteTagID: String) async -> SharedTagInviteCreationResult {
        await services.sharedTagCloud.createInvite(remoteTagID: remoteTagID)
    }

    func requestSharedTagSync(authUserID: String) {
        Task {
            await services.sharedTagSyncExecutor.enqueue(authUserID: authUserID)
        }
    }

    func performNotificationAction() {
        guard let notification = currentNotification else { return }
        dismissCurrentNotification()
        guard let action = notification.action else { return }

        switch action {
        case .openEntry(let entryID):
            openEntry(entryID)
        case .openArchive:
            selectedTab = .archive
        case .undoPendingDelete(let entryID):
            Task {
                _ = try? services.repository.restore(entryID: entryID)
                cancelDeleteTimer(entryID: entryID)
                await reload()
            }
        case .undoPendingDeleteBatch(let entryIDs):
            Task {
                for entryID in entryIDs {
                    _ = try? services.repository.restore(entryID: entryID)
                    cancelDeleteTimer(entryID: entryID)
                }
                await reload()
            }
        case .undoArchive(let entryID):
            Task {
                _ = try? services.repository.unarchive(entryID: entryID)
                await reload()
            }
        case .undoArchiveBatch(let entryIDs):
            Task {
                for entryID in entryIDs {
                    _ = try? services.repository.unarchive(entryID: entryID)
                }
                await reload()
            }
        case .undoTitle(let entryID, let oldTitle):
            Task {
                _ = try? services.repository.restoreUserTitle(entryID: entryID, oldTitle: oldTitle)
                await reload()
            }
        }
    }

    func dismissCurrentNotification() {
        notificationDismissTask?.cancel()
        notificationDismissTask = nil
        currentNotification = nil
        showNextNotificationIfNeeded()
    }

    func openEntry(_ entryID: Int64) {
        if selectedTab != .main,
           archivedEntries.contains(where: { $0.id == entryID }) {
            selectedTab = .archive
        }
        navigationPath.append(entryID)
    }

    func entry(for entryID: Int64) -> URLRecord? {
        if let visibleEntry = activeEntries.first(where: { $0.id == entryID }) ?? archivedEntries.first(where: { $0.id == entryID }) {
            return visibleEntry
        }
        return try? services.repository.loadEntry(id: entryID)
    }

    private func handleSaveResult(_ saveResult: SaveResult, degradationNotice: ShareDegradationNotice?) async {
        if saveResult.shouldScheduleMetadata, let entryID = saveResult.entryID {
            AppBackgroundScheduler.schedule()
            Task {
                _ = await services.metadataCoordinator.enqueue(entryID: entryID)
                await reload()
            }
        }

        switch saveResult.result {
        case .batchProcessed:
            break
        case .created:
            enqueueNotification(AppNotification(message: "保存しました", actionLabel: nil, action: nil, autoDismissAfter: 3))
        case .duplicateActive:
            enqueueNotification(
                AppNotification(
                    message: "このURLはすでに保存済みです",
                    actionLabel: "見る",
                    action: saveResult.entryID.map(AppNotification.Action.openEntry),
                    autoDismissAfter: 5
                )
            )
        case .duplicateArchived:
            enqueueNotification(
                AppNotification(
                    message: "このURLはアーカイブ済みです",
                    actionLabel: "見る",
                    action: saveResult.entryID.map(AppNotification.Action.openEntry) ?? .openArchive,
                    autoDismissAfter: 5
                )
            )
        case .restoredFromPendingDelete:
            if let entryID = saveResult.entryID {
                cancelDeleteTimer(entryID: entryID)
            }
            enqueueNotification(AppNotification(message: "削除を取り消して復元しました", actionLabel: nil, action: nil, autoDismissAfter: 3))
        case .inputTooLarge:
            enqueueNotification(AppNotification(message: "共有内容が長すぎるため処理できませんでした", actionLabel: nil, action: nil, autoDismissAfter: 4))
        case .saveFailed:
            enqueueNotification(AppNotification(message: "保存できませんでした", actionLabel: nil, action: nil, autoDismissAfter: 3))
        case .invalidURL:
            enqueueNotification(AppNotification(message: "有効なURLではありませんでした", actionLabel: nil, action: nil, autoDismissAfter: 3))
        case .noURLFound:
            enqueueNotification(AppNotification(message: "URLが見つかりませんでした", actionLabel: nil, action: nil, autoDismissAfter: 3))
        }

        if degradationNotice == .truncatedToFirstURL {
            enqueueNotification(
                AppNotification(
                    message: "共有内容に複数URLが含まれていたため、1件目のみ保存しました",
                    actionLabel: nil,
                    action: nil,
                    autoDismissAfter: 4
                )
            )
        } else if degradationNotice == .truncatedToMaxURLs {
            enqueueNotification(
                AppNotification(
                    message: "共有内容に多数のURLが含まれていたため、先頭\(URLRules.maxBatchSaveURLsPerIntake)件のみ処理しました",
                    actionLabel: nil,
                    action: nil,
                    autoDismissAfter: 4
                )
            )
        }
        await reload()
    }

    private func handleShareReport(_ report: ShareHandoffReport) {
        switch report.result {
        case .batchProcessed:
            if let summary = report.batchSummary {
                enqueueNotification(
                    AppNotification(
                        message: "\(summary.total)件を処理しました（新規\(summary.created) / 既存\(summary.duplicate) / 復元\(summary.restored) / 失敗\(summary.failed)）",
                        actionLabel: nil,
                        action: nil,
                        autoDismissAfter: 5
                    )
                )
            }
        case .created:
            enqueueNotification(AppNotification(message: "保存しました", actionLabel: nil, action: nil, autoDismissAfter: 3))
        case .duplicateActive:
            enqueueNotification(
                AppNotification(
                    message: "このURLはすでに保存済みです",
                    actionLabel: "見る",
                    action: report.entryID.map(AppNotification.Action.openEntry),
                    autoDismissAfter: 5
                )
            )
        case .duplicateArchived:
            enqueueNotification(
                AppNotification(
                    message: "このURLはアーカイブ済みです",
                    actionLabel: "見る",
                    action: report.entryID.map(AppNotification.Action.openEntry) ?? .openArchive,
                    autoDismissAfter: 5
                )
            )
        case .restoredFromPendingDelete:
            enqueueNotification(AppNotification(message: "削除を取り消して復元しました", actionLabel: nil, action: nil, autoDismissAfter: 3))
        case .inputTooLarge:
            enqueueNotification(AppNotification(message: "共有内容が長すぎるため処理できませんでした", actionLabel: nil, action: nil, autoDismissAfter: 4))
        case .saveFailed:
            enqueueNotification(AppNotification(message: "保存できませんでした", actionLabel: nil, action: nil, autoDismissAfter: 3))
        case .invalidURL:
            enqueueNotification(AppNotification(message: "有効なURLではありませんでした", actionLabel: nil, action: nil, autoDismissAfter: 3))
        case .noURLFound:
            enqueueNotification(AppNotification(message: "URLが見つかりませんでした", actionLabel: nil, action: nil, autoDismissAfter: 3))
        }

        if report.degradationNotice == .truncatedToFirstURL {
            enqueueNotification(
                AppNotification(
                    message: "共有内容に複数URLが含まれていたため、1件目のみ保存しました",
                    actionLabel: nil,
                    action: nil,
                    autoDismissAfter: 4
                )
            )
        } else if report.degradationNotice == .truncatedToMaxURLs {
            enqueueNotification(
                AppNotification(
                    message: "共有内容に多数のURLが含まれていたため、先頭\(URLRules.maxBatchSaveURLsPerIntake)件のみ処理しました",
                    actionLabel: nil,
                    action: nil,
                    autoDismissAfter: 4
                )
            )
        }
    }

    private func enqueueNotification(_ notification: AppNotification) {
        notificationQueue.append(notification)
        if currentNotification == nil {
            showNextNotificationIfNeeded()
        }
    }

    private func showNextNotificationIfNeeded() {
        guard currentNotification == nil, !notificationQueue.isEmpty else { return }
        let next = notificationQueue.removeFirst()
        currentNotification = next

        if let autoDismissAfter = next.autoDismissAfter {
            notificationDismissTask = Task { [weak self] in
                let nanoseconds = UInt64(max(autoDismissAfter, 0) * 1_000_000_000)
                try? await Task.sleep(nanoseconds: nanoseconds)
                await MainActor.run {
                    if self?.currentNotification?.id == next.id {
                        self?.dismissCurrentNotification()
                    }
                }
            }
        }
    }

    private func scheduleDeleteTimersForPersistedEntries() async {
        let pendingEntries = (try? services.repository.loadPendingDeleteEntries()) ?? []
        for entry in pendingEntries {
            guard let due = entry.pendingDeletionUntil else { continue }
            scheduleDeleteTimer(entryID: entry.id, due: due)
        }
    }

    private func scheduleDeleteTimer(entryID: Int64, due: Date) {
        cancelDeleteTimer(entryID: entryID)
        deleteTimers[entryID] = Task { [weak self] in
            let seconds = max(0, due.timeIntervalSinceNow)
            try? await Task.sleep(nanoseconds: UInt64(seconds * 1_000_000_000))
            try? self?.services.repository.finalizePendingDelete(entryID: entryID)
            await self?.reload()
            await MainActor.run {
                self?.deleteTimers[entryID] = nil
            }
        }
    }

    private func cancelDeleteTimer(entryID: Int64) {
        deleteTimers.removeValue(forKey: entryID)?.cancel()
    }
}

private enum IncomingURLRoute {
    case authCallback
    case invite(String)
    case promo(String)
    case tag(String)
    case save(String, ShareDegradationNotice?)
    case unknown

    init(url: URL) {
        let scheme = url.scheme?.lowercased()
        let host = url.host?.lowercased()
        let token = url.path.trimmingCharacters(in: CharacterSet(charactersIn: "/"))
        if scheme == "http" || scheme == "https" {
            let pathComponents = url.pathComponents.filter { $0 != "/" }
            if pathComponents.count == 2, pathComponents.first?.lowercased() == "invite" {
                self = .invite(pathComponents[1])
            } else if pathComponents.count == 1, pathComponents.first?.lowercased() == "promo" {
                self = .promo(Self.promoCode(from: url))
            } else {
                self = .unknown
            }
            return
        }

        guard scheme == "urlsaver" else {
            self = .unknown
            return
        }

        switch host {
        case "auth" where url.path == "/callback":
            self = .authCallback
        case "invite":
            self = .invite(token)
        case "promo":
            self = .promo(Self.promoCode(from: url))
        case "tag":
            self = .tag(token)
        case "save":
            let components = URLComponents(url: url, resolvingAgainstBaseURL: false)
            let rawURL = components?.queryItems?.first(where: { $0.name == "url" })?.value
            let degradation = components?.queryItems?
                .first(where: { $0.name == "degradation" })?
                .value
                .flatMap(ShareDegradationNotice.init(rawValue:))
            if let rawURL, !rawURL.isEmpty {
                self = .save(rawURL, degradation)
            } else {
                self = .unknown
            }
        default:
            self = .unknown
        }
    }

    private static func promoCode(from url: URL) -> String {
        if let components = URLComponents(url: url, resolvingAgainstBaseURL: false),
           let code = components.queryItems?.first(where: { $0.name == "code" })?.value,
           !code.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            return code.trimmingCharacters(in: .whitespacesAndNewlines)
        }
        guard let fragment = url.fragment else { return "" }
        let fragmentComponents = URLComponents(string: "urlsaver://promo?\(fragment)")
        return fragmentComponents?.queryItems?
            .first(where: { $0.name == "code" })?
            .value?
            .trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
    }
}
