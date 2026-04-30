import SwiftUI

@main
struct URLSaveriOSApp: App {
    @StateObject private var model = URLSaverAppModel(services: .shared)
    @AppStorage("appThemeMode") private var themeModeRaw = AppThemeMode.system.rawValue

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
            }
        }
    }
}
