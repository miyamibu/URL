import Foundation

final class UserProfileStore: @unchecked Sendable {
    private let fileURL: URL
    private let encoder: JSONEncoder
    private let decoder: JSONDecoder

    init(fileURL: URL? = nil, fileManager: FileManager = .default) {
        if let fileURL {
            self.fileURL = fileURL
        } else {
            let baseDirectory = try! fileManager.url(
                for: .applicationSupportDirectory,
                in: .userDomainMask,
                appropriateFor: nil,
                create: true
            ).appendingPathComponent("URLSaveriOS", isDirectory: true)
            try? fileManager.createDirectory(at: baseDirectory, withIntermediateDirectories: true)
            self.fileURL = baseDirectory.appendingPathComponent("user-profile.json")
        }

        let encoder = JSONEncoder()
        encoder.dateEncodingStrategy = .iso8601
        self.encoder = encoder

        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .iso8601
        self.decoder = decoder
    }

    func load() throws -> UserProfile {
        guard FileManager.default.fileExists(atPath: fileURL.path) else {
            return .empty
        }
        let data = try Data(contentsOf: fileURL)
        return try decoder.decode(UserProfile.self, from: data)
    }

    func save(_ profile: UserProfile) throws {
        let data = try encoder.encode(profile)
        try data.write(to: fileURL, options: [.atomic])
    }
}
