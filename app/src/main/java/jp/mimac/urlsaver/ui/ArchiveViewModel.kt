package jp.mimac.urlsaver.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import jp.mimac.urlsaver.data.CollectionEntity
import jp.mimac.urlsaver.data.CreateCollectionResult
import jp.mimac.urlsaver.data.EntryCardDisplayModeStore
import jp.mimac.urlsaver.data.ServiceFilterOrderStore
import jp.mimac.urlsaver.data.TopFilterOrderStore
import jp.mimac.urlsaver.data.UrlRepository
import jp.mimac.urlsaver.domain.EntryCardDisplayMode
import jp.mimac.urlsaver.domain.ServiceType
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn

class ArchiveViewModel(
    private val repository: UrlRepository,
    displayModeStore: EntryCardDisplayModeStore = InMemoryArchiveEntryCardDisplayModeStore(),
    private val serviceFilterOrderStore: ServiceFilterOrderStore = InMemoryArchiveServiceFilterOrderStore(),
    private val topFilterOrderStore: TopFilterOrderStore = InMemoryArchiveTopFilterOrderStore(),
) : ViewModel() {
    private val selectedService = MutableStateFlow(ServiceType.ALL)
    private val selectedCollectionId = MutableStateFlow<Long?>(null)

    val collections: StateFlow<List<CollectionEntity>> = repository.observeCollections()
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
        repository.observeArchiveEntries(),
        selectedService,
        selectedCollectionId,
        repository.observeLocalTagCollectionEntryRefs(),
    ) { entries, selectedService, selectedCollection, localTagRefs ->
        buildListFilterUiState(
            entries = entries,
            selectedService = selectedService,
            selectedCollectionId = selectedCollection,
            localTagCollectionEntryIds = localTagRefs.groupBy(
                keySelector = { it.collectionId },
                valueTransform = { it.entryId },
            ).mapValues { (_, entryIds) -> entryIds.toSet() },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ListFilterUiState())

    val selectedServiceFlow: StateFlow<ServiceType> = selectedService
    val selectedCollectionIdFlow: StateFlow<Long?> = selectedCollectionId

    fun selectService(serviceType: ServiceType) {
        selectedService.value = serviceType
        selectedCollectionId.value = null
    }

    fun selectCollection(collectionId: Long?) {
        selectedCollectionId.value = collectionId
        selectedService.value = ServiceType.ALL
    }

    suspend fun createCollection(name: String): CreateCollectionResult {
        return repository.createCollection(name)
    }

    suspend fun reorderCollections(collectionIds: List<Long>): Boolean {
        return repository.reorderCollections(collectionIds)
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

    suspend fun deleteCollection(collectionId: Long): Boolean {
        val deleted = repository.deleteCollection(collectionId)
        if (deleted && selectedCollectionId.value == collectionId) {
            selectedCollectionId.value = null
        }
        return deleted
    }
}

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
