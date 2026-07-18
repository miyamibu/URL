package jp.mimac.urlsaver.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import jp.mimac.urlsaver.data.ChatGptExportException
import jp.mimac.urlsaver.data.ChatGptExportFailureReason
import jp.mimac.urlsaver.data.ChatGptExportPreview
import jp.mimac.urlsaver.data.ExportRecordStateFilter
import jp.mimac.urlsaver.data.ExportRepository
import jp.mimac.urlsaver.data.ExportRequest
import jp.mimac.urlsaver.data.ExportScope
import jp.mimac.urlsaver.data.ExportTagOption
import jp.mimac.urlsaver.data.ExportOutputFormat
import jp.mimac.urlsaver.data.PreparedExportArchive
import jp.mimac.urlsaver.domain.ServiceType
import jp.mimac.urlsaver.domain.SharedTagScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.job
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

data class ChatGptExportUiState(
    val selectedTagIds: Set<Long> = emptySet(),
    val preview: ChatGptExportPreview? = null,
    val isPreviewLoading: Boolean = false,
    val previewError: String? = null,
    val isContentConfirmed: Boolean = false,
    val preparedArchive: PreparedExportArchive? = null,
    val isArchivePreparing: Boolean = false,
    val archiveError: String? = null,
    val archiveSuccessMessage: String? = null,
)

