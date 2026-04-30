package jp.mimac.urlsaver.ui

import android.content.Intent
import androidx.compose.material3.SnackbarDuration
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import jp.mimac.urlsaver.data.TagRepository
import jp.mimac.urlsaver.data.EXTRA_SHARE_BATCH_CREATED_COUNT
import jp.mimac.urlsaver.data.EXTRA_SHARE_BATCH_DUPLICATE_COUNT
import jp.mimac.urlsaver.data.EXTRA_SHARE_BATCH_FAILED_COUNT
import jp.mimac.urlsaver.data.EXTRA_SHARE_BATCH_RESTORED_COUNT
import jp.mimac.urlsaver.data.EXTRA_SHARE_BATCH_TOTAL_COUNT
import jp.mimac.urlsaver.data.EXTRA_SHARE_DEGRADATION_NOTICE
import jp.mimac.urlsaver.data.EXTRA_SHARE_ENTRY_ID
import jp.mimac.urlsaver.data.EXTRA_MAIN_INTENT_EVENT_TOKEN
import jp.mimac.urlsaver.data.EXTRA_SHARE_SAVE_RESULT
import jp.mimac.urlsaver.data.SHARE_DEGRADATION_TRUNCATED_TO_FIRST_URL
import jp.mimac.urlsaver.data.SHARE_DEGRADATION_TRUNCATED_TO_MAX_URLS
import jp.mimac.urlsaver.data.UrlRepository
import jp.mimac.urlsaver.domain.DetailEffect
import jp.mimac.urlsaver.domain.MainNavigationEvent
import jp.mimac.urlsaver.domain.ShareSaveResult
import jp.mimac.urlsaver.domain.SnackbarEvent
import jp.mimac.urlsaver.domain.SnackbarEventKind
import jp.mimac.urlsaver.domain.SnackbarTargetRoute
import jp.mimac.urlsaver.util.AppClock
import jp.mimac.urlsaver.util.SystemAppClock
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow as ChannelOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

sealed interface SnackbarControlEvent {
    data object DismissUndoTitle : SnackbarControlEvent
}

