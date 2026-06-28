package jp.mimac.urlsaver.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import jp.mimac.urlsaver.data.TagRepository
import jp.mimac.urlsaver.domain.SharedTagAuthResult
import jp.mimac.urlsaver.domain.SharedTagCloudState
import jp.mimac.urlsaver.domain.SharedTagInviteAcceptanceResult
import jp.mimac.urlsaver.domain.SharedTagInvitePreviewResult
import jp.mimac.urlsaver.domain.SharedInviteType
import jp.mimac.urlsaver.domain.SharedTagRecord
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn

@OptIn(ExperimentalCoroutinesApi::class)
class SharedTagInviteViewModel(
    private val inviteToken: String,
    private val tagRepository: TagRepository,
) : ViewModel() {

    val cloudState: StateFlow<SharedTagCloudState> = tagRepository.cloudState
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            SharedTagCloudState(isConfigured = false, isSignedIn = false),
        )

    private val acceptedRemoteTagId = MutableStateFlow<String?>(null)
    private val _previewResult = MutableStateFlow<SharedTagInvitePreviewResult?>(null)
    val previewResult: StateFlow<SharedTagInvitePreviewResult?> = _previewResult.asStateFlow()

    val joinedTag: StateFlow<SharedTagRecord?> = acceptedRemoteTagId
        .flatMapLatest { remoteTagId ->
            if (remoteTagId == null) {
                flowOf(null)
            } else {
                tagRepository.observeTagByRemoteId(remoteTagId)
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun hasInviteToken(): Boolean = inviteToken.isNotBlank()

    suspend fun loadPreview() {
        if (inviteToken.isBlank()) {
            _previewResult.value = SharedTagInvitePreviewResult.InvalidInvite
            return
        }
        _previewResult.value = tagRepository.previewInvite(inviteToken)
    }

    suspend fun signIn(email: String, password: String): SharedTagAuthResult {
        return tagRepository.signIn(email, password)
    }

    suspend fun signUp(email: String, password: String): SharedTagAuthResult {
        return tagRepository.signUp(email, password)
    }

    suspend fun resendEmailConfirmation(email: String): SharedTagAuthResult {
        return tagRepository.resendEmailConfirmation(email)
    }

    suspend fun sendPasswordRecovery(email: String): SharedTagAuthResult {
        return tagRepository.sendPasswordRecovery(email)
    }

    fun googleOAuthUrl(): String? {
        return tagRepository.googleOAuthUrl()
    }

    suspend fun acceptInvite(): SharedTagInviteAcceptanceResult {
        val result = tagRepository.acceptInvite(inviteToken)
        if (result is SharedTagInviteAcceptanceResult.Success && result.inviteType == SharedInviteType.TAG) {
            acceptedRemoteTagId.value = result.remoteId
        }
        return result
    }
}
