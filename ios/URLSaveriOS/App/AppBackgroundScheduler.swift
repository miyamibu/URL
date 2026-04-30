import BackgroundTasks
import Foundation

extension BGAppRefreshTask: BackgroundTaskHandling {}

enum AppBackgroundScheduler {
    static let identifier = "jp.mimac.urlsaver.metadata.refresh"

    static func register(services: AppServices) {
        BGTaskScheduler.shared.register(forTaskWithIdentifier: identifier, using: nil) { task in
            handle(task: task as! BGAppRefreshTask, services: services)
        }
    }

    static func schedule() {
        let request = BGAppRefreshTaskRequest(identifier: identifier)
        request.earliestBeginDate = Date(timeIntervalSinceNow: 15 * 60)
        try? BGTaskScheduler.shared.submit(request)
    }

    private static func handle(task: BGAppRefreshTask, services: AppServices) {
        schedule()
        BackgroundTaskRunner.run(task: task) {
            await services.metadataCoordinator.processBacklog(limit: 12)
        }
    }
}
