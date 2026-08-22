package jp.mimac.urlsaver.data

import jp.mimac.urlsaver.domain.EntitlementGrant
import jp.mimac.urlsaver.util.AppClock
import kotlinx.coroutines.CancellationException

class EntitlementGrantRepository(
    private val authSessionProvider: SharedTagAuthSessionProvider,
    private val remoteDataSource: EntitlementGrantRemoteDataSource,
    private val grantStore: EntitlementGrantStore,
    private val clock: AppClock,
    private val accountOperationFence: AccountOperationFence = AccountOperationFence(),
) {
    fun currentGrantsSnapshot(): List<EntitlementGrant> {
        val session = authSessionProvider.session.value ?: return emptyList()
        return grantStore.cachedGrantsSnapshot(
            authUserId = session.authUserId,
            currentTimeMillis = clock.nowEpochMillis(),
        )
    }

    suspend fun refreshForCurrentSession(): List<EntitlementGrant> {
        return accountOperationFence.withAccountOperation(
            authUserId = { authSessionProvider.session.value?.authUserId },
            blockedResult = { emptyList() },
        ) {
            val session = authSessionProvider.session.value ?: return@withAccountOperation emptyList()
            val now = clock.nowEpochMillis()
            try {
                val remoteGrants = remoteDataSource.fetchGrants(session)
                if (!isCurrentSession(session)) {
                    return@withAccountOperation emptyList()
                }
                grantStore.saveLastKnownGrants(
                    authUserId = session.authUserId,
                    grants = remoteGrants,
                    fetchedAtMillis = now,
                )
                if (isCurrentSession(session)) remoteGrants else emptyList()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                if (!isCurrentSession(session)) return@withAccountOperation emptyList()
                val cached = grantStore.loadLastKnownGrants(
                    authUserId = session.authUserId,
                    currentTimeMillis = now,
                )
                if (isCurrentSession(session)) cached else emptyList()
            }
        }
    }

    suspend fun redeemPromoCode(code: String): PromoCodeRedemptionResult {
        val trimmedCode = code.trim()
        if (trimmedCode.isEmpty()) {
            return PromoCodeRedemptionResult.InvalidCode
        }
        return accountOperationFence.withAccountOperation(
            authUserId = { authSessionProvider.session.value?.authUserId },
            blockedResult = { PromoCodeRedemptionResult.AuthRequired },
        ) {
            val session = authSessionProvider.session.value
                ?: return@withAccountOperation PromoCodeRedemptionResult.AuthRequired
            val now = clock.nowEpochMillis()
            try {
                val remoteGrants = remoteDataSource.redeemPromoCode(session, trimmedCode)
                if (!isCurrentSession(session)) {
                    return@withAccountOperation PromoCodeRedemptionResult.AuthRequired
                }
                grantStore.saveLastKnownGrants(
                    authUserId = session.authUserId,
                    grants = remoteGrants,
                    fetchedAtMillis = now,
                )
                if (!isCurrentSession(session)) {
                    PromoCodeRedemptionResult.AuthRequired
                } else {
                    PromoCodeRedemptionResult.Success(remoteGrants)
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                PromoCodeRedemptionResult.Failure(error.message ?: "優待コードを適用できませんでした")
            }
        }
    }

    private fun isCurrentSession(session: SharedTagAuthSession): Boolean =
        authSessionProvider.session.value?.authUserId == session.authUserId
}

sealed interface PromoCodeRedemptionResult {
    data class Success(val grants: List<EntitlementGrant>) : PromoCodeRedemptionResult
    data object InvalidCode : PromoCodeRedemptionResult
    data object AuthRequired : PromoCodeRedemptionResult
    data class Failure(val message: String) : PromoCodeRedemptionResult
}
