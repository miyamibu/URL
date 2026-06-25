import Foundation

enum ServiceType: String, Codable, CaseIterable, Sendable {
    case all
    case youtube
    case tiktok
    case x
    case instagram
    case web

    var displayName: String {
        switch self {
        case .all: return "すべて"
        case .youtube: return "YouTube"
        case .tiktok: return "TikTok"
        case .x: return "X"
        case .instagram: return "Instagram"
        case .web: return "Webサイト"
        }
    }
}

enum PlanType: String, Codable, CaseIterable, Sendable {
    case free
    case launchStandard = "launch_standard"
    case pro
    case promoPro = "promo_pro"
}

struct PlanLimits: Codable, Equatable, Sendable {
    let personalURLLimit: Int
    let normalTagLimit: Int
    let sharedTagLimit: Int
    let sharedTagURLLimitPerTag: Int
    let sharedTagGroupLimit: Int
    let sharedTagGroupMemberLimit: Int

    init(
        personalURLLimit: Int,
        normalTagLimit: Int,
        sharedTagLimit: Int,
        sharedTagURLLimitPerTag: Int,
        sharedTagGroupLimit: Int = 0,
        sharedTagGroupMemberLimit: Int = 0
    ) {
        self.personalURLLimit = personalURLLimit
        self.normalTagLimit = normalTagLimit
        self.sharedTagLimit = sharedTagLimit
        self.sharedTagURLLimitPerTag = sharedTagURLLimitPerTag
        self.sharedTagGroupLimit = sharedTagGroupLimit
        self.sharedTagGroupMemberLimit = sharedTagGroupMemberLimit
    }
}

struct FeatureEntitlements: Codable, Equatable, Sendable {
    let planType: PlanType
    let limits: PlanLimits
    let subscriptionEnabled: Bool
    let shouldShowAds: Bool
    let exportEnabled: Bool
    let sharedSyncEnabled: Bool
}

struct SharedTagUsage: Codable, Equatable, Sendable {
    let tagID: Int64
    let tagName: String
    let urlCount: Int
    let limit: Int
}

struct UsageSummary: Codable, Equatable, Sendable {
    let personalURLCount: Int
    let normalTagCount: Int
    let sharedTagCount: Int
    let sharedTagGroupCount: Int
    let sharedTagUsages: [SharedTagUsage]

    init(
        personalURLCount: Int,
        normalTagCount: Int,
        sharedTagCount: Int,
        sharedTagGroupCount: Int = 0,
        sharedTagUsages: [SharedTagUsage]
    ) {
        self.personalURLCount = personalURLCount
        self.normalTagCount = normalTagCount
        self.sharedTagCount = sharedTagCount
        self.sharedTagGroupCount = sharedTagGroupCount
        self.sharedTagUsages = sharedTagUsages
    }

    func sharedTagUsage(tagID: Int64) -> SharedTagUsage? {
        sharedTagUsages.first { $0.tagID == tagID }
    }
}

enum LimitTarget: Equatable, Sendable {
    case personalURL
    case normalTag
    case sharedTag
    case sharedTagURL
    case sharedTagGroup
}

enum LimitResult: Equatable, Sendable {
    case allowed
    case blocked(target: LimitTarget, message: String)
}

struct LimitChecker: Sendable {
    let entitlements: FeatureEntitlements

    private var limits: PlanLimits { entitlements.limits }
    private var planLabel: String { entitlements.planType.limitMessageLabel }

    func checkCanSavePersonalURL(_ usage: UsageSummary) -> LimitResult {
        if usage.personalURLCount >= limits.personalURLLimit {
            return .blocked(
                target: .personalURL,
                message: "\(planLabel)の保存上限に達しました。不要なURLを整理してから追加してください。"
            )
        }
        return .allowed
    }

    func checkCanCreateNormalTag(_ usage: UsageSummary) -> LimitResult {
        if usage.normalTagCount >= limits.normalTagLimit {
            return .blocked(
                target: .normalTag,
                message: "通常タグは\(planLabel)では\(limits.normalTagLimit)個まで作成できます。"
            )
        }
        return .allowed
    }

