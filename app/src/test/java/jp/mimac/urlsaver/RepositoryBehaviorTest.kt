package jp.mimac.urlsaver

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import jp.mimac.urlsaver.data.AppDatabase
import jp.mimac.urlsaver.data.DefaultUrlRepository
import jp.mimac.urlsaver.data.DefaultUsageSummaryDataSource
import jp.mimac.urlsaver.data.MetadataScheduler
import jp.mimac.urlsaver.data.MetadataUpdate
import jp.mimac.urlsaver.data.UrlEntryEntity
import jp.mimac.urlsaver.domain.ContentContext
import jp.mimac.urlsaver.domain.MetadataError
import jp.mimac.urlsaver.domain.MetadataState
import jp.mimac.urlsaver.domain.RecordState
import jp.mimac.urlsaver.domain.ServiceType
import jp.mimac.urlsaver.domain.ShareSaveResult
import jp.mimac.urlsaver.util.AppClock
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RepositoryBehaviorTest {

    private lateinit var db: AppDatabase
    private lateinit var repository: DefaultUrlRepository
    private val scheduler = FakeScheduler()
    private val clock = FakeClock(1_000L)

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        repository = DefaultUrlRepository(
            database = db,
            dao = db.urlEntryDao(),
            collectionDao = db.collectionDao(),
            userLabelDao = db.userLabelDao(),
            clock = clock,
            scheduler = scheduler,
            usageSummaryDataSource = DefaultUsageSummaryDataSource(
                urlEntryDao = db.urlEntryDao(),
                tagDao = db.tagDao(),
                authSessionProvider = FakeAuthSessionProvider(),
            ),
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun createCollection_andSaveIntoSelectedCollection() = runBlocking {
        clock.now = 4_000L
        val createdCollection = repository.createCollection("work")
        assertTrue(createdCollection.success)
        val targetCollectionId = createdCollection.collectionId
        assertTrue(targetCollectionId != null && targetCollectionId > 0L)

        val created = repository.saveFromManualInput("https://example.com/in-work", targetCollectionId)
        assertEquals(ShareSaveResult.CREATED, created.result)

        val saved = db.urlEntryDao().findById(created.entryId!!)!!
        assertEquals(targetCollectionId, saved.collectionId)
    }

    @Test
    fun duplicateActive_withTargetCollection_movesEntryToRequestedCollection() = runBlocking {
        val c1 = repository.createCollection("later")
        val c2 = repository.createCollection("today")
        val c1Id = c1.collectionId!!
        val c2Id = c2.collectionId!!

        val first = repository.saveFromManualInput("https://example.com/move-me", c1Id)
        assertEquals(ShareSaveResult.CREATED, first.result)

        val duplicate = repository.saveFromManualInput("https://example.com/move-me", c2Id)
        assertEquals(ShareSaveResult.DUPLICATE_ACTIVE, duplicate.result)
        assertEquals(first.entryId, duplicate.entryId)

        val updated = db.urlEntryDao().findById(first.entryId!!)!!
        assertEquals(c2Id, updated.collectionId)
    }

    @Test
    fun metadataUpdate_doesNotChangeUpdatedAt() = runBlocking {
        val id = db.urlEntryDao().insert(
            UrlEntryEntity(
                originalUrl = "https://example.com/raw",
                normalizedUrl = "https://example.com",
                displayUrl = "example.com",
                openUrl = "https://example.com",
                normalizedHost = "example.com",
                rawSourceHost = "example.com",
                serviceType = ServiceType.WEB,
                contentContext = ContentContext.STANDARD,
                metadataState = MetadataState.PENDING,
                recordState = RecordState.ACTIVE,
                createdAt = 100L,
                updatedAt = 100L,
            ),
        )

        repository.applyMetadataUpdate(
            id,
            MetadataUpdate(
                fetchedTitle = "title",
                fetchedBody = null,
                bodySummary = null,
                description = null,
                thumbnailUrl = null,
                metadataState = MetadataState.READY,
                metadataFetchedAt = 500L,
                metadataError = null,
                canonicalId = null,
                normalizedHost = null,
                rawSourceHost = null,
            ),
        )

        val updated = db.urlEntryDao().findById(id)!!
        assertEquals(100L, updated.updatedAt)
        assertEquals(500L, updated.metadataFetchedAt)
        assertEquals("title", updated.fetchedTitle)
    }

    @Test
    fun create_setsMetadataRequestedAt() = runBlocking {
        clock.now = 4_000L
        val created = repository.saveFromManualInput("https://example.com/requested-at")
        assertEquals(ShareSaveResult.CREATED, created.result)

        val saved = db.urlEntryDao().findById(created.entryId!!)!!
        assertEquals(4_000L, saved.metadataRequestedAt)
    }

    @Test
    fun memoAndTitleUpdate_changeUpdatedAt_andNormalizeEmpty() = runBlocking {
        val id = db.urlEntryDao().insert(
            UrlEntryEntity(
                originalUrl = "https://example.com/raw",
                normalizedUrl = "https://example.com/m",
                displayUrl = "example.com/m",
                openUrl = "https://example.com/m",
                normalizedHost = "example.com",
                rawSourceHost = "example.com",
                serviceType = ServiceType.WEB,
                contentContext = ContentContext.STANDARD,
                metadataState = MetadataState.PENDING,
                recordState = RecordState.ACTIVE,
                createdAt = 100L,
                updatedAt = 100L,
            ),
        )

        clock.now = 2_000L
        repository.saveMemo(id, "   ")

        clock.now = 3_000L
        repository.saveUserTitle(id, "   ")

        val updated = db.urlEntryDao().findById(id)!!
        assertEquals("", updated.memo)
        assertEquals(null, updated.userTitle)
        assertEquals(3_000L, updated.updatedAt)
    }

    @Test
    fun saveMemoAndTitle_tooLongDoesNotPersist() = runBlocking {
        val id = db.urlEntryDao().insert(
            UrlEntryEntity(
                originalUrl = "https://example.com/raw",
                normalizedUrl = "https://example.com/too-long",
                displayUrl = "example.com/too-long",
                openUrl = "https://example.com/too-long",
                normalizedHost = "example.com",
                rawSourceHost = "example.com",
                serviceType = ServiceType.WEB,
                contentContext = ContentContext.STANDARD,
                memo = "keep",
                metadataState = MetadataState.PENDING,
                recordState = RecordState.ACTIVE,
                createdAt = 100L,
                updatedAt = 100L,
            ),
        )

        clock.now = 2_000L
        val memoResult = repository.saveMemo(id, "a".repeat(2001))
        val titleResult = repository.saveUserTitle(id, "a".repeat(121))

        val after = db.urlEntryDao().findById(id)!!
        assertFalse(memoResult.success)
        assertTrue(memoResult.tooLong)
        assertFalse(titleResult.success)
        assertTrue(titleResult.tooLong)
        assertEquals("keep", after.memo)
        assertEquals(null, after.userTitle)
        assertEquals(100L, after.updatedAt)
    }

    @Test
    fun restoreFromPending_reenqueueRulesMatchPhase1a() = runBlocking {
        suspend fun insertPending(
            normalizedUrl: String,
            metadataState: MetadataState,
            metadataFetchedAt: Long? = null,
        ): Long {
            return db.urlEntryDao().insert(
                UrlEntryEntity(
                    originalUrl = "$normalizedUrl/raw",
                    normalizedUrl = normalizedUrl,
                    displayUrl = normalizedUrl.removePrefix("https://"),
                    openUrl = normalizedUrl,
                    normalizedHost = "example.com",
                    rawSourceHost = "example.com",
                    serviceType = ServiceType.WEB,
                    contentContext = ContentContext.STANDARD,
                    metadataState = metadataState,
                    metadataFetchedAt = metadataFetchedAt,
                    recordState = RecordState.PENDING_DELETE,
                    createdAt = 100L,
                    updatedAt = 100L,
                    pendingDeletionUntil = 9_999_999L,
                ),
            )
        }

        scheduler.enqueued.clear()
        insertPending("https://example.com/ready", MetadataState.READY, metadataFetchedAt = 500L)
        val ready = repository.saveFromManualInput("https://example.com/ready")
        assertEquals(ShareSaveResult.RESTORED_FROM_PENDING_DELETE, ready.result)
        assertTrue(scheduler.enqueued.isEmpty())

        scheduler.enqueued.clear()
        val failedId = insertPending("https://example.com/failed", MetadataState.FAILED, metadataFetchedAt = 500L)
        val failed = repository.saveFromManualInput("https://example.com/failed")
        assertEquals(ShareSaveResult.RESTORED_FROM_PENDING_DELETE, failed.result)
        assertEquals(listOf(failedId), scheduler.enqueued)

        scheduler.enqueued.clear()
        val pendingId = insertPending("https://example.com/pending", MetadataState.PENDING, metadataFetchedAt = null)
        val pending = repository.saveFromManualInput("https://example.com/pending")
        assertEquals(ShareSaveResult.RESTORED_FROM_PENDING_DELETE, pending.result)
        assertEquals(listOf(pendingId), scheduler.enqueued)

        scheduler.enqueued.clear()
        insertPending("https://example.com/pending-inflight", MetadataState.PENDING, metadataFetchedAt = 700L)
        val pendingInflight = repository.saveFromManualInput("https://example.com/pending-inflight")
        assertEquals(ShareSaveResult.RESTORED_FROM_PENDING_DELETE, pendingInflight.result)
        assertTrue(scheduler.enqueued.isEmpty())

        scheduler.enqueued.clear()
        insertPending("https://example.com/unavailable", MetadataState.UNAVAILABLE, metadataFetchedAt = 500L)
        val unavailable = repository.saveFromManualInput("https://example.com/unavailable")
        assertEquals(ShareSaveResult.RESTORED_FROM_PENDING_DELETE, unavailable.result)
        assertTrue(scheduler.enqueued.isEmpty())
    }

    @Test
    fun retryMetadata_acceptsFailedUnavailableAndReadyWithoutFetchedContent() = runBlocking {
        suspend fun insertActive(
            normalizedUrl: String,
            metadataState: MetadataState,
            metadataError: MetadataError? = null,
            bodySummary: String? = null,
            fetchedBody: String? = null,
            serviceType: ServiceType = ServiceType.WEB,
            badgeImageUrl: String? = null,
        ): Long {
            return db.urlEntryDao().insert(
                UrlEntryEntity(
                    originalUrl = "$normalizedUrl/raw",
                    normalizedUrl = normalizedUrl,
                    displayUrl = normalizedUrl.removePrefix("https://"),
                    openUrl = normalizedUrl,
                    normalizedHost = "example.com",
                    rawSourceHost = "example.com",
                    serviceType = serviceType,
                    contentContext = ContentContext.STANDARD,
                    metadataState = metadataState,
                    metadataError = metadataError,
                    bodySummary = bodySummary,
                    fetchedBody = fetchedBody,
                    badgeImageUrl = badgeImageUrl,
                    recordState = RecordState.ACTIVE,
                    createdAt = 100L,
                    updatedAt = 100L,
                ),
            )
        }

        scheduler.enqueued.clear()
        val failedId = insertActive(
            normalizedUrl = "https://example.com/retry-failed",
            metadataState = MetadataState.FAILED,
            metadataError = MetadataError.TIMEOUT,
        )
        clock.now = 9_000L
        assertTrue(repository.retryMetadata(failedId))
        val failedAfter = db.urlEntryDao().findById(failedId)!!
        assertEquals(MetadataState.PENDING, failedAfter.metadataState)
        assertEquals(null, failedAfter.metadataError)
        assertEquals(9_000L, failedAfter.metadataRequestedAt)
        assertEquals(listOf(failedId), scheduler.enqueued)

        scheduler.enqueued.clear()
        val unavailableId = insertActive(
            normalizedUrl = "https://example.com/retry-unavailable",
            metadataState = MetadataState.UNAVAILABLE,
            metadataError = MetadataError.NON_HTML,
        )
        clock.now = 9_500L
        assertTrue(repository.retryMetadata(unavailableId))
        val unavailableAfter = db.urlEntryDao().findById(unavailableId)!!
        assertEquals(MetadataState.PENDING, unavailableAfter.metadataState)
        assertEquals(null, unavailableAfter.metadataError)
        assertEquals(9_500L, unavailableAfter.metadataRequestedAt)
        assertEquals(listOf(unavailableId), scheduler.enqueued)

        scheduler.enqueued.clear()
        val readyWithoutContentId = insertActive(
            normalizedUrl = "https://example.com/retry-ready",
            metadataState = MetadataState.READY,
        )
        clock.now = 9_700L
        assertTrue(repository.retryMetadata(readyWithoutContentId))
        val readyWithoutContentAfter = db.urlEntryDao().findById(readyWithoutContentId)!!
        assertEquals(MetadataState.PENDING, readyWithoutContentAfter.metadataState)
        assertEquals(9_700L, readyWithoutContentAfter.metadataRequestedAt)
        assertEquals(listOf(readyWithoutContentId), scheduler.enqueued)

        scheduler.enqueued.clear()
        val readyWithContentId = insertActive(
            normalizedUrl = "https://example.com/retry-ready-with-content",
            metadataState = MetadataState.READY,
            bodySummary = "already summarized",
            fetchedBody = "already fetched body",
        )
        assertFalse(repository.retryMetadata(readyWithContentId))
        assertTrue(scheduler.enqueued.isEmpty())

        scheduler.enqueued.clear()
        val readyWithContentButMissingXBadgeId = insertActive(
            normalizedUrl = "https://x.com/openai/status/123",
            metadataState = MetadataState.READY,
            serviceType = ServiceType.X,
            bodySummary = "summary exists",
            fetchedBody = "body exists",
            badgeImageUrl = null,
        )
        clock.now = 9_900L
        assertTrue(repository.retryMetadata(readyWithContentButMissingXBadgeId))
        val readyWithContentButMissingXBadgeAfter = db.urlEntryDao().findById(readyWithContentButMissingXBadgeId)!!
        assertEquals(MetadataState.PENDING, readyWithContentButMissingXBadgeAfter.metadataState)
        assertEquals(9_900L, readyWithContentButMissingXBadgeAfter.metadataRequestedAt)
        assertEquals(listOf(readyWithContentButMissingXBadgeId), scheduler.enqueued)

        scheduler.enqueued.clear()
        val pendingId = insertActive(
            normalizedUrl = "https://example.com/retry-pending",
            metadataState = MetadataState.PENDING,
        )
        assertFalse(repository.retryMetadata(pendingId))
        assertTrue(scheduler.enqueued.isEmpty())
    }

    @Test
    fun saveFromManualInput_httpsDetectsLegacyHttpDuplicate() = runBlocking {
        val existingId = db.urlEntryDao().insert(
            UrlEntryEntity(
                originalUrl = "http://example.com/legacy",
                normalizedUrl = "http://example.com/legacy",
                displayUrl = "example.com/legacy",
                openUrl = "http://example.com/legacy",
                normalizedHost = "example.com",
                rawSourceHost = "example.com",
                serviceType = ServiceType.WEB,
                contentContext = ContentContext.STANDARD,
                metadataState = MetadataState.UNAVAILABLE,
                metadataError = MetadataError.UNSUPPORTED_SCHEME,
                recordState = RecordState.ACTIVE,
                createdAt = 100L,
                updatedAt = 100L,
            ),
        )

        val result = repository.saveFromManualInput("https://example.com/legacy")
        assertEquals(ShareSaveResult.DUPLICATE_ACTIVE, result.result)
        assertEquals(existingId, result.entryId)
        assertEquals("http://example.com/legacy", result.normalizedUrl)
    }

    @Test
    fun schedulerFailure_marksCreatedEntryMetadataUnavailable() = runBlocking {
        scheduler.failOnEnqueue = true
        clock.now = 7_000L

        val result = repository.saveFromManualInput("https://example.com/scheduler-down")
        assertEquals(ShareSaveResult.CREATED, result.result)

        val saved = db.urlEntryDao().findById(result.entryId!!)!!
        assertEquals(MetadataState.UNAVAILABLE, saved.metadataState)
        assertEquals(MetadataError.SCHEDULER_UNAVAILABLE, saved.metadataError)
        assertEquals(7_000L, saved.metadataFetchedAt)
    }

    @Test
    fun retryMetadata_schedulerFailure_doesNotLeavePending() = runBlocking {
        val id = db.urlEntryDao().insert(
            UrlEntryEntity(
                originalUrl = "https://example.com/raw",
                normalizedUrl = "https://example.com/retry-fail",
                displayUrl = "example.com/retry-fail",
                openUrl = "https://example.com/retry-fail",
                normalizedHost = "example.com",
                rawSourceHost = "example.com",
                serviceType = ServiceType.WEB,
                contentContext = ContentContext.STANDARD,
                metadataState = MetadataState.FAILED,
                metadataError = MetadataError.TIMEOUT,
                recordState = RecordState.ACTIVE,
                createdAt = 100L,
                updatedAt = 100L,
            ),
        )
        scheduler.failOnEnqueue = true
        clock.now = 8_000L

        assertFalse(repository.retryMetadata(id))
        val updated = db.urlEntryDao().findById(id)!!
        assertEquals(MetadataState.UNAVAILABLE, updated.metadataState)
        assertEquals(MetadataError.SCHEDULER_UNAVAILABLE, updated.metadataError)
        assertEquals(8_000L, updated.metadataFetchedAt)
    }

    @Test
    fun saveFromManualInput_blocksAtLaunchStandardPersonalUrlLimit() = runBlocking {
        repeat(200) { index ->
            val result = repository.saveFromManualInput("https://example.com/limit-$index")
            assertEquals(ShareSaveResult.CREATED, result.result)
        }

        val blocked = repository.saveFromManualInput("https://example.com/limit-over")
        assertEquals(ShareSaveResult.PERSONAL_URL_LIMIT_REACHED, blocked.result)
    }

    private class FakeScheduler : MetadataScheduler {
        val enqueued = mutableListOf<Long>()
        var failOnEnqueue: Boolean = false

        override fun enqueueMetadata(entryId: Long) {
            if (failOnEnqueue) {
                throw IllegalStateException("scheduler unavailable")
            }
            enqueued += entryId
        }
    }

    private class FakeClock(now: Long) : AppClock {
        var now: Long = now
        override fun nowEpochMillis(): Long = now
    }

    private class FakeAuthSessionProvider : jp.mimac.urlsaver.data.SharedTagAuthSessionProvider {
        override val session = kotlinx.coroutines.flow.MutableStateFlow<jp.mimac.urlsaver.data.SharedTagAuthSession?>(null)
        override fun updateSession(newSession: jp.mimac.urlsaver.data.SharedTagAuthSession?) {
            session.value = newSession
        }
    }
}
