package jp.mimac.urlsaver.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import jp.mimac.urlsaver.data.UrlEntryEntity
import jp.mimac.urlsaver.data.UrlRepository
import jp.mimac.urlsaver.domain.DetailEffect
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

class DetailViewModel(
    private val entryId: Long,
    private val repository: UrlRepository,
) : ViewModel() {

    val entry: Flow<UrlEntryEntity?> = repository.observeEntry(entryId)

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
