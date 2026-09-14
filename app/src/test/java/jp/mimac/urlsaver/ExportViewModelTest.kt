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
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

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
        val staleArchive = archive("stale.zip", "snapshot-one")
        staleArchiveDeferred.complete(staleArchive)
        advanceUntilIdle()

        val state = viewModel.chatGptUiState.value
        assertEquals(setOf(1L, 2L), state.selectedTagIds)
        assertEquals("snapshot-two", state.preview?.snapshotToken)
        assertFalse(state.isContentConfirmed)
        assertFalse(state.isArchivePreparing)
        assertNull(state.preparedArchive)
        assertNull(state.archiveSuccessMessage)
        assertEquals(listOf(setOf(1L) to "snapshot-one"), repository.prepareRequests)
        assertEquals(listOf(staleArchive), repository.releasedArchives)
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
        val revokedArchive = archive("revoked.zip", "snapshot-one")
        staleArchiveDeferred.complete(revokedArchive)
        advanceUntilIdle()

        val state = viewModel.chatGptUiState.value
        assertFalse(state.isContentConfirmed)
        assertFalse(state.isArchivePreparing)
        assertNull(state.preparedArchive)
        assertNull(state.archiveSuccessMessage)
        assertEquals(listOf(revokedArchive), repository.releasedArchives)
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

        val abaArchive = archive("aba.zip", "snapshot-one")
        staleArchiveDeferred.complete(abaArchive)
        advanceUntilIdle()

        val state = viewModel.chatGptUiState.value
        assertFalse(state.isArchivePreparing)
        assertFalse(state.isContentConfirmed)
        assertNull(state.preparedArchive)
        assertNull(state.archiveSuccessMessage)
        assertEquals(listOf(abaArchive), repository.releasedArchives)
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

    @Test
    fun chatGptRegenerationAndScreenDisposeReleaseEveryReplacedArchive() = runTest {
        val repository = FakeExportRepository(
            previews = mapOf(setOf(1L) to preview("snapshot", setOf(1L))),
        )
        val viewModel = ExportViewModel(repository)
        viewModel.toggleChatGptTagSelection(1L)
        advanceUntilIdle()
        viewModel.setChatGptContentConfirmed(true)

        viewModel.prepareChatGptExport()
        advanceUntilIdle()
        val firstArchive = requireNotNull(viewModel.chatGptUiState.value.preparedArchive)

        viewModel.prepareChatGptExport()
        advanceUntilIdle()
        val secondArchive = requireNotNull(viewModel.chatGptUiState.value.preparedArchive)
        assertTrue(firstArchive != secondArchive)
        assertEquals(listOf(firstArchive), repository.releasedArchives)

        viewModel.clearPreparedChatGptArchive()
        assertNull(viewModel.chatGptUiState.value.preparedArchive)
        assertEquals(listOf(firstArchive, secondArchive), repository.releasedArchives)
    }

    @Test
    fun standardArchiveReleaseAndCancelledReturnAreBothReclaimed() = runTest {
        val deferredArchive = CompletableDeferred<PreparedExportArchive>()
        var prepareCount = 0
        val repository = FakeExportRepository(
            standardPrepareBlock = {
                prepareCount += 1
                if (prepareCount == 1) {
                    archive("standard.zip", "standard")
                } else {
                    withContext(NonCancellable) { deferredArchive.await() }
                }
            },
        )
        val viewModel = ExportViewModel(repository)

        val firstArchive = viewModel.prepareExport(ExportOutputFormat.ZIP).getOrThrow()
        viewModel.releaseAllStandardPreparedArchives()
        assertEquals(listOf(firstArchive), repository.releasedArchives)

        val cancelledResult = async { viewModel.prepareExport(ExportOutputFormat.ZIP) }
        runCurrent()
        cancelledResult.cancel()
        val lateArchive = archive("late-standard.zip", "late-standard")
        deferredArchive.complete(lateArchive)
        assertTrue(runCatching { cancelledResult.await() }.isFailure)
        assertEquals(listOf(firstArchive, lateArchive), repository.releasedArchives)
    }

    private fun archive(fileName: String, token: String): PreparedExportArchive {
        val file = preparedFile(byteArrayOf(1))
        return PreparedExportArchive(
            fileName = fileName,
            file = file,
            byteCount = file.length(),
            entryCount = 1,
            mimeType = ExportOutputFormat.ZIP.mimeType,
            chatGptPreview = preview(token, setOf(1L)),
        )
    }

    private class FakeExportRepository(
        private val previews: Map<Set<Long>, ChatGptExportPreview> = emptyMap(),
        private val previewBlock: (suspend (Set<Long>) -> ChatGptExportPreview)? = null,
        private val standardPrepareBlock: suspend (ExportRequest) -> PreparedExportArchive = {
            error("standard export is not used")
        },
        private val prepareBlock: suspend (Set<Long>, String) -> PreparedExportArchive = { _, token ->
            val file = preparedFile(byteArrayOf(1))
            PreparedExportArchive(
                fileName = "prepared.zip",
                file = file,
                byteCount = file.length(),
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
        val releasedArchives = mutableListOf<PreparedExportArchive>()

        override suspend fun loadAvailableTags(): List<ExportTagOption> = tags

        override fun observeAvailableTags(): Flow<List<ExportTagOption>> = tagsFlow

        override suspend fun prepareExport(request: ExportRequest): PreparedExportArchive {
            return standardPrepareBlock(request)
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

        override fun releasePreparedArchive(archive: PreparedExportArchive): Boolean {
            releasedArchives += archive
            return true
        }
    }

    private companion object {
        fun preparedFile(bytes: ByteArray): File {
            return File.createTempFile("rinbam-export-view-model", ".tmp").apply {
                writeBytes(bytes)
                deleteOnExit()
            }
        }

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
