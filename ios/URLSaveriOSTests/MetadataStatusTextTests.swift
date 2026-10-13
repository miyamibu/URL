import XCTest
@testable import URLSaveriOS

final class MetadataStatusTextTests: XCTestCase {
    private let backgroundTaskWaitTimeout: TimeInterval = 5.0

    func testDisplayTitlePreservesRealTitlesAndUsesBoundedCaptionWhenUntitled() {
        XCTAssertEqual(preferredDisplayTitle(for: titleRecord(service: .web, title: "実際の記事タイトル", body: "記事本文")), "実際の記事タイトル")
        XCTAssertEqual(preferredDisplayTitle(for: titleRecord(service: .youtube, title: "動画タイトル", body: "動画の説明")), "動画タイトル")
        XCTAssertEqual(preferredDisplayTitle(for: titleRecord(service: .web, title: nil, body: "本文の\n冒頭だけを表示")), "本文の 冒頭だけを表示")
        let caption = String(repeating: "猫", count: 130)
        XCTAssertEqual(preferredDisplayTitle(for: titleRecord(service: .instagram, title: "@example", body: caption)), String(caption.prefix(100)) + "…")
        XCTAssertEqual(preferredDisplayTitle(for: titleRecord(service: .instagram, title: "夕暮れの散歩", body: "長い本文")), "夕暮れの散歩")
        XCTAssertEqual(preferredDisplayTitle(for: titleRecord(service: .instagram, title: "夕暮れの散歩 • Instagram photos and videos", body: "長い本文")), "夕暮れの散歩")
        XCTAssertEqual(preferredDisplayTitle(for: titleRecord(service: .instagram, title: "100 likesのお祝い", body: "長い本文")), "100 likesのお祝い")
        XCTAssertEqual(preferredDisplayTitle(for: titleRecord(service: .instagram, title: "いいねをありがとう", body: "長い本文")), "いいねをありがとう")
        XCTAssertEqual(preferredDisplayTitle(for: titleRecord(service: .instagram, title: "Likes, comments and community", body: "長い本文")), "Likes, comments and community")
    }

    func testDisplayTitleCleansStoredInstagramEnvelopeAndRespectsUserTitle() {
        let body = "1,234 likes, 56 comments - example on October 12, 2026: \"秋の散歩。2026年の記録。\""
        XCTAssertEqual(preferredDisplayTitle(for: titleRecord(service: .instagram, title: "example on Instagram", body: body)), "秋の散歩。2026年の記録。")
        XCTAssertEqual(preferredDisplayTitle(for: titleRecord(service: .instagram, title: "example on Instagram", body: body, userTitle: "自分のタイトル")), "自分のタイトル")
        XCTAssertEqual(preferredDisplayTitle(for: titleRecord(service: .instagram, title: "@example", body: "プロフィール本文", context: .profile)), "@example")
        XCTAssertEqual(preferredDisplayTitle(for: titleRecord(service: .instagram, title: "2026年10月12日・123件のいいね、8件のコメント Instagram", body: nil)), "Instagramのリンク")
    }

    private func titleRecord(service: URLSaveriOS.ServiceType, title: String?, body: String?, userTitle: String? = nil, context: URLSaveriOS.ContentContext = .standard) -> URLSaveriOS.URLRecord {
        URLSaveriOS.URLRecord(
            id: 1, originalURL: "https://example.com/post", normalizedURL: "https://example.com/post",
            displayURL: "example.com/post", openURL: "https://example.com/post",
            normalizedHost: "example.com", rawSourceHost: "example.com", collectionID: 1,
            serviceType: service, contentContext: context, userTitle: userTitle, fetchedTitle: title,
            fetchedBody: body, fetchedBodyKind: nil, bodySummary: nil, description: nil, memo: "",
            thumbnailURL: nil, badgeImageURL: nil, canonicalID: nil, metadataState: .ready,
            metadataError: nil, metadataRequestedAt: nil, metadataFetchedAt: nil,
            recordState: .active, localProvenanceCount: 1, sharedReferenceCount: 0,
            createdAt: .distantPast, updatedAt: .distantPast, archivedAt: nil, pendingDeletionUntil: nil
        )
    }