class ExportViewModel(
    private val exportRepository: ExportRepository,
) : ViewModel() {

    private val uiStateFlow = MutableStateFlow(ExportUiState())
    val uiState: StateFlow<ExportUiState> = uiStateFlow.asStateFlow()

    private val availableTagsFlow = MutableStateFlow<List<ExportTagOption>>(emptyList())
    val availableTags: StateFlow<List<ExportTagOption>> = availableTagsFlow.asStateFlow()

    private val availableChatGptTagsFlow = MutableStateFlow<List<ExportTagOption>>(emptyList())
    val availableChatGptTags: StateFlow<List<ExportTagOption>> = availableChatGptTagsFlow.asStateFlow()

    private val chatGptUiStateFlow = MutableStateFlow(ChatGptExportUiState())
    val chatGptUiState: StateFlow<ChatGptExportUiState> = chatGptUiStateFlow.asStateFlow()

    private var chatGptPreviewJob: Job? = null
    private var chatGptPrepareJob: Job? = null
    private var chatGptPreviewGeneration: Long = 0L
    private var chatGptPrepareGeneration: Long = 0L

    init {
        viewModelScope.launch {
            exportRepository.observeAvailableTags().collectLatest { tags ->
                availableTagsFlow.value = tags
                removeUnavailableSelectedTags(tags)
                val localTags = tags.filter { tag -> tag.scope == SharedTagScope.LOCAL_ONLY }
                availableChatGptTagsFlow.value = localTags
                removeUnavailableChatGptTags(localTags)
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

    fun toggleChatGptTagSelection(tagId: Long) {
        invalidatePreparedChatGptArchive()
        val selectedTagIds = chatGptUiStateFlow.value.selectedTagIds.toMutableSet().apply {
            if (!add(tagId)) remove(tagId)
        }.toSet()
        chatGptUiStateFlow.value = chatGptUiStateFlow.value.copy(selectedTagIds = selectedTagIds)
        refreshChatGptPreview()
    }

    fun retryChatGptPreview() {
        invalidatePreparedChatGptArchive()
        refreshChatGptPreview()
    }

    fun setChatGptContentConfirmed(confirmed: Boolean) {
        val current = chatGptUiStateFlow.value
        val canConfirm = current.preview?.entries?.isNotEmpty() == true && !current.isPreviewLoading
        val nextConfirmed = confirmed && canConfirm
        if (!nextConfirmed) {
            invalidatePreparedChatGptArchive()
        }
        chatGptUiStateFlow.value = chatGptUiStateFlow.value.copy(
            isContentConfirmed = nextConfirmed,
        )
    }

    fun prepareChatGptExport() {
        val current = chatGptUiStateFlow.value
        val preview = current.preview
        if (
            current.selectedTagIds.isEmpty() ||
            preview == null ||
            preview.entries.isEmpty() ||
            !current.isContentConfirmed
        ) {
            chatGptUiStateFlow.value = current.copy(
                preparedArchive = null,
                isArchivePreparing = false,
                archiveError = CHATGPT_PREPARE_REQUIRES_PREVIEW_MESSAGE,
                archiveSuccessMessage = null,
            )
            return
        }

        chatGptPrepareJob?.cancel()
        chatGptPrepareGeneration += 1L
        val request = ChatGptPrepareRequest(
            selectedTagIds = current.selectedTagIds,
            snapshotToken = preview.snapshotToken,
            generationId = chatGptPrepareGeneration,
        )
        chatGptUiStateFlow.value = current.copy(
            preparedArchive = null,
            isArchivePreparing = true,
            archiveError = null,
            archiveSuccessMessage = null,
        )
        val prepareJob = viewModelScope.launch(start = CoroutineStart.LAZY) {
            val runningJob = coroutineContext.job
            try {
                val archive = exportRepository.prepareChatGptExport(
                    selectedTagIds = request.selectedTagIds,
                    expectedSnapshotToken = request.snapshotToken,
                )
                if (isCurrentChatGptRequest(request, runningJob)) {
                    chatGptPrepareJob = null
                    chatGptUiStateFlow.value = chatGptUiStateFlow.value.copy(
                        preparedArchive = archive,
                        isArchivePreparing = false,
                        archiveError = null,
                        archiveSuccessMessage =
                            "${archive.entryCount}件のChatGPT用ZIPを作成しました",
                    )
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: ChatGptExportException) {
                if (isCurrentChatGptRequest(request, runningJob)) {
                    chatGptPrepareJob = null
                    if (error.reason == ChatGptExportFailureReason.SNAPSHOT_CHANGED) {
                        refreshChatGptPreview(
                            preservedArchiveError = error.userMessage,
                            cancelPreparation = false,
                        )
                    } else {
                        chatGptUiStateFlow.value = chatGptUiStateFlow.value.copy(
                            preparedArchive = null,
                            isArchivePreparing = false,
                            archiveError = error.userMessage,
                            archiveSuccessMessage = null,
                        )
                    }
                }
            } catch (_: Exception) {
                if (isCurrentChatGptRequest(request, runningJob)) {
                    chatGptPrepareJob = null
                    chatGptUiStateFlow.value = chatGptUiStateFlow.value.copy(
                        preparedArchive = null,
                        isArchivePreparing = false,
                        archiveError = CHATGPT_PREPARE_FAILED_MESSAGE,
                        archiveSuccessMessage = null,
                    )
                }
            }
        }
        chatGptPrepareJob = prepareJob
        prepareJob.start()
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

    private fun removeUnavailableChatGptTags(tags: List<ExportTagOption>) {
        val availableIds = tags.mapTo(mutableSetOf()) { tag -> tag.id }
        val current = chatGptUiStateFlow.value
        val selected = current.selectedTagIds.intersect(availableIds)
        if (selected != current.selectedTagIds) {
            invalidatePreparedChatGptArchive()
            chatGptUiStateFlow.value = chatGptUiStateFlow.value.copy(selectedTagIds = selected)
            refreshChatGptPreview()
        }
    }

    private fun refreshChatGptPreview(
        preservedArchiveError: String? = null,
        cancelPreparation: Boolean = true,
    ) {
        chatGptPreviewJob?.cancel()
        chatGptPreviewGeneration += 1L
        val previewGeneration = chatGptPreviewGeneration
        if (cancelPreparation) {
            chatGptPrepareGeneration += 1L
            chatGptPrepareJob?.cancel()
            chatGptPrepareJob = null
        }
        val selectedTagIds = chatGptUiStateFlow.value.selectedTagIds
        if (selectedTagIds.isEmpty()) {
            chatGptUiStateFlow.value = ChatGptExportUiState()
            return
        }

        chatGptUiStateFlow.value = chatGptUiStateFlow.value.copy(
            preview = null,
            isPreviewLoading = true,
            previewError = null,
            isContentConfirmed = false,
            preparedArchive = null,
            isArchivePreparing = false,
            archiveError = preservedArchiveError,
            archiveSuccessMessage = null,
        )
        chatGptPreviewJob = viewModelScope.launch {
            try {
                val preview = exportRepository.loadChatGptExportPreview(selectedTagIds)
                if (
                    chatGptPreviewGeneration == previewGeneration &&
                    chatGptUiStateFlow.value.selectedTagIds == selectedTagIds
                ) {
                    chatGptUiStateFlow.value = chatGptUiStateFlow.value.copy(
                        preview = preview,
                        isPreviewLoading = false,
                        previewError = null,
                    )
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                if (
                    chatGptPreviewGeneration == previewGeneration &&
                    chatGptUiStateFlow.value.selectedTagIds == selectedTagIds
                ) {
                    chatGptUiStateFlow.value = chatGptUiStateFlow.value.copy(
                        preview = null,
                        isPreviewLoading = false,
                        previewError = CHATGPT_PREVIEW_FAILED_MESSAGE,
                    )
                }
            }
        }
    }

    private fun invalidatePreparedChatGptArchive() {
        chatGptPrepareGeneration += 1L
        chatGptPrepareJob?.cancel()
        chatGptPrepareJob = null
        chatGptUiStateFlow.value = chatGptUiStateFlow.value.copy(
            preparedArchive = null,
            isArchivePreparing = false,
            archiveError = null,
            archiveSuccessMessage = null,
        )
    }

    private fun isCurrentChatGptRequest(request: ChatGptPrepareRequest, runningJob: Job): Boolean {
        val current = chatGptUiStateFlow.value
        return chatGptPrepareGeneration == request.generationId &&
            chatGptPrepareJob === runningJob &&
            current.isArchivePreparing &&
            current.isContentConfirmed &&
            current.selectedTagIds == request.selectedTagIds &&
            current.preview?.snapshotToken == request.snapshotToken
    }

    private data class ChatGptPrepareRequest(
        val selectedTagIds: Set<Long>,
        val snapshotToken: String,
        val generationId: Long,
    )

    private companion object {
        const val CHATGPT_PREVIEW_FAILED_MESSAGE =
            "対象URLを確認できませんでした。時間をおいてもう一度お試しください。"
        const val CHATGPT_PREPARE_REQUIRES_PREVIEW_MESSAGE =
            "対象URLと表示内容を確認し、確認欄にチェックを入れてください。"
        const val CHATGPT_PREPARE_FAILED_MESSAGE =
            "ChatGPT用ZIPを作成できませんでした。もう一度お試しください。"
    }
}
