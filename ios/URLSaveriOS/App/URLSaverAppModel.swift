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

struct ManualTagImportPreview: Identifiable, Equatable, Sendable {
    let id: UUID
    let tagName: String
    let validURLCount: Int
    let payload: TagSharePayload

    init(tagName: String, validURLCount: Int, payload: TagSharePayload) {
        self.id = UUID()
        self.tagName = tagName
        self.validURLCount = validURLCount
        self.payload = payload
    }
}

enum ManualTagImportPreparation: Equatable {
    case notTagPayload
    case ready(ManualTagImportPreview)
    case invalid(String)

    static func prepare(input: String) -> ManualTagImportPreparation {
        let trimmed = input.trimmingCharacters(in: .whitespacesAndNewlines)
        guard trimmed.first == "{" else {
            return .notTagPayload
        }

        guard let data = trimmed.data(using: .utf8) else {
            return .invalid("タグデータを読み込めませんでした")
        }
        guard data.count <= URLRules.maxInputTextBytes else {
            return .invalid("タグデータが大きすぎます。256KB以内で貼り付けてください")
        }
        guard let payload = try? JSONDecoder().decode(TagSharePayload.self, from: data) else {
            return .notTagPayload
        }
        guard payload.urlsaverVersion == 1 else {
            return .invalid("対応していないタグデータです")
        }
        guard payload.urls.count <= URLRules.maxBatchSaveURLsPerIntake else {
            return .invalid("タグデータのURLは\(URLRules.maxBatchSaveURLsPerIntake)件以内にしてください")
        }
        guard let tagName = normalizedTagName(payload.tag) else {
            return .invalid("タグ名が空または長すぎます")
        }

        let normalizedURLs = payload.urls.compactMap { URLRules.normalize($0.url) }
        guard !normalizedURLs.isEmpty else {
            return .invalid("タグデータ内に有効なURLがありません")
        }

        let sanitizedPayload = TagSharePayload(
            urlsaverVersion: 1,
            tag: tagName,
            exportedAt: payload.exportedAt,
            urls: normalizedURLs.map { TagShareURL(url: $0) }
        )
        return .ready(
            ManualTagImportPreview(
                tagName: tagName,
                validURLCount: normalizedURLs.count,
                payload: sanitizedPayload
            )
        )
    }

    static func importConfirmed(
        _ preview: ManualTagImportPreview,
        repository: URLRepository
    ) throws -> TagImportResult {
        let sanitizedPayload = TagSharePayload(
            urlsaverVersion: 1,
            tag: preview.tagName,
            exportedAt: preview.payload.exportedAt,
            urls: preview.payload.urls.compactMap { item in
                guard let normalizedURL = URLRules.normalize(item.url) else { return nil }
                return TagShareURL(url: normalizedURL)
            }
        )
        return try repository.importLocalTagPayload(sanitizedPayload)
    }

    private static func normalizedTagName(_ rawName: String) -> String? {
        let trimmed = rawName.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty, trimmed.count <= 40 else { return nil }
        let normalized = trimmed
            .split(whereSeparator: { $0.isWhitespace })
            .joined(separator: " ")
        return normalized.isEmpty ? nil : normalized
    }
}

enum ManualSaveOutcome: Equatable {
    case inputError(ShareSaveResult)
    case tagImportConfirmation(ManualTagImportPreview)
    case tagImportError(String)
    case saved(SaveResult)
    case completed
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
    var operationEnabled: Bool = false
    var enabled: Bool = false
    var contentFetchEnabled: Bool = false
    var eligibleCount: Int = 0
    var excludedCount: Int = 0
    var exclusionReasons: [String: Int] = [:]
    var lastSyncedAt: Date?
    var isLoading: Bool = false
    var status: ChatGptPersonalLinkSyncStatus = .idle
    var pendingConfirmation: Bool?
    var lastErrorMessage: String?
}

enum ChatGptExportPreparationGate {
    static func requireSuccessfulSync(_ succeeded: Bool) throws {
        guard succeeded else {
            throw URLExportError.invalidRequest("共有タグの同期に失敗したため、ChatGPT用ZIPを作成しませんでした。通信状態を確認して、もう一度お試しください。")
        }
    }

    static func loadSharedTagsByEntryID(
        entries: [URLRecord],
        bulkLookup: ([URLRecord]) throws -> [Int64: [SharedTagSummary]]
    ) throws -> [Int64: [SharedTagSummary]] {
        do {
            let result = try bulkLookup(entries)
            guard Set(result.keys) == Set(entries.map(\.id)) else {
                throw URLExportError.invalidRequest("共有タグの状態を確認できませんでした。")
            }
            return result
        } catch {
            throw URLExportError.invalidRequest("共有タグの状態を確認できなかったため、ChatGPT用ZIPを作成しませんでした。もう一度お試しください。")
        }
    }
}

