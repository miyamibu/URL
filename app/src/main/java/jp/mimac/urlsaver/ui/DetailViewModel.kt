package jp.mimac.urlsaver.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import jp.mimac.urlsaver.data.CollectionEntity
import jp.mimac.urlsaver.data.TagRepository
import jp.mimac.urlsaver.data.UrlEntryEntity
import jp.mimac.urlsaver.data.UrlRepository
import jp.mimac.urlsaver.domain.AssignTagResult
import jp.mimac.urlsaver.domain.CreateTagResult
import jp.mimac.urlsaver.domain.TagWithCount
import jp.mimac.urlsaver.domain.DetailEffect
import jp.mimac.urlsaver.domain.SharedTagRecord
import jp.mimac.urlsaver.domain.SharedTagNameValidationError
import jp.mimac.urlsaver.domain.normalizeSharedTagName
import jp.mimac.urlsaver.domain.validateSharedTagName
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class DetailViewModel(
    private val entryId: Long,
    private val repository: UrlRepository,
    private val tagRepository: TagRepository,
) : ViewModel() {

    val entry: Flow<UrlEntryEntity?> = repository.observeEntry(entryId)
    val assignedTags: StateFlow<List<SharedTagRecord>> = tagRepository.observeTagsForEntry(entryId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val allTagsWithCount: StateFlow<List<TagWithCount>> = tagRepository.observeAllTagsWithCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val collections: StateFlow<List<CollectionEntity>> = repository.observeCollections()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val effectChannel = Channel<DetailEffect>(capacity = Channel.BUFFERED)
    val effects = effectChannel.receiveAsFlow()

    suspend fun saveTitle(input: String): SaveTitleUiResult {
        val result = repository.saveUserTitle(entryId, input)
        if (result.success) {
            effectChannel.send(DetailEffect.TitleEdited(entryId, result.oldTitle))
            return SaveTitleUiResult.Success
        }
        return if (result.tooLong) SaveTitleUiResult.TooLong else SaveTitleUiResult.Failed
    }

    suspend fun saveMemo(input: String): SaveMemoUiResult {
        val result = repository.saveMemo(entryId, input)
        if (result.success) return SaveMemoUiResult.Success
        return if (result.tooLong) SaveMemoUiResult.TooLong else SaveMemoUiResult.Failed
    }

    suspend fun retryMetadata(): Boolean {
        return repository.retryMetadata(entryId)
    }

    suspend fun refreshMetadata(): Boolean {
        return repository.refreshMetadata(entryId)
    }

    suspend fun assignTag(tagId: Long): AssignTagResult {
        return tagRepository.assignTagWithResult(tagId = tagId, entryId = entryId)
    }

    fun removeTag(tagId: Long) {
        viewModelScope.launch {
            tagRepository.removeTag(tagId = tagId, entryId = entryId)
        }
    }

    fun assignCollection(collectionId: Long) {
        viewModelScope.launch {
            repository.assignCollection(entryId = entryId, collectionId = collectionId)
        }
    }

    suspend fun createAndAssignTag(name: String): CreateAndAssignTagResult {
        when (validateSharedTagName(name)) {
            SharedTagNameValidationError.BLANK -> return CreateAndAssignTagResult.Blank
            SharedTagNameValidationError.TOO_LONG -> return CreateAndAssignTagResult.TooLong
            null -> Unit
        }

        when (val created = tagRepository.createTagWithResult(name)) {
            is CreateTagResult.Success -> {
                return when (val assigned = tagRepository.assignTagWithResult(tagId = created.tagId, entryId = entryId)) {
                    AssignTagResult.Success,
                    AssignTagResult.AlreadyAssigned,
                    -> CreateAndAssignTagResult.Success
                    is AssignTagResult.LimitReached -> CreateAndAssignTagResult.LimitReached(assigned.message)
                    AssignTagResult.Failed -> CreateAndAssignTagResult.Failed
                }
            }
            CreateTagResult.InvalidName -> return CreateAndAssignTagResult.Blank
            CreateTagResult.Duplicate -> Unit
            is CreateTagResult.LimitReached -> return CreateAndAssignTagResult.LimitReached(created.message)
            CreateTagResult.Failed -> return CreateAndAssignTagResult.Failed
        }

        val normalized = normalizeSharedTagName(name)
        val existing = allTagsWithCount.value.firstOrNull { it.name == normalized } ?: return CreateAndAssignTagResult.Failed
        val alreadyAssigned = assignedTags.value.any { it.id == existing.id }
        if (alreadyAssigned) {
            return CreateAndAssignTagResult.Duplicate
        }
        return when (val assigned = tagRepository.assignTagWithResult(tagId = existing.id, entryId = entryId)) {
            AssignTagResult.Success,
            AssignTagResult.AlreadyAssigned,
            -> CreateAndAssignTagResult.Success
            is AssignTagResult.LimitReached -> CreateAndAssignTagResult.LimitReached(assigned.message)
            AssignTagResult.Failed -> CreateAndAssignTagResult.Failed
        }
    }

    fun archive() {
        viewModelScope.launch {
            val archived = repository.archive(entryId)
            if (archived) {
                effectChannel.send(DetailEffect.NavigateBackAfterArchive(entryId))
            }
        }
    }

    fun unarchive() {
        viewModelScope.launch {
            val restored = repository.unarchive(entryId)
            if (restored) {
                effectChannel.send(DetailEffect.NavigateBackAfterRestore(entryId))
            }
        }
    }

    fun deleteToPending(onPending: (Long) -> Unit) {
        viewModelScope.launch {
            val pendingUntil = repository.markPendingDelete(entryId) ?: return@launch
            onPending(pendingUntil)
            effectChannel.send(DetailEffect.NavigateBackAfterPendingDelete(entryId))
        }
    }
}

sealed interface SaveTitleUiResult {
    data object Success : SaveTitleUiResult
    data object TooLong : SaveTitleUiResult
    data object Failed : SaveTitleUiResult
}

sealed interface SaveMemoUiResult {
    data object Success : SaveMemoUiResult
    data object TooLong : SaveMemoUiResult
    data object Failed : SaveMemoUiResult
}

sealed interface CreateAndAssignTagResult {
    data object Success : CreateAndAssignTagResult
    data object Blank : CreateAndAssignTagResult
    data object TooLong : CreateAndAssignTagResult
    data object Duplicate : CreateAndAssignTagResult
    data class LimitReached(val message: String) : CreateAndAssignTagResult
    data object Failed : CreateAndAssignTagResult
}
