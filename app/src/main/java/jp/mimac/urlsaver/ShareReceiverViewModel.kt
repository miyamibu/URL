package jp.mimac.urlsaver

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import jp.mimac.urlsaver.app.AppContainer
import jp.mimac.urlsaver.data.SHARE_DEGRADATION_TRUNCATED_TO_FIRST_URL
import jp.mimac.urlsaver.data.SHARE_DEGRADATION_TRUNCATED_TO_MAX_URLS
import jp.mimac.urlsaver.domain.AssignTagResult
import jp.mimac.urlsaver.domain.CreateTagResult
import jp.mimac.urlsaver.domain.SaveResult
import jp.mimac.urlsaver.domain.ShareSaveResult
import jp.mimac.urlsaver.domain.UrlRules
import jp.mimac.urlsaver.domain.normalizeSharedTagName
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

internal interface ShareReceiverOperations {
    suspend fun saveURL(url: String, memo: String?): SaveResult
    suspend fun assignTag(entryId: Long, tagId: Long): AssignTagResult
    suspend fun createLocalTag(name: String): CreateTagResult
    suspend fun findLocalTagIdByName(name: String): Long?
}

internal class RepositoryShareReceiverOperations(
    private val container: AppContainer,
) : ShareReceiverOperations {
    override suspend fun saveURL(url: String, memo: String?): SaveResult {
        return container.repository.saveFromManualInput(url, initialMemo = memo)
    }

    override suspend fun assignTag(entryId: Long, tagId: Long): AssignTagResult {
        return container.tagRepository.assignTagWithResult(tagId = tagId, entryId = entryId)
    }

    override suspend fun createLocalTag(name: String): CreateTagResult {
        return container.tagRepository.createLocalTagWithResult(name)
    }

    override suspend fun findLocalTagIdByName(name: String): Long? {
        return container.tagRepository.findLocalTagIdByName(name)
    }
}

internal data class ShareReceiverUiState(
    val selectedLocalTagIds: Set<Long> = emptySet(),
    val newTagName: String = "",
    val tagCreateError: String? = null,
    val isTagSelectionLocked: Boolean = false,
    val isCreatingTag: Boolean = false,
    val isSaving: Boolean = false,
    val resultMessage: String? = null,
    val retryMessage: String? = null,
    val pendingCount: Int = 0,
) {
    val hasPendingShare: Boolean get() = pendingCount > 0
}

