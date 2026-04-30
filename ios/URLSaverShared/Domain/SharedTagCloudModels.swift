import Foundation

struct SharedTagCloudState: Equatable, Sendable {
    let isConfigured: Bool
    let isSignedIn: Bool
    let signedInEmail: String?
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
let sharedTagNormalizationVersion = 1
let sharedTagInviteRole = "editor"

struct SharedTagAuthSession: Codable, Equatable, Sendable {
    let authUserID: String
    let accessToken: String
    let refreshToken: String?
    let userEmail: String?
}

enum SharedTagAccountDeletionResult: Equatable, Sendable {
    case success
    case authRequired
    case ownerTransferRequired
    case failure(String)
}

enum SharedTagAuthResult: Equatable, Sendable {
    case success(email: String?)
    case needsEmailConfirmation
    case failure(String)
}

enum SharedTagInviteAcceptanceResult: Equatable, Sendable {
    case accepted(tagName: String, role: SharedTagMemberRole?)
    case authRequired
    case invalidInvite
    case failure(String)
}