struct SavedAppMediaFile: Identifiable, Equatable, Sendable {
    let id: String
    let fileURL: URL
    let mediaType: String
    let fileName: String
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
    @Published private(set) var chatGptPersonalLinkSettings = ChatGptPersonalLinkSettings()
    @Published private(set) var isUpdatingChatGptPersonalLinkSync = false
    @Published private(set) var mediaSaveRevision = 0
    @Published private(set) var profileStatusMessage: String?
    @Published private(set) var sharedTagAccountLocalCleanupState: SharedTagAccountLocalCleanupState?
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
        sharedTagAccountLocalCleanupState = services.sharedTagCloud.localAccountCleanupState
    }

    func bootstrapIfNeeded() async {
        guard !hasBootstrapped else { return }
        hasBootstrapped = true
        sharedTagAccountLocalCleanupState = services.sharedTagCloud.localAccountCleanupState
        profile = (try? services.profileStore.load()) ?? .empty
        pendingInviteRecord = try? services.pendingInviteStore.load()
        try? services.repository.cleanupExpiredPendingDeletes()
        services.storePurchaseService.startTransactionUpdates()
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
    }

    func processMetadataBacklog() async {
        AppBackgroundScheduler.schedule()
        _ = await services.metadataCoordinator.processBacklog(limit: 60)
        await reload()
    }

    func refreshEntitlements() async {
        await services.storePurchaseService.refreshCurrentEntitlements()
        entitlements = await services.entitlementService.refreshForCurrentSession()
    }

    func refreshChatGptPersonalLinkSettings() {
        let defaults = UserDefaults.standard
        let eligibility = (try? services.sharedTagCloud.chatGptPersonalLinkEligibility()) ?? .empty
        let operationEnabled = services.sharedTagCloud.config.isPersonalLinkSyncConfigured
        chatGptPersonalLinkSettings = ChatGptPersonalLinkSettings(
            operationEnabled: operationEnabled,
            enabled: operationEnabled && defaults.bool(forKey: "chatgpt_personal_link_sync.enabled"),
            contentFetchEnabled: false,
            eligibleCount: eligibility.eligibleCount,
            excludedCount: eligibility.excludedCount,
            exclusionReasons: eligibility.exclusionReasons,
            lastSyncedAt: defaults.object(forKey: "chatgpt_personal_link_sync.last_synced_at") as? Date,
            lastErrorMessage: defaults.string(forKey: "chatgpt_personal_link_sync.last_error_message")
        )
    }

    func requestChatGptPersonalLinkSyncChange(enabled: Bool) {
        guard chatGptPersonalLinkSettings.operationEnabled else { return }
        chatGptPersonalLinkSettings.pendingConfirmation = enabled
    }

    func cancelChatGptPersonalLinkSyncChange() {
        chatGptPersonalLinkSettings.pendingConfirmation = nil
    }

    func setChatGptPersonalLinkSync(enabled: Bool, contentFetchEnabled: Bool = false) async {
        _ = contentFetchEnabled
        requestChatGptPersonalLinkSyncChange(enabled: enabled)
        await confirmChatGptPersonalLinkSyncChange()
    }

    func confirmChatGptPersonalLinkSyncChange() async {
        guard let enabled = chatGptPersonalLinkSettings.pendingConfirmation else { return }
        chatGptPersonalLinkSettings.pendingConfirmation = nil
        guard chatGptPersonalLinkSettings.operationEnabled else { return }
        guard !isUpdatingChatGptPersonalLinkSync else { return }
        isUpdatingChatGptPersonalLinkSync = true
        chatGptPersonalLinkSettings.isLoading = true
        chatGptPersonalLinkSettings.status = .loading
        defer { isUpdatingChatGptPersonalLinkSync = false }
        do {
            let result = try await services.sharedTagCloud.setChatGptPersonalLinkSync(enabled: enabled)
            let defaults = UserDefaults.standard
            defaults.set(enabled, forKey: "chatgpt_personal_link_sync.enabled")
            defaults.set(false, forKey: "chatgpt_personal_link_sync.content_fetch_enabled")
            defaults.set(Date(), forKey: "chatgpt_personal_link_sync.last_synced_at")
            defaults.removeObject(forKey: "chatgpt_personal_link_sync.last_error_message")
            updateChatGptPersonalLinkEligibility()
            chatGptPersonalLinkSettings.enabled = enabled
            chatGptPersonalLinkSettings.contentFetchEnabled = false
            chatGptPersonalLinkSettings.lastSyncedAt = defaults.object(forKey: "chatgpt_personal_link_sync.last_synced_at") as? Date
            chatGptPersonalLinkSettings.lastErrorMessage = nil
            chatGptPersonalLinkSettings.status = result.status
            chatGptPersonalLinkSettings.isLoading = false
            enqueueNotification(AppNotification(message: enabled ? "ChatGPT連携を有効にしました" : "ChatGPT連携を無効にしました", actionLabel: nil, action: nil, autoDismissAfter: 4))
        } catch SharedTagCloudError.authRequired {
            chatGptPersonalLinkSettings.status = .failure("りんばむのアカウントでサインインしてください")
            chatGptPersonalLinkSettings.lastErrorMessage = "りんばむのアカウントでサインインしてください"
            chatGptPersonalLinkSettings.isLoading = false
            enqueueNotification(AppNotification(message: "ChatGPT連携にはサインインが必要です", actionLabel: nil, action: nil, autoDismissAfter: 4))
        } catch {
            let message = (error as? SharedTagCloudError)?.userMessage ?? error.localizedDescription
            UserDefaults.standard.set(message, forKey: "chatgpt_personal_link_sync.last_error_message")
            chatGptPersonalLinkSettings.status = .failure(message)
            chatGptPersonalLinkSettings.lastErrorMessage = message
            chatGptPersonalLinkSettings.isLoading = false
            enqueueNotification(AppNotification(message: message, actionLabel: nil, action: nil, autoDismissAfter: 5))
        }
    }

    func syncChatGptPersonalLinksNow() async {
        guard chatGptPersonalLinkSettings.operationEnabled,
              chatGptPersonalLinkSettings.enabled,
              !isUpdatingChatGptPersonalLinkSync else { return }
        isUpdatingChatGptPersonalLinkSync = true
        chatGptPersonalLinkSettings.isLoading = true
        chatGptPersonalLinkSettings.status = .loading
        defer { isUpdatingChatGptPersonalLinkSync = false }
        do {
            let result = try await services.sharedTagCloud.syncChatGptPersonalLinks()
            UserDefaults.standard.set(Date(), forKey: "chatgpt_personal_link_sync.last_synced_at")
            UserDefaults.standard.removeObject(forKey: "chatgpt_personal_link_sync.last_error_message")
            updateChatGptPersonalLinkEligibility()
            chatGptPersonalLinkSettings.lastSyncedAt = UserDefaults.standard.object(forKey: "chatgpt_personal_link_sync.last_synced_at") as? Date
            chatGptPersonalLinkSettings.lastErrorMessage = nil
            chatGptPersonalLinkSettings.status = result.status
            chatGptPersonalLinkSettings.isLoading = false
        } catch SharedTagCloudError.authRequired {
            chatGptPersonalLinkSettings.status = .failure("りんばむのアカウントでサインインしてください")
            chatGptPersonalLinkSettings.lastErrorMessage = "りんばむのアカウントでサインインしてください"
            chatGptPersonalLinkSettings.isLoading = false
        } catch {
            let message = (error as? SharedTagCloudError)?.userMessage ?? error.localizedDescription
            UserDefaults.standard.set(message, forKey: "chatgpt_personal_link_sync.last_error_message")
            chatGptPersonalLinkSettings.status = .failure(message)
            chatGptPersonalLinkSettings.lastErrorMessage = message
            chatGptPersonalLinkSettings.isLoading = false
        }
    }

    func retryChatGptPersonalLinkSync() async {
        await syncChatGptPersonalLinksNow()
    }

    private func updateChatGptPersonalLinkEligibility() {
        let eligibility = (try? services.sharedTagCloud.chatGptPersonalLinkEligibility()) ?? .empty
        chatGptPersonalLinkSettings.eligibleCount = eligibility.eligibleCount
        chatGptPersonalLinkSettings.excludedCount = eligibility.excludedCount
        chatGptPersonalLinkSettings.exclusionReasons = eligibility.exclusionReasons
    }

    func aiTransparencyPreview() -> AiSendPreview? {
        #if DEBUG
        guard AiTransparencyFeature.isEnabled else { return nil }
        let namesByID = Dictionary(uniqueKeysWithValues: localTags.map { ($0.id, $0.name) })
        let sources = activeEntries.map { entry in
            let tagNames = (localTagAssignments[entry.id] ?? [])
                .compactMap { namesByID[$0] }
            return AiTransparencyPolicy.source(
                for: entry,
                publicSafeID: AiTransparencyPolicy.publicSafeID(for: entry),
                tagNames: tagNames
            )
        }
        return AiTransparencyPolicy.buildPreview(
            actionKind: .personalLinkSync,
            destination: "端末内モック",
            sources: sources
        )
        #else
        return nil
        #endif
    }

    func saveAiTransparencyReceipt(preview: AiSendPreview) -> AiSendReceipt? {
        #if DEBUG
        guard AiTransparencyFeature.isEnabled else { return nil }
        return try? services.repository.saveAiReceipt(preview: preview)
        #else
        return nil
        #endif
    }

    func generateAiTransparencyDraft(preview: AiSendPreview, receipt: AiSendReceipt) -> AiDraft? {
        #if DEBUG
        guard AiTransparencyFeature.isEnabled else { return nil }
        return try? services.repository.generateAiDraftWithFallback(preview: preview, receipt: receipt)
        #else
        return nil
        #endif
    }

    func saveAiTransparencyDiff(draft: AiDraft, operations: [AiDiffOperation]) -> AiDiffProposal? {
        #if DEBUG
        guard AiTransparencyFeature.isEnabled else { return nil }
        return try? services.repository.saveAiDiffProposal(draft: draft, operations: operations)
        #else
        return nil
        #endif
    }

    func applyAiTransparencyDiff(proposalID: String, confirm: Bool) async -> Bool {
        #if DEBUG
        guard AiTransparencyFeature.isEnabled,
              let applied = try? services.repository.applyAiDiffProposal(proposalID: proposalID, confirm: confirm),
              applied else {
            return false
        }
        await reload()
        return true
        #else
        return false
        #endif
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

    func prepareManualSave(input: String, localTagIDs: Set<Int64> = []) async -> ManualSaveOutcome {
        switch ManualTagImportPreparation.prepare(input: input) {
        case .ready(let preview):
            return .tagImportConfirmation(preview)
        case .invalid(let message):
            return .tagImportError(message)
        case .notTagPayload:
            break
        }

        guard let saveResult = try? services.repository.saveFromManualInput(input, localTagIDs: Array(localTagIDs)) else {
            return .saved(SaveResult(result: .saveFailed))
        }

        switch saveResult.result {
        case .inputTooLarge, .invalidURL, .noURLFound:
            return .inputError(saveResult.result)
        default:
            return .saved(saveResult)
        }
    }

    func finishPreparedManualSave(_ saveResult: SaveResult) async {
        await handleSaveResult(saveResult, degradationNotice: nil)
    }

    func manualSave(input: String, localTagIDs: Set<Int64> = []) async -> ShareSaveResult? {
        switch await prepareManualSave(input: input, localTagIDs: localTagIDs) {
        case .inputError(let result):
            return result
        case .saved(let saveResult):
            await finishPreparedManualSave(saveResult)
            return nil
        case .tagImportConfirmation, .tagImportError:
            return .saveFailed
        case .completed:
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
        let normalizedName = name.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        if !normalizedName.isEmpty,
           localTags.contains(where: { $0.name.trimmingCharacters(in: .whitespacesAndNewlines).lowercased() == normalizedName }) {
            enqueueNotification(AppNotification(message: "このタグはすでにあります", actionLabel: nil, action: nil, autoDismissAfter: 3))
            return nil
        }
        guard let tag = try? services.repository.createLocalTag(name: name) else {
            enqueueNotification(AppNotification(message: "タグを作成できませんでした", actionLabel: nil, action: nil, autoDismissAfter: 3))
            return nil
        }
        await reload()
        enqueueNotification(AppNotification(message: "タグを作成しました", actionLabel: nil, action: nil, autoDismissAfter: 3))
        return tag
    }

    func renameLocalTag(tagID: Int64, name: String) async -> Bool {
        guard (try? services.repository.renameLocalTag(id: tagID, name: name)) == true else {
            enqueueNotification(AppNotification(message: "タグ名を変更できませんでした", actionLabel: nil, action: nil, autoDismissAfter: 3))
            return false
        }
        await reload()
        enqueueNotification(AppNotification(message: "タグ名を変更しました", actionLabel: nil, action: nil, autoDismissAfter: 3))
        return true
    }

    func reorderLocalTags(tagIDs: [Int64]) async -> Bool {
        guard (try? services.repository.reorderLocalTags(tagIDs: tagIDs)) == true else {
            enqueueNotification(AppNotification(message: "タグを並び替えできませんでした", actionLabel: nil, action: nil, autoDismissAfter: 3))
            return false
        }
        await reload()
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

    func localTagShareURLCount(tagID: Int64) -> Int {
        (try? services.repository.exportLocalTag(tagID: tagID)?.urls.count) ?? 0
    }

    func localTagShareFileURL(tagID: Int64) -> URL? {
        guard let payload = try? services.repository.exportLocalTag(tagID: tagID),
              let data = try? JSONEncoder().encode(payload) else {
            return nil
        }
        let fileURL = FileManager.default.temporaryDirectory
            .appendingPathComponent("rinbam-tag-\(UUID().uuidString).json")
        do {
            try data.write(to: fileURL, options: .atomic)
            return fileURL
        } catch {
            return nil
        }
    }

    @discardableResult
    func confirmManualTagImport(_ preview: ManualTagImportPreview) async -> Bool {
        guard let result = try? ManualTagImportPreparation.importConfirmed(preview, repository: services.repository),
              result.tagID >= 0 else {
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
            sharedTagAccountLocalCleanupState = nil
            await refreshAfterRemoteAccountDeletion()
            profileStatusMessage = "共有タグアカウントを削除しました。"
            enqueueNotification(AppNotification(message: "共有タグクラウドのアカウントを削除しました", actionLabel: nil, action: nil, autoDismissAfter: 5))
        case .localCleanupRequired(let state):
            sharedTagAccountLocalCleanupState = state
            await refreshAfterRemoteAccountDeletion()
            let message = localAccountCleanupMessage(for: state)
            profileStatusMessage = message
            enqueueNotification(AppNotification(message: message, actionLabel: nil, action: nil, autoDismissAfter: 6))
        case .authRequired:
            enqueueNotification(AppNotification(message: "先に共有タグクラウドへサインインしてください", actionLabel: nil, action: nil, autoDismissAfter: 4))
        case .ownerTransferRequired:
            enqueueNotification(AppNotification(message: "共有タグ詳細の参加者からオーナー権限を移譲してから削除してください", actionLabel: nil, action: nil, autoDismissAfter: 5))
        case .failure(let message):
            enqueueNotification(AppNotification(message: message, actionLabel: nil, action: nil, autoDismissAfter: 4))
        }
    }

    func retrySharedTagAccountLocalCleanup() async {
        guard sharedTagAccountLocalCleanupState != nil else { return }
        switch services.sharedTagCloud.retryLocalAccountCleanup() {
        case .success:
            sharedTagAccountLocalCleanupState = nil
            await refreshSharedTagCloudState()
            await reload()
            profileStatusMessage = "端末内データの消去を完了しました。"
            enqueueNotification(AppNotification(message: "端末内データの消去を完了しました", actionLabel: nil, action: nil, autoDismissAfter: 5))
        case .localCleanupRequired(let state):
            sharedTagAccountLocalCleanupState = state
            await refreshSharedTagCloudState()
            await reload()
            let message = localAccountCleanupMessage(for: state)
            profileStatusMessage = message
            enqueueNotification(AppNotification(message: message, actionLabel: nil, action: nil, autoDismissAfter: 6))
        case .failure(let message):
            profileStatusMessage = message
            enqueueNotification(AppNotification(message: message, actionLabel: nil, action: nil, autoDismissAfter: 5))
        case .authRequired, .ownerTransferRequired:
            let message = "端末内データの消去を完了できませんでした。もう一度お試しください。"
            profileStatusMessage = message
            enqueueNotification(AppNotification(message: message, actionLabel: nil, action: nil, autoDismissAfter: 5))
        }
    }

    private func refreshAfterRemoteAccountDeletion() async {
        try? services.pendingInviteStore.clear()
        pendingInviteRecord = nil
        await refreshSharedTagCloudState()
        await reload()
    }

    private func localAccountCleanupMessage(for state: SharedTagAccountLocalCleanupState) -> String {
        if state.aiDataCleanupPending && state.signOutCleanupPending {
            return "アカウント削除済み・端末内AIデータとサインアウト情報の消去未完了"
        }
        if state.aiDataCleanupPending {
            return "アカウント削除済み・端末内AIデータ消去未完了"
        }
        return "アカウント削除済み・端末内のサインアウト情報消去未完了"
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

    func chatGptExportPreview(selectedLocalTagIDs: Set<Int64>) async throws -> ChatGptExportPreview {
        let services = services
        let operation = Task.detached(priority: .userInitiated) {
            try Task.checkCancellation()
            let snapshot = try services.repository.loadChatGptExportLocalSnapshot()
            let candidateEntries = snapshot.entries.filter { entry in
                !(snapshot.localTagAssignments[entry.id] ?? []).isDisjoint(with: selectedLocalTagIDs)
            }
            let sharedTagsByEntryID = try ChatGptExportPreparationGate.loadSharedTagsByEntryID(
                entries: candidateEntries,
                bulkLookup: services.sharedTagCloud.loadVisibleTagsByEntryID(entries:)
            )
            try Task.checkCancellation()
            return try URLExportArchiveBuilder.buildChatGptExportPreview(
                selectedLocalTagIDs: selectedLocalTagIDs,
                entries: snapshot.entries,
                localTags: snapshot.localTags,
                localTagAssignments: snapshot.localTagAssignments,
                sharedTagsByEntryID: sharedTagsByEntryID
            )
        }
        return try await withTaskCancellationHandler(
            operation: { try await operation.value },
            onCancel: { operation.cancel() }
        )
    }

    func prepareChatGptExportArchive(
        selectedLocalTagIDs: Set<Int64>,
        expectedSnapshotToken: String
    ) async throws -> PreparedExportArchive {
        try Task.checkCancellation()
        let syncSucceeded = await syncSharedTagCloud(showFailureNotification: false)
        try Task.checkCancellation()
        try ChatGptExportPreparationGate.requireSuccessfulSync(syncSucceeded)

        let services = services
        let appVersion = Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "iOS"
        let operation = Task.detached(priority: .userInitiated) {
            try Task.checkCancellation()
            let snapshot = try services.repository.loadChatGptExportLocalSnapshot()
            let candidateEntries = snapshot.entries.filter { entry in
                !(snapshot.localTagAssignments[entry.id] ?? []).isDisjoint(with: selectedLocalTagIDs)
            }
            let sharedTagsByEntryID = try ChatGptExportPreparationGate.loadSharedTagsByEntryID(
                entries: candidateEntries,
                bulkLookup: services.sharedTagCloud.loadVisibleTagsByEntryID(entries:)
            )
            try Task.checkCancellation()
            return try URLExportArchiveBuilder.prepareChatGptExport(
                selectedLocalTagIDs: selectedLocalTagIDs,
                expectedSnapshotToken: expectedSnapshotToken,
                entries: snapshot.entries,
                localTags: snapshot.localTags,
                localTagAssignments: snapshot.localTagAssignments,
                sharedTagsByEntryID: sharedTagsByEntryID,
                appVersion: appVersion
            )
        }
        return try await withTaskCancellationHandler(
            operation: { try await operation.value },
            onCancel: { operation.cancel() }
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

    func savedAppMediaItemsForEntry(entryID: Int64) -> [SavedAppMediaFile] {
        IOSAppMediaStore.savedFiles(entryID: entryID)
    }

    func saveMediaForEntry(entryID: Int64) async -> Bool {
        guard let entry = entry(for: entryID),
              entry.localProvenanceCount > 0,
              entry.recordState == .active,
              IOSBackendMediaResolver.supports(serviceType: entry.serviceType) else {
            enqueueNotification(AppNotification(message: "この投稿のメディアは保存できません", actionLabel: nil, action: nil, autoDismissAfter: 4))
            return false
        }

        do {
            let assets = try await IOSBackendMediaResolver().resolve(entry: entry)
            guard !assets.isEmpty else {
                enqueueNotification(AppNotification(message: "メディアを取得できませんでした", actionLabel: nil, action: nil, autoDismissAfter: 4))
                return false
            }

            var savedFiles: [SavedAppMediaFile] = []
            for (index, asset) in assets.enumerated() {
                do {
                    let savedFile = try await IOSAppMediaSaver.save(asset: asset, entryID: entryID, index: index)
                    savedFiles.append(savedFile)
                } catch {
                    continue
                }
            }

            let savedCount = savedFiles.count
            guard savedCount > 0 else {
                enqueueNotification(AppNotification(message: "メディアを保存できませんでした", actionLabel: nil, action: nil, autoDismissAfter: 4))
                return false
            }
            if savedCount == assets.count {
                IOSAppMediaStore.removeFilesNotMatching(
                    entryID: entryID,
                    keepFileNames: Set(savedFiles.map(\.fileName))
                )
            }

            mediaSaveRevision += 1
            let message = savedCount == 1 ? "メディアを保存しました" : "\(savedCount)件のメディアを保存しました"
            enqueueNotification(AppNotification(message: message, actionLabel: nil, action: nil, autoDismissAfter: 4))
            return true
        } catch let error as IOSMediaResolverError {
            enqueueNotification(AppNotification(message: error.userMessage, actionLabel: nil, action: nil, autoDismissAfter: 4))
            return false
        } catch {
            enqueueNotification(AppNotification(message: "メディアを取得できませんでした", actionLabel: nil, action: nil, autoDismissAfter: 4))
            return false
        }
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

private struct IOSResolvedMediaAsset: Decodable, Sendable {
    let providerAssetID: String
    let downloadURL: String
    let mediaType: String
    let mimeType: String?
    let requestHeadersJSON: String?
    let sortIndex: Int?

    enum CodingKeys: String, CodingKey {
        case providerAssetID = "providerAssetId"
        case downloadURL = "downloadUrl"
        case mediaType
        case mimeType
        case requestHeadersJSON = "requestHeadersJson"
        case sortIndex
    }
}

private struct IOSMediaResolveResponse: Decodable {
    let ok: Bool
    let assets: [IOSResolvedMediaAsset]
    let error: String?
    let message: String?
}

private enum IOSMediaResolverError: Error {
    case unsupported
    case unconfigured
    case failed
    case authRequired
    case message(String)
    case invalidDownloadURL
    case httpStatus(Int)

    var userMessage: String {
        switch self {
        case .unsupported:
            return "この投稿のメディアは保存できません"
        case .unconfigured:
            return "メディア保存サーバーが未設定です"
        case .authRequired:
            return "サービス側の認証が必要なため、現在はメディアを取得できません"
        case .message(let value):
            return value
        case .failed, .invalidDownloadURL, .httpStatus:
            return "メディアを取得できませんでした"
        }
    }
}

private struct IOSBackendMediaResolver {
    static func supports(serviceType: ServiceType) -> Bool {
        switch serviceType {
        case .youtube, .tiktok, .instagram:
            return true
        case .all, .web:
            return false
        case .x:
            return false
        }
    }

    func resolve(entry: URLRecord) async throws -> [IOSResolvedMediaAsset] {
        guard Self.supports(serviceType: entry.serviceType) else {
            throw IOSMediaResolverError.unsupported
        }
        guard let baseURL = Self.backendBaseURL() else {
            throw IOSMediaResolverError.unconfigured
        }

        var request = URLRequest(url: baseURL.appendingPathComponent("resolve"))
        request.httpMethod = "POST"
        request.timeoutInterval = 75
        request.setValue("application/json; charset=utf-8", forHTTPHeaderField: "Content-Type")
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        request.setValue("Rinbam iOS", forHTTPHeaderField: "User-Agent")
        request.httpBody = try JSONSerialization.data(
            withJSONObject: [
                "provider": providerName(for: entry.serviceType),
                "serviceType": entry.serviceType.rawValue,
                "url": entry.openURL,
            ],
            options: []
        )

        let (data, response) = try await URLSession.shared.data(for: request)
        guard let httpResponse = response as? HTTPURLResponse else {
            throw IOSMediaResolverError.httpStatus(-1)
        }
        if !(200..<300).contains(httpResponse.statusCode) {
            throw Self.error(from: data, fallbackStatus: httpResponse.statusCode)
        }

        let decoded = try JSONDecoder().decode(IOSMediaResolveResponse.self, from: data)
        guard decoded.ok else {
            throw Self.error(from: decoded)
        }
        return decoded.assets.filter { asset in
            guard let url = URL(string: asset.downloadURL),
                  let scheme = url.scheme?.lowercased(),
                  scheme == "https" || scheme == "http" else {
                return false
            }
            return asset.mediaType == "IMAGE" || asset.mediaType == "VIDEO"
        }
    }

    private static func backendBaseURL() -> URL? {
        let rawValue = Bundle.main.object(forInfoDictionaryKey: "MediaResolverBackendURL") as? String
        let normalized = rawValue?
            .trimmingCharacters(in: .whitespacesAndNewlines)
            .replacingOccurrences(of: "https:/$()/", with: "https://")
            .replacingOccurrences(of: "http:/$()/", with: "http://")
            .trimmingCharacters(in: CharacterSet(charactersIn: "/"))
        guard let normalized, !normalized.isEmpty else { return nil }
        return URL(string: normalized)
    }

    private static func error(from data: Data, fallbackStatus: Int) -> IOSMediaResolverError {
        if let decoded = try? JSONDecoder().decode(IOSMediaResolveResponse.self, from: data) {
            return error(from: decoded)
        }
        return .httpStatus(fallbackStatus)
    }

    private static func error(from response: IOSMediaResolveResponse) -> IOSMediaResolverError {
        let errorCode = response.error?.trimmingCharacters(in: .whitespacesAndNewlines)
        let rawMessage = response.message?.trimmingCharacters(in: .whitespacesAndNewlines)
        let lowered = rawMessage?.lowercased() ?? ""
        if errorCode == "AUTH_REQUIRED" ||
            lowered.contains("sign in") ||
            lowered.contains("cookies") ||
            lowered.contains("login") ||
            lowered.contains("not a bot") {
            return .authRequired
        }
        if errorCode == "MEDIA_RESOLVER_BACKEND_UNCONFIGURED" {
            return .unconfigured
        }
        return .failed
    }

    private func providerName(for serviceType: ServiceType) -> String {
        switch serviceType {
        case .youtube:
            return "youtube"
        case .tiktok:
            return "tiktok"
        case .instagram:
            return "instagram"
        case .all, .web, .x:
            return "generic"
        }
    }
}

private enum IOSAppMediaSaver {
    static func save(asset: IOSResolvedMediaAsset, entryID: Int64, index: Int) async throws -> SavedAppMediaFile {
        guard let downloadURL = URL(string: asset.downloadURL),
              let scheme = downloadURL.scheme?.lowercased(),
              scheme == "https" || scheme == "http" else {
            throw IOSMediaResolverError.invalidDownloadURL
        }

        var request = URLRequest(url: downloadURL)
        request.timeoutInterval = 90
        request.setValue("Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1", forHTTPHeaderField: "User-Agent")
        request.setValue("image/avif,image/webp,image/apng,image/*,video/*,*/*;q=0.8", forHTTPHeaderField: "Accept")
        for (name, value) in sanitizedHeaders(from: asset.requestHeadersJSON) {
            if request.value(forHTTPHeaderField: name) == nil {
                request.setValue(value, forHTTPHeaderField: name)
            }
        }

        let (temporaryURL, response) = try await URLSession.shared.download(for: request)
        guard let httpResponse = response as? HTTPURLResponse,
              (200..<300).contains(httpResponse.statusCode) else {
            try? FileManager.default.removeItem(at: temporaryURL)
            throw IOSMediaResolverError.httpStatus((response as? HTTPURLResponse)?.statusCode ?? -1)
        }

        let fileName = fileName(for: asset, fallbackIndex: index)
        let destination = try IOSAppMediaStore.fileURL(entryID: entryID, fileName: fileName)
        try? FileManager.default.removeItem(at: destination)
        try FileManager.default.createDirectory(at: destination.deletingLastPathComponent(), withIntermediateDirectories: true)
        try FileManager.default.moveItem(at: temporaryURL, to: destination)
        return SavedAppMediaFile(
            id: destination.path,
            fileURL: destination,
            mediaType: asset.mediaType,
            fileName: fileName
        )
    }

    private static func sanitizedHeaders(from json: String?) -> [(String, String)] {
        guard let json,
              let data = json.data(using: .utf8),
              let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            return []
        }
        return object.compactMap { key, value in
            let lowerKey = key.lowercased()
            guard lowerKey != "cookie",
                  lowerKey != "authorization",
                  let stringValue = value as? String,
                  !stringValue.isEmpty else {
                return nil
            }
            return (key, stringValue)
        }
    }

    private static func fileName(for asset: IOSResolvedMediaAsset, fallbackIndex: Int) -> String {
        let sortIndex = max(0, asset.sortIndex ?? fallbackIndex)
        let prefix = String(format: "%03d", sortIndex)
        let safeBase = asset.providerAssetID
            .replacingOccurrences(of: "[^A-Za-z0-9._-]+", with: "_", options: .regularExpression)
            .trimmingCharacters(in: CharacterSet(charactersIn: "._-"))
        let base = safeBase.isEmpty ? "\(prefix)_rinbam_media" : "\(prefix)_\(safeBase)"
        return "\(base).\(fileExtension(for: asset))"
    }

    private static func fileExtension(for asset: IOSResolvedMediaAsset) -> String {
        if let ext = URL(string: asset.downloadURL)?.pathExtension.lowercased(),
           ["jpg", "jpeg", "png", "webp", "heic", "mp4", "mov", "webm"].contains(ext) {
            return ext == "jpeg" ? "jpg" : ext
        }
        switch asset.mimeType?.lowercased() {
        case "image/png": return "png"
        case "image/webp": return "webp"
        case "image/heic": return "heic"
        case "video/quicktime": return "mov"
        case "video/webm": return "webm"
        case "video/mp4": return "mp4"
        default:
            return asset.mediaType == "IMAGE" ? "jpg" : "mp4"
        }
    }
}

private enum IOSAppMediaStore {
    private static let rootDirectoryName = "RinbamMedia"

    static func savedFiles(entryID: Int64) -> [SavedAppMediaFile] {
        guard let directory = try? directoryURL(entryID: entryID) else { return [] }
        let urls = (try? FileManager.default.contentsOfDirectory(
            at: directory,
            includingPropertiesForKeys: [],
            options: [.skipsHiddenFiles]
        )) ?? []
        return urls
            .filter { !$0.hasDirectoryPath }
            .sorted { lhs, rhs in
                rinbamMediaFileNamePrecedes(lhs.lastPathComponent, rhs.lastPathComponent)
            }
            .map { url in
                SavedAppMediaFile(
                    id: url.path,
                    fileURL: url,
                    mediaType: mediaType(for: url),
                    fileName: url.lastPathComponent
                )
            }
    }

    static func removeFilesNotMatching(entryID: Int64, keepFileNames: Set<String>) {
        guard let directory = try? directoryURL(entryID: entryID) else { return }
        let urls = (try? FileManager.default.contentsOfDirectory(
            at: directory,
            includingPropertiesForKeys: [],
            options: [.skipsHiddenFiles]
        )) ?? []
        for url in urls where !url.hasDirectoryPath && !keepFileNames.contains(url.lastPathComponent) {
            try? FileManager.default.removeItem(at: url)
        }
    }

    static func fileURL(entryID: Int64, fileName: String) throws -> URL {
        try directoryURL(entryID: entryID)
            .appendingPathComponent(fileName, isDirectory: false)
    }

    private static func directoryURL(entryID: Int64) throws -> URL {
        let root = try rootURL()
        return root.appendingPathComponent(String(entryID), isDirectory: true)
    }

    private static func rootURL() throws -> URL {
        let base = try FileManager.default.url(
            for: .applicationSupportDirectory,
            in: .userDomainMask,
            appropriateFor: nil,
            create: true
        )
        let root = base.appendingPathComponent(rootDirectoryName, isDirectory: true)
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        return root
    }

    private static func mediaType(for url: URL) -> String {
        switch url.pathExtension.lowercased() {
        case "jpg", "jpeg", "png", "webp", "gif", "heic", "heif":
            return "IMAGE"
        default:
            return "VIDEO"
        }
    }
}

func rinbamMediaSortIndex(from fileName: String) -> Int? {
    let prefix = fileName.prefix(while: { $0.isNumber })
    guard prefix.count == 3,
          fileName.dropFirst(prefix.count).first == "_",
          let value = Int(prefix) else {
        return nil
    }
    return value
}

func rinbamMediaFileNamePrecedes(_ lhs: String, _ rhs: String) -> Bool {
    let leftIndex = rinbamMediaSortIndex(from: lhs)
    let rightIndex = rinbamMediaSortIndex(from: rhs)
    switch (leftIndex, rightIndex) {
    case let (left?, right?) where left != right:
        return left < right
    case (.some, nil):
        return true
    case (nil, .some):
        return false
    default:
        return lhs.localizedStandardCompare(rhs) == .orderedAscending
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
