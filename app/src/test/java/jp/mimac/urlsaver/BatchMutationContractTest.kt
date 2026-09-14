package jp.mimac.urlsaver

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BatchMutationContractTest {
    @Test
    fun batchFailuresRemainSelectedAndSuccessfulItemsAloneReachUndo() {
        val source = File("src/main/java/jp/mimac/urlsaver/ui/UrlSaverRoot.kt").readText()

        assertTrue(source.contains("val failedIds = requestedIds - archivedIds.toSet()"))
        assertTrue(source.contains("val failedIds = requestedIds - pendingDeletions.keys"))
        assertTrue(source.contains("selectedEntryIds = failedIds"))
        assertTrue(source.contains("onBatchArchiveEntries(archivedIds)"))
        assertTrue(source.contains("onBatchPendingDeleteEntries(pendingDeletions)"))
    }

    @Test
    fun userVisibleInviteClipboardUsesRinbamBrand() {
        val source = File("src/main/java/jp/mimac/urlsaver/ui/UrlSaverRoot.kt").readText()

        assertTrue(source.contains("りんばむ グループ招待"))
        assertFalse(source.contains("URL Saver group invite"))
    }
}
