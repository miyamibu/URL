import Foundation

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

    private init() {
        repository = try! URLRepository()
        handoffStore = ShareHandoffStore()
        metadataCoordinator = MetadataCoordinator(repository: repository)
        pendingInviteStore = PendingInviteStore()
        profileStore = UserProfileStore()
        let sharedTagStore = try! SharedTagStore(database: repository.database)
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
    }
}
