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
