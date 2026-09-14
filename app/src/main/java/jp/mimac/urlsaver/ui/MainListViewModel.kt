package jp.mimac.urlsaver.ui

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import jp.mimac.urlsaver.data.EntryCardDisplayModeStore
import jp.mimac.urlsaver.data.MainListRepository
import jp.mimac.urlsaver.data.ServiceFilterOrderStore
import jp.mimac.urlsaver.data.TagRepository
import jp.mimac.urlsaver.data.TopFilterOrderStore
import jp.mimac.urlsaver.data.UrlEntryEntity
import jp.mimac.urlsaver.domain.AssignTagResult
import jp.mimac.urlsaver.domain.CreateTagResult
import jp.mimac.urlsaver.domain.EntryCardDisplayMode
import jp.mimac.urlsaver.domain.RecordState
import jp.mimac.urlsaver.domain.ServiceType
import jp.mimac.urlsaver.domain.ShareSaveResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update

class MainListViewModel(
    private val repository: MainListRepository,
    private val displayModeStore: EntryCardDisplayModeStore = InMemoryEntryCardDisplayModeStore(),
    private val serviceFilterOrderStore: ServiceFilterOrderStore = InMemoryServiceFilterOrderStore(),
    private val topFilterOrderStore: TopFilterOrderStore = InMemoryTopFilterOrderStore(),
    private val tagRepository: TagRepository? = null,
    private val savedStateHandle: SavedStateHandle = SavedStateHandle(),
) : ViewModel() {

    private val selectedService = MutableStateFlow(ServiceType.ALL)
    private val entrySourceState = MutableStateFlow(EntrySourceUiState())
    private val _manualInputState = MutableStateFlow(restoreManualInputUiState(savedStateHandle))
    val manualInputState: StateFlow<ManualInputUiState> = _manualInputState
    private val manualSaveEventChannel = Channel<ManualSaveEvent>(capacity = Channel.BUFFERED)
    val manualSaveEvents = manualSaveEventChannel.receiveAsFlow()
    private var observeEntriesJob: Job? = null

    init {
        observeActiveEntries()
    }

    val localTagEntryRefs = repository.observeLocalTagEntryRefs()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val entryCardDisplayMode: StateFlow<EntryCardDisplayMode> = displayModeStore.observeDisplayMode()
        .stateIn(viewModelScope, SharingStarted.Eagerly, EntryCardDisplayMode.RICH)

    val serviceFilterOrder: StateFlow<List<ServiceType>> = serviceFilterOrderStore.observeServiceOrder()
        .stateIn(
            viewModelScope,
            SharingStarted.Eagerly,
            listOf(ServiceType.YOUTUBE, ServiceType.X, ServiceType.INSTAGRAM, ServiceType.WEB),
        )

    val topFilterOrderTokens: StateFlow<List<String>> = topFilterOrderStore.observeOrderTokens()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val uiState: StateFlow<ListFilterUiState> = combine(
        entrySourceState,
        selectedService,
    ) { sourceState, selectedService ->
        buildListFilterUiState(
            entries = sourceState.entries,
            selectedService = selectedService,
            loadState = sourceState.loadState,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, ListFilterUiState())

    val selectedServiceFlow: StateFlow<ServiceType> = selectedService

    fun selectService(serviceType: ServiceType) {
        selectedService.value = serviceType
    }

    suspend fun searchEntryIds(query: String): Set<Long> {
        return repository.searchEntryIds(query = query, recordState = RecordState.ACTIVE)
    }

    fun retryLoading() {
        entrySourceState.update { current ->
            current.copy(loadState = ListFilterLoadState.Loading)
        }
        observeActiveEntries()
    }

    fun toggleEntryCardDisplayMode() {
        viewModelScope.launch {
            displayModeStore.setDisplayMode(entryCardDisplayMode.value.toggled())
        }
    }

    fun reorderServices(serviceOrder: List<ServiceType>) {
        viewModelScope.launch {
            serviceFilterOrderStore.setServiceOrder(serviceOrder)
        }
    }

    fun reorderTopFilters(tokens: List<String>) {
        viewModelScope.launch {
            topFilterOrderStore.setOrderTokens(tokens)
        }
    }

    suspend fun createLocalTag(name: String): CreateTagResult {
        return tagRepository?.createLocalTagWithResult(name) ?: CreateTagResult.Failed
    }

    suspend fun renameLocalTag(tagId: Long, name: String): CreateTagResult {
        return tagRepository?.renameLocalTagWithResult(tagId, name) ?: CreateTagResult.Failed
    }

    suspend fun deleteLocalTag(tagId: Long): Boolean {
        val repository = tagRepository ?: return false
        return runCatching {
            repository.deleteTag(tagId)
        }.isSuccess
    }

    suspend fun assignTagToEntries(tagId: Long, entryIds: Collection<Long>): Int {
        val repository = tagRepository ?: return 0
        var assignedCount = 0
        entryIds.forEach { entryId ->
            when (repository.assignTagWithResult(tagId = tagId, entryId = entryId)) {
                AssignTagResult.Success,
                AssignTagResult.AlreadyAssigned,
                -> assignedCount += 1
                is AssignTagResult.LimitReached,
                AssignTagResult.Failed,
                -> Unit
            }
        }
        return assignedCount
    }

    fun openManualInput() {
        updateManualInputState(ManualInputUiState(visible = true))
    }

    fun dismissManualInput() {
        updateManualInputState(ManualInputUiState())
    }

    fun updateManualInputText(inputText: String) {
        updateManualInputState(
            manualInputState.value.copy(
                inputText = inputText,
                inputError = null,
            ),
        )
    }

    fun selectManualInputTag(tagId: Long) {
        val current = manualInputState.value
        updateManualInputState(
            current.copy(
                selectedLocalTagIds = current.selectedLocalTagIds + tagId,
                localTagError = null,
            ),
        )
    }

    fun toggleManualInputTag(tagId: Long) {
        val current = manualInputState.value
        updateManualInputState(
            current.copy(
                selectedLocalTagIds = if (tagId in current.selectedLocalTagIds) {
                    current.selectedLocalTagIds - tagId
                } else {
                    current.selectedLocalTagIds + tagId
                },
                localTagError = null,
            ),
        )
    }

    fun submitCurrentManualInput() {
        val snapshot = manualInputState.value
        if (!snapshot.visible || snapshot.isSaving) return
        updateManualInputState(snapshot.copy(isSaving = true))
        viewModelScope.launch {
            try {
                val submitResult = submitManualInput(
                    input = snapshot.inputText,
                    localTagIds = snapshot.selectedLocalTagIds,
                )
                if (shouldPreserveManualInputAfter(submitResult.saveResult)) {
                    updateManualInputState(
                        manualInputState.value.copy(
                            inputError = submitResult.saveResult,
                            isSaving = false,
                        ),
                    )
                } else {
                    updateManualInputState(ManualInputUiState())
                    manualSaveEventChannel.send(
                        ManualSaveEvent(
                            saveResult = submitResult.saveResult,
                            entryId = submitResult.entryId,
                            failedTagAssignmentCount = submitResult.failedTagAssignmentCount,
                        ),
                    )
                }
            } catch (cancellation: CancellationException) {
                updateManualInputState(manualInputState.value.copy(isSaving = false))
                throw cancellation
            } catch (_: Throwable) {
                updateManualInputState(
                    manualInputState.value.copy(
                        inputError = ShareSaveResult.SAVE_FAILED,
                        isSaving = false,
                    ),
                )
            }
        }
    }

    suspend fun submitManualInput(
        input: String,
        localTagIds: Set<Long> = emptySet(),
    ): ManualInputSubmitResult {
        val result = repository.saveFromManualInput(input)
        val savedEntryId = result.entryId
        var failedTagAssignmentCount = 0
        if (
            savedEntryId != null &&
            localTagIds.isNotEmpty() &&
            (result.result == ShareSaveResult.CREATED || result.result == ShareSaveResult.RESTORED_FROM_PENDING_DELETE)
        ) {
            val repository = tagRepository
            if (repository == null) {
                failedTagAssignmentCount = localTagIds.size
            } else {
                localTagIds.forEach { tagId ->
                    when (repository.assignTagWithResult(tagId = tagId, entryId = savedEntryId)) {
                        AssignTagResult.Success,
                        AssignTagResult.AlreadyAssigned,
                        -> Unit
                        is AssignTagResult.LimitReached,
                        AssignTagResult.Failed,
                        -> failedTagAssignmentCount += 1
                    }
                }
            }
        }
        return ManualInputSubmitResult(
            saveResult = result.result,
            entryId = result.entryId,
            failedTagAssignmentCount = failedTagAssignmentCount,
        )
    }

    suspend fun archiveEntry(entryId: Long): Boolean {
        return repository.archive(entryId)
    }

    suspend fun archiveEntries(entryIds: Collection<Long>): List<Long> {
        val archivedIds = repository.archiveEntries(entryIds)
        return entryIds.distinct().filter { it in archivedIds }
    }

    suspend fun markPendingDelete(entryId: Long): Long? {
        return repository.markPendingDelete(entryId)
    }

    suspend fun markPendingDeleteEntries(entryIds: Collection<Long>): Map<Long, Long> {
        return repository.markPendingDeleteEntries(entryIds)
    }

    private fun observeActiveEntries() {
        observeEntriesJob?.cancel()
        observeEntriesJob = viewModelScope.launch {
            val entriesFlow = try {
                repository.observeActiveEntries()
            } catch (_: Throwable) {
                entrySourceState.update { current ->
                    current.copy(loadState = ListFilterLoadState.Error())
                }
                return@launch
            }
            entriesFlow
                .onStart {
                    entrySourceState.update { current ->
                        current.copy(loadState = ListFilterLoadState.Loading)
                    }
                }
                .catch { error ->
                    if (error is CancellationException) throw error
                    entrySourceState.update { current ->
                        current.copy(loadState = ListFilterLoadState.Error())
                    }
                }
                .collect { entries ->
                    entrySourceState.value = EntrySourceUiState(
                        entries = entries,
                        loadState = if (entries.isEmpty()) {
                            ListFilterLoadState.Empty
                        } else {
                            ListFilterLoadState.Content
                        },
                    )
                }
        }
    }

    private fun updateManualInputState(state: ManualInputUiState) {
        _manualInputState.value = state
        savedStateHandle[MANUAL_INPUT_VISIBLE_KEY] = state.visible
        savedStateHandle[MANUAL_INPUT_TEXT_KEY] = state.inputText
        savedStateHandle[MANUAL_INPUT_ERROR_KEY] = state.inputError?.name
        savedStateHandle[MANUAL_INPUT_TAG_IDS_KEY] = state.selectedLocalTagIds.sorted().toLongArray()
        savedStateHandle[MANUAL_INPUT_LOCAL_TAG_ERROR_KEY] = state.localTagError
    }
}

private data class EntrySourceUiState(
    val entries: List<UrlEntryEntity> = emptyList(),
    val loadState: ListFilterLoadState = ListFilterLoadState.Initial,
)

data class ManualInputSubmitResult(
    val saveResult: ShareSaveResult,
    val entryId: Long?,
    val failedTagAssignmentCount: Int = 0,
)

data class ManualSaveEvent(
    val saveResult: ShareSaveResult,
    val entryId: Long?,
    val failedTagAssignmentCount: Int = 0,
)

data class ManualInputUiState(
    val visible: Boolean = false,
    val inputText: String = "",
    val inputError: ShareSaveResult? = null,
    val selectedLocalTagIds: Set<Long> = emptySet(),
    val localTagError: String? = null,
    val isSaving: Boolean = false,
)

internal fun restoreManualInputUiState(savedStateHandle: SavedStateHandle): ManualInputUiState {
    val errorName = savedStateHandle.get<String>(MANUAL_INPUT_ERROR_KEY)
    return ManualInputUiState(
        visible = savedStateHandle[MANUAL_INPUT_VISIBLE_KEY] ?: false,
        inputText = savedStateHandle[MANUAL_INPUT_TEXT_KEY] ?: "",
        inputError = errorName?.let { runCatching { ShareSaveResult.valueOf(it) }.getOrNull() },
        selectedLocalTagIds = savedStateHandle.get<LongArray>(MANUAL_INPUT_TAG_IDS_KEY)?.toSet().orEmpty(),
        localTagError = savedStateHandle[MANUAL_INPUT_LOCAL_TAG_ERROR_KEY],
        isSaving = false,
    )
}

private const val MANUAL_INPUT_VISIBLE_KEY = "manual_input.visible"
private const val MANUAL_INPUT_TEXT_KEY = "manual_input.text"
private const val MANUAL_INPUT_ERROR_KEY = "manual_input.error"
private const val MANUAL_INPUT_TAG_IDS_KEY = "manual_input.local_tag_ids"
private const val MANUAL_INPUT_LOCAL_TAG_ERROR_KEY = "manual_input.local_tag_error"

private class InMemoryEntryCardDisplayModeStore : EntryCardDisplayModeStore {
    override fun observeDisplayMode() = flowOf(EntryCardDisplayMode.RICH)
    override suspend fun setDisplayMode(mode: EntryCardDisplayMode) = Unit
}

private class InMemoryServiceFilterOrderStore : ServiceFilterOrderStore {
    override fun observeServiceOrder() = flowOf(
        listOf(ServiceType.YOUTUBE, ServiceType.X, ServiceType.INSTAGRAM, ServiceType.TIKTOK, ServiceType.WEB),
    )

    override suspend fun setServiceOrder(serviceOrder: List<ServiceType>) = Unit
}

private class InMemoryTopFilterOrderStore : TopFilterOrderStore {
    override fun observeOrderTokens() = flowOf(emptyList<String>())
    override suspend fun setOrderTokens(tokens: List<String>) = Unit
}
