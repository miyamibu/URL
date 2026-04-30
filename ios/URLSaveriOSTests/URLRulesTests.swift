import XCTest

final class URLRulesTests: XCTestCase {
    func testNormalizeAppliesPhaseRules() {
        XCTAssertEqual(
            URLRules.normalize("HTTPS://Example.COM:443/path/?a=1#frag"),
            "https://example.com/path?a=1"
        )
        XCTAssertEqual(
            URLRules.normalize("HTTPS://Example.com:443/?q=1"),
            "https://example.com/?q=1"
        )
    }

    func testNormalizeRejectsMissingSchemeAndNonHTTPS() {
        XCTAssertNil(URLRules.normalize("example.com/path"))
        XCTAssertNil(URLRules.normalize("http://example.com/path"))
        XCTAssertNil(URLRules.normalize("ftp://example.com/path"))
    }

    func testNormalizeAllowsLoopbackHTTP() {
        XCTAssertEqual(
            URLRules.normalize("http://127.0.0.1/path"),
            "http://127.0.0.1/path"
        )
    }

    func testDisplayURLKeepsOnlyYouTubeVQuery() {
        let display = URLRules.toDisplayURL(
            normalizedURL: "https://www.youtube.com/watch?v=abc123&t=9",
            service: .youtube
        )
        XCTAssertEqual(display, "www.youtube.com/watch?v=abc123")
    }

    func testExtractFromCandidateGroupsFallsBackByPriority() {
        let groups = ShareCandidateGroups(
            extraCandidates: ["broken https:///oops"],
            providerTextCandidates: ["https://example.com/path"],
            streamCandidates: ["https://ignored.example"],
            directURLCandidates: []
        )

        XCTAssertEqual(
            URLRules.extractFromCandidateGroups(groups),
            .found("https://example.com/path")
        )
    }

    func testExtractAllDeduplicatesNormalizedURLs() {
        let groups = ShareCandidateGroups(
            extraCandidates: [
                "HTTPS://Example.com:443/path?x=1",
                "https://example.com/path?x=1",
                "https://example.com/next",
            ]
        )

        XCTAssertEqual(
            URLRules.extractAllFromCandidateGroups(groups).urls,
            ["https://example.com/path?x=1", "https://example.com/next"]
        )
    }

    func testManualInputDifferentiatesNoURLAndInvalidURL() {
        XCTAssertEqual(URLRules.extractForManualInput("hello"), .noURLFound)
        XCTAssertEqual(URLRules.extractForManualInput("https:///broken"), .invalidURL)
    }

    func testManualInputTooLargeReturnsExplicitError() {
        let oversized = String(repeating: "a", count: URLRules.maxInputTextBytes + 1)
        XCTAssertEqual(URLRules.extractForManualInput(oversized), .inputTooLarge)
    }

    func testExtractAllCapsToMaxBatchSize() {
        let payload = (0..<(URLRules.maxBatchSaveURLsPerIntake + 5))
            .map { "https://example.com/item-\($0)" }
            .joined(separator: "\n")
        let groups = ShareCandidateGroups(extraCandidates: [payload])

        let extracted = URLRules.extractAllFromCandidateGroups(groups)

        XCTAssertEqual(extracted.urls.count, URLRules.maxBatchSaveURLsPerIntake)
        XCTAssertTrue(extracted.truncatedToMaxURLs)
        XCTAssertEqual(extracted.urls.first, "https://example.com/item-0")
    }

    func testOversizedCandidateGroupReturnsExplicitError() {
        let oversized = String(repeating: "a", count: URLRules.maxInputTextBytes + 1)
        let groups = ShareCandidateGroups(extraCandidates: [oversized])
        XCTAssertEqual(URLRules.extractFromCandidateGroups(groups), .inputTooLarge)
    }
}