    func testListAndDetailCopyMatchesContract() {
        let record = URLRecord(
            id: 1,
            originalURL: "https://example.com",
            normalizedURL: "https://example.com",
            displayURL: "example.com/",
            openURL: "https://example.com",
            normalizedHost: "example.com",
            rawSourceHost: "example.com",
            collectionID: 1,
            serviceType: .web,
            contentContext: .standard,
            userTitle: nil,
            fetchedTitle: nil,
            fetchedBody: nil,
            fetchedBodyKind: nil,
            bodySummary: nil,
            description: nil,
            memo: "",
            thumbnailURL: nil,
            badgeImageURL: nil,
            canonicalID: nil,
            metadataState: .failed,
            metadataError: .timeout,
            metadataRequestedAt: nil,
            metadataFetchedAt: nil,
            recordState: .active,
            localProvenanceCount: 1,
            sharedReferenceCount: 0,
            createdAt: .distantPast,
            updatedAt: .distantPast,
            archivedAt: nil,
            pendingDeletionUntil: nil
        )

        XCTAssertEqual(MetadataStatusText.listText(for: record), "一時的に取得できません")
        XCTAssertEqual(MetadataStatusText.detailText(for: record), "一時的に情報を取得できませんでした")
        XCTAssertEqual(MetadataStatusText.technicalErrorText(for: .parseFailed), "ページ形式を解析できませんでした (PARSE_FAILED)")

        let oversized = URLRecord(
            id: 2,
            originalURL: "https://example.com/large",
            normalizedURL: "https://example.com/large",
            displayURL: "example.com/large",
            openURL: "https://example.com/large",
            normalizedHost: "example.com",
            rawSourceHost: "example.com",
            collectionID: 1,
            serviceType: .web,
            contentContext: .standard,
            userTitle: nil,
            fetchedTitle: nil,
            fetchedBody: nil,
            fetchedBodyKind: nil,
            bodySummary: nil,
            description: nil,
            memo: "",
            thumbnailURL: nil,
            badgeImageURL: nil,
            canonicalID: nil,
            metadataState: .unavailable,
            metadataError: .oversized,
            metadataRequestedAt: nil,
            metadataFetchedAt: nil,
            recordState: .active,
            localProvenanceCount: 1,
            sharedReferenceCount: 0,
            createdAt: .distantPast,
            updatedAt: .distantPast,
            archivedAt: nil,
            pendingDeletionUntil: nil
        )
        XCTAssertEqual(MetadataStatusText.detailText(for: oversized), "URLを保存しました")
        XCTAssertEqual(MetadataStatusText.technicalErrorText(for: .oversized), "ページが大きいため、内容の自動取得はできませんでした。")
    }

    func testBackgroundTaskCompletionWaitsForSuccessfulBacklog() async {
        let task = FakeBackgroundTask()
        let started = expectation(description: "started")
        let completed = expectation(description: "completed")
        task.onComplete = { _ in completed.fulfill() }
        let gate = AsyncGate()

        BackgroundTaskRunner.run(task: task) {
            started.fulfill()
            await gate.wait()
            return true
        }

        await fulfillment(of: [started], timeout: backgroundTaskWaitTimeout)
        XCTAssertNil(task.completedSuccess)

        await gate.resume()
        await fulfillment(of: [completed], timeout: backgroundTaskWaitTimeout)
        XCTAssertEqual(task.completedSuccess, true)
        XCTAssertEqual(task.completedCallCount, 1)
        XCTAssertNil(task.expirationHandler)
    }

    func testBackgroundTaskCompletionReportsFailure() async {
        let task = FakeBackgroundTask()
        let completed = expectation(description: "completed")
        task.onComplete = { _ in completed.fulfill() }

        BackgroundTaskRunner.run(task: task) { false }

        await fulfillment(of: [completed], timeout: backgroundTaskWaitTimeout)
        XCTAssertEqual(task.completedSuccess, false)
        XCTAssertEqual(task.completedCallCount, 1)
    }

    func testBackgroundTaskExpirationCancelsAndCompletesFalseOnce() async {
        let task = FakeBackgroundTask()
        let completed = expectation(description: "completed")
        task.onComplete = { _ in completed.fulfill() }
        let started = expectation(description: "started")

        BackgroundTaskRunner.run(task: task) {
            started.fulfill()
            while !Task.isCancelled {
                try? await Task.sleep(nanoseconds: 10_000_000)
            }
            return false
        }

        await fulfillment(of: [started], timeout: backgroundTaskWaitTimeout)
        task.expirationHandler?()
        await fulfillment(of: [completed], timeout: backgroundTaskWaitTimeout)
        XCTAssertEqual(task.completedSuccess, false)
        XCTAssertEqual(task.completedCallCount, 1)
        XCTAssertNil(task.expirationHandler)
    }

    func testBackgroundTaskExpirationDoesNotCompleteBeforeOperationReturns() async {
        let task = FakeBackgroundTask()
        let started = expectation(description: "started")
        let completed = expectation(description: "completed")
        task.onComplete = { _ in completed.fulfill() }
        let gate = AsyncGate()

        BackgroundTaskRunner.run(task: task) {
            started.fulfill()
            while !Task.isCancelled {
                try? await Task.sleep(nanoseconds: 10_000_000)
            }
            await gate.wait()
            return false
        }

        await fulfillment(of: [started], timeout: backgroundTaskWaitTimeout)
        task.expirationHandler?()
        XCTAssertNil(task.completedSuccess)

        await gate.resume()
        await fulfillment(of: [completed], timeout: backgroundTaskWaitTimeout)
        XCTAssertEqual(task.completedSuccess, false)
        XCTAssertEqual(task.completedCallCount, 1)
    }
}

private final class FakeBackgroundTask: BackgroundTaskHandling {
    var expirationHandler: (() -> Void)?
    var completedSuccess: Bool?
    var completedCallCount = 0
    var onComplete: ((Bool) -> Void)?

    func setTaskCompleted(success: Bool) {
        completedCallCount += 1
        completedSuccess = success
        onComplete?(success)
    }
}

private actor AsyncGate {
    private var isResumed = false
    private var continuations: [CheckedContinuation<Void, Never>] = []

    func wait() async {
        if isResumed {
            return
        }
        await withCheckedContinuation { continuation in
            continuations.append(continuation)
        }
    }

    func resume() async {
        isResumed = true
        let pending = continuations
        continuations.removeAll()
        for continuation in pending {
            continuation.resume()
        }
    }
}
