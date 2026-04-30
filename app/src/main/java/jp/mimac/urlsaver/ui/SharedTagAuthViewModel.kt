package jp.mimac.urlsaver.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import jp.mimac.urlsaver.data.TagRepository
import jp.mimac.urlsaver.data.UserProfileStore
import jp.mimac.urlsaver.domain.FeatureEntitlements
import jp.mimac.urlsaver.domain.SharedTagAccountDeletionResult
import jp.mimac.urlsaver.domain.SharedTagAuthResult
import jp.mimac.urlsaver.domain.SharedTagCloudState
import jp.mimac.urlsaver.domain.UsageSummary
import jp.mimac.urlsaver.domain.UserProfile
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import java.util.Base64

class SharedTagAuthViewModel(
    private val tagRepository: TagRepository,
    private val userProfileStore: UserProfileStore,
) : ViewModel() {

    val cloudState: StateFlow<SharedTagCloudState> = tagRepository.cloudState
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            SharedTagCloudState(isConfigured = false, isSignedIn = false),
        )
    val profile: StateFlow<UserProfile> = userProfileStore.observeProfile()
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            UserProfile(),
        )
    val usageSummary: StateFlow<UsageSummary> = tagRepository.observeUsageSummary()
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            UsageSummary(
                personalUrlCount = 0,
                normalTagCount = 0,
                sharedTagCount = 0,
                sharedTagUsages = emptyList(),
            ),
        )
    val entitlements: FeatureEntitlements = tagRepository.featureEntitlements()

    suspend fun signIn(email: String, password: String): SharedTagAuthResult {
        return tagRepository.signIn(email, password)
    }

    suspend fun signUp(email: String, password: String): SharedTagAuthResult {
        return tagRepository.signUp(email, password)
    }

    suspend fun signOut() {
        tagRepository.signOut()
    }

    suspend fun deleteAccount(): SharedTagAccountDeletionResult {
        return tagRepository.deleteAccount()
    }

    suspend fun saveDisplayName(displayName: String) {
        userProfileStore.saveDisplayName(displayName)
    }

    suspend fun saveAvatarBytes(bytes: ByteArray?) {
        userProfileStore.saveAvatarBase64(
            bytes?.let { Base64.getEncoder().encodeToString(it) },
        )
    }

    fun canApplyInviteCode(code: String): Boolean = code.trim().isNotEmpty()

    suspend fun applyInviteCode(code: String): InviteCodeApplyResult {
        if (!canApplyInviteCode(code)) {
            return InviteCodeApplyResult.InvalidCode
        }
        return InviteCodeApplyResult.NotAvailable("招待コード機能は現在準備中です")
    }
}

sealed interface InviteCodeApplyResult {
    data object InvalidCode : InviteCodeApplyResult
    data class NotAvailable(val message: String) : InviteCodeApplyResult
}
