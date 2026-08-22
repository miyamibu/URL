package jp.mimac.urlsaver.data

interface AccountLinkedLocalDataCleaner {
    suspend fun cancelSharedTagSyncWork(authUserId: String)

    suspend fun clearSharedTagData(authUserId: String)

    fun clearPendingInvite()

    suspend fun clearEntitlementCache(authUserId: String)

    fun clearChatGptPersonalLinkSettings(authUserId: String)
}

class DefaultAccountLinkedLocalDataCleaner(
    private val syncScheduler: SharedTagSyncScheduler,
    private val syncCoordinator: SharedTagSyncCoordinator,
    private val pendingInviteStore: PendingInviteStore,
    private val entitlementGrantStore: EntitlementGrantStore,
    private val chatGptPersonalLinkSyncSettingsStore: ChatGptPersonalLinkSyncSettingsStore,
) : AccountLinkedLocalDataCleaner {
    override suspend fun cancelSharedTagSyncWork(authUserId: String) {
        syncScheduler.cancel(authUserId)
    }

    override suspend fun clearSharedTagData(authUserId: String) {
        syncCoordinator.clearLocalStateForDeletedAccount(authUserId)
    }

    override fun clearPendingInvite() {
        pendingInviteStore.clear()
    }

    override suspend fun clearEntitlementCache(authUserId: String) {
        entitlementGrantStore.clearLastKnownGrants(authUserId)
    }

    override fun clearChatGptPersonalLinkSettings(authUserId: String) {
        chatGptPersonalLinkSyncSettingsStore.clear(authUserId)
    }
}

object NoopAccountLinkedLocalDataCleaner : AccountLinkedLocalDataCleaner {
    override suspend fun cancelSharedTagSyncWork(authUserId: String) = Unit

    override suspend fun clearSharedTagData(authUserId: String) = Unit

    override fun clearPendingInvite() = Unit

    override suspend fun clearEntitlementCache(authUserId: String) = Unit

    override fun clearChatGptPersonalLinkSettings(authUserId: String) = Unit
}
