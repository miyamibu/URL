package jp.mimac.urlsaver.data

import jp.mimac.urlsaver.domain.EntitlementGrant
import jp.mimac.urlsaver.util.AppClock

class EntitlementGrantRepository(
    private val authSessionProvider: SharedTagAuthSessionProvider,
    private val remoteDataSource: EntitlementGrantRemoteDataSource,
    private val grantStore: EntitlementGrantStore,
    private val clock: AppClock,
) {
    fun currentGrantsSnapshot(): List<EntitlementGrant> {
        val session = authSessionProvider.session.value ?: return emptyList()
        return grantStore.cachedGrantsSnapshot(
            authUserId = session.authUserId,
            currentTimeMillis = clock.nowEpochMillis(),
        )
    }

    suspend fun refreshForCurrentSession(): List<EntitlementGrant> {
        val session = authSessionProvider.session.value ?: return emptyList()
        val now = clock.nowEpochMillis()
        return runCatching {
            val remoteGrants = remoteDataSource.fetchGrants(session)
            grantStore.saveLastKnownGrants(
                authUserId = session.authUserId,
                grants = remoteGrants,
                fetchedAtMillis = now,
            )
            remoteGrants
        }.getOrElse {
            grantStore.loadLastKnownGrants(
                authUserId = session.authUserId,
                currentTimeMillis = now,
            )
        }
    }
}
