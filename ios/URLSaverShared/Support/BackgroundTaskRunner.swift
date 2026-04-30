import Foundation

protocol BackgroundTaskHandling: AnyObject {
    var expirationHandler: (() -> Void)? { get set }
    func setTaskCompleted(success: Bool)
}

enum BackgroundTaskRunner {
    static func run(
        task: BackgroundTaskHandling,
        operation: @escaping @Sendable () async -> Bool
    ) {
        let completion = CompletionReporter(task: task)
        let workTask = Task(priority: .background) {
            let succeeded = await operation()
            completion.finish(success: succeeded && !Task.isCancelled)
        }

        task.expirationHandler = {
            workTask.cancel()
        }
    }
}

private final class CompletionReporter: @unchecked Sendable {
    private let lock = NSLock()
    private var task: BackgroundTaskHandling?
    private var completed = false

    init(task: BackgroundTaskHandling) {
        self.task = task
    }

    func finish(success: Bool) {
        lock.lock()
        guard !completed else {
            lock.unlock()
            return
        }
        completed = true
        let task = task
        self.task = nil
        lock.unlock()

        task?.expirationHandler = nil
        task?.setTaskCompleted(success: success)
    }
}
