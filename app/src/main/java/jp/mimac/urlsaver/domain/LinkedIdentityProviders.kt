package jp.mimac.urlsaver.domain

enum class LinkedIdentityProvider(val supabaseValue: String) {
    GOOGLE("google"),
    APPLE("apple"),
}

data class LinkedIdentityProvidersSnapshot(
    val authUserId: String,
    val providers: Set<LinkedIdentityProvider>,
    val verifiedAtEpochMillis: Long,
) {
    val googleLinked: Boolean get() = LinkedIdentityProvider.GOOGLE in providers
    val appleLinked: Boolean get() = LinkedIdentityProvider.APPLE in providers
}

enum class LinkedIdentityProviderRefreshFailure {
    SIGNED_OUT,
    NOT_CONFIGURED,
    ACCOUNT_CLEANUP_IN_PROGRESS,
    REQUEST_FAILED,
    SESSION_CHANGED,
}

sealed interface LinkedIdentityProviderRefreshResult {
    data class Verified(val snapshot: LinkedIdentityProvidersSnapshot) : LinkedIdentityProviderRefreshResult
    data class Failed(val reason: LinkedIdentityProviderRefreshFailure) : LinkedIdentityProviderRefreshResult
}

sealed interface LinkedIdentityProviderState {
    data class Unknown(val authUserId: String?) : LinkedIdentityProviderState
    data class LastConfirmed(val snapshot: LinkedIdentityProvidersSnapshot) : LinkedIdentityProviderState
    data class Loading(
        val authUserId: String,
        val lastConfirmed: LinkedIdentityProvidersSnapshot?,
    ) : LinkedIdentityProviderState
    data class Verified(val snapshot: LinkedIdentityProvidersSnapshot) : LinkedIdentityProviderState
    data class Error(
        val authUserId: String,
        val reason: LinkedIdentityProviderRefreshFailure,
        val lastConfirmed: LinkedIdentityProvidersSnapshot?,
    ) : LinkedIdentityProviderState
}
