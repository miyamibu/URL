package jp.mimac.urlsaver

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import jp.mimac.urlsaver.data.MetadataWorkScheduler
import jp.mimac.urlsaver.data.MetadataUpdate
import jp.mimac.urlsaver.domain.MetadataError
import jp.mimac.urlsaver.domain.MetadataState
import jp.mimac.urlsaver.worker.FetchOutcome
import jp.mimac.urlsaver.worker.FetchMetadataWorker
import jp.mimac.urlsaver.worker.resolveFetchOutcome
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
    fun resolveFetchOutcome_usesSecondCandidateWhenFirstUnavailable() {
        val calledUrls = mutableListOf<String>()
        val firstCandidate = "https://first-candidate.example/a"
        val secondCandidate = "https://second-candidate.example/b"
        val outcome = resolveFetchOutcome(
            fetchUrls = listOf(firstCandidate, secondCandidate),
            fetch = { url ->
                calledUrls += url
                if (url == firstCandidate) {
                    FetchOutcome.Unavailable(MetadataError.HTTP_4XX)
                } else {
                    FetchOutcome.Ready(
                        fetchedTitle = "title",
                        thumbnailUrl = null,
                        canonicalId = "123",
                        normalizedHost = "second-candidate.example",
                        rawSourceHost = "second-candidate.example",
                    )
                }
            },
        )

        assertEquals(
            listOf(firstCandidate, secondCandidate),
            calledUrls,
        )
        assertTrue(outcome is FetchOutcome.Ready)
    }

    @Test
    fun resolveFetchOutcome_prefersUnavailableWhenRetryableAndUnavailableMixed() {
        val firstCandidate = "first-candidate"
        val secondCandidate = "second-candidate"
        val outcome = resolveFetchOutcome(
            fetchUrls = listOf(firstCandidate, secondCandidate),
            fetch = { url ->
                if (url == firstCandidate) {
                    FetchOutcome.FailedRetryable(MetadataError.NETWORK_IO)
                } else {
                    FetchOutcome.Unavailable(MetadataError.HTTP_4XX)
                }
            },
        )

        assertEquals(FetchOutcome.Unavailable(MetadataError.HTTP_4XX), outcome)
    }

    @Test
    fun resolveFetchOutcome_returnsRetryableWhenAllCandidatesRetryable() {
        val firstCandidate = "first-candidate"
        val secondCandidate = "second-candidate"
        val outcome = resolveFetchOutcome(
            fetchUrls = listOf(firstCandidate, secondCandidate),
            fetch = { FetchOutcome.FailedRetryable(MetadataError.TIMEOUT) },
        )

        assertEquals(FetchOutcome.FailedRetryable(MetadataError.TIMEOUT), outcome)
    }

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

    @Test
    fun successfulFetch_savesDescription() {
        withServer { server ->
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .addHeader("Content-Type", "text/html; charset=utf-8")
                    .setBody(
                        """
                        <html>
                          <head>
                            <title>Example</title>
                            <meta property="og:title" content="Example title" />
                            <meta property="og:description" content="OG description wins." />
                            <meta name="twitter:description" content="Twitter description." />
                            <meta name="description" content="Meta description." />
                          </head>
                          <body><p>Body fallback.</p></body>
                        </html>
                        """.trimIndent(),
                    ),
            )

            val context = ApplicationProvider.getApplicationContext<Context>()
            val repository = (context as UrlSaverApp).container.repository
            val entryId = runBlocking { repository.saveFromManualInput(server.url("/desc").toString()).entryId!! }

            val worker = buildWorker(context, entryId, runAttemptCount = 0)
            val result = runBlocking { worker.doWork() }
            assertEquals(ListenableWorker.Result.success(), result)

            val updated = runBlocking { repository.loadEntry(entryId) }!!
            assertEquals("OG description wins.", updated.description)
        }
    }

    @Test
    fun retryableFailure_keepsExistingDescriptionOnFinalFailure() {
        withServer { server ->
            server.enqueue(MockResponse().setResponseCode(503))
            server.enqueue(MockResponse().setResponseCode(503))

            val context = ApplicationProvider.getApplicationContext<Context>()
            val repository = (context as UrlSaverApp).container.repository
            val entryId = runBlocking { repository.saveFromManualInput(server.url("/fail-desc").toString()).entryId!! }

            runBlocking {
                repository.applyMetadataUpdate(
                    entryId,
                    MetadataUpdate(
                        fetchedTitle = null,
                        fetchedBody = null,
                        bodySummary = null,
                        description = "Keep existing description",
                        thumbnailUrl = null,
                        metadataState = MetadataState.READY,
                        metadataFetchedAt = 1L,
                        metadataError = null,
                        canonicalId = null,
                        normalizedHost = null,
                        rawSourceHost = null,
                    ),
                )
            }

            val worker = buildWorker(context, entryId, runAttemptCount = 2)
            val result = runBlocking { worker.doWork() }
            assertEquals(ListenableWorker.Result.success(), result)

            val updated = runBlocking { repository.loadEntry(entryId) }!!
            assertEquals(MetadataState.FAILED, updated.metadataState)
            assertEquals("Keep existing description", updated.description)
        }
    }

    private fun buildWorker(
        context: Context,
        entryId: Long,
        runAttemptCount: Int,
    ): FetchMetadataWorker {
        return TestListenableWorkerBuilder<FetchMetadataWorker>(context)
            .setWorkerFactory((context as UrlSaverApp).container.workerFactory)
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
