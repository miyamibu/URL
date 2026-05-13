package jp.mimac.urlsaver.ui

import jp.mimac.urlsaver.data.UrlEntryEntity
import jp.mimac.urlsaver.domain.ServiceType

data class ListFilterUiState(
    val entries: List<UrlEntryEntity> = emptyList(),
    val globalCount: Int = 0,
    val scopeCount: Int = 0,
)

internal fun buildListFilterUiState(
    entries: List<UrlEntryEntity>,
    selectedService: ServiceType,
): ListFilterUiState {
    val scopedEntries = if (selectedService == ServiceType.ALL) {
        entries
    } else {
        entries.filter { serviceForFilterMatch(it.serviceType) == selectedService }
    }

    return ListFilterUiState(
        entries = scopedEntries,
        globalCount = entries.size,
        scopeCount = scopedEntries.size,
    )
}

internal fun buildListFilterUiState(
    entries: List<UrlEntryEntity>,
    selectedCollectionId: Long?,
): ListFilterUiState {
    return buildListFilterUiState(
        entries = entries,
        selectedService = ServiceType.ALL,
        selectedCollectionId = selectedCollectionId,
    )
}

internal fun buildListFilterUiState(
    entries: List<UrlEntryEntity>,
    selectedService: ServiceType,
    selectedCollectionId: Long?,
    localTagCollectionEntryIds: Map<Long, Set<Long>> = emptyMap(),
): ListFilterUiState {
    val collectionScopedEntries = if (selectedCollectionId == null) {
        entries
    } else {
        val localTagEntryIds = localTagCollectionEntryIds[selectedCollectionId].orEmpty()
        entries.filter { it.collectionId == selectedCollectionId || it.id in localTagEntryIds }
    }

    val serviceScoped = if (selectedService == ServiceType.ALL) {
        collectionScopedEntries
    } else {
        collectionScopedEntries.filter { serviceForFilterMatch(it.serviceType) == selectedService }
    }

    return ListFilterUiState(
        entries = serviceScoped,
        globalCount = entries.size,
        scopeCount = serviceScoped.size,
    )
}
