import SwiftUI
import UIKit
import UserNotifications

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
