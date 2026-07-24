package jp.mimac.urlsaver

import java.io.IOException
import kotlinx.coroutines.CancellationException
import jp.mimac.urlsaver.video.isRetryableDownloadFailure
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoDownloadWorkerTest {
    @Test
    fun retryableDownloadFailures_areSeparatedFromPermanentFailuresAndCancellation() {
        assertTrue(isRetryableDownloadFailure(IOException("connection reset")))
        assertTrue(isRetryableDownloadFailure(IllegalStateException("HTTP_503")))
        assertTrue(isRetryableDownloadFailure(IllegalStateException("HTTP_429")))
        assertFalse(isRetryableDownloadFailure(IllegalStateException("HTTP_404")))
        assertFalse(isRetryableDownloadFailure(CancellationException("cancelled")))
    }
}
