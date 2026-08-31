import SwiftUI
import UIKit
import UserNotifications

extension Notification.Name {
    static let openSharedTagCloudFromNotification = Notification.Name("openSharedTagCloudFromNotification")
}

final class URLSaverNotificationDelegate: NSObject, UIApplicationDelegate, UNUserNotificationCenterDelegate {
    func application(
        _ application: UIApplication,
        didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil
    ) -> Bool {
        UNUserNotificationCenter.current().delegate = self
        return true
    }

    nonisolated func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        willPresent notification: UNNotification
    ) async -> UNNotificationPresentationOptions {
        [.banner, .list, .sound]
    }

    nonisolated func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        didReceive response: UNNotificationResponse
    ) async {
        guard response.notification.request.content.userInfo["route"] as? String == "shared-tag-cloud" else {
            return
        }
        if let remoteTagID = response.notification.request.content.userInfo["remoteTagID"] as? String,
           !remoteTagID.isEmpty {
            UserDefaults.standard.set(remoteTagID, forKey: "pendingSharedTagRemoteIDFromNotification")
        }
        UserDefaults.standard.set(true, forKey: "pendingOpenSharedTagCloudFromNotification")
        await MainActor.run {
            NotificationCenter.default.post(name: .openSharedTagCloudFromNotification, object: nil)
        }
    }
}

@main
struct URLSaveriOSApp: App {
    @UIApplicationDelegateAdaptor(URLSaverNotificationDelegate.self) private var notificationDelegate
    @StateObject private var model = URLSaverAppModel(services: .shared)
    @AppStorage("appThemeMode") private var themeModeRaw = AppThemeMode.system.rawValue
    @Environment(\.scenePhase) private var scenePhase

    init() {
        AppBackgroundScheduler.register(services: .shared)
    }

    var body: some Scene {
        WindowGroup {
            GeometryReader { proxy in
                RootView(model: model)
                    .frame(width: proxy.size.width, height: proxy.size.height, alignment: .top)
                    .background(AppPalette.background.ignoresSafeArea())
                    .preferredColorScheme(AppThemeMode(rawValue: themeModeRaw)?.colorScheme)
                    .task {
                        await model.bootstrapIfNeeded()
                    }
                    .onOpenURL { url in
                        Task {
                            await model.handleIncomingURL(url)
                        }
                    }
                    .onChange(of: scenePhase) { _, newPhase in
                        guard newPhase == .active else { return }
                        Task {
                            await model.refreshAfterReturningToForeground()
                        }
                    }
            }
        }
    }
}
