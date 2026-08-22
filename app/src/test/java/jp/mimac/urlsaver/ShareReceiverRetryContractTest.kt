package jp.mimac.urlsaver

import androidx.lifecycle.SavedStateHandle
import jp.mimac.urlsaver.domain.AssignTagResult
import jp.mimac.urlsaver.domain.CreateTagResult
import jp.mimac.urlsaver.domain.SaveResult
import jp.mimac.urlsaver.domain.ShareSaveResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

class ShareReceiverRetryContractTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun activityRecreationRestoresTagsAndRetriesURLAndTagOnlyFailuresWithoutDuplicateSave() = runTest {
        val firstURL = "https://example.com/tag-only"
        val secondURL = "https://example.com/save-retry"
        val payload = ShareReceiverPayload.Pending(
            urls = listOf(firstURL, secondURL),
            isBatch = true,
            memo = "共有メモ",
            degradationNotice = null,
        )
        val savedStateHandle = SavedStateHandle()
        val operations = FakeShareReceiverOperations().apply {
            enqueueSave(firstURL, SaveResult(ShareSaveResult.CREATED, entryId = 101L))
            enqueueSave(secondURL, SaveResult(ShareSaveResult.SAVE_FAILED))
            enqueueAssign(101L, 7L, AssignTagResult.Failed)
        }
        val first = ShareReceiverViewModel(operations, savedStateHandle)
        first.initialize(payload)
        first.toggleLocalTag(7L)

        first.savePendingShare()
        advanceUntilIdle()

        assertEquals(setOf(7L), first.uiState.value.selectedLocalTagIds)
        assertTrue(first.uiState.value.isTagSelectionLocked)
        assertEquals(2, first.uiState.value.pendingCount)
        assertTrue(first.uiState.value.retryMessage.orEmpty().contains("タグ付けが未完了"))

        first.toggleLocalTag(7L)
        first.toggleLocalTag(8L)
        first.updateNewTagName("後から追加")
        first.createLocalTag()
        advanceUntilIdle()
        assertEquals(setOf(7L), first.uiState.value.selectedLocalTagIds)
        assertEquals("", first.uiState.value.newTagName)
        assertTrue(operations.createTagCalls.isEmpty())

        val restored = ShareReceiverViewModel(operations, savedStateHandle)
        restored.initialize(payload)
        assertEquals(setOf(7L), restored.uiState.value.selectedLocalTagIds)
        assertTrue(restored.uiState.value.isTagSelectionLocked)
        assertEquals(2, restored.uiState.value.pendingCount)

        operations.enqueueAssign(101L, 7L, AssignTagResult.Success)
        operations.enqueueSave(secondURL, SaveResult(ShareSaveResult.CREATED, entryId = 202L))
        operations.enqueueAssign(202L, 7L, AssignTagResult.Success)
        restored.savePendingShare()
        advanceUntilIdle()

        assertFalse(restored.uiState.value.hasPendingShare)
        assertNotNull(restored.uiState.value.resultMessage)
        assertEquals(listOf(firstURL, secondURL, secondURL), operations.saveCalls)
        assertEquals(listOf(101L to 7L, 101L to 7L, 202L to 7L), operations.assignCalls)
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun operationStartFreezesTagSelectionForEveryItemBeforeFirstSaveRuns() = runTest {
        val firstURL = "https://example.com/frozen-first"
        val secondURL = "https://example.com/frozen-second"
        val operations = FakeShareReceiverOperations().apply {
            enqueueSave(firstURL, SaveResult(ShareSaveResult.CREATED, entryId = 401L))
            enqueueSave(secondURL, SaveResult(ShareSaveResult.CREATED, entryId = 402L))
            enqueueAssign(401L, 7L, AssignTagResult.Success)
            enqueueAssign(402L, 7L, AssignTagResult.Success)
        }
        val viewModel = ShareReceiverViewModel(operations, SavedStateHandle())
        viewModel.initialize(
            ShareReceiverPayload.Pending(
                urls = listOf(firstURL, secondURL),
                isBatch = true,
                degradationNotice = null,
            ),
        )
        viewModel.toggleLocalTag(7L)

        viewModel.savePendingShare()
        viewModel.toggleLocalTag(8L)
        viewModel.toggleLocalTag(7L)
        viewModel.updateNewTagName("処理中の変更")

        assertTrue(viewModel.uiState.value.isTagSelectionLocked)
        assertEquals(setOf(7L), viewModel.uiState.value.selectedLocalTagIds)
        assertEquals("", viewModel.uiState.value.newTagName)

        advanceUntilIdle()

        assertEquals(listOf(401L to 7L, 402L to 7L), operations.assignCalls)
        assertFalse(viewModel.uiState.value.hasPendingShare)
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun tagCreationBlocksSaveAndDuplicateCreationUntilCreatedTagIsSelected() = runTest {
        val url = "https://example.com/create-tag-race"
        val creation = CompletableDeferred<CreateTagResult>()
        val operations = FakeShareReceiverOperations().apply {
            createTagResult = creation
            enqueueSave(url, SaveResult(ShareSaveResult.CREATED, entryId = 501L))
            enqueueAssign(501L, 9L, AssignTagResult.Success)
        }
        val viewModel = ShareReceiverViewModel(operations, SavedStateHandle())
        viewModel.initialize(
            ShareReceiverPayload.Pending(
                urls = listOf(url),
                isBatch = false,
                degradationNotice = null,
            ),
        )
        viewModel.updateNewTagName("旅行")

        viewModel.createLocalTag()
        viewModel.createLocalTag()
        viewModel.toggleLocalTag(7L)
        viewModel.savePendingShare()

        assertTrue(viewModel.uiState.value.isCreatingTag)
        assertFalse(viewModel.uiState.value.isTagSelectionLocked)
        assertTrue(operations.saveCalls.isEmpty())
        assertEquals(setOf<Long>(), viewModel.uiState.value.selectedLocalTagIds)
        advanceUntilIdle()
        assertEquals(listOf("旅行"), operations.createTagCalls)

        creation.complete(CreateTagResult.Success(tagId = 9L))
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.isCreatingTag)
        assertEquals(setOf(9L), viewModel.uiState.value.selectedLocalTagIds)
        assertEquals("", viewModel.uiState.value.newTagName)

        viewModel.savePendingShare()
        advanceUntilIdle()
        assertEquals(listOf(url), operations.saveCalls)
        assertEquals(listOf(501L to 9L), operations.assignCalls)
    }