    func checkCanCreateSharedTag(_ usage: UsageSummary) -> LimitResult {
        if usage.sharedTagCount >= limits.sharedTagLimit {
            return .blocked(
                target: .sharedTag,
                message: "共有タグは\(planLabel)では\(limits.sharedTagLimit)個まで作成できます。"
            )
        }
        return .allowed
    }

    func checkCanAddURLToSharedTag(_ usage: UsageSummary, tagID: Int64) -> LimitResult {
        if let tagUsage = usage.sharedTagUsage(tagID: tagID),
           tagUsage.urlCount >= limits.sharedTagURLLimitPerTag {
            return .blocked(
                target: .sharedTagURL,
                message: "この共有タグには\(limits.sharedTagURLLimitPerTag)件までURLを追加できます。"
            )
        }
        return .allowed
    }

    func checkCanCreateSharedTagGroup(_ usage: UsageSummary) -> LimitResult {
        if usage.sharedTagGroupCount >= limits.sharedTagGroupLimit {
            return .blocked(
                target: .sharedTagGroup,
                message: "グループは\(planLabel)では\(limits.sharedTagGroupLimit)個まで作成できます。"
            )
        }
        return .allowed
    }
}

enum LaunchStandardPlan {
    static let limits = PlanLimits(
        personalURLLimit: 200,
        normalTagLimit: 10,
        sharedTagLimit: 2,
        sharedTagURLLimitPerTag: 20,
        sharedTagGroupLimit: 2,
        sharedTagGroupMemberLimit: 10
    )

    static let entitlements = FeatureEntitlements(
        planType: .launchStandard,
        limits: limits,
        subscriptionEnabled: false,
        shouldShowAds: false,
        exportEnabled: true,
        sharedSyncEnabled: true
    )
}

enum FreePlan {
    static let limits = PlanLimits(
        personalURLLimit: 100,
        normalTagLimit: 5,
        sharedTagLimit: 1,
        sharedTagURLLimitPerTag: 10,
        sharedTagGroupLimit: 0,
        sharedTagGroupMemberLimit: 0
    )

    static let entitlements = FeatureEntitlements(
        planType: .free,
        limits: limits,
        subscriptionEnabled: false,
        shouldShowAds: true,
        exportEnabled: false,
        sharedSyncEnabled: false
    )
}

enum ProPlan {
    static let limits = PlanLimits(
        personalURLLimit: 10_000,
        normalTagLimit: 200,
        sharedTagLimit: 50,
        sharedTagURLLimitPerTag: 10_000,
        sharedTagGroupLimit: 50,
        sharedTagGroupMemberLimit: 100
    )

    static let entitlements = FeatureEntitlements(
        planType: .pro,
        limits: limits,
        subscriptionEnabled: false,
        shouldShowAds: false,
        exportEnabled: true,
        sharedSyncEnabled: true
    )
}

enum PromoProPlan {
    static let entitlements = FeatureEntitlements(
        planType: .promoPro,
        limits: ProPlan.limits,
        subscriptionEnabled: false,
        shouldShowAds: false,
        exportEnabled: true,
        sharedSyncEnabled: true
    )
}

enum PlanEntitlements {
    static func entitlements(for planType: PlanType) -> FeatureEntitlements {
        switch planType {
        case .free:
            return FreePlan.entitlements
        case .launchStandard:
            return LaunchStandardPlan.entitlements
        case .pro:
            return ProPlan.entitlements
        case .promoPro:
            return PromoProPlan.entitlements
        }
    }
}

enum EntitlementSource: String, Codable, Sendable {
    case storeSubscription = "store_subscription"
    case storePromoCode = "store_promo_code"
    case adminGrant = "admin_grant"
    case referralGrant = "referral_grant"
}

enum EntitlementGrantStatus: String, Codable, Sendable {
    case active
    case revoked
    case pending
}

struct EntitlementGrant: Codable, Equatable, Sendable {
    let planType: PlanType
    let source: EntitlementSource
    let status: EntitlementGrantStatus
    let startsAt: Date
    let endsAt: Date?
    let sourceID: String?
    let note: String?