internal class ShareReceiverViewModel(
    private val operations: ShareReceiverOperations,
    private val savedStateHandle: SavedStateHandle,
) : ViewModel() {
    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    }
    private var durableState = restoreDurableState()
    private var isSaving = false
    private var isCreatingTag = false
    private var createTagJob: Job? = null
    private var tagCreationGeneration = 0L
    private val _uiState = MutableStateFlow(buildUiState())
    val uiState: StateFlow<ShareReceiverUiState> = _uiState
    private val meaningfulActionChannel = Channel<Unit>(capacity = Channel.BUFFERED)
    val meaningfulActions = meaningfulActionChannel.receiveAsFlow()

    fun initialize(payload: ShareReceiverPayload.Pending) {
        val sameSource = durableState.initialized &&
            durableState.originalUrls == payload.urls &&
            durableState.memo == payload.memo &&
            durableState.degradationNotice == payload.degradationNotice
        if (sameSource) {
            publish()
            return
        }
        durableState = ShareReceiverDurableState(
            initialized = true,
            operationId = UUID.randomUUID().toString(),
            originalUrls = payload.urls,
            memo = payload.memo,
            degradationNotice = payload.degradationNotice,
            pendingItems = payload.urls.map { url -> ShareReceiverPendingItem(url = url) },
        )
        persistAndPublish()
    }

    fun toggleLocalTag(tagId: Long) {
        if (isTagEditingDisabled()) return
        val selected = durableState.selectedLocalTagIds.toSet()
        durableState = durableState.copy(
            selectedLocalTagIds = if (tagId in selected) {
                (selected - tagId).sorted()
            } else {
                (selected + tagId).sorted()
            },
            tagCreateError = null,
        )
        persistAndPublish()
    }

    fun updateNewTagName(name: String) {
        if (isTagEditingDisabled()) return
        durableState = durableState.copy(newTagName = name, tagCreateError = null)
        persistAndPublish()
    }

    fun createLocalTag() {
        if (isTagEditingDisabled()) return
        val normalizedName = normalizeSharedTagName(durableState.newTagName)
        val generation = ++tagCreationGeneration
        isCreatingTag = true
        publish()
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                when (val result = operations.createLocalTag(normalizedName)) {
                    is CreateTagResult.Success -> {
                        if (generation == tagCreationGeneration) selectCreatedTag(result.tagId)
                    }
                    CreateTagResult.Duplicate -> {
                        val duplicateId = operations.findLocalTagIdByName(normalizedName)
                        if (generation == tagCreationGeneration) {
                            if (duplicateId != null) {
                                selectCreatedTag(duplicateId)
                            } else {
                                updateTagCreateError("同じ名前のタグがあります")
                            }
                        }
                    }
                    CreateTagResult.InvalidName -> if (generation == tagCreationGeneration) {
                        updateTagCreateError("タグ名を入力してください")
                    }
                    is CreateTagResult.LimitReached -> if (generation == tagCreationGeneration) {
                        updateTagCreateError(result.message)
                    }
                    CreateTagResult.Failed -> if (generation == tagCreationGeneration) {
                        updateTagCreateError("タグを作成できませんでした")
                    }
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                if (generation == tagCreationGeneration) {
                    updateTagCreateError("タグを作成できませんでした")
                }
            } finally {
                if (generation == tagCreationGeneration) {
                    isCreatingTag = false
                    createTagJob = null
                    publish()
                }
            }
        }
        createTagJob = job
        job.start()
    }

    fun savePendingShare() {
        if (isSaving || isCreatingTag || durableState.pendingItems.isEmpty()) return
        isSaving = true
        val operationTagIds = durableState.operationSelectedLocalTagIds
            ?: durableState.selectedLocalTagIds.distinct().sorted()
        durableState = durableState.copy(
            operationSelectedLocalTagIds = operationTagIds,
            retryMessage = null,
            resultMessage = null,
        )
        persistAndPublish()
        viewModelScope.launch {
            var registeredMeaningfulAction = false
            try {
                val workItems = durableState.pendingItems
                val retryItems = mutableListOf<ShareReceiverPendingItem>()
                for ((index, item) in workItems.withIndex()) {
                    val attempt = attempt(item, operationTagIds)
                    registeredMeaningfulAction = registeredMeaningfulAction || attempt.meaningfulAction
                    val completed = durableState.completedItems + attempt.completedItems
                    attempt.retryItem?.let(retryItems::add)
                    durableState = durableState.copy(
                        pendingItems = retryItems + workItems.drop(index + 1),
                        completedItems = completed.distinctBy { completedItem -> completedItem.url },
                    )
                    persistAndPublish()
                }

                val pendingItems = durableState.pendingItems
                durableState = if (pendingItems.isEmpty()) {
                    durableState.copy(
                        resultMessage = buildCompletionMessage(durableState),
                        retryMessage = null,
                    )
                } else {
                    durableState.copy(
                        retryMessage = buildRetryMessage(durableState),
                        resultMessage = null,
                    )
                }
                if (registeredMeaningfulAction) meaningfulActionChannel.send(Unit)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } finally {
                isSaving = false
                persistAndPublish()
            }
        }
    }

    fun discard() {
        tagCreationGeneration += 1
        createTagJob?.cancel()
        createTagJob = null
        isCreatingTag = false
        isSaving = false
        durableState = ShareReceiverDurableState()
        savedStateHandle[STATE_KEY] = null
        publish()
    }

    private suspend fun attempt(
        item: ShareReceiverPendingItem,
        operationTagIds: List<Long>,
    ): ShareReceiverItemAttempt {
        if (!item.needsUrlSave) {
            val entryId = item.entryId
            if (entryId == null) {
                return ShareReceiverItemAttempt(
                    retryItem = item.copy(needsUrlSave = true, pendingTagIds = emptyList()),
                )
            }
            val failedTagIds = assignTags(entryId, item.pendingTagIds)
            return if (failedTagIds.isEmpty()) {
                ShareReceiverItemAttempt(
                    completedItems = listOf(
                        ShareReceiverCompletedItem(
                            item.url,
                            item.savedResult ?: ShareSaveResult.DUPLICATE_ACTIVE.name,
                        ),
                    ),
                )
            } else {
                ShareReceiverItemAttempt(
                    retryItem = item.copy(pendingTagIds = failedTagIds.sorted()),
                )
            }
        }

        val saveResult = runCatching { operations.saveURL(item.url, durableState.memo) }
            .getOrElse { SaveResult(ShareSaveResult.SAVE_FAILED) }
        if (saveResult.result == ShareSaveResult.SAVE_FAILED) {
            return ShareReceiverItemAttempt(retryItem = item)
        }

        val failedTagIds = if (shouldAssignShareTags(saveResult.result, saveResult.entryId)) {
            assignTags(requireNotNull(saveResult.entryId), operationTagIds)
        } else {
            emptyList()
        }
        if (failedTagIds.isNotEmpty()) {
            return ShareReceiverItemAttempt(
                retryItem = item.copy(
                    needsUrlSave = false,
                    entryId = saveResult.entryId,
                    pendingTagIds = failedTagIds.sorted(),
                    savedResult = saveResult.result.name,
                ),
                meaningfulAction = saveResult.result == ShareSaveResult.CREATED ||
                    saveResult.result == ShareSaveResult.RESTORED_FROM_PENDING_DELETE,
            )
        }

        return ShareReceiverItemAttempt(
            completedItems = listOf(ShareReceiverCompletedItem(item.url, saveResult.result.name)),
            meaningfulAction = saveResult.result == ShareSaveResult.CREATED ||
                saveResult.result == ShareSaveResult.RESTORED_FROM_PENDING_DELETE,
        )
    }

    private suspend fun assignTags(entryId: Long, tagIds: Collection<Long>): List<Long> {
        val failed = mutableListOf<Long>()
        tagIds.distinct().forEach { tagId ->
            val result = runCatching { operations.assignTag(entryId, tagId) }
                .getOrDefault(AssignTagResult.Failed)
            when (result) {
                AssignTagResult.Success,
                AssignTagResult.AlreadyAssigned,
                -> Unit
                is AssignTagResult.LimitReached,
                AssignTagResult.Failed,
                -> failed += tagId
            }
        }
        return failed
    }

    private fun selectCreatedTag(tagId: Long) {
        if (isTagSelectionLocked()) return
        durableState = durableState.copy(
            selectedLocalTagIds = (durableState.selectedLocalTagIds + tagId).distinct().sorted(),
            newTagName = "",
            tagCreateError = null,
        )
        persistAndPublish()
    }

    private fun updateTagCreateError(message: String) {
        if (isTagSelectionLocked()) return
        durableState = durableState.copy(tagCreateError = message)
        persistAndPublish()
    }

    private fun restoreDurableState(): ShareReceiverDurableState {
        val encoded = savedStateHandle.get<String>(STATE_KEY) ?: return ShareReceiverDurableState()
        val restored = runCatching { json.decodeFromString<ShareReceiverDurableState>(encoded) }
            .getOrDefault(ShareReceiverDurableState())
        val requiresLegacyOperationLock = restored.operationSelectedLocalTagIds == null &&
            (restored.retryMessage != null ||
                restored.completedItems.isNotEmpty() ||
                restored.pendingItems.any { !it.needsUrlSave })
        return if (requiresLegacyOperationLock) {
            restored.copy(operationSelectedLocalTagIds = restored.selectedLocalTagIds.distinct().sorted())
        } else {
            restored
        }
    }

    private fun persistAndPublish() {
        if (durableState.initialized) {
            savedStateHandle[STATE_KEY] = json.encodeToString(durableState)
        }
        publish()
    }

    private fun publish() {
        _uiState.value = buildUiState()
    }

    private fun buildUiState(): ShareReceiverUiState {
        return ShareReceiverUiState(
            selectedLocalTagIds = durableState.selectedLocalTagIds.toSet(),
            newTagName = durableState.newTagName,
            tagCreateError = durableState.tagCreateError,
            isTagSelectionLocked = isTagSelectionLocked(),
            isCreatingTag = isCreatingTag,
            isSaving = isSaving,
            resultMessage = durableState.resultMessage,
            retryMessage = durableState.retryMessage,
            pendingCount = durableState.pendingItems.size,
        )
    }

    private fun buildCompletionMessage(state: ShareReceiverDurableState): String {
        if (state.originalUrls.size == 1) {
            val result = state.completedItems.firstOrNull()?.result
                ?.let { runCatching { ShareSaveResult.valueOf(it) }.getOrNull() }
                ?: ShareSaveResult.SAVE_FAILED
            return shareReceiverResultText(result, state.degradationNotice)
        }
        val results = state.completedItems.mapNotNull { item ->
            runCatching { ShareSaveResult.valueOf(item.result) }.getOrNull()
        }
        val created = results.count { it == ShareSaveResult.CREATED }
        val duplicate = results.count {
            it == ShareSaveResult.DUPLICATE_ACTIVE || it == ShareSaveResult.DUPLICATE_ARCHIVED
        }
        val restored = results.count { it == ShareSaveResult.RESTORED_FROM_PENDING_DELETE }
        val failed = state.originalUrls.size - created - duplicate - restored
        return buildString {
            append("${state.originalUrls.size}件を処理しました（新規$created / 既存$duplicate / 復元$restored / 失敗$failed）")
            degradationText(state.degradationNotice)?.let { append("\n").append(it) }
        }
    }

    private fun buildRetryMessage(state: ShareReceiverDurableState): String {
        val tagOnlyCount = state.pendingItems.count { !it.needsUrlSave }
        val saveCount = state.pendingItems.size - tagOnlyCount
        return buildString {
            if (saveCount > 0) {
                append("${saveCount}件を保存できませんでした。入力と選択タグは保持しています。")
            }
            if (tagOnlyCount > 0) {
                if (isNotEmpty()) append("\n")
                append("${tagOnlyCount}件はURLを保存済みですが、タグ付けが未完了です。再試行ではURLを重複送信せず、未完了のタグだけを追加します。")
            }
            if (state.completedItems.isNotEmpty()) {
                append("\n完了済みの${state.completedItems.size}件は再送しません。")
            }
        }
    }

    private fun shareReceiverResultText(result: ShareSaveResult, degradationNotice: String?): String {
        return buildString {
            append(
                when (result) {
                    ShareSaveResult.CREATED -> "保存しました"
                    ShareSaveResult.DUPLICATE_ACTIVE -> "この内容はすでに保存済みです"
                    ShareSaveResult.DUPLICATE_ARCHIVED -> "この内容はアーカイブ済みです"
                    ShareSaveResult.RESTORED_FROM_PENDING_DELETE -> "削除を取り消して復元しました"
                    ShareSaveResult.PERSONAL_URL_LIMIT_REACHED -> "現在のプランの保存上限に達しました。不要なURLを整理してから追加してください。"
                    ShareSaveResult.INPUT_TOO_LARGE -> "共有内容が長すぎるため処理できませんでした"
                    ShareSaveResult.INVALID_URL -> "有効なURLではありませんでした"
                    ShareSaveResult.NO_URL_FOUND -> "保存できる内容が見つかりませんでした"
                    ShareSaveResult.SAVE_FAILED -> "保存できませんでした"
                    ShareSaveResult.BATCH_PROCESSED -> "保存しました"
                },
            )
            degradationText(degradationNotice)?.let { append("\n").append(it) }
        }
    }

    private fun degradationText(notice: String?): String? {
        return when (notice) {
            SHARE_DEGRADATION_TRUNCATED_TO_FIRST_URL ->
                "共有内容に複数URLが含まれていたため、1件目のみ保存しました"
            SHARE_DEGRADATION_TRUNCATED_TO_MAX_URLS ->
                "共有内容に多数のURLが含まれていたため、先頭${UrlRules.MAX_BATCH_SAVE_URLS_PER_INTAKE}件のみ処理しました"
            else -> null
        }
    }

    private fun isTagSelectionLocked(): Boolean {
        return isSaving || durableState.operationSelectedLocalTagIds != null
    }

    private fun isTagEditingDisabled(): Boolean {
        return isCreatingTag || isTagSelectionLocked()
    }

    private companion object {
        const val STATE_KEY = "share_receiver.durable_state.v1"
    }
}

