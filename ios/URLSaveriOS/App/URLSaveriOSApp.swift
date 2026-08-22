import SwiftUI

@main
struct URLSaveriOSApp: App {
    @StateObject private var model: URLSaverAppModel
    @AppStorage("appThemeMode") private var themeModeRaw = AppThemeMode.system.rawValue
    @Environment(\.scenePhase) private var scenePhase

    init() {
        let databaseURL = SharedContainer.databaseURL()
        FirstRunOnboardingStore.initialize(
            hadExistingDatabaseBeforeStartup: FileManager.default.fileExists(atPath: databaseURL.path)
        )
        let services = AppServices.shared
        _model = StateObject(wrappedValue: URLSaverAppModel(services: services))
        AppBackgroundScheduler.register(services: services)
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