    init(
        planType: PlanType,
        source: EntitlementSource,
        status: EntitlementGrantStatus = .active,
        startsAt: Date,
        endsAt: Date? = nil,
        sourceID: String? = nil,
        note: String? = nil
    ) {
        self.planType = planType
        self.source = source
        self.status = status
        self.startsAt = startsAt
        self.endsAt = endsAt
        self.sourceID = sourceID
        self.note = note
    }

    func isActive(at date: Date) -> Bool {
        guard status == .active else { return false }
        return date >= startsAt && (endsAt == nil || date < endsAt!)
    }
}

protocol EntitlementResolving: Sendable {
    func resolve(at date: Date) -> FeatureEntitlements
}

struct EntitlementResolver: EntitlementResolving {
    let defaultEntitlements: FeatureEntitlements
    let grantsProvider: @Sendable () -> [EntitlementGrant]

    init(
        defaultEntitlements: FeatureEntitlements = LaunchStandardPlan.entitlements,
        grantsProvider: @escaping @Sendable () -> [EntitlementGrant] = { [] }
    ) {
        self.defaultEntitlements = defaultEntitlements
        self.grantsProvider = grantsProvider
    }

    func resolve(at date: Date = Date()) -> FeatureEntitlements {
        guard let grant = grantsProvider()
            .filter({ $0.isActive(at: date) })
            .sorted(by: Self.grantSort)
            .first
        else {
            return defaultEntitlements
        }
        return PlanEntitlements.entitlements(for: grant.planType)
    }

    private static func grantSort(_ lhs: EntitlementGrant, _ rhs: EntitlementGrant) -> Bool {
        if lhs.planType.priority != rhs.planType.priority {
            return lhs.planType.priority < rhs.planType.priority
        }
        if lhs.source.priority != rhs.source.priority {
            return lhs.source.priority < rhs.source.priority
        }
        return lhs.startsAt > rhs.startsAt
    }
}

private extension PlanType {
    var priority: Int {
        switch self {
        case .promoPro: return 0
        case .pro: return 1
        case .launchStandard: return 2
        case .free: return 3
        }
    }

    var limitMessageLabel: String {
        switch self {
        case .free: return "無料プラン"
        case .launchStandard: return "ローンチ版"
        case .pro: return "Proプラン"
        case .promoPro: return "優待Pro"
        }
    }
}

private extension EntitlementSource {
    var priority: Int {
        switch self {
        case .adminGrant: return 0
        case .storeSubscription: return 1
        case .storePromoCode: return 2
        case .referralGrant: return 3
        }
    }
}

enum BuildVariantEntitlementOverrides {
    #if DEBUG
    static let debugPlanOverrideKey = "urlsaver.debug.entitlement.plan"

    static func grants(at date: Date) -> [EntitlementGrant] {
        guard let rawPlan = UserDefaults.standard.string(forKey: debugPlanOverrideKey),
              let planType = PlanType(rawValue: rawPlan.trimmingCharacters(in: .whitespacesAndNewlines)) else {
            return []
        }
        return [
            EntitlementGrant(
                planType: planType,
                source: .adminGrant,
                status: .active,
                startsAt: date,
                sourceID: "debug_override"
            )
        ]
    }
    #else
    static func grants(at date: Date) -> [EntitlementGrant] {
        []
    }
    #endif
}

enum ContentContext: String, Codable, Sendable {
    case standard
    case video
    case shorts
    case live
    case music
    case post
    case reel
    case profile
    case sound
    case hashtag
}

enum RecordState: String, Codable, Sendable {
    case active = "ACTIVE"
    case archived = "ARCHIVED"
    case pendingDelete = "PENDING_DELETE"
}

enum MetadataState: String, Codable, Sendable {
    case pending = "PENDING"
    case ready = "READY"
    case failed = "FAILED"
    case unavailable = "UNAVAILABLE"
}

enum MetadataError: String, Codable, Sendable {
    case timeout = "TIMEOUT"
    case networkIO = "NETWORK_IO"
    case unsupportedScheme = "UNSUPPORTED_SCHEME"
    case schedulerUnavailable = "SCHEDULER_UNAVAILABLE"
    case http404 = "HTTP_404"
    case http4xx = "HTTP_4XX"
    case http5xx = "HTTP_5XX"
    case parseFailed = "PARSE_FAILED"
    case nonHTML = "NON_HTML"
    case oversized = "OVERSIZED"
    case tooManyRedirects = "TOO_MANY_REDIRECTS"
}

