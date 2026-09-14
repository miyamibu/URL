package jp.mimac.urlsaver

import android.content.Intent
import android.net.Uri
import androidx.core.content.IntentCompat
import androidx.test.core.app.ApplicationProvider
import jp.mimac.urlsaver.data.PreparedExportArchive
import jp.mimac.urlsaver.ui.exportTodayDateInput
import jp.mimac.urlsaver.ui.ChatGptDirectShareOutcome
import jp.mimac.urlsaver.ui.CachedExportFileInfo
import jp.mimac.urlsaver.ui.MAX_CHATGPT_ARCHIVE_BYTES
import jp.mimac.urlsaver.ui.buildChatGptDirectShareIntent
import jp.mimac.urlsaver.ui.cacheExportArchive
import jp.mimac.urlsaver.ui.cachedExportFileNamesToPrune
import jp.mimac.urlsaver.ui.copyPreparedExportArchive
import jp.mimac.urlsaver.ui.isChatGptZipCreationEnabled
import jp.mimac.urlsaver.ui.shouldFallbackToChatGptChooser
import jp.mimac.urlsaver.ui.shouldShowSharedTagExportPreset
import jp.mimac.urlsaver.ui.writeCachedExportArchive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.io.ByteArrayOutputStream
import java.io.RandomAccessFile
import java.time.LocalDate
import kotlinx.coroutines.runBlocking

@RunWith(RobolectricTestRunner::class)
class ExportScreenTest {
    @Test
    fun exportTodayDateInput_formatsProvidedDateWithoutStaleFixtureDate() {
        assertEquals("2026-07-09", exportTodayDateInput(LocalDate.of(2026, 7, 9)))
    }

    @Test
    fun shouldShowSharedTagExportPreset_hidesPresetWhenCloudDisabled() {
        assertFalse(shouldShowSharedTagExportPreset(isSharedTagCloudEnabled = false))
    }

    @Test
    fun shouldShowSharedTagExportPreset_keepsPresetWhenCloudEnabled() {
        assertTrue(shouldShowSharedTagExportPreset(isSharedTagCloudEnabled = true))
    }

    @Test
    fun isChatGptZipCreationEnabled_requiresSelectionAndEligibleTarget() {
        assertFalse(
            isChatGptZipCreationEnabled(
                selectedTagCount = 0,
                targetCount = 1,
                isContentConfirmed = true,
                isPreviewLoading = false,
                isPreparingArchive = false,
            ),
        )
        assertFalse(
            isChatGptZipCreationEnabled(
                selectedTagCount = 1,
                targetCount = 0,
                isContentConfirmed = true,
                isPreviewLoading = false,
                isPreparingArchive = false,
            ),
        )
        assertTrue(
            isChatGptZipCreationEnabled(
                selectedTagCount = 2,
                targetCount = 3,
                isContentConfirmed = true,
                isPreviewLoading = false,
                isPreparingArchive = false,
            ),
        )
    }

    @Test
    fun isChatGptZipCreationEnabled_requiresExplicitContentConfirmation() {
        assertFalse(
            isChatGptZipCreationEnabled(
                selectedTagCount = 1,
                targetCount = 1,
                isContentConfirmed = false,
                isPreviewLoading = false,
                isPreparingArchive = false,
            ),
        )
    }

    @Test
    fun isChatGptZipCreationEnabled_blocksWhilePreviewOrArchiveIsBusy() {
        assertFalse(
            isChatGptZipCreationEnabled(
                selectedTagCount = 1,
                targetCount = 1,
                isContentConfirmed = true,
                isPreviewLoading = true,
                isPreparingArchive = false,
            ),
        )
        assertFalse(
            isChatGptZipCreationEnabled(
                selectedTagCount = 1,
                targetCount = 1,
                isContentConfirmed = true,
                isPreviewLoading = false,
                isPreparingArchive = true,
            ),
        )
    }

    @Test
    fun shouldFallbackToChatGptChooser_onlySkipsChooserAfterDirectStart() {
        assertFalse(shouldFallbackToChatGptChooser(ChatGptDirectShareOutcome.STARTED))
        assertTrue(shouldFallbackToChatGptChooser(ChatGptDirectShareOutcome.ACTIVITY_NOT_FOUND))
        assertTrue(shouldFallbackToChatGptChooser(ChatGptDirectShareOutcome.SECURITY_ERROR))
        assertTrue(shouldFallbackToChatGptChooser(ChatGptDirectShareOutcome.OTHER_ERROR))
    }

    @Test
    fun chatGptShareIntent_containsOnlyZipAttachmentAndReadGrant() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val archive = preparedArchive("rinbam-chatgpt-test.zip", byteArrayOf(1, 2, 3))
        val uri = Uri.parse("content://${context.packageName}.fileprovider/exports/test.zip")

        val intent = buildChatGptDirectShareIntent(context, archive, uri)

        assertEquals(Intent.ACTION_SEND, intent.action)
        assertEquals("application/zip", intent.type)
        assertEquals("com.openai.chatgpt", intent.`package`)
        assertEquals(uri, IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
        assertEquals("rinbam-chatgpt-test.zip", intent.getStringExtra(Intent.EXTRA_SUBJECT))
        assertFalse(intent.hasExtra(Intent.EXTRA_TEXT))
        assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertEquals(1, intent.clipData?.itemCount)
        assertEquals(uri, intent.clipData?.getItemAt(0)?.uri)
    }

