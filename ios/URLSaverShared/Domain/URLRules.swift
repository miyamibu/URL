import Foundation
import CryptoKit

enum URLRules {
    static let maxInputTextBytes = 256 * 1024
    static let maxExtractedURLsPerInput = 50
    static let maxBatchSaveURLsPerIntake = 50
    static let textCardHost = "text.rinbam.local"

    struct ExtractedURLBatch: Equatable, Sendable {
        let urls: [String]
        let truncatedToMaxURLs: Bool
        let sawOversizedInput: Bool
    }

    static func extractFromCandidateGroups(_ candidateGroups: ShareCandidateGroups) -> ShareExtractionResult {
        var hasAnyCandidateString = false
        var sawOversizedInput = false

        for sourceCandidates in candidateGroups.orderedGroups {
            if sourceCandidates.isEmpty { continue }
            hasAnyCandidateString = true

            switch findFirstValidURL(in: sourceCandidates) {
            case .found(let valid):
                return .found(valid)
            case .inputTooLarge:
                sawOversizedInput = true
            case .noURLFound, .invalidURL:
                break
            case nil:
                break
            }
        }

        if sawOversizedInput { return .inputTooLarge }
        return hasAnyCandidateString ? .invalidURL : .noURLFound
    }

    static func extractAllFromCandidateGroups(_ candidateGroups: ShareCandidateGroups) -> ExtractedURLBatch {
        var normalizedURLs = OrderedSet<String>()
        var truncatedToMaxURLs = false
        var sawOversizedInput = false

        for sourceCandidates in candidateGroups.orderedGroups {
            for candidateText in sourceCandidates {
                guard isWithinInputTextByteLimit(candidateText) else {
                    sawOversizedInput = true
                    continue
                }
                for candidateURL in extractURLCandidates(from: candidateText) {
                    guard let normalized = normalize(candidateURL) else { continue }
                    guard normalizedURLs.append(normalized) else { continue }
                    if normalizedURLs.elements.count > maxExtractedURLsPerInput {
                        normalizedURLs.removeLast()
                        truncatedToMaxURLs = true
                        return ExtractedURLBatch(
                            urls: Array(normalizedURLs.elements.prefix(maxBatchSaveURLsPerIntake)),
                            truncatedToMaxURLs: true,
                            sawOversizedInput: sawOversizedInput
                        )
                    }
                }
            }
        }
        return ExtractedURLBatch(
            urls: Array(normalizedURLs.elements.prefix(maxBatchSaveURLsPerIntake)),
            truncatedToMaxURLs: truncatedToMaxURLs || normalizedURLs.elements.count > maxBatchSaveURLsPerIntake,
            sawOversizedInput: sawOversizedInput
        )
    }

    static func countValidURLs(in candidateGroups: ShareCandidateGroups) -> Int {
        extractAllFromCandidateGroups(candidateGroups).urls.count
    }

    static func extractForManualInput(_ input: String) -> ShareExtractionResult {
        let trimmed = input.trimmingCharacters(in: .whitespacesAndNewlines)
        if trimmed.isEmpty {
            return .noURLFound
        }

        guard isWithinInputTextByteLimit(trimmed) else {
            return .inputTooLarge
        }

        switch findFirstValidURL(in: [trimmed]) {
        case .found(let valid):
            return .found(valid)
        case .inputTooLarge:
            return .inputTooLarge
        case .noURLFound, .invalidURL:
            return .invalidURL
        case nil:
            return extractURLCandidates(from: trimmed).isEmpty ? .noURLFound : .invalidURL
        }
    }

    static func extractTextFallback(from candidateGroups: ShareCandidateGroups) -> String? {
        for group in candidateGroups.orderedGroups {
            for candidate in group {
                if let text = normalizeTextCardBody(candidate) {
                    return text
                }
            }
        }
        return nil
    }

    static func extractMemoWithoutURLs(from candidateGroups: ShareCandidateGroups) -> String? {
        for group in candidateGroups.orderedGroups {
            for candidate in group {
                if let memo = extractMemoWithoutURLs(candidate) {
                    return memo
                }
            }
        }
        return nil
    }

