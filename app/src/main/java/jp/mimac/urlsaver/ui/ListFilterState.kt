package jp.mimac.urlsaver.ui

import jp.mimac.urlsaver.data.UrlEntryEntity
import jp.mimac.urlsaver.domain.ServiceType

sealed interface ListFilterLoadState {
    data object Initial : ListFilterLoadState
    data object Loading : ListFilterLoadState
    data object Content : ListFilterLoadState
    data object Empty : ListFilterLoadState
    data class Error(
        val message: String = "URLを読み込めませんでした",
    ) : ListFilterLoadState
}

data class ListFilterUiState(
    val entries: List<UrlEntryEntity> = emptyList(),
    val globalCount: Int = 0,
    val scopeCount: Int = 0,
    val loadState: ListFilterLoadState = ListFilterLoadState.Initial,
)

internal fun buildListFilterUiState(
    entries: List<UrlEntryEntity>,
    selectedService: ServiceType,
    loadState: ListFilterLoadState = entries.defaultListFilterLoadState(),
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
        loadState = loadState,
    )
}

private fun List<UrlEntryEntity>.defaultListFilterLoadState(): ListFilterLoadState {
    return if (isEmpty()) {
        ListFilterLoadState.Empty
    } else {
        ListFilterLoadState.Content
    }
}
