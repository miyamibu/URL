package jp.mimac.urlsaver

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import jp.mimac.urlsaver.data.MetadataWorkScheduler
import jp.mimac.urlsaver.domain.MetadataError
import jp.mimac.urlsaver.domain.MetadataState
import jp.mimac.urlsaver.worker.FetchMetadataWorker
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class FetchMetadataWorkerTest {

    @Test
    fun retryableFailure_retriesThenStopsAtThirdAttempt() {
        withServer { server ->
            server.enqueue(MockResponse().setResponseCode(503))
            server.enqueue(MockResponse().setResponseCode(503))

            val context = ApplicationProvider.getApplicationContext<Context>()
            val repository = (context as UrlSaverApp).container.repository
            val url = server.url("/err").toString()
            val entryId = runBlocking { repository.saveFromManualInput(url).entryId!! }

            val first = buildWorker(context, entryId, runAttemptCount = 0)
            val firstResult = runBlocking { first.doWork() }
            assertEquals(ListenableWorker.Result.retry(), firstResult)

            val third = buildWorker(context, entryId, runAttemptCount = 2)
            val thirdResult = runBlocking { third.doWork() }
            assertEquals(ListenableWorker.Result.success(), thirdResult)

            val updated = runBlocking { repository.loadEntry(entryId) }!!
            assertEquals(MetadataState.FAILED, updated.metadataState)
            assertEquals(MetadataError.HTTP_5XX, updated.metadataError)
            assertTrue(updated.metadataFetchedAt != null)
        }
    }

    @Test
    fun parseFailed_isUnavailableAndDoesNotRetry() {
        withServer { server ->
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .addHeader("Content-Type", "text/html")
                    .setBody("<html><body>no parseable metadata</body></html>"),
            )

            val context = ApplicationProvider.getApplicationContext<Context>()
            val repository = (context as UrlSaverApp).container.repository
            val url = server.url("/parse").toString()
            val entryId = runBlocking { repository.saveFromManualInput(url).entryId!! }

            val worker = buildWorker(context, entryId, runAttemptCount = 0)
            val result = runBlocking { worker.doWork() }
            assertEquals(ListenableWorker.Result.success(), result)

            val updated = runBlocking { repository.loadEntry(entryId) }!!
            assertEquals(MetadataState.UNAVAILABLE, updated.metadataState)
            assertEquals(MetadataError.PARSE_FAILED, updated.metadataError)
        }
    }

    private fun buildWorker(
        context: Context,
        entryId: Long,
        runAttemptCount: Int,
    ): FetchMetadataWorker {
        return TestListenableWorkerBuilder<FetchMetadataWorker>(context)
            .setInputData(workDataOf(MetadataWorkScheduler.KEY_ENTRY_ID to entryId))
            .setRunAttemptCount(runAttemptCount)
            .build()
    }

    private fun withServer(block: (MockWebServer) -> Unit) {
        val server = MockWebServer()
        server.start()
        try {
            block(server)
        } finally {
            server.shutdown()
        }
    }
}
