import Foundation

struct UserProfile: Codable, Equatable, Sendable {
    var displayName: String
    var avatarImageData: Data?
    var updatedAt: Date?

    static let empty = UserProfile(displayName: "", avatarImageData: nil, updatedAt: nil)

    var trimmedDisplayName: String {
        displayName.trimmingCharacters(in: .whitespacesAndNewlines)
    }
}
