import Foundation

struct SharedTagCloudState: Equatable, Sendable {
    let isConfigured: Bool
    let isSignedIn: Bool
    let signedInEmail: String?
}

enum ChatGptPersonalLinkSyncStatus: Equatable, Sendable {
    case idle
    case loading
    case success
    case partial
    case failure(String)
}

struct ChatGptPersonalLinkEligibility: Equatable, Sendable {
    let eligibleCount: Int
    let excludedCount: Int
    let exclusionReasons: [String: Int]

    static let empty = ChatGptPersonalLinkEligibility(
        eligibleCount: 0,
        excludedCount: 0,
        exclusionReasons: [:]
    )
}

struct ChatGptPersonalLinkSyncResult: Equatable, Sendable {
    let attemptedCount: Int
    let appliedCount: Int
    let excludedCount: Int
    let exclusionReasons: [String: Int]

    var status: ChatGptPersonalLinkSyncStatus {
        if attemptedCount == 0 || appliedCount == attemptedCount {
            return .success
        }
        return .partial
    }
}

enum ChatGptPersonalLinkSyncPolicy {
    static func eligibility(for entry: URLRecord) -> (eligible: Bool, reasons: [String]) {
        var reasons: [String] = []
        if entry.localProvenanceCount <= 0 {
            reasons.append("local_provenance_required")
        }
        if entry.sharedReferenceCount > 0 {
            reasons.append("shared_tag_allocation")
        }
        if entry.recordState != .active {
            reasons.append("active_only")
        }
        if entry.pendingDeletionUntil != nil {
            reasons.append("pending_delete")
        }
        return (reasons.isEmpty, reasons.sorted())
    }
}

enum SharedTagMemberRole: String, Codable, CaseIterable, Sendable {
    case owner
    case editor
    case viewer

    var displayName: String {
        switch self {
        case .owner: return "オーナー"
        case .editor: return "編集者"
        case .viewer: return "閲覧者"
        }
    }
}

struct SharedTagSummary: Identifiable, Equatable, Sendable {
    let remoteTagID: String
    let name: String
    let currentUserRole: SharedTagMemberRole?
    let activeURLCount: Int
    let lastSyncedAt: Date?

    var id: String { remoteTagID }
}

struct SharedTagMemberSummary: Identifiable, Equatable, Sendable {
    let tagID: String
    let userID: String
    let role: SharedTagMemberRole
    let isCurrentUser: Bool

    var id: String { "\(tagID):\(userID)" }
}

struct SharedTagGroupSummary: Identifiable, Equatable, Sendable {
    let remoteGroupID: String
    let name: String
    let currentUserRole: SharedTagMemberRole?
    let tagCount: Int
    let memberCount: Int
    let lastSyncedAt: Date?

    var id: String { remoteGroupID }
}

struct SharedTagGroupMemberSummary: Identifiable, Equatable, Sendable {
    let groupID: String
    let userID: String
    let displayName: String?
    let role: SharedTagMemberRole
    let isCurrentUser: Bool

    var id: String { "\(groupID):\(userID)" }
}

struct SharedTagGroupTagSummary: Identifiable, Equatable, Sendable {
    let groupID: String
    let remoteTagID: String
    let tagName: String
    let currentUserRole: SharedTagMemberRole?

    var id: String { "\(groupID):\(remoteTagID)" }
}

enum SharedTagMutationResult: Equatable, Sendable {
    case success
    case authRequired
    case failure(String)
}

enum SharedTagInviteCreationResult: Equatable, Sendable {
    case success(inviteURL: String, expiresAt: Date?)
    case authRequired
    case ownerOnly
    case syncPending
    case failure(String)
}

let maxSharedTagNameLength = 50
let sharedTagNormalizationVersion = URLRules.normalizationContractVersion
let sharedTagInviteRole = "editor"

struct SharedTagAuthSession: Codable, Equatable, Sendable {
    let authUserID: String
    let accessToken: String
    let refreshToken: String?
    let userEmail: String?
}

struct SharedTagAccountLocalCleanupState: Codable, Equatable, Sendable {
    var authUserID: String?
    var aiDataCleanupPending: Bool
    var signOutCleanupPending: Bool
    var syncCancellationCleanupPending: Bool
    var sharedDataCleanupPending: Bool
    var pendingInviteCleanupPending: Bool
    var entitlementCleanupPending: Bool
    var personalLinkSettingsCleanupPending: Bool

    init(
        authUserID: String? = nil,
        aiDataCleanupPending: Bool,
        signOutCleanupPending: Bool,
        syncCancellationCleanupPending: Bool = false,
        sharedDataCleanupPending: Bool = false,
        pendingInviteCleanupPending: Bool = false,
        entitlementCleanupPending: Bool = false,
        personalLinkSettingsCleanupPending: Bool = false
    ) {
        self.authUserID = authUserID
        self.aiDataCleanupPending = aiDataCleanupPending
        self.signOutCleanupPending = signOutCleanupPending
        self.syncCancellationCleanupPending = syncCancellationCleanupPending
        self.sharedDataCleanupPending = sharedDataCleanupPending
        self.pendingInviteCleanupPending = pendingInviteCleanupPending
        self.entitlementCleanupPending = entitlementCleanupPending
        self.personalLinkSettingsCleanupPending = personalLinkSettingsCleanupPending
    }

    var requiresCleanup: Bool {
        aiDataCleanupPending ||
            signOutCleanupPending ||
            syncCancellationCleanupPending ||
            sharedDataCleanupPending ||
            pendingInviteCleanupPending ||
            entitlementCleanupPending ||
            personalLinkSettingsCleanupPending
    }
}

enum SharedTagAccountDeletionResult: Equatable, Sendable {
    case success
    case authRequired
    case ownerTransferRequired
    case localCleanupRequired(SharedTagAccountLocalCleanupState)
    case failure(String)
}

enum SharedTagAuthResult: Equatable, Sendable {
    case success(email: String?)
    case needsEmailConfirmation
    case failure(String)
}

enum SharedTagInviteAcceptanceResult: Equatable, Sendable {
    case accepted(displayName: String, inviteType: SharedInviteType, role: SharedTagMemberRole?)
    case authRequired
    case invalidInvite
    case failure(String)
}

enum SharedTagInvitePreviewResult: Equatable, Sendable {
    case success(displayName: String, inviteType: SharedInviteType)
    case invalidInvite
    case failure(String)
}

enum SharedInviteType: String, Codable, Equatable, Sendable {
    case tag
    case group
}

/// Durable record of an in-flight remote account deletion. Persisted BEFORE the
/// remote delete call so a process loss after the server committed can still
/// converge through the status query without repeating remote deletion
/// (S1-REMOTE-MARKER-006).
struct SharedTagAccountDeletionRequestRecord: Codable, Equatable, Sendable {
    var authUserID: String
    var requestID: String
    var token: String

    enum CodingKeys: String, CodingKey {
        case authUserID = "auth_user_id"
        case requestID = "request_id"
        case token
    }
}

struct SharedTagAccountDeletionRequestGrant: Decodable, Sendable {
    let requestID: String
    let token: String

    enum CodingKeys: String, CodingKey {
        case requestID = "request_id"
        case token
    }
}

struct SharedTagAccountDeletionRemoteStatus: Decodable, Sendable {
    let status: String

    var isCompleted: Bool { status == "completed" }
}
