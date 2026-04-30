package jp.mimac.urlsaver.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import jp.mimac.urlsaver.data.ExportRecordStateFilter
import jp.mimac.urlsaver.data.ExportRepository
import jp.mimac.urlsaver.data.ExportRequest
import jp.mimac.urlsaver.data.ExportScope
import jp.mimac.urlsaver.data.ExportTagOption
import jp.mimac.urlsaver.data.ExportOutputFormat
import jp.mimac.urlsaver.data.PreparedExportArchive
import jp.mimac.urlsaver.domain.ServiceType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.time.LocalDate

data class ExportUiState(
    val scope: ExportScope = ExportScope.ALL,
    val selectedTagIds: Set<Long> = emptySet(),
    val recordStateFilter: ExportRecordStateFilter = ExportRecordStateFilter.BOTH,
    val serviceType: ServiceType? = null,
    val onlyWithMemo: Boolean = false,
    val dateFromInput: String = "",
    val dateToInput: String = "",
)

class ExportViewModel(
    private val exportRepository: ExportRepository,
) : ViewModel() {

    private val uiStateFlow = MutableStateFlow(ExportUiState())
    val uiState: StateFlow<ExportUiState> = uiStateFlow.asStateFlow()

    private val availableTagsFlow = MutableStateFlow<List<ExportTagOption>>(emptyList())
    val availableTags: StateFlow<List<ExportTagOption>> = availableTagsFlow.asStateFlow()

    init {
        viewModelScope.launch {
            exportRepository.observeAvailableTags().collectLatest { tags ->
                availableTagsFlow.value = tags
                removeUnavailableSelectedTags(tags)
            }
        }
    }

    fun selectScope(scope: ExportScope) {
        uiStateFlow.value = uiStateFlow.value.copy(
            scope = scope,
            selectedTagIds = when (scope) {
                ExportScope.ALL, ExportScope.SHARED_TAGS_ONLY -> emptySet()
                ExportScope.SINGLE_TAG -> uiStateFlow.value.selectedTagIds.take(1).toSet()
                ExportScope.MULTIPLE_TAGS -> uiStateFlow.value.selectedTagIds
            },
        )
    }

    fun toggleTagSelection(tagId: Long) {
        val current = uiStateFlow.value
        val next = when (current.scope) {
            ExportScope.SINGLE_TAG -> {
                if (tagId in current.selectedTagIds) emptySet() else setOf(tagId)
            }
            ExportScope.MULTIPLE_TAGS -> {
                current.selectedTagIds.toMutableSet().apply {
                    if (!add(tagId)) remove(tagId)
                }
            }
            ExportScope.ALL, ExportScope.SHARED_TAGS_ONLY -> current.selectedTagIds
        }
        uiStateFlow.value = current.copy(selectedTagIds = next)
    }

    fun selectRecordStateFilter(filter: ExportRecordStateFilter) {
        uiStateFlow.value = uiStateFlow.value.copy(recordStateFilter = filter)
    }

    fun selectServiceType(serviceType: ServiceType?) {
        uiStateFlow.value = uiStateFlow.value.copy(serviceType = serviceType)
    }

    fun setOnlyWithMemo(enabled: Boolean) {
        uiStateFlow.value = uiStateFlow.value.copy(onlyWithMemo = enabled)
    }

    fun setDateFromInput(value: String) {
        uiStateFlow.value = uiStateFlow.value.copy(dateFromInput = value)
    }

    fun setDateToInput(value: String) {
        uiStateFlow.value = uiStateFlow.value.copy(dateToInput = value)
    }

    suspend fun prepareExport(outputFormat: ExportOutputFormat): Result<PreparedExportArchive> {
        return runCatching {
            exportRepository.prepareExport(
                ExportRequest(
                    scope = uiStateFlow.value.scope,
                    selectedTagIds = uiStateFlow.value.selectedTagIds,
                    recordStateFilter = uiStateFlow.value.recordStateFilter,
                    serviceType = uiStateFlow.value.serviceType,
                    onlyWithMemo = uiStateFlow.value.onlyWithMemo,
                    dateFrom = parseDate(uiStateFlow.value.dateFromInput),
                    dateTo = parseDate(uiStateFlow.value.dateToInput),
                    outputFormat = outputFormat,
                ),
            )
        }
    }

    private fun parseDate(input: String): LocalDate? {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return null
        return LocalDate.parse(trimmed)
    }

    private fun removeUnavailableSelectedTags(tags: List<ExportTagOption>) {
        val availableIds = tags.mapTo(mutableSetOf()) { it.id }
        val current = uiStateFlow.value
        val selected = current.selectedTagIds.intersect(availableIds)
        if (selected != current.selectedTagIds) {
            uiStateFlow.value = current.copy(selectedTagIds = selected)
        }
    }
}
