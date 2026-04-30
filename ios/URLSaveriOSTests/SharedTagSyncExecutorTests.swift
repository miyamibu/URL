import XCTest

final class SharedTagSyncExecutorTests: XCTestCase {
    func testSameUserTriggersCoalesceIntoSingleRerun() async {
        let firstStarted = expectation(description: "first started")
        let secondStarted = expectation(description: "second started")
        let driver = TestSharedTagSyncDriver(
            firstStarted: firstStarted,
            secondStarted: secondStarted
        )
        let executor = SharedTagSyncExecutor(driver: driver)

        await executor.enqueue(authUserID: "user-1")
        await fulfillment(of: [firstStarted], timeout: 1.0)

        await executor.enqueue(authUserID: "user-1")
        await executor.enqueue(authUserID: "user-1")

        let countAfterCoalesce = await driver.currentInvocationCount()
        XCTAssertEqual(countAfterCoalesce, 1)

        await driver.resumeNext()
        await fulfillment(of: [secondStarted], timeout: 1.0)
        let countAfterSecondStart = await driver.currentInvocationCount()
        XCTAssertEqual(countAfterSecondStart, 2)

        await driver.resumeNext()
        try? await Task.sleep(nanoseconds: 200_000_000)
        let finalCount = await driver.currentInvocationCount()
        XCTAssertEqual(finalCount, 2)
    }
}

private actor TestSharedTagSyncDriver: SharedTagSyncDriving {
    private let firstStarted: XCTestExpectation
    private let secondStarted: XCTestExpectation
    private var continuations: [CheckedContinuation<Void, Never>] = []
    private(set) var invocationCount = 0

    init(
        firstStarted: XCTestExpectation,
        secondStarted: XCTestExpectation
    ) {
        self.firstStarted = firstStarted
        self.secondStarted = secondStarted
    }

    func sync(authUserID: String) async -> Bool {
        _ = authUserID
        invocationCount += 1
        if invocationCount == 1 {
            firstStarted.fulfill()
        } else if invocationCount == 2 {
            secondStarted.fulfill()
        }

        await withCheckedContinuation { continuation in
            continuations.append(continuation)
        }
        return true
    }

    func resumeNext() {
        guard !continuations.isEmpty else { return }
        continuations.removeFirst().resume()
    }

    func currentInvocationCount() -> Int {
        invocationCount
    }
}
