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
            return "ページサイズが大きすぎて取得できません (\(error.rawValue))"
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
