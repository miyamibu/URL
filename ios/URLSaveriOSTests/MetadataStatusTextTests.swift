import XCTest
@testable import URLSaveriOS

final class MetadataStatusTextTests: XCTestCase {
    func testListAndDetailCopyMatchesContract() {
        let record = URLRecord(
            id: 1,
            originalURL: "https://example.com",
            normalizedURL: "https://example.com",
            displayURL: "example.com/",
            openURL: "https://example.com",
            normalizedHost: "example.com",
            rawSourceHost: "example.com",
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

        await fulfillment(of: [started], timeout: 1.0)
        XCTAssertNil(task.completedSuccess)

        await gate.resume()
        await fulfillment(of: [completed], timeout: 1.0)
        XCTAssertEqual(task.completedSuccess, true)
        XCTAssertEqual(task.completedCallCount, 1)
        XCTAssertNil(task.expirationHandler)
    }

    func testBackgroundTaskCompletionReportsFailure() async {
        let task = FakeBackgroundTask()
        let completed = expectation(description: "completed")
        task.onComplete = { _ in completed.fulfill() }

        BackgroundTaskRunner.run(task: task) { false }

        await fulfillment(of: [completed], timeout: 1.0)
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

        await fulfillment(of: [started], timeout: 1.0)
        task.expirationHandler?()
        await fulfillment(of: [completed], timeout: 1.0)
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

        await fulfillment(of: [started], timeout: 1.0)
        task.expirationHandler?()
        XCTAssertNil(task.completedSuccess)

        await gate.resume()
        await fulfillment(of: [completed], timeout: 1.0)
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