    @Test
    fun writeCachedExportArchive_writesBytesOffUiContract() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val archive = preparedArchive("rinbam-chatgpt-test.zip", byteArrayOf(9, 8, 7))

        val targetFile = writeCachedExportArchive(context, archive)
        val copied = targetFile.readBytes()

        assertEquals(byteArrayOf(9, 8, 7).toList(), copied.toList())
        assertTrue(targetFile.absolutePath.contains("/cache/exports/"))
    }

    @Test
    fun preparedArchive_streamCopyPreservesContentAndRejectsByteCountDrift() = runBlocking {
        val archive = preparedArchive("rinbam-stream-copy.zip", byteArrayOf(4, 5, 6, 7))
        val output = ByteArrayOutputStream()

        copyPreparedExportArchive(archive, output)

        assertEquals(listOf(4, 5, 6, 7), output.toByteArray().map { it.toInt() })
        archive.file.appendBytes(byteArrayOf(8))
        assertTrue(runCatching { copyPreparedExportArchive(archive, ByteArrayOutputStream()) }.isFailure)
    }

    @Test
    fun cachePrune_neverRemovesCurrentFileBackedArchiveBeforeShare() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val exportRoot = File(context.cacheDir, "exports").apply { mkdirs() }
        val sourceDirectory = File(exportRoot, "current-source-${System.nanoTime()}").apply { mkdirs() }
        val sourceFile = File(sourceDirectory, "rinbam-current.zip").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val archive = PreparedExportArchive(
            fileName = sourceFile.name,
            file = sourceFile,
            byteCount = sourceFile.length(),
            entryCount = 1,
            mimeType = "application/zip",
        )
        val overflowDirectories = (0 until 70).map { index ->
            File(exportRoot, "overflow-${System.nanoTime()}-$index").apply {
                mkdirs()
                setLastModified(1L + index)
            }
        }

        val sharedFile = writeCachedExportArchive(context, archive)

        assertFalse(sourceFile.canonicalFile == sharedFile.canonicalFile)
        assertEquals(sourceFile.readBytes().toList(), sharedFile.readBytes().toList())
        assertTrue(sourceFile.isFile)
        sharedFile.parentFile?.deleteRecursively()
        overflowDirectories.forEach { it.deleteRecursively() }
        sourceDirectory.deleteRecursively()
        Unit
    }

    @Test
    fun exportScreenOwnsNormalCancellationFailureAndDisposeCleanupPaths() {
        val source = File("src/main/java/jp/mimac/urlsaver/ui/ExportScreen.kt").readText()

        assertTrue(source.contains("pendingFileArchive?.let(viewModel::releaseStandardPreparedArchive)"))
        assertTrue(source.contains("viewModel.releaseAllStandardPreparedArchives()"))
        assertTrue(source.contains("viewModel.releaseStandardPreparedArchive(archive)"))
        assertTrue(source.contains("viewModel.releasePreparedChatGptArchive(archive)"))
        assertTrue(source.contains("viewModel.clearPreparedChatGptArchive()"))
        assertTrue(source.contains("finally"))
    }

    @Test
    fun cacheExportArchive_rejectsOversizedChatGptArchive() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val oversizedFile = File.createTempFile("rinbam-chatgpt-too-large", ".zip").apply {
            RandomAccessFile(this, "rw").use { output ->
                output.setLength((MAX_CHATGPT_ARCHIVE_BYTES + 1).toLong())
            }
            deleteOnExit()
        }
        val archive = PreparedExportArchive(
            fileName = "rinbam-chatgpt-too-large.zip",
            file = oversizedFile,
            byteCount = oversizedFile.length(),
            entryCount = 1,
            mimeType = "application/zip",
        )

        var failed = false
        try {
            cacheExportArchive(context, archive)
        } catch (error: IllegalArgumentException) {
            failed = error.message?.contains("25 MiB") == true
        }
        assertTrue(failed)
    }

    private fun preparedArchive(fileName: String, bytes: ByteArray): PreparedExportArchive {
        val file = File.createTempFile("rinbam-export-screen", ".tmp").apply {
            writeBytes(bytes)
            deleteOnExit()
        }
        return PreparedExportArchive(
            fileName = fileName,
            file = file,
            byteCount = file.length(),
            entryCount = 1,
            mimeType = "application/zip",
        )
    }

    @Test
    fun cachedExportPruning_keepsRecentRecipientsAndRemovesOnlyExpiredOrOverflowFiles() {
        val files = listOf(
            CachedExportFileInfo(name = "expired.zip", lastModified = 1_000L),
            CachedExportFileInfo(name = "oldest-recent.zip", lastModified = 9_100L),
            CachedExportFileInfo(name = "recent.zip", lastModified = 9_500L),
            CachedExportFileInfo(name = "newest.zip", lastModified = 9_900L),
        )

        assertEquals(
            setOf("expired.zip", "oldest-recent.zip"),
            cachedExportFileNamesToPrune(
                files = files,
                nowEpochMillis = 10_000L,
                maxAgeMillis = 2_000L,
                maxRetainedFiles = 2,
            ),
        )
    }
}
