import BackgroundTasks
import Foundation
import OSLog

extension BGAppRefreshTask: BackgroundTaskHandling {}

enum AppBackgroundScheduler {
    static let identifier = "jp.mimac.urlsaver.metadata.refresh"
    private static let logger = Logger(subsystem: "com.mibu.codebridge.ios", category: "BackgroundTasks")

    static func register(services: AppServices) {
        let registered = BGTaskScheduler.shared.register(forTaskWithIdentifier: identifier, using: nil) { task in
            guard let refreshTask = task as? BGAppRefreshTask else {
                logger.error("Unexpected background task type for \(identifier, privacy: .public)")
                task.setTaskCompleted(success: false)
                return
            }
            handle(task: refreshTask, services: services)
        }
        if !registered {
            logger.error("Failed to register background task \(identifier, privacy: .public)")
        }
    }

    static func schedule() {
        let request = BGAppRefreshTaskRequest(identifier: identifier)
        request.earliestBeginDate = Date(timeIntervalSinceNow: 15 * 60)
        do {
            try BGTaskScheduler.shared.submit(request)
        } catch {
            logger.error("Failed to schedule background refresh: \(error.localizedDescription, privacy: .public)")
        }
    }

    private static func handle(task: BGAppRefreshTask, services: AppServices) {
        schedule()
        BackgroundTaskRunner.run(task: task) {
            await services.metadataCoordinator.processBacklog(limit: 12)
        }
    }
}