    static func extractMemoWithoutURLs(_ input: String) -> String? {
        guard let normalized = normalizeTextCardBody(input) else { return nil }
        let nsText = normalized as NSString
        let validRanges = extractURLCandidateRanges(from: normalized)
            .filter { range in
                normalize(nsText.substring(with: range)) != nil
            }
        guard !validRanges.isEmpty else { return nil }

        var pieces: [String] = []
        var index = 0
        for range in validRanges {
            if index < range.location {
                pieces.append(nsText.substring(with: NSRange(location: index, length: range.location - index)))
            }
            pieces.append("\n")
            index = range.location + range.length
        }
        if index < nsText.length {
            pieces.append(nsText.substring(from: index))
        }

        let memo = pieces
            .joined()
            .components(separatedBy: .newlines)
            .map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }
            .filter { !$0.isEmpty }
            .joined(separator: "\n")
            .trimmingCharacters(in: .whitespacesAndNewlines)
        guard !memo.isEmpty else { return nil }
        return String(memo.prefix(2_000))
    }

    static func normalizeTextCardBody(_ input: String) -> String? {
        let normalized = input
            .replacingOccurrences(of: "\r\n", with: "\n")
            .replacingOccurrences(of: "\r", with: "\n")
            .trimmingCharacters(in: .whitespacesAndNewlines)
        guard !normalized.isEmpty, isWithinInputTextByteLimit(normalized) else {
            return nil
        }
        return normalized
    }

    static func parseTextCard(_ input: String) -> ParsedURL? {
        guard let body = normalizeTextCardBody(input) else { return nil }
        let hash = sha256(body)
        let normalized = "https://\(textCardHost)/note/\(hash)"
        return ParsedURL(
            originalURL: body,
            normalizedURL: normalized,
            displayURL: "テキスト",
            openURL: normalized,
            normalizedHost: textCardHost,
            rawSourceHost: textCardHost,
            serviceType: .web,
            contentContext: .post
        )
    }

    static func isTextCardHost(_ host: String?) -> Bool {
        host?.caseInsensitiveCompare(textCardHost) == .orderedSame
    }

    static func textCardTitle(_ body: String) -> String {
        let firstLine = body
            .split(whereSeparator: \.isNewline)
            .map { String($0).trimmingCharacters(in: .whitespacesAndNewlines) }
            .first(where: { !$0.isEmpty }) ?? body.trimmingCharacters(in: .whitespacesAndNewlines)
        let collapsed = firstLine
            .split(whereSeparator: \.isWhitespace)
            .joined(separator: " ")
        let title = String(collapsed.prefix(120))
        return title.isEmpty ? "テキスト" : title
    }

    static func parseURL(_ original: String) -> ParsedURL? {
        guard let normalized = normalize(original),
              let components = URLComponents(string: normalized),
              let host = components.host,
              !host.isEmpty else {
            return nil
        }

        let service = classifyService(host: host)
        let contentContext = classifyContent(service: service, components: components)
        let display = toDisplayURL(normalizedURL: normalized, service: service)
        return ParsedURL(
            originalURL: original,
            normalizedURL: normalized,
            displayURL: display,
            openURL: normalized,
            normalizedHost: host,
            rawSourceHost: host,
            serviceType: service,
            contentContext: contentContext
        )
    }

    static func normalize(_ raw: String) -> String? {
        let value = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        guard var components = URLComponents(string: value),
              let scheme = components.scheme?.lowercased(),
              let host = components.host?.lowercased(),
              !host.isEmpty else {
            return nil
        }

        let absolute = value.contains("://")
        guard absolute else { return nil }
        guard scheme == "https" || (scheme == "http" && isLoopbackHost(host)) else {
            return nil
        }
        guard components.user == nil, components.password == nil else { return nil }

        components.scheme = scheme
        components.host = host
        components.fragment = nil

        if (scheme == "https" && components.port == 443) || (scheme == "http" && components.port == 80) {
            components.port = nil
        }

        var path = components.percentEncodedPath.isEmpty ? "/" : components.percentEncodedPath
        if path != "/" && path.hasSuffix("/") {
            path = String(path.drop(while: { $0 == "/" }).dropLast())
            path = "/" + path
            if path == "/" {
                path = "/"
            }
        }
        components.percentEncodedPath = path

        return components.url?.absoluteString
    }

    static func toDisplayURL(normalizedURL: String, service: ServiceType) -> String {
        guard let components = URLComponents(string: normalizedURL),
              let host = components.host else {
            return normalizedURL
        }
        let path = components.percentEncodedPath.isEmpty ? "/" : components.percentEncodedPath
        let query: String?
        switch service {
        case .youtube:
            query = components.queryItems?
                .first(where: { $0.name == "v" && ($0.value?.isEmpty == false) })
                .flatMap { item in
                    guard let value = item.value else { return nil }
                    return "v=\(value)"
                }
        default:
            query = nil
        }

        if let query {
            return "\(host)\(path)?\(query)"
        }
        return "\(host)\(path)"
    }

    static func effectiveTitle(
        userTitle: String?,
        fetchedTitle: String?,
        serviceType: ServiceType,
        normalizedHost: String
    ) -> String {
        if let userTitle = userTitle, !userTitle.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            return userTitle
        }
        if let fetchedTitle = fetchedTitle, !fetchedTitle.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            return fetchedTitle
        }
        if isTextCardHost(normalizedHost) {
            return "テキスト"
        }
        if serviceType != .web {
            return "\(serviceType.displayName)のリンク"
        }
        if !normalizedHost.isEmpty {
            return normalizedHost
        }
        return "保存したリンク"
    }

    static func normalizeUserTitle(_ input: String?) -> String? {
        guard let trimmed = input?.trimmingCharacters(in: .whitespacesAndNewlines),
              !trimmed.isEmpty,
              trimmed.count <= 120 else {
            return nil
        }
        return trimmed
    }

    static func normalizeMemo(_ input: String?) -> String {
        let trimmed = input?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        return trimmed.isEmpty ? "" : trimmed
    }

    static func isMemoLengthValid(_ input: String) -> Bool {
        input.trimmingCharacters(in: .whitespacesAndNewlines).count <= 2_000
    }

    static func isTitleLengthValid(_ input: String) -> Bool {
        input.trimmingCharacters(in: .whitespacesAndNewlines).count <= 120
    }

    static func extractXStatusID(from url: String) -> String? {
        guard let components = URLComponents(string: url),
              let host = components.host?.lowercased(),
              host == "x.com" || host.hasSuffix("twitter.com") else {
            return nil
        }

        let segments = components.path.split(separator: "/").map(String.init)
        if segments.count >= 3, segments[1].lowercased() == "status" {
            return segments[2]
        }
        if segments.count >= 4,
           segments[0].lowercased() == "i",
           segments[1].lowercased() == "web",
           segments[2].lowercased() == "status" {
            return segments[3]
        }
        if segments.count >= 3,
           segments[0].lowercased() == "i",
           segments[1].lowercased() == "status" {
            return segments[2]
        }
        return nil
    }

    private static func findFirstValidURL(in sourceCandidates: [String]) -> ShareExtractionResult? {
        var sawOversizedInput = false
        var scannedCandidateURLs = 0
        for candidateText in sourceCandidates {
            guard isWithinInputTextByteLimit(candidateText) else {
                sawOversizedInput = true
                continue
            }
            for candidateURL in extractURLCandidates(from: candidateText) {
                scannedCandidateURLs += 1
                if scannedCandidateURLs > maxExtractedURLsPerInput {
                    return nil
                }
                if normalize(candidateURL) != nil {
                    return .found(candidateURL)
                }
            }
        }
        return sawOversizedInput ? .inputTooLarge : nil
    }

    private static func sha256(_ value: String) -> String {
        let digest = SHA256.hash(data: Data(value.utf8))
        return digest.map { String(format: "%02x", $0) }.joined()
    }

    private static func extractURLCandidates(from text: String) -> [String] {
        let nsText = text as NSString
        return extractURLCandidateRanges(from: text).map {
            nsText.substring(with: $0)
        }
    }

    private static func extractURLCandidateRanges(from text: String) -> [NSRange] {
        let pattern = #"https?://[^\s<>()\[\]"']+"#
        guard let regex = try? NSRegularExpression(pattern: pattern, options: [.caseInsensitive]) else {
            return []
        }
        let nsText = text as NSString
        return regex.matches(in: text, range: NSRange(location: 0, length: nsText.length)).map(\.range)
    }

    private static func isLoopbackHost(_ host: String) -> Bool {
        host == "127.0.0.1" || host == "localhost" || host == "::1"
    }

    private static func isWithinInputTextByteLimit(_ text: String) -> Bool {
        text.lengthOfBytes(using: .utf8) <= maxInputTextBytes
    }

    private static func classifyService(host: String) -> ServiceType {
        let lowered = host.lowercased()
        switch lowered {
        case _ where lowered.hasSuffix("youtube.com") || lowered == "youtu.be":
            return .youtube
        case _ where lowered.hasSuffix("tiktok.com"):
            return .tiktok
        case _ where lowered == "x.com" || lowered.hasSuffix("twitter.com"):
            return .x
        case _ where lowered.hasSuffix("instagram.com"):
            return .instagram
        default:
            return .web
        }
    }

    private static func classifyContent(service: ServiceType, components: URLComponents) -> ContentContext {
        let path = components.path.lowercased()
        switch service {
        case .youtube:
            if path.hasPrefix("/shorts/") { return .shorts }
            if path.hasPrefix("/live/") { return .live }
            if path.hasPrefix("/watch") || components.queryItems?.contains(where: { $0.name == "v" }) == true {
                return .video
            }
            return .standard
        case .tiktok:
            if path.contains("/video/") { return .video }
            if path.contains("/music/") { return .music }
            if path.contains("/tag/") { return .hashtag }
            return .standard
        case .x:
            return path.contains("/status/") ? .post : .standard
        case .instagram:
            if path.hasPrefix("/reel/") { return .reel }
            if path.hasPrefix("/p/") { return .post }
            if path.hasPrefix("/@") { return .profile }
            return .standard
        case .web, .all:
            return .standard
        }
    }
}

private struct OrderedSet<Element: Hashable> {
    private var seen = Set<Element>()
    private(set) var elements: [Element] = []

    @discardableResult
    mutating func append(_ element: Element) -> Bool {
        guard seen.insert(element).inserted else { return false }
        elements.append(element)
        return true
    }

    mutating func removeLast() {
        guard let last = elements.popLast() else { return }
        seen.remove(last)
    }
}
