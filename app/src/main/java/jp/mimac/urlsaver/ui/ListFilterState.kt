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

internal fun sortListEntries(entries: List<UrlEntryEntity>, order: jp.mimac.urlsaver.domain.EntryListSortOrder): List<UrlEntryEntity> {
    if (order.key == jp.mimac.urlsaver.domain.EntryListSortKey.ADDED_DATE) {
        val comparator = compareBy<UrlEntryEntity> { it.createdAt }.thenBy { it.id }
        return entries.sortedWith(if (order.ascending) comparator else comparator.reversed())
    }
    // Compute display titles once per snapshot instead of allocating them on every comparison.
    val titled = entries.map { entry -> entry to jp.mimac.urlsaver.domain.UrlRules.effectiveTitle(
        entry.userTitle, entry.fetchedTitle, entry.serviceType, entry.normalizedHost,
    ).lowercase(java.util.Locale.ROOT) }
    val comparator = compareBy<Pair<UrlEntryEntity, String>> { it.second }.thenBy { it.first.id }
    return titled.sortedWith(if (order.ascending) comparator else comparator.reversed()).map { it.first }
}