private fun shouldAssignShareTags(result: ShareSaveResult, entryId: Long?): Boolean {
    return entryId != null && when (result) {
        ShareSaveResult.CREATED,
        ShareSaveResult.DUPLICATE_ACTIVE,
        ShareSaveResult.RESTORED_FROM_PENDING_DELETE,
        -> true
        ShareSaveResult.BATCH_PROCESSED,
        ShareSaveResult.DUPLICATE_ARCHIVED,
        ShareSaveResult.PERSONAL_URL_LIMIT_REACHED,
        ShareSaveResult.SAVE_FAILED,
        ShareSaveResult.INPUT_TOO_LARGE,
        ShareSaveResult.INVALID_URL,
        ShareSaveResult.NO_URL_FOUND,
        -> false
    }
}

@Serializable
private data class ShareReceiverDurableState(
    val initialized: Boolean = false,
    val operationId: String = "",
    val originalUrls: List<String> = emptyList(),
    val memo: String? = null,
    val degradationNotice: String? = null,
    val selectedLocalTagIds: List<Long> = emptyList(),
    val operationSelectedLocalTagIds: List<Long>? = null,
    val newTagName: String = "",
    val tagCreateError: String? = null,
    val pendingItems: List<ShareReceiverPendingItem> = emptyList(),
    val completedItems: List<ShareReceiverCompletedItem> = emptyList(),
    val resultMessage: String? = null,
    val retryMessage: String? = null,
)

@Serializable
private data class ShareReceiverPendingItem(
    val url: String,
    val needsUrlSave: Boolean = true,
    val entryId: Long? = null,
    val pendingTagIds: List<Long> = emptyList(),
    val savedResult: String? = null,
)

@Serializable
private data class ShareReceiverCompletedItem(
    val url: String,
    val result: String,
)

private data class ShareReceiverItemAttempt(
    val retryItem: ShareReceiverPendingItem? = null,
    val completedItems: List<ShareReceiverCompletedItem> = emptyList(),
    val meaningfulAction: Boolean = false,
)
