package jp.mimac.urlsaver.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import jp.mimac.urlsaver.data.EntryCardDisplayModeStore
import jp.mimac.urlsaver.data.ServiceFilterOrderStore
import jp.mimac.urlsaver.data.TagRepository
import jp.mimac.urlsaver.data.TopFilterOrderStore
import jp.mimac.urlsaver.data.UrlEntryEntity
import jp.mimac.urlsaver.data.UrlRepository
import jp.mimac.urlsaver.domain.CreateTagResult
import jp.mimac.urlsaver.domain.EntryCardDisplayMode
import jp.mimac.urlsaver.domain.ServiceType
import jp.mimac.urlsaver.domain.TagWithCount
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update

class ArchiveViewModel(
    private val repository: UrlRepository,
    displayModeStore: EntryCardDisplayModeStore = InMemoryArchiveEntryCardDisplayModeStore(),
    private val serviceFilterOrderStore: ServiceFilterOrderStore = InMemoryArchiveServiceFilterOrderStore(),
    private val topFilterOrderStore: TopFilterOrderStore = InMemoryArchiveTopFilterOrderStore(),
    private val tagRepository: TagRepository? = null,
) : ViewModel() {
    private val selectedService = MutableStateFlow(ServiceType.ALL)
    private val selectedLocalTagId = MutableStateFlow<Long?>(null)
    private val entrySourceState = MutableStateFlow(ArchiveEntrySourceUiState())
    private var observeEntriesJob: Job? = null

    init {
        observeArchiveEntries()
    }

    val allTagsWithCount: StateFlow<List<TagWithCount>> = tagRepository
        ?.observeAllTagsWithCount()
        ?.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
        ?: MutableStateFlow<List<TagWithCount>>(emptyList())

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
        selectedLocalTagId,
        repository.observeLocalTagEntryRefs(),
    ) { sourceState, selectedService, selectedLocalTag, entryRefs ->
        val baseState = buildListFilterUiState(
            entries = sourceState.entries,
            selectedService = selectedService,
            loadState = sourceState.loadState,
        )
        val scopedEntries = if (selectedLocalTag == null) {
            baseState.entries
        } else {
            val entryIds = entryRefs
                .filter { it.tagId == selectedLocalTag }
                .map { it.entryId }
                .toSet()
            baseState.entries.filter { it.id in entryIds }
        }
        baseState.copy(
            entries = scopedEntries,
            scopeCount = scopedEntries.size,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, ListFilterUiState())

    val selectedServiceFlow: StateFlow<ServiceType> = selectedService
    val selectedLocalTagIdFlow: StateFlow<Long?> = selectedLocalTagId

    fun selectService(serviceType: ServiceType) {
        selectedService.value = serviceType
        selectedLocalTagId.value = null
    }

    fun selectLocalTag(tagId: Long?) {
        selectedLocalTagId.value = tagId
        selectedService.value = ServiceType.ALL
    }

    fun retryLoading() {
        entrySourceState.update { current ->
            current.copy(loadState = ListFilterLoadState.Loading)
        }
        observeArchiveEntries()
    }

    suspend fun createLocalTag(name: String): CreateTagResult {
        return tagRepository?.createLocalTagWithResult(name) ?: CreateTagResult.Failed
    }

    suspend fun renameLocalTag(tagId: Long, name: String): CreateTagResult {
        return tagRepository?.renameLocalTagWithResult(tagId, name) ?: CreateTagResult.Failed
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

    suspend fun restoreEntry(entryId: Long): Boolean {
        return repository.unarchive(entryId)
    }

    suspend fun markPendingDelete(entryId: Long): Long? {
        return repository.markPendingDelete(entryId)
    }

    private fun observeArchiveEntries() {
        observeEntriesJob?.cancel()
        observeEntriesJob = viewModelScope.launch {
            val entriesFlow = try {
                repository.observeArchiveEntries()
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
                    entrySourceState.value = ArchiveEntrySourceUiState(
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
}

private data class ArchiveEntrySourceUiState(
    val entries: List<UrlEntryEntity> = emptyList(),
    val loadState: ListFilterLoadState = ListFilterLoadState.Initial,
)

private class InMemoryArchiveEntryCardDisplayModeStore : EntryCardDisplayModeStore {
    override fun observeDisplayMode() = flowOf(EntryCardDisplayMode.RICH)
    override suspend fun setDisplayMode(mode: EntryCardDisplayMode) = Unit
}

private class InMemoryArchiveServiceFilterOrderStore : ServiceFilterOrderStore {
    override fun observeServiceOrder() = flowOf(
        listOf(ServiceType.YOUTUBE, ServiceType.X, ServiceType.INSTAGRAM, ServiceType.TIKTOK, ServiceType.WEB),
    )

    override suspend fun setServiceOrder(serviceOrder: List<ServiceType>) = Unit
}

private class InMemoryArchiveTopFilterOrderStore : TopFilterOrderStore {
    override fun observeOrderTokens() = flowOf(emptyList<String>())
    override suspend fun setOrderTokens(tokens: List<String>) = Unit
}
