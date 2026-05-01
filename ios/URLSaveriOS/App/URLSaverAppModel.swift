import Foundation
import SwiftUI

enum RootTab: Hashable {
    case main
    case archive
}

struct AppNotification: Identifiable, Equatable {
    enum Action: Equatable {
        case openEntry(Int64)
        case openArchive
        case undoPendingDelete(Int64)
        case undoArchive(Int64)
        case undoTitle(Int64, String?)
    }

    let id = UUID()
    let message: String
    let actionLabel: String?
    let action: Action?
    let autoDismissAfter: TimeInterval?
}

@MainActor
final class URLSaverAppModel: ObservableObject {
    @Published private(set) var activeEntries: [URLRecord] = []
    @Published private(set) var archivedEntries: [URLRecord] = []
    @Published private(set) var profile: UserProfile = .empty
    @Published private(set) var pendingInviteRecord: PendingInviteRecord?
    @Published private(set) var sharedTagCloudState = SharedTagCloudState(isConfigured: false, isSignedIn: false, signedInEmail: nil)
    @Published private(set) var entitlements: FeatureEntitlements = LaunchStandardPlan.entitlements
    @Published private(set) var sharedTags: [SharedTagSummary] = []
    @Published private(set) var localTags: [LocalTagSummary] = []
    @Published private(set) var localTagAssignments: [Int64: Set<Int64>] = [:]
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
        await scheduleDeleteTimersForPersistedEntries()
        await consumeShareHandoffReport()
        startPostBootstrapRefresh()
    }

    private func startPostBootstrapRefresh() {
        Task { [weak self] in
            guard let self else { return }
            let syncSucceeded = await self.syncSharedTagCloud(showFailureNotification: false)
            if !syncSucceeded {
                await self.processMetadataBacklog()
            }
        }
    }

    func reload() async {
        activeEntries = (try? services.repository.observeActiveSnapshot()) ?? []
        archivedEntries = (try? services.repository.observeArchiveSnapshot()) ?? []
        localTags = (try? services.repository.loadLocalTags()) ?? []
        localTagAssignments = (try? services.repository.loadLocalTagAssignments()) ?? [:]
    }

    func processMetadataBacklog() async {
        AppBackgroundScheduler.schedule()
        _ = await services.metadataCoordinator.processBacklog(limit: 60)
        await reload()
    }

    func refreshEntitlements() async {
        entitlements = await services.entitlementService.refreshForCurrentSession()
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

    func manualSave(input: String, localTagIDs: Set<Int64> = []) async -> ShareSaveResult? {
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
        case .invite(let token):
            guard !token.isEmpty else {
                enqueueNotification(AppNotification(message: "共有招待リンクを開けませんでした", actionLabel: nil, action: nil, autoDismissAfter: 4))
                return
            }
            do {
                try services.pendingInviteStore.save(inviteToken: token)
                pendingInviteRecord = try services.pendingInviteStore.load()
                enqueueNotification(AppNotification(message: "共有招待を受け取りました", actionLabel: nil, action: nil, autoDismissAfter: 4))
            } catch {
                enqueueNotification(AppNotification(message: "共有招待を保存できませんでした", actionLabel: nil, action: nil, autoDismissAfter: 4))
            }
        case .save(let rawURL, let degradationNotice):
            guard let saveResult = try? services.repository.saveFromResolvedURL(rawURL) else {
                enqueueNotification(AppNotification(message: "保存できませんでした", actionLabel: nil, action: nil, autoDismissAfter: 3))
                return
            }
            await handleSaveResult(saveResult, degradationNotice: degradationNotice)
        case .tag, .unknown:
            break
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

    func refreshSharedTagCloudState() async {
        sharedTagCloudState = services.sharedTagCloud.state
        sharedTags = (try? services.sharedTagCloud.loadVisibleTags()) ?? []
    }

    func clearProfileStatusMessage() {
        profileStatusMessage = nil
    }

    func saveProfile(displayName: String) {
        do {
            var updated = profile
            updated.displayName = String(displayName.trimmingCharacters(in: .whitespacesAndNewlines).prefix(40))
            updated.updatedAt = Date()
            try services.profileStore.save(updated)
            profile = updated
            profileStatusMessage = "プロフィールを保存しました"
            enqueueNotification(AppNotification(message: "プロフィールを保存しました", actionLabel: nil, action: nil, autoDismissAfter: 3))
        } catch {
            profileStatusMessage = "プロフィールを保存できませんでした"
            enqueueNotification(AppNotification(message: "プロフィールを保存できませんでした", actionLabel: nil, action: nil, autoDismissAfter: 4))
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
        return "プロフィール未設定"
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
        case .accepted(let tagName, let role):
            try? services.pendingInviteStore.clear()
            self.pendingInviteRecord = nil
            await refreshSharedTagCloudState()
            let roleText = role?.displayName ?? "メンバー"
            enqueueNotification(
                AppNotification(
                    message: "共有タグ「\(tagName)」に \(roleText) として参加しました",
                    actionLabel: nil,
                    action: nil,
                    autoDismissAfter: 5
                )
            )
        case .authRequired:
            enqueueNotification(AppNotification(message: "先に共有タグクラウドへサインインしてください", actionLabel: nil, action: nil, autoDismissAfter: 4))
        case .invalidInvite:
            try? services.pendingInviteStore.clear()
            self.pendingInviteRecord = nil
            enqueueNotification(AppNotification(message: "招待リンクが無効か期限切れでした", actionLabel: nil, action: nil, autoDismissAfter: 4))
        case .failure(let message):
            enqueueNotification(AppNotification(message: message, actionLabel: nil, action: nil, autoDismissAfter: 4))
        }
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
            enqueueNotification(AppNotification(message: "他のメンバーがいる共有タグのオーナー権限を移譲してから削除してください", actionLabel: nil, action: nil, autoDismissAfter: 5))
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

    func addEntry(_ entryID: Int64, toSharedTag remoteTagID: String) async -> Bool {
        switch await services.sharedTagCloud.assignEntry(remoteTagID: remoteTagID, entryID: entryID) {
        case .success:
            await refreshSharedTagCloudState()
            await reload()
            enqueueNotification(AppNotification(message: "共有タグに追加しました", actionLabel: nil, action: nil, autoDismissAfter: 4))
            return true
        case .authRequired:
            enqueueNotification(AppNotification(message: "先に共有タグクラウドへサインインしてください", actionLabel: nil, action: nil, autoDismissAfter: 4))
            return false
        case .failure(let message):
            enqueueNotification(AppNotification(message: message, actionLabel: nil, action: nil, autoDismissAfter: 4))
            return false
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
        case .undoArchive(let entryID):
            Task {
                _ = try? services.repository.unarchive(entryID: entryID)
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
    case invite(String)
    case tag(String)
    case save(String, ShareDegradationNotice?)
    case unknown

    init(url: URL) {
        guard url.scheme?.lowercased() == "urlsaver" else {
            self = .unknown
            return
        }

        let host = url.host?.lowercased()
        let token = url.path.trimmingCharacters(in: CharacterSet(charactersIn: "/"))
        switch host {
        case "invite":
            self = .invite(token)
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
}