enum MetadataBodyKind: String, Codable, Sendable {
    case youtubeDescription = "YOUTUBE_DESCRIPTION"
    case instagramCaption = "INSTAGRAM_CAPTION"
    case xPostText = "X_POST_TEXT"
    case webDescription = "WEB_DESCRIPTION"
    case webExcerpt = "WEB_EXCERPT"
}

enum ShareSaveResult: String, Codable, Sendable {
    case batchProcessed = "BATCH_PROCESSED"
    case created = "CREATED"
    case duplicateActive = "DUPLICATE_ACTIVE"
    case duplicateArchived = "DUPLICATE_ARCHIVED"
    case restoredFromPendingDelete = "RESTORED_FROM_PENDING_DELETE"
    case saveFailed = "SAVE_FAILED"
    case inputTooLarge = "INPUT_TOO_LARGE"
    case invalidURL = "INVALID_URL"
    case noURLFound = "NO_URL_FOUND"
}

enum ShareDegradationNotice: String, Codable, Sendable {
    case truncatedToFirstURL = "truncated_to_first_url"
    case truncatedToMaxURLs = "truncated_to_max_urls"
}

enum ShareExtractionResult: Equatable, Sendable {
    case found(String)
    case inputTooLarge
    case noURLFound
    case invalidURL
}

struct ParsedURL: Equatable, Sendable {
    let originalURL: String
    let normalizedURL: String
    let displayURL: String
    let openURL: String
    let normalizedHost: String
    let rawSourceHost: String
    let serviceType: ServiceType
    let contentContext: ContentContext
}

struct SaveResult: Equatable, Sendable {
    let result: ShareSaveResult
    let entryID: Int64?
    let normalizedURL: String?
    let shouldScheduleMetadata: Bool

    init(
        result: ShareSaveResult,
        entryID: Int64? = nil,
        normalizedURL: String? = nil,
        shouldScheduleMetadata: Bool = false
    ) {
        self.result = result
        self.entryID = entryID
        self.normalizedURL = normalizedURL
        self.shouldScheduleMetadata = shouldScheduleMetadata
    }
}

struct LocalTagSummary: Identifiable, Equatable, Sendable {
    let id: Int64
    let name: String
    let activeURLCount: Int
    let createdAt: Date
    let updatedAt: Date
}

struct BatchSaveSummary: Codable, Equatable, Sendable {
    let total: Int
    let created: Int
    let duplicate: Int
    let restored: Int
    let failed: Int
}

struct ShareHandoffReport: Codable, Equatable, Sendable {
    let result: ShareSaveResult
    let entryID: Int64?
    let normalizedURL: String?
    let degradationNotice: ShareDegradationNotice?
    let batchSummary: BatchSaveSummary?
    let createdAt: Date
}

struct MetadataUpdate: Equatable, Sendable {
    let fetchedTitle: String?
    var fetchedAuthorName: String? = nil
    let fetchedBody: String?
    let fetchedBodyKind: MetadataBodyKind?
    let bodySummary: String?
    let description: String?
    let thumbnailURL: String?
    let badgeImageURL: String?
    let metadataState: MetadataState
    let metadataFetchedAt: Date?
    let metadataError: MetadataError?
    let canonicalID: String?
    let normalizedHost: String?
    let rawSourceHost: String?
}

struct URLRecord: Identifiable, Equatable, Sendable {
    let id: Int64
    let originalURL: String
    let normalizedURL: String
    let displayURL: String
    let openURL: String
    let normalizedHost: String
    let rawSourceHost: String
    let serviceType: ServiceType
    let contentContext: ContentContext
    let userTitle: String?
    let fetchedTitle: String?
    var fetchedAuthorName: String? = nil
    let fetchedBody: String?
    let fetchedBodyKind: MetadataBodyKind?
    let bodySummary: String?
    let description: String?
    let memo: String
    let thumbnailURL: String?
    let badgeImageURL: String?
    let canonicalID: String?
    let metadataState: MetadataState
    let metadataError: MetadataError?
    let metadataRequestedAt: Date?
    let metadataFetchedAt: Date?
    let recordState: RecordState
    let localProvenanceCount: Int
    let sharedReferenceCount: Int
    let createdAt: Date
    let updatedAt: Date
    let archivedAt: Date?
    let pendingDeletionUntil: Date?

