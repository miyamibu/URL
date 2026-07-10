package jp.mimac.urlsaver.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import jp.mimac.urlsaver.data.EntryCardDisplayModeStore
import jp.mimac.urlsaver.data.TagRepository
import jp.mimac.urlsaver.data.UrlEntryEntity
import jp.mimac.urlsaver.data.UrlRepository
import jp.mimac.urlsaver.domain.EntryCardDisplayMode
import jp.mimac.urlsaver.domain.AssignTagResult
import jp.mimac.urlsaver.domain.MigrateSharedTagResult
import jp.mimac.urlsaver.domain.SharedTagCloudState
import jp.mimac.urlsaver.domain.SharedTagInviteCreationResult
import jp.mimac.urlsaver.domain.SharedTagMemberRecord
import jp.mimac.urlsaver.domain.SharedTagOwnershipTransferResult
import jp.mimac.urlsaver.domain.SharedTagRecord
import jp.mimac.urlsaver.domain.TagShareJson
import kotlinx.serialization.encodeToString
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class TagDetailViewModel(
    private val tagId: Long,
    private val tagRepository: TagRepository,
    private val urlRepository: UrlRepository,
    private val displayModeStore: EntryCardDisplayModeStore,
) : ViewModel() {
    private val _isSyncing = MutableStateFlow(false)
    val isSyncing = _isSyncing.asStateFlow()

    private val _syncNotice = MutableStateFlow<String?>(null)
    val syncNotice = _syncNotice.asStateFlow()

    private val _syncNoticeIsError = MutableStateFlow(false)
    val syncNoticeIsError = _syncNoticeIsError.asStateFlow()

    val tag: StateFlow<SharedTagRecord?> = tagRepository.observeTag(tagId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val entries: StateFlow<List<UrlEntryEntity>> = tagRepository.observeEntriesForTag(tagId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val members: StateFlow<List<SharedTagMemberRecord>> = tagRepository.observeMembersForTag(tagId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val availableEntriesToAdd: StateFlow<List<UrlEntryEntity>> = combine(
        urlRepository.observeActiveEntries(),
        urlRepository.observeArchiveEntries(),
        entries,
    ) { activeEntries, archiveEntries, taggedEntries ->
        val taggedEntryIds = taggedEntries.mapTo(linkedSetOf()) { it.id }
        (activeEntries + archiveEntries)
            .filterNot { entry -> entry.id in taggedEntryIds }
            .sortedByDescending { entry -> entry.createdAt }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val isSyncAvailable: StateFlow<Boolean> = tagRepository.isSyncAvailable
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val cloudState: StateFlow<SharedTagCloudState> = tagRepository.cloudState
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            SharedTagCloudState(isConfigured = false, isSignedIn = false),
        )

    val entryCardDisplayMode: StateFlow<EntryCardDisplayMode> = displayModeStore.observeDisplayMode()
        .stateIn(viewModelScope, SharingStarted.Eagerly, EntryCardDisplayMode.RICH)

    fun buildLocalShareLink(): String {
        return "urlsaver://tag/$tagId"
    }

    suspend fun buildTagSharePayloadText(): String? {
        val payload = tagRepository.exportTag(tagId) ?: return null
        return TagShareJson.encodeToString(payload)
    }

    fun toggleEntryCardDisplayMode() {
        viewModelScope.launch {
            displayModeStore.setDisplayMode(entryCardDisplayMode.value.toggled())
        }
    }

    fun syncSharedTagNow(showSuccessNotice: Boolean = true) {
        viewModelScope.launch {
            if (_isSyncing.value) return@launch
            _isSyncing.value = true
            _syncNotice.value = null
            _syncNoticeIsError.value = false
            val cloud = tagRepository.cloudState.first()
            if (!cloud.isConfigured) {
                _isSyncing.value = false
                _syncNoticeIsError.value = true
                _syncNotice.value = "このビルドではクラウド同期が未設定です。前回同期した内容を表示しています。"
                return@launch
            }
            if (!cloud.isSignedIn) {
                _isSyncing.value = false
                _syncNoticeIsError.value = true
                _syncNotice.value = "サインインしていないため同期できません。前回同期した内容を表示しています。"
                return@launch
            }
            val success = tagRepository.triggerSync()
            _isSyncing.value = false
            if (success && showSuccessNotice) {
                _syncNoticeIsError.value = false
                _syncNotice.value = "更新しました"
            } else if (!success) {
                _syncNoticeIsError.value = true
                _syncNotice.value = "更新できませんでした"
            }
        }
    }

    suspend fun addEntryToTag(entryId: Long): AssignTagResult {
        return tagRepository.assignTagWithResult(tagId = tagId, entryId = entryId)
    }

    suspend fun removeEntryFromTag(entryId: Long): Boolean {
        tagRepository.removeTag(tagId = tagId, entryId = entryId)
        return true
    }

    suspend fun deleteTag() {
        tagRepository.deleteTag(tagId)
    }

    suspend fun migrateToCloud(): MigrateSharedTagResult {
        return tagRepository.migrateLocalTagToCloud(tagId)
    }

    suspend fun createInviteLink(): SharedTagInviteCreationResult {
        return tagRepository.createInviteLink(tagId)
    }

    suspend fun transferOwnership(newOwnerUserId: String): SharedTagOwnershipTransferResult {
        return tagRepository.transferOwnership(tagId, newOwnerUserId)
    }

    suspend fun leaveSharedTag(): Boolean {
        return tagRepository.leaveSharedTag(tagId)
    }

    suspend fun removeMember(userId: String): Boolean {
        return tagRepository.removeMember(tagId, userId)
    }
}
