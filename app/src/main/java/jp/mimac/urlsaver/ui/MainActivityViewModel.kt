package jp.mimac.urlsaver.ui

import android.content.Intent
import androidx.compose.material3.SnackbarDuration
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import jp.mimac.urlsaver.data.EXTRA_SHARE_BATCH_CREATED_COUNT
import jp.mimac.urlsaver.data.EXTRA_SHARE_BATCH_DUPLICATE_COUNT
import jp.mimac.urlsaver.data.EXTRA_SHARE_BATCH_FAILED_COUNT
import jp.mimac.urlsaver.data.EXTRA_SHARE_BATCH_RESTORED_COUNT
import jp.mimac.urlsaver.data.EXTRA_SHARE_BATCH_TOTAL_COUNT
import jp.mimac.urlsaver.data.EXTRA_SHARE_DEGRADATION_NOTICE
import jp.mimac.urlsaver.data.EXTRA_SHARE_ENTRY_ID
import jp.mimac.urlsaver.data.EXTRA_SHARE_SAVE_RESULT
import jp.mimac.urlsaver.data.SHARE_DEGRADATION_TRUNCATED_TO_FIRST_URL
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

    fun consumeShareResult(intent: Intent, currentRoute: String?) {
        val resultName = intent.getStringExtra(EXTRA_SHARE_SAVE_RESULT) ?: return
        val entryId = if (intent.hasExtra(EXTRA_SHARE_ENTRY_ID)) intent.getLongExtra(EXTRA_SHARE_ENTRY_ID, -1L) else null
        val degradationNotice = intent.getStringExtra(EXTRA_SHARE_DEGRADATION_NOTICE)
        val signature = "${System.identityHashCode(intent)}:$resultName:${entryId ?: -1}:${degradationNotice ?: ""}"
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

            ShareSaveResult.SAVE_FAILED -> enqueueSnackbar(SnackbarEvent(kind = SnackbarEventKind.INFO, message = "保存できませんでした"))
            ShareSaveResult.INVALID_URL,
            ShareSaveResult.NO_URL_FOUND,
            -> {
                // Manual input handles these in bottom sheet error state.
            }
        }
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

    private data class TitleUndoState(
        val entryId: Long,
        val oldTitle: String?,
    )
}
