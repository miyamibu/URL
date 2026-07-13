package jp.mimac.urlsaver.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import jp.mimac.urlsaver.data.EntryCardDisplayModeStore
import jp.mimac.urlsaver.data.MainListRepository
import jp.mimac.urlsaver.data.ServiceFilterOrderStore
import jp.mimac.urlsaver.data.TagRepository
import jp.mimac.urlsaver.data.TopFilterOrderStore
import jp.mimac.urlsaver.domain.AssignTagResult
import jp.mimac.urlsaver.domain.CreateTagResult
import jp.mimac.urlsaver.domain.EntryCardDisplayMode
import jp.mimac.urlsaver.domain.ServiceType
import jp.mimac.urlsaver.domain.ShareSaveResult
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn

class MainListViewModel(
    private val repository: MainListRepository,
    private val displayModeStore: EntryCardDisplayModeStore = InMemoryEntryCardDisplayModeStore(),
    private val serviceFilterOrderStore: ServiceFilterOrderStore = InMemoryServiceFilterOrderStore(),
    private val topFilterOrderStore: TopFilterOrderStore = InMemoryTopFilterOrderStore(),
    private val tagRepository: TagRepository? = null,
) : ViewModel() {

    private val selectedService = MutableStateFlow(ServiceType.ALL)

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
        repository.observeActiveEntries(),
        selectedService,
    ) { entries, selectedService ->
        buildListFilterUiState(
            entries = entries,
            selectedService = selectedService,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ListFilterUiState())

    val selectedServiceFlow: StateFlow<ServiceType> = selectedService

    fun selectService(serviceType: ServiceType) {
        selectedService.value = serviceType
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
        return entryIds.filter { repository.archive(it) }
    }

    suspend fun markPendingDelete(entryId: Long): Long? {
        return repository.markPendingDelete(entryId)
    }

    suspend fun markPendingDeleteEntries(entryIds: Collection<Long>): Map<Long, Long> {
        return buildMap {
            entryIds.forEach { entryId ->
                repository.markPendingDelete(entryId)?.let { pendingUntil ->
                    put(entryId, pendingUntil)
                }
            }
        }
    }
}

data class ManualInputSubmitResult(
    val saveResult: ShareSaveResult,
    val entryId: Long?,
    val failedTagAssignmentCount: Int = 0,
)

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