class MainActivityViewModel(
    private val repository: UrlRepository,
    private val clock: AppClock = SystemAppClock,
    private val tagRepository: TagRepository? = null,
) : ViewModel() {

    private val snackbarChannel = Channel<SnackbarEvent>(capacity = 64, onBufferOverflow = ChannelOverflow.SUSPEND)
    val snackbarEvents = snackbarChannel.receiveAsFlow()

    private val navigationChannel = Channel<MainNavigationEvent>(capacity = Channel.BUFFERED)
    val navigationEvents = navigationChannel.receiveAsFlow()

    private val snackbarControlChannel = Channel<SnackbarControlEvent>(capacity = Channel.BUFFERED)
    val snackbarControlEvents = snackbarControlChannel.receiveAsFlow()

    private val consumedShareSignatures = mutableSetOf<String>()
    private val deleteTimers = mutableMapOf<Long, Job>()

    private var titleUndo: TitleUndoState? = null
    private val secondaryIntentHandler = MainActivitySecondaryIntentHandler(
        enqueueSnackbar = ::enqueueSnackbar,
        navigate = { event ->
            viewModelScope.launch {
                navigationChannel.send(event)
            }
        },
    )

    fun consumeShareResult(intent: Intent, currentRoute: String?) {
        val resultName = intent.getStringExtra(EXTRA_SHARE_SAVE_RESULT) ?: return
        val entryId = if (intent.hasExtra(EXTRA_SHARE_ENTRY_ID)) intent.getLongExtra(EXTRA_SHARE_ENTRY_ID, -1L) else null
        val degradationNotice = intent.getStringExtra(EXTRA_SHARE_DEGRADATION_NOTICE)
        val signature = buildShareIntentSignature(
            intent = intent,
            resultName = resultName,
            entryId = entryId,
            degradationNotice = degradationNotice,
        )
        if (!consumedShareSignatures.add(signature)) return

        val result = runCatching { ShareSaveResult.valueOf(resultName) }.getOrNull() ?: return
        when (result) {
            ShareSaveResult.BATCH_PROCESSED -> {
                enqueueSnackbar(
                    SnackbarEvent(
                        kind = SnackbarEventKind.INFO,
                        message = buildBatchProcessedMessage(intent),
                    ),
                )
            }
            ShareSaveResult.CREATED -> enqueueSnackbar(SnackbarEvent(kind = SnackbarEventKind.INFO, message = "保存しました"))
            ShareSaveResult.DUPLICATE_ACTIVE -> enqueueSnackbar(
                duplicateActiveEvent(entryId = entryId),
            )
            ShareSaveResult.DUPLICATE_ARCHIVED -> {
                if (currentRoute == Routes.ARCHIVE) {
                    enqueueSnackbar(SnackbarEvent(kind = SnackbarEventKind.INFO, message = "このURLはアーカイブ済みです"))
                } else {
                    enqueueSnackbar(
                        SnackbarEvent(
                            kind = SnackbarEventKind.OPEN_EXISTING,
                            message = "このURLはアーカイブ済みです",
                            actionLabel = "見る",
                            entryId = entryId,
                            targetRoute = SnackbarTargetRoute.ARCHIVE,
                        ),
                    )
                }
            }

            ShareSaveResult.RESTORED_FROM_PENDING_DELETE -> {
                if (entryId == null || entryId <= 0L) {
                    enqueueSnackbar(SnackbarEvent(kind = SnackbarEventKind.INFO, message = "保存できませんでした"))
                    return
                }
                cancelDeleteTimer(entryId)
                enqueueSnackbar(SnackbarEvent(kind = SnackbarEventKind.INFO, message = "削除を取り消して復元しました"))
            }

            ShareSaveResult.INPUT_TOO_LARGE -> enqueueSnackbar(
                SnackbarEvent(kind = SnackbarEventKind.INFO, message = "共有内容が長すぎるため処理できませんでした"),
            )
            ShareSaveResult.PERSONAL_URL_LIMIT_REACHED -> enqueueSnackbar(
                SnackbarEvent(
                    kind = SnackbarEventKind.INFO,
                    message = "ローンチ版の保存上限に達しました。不要なURLを整理してから追加してください。",
                ),
            )
            ShareSaveResult.SAVE_FAILED -> enqueueSnackbar(SnackbarEvent(kind = SnackbarEventKind.INFO, message = "保存できませんでした"))
            ShareSaveResult.INVALID_URL -> enqueueSnackbar(SnackbarEvent(kind = SnackbarEventKind.INFO, message = "有効なURLではありませんでした"))
            ShareSaveResult.NO_URL_FOUND -> enqueueSnackbar(SnackbarEvent(kind = SnackbarEventKind.INFO, message = "URLが見つかりませんでした"))
        }

        when (degradationNotice) {
            SHARE_DEGRADATION_TRUNCATED_TO_FIRST_URL -> {
                enqueueSnackbar(
                    SnackbarEvent(
                        kind = SnackbarEventKind.INFO,
                        message = "共有内容に複数URLが含まれていたため、1件目のみ保存しました",
                    ),
                )
            }
            SHARE_DEGRADATION_TRUNCATED_TO_MAX_URLS -> {
                enqueueSnackbar(
                    SnackbarEvent(
                        kind = SnackbarEventKind.INFO,
                        message = "共有内容に多数のURLが含まれていたため、先頭${jp.mimac.urlsaver.domain.UrlRules.MAX_BATCH_SAVE_URLS_PER_INTAKE}件のみ処理しました",
                    ),
                )
            }
            null -> Unit
        }
    }

    fun onManualSaveResult(result: ShareSaveResult, entryId: Long?, currentRoute: String?) {
        when (result) {
            ShareSaveResult.BATCH_PROCESSED -> Unit
            ShareSaveResult.CREATED -> enqueueSnackbar(SnackbarEvent(kind = SnackbarEventKind.INFO, message = "保存しました"))
            ShareSaveResult.DUPLICATE_ACTIVE -> enqueueSnackbar(
                duplicateActiveEvent(entryId = entryId),
            )
            ShareSaveResult.DUPLICATE_ARCHIVED -> {
                if (currentRoute == Routes.ARCHIVE) {
                    enqueueSnackbar(SnackbarEvent(kind = SnackbarEventKind.INFO, message = "このURLはアーカイブ済みです"))
                } else {
                    enqueueSnackbar(
                        SnackbarEvent(
                            kind = SnackbarEventKind.OPEN_EXISTING,
                            message = "このURLはアーカイブ済みです",
                            actionLabel = "見る",
                            entryId = entryId,
                            targetRoute = SnackbarTargetRoute.ARCHIVE,
                        ),
                    )
                }
            }

            ShareSaveResult.RESTORED_FROM_PENDING_DELETE -> {
                entryId?.let(::cancelDeleteTimer)
                enqueueSnackbar(SnackbarEvent(kind = SnackbarEventKind.INFO, message = "削除を取り消して復元しました"))
            }

            ShareSaveResult.PERSONAL_URL_LIMIT_REACHED -> enqueueSnackbar(
                SnackbarEvent(
                    kind = SnackbarEventKind.INFO,
                    message = "ローンチ版の保存上限に達しました。不要なURLを整理してから追加してください。",
                ),
            )
            ShareSaveResult.SAVE_FAILED -> enqueueSnackbar(SnackbarEvent(kind = SnackbarEventKind.INFO, message = "保存できませんでした"))
            ShareSaveResult.INPUT_TOO_LARGE,
            ShareSaveResult.INVALID_URL,
            ShareSaveResult.NO_URL_FOUND,
            -> {
                // Manual input handles these in bottom sheet error state.
            }
        }
    }

    fun consumeTagImportResult(intent: Intent) {
        secondaryIntentHandler.consumeTagImportResult(intent)
    }

    fun consumeDeepLinkIntent(intent: Intent) {
        secondaryIntentHandler.consumeDeepLinkIntent(intent)
    }

    fun onDetailEffect(effect: DetailEffect) {
        when (effect) {
            is DetailEffect.NavigateBackAfterPendingDelete -> {
                invalidateTitleUndo()
                enqueueSnackbar(
                    SnackbarEvent(
                        kind = SnackbarEventKind.UNDO_PENDING_DELETE,
                        message = "削除しました",
                        actionLabel = "元に戻す",
                        duration = SnackbarDuration.Indefinite,
                        customDurationMillis = 5000,
                        entryId = effect.entryId,
                    ),
                )
            }

            is DetailEffect.NavigateBackAfterArchive -> {
                invalidateTitleUndo()
                enqueueSnackbar(
                    SnackbarEvent(
                        kind = SnackbarEventKind.UNDO_ARCHIVE,
                        message = "アーカイブしました",
                        actionLabel = "元に戻す",
                        duration = SnackbarDuration.Indefinite,
                        customDurationMillis = 5000,
                        entryId = effect.entryId,
                    ),
                )
            }

            is DetailEffect.NavigateBackAfterRestore -> {
                invalidateTitleUndo()
                enqueueSnackbar(
                    SnackbarEvent(
                        kind = SnackbarEventKind.INFO,
                        message = "復元しました",
                    ),
                )
            }

            is DetailEffect.TitleEdited -> {
                invalidateTitleUndo()
                titleUndo = TitleUndoState(effect.entryId, effect.oldTitle)
                enqueueSnackbar(
                    SnackbarEvent(
                        kind = SnackbarEventKind.UNDO_TITLE_EDIT,
                        message = "タイトルを保存しました",
                        actionLabel = "元に戻す",
                        duration = SnackbarDuration.Indefinite,
                        customDurationMillis = 5000,
                        entryId = effect.entryId,
                    ),
                )
            }
        }
    }

    fun onTitleEditStarted() {
        invalidateTitleUndo()
    }

    fun onRouteChanged(route: String?) {
        val undo = titleUndo ?: return
        if (route?.startsWith("detail/") == true) {
            val routeEntryId = route.substringAfter("detail/").toLongOrNull()
            if (routeEntryId != null && routeEntryId != undo.entryId) {
                invalidateTitleUndo()
            }
        }
    }

    fun enqueueForegroundSharedTagSyncIfNeeded() {
        val tags = tagRepository ?: return
        viewModelScope.launch {
            tags.triggerSyncIfStale()
        }
    }

    fun cleanupOnStart() {
        viewModelScope.launch {
            repository.cleanupExpiredPendingDeletes()
        }
    }

    fun startDeleteTimer(entryId: Long, pendingDeletionUntil: Long) {
        cancelDeleteTimer(entryId)
        val now = clock.nowEpochMillis()
        val delayMs = (pendingDeletionUntil - now).coerceAtLeast(0)
        val job = viewModelScope.launch {
            delay(delayMs)
            repository.finalizePendingDelete(entryId)
            deleteTimers.remove(entryId)
        }
        deleteTimers[entryId] = job
    }

    fun onBatchPendingDelete(pendingDeletions: Map<Long, Long>) {
        if (pendingDeletions.isEmpty()) return
        invalidateTitleUndo()
        pendingDeletions.forEach { (entryId, pendingUntil) ->
            startDeleteTimer(entryId, pendingUntil)
        }
        val entryIds = pendingDeletions.keys.sorted()
        enqueueSnackbar(
            SnackbarEvent(
                kind = SnackbarEventKind.UNDO_BATCH_PENDING_DELETE,
                message = "${entryIds.size}件を削除しました",
                actionLabel = "元に戻す",
                duration = SnackbarDuration.Indefinite,
                customDurationMillis = 5000,
                entryIds = entryIds,
            ),
        )
    }

    fun onBatchArchive(entryIds: List<Long>) {
        if (entryIds.isEmpty()) return
        invalidateTitleUndo()
        enqueueSnackbar(
            SnackbarEvent(
                kind = SnackbarEventKind.UNDO_BATCH_ARCHIVE,
                message = "${entryIds.size}件をアーカイブしました",
                actionLabel = "元に戻す",
                duration = SnackbarDuration.Indefinite,
                customDurationMillis = 5000,
                entryIds = entryIds.sorted(),
            ),
        )
    }

    fun cancelDeleteTimer(entryId: Long) {
        deleteTimers.remove(entryId)?.cancel()
    }

    suspend fun onSnackbarAction(event: SnackbarEvent) {
        when (event.kind) {
            SnackbarEventKind.UNDO_PENDING_DELETE -> {
                val id = event.entryId ?: return
                if (repository.restore(id)) {
                    cancelDeleteTimer(id)
                }
            }

            SnackbarEventKind.UNDO_ARCHIVE -> {
                val id = event.entryId ?: return
                repository.unarchive(id)
            }

            SnackbarEventKind.UNDO_BATCH_PENDING_DELETE -> {
                val ids = event.entryIds ?: return
                ids.forEach { id ->
                    if (repository.restore(id)) {
                        cancelDeleteTimer(id)
                    }
                }
            }

            SnackbarEventKind.UNDO_BATCH_ARCHIVE -> {
                val ids = event.entryIds ?: return
                ids.forEach { id -> repository.unarchive(id) }
            }

            SnackbarEventKind.OPEN_ARCHIVE -> {
                navigationChannel.send(MainNavigationEvent.NavigateToArchive)
            }

            SnackbarEventKind.OPEN_EXISTING -> {
                val id = event.entryId
                if (id != null && id > 0L) {
                    navigationChannel.send(MainNavigationEvent.NavigateToDetail(id))
                    return
                }
                when (event.targetRoute) {
                    SnackbarTargetRoute.ARCHIVE -> navigationChannel.send(MainNavigationEvent.NavigateToArchive)
                    SnackbarTargetRoute.MAIN,
                    null,
                    -> navigationChannel.send(MainNavigationEvent.NavigateToMain)
                }
            }

            SnackbarEventKind.OPEN_TAG_DETAIL -> {
                val tagId = event.tagId ?: return
                if (tagId > 0L) {
                    navigationChannel.send(MainNavigationEvent.NavigateToTagDetail(tagId))
                }
            }

            SnackbarEventKind.UNDO_TITLE_EDIT -> {
                val undo = titleUndo ?: return
                repository.restoreUserTitle(undo.entryId, undo.oldTitle)
                titleUndo = null
            }

            SnackbarEventKind.INFO -> Unit
        }
    }

    fun onSnackbarDismissed(event: SnackbarEvent) {
        if (event.kind == SnackbarEventKind.UNDO_TITLE_EDIT) {
            titleUndo = null
        }
    }

    fun notifyCopySuccess() {
        enqueueSnackbar(SnackbarEvent(kind = SnackbarEventKind.INFO, message = "リンクをコピーしました"))
    }

    fun notifyMemoSaved() {
        enqueueSnackbar(SnackbarEvent(kind = SnackbarEventKind.INFO, message = "メモを保存しました"))
    }

    fun notifyOpenFailed() {
        enqueueSnackbar(SnackbarEvent(kind = SnackbarEventKind.INFO, message = "リンクを開けませんでした"))
    }

    fun notifyMetadataRetryUnavailable() {
        enqueueSnackbar(SnackbarEvent(kind = SnackbarEventKind.INFO, message = "この状態では再取得できません"))
    }

    fun notifyArchiveFailed() {
        enqueueSnackbar(SnackbarEvent(kind = SnackbarEventKind.INFO, message = "アーカイブできませんでした"))
    }

    fun notifyDeleteFailed() {
        enqueueSnackbar(SnackbarEvent(kind = SnackbarEventKind.INFO, message = "削除できませんでした"))
    }

    private fun invalidateTitleUndo() {
        if (titleUndo != null) {
            titleUndo = null
            viewModelScope.launch {
                snackbarControlChannel.send(SnackbarControlEvent.DismissUndoTitle)
            }
        }
    }

    private fun enqueueSnackbar(event: SnackbarEvent) {
        viewModelScope.launch {
            snackbarChannel.send(event)
        }
    }

    private fun duplicateActiveEvent(entryId: Long?): SnackbarEvent {
        return SnackbarEvent(
            kind = SnackbarEventKind.OPEN_EXISTING,
            message = "このURLはすでに保存済みです",
            actionLabel = "見る",
            entryId = entryId,
            targetRoute = SnackbarTargetRoute.MAIN,
        )
    }

    private fun buildBatchProcessedMessage(intent: Intent): String {
        val total = intent.getIntExtra(EXTRA_SHARE_BATCH_TOTAL_COUNT, 0)
        val created = intent.getIntExtra(EXTRA_SHARE_BATCH_CREATED_COUNT, 0)
        val duplicate = intent.getIntExtra(EXTRA_SHARE_BATCH_DUPLICATE_COUNT, 0)
        val restored = intent.getIntExtra(EXTRA_SHARE_BATCH_RESTORED_COUNT, 0)
        val failed = intent.getIntExtra(EXTRA_SHARE_BATCH_FAILED_COUNT, 0)
        if (total <= 0) {
            return "複数URLの共有を処理しました"
        }
        return "複数URLの共有を処理しました（${total}件） 新規${created}件 / 既存${duplicate}件 / 復元${restored}件 / 失敗${failed}件"
    }

    private fun buildShareIntentSignature(
        intent: Intent,
        resultName: String,
        entryId: Long?,
        degradationNotice: String?,
    ): String {
        val eventToken = intent.getStringExtra(EXTRA_MAIN_INTENT_EVENT_TOKEN)?.takeIf { it.isNotBlank() }
        if (eventToken != null) {
            return "event:$eventToken"
        }
        return listOf(
            "legacy",
            resultName,
            entryId?.toString() ?: "null",
            degradationNotice ?: "null",
            intExtraComponent(intent, EXTRA_SHARE_BATCH_TOTAL_COUNT),
            intExtraComponent(intent, EXTRA_SHARE_BATCH_CREATED_COUNT),
            intExtraComponent(intent, EXTRA_SHARE_BATCH_DUPLICATE_COUNT),
            intExtraComponent(intent, EXTRA_SHARE_BATCH_RESTORED_COUNT),
            intExtraComponent(intent, EXTRA_SHARE_BATCH_FAILED_COUNT),
        ).joinToString(":")
    }

    private fun intExtraComponent(intent: Intent, key: String): String {
        return if (intent.hasExtra(key)) intent.getIntExtra(key, 0).toString() else "null"
    }

    private data class TitleUndoState(
        val entryId: Long,
        val oldTitle: String?,
    )
}