    @Test
    fun shareReceiverUiDisablesTagEditingAndSaveDuringCreationOrFrozenRetry() {
        val source = File("src/main/java/jp/mimac/urlsaver/ShareReceiverActivity.kt").readText()

        assertTrue(source.contains("val isTagEditingEnabled = !isTagSelectionLocked && !isCreatingTag"))
        assertTrue(source.contains("enabled = isTagEditingEnabled"))
        assertTrue(source.contains("enabled = !isSaving && !isCreatingTag"))
        assertTrue(source.contains("タグを作成しています。完了後に保存できます。"))
        assertTrue(source.contains("保存処理を開始したため、この操作が完了するまでタグ選択は変更できません。"))
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun repeatedSaveTapWhileOperationIsRunningDoesNotSubmitTwice() = runTest {
        val url = "https://example.com/one-submit"
        val operations = FakeShareReceiverOperations().apply {
            enqueueSave(url, SaveResult(ShareSaveResult.CREATED, entryId = 301L))
        }
        val viewModel = ShareReceiverViewModel(operations, SavedStateHandle())
        viewModel.initialize(
            ShareReceiverPayload.Pending(
                urls = listOf(url),
                isBatch = false,
                degradationNotice = null,
            ),
        )

        viewModel.savePendingShare()
        viewModel.savePendingShare()
        advanceUntilIdle()

        assertEquals(listOf(url), operations.saveCalls)
        assertFalse(viewModel.uiState.value.hasPendingShare)
    }

    private class FakeShareReceiverOperations : ShareReceiverOperations {
        private val saveResults = mutableMapOf<String, MutableList<SaveResult>>()
        private val assignResults = mutableMapOf<Pair<Long, Long>, MutableList<AssignTagResult>>()
        val saveCalls = mutableListOf<String>()
        val assignCalls = mutableListOf<Pair<Long, Long>>()
        val createTagCalls = mutableListOf<String>()
        var createTagResult: CompletableDeferred<CreateTagResult>? = null

        fun enqueueSave(url: String, result: SaveResult) {
            saveResults.getOrPut(url) { mutableListOf() } += result
        }

        fun enqueueAssign(entryId: Long, tagId: Long, result: AssignTagResult) {
            assignResults.getOrPut(entryId to tagId) { mutableListOf() } += result
        }

        override suspend fun saveURL(url: String, memo: String?): SaveResult {
            saveCalls += url
            return saveResults[url]?.removeFirstOrNull() ?: SaveResult(ShareSaveResult.SAVE_FAILED)
        }

        override suspend fun assignTag(entryId: Long, tagId: Long): AssignTagResult {
            assignCalls += entryId to tagId
            return assignResults[entryId to tagId]?.removeFirstOrNull() ?: AssignTagResult.Failed
        }

        override suspend fun createLocalTag(name: String): CreateTagResult {
            createTagCalls += name
            return createTagResult?.await() ?: CreateTagResult.Failed
        }

        override suspend fun findLocalTagIdByName(name: String): Long? = null
    }
}
