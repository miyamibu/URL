package jp.mimac.urlsaver

import jp.mimac.urlsaver.data.ChatGptExportPreview
import jp.mimac.urlsaver.data.ChatGptExportPreviewEntry
import jp.mimac.urlsaver.data.ExportOutputFormat
import jp.mimac.urlsaver.data.ExportRepository
import jp.mimac.urlsaver.data.ExportRequest
import jp.mimac.urlsaver.data.ExportTagOption
import jp.mimac.urlsaver.data.PreparedExportArchive
import jp.mimac.urlsaver.domain.SharedTagScope
import jp.mimac.urlsaver.ui.ExportViewModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ExportViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun chatGptTagChange_cancelsPreparationAndNeverAcceptsStaleArchive() = runTest {
        val staleArchiveDeferred = CompletableDeferred<PreparedExportArchive>()
        val repository = FakeExportRepository(
            previews = mapOf(
                setOf(1L) to preview("snapshot-one", setOf(1L)),
                setOf(1L, 2L) to preview("snapshot-two", setOf(1L, 2L)),
            ),
            prepareBlock = { _, _ ->
                withContext(NonCancellable) { staleArchiveDeferred.await() }
            },
        )
        val viewModel = ExportViewModel(repository)

        viewModel.toggleChatGptTagSelection(1L)
        advanceUntilIdle()
        viewModel.setChatGptContentConfirmed(true)
        viewModel.prepareChatGptExport()
        assertTrue(viewModel.chatGptUiState.value.isArchivePreparing)

        viewModel.toggleChatGptTagSelection(2L)
        advanceUntilIdle()
        staleArchiveDeferred.complete(archive("stale.zip", "snapshot-one"))
        advanceUntilIdle()

        val state = viewModel.chatGptUiState.value
        assertEquals(setOf(1L, 2L), state.selectedTagIds)
        assertEquals("snapshot-two", state.preview?.snapshotToken)
        assertFalse(state.isContentConfirmed)
        assertFalse(state.isArchivePreparing)
        assertNull(state.preparedArchive)
        assertNull(state.archiveSuccessMessage)
        assertEquals(listOf(setOf(1L) to "snapshot-one"), repository.prepareRequests)
    }

    @Test
    fun chatGptConfirmationRevoked_neverAcceptsNonCancellableArchiveCompletion() = runTest {
        val staleArchiveDeferred = CompletableDeferred<PreparedExportArchive>()
        val repository = FakeExportRepository(
            previews = mapOf(setOf(1L) to preview("snapshot-one", setOf(1L))),
            prepareBlock = { _, _ ->
                withContext(NonCancellable) { staleArchiveDeferred.await() }
            },
        )
        val viewModel = ExportViewModel(repository)

        viewModel.toggleChatGptTagSelection(1L)
        advanceUntilIdle()
        viewModel.setChatGptContentConfirmed(true)
        viewModel.prepareChatGptExport()
        assertTrue(viewModel.chatGptUiState.value.isArchivePreparing)

        viewModel.setChatGptContentConfirmed(false)
        staleArchiveDeferred.complete(archive("revoked.zip", "snapshot-one"))
        advanceUntilIdle()

        val state = viewModel.chatGptUiState.value
        assertFalse(state.isContentConfirmed)
        assertFalse(state.isArchivePreparing)
        assertNull(state.preparedArchive)
        assertNull(state.archiveSuccessMessage)
    }

    @Test
    fun chatGptTagSelectionAba_neverAcceptsOldJobWithSameSelectionAndSnapshot() = runTest {
        val staleArchiveDeferred = CompletableDeferred<PreparedExportArchive>()
        val repository = FakeExportRepository(
            previews = mapOf(
                setOf(1L) to preview("snapshot-one", setOf(1L)),
                setOf(1L, 2L) to preview("snapshot-two", setOf(1L, 2L)),
            ),
            prepareBlock = { _, _ ->
                withContext(NonCancellable) { staleArchiveDeferred.await() }
            },
        )
        val viewModel = ExportViewModel(repository)

        viewModel.toggleChatGptTagSelection(1L)
        advanceUntilIdle()
        viewModel.setChatGptContentConfirmed(true)
        viewModel.prepareChatGptExport()

        viewModel.toggleChatGptTagSelection(2L)
        advanceUntilIdle()
        viewModel.toggleChatGptTagSelection(2L)
        advanceUntilIdle()
        assertEquals(setOf(1L), viewModel.chatGptUiState.value.selectedTagIds)
        assertEquals("snapshot-one", viewModel.chatGptUiState.value.preview?.snapshotToken)
        assertFalse(viewModel.chatGptUiState.value.isContentConfirmed)

        staleArchiveDeferred.complete(archive("aba.zip", "snapshot-one"))
        advanceUntilIdle()

        val state = viewModel.chatGptUiState.value
        assertFalse(state.isArchivePreparing)
        assertFalse(state.isContentConfirmed)
        assertNull(state.preparedArchive)
        assertNull(state.archiveSuccessMessage)
    }

    @Test
    fun chatGptPreviewRefresh_resetsExplicitConfirmationForNewSnapshot() = runTest {
        var previewCallCount = 0
        val repository = FakeExportRepository(
            previewBlock = {
                previewCallCount += 1
                preview("snapshot-$previewCallCount", setOf(1L))
            },
        )
        val viewModel = ExportViewModel(repository)

        viewModel.toggleChatGptTagSelection(1L)
        advanceUntilIdle()
        viewModel.setChatGptContentConfirmed(true)
        assertTrue(viewModel.chatGptUiState.value.isContentConfirmed)

        viewModel.retryChatGptPreview()
        advanceUntilIdle()

        assertEquals("snapshot-2", viewModel.chatGptUiState.value.preview?.snapshotToken)
        assertFalse(viewModel.chatGptUiState.value.isContentConfirmed)
        assertNull(viewModel.chatGptUiState.value.preparedArchive)
    }

    @Test
    fun chatGptPreviewRetry_neverLetsOlderResponseOverwriteNewSnapshot() = runTest {
        val oldPreviewDeferred = CompletableDeferred<ChatGptExportPreview>()
        var previewCallCount = 0
        val repository = FakeExportRepository(
            previewBlock = {
                previewCallCount += 1
                if (previewCallCount == 1) {
                    withContext(NonCancellable) { oldPreviewDeferred.await() }
                } else {
                    preview("new-snapshot", setOf(1L))
                }
            },
        )
        val viewModel = ExportViewModel(repository)

        viewModel.toggleChatGptTagSelection(1L)
        assertTrue(viewModel.chatGptUiState.value.isPreviewLoading)
        viewModel.retryChatGptPreview()
        advanceUntilIdle()
        assertEquals("new-snapshot", viewModel.chatGptUiState.value.preview?.snapshotToken)

        oldPreviewDeferred.complete(preview("old-snapshot", setOf(1L)))
        advanceUntilIdle()

        assertEquals("new-snapshot", viewModel.chatGptUiState.value.preview?.snapshotToken)
        assertFalse(viewModel.chatGptUiState.value.isContentConfirmed)
    }

    @Test
    fun chatGptUnexpectedPrepareFailure_neverExposesRawExceptionMessage() = runTest {
        val repository = FakeExportRepository(
            previews = mapOf(setOf(1L) to preview("snapshot", setOf(1L))),
            prepareBlock = { _, _ -> error("database-path=/Users/private raw-secret") },
        )
        val viewModel = ExportViewModel(repository)
        viewModel.toggleChatGptTagSelection(1L)
        advanceUntilIdle()
        viewModel.setChatGptContentConfirmed(true)

        viewModel.prepareChatGptExport()
        advanceUntilIdle()

        val error = viewModel.chatGptUiState.value.archiveError.orEmpty()
        assertTrue(error.contains("ChatGPT用ZIPを作成できません"))
        assertFalse(error.contains("raw-secret"))
        assertFalse(error.contains("/Users/"))
    }

    @Test
    fun chatGptPrepare_withoutExplicitConfirmationNeverCallsRepository() = runTest {
        val repository = FakeExportRepository(
            previews = mapOf(setOf(1L) to preview("snapshot", setOf(1L))),
        )
        val viewModel = ExportViewModel(repository)
        viewModel.toggleChatGptTagSelection(1L)
        advanceUntilIdle()

        viewModel.prepareChatGptExport()
        advanceUntilIdle()

        assertTrue(repository.prepareRequests.isEmpty())
        assertNull(viewModel.chatGptUiState.value.preparedArchive)
        assertTrue(viewModel.chatGptUiState.value.archiveError.orEmpty().contains("確認欄"))
    }

    private fun archive(fileName: String, token: String): PreparedExportArchive {
        return PreparedExportArchive(
            fileName = fileName,
            bytes = byteArrayOf(1),
            entryCount = 1,
            mimeType = ExportOutputFormat.ZIP.mimeType,
            chatGptPreview = preview(token, setOf(1L)),
        )
    }

    private class FakeExportRepository(
        private val previews: Map<Set<Long>, ChatGptExportPreview> = emptyMap(),
        private val previewBlock: (suspend (Set<Long>) -> ChatGptExportPreview)? = null,
        private val prepareBlock: suspend (Set<Long>, String) -> PreparedExportArchive = { _, token ->
            PreparedExportArchive(
                fileName = "prepared.zip",
                bytes = byteArrayOf(1),
                entryCount = 1,
                mimeType = ExportOutputFormat.ZIP.mimeType,
                chatGptPreview = preview(token, setOf(1L)),
            )
        },
    ) : ExportRepository {
        private val tags = listOf(
            ExportTagOption(1L, "tag-1", SharedTagScope.LOCAL_ONLY, 1),
            ExportTagOption(2L, "tag-2", SharedTagScope.LOCAL_ONLY, 1),
        )
        private val tagsFlow = MutableStateFlow(tags)
        val prepareRequests = mutableListOf<Pair<Set<Long>, String>>()

        override suspend fun loadAvailableTags(): List<ExportTagOption> = tags

        override fun observeAvailableTags(): Flow<List<ExportTagOption>> = tagsFlow

        override suspend fun prepareExport(request: ExportRequest): PreparedExportArchive {
            error("standard export is not used")
        }

        override suspend fun loadChatGptExportPreview(selectedTagIds: Set<Long>): ChatGptExportPreview {
            return previewBlock?.invoke(selectedTagIds) ?: requireNotNull(previews[selectedTagIds])
        }

        override suspend fun prepareChatGptExport(
            selectedTagIds: Set<Long>,
            expectedSnapshotToken: String,
        ): PreparedExportArchive {
            prepareRequests += selectedTagIds to expectedSnapshotToken
            return prepareBlock(selectedTagIds, expectedSnapshotToken)
        }
    }

    private companion object {
        fun preview(token: String, selectedTagIds: Set<Long>): ChatGptExportPreview {
            return ChatGptExportPreview(
                selectedTagNames = selectedTagIds.sorted().map { "tag-$it" },
                entries = listOf(
                    ChatGptExportPreviewEntry(
                        publicSafeId = "public-$token",
                        effectiveTitle = "Title",
                        normalizedUrl = "https://example.com/$token",
                        localTagNames = selectedTagIds.sorted().map { "tag-$it" },
                        archiveEntryJson = "{\"publicSafeId\":\"public-$token\"}",
                    ),
                ),
                excludedCount = 0,
                exclusionsByReason = emptyMap(),
                snapshotToken = token,
            )
        }
    }
}
