import Foundation

struct AppServicesStartupIssue: Equatable, Sendable {
    let message: String
    let canonicalDatabasePath: String
    let recoveryDatabasePath: String
    let usingRecoveryDatabase: Bool
}

final class AppServices: @unchecked Sendable {
    static let shared = AppServices()

    let repository: URLRepository
    let handoffStore: ShareHandoffStore
    let metadataCoordinator: MetadataCoordinator
    let pendingInviteStore: PendingInviteStore
    let profileStore: UserProfileStore
    let entitlementService: EntitlementService
    let storePurchaseService: StoreKitPurchaseService
    let contactSupportService: ContactSupportService
    let sharedTagCloud: SharedTagCloudService
    let sharedTagSyncExecutor: SharedTagSyncExecutor
    let startupIssue: AppServicesStartupIssue?

    private init() {
        let canonicalDatabaseURL = SharedContainer.databaseURL()
        let recoveryDatabaseURL = SharedContainer.recoveryDatabaseURL()
        let repository: URLRepository
        var startupMessage: String?
        var usingRecoveryDatabase = false

        do {
            repository = try URLRepository(databaseURL: canonicalDatabaseURL)
        } catch {
            usingRecoveryDatabase = true
            startupMessage = "保存データベースを開けなかったため、復旧用データベースを使用しています。元のデータベースは削除していません。"
            do {
                repository = try URLRepository(databaseURL: recoveryDatabaseURL)
            } catch {
                startupMessage = "保存データベースと復旧用データベースを開けませんでした。元のデータベースは削除していません。"
                repository = URLRepository.unavailable(
                    databaseURL: recoveryDatabaseURL,
                    message: error.localizedDescription
                )
            }
        }
        self.repository = repository
        handoffStore = ShareHandoffStore()
        metadataCoordinator = MetadataCoordinator(repository: repository)
        pendingInviteStore = PendingInviteStore()
        profileStore = UserProfileStore()
        let sharedTagStore: SharedTagStore
        do {
            sharedTagStore = try SharedTagStore(database: repository.database)
        } catch {
            startupMessage = [startupMessage, "共有タグのローカルデータベースを初期化できませんでした。"].compactMap { $0 }.joined(separator: " ")
            sharedTagStore = SharedTagStore.unavailable(database: repository.database)
        }
        let sharedTagSessionStore = SharedTagAuthSessionStore()
        let sharedTagConfig = SharedTagCloudConfig()
        let contactSupportConfig = ContactSupportConfig()
        entitlementService = EntitlementService(
            config: sharedTagConfig,
            sessionStore: sharedTagSessionStore
        )
        storePurchaseService = StoreKitPurchaseService(
            config: sharedTagConfig,
            sessionStore: sharedTagSessionStore
        )
        contactSupportService = ContactSupportService(
            config: contactSupportConfig,
            sessionStore: sharedTagSessionStore
        )
        sharedTagCloud = SharedTagCloudService(
            config: sharedTagConfig,
            sessionStore: sharedTagSessionStore,
            store: sharedTagStore,
            repository: repository
        )
        sharedTagSyncExecutor = SharedTagSyncExecutor(driver: SharedTagCloudSyncDriver(service: sharedTagCloud))
        startupIssue = startupMessage.map {
            AppServicesStartupIssue(
                message: $0,
                canonicalDatabasePath: canonicalDatabaseURL.path,
                recoveryDatabasePath: recoveryDatabaseURL.path,
                usingRecoveryDatabase: usingRecoveryDatabase
            )
        }
    }
}