    var effectiveTitle: String {
        URLRules.effectiveTitle(
            userTitle: userTitle,
            fetchedTitle: fetchedTitle,
            serviceType: serviceType,
            normalizedHost: normalizedHost
        )
    }

    var needsMetadataRetryAfterRestore: Bool {
        switch metadataState {
        case .ready, .unavailable:
            return false
        case .failed:
            return true
        case .pending:
            return true
        }
    }

    var canRetryMetadata: Bool {
        switch metadataState {
        case .failed, .unavailable:
            return true
        case .pending:
            return false
        case .ready:
            let lacksFetchedContent = fetchedTitle == nil && fetchedBody == nil && thumbnailURL == nil
            let lacksSocialBadge = [.youtube, .tiktok, .x, .instagram].contains(serviceType) && badgeImageURL == nil
            return lacksFetchedContent || lacksSocialBadge
        }
    }

    var isVisibleInLocalLists: Bool {
        localProvenanceCount > 0
    }
}

struct ShareCandidateGroups: Sendable {
    var extraCandidates: [String] = []
    var providerTextCandidates: [String] = []
    var streamCandidates: [String] = []
    var directURLCandidates: [String] = []

    var orderedGroups: [[String]] {
        [
            extraCandidates,
            providerTextCandidates,
            streamCandidates,
            directURLCandidates,
        ]
    }
}

enum MetadataStatusText {
    static func listText(for record: URLRecord) -> String? {
        switch record.metadataState {
        case .pending:
            return "取得中"
        case .failed:
            return "一時的に取得できません"
        case .unavailable:
            return unavailableShortText(for: record)
        case .ready:
            return nil
        }
    }

    static func detailText(for record: URLRecord) -> String? {
        switch record.metadataState {
        case .pending:
            return "情報を更新中です"
        case .failed:
            return "一時的に情報を取得できませんでした"
        case .unavailable:
            if case .oversized? = record.metadataError {
                return "URLを保存しました"
            }
            if case .parseFailed? = record.metadataError {
                return "このURLは自動取得できません"
            }
            return unavailableDetailText(for: record)
        case .ready:
            return nil
        }
    }

    static func technicalErrorText(for error: MetadataError?) -> String? {
        guard let error else { return nil }
        switch error {
        case .timeout:
            return "タイムアウトしました (\(error.rawValue))"
        case .networkIO:
            return "通信に失敗しました (\(error.rawValue))"
        case .unsupportedScheme:
            return "対応していないURL形式です (\(error.rawValue))"
        case .schedulerUnavailable:
            return "iOSのバックグラウンド実行制約で保留されています (\(error.rawValue))"
        case .http404:
            return "ページが見つかりませんでした (\(error.rawValue))"
        case .http4xx:
            return "アクセス制限のため取得できませんでした (\(error.rawValue))"
        case .http5xx:
            return "一時的なサーバーエラーでした (\(error.rawValue))"
        case .parseFailed:
            return "ページ形式を解析できませんでした (\(error.rawValue))"
        case .nonHTML:
            return "HTMLページではないため自動取得できません (\(error.rawValue))"
        case .oversized:
            return "ページが大きいため、内容の自動取得はできませんでした。"
        case .tooManyRedirects:
            return "リダイレクトが多すぎて取得できません (\(error.rawValue))"
        }
    }

    private static func unavailableShortText(for record: URLRecord) -> String {
        switch record.serviceType {
        case .x, .instagram:
            return "自動取得に制限あり"
        default:
            return "自動取得できません"
        }
    }

    private static func unavailableDetailText(for record: URLRecord) -> String {
        switch record.serviceType {
        case .x:
            return "Xの投稿はアクセス制限により自動取得に制限があります"
        case .instagram:
            return "Instagramの投稿は公開状態や制限により自動取得に制限があります"
        default:
            return "このURLは自動取得できません"
        }
    }
}
