package jp.mimac.urlsaver.ui

import jp.mimac.urlsaver.data.UrlEntryEntity
import jp.mimac.urlsaver.domain.ServiceType

data class ListSearchUiState(
    val entries: List<UrlEntryEntity> = emptyList(),
    val globalCount: Int = 0,
    val scopeCount: Int = 0,
)

internal fun buildListSearchUiState(
    entries: List<UrlEntryEntity>,
    selectedService: ServiceType,
): ListSearchUiState {
    val scopedEntries = if (selectedService == ServiceType.ALL) {
        entries
    } else {
        entries.filter { it.serviceType == selectedService }
    }

    return ListSearchUiState(
        entries = scopedEntries,
        globalCount = entries.size,
        scopeCount = scopedEntries.size,
    )
}