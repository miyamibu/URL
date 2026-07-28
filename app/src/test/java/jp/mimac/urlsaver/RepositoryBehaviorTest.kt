package jp.mimac.urlsaver

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import jp.mimac.urlsaver.data.AppDatabase
import jp.mimac.urlsaver.data.DefaultUrlRepository
import jp.mimac.urlsaver.data.DefaultUsageSummaryDataSource
import jp.mimac.urlsaver.data.MetadataScheduler
import jp.mimac.urlsaver.data.MetadataUpdate
import jp.mimac.urlsaver.data.PendingDeleteMediaCleanup
import jp.mimac.urlsaver.data.PendingDeleteFinalizationResult
import jp.mimac.urlsaver.data.TagEntity
import jp.mimac.urlsaver.data.TagUrlCrossRef
import jp.mimac.urlsaver.data.UrlEntryEntity
import jp.mimac.urlsaver.data.VideoAssetEntity
import jp.mimac.urlsaver.domain.ContentContext
import jp.mimac.urlsaver.domain.MetadataError
import jp.mimac.urlsaver.domain.MetadataBodyKind
import jp.mimac.urlsaver.domain.MetadataState
import jp.mimac.urlsaver.domain.RecordState
import jp.mimac.urlsaver.domain.ServiceType
import jp.mimac.urlsaver.domain.ShareSaveResult
import jp.mimac.urlsaver.domain.SharedTagScope
import jp.mimac.urlsaver.domain.SharedTagSyncStatus
import jp.mimac.urlsaver.util.AppClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RepositoryBehaviorTest {

    private lateinit var db: AppDatabase
    private lateinit var repository: DefaultUrlRepository
    private lateinit var mediaCleanup: RecordingPendingDeleteMediaCleanup
    private val scheduler = FakeScheduler()
    private val clock = FakeClock(1_000L)

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        mediaCleanup = RecordingPendingDeleteMediaCleanup(db)

        repository = DefaultUrlRepository(
            database = db,
            dao = db.urlEntryDao(),
            tagDao = db.tagDao(),
            clock = clock,
            scheduler = scheduler,
            usageSummaryDataSource = DefaultUsageSummaryDataSource(
                urlEntryDao = db.urlEntryDao(),
                tagDao = db.tagDao(),
                authSessionProvider = FakeAuthSessionProvider(),
            ),
            pendingDeleteMediaCleanup = mediaCleanup,
            videoAssetDao = db.videoAssetDao(),
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun saveFromManualInput_uppercaseSchemeAndDefaultPortDeduplicates() = runBlocking {
        val first = repository.saveFromManualInput("HTTPS://Example.COM:443/manual-normalize/#frag")
        assertEquals(ShareSaveResult.CREATED, first.result)

        val duplicate = repository.saveFromManualInput("https://example.com/manual-normalize")
        assertEquals(ShareSaveResult.DUPLICATE_ACTIVE, duplicate.result)
        assertEquals(first.entryId, duplicate.entryId)

        val rows = db.urlEntryDao().loadAllEntries()
            .filter { it.normalizedUrl == "https://example.com/manual-normalize" }
        assertEquals(1, rows.size)
        assertEquals("https://example.com/manual-normalize", rows.single().normalizedUrl)
    }

    @Test
    fun markPendingDelete_acceptsArchivedEntry_andRestoreReturnsToArchive() = runBlocking {
        val created = repository.saveFromManualInput("https://example.com/archive-delete")
        val entryId = created.entryId!!

        clock.now = 2_000L
        assertTrue(repository.archive(entryId))
        val archivedAt = db.urlEntryDao().findById(entryId)!!.archivedAt
        assertEquals(2_000L, archivedAt)

        clock.now = 3_000L
        val pendingUntil = repository.markPendingDelete(entryId)
        assertEquals(8_000L, pendingUntil)
        val pending = db.urlEntryDao().findById(entryId)!!
        assertEquals(RecordState.PENDING_DELETE, pending.recordState)
        assertEquals(archivedAt, pending.archivedAt)

        clock.now = 4_000L
        assertTrue(repository.restore(entryId))
        val restored = db.urlEntryDao().findById(entryId)!!
        assertEquals(RecordState.ARCHIVED, restored.recordState)
        assertEquals(archivedAt, restored.archivedAt)
        assertEquals(null, restored.pendingDeletionUntil)
    }

    @Test
    fun finalizePendingDelete_localOnlyDue_deletesRow() = runBlocking {
        val entryId = createEntry("https://example.com/pending-local-only")

        val pendingUntil = repository.markPendingDelete(entryId, gracePeriodMillis = 5_000L)
        assertEquals(6_000L, pendingUntil)
        clock.now = 6_000L

        assertEquals(
            PendingDeleteFinalizationResult.Deleted,
            repository.finalizePendingDelete(entryId),
        )
        assertNull(db.urlEntryDao().findById(entryId))
        assertEquals(listOf(entryId), mediaCleanup.calls.map { it.entryId })
        assertEquals(listOf(false), mediaCleanup.entryWasPresentAtCleanup)
    }

    @Test
    fun finalizePendingDelete_localAndSharedDue_preservesSharedRowAndReleasesLocalProvenance() = runBlocking {
        val entryId = createEntry("https://example.com/pending-local-shared")
        clock.now = 2_000L
        assertTrue(repository.archive(entryId))
        addActiveLocalReference(entryId)
        addActiveSyncedReference(entryId)

        clock.now = 3_000L
        assertEquals(8_000L, repository.markPendingDelete(entryId))
        clock.now = 8_000L

        assertEquals(
            PendingDeleteFinalizationResult.PreservedShared(sharedReferenceCount = 1),
            repository.finalizePendingDelete(entryId),
        )

        val preserved = db.urlEntryDao().findById(entryId)!!
        assertEquals(0, preserved.localProvenanceCount)
        assertEquals(1, preserved.sharedReferenceCount)
        assertEquals(RecordState.ACTIVE, preserved.recordState)
        assertNull(preserved.pendingDeletionUntil)
        assertNull(preserved.archivedAt)
        assertTrue(db.tagDao().getActiveLocalCrossRefsForEntries(listOf(entryId)).isEmpty())
        assertTrue(mediaCleanup.calls.isEmpty())
    }

    @Test
    fun finalizePendingDelete_staleDoesNotDeleteLocalCrossRefs() = runBlocking {
        val entryId = createEntry("https://example.com/pending-stale-local-ref")
        addActiveLocalReference(entryId)
        assertEquals(6_000L, repository.markPendingDelete(entryId, gracePeriodMillis = 5_000L))

        clock.now = 1_001L
        assertEquals(
            PendingDeleteFinalizationResult.Stale,
            repository.finalizePendingDelete(entryId),
        )

        assertEquals(1, db.tagDao().getActiveLocalCrossRefsForEntries(listOf(entryId)).size)
        assertTrue(mediaCleanup.calls.isEmpty())
    }

    @Test
    fun finalizePendingDelete_sharedReferenceAddedDuringGrace_preservesDespiteStaleCache() = runBlocking {
        val entryId = createEntry("https://example.com/pending-shared-added")
        assertEquals(6_000L, repository.markPendingDelete(entryId, gracePeriodMillis = 5_000L))

        addActiveSyncedReference(entryId)
        assertEquals(0, db.urlEntryDao().findById(entryId)!!.sharedReferenceCount)
        clock.now = 6_000L

        assertEquals(
            PendingDeleteFinalizationResult.PreservedShared(sharedReferenceCount = 1),
            repository.finalizePendingDelete(entryId),
        )
        val preserved = db.urlEntryDao().findById(entryId)!!
        assertEquals(0, preserved.localProvenanceCount)
        assertEquals(RecordState.ACTIVE, preserved.recordState)
        assertNull(preserved.pendingDeletionUntil)
        assertTrue(mediaCleanup.calls.isEmpty())
    }

    @Test
    fun cleanupExpiredPendingDeletes_restoreBeforeCleanup_keepsRestoredRow() = runBlocking {
        val entryId = createEntry("https://example.com/pending-restore")
        assertEquals(6_000L, repository.markPendingDelete(entryId, gracePeriodMillis = 5_000L))

        clock.now = 5_000L
        assertTrue(repository.restore(entryId))
        clock.now = 6_000L
        repository.cleanupExpiredPendingDeletes()

        val restored = db.urlEntryDao().findById(entryId)!!
        assertEquals(1, restored.localProvenanceCount)
        assertEquals(RecordState.ACTIVE, restored.recordState)
        assertNull(restored.pendingDeletionUntil)
        assertEquals(PendingDeleteFinalizationResult.Stale, repository.finalizePendingDelete(entryId))
        assertTrue(mediaCleanup.calls.isEmpty())
    }

    @Test
    fun cleanupAndRestoreRace_hasOneConsistentWinner() = runBlocking {
        val entryId = createEntry("https://example.com/pending-race")
        assertEquals(6_000L, repository.markPendingDelete(entryId, gracePeriodMillis = 5_000L))
        clock.now = 6_000L

        val cleanup = async(Dispatchers.Default) {
            repository.cleanupExpiredPendingDeletes()
        }
        val restore = async(Dispatchers.Default) {
            repository.restore(entryId)
        }
        val restored = restore.await()
        cleanup.await()

        val finalRow = db.urlEntryDao().findById(entryId)
        if (restored) {
            assertNotNull(finalRow)
            assertEquals(RecordState.ACTIVE, finalRow!!.recordState)
            assertNull(finalRow.pendingDeletionUntil)
        } else {
            assertNull(finalRow)
        }
    }

    @Test
    fun cleanupExpiredPendingDeletes_rechecksSyncedCrossRefsWhenCacheIsStale() = runBlocking {
        val entryId = createEntry("https://example.com/pending-sync-race")
        assertEquals(6_000L, repository.markPendingDelete(entryId, gracePeriodMillis = 5_000L))

        // This is the committed local outcome of a sync refresh; the cache column is intentionally unchanged.
        addActiveSyncedReference(entryId)
        clock.now = 6_000L
        repository.cleanupExpiredPendingDeletes()

        val preserved = db.urlEntryDao().findById(entryId)!!
        assertEquals(0, preserved.localProvenanceCount)
        assertEquals(1, preserved.sharedReferenceCount)
        assertEquals(RecordState.ACTIVE, preserved.recordState)
        assertNull(preserved.pendingDeletionUntil)
        assertNull(preserved.archivedAt)
        assertTrue(mediaCleanup.calls.isEmpty())
    }

    @Test
    fun cleanupExpiredPendingDeletes_cleansOnlyDeletedEntriesAfterCommit() = runBlocking {
        val deletedEntryId = createEntry("https://example.com/pending-cleanup-deleted")
        val preservedEntryId = createEntry("https://example.com/pending-cleanup-preserved")
        addActiveSyncedReference(preservedEntryId)
        val assetId = db.videoAssetDao().insertAsset(videoAssetFor(deletedEntryId))

        assertEquals(6_000L, repository.markPendingDelete(deletedEntryId, gracePeriodMillis = 5_000L))
        assertEquals(6_000L, repository.markPendingDelete(preservedEntryId, gracePeriodMillis = 5_000L))
        clock.now = 6_000L

        repository.cleanupExpiredPendingDeletes()

        assertNull(db.urlEntryDao().findById(deletedEntryId))
        assertNotNull(db.urlEntryDao().findById(preservedEntryId))
        assertEquals(listOf(deletedEntryId), mediaCleanup.calls.map { it.entryId })
        assertEquals(listOf(assetId), mediaCleanup.calls.single().downloadAssetIds)
        assertEquals(listOf(false), mediaCleanup.entryWasPresentAtCleanup)
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
    fun saveFromManualInput_urlWithTextStoresTextAsMemo() = runBlocking {
        val created = repository.saveFromManualInput(
            """
            あとで読む
            https://example.com/with-memo
            メモ本文
            """.trimIndent(),
        )

        assertEquals(ShareSaveResult.CREATED, created.result)
        val saved = db.urlEntryDao().findById(created.entryId!!)!!
        assertEquals("https://example.com/with-memo", saved.normalizedUrl)
        assertEquals("あとで読む\nメモ本文", saved.memo)
    }

    @Test
    fun saveFromManualInput_withoutUrlCreatesTextCard() = runBlocking {
        val body = """
            投稿メモのタイトル
            これはURLなしで保存する本文です。
            あとでカードとして読み返します。
        """.trimIndent()

        val created = repository.saveFromManualInput(body)
        assertEquals(ShareSaveResult.CREATED, created.result)

        val saved = db.urlEntryDao().findById(created.entryId!!)!!
        assertTrue(saved.normalizedUrl.startsWith("https://text.rinbam.local/note/"))
        assertEquals("テキスト", saved.displayUrl)
        assertEquals("投稿メモのタイトル", saved.fetchedTitle)
        assertEquals(body, saved.fetchedBody)
        assertEquals(MetadataBodyKind.WEB_EXCERPT, saved.fetchedBodyKind)
        assertEquals("投稿メモのタイトル", saved.bodySummary)
        assertEquals(MetadataState.READY, saved.metadataState)
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

    private suspend fun createEntry(normalizedUrl: String): Long {
        val result = repository.saveFromManualInput(normalizedUrl)
        assertEquals(ShareSaveResult.CREATED, result.result)
        return result.entryId!!
    }

    private suspend fun addActiveSyncedReference(entryId: Long, suffix: String = "primary") {
        val entry = db.urlEntryDao().findById(entryId)!!
        val authUserId = "shared-user-$entryId-$suffix"
        val tagId = db.tagDao().insertTag(
            TagEntity(
                name = "shared-tag-$entryId-$suffix",
                createdAt = clock.now,
                scope = SharedTagScope.SYNCED,
                authUserId = authUserId,
                remoteTagId = "remote-tag-$entryId-$suffix",
                syncStatus = SharedTagSyncStatus.SYNCED,
            ),
        )
        db.tagDao().insertCrossRef(
            TagUrlCrossRef(
                tagId = tagId,
                entryId = entryId,
                createdAt = clock.now,
                scope = SharedTagScope.SYNCED,
                authUserId = authUserId,
                remoteUrlId = "remote-url-$entryId-$suffix",
                normalizedUrl = entry.normalizedUrl,
                syncStatus = SharedTagSyncStatus.SYNCED,
            ),
        )
    }

    private suspend fun addActiveLocalReference(entryId: Long) {
        val tagId = db.tagDao().insertTag(
            TagEntity(
                name = "local-tag-$entryId",
                createdAt = clock.now,
            ),
        )
        db.tagDao().insertCrossRef(
            TagUrlCrossRef(
                tagId = tagId,
                entryId = entryId,
                createdAt = clock.now,
            ),
        )
    }

    private fun videoAssetFor(entryId: Long): VideoAssetEntity {
        return VideoAssetEntity(
            entryId = entryId,
            provider = "test",
            providerAssetId = "asset-$entryId",
            sourceUrl = "https://example.com/media/$entryId",
            canonicalPostUrl = null,
            authorName = null,
            title = null,
            bodyText = null,
            thumbnailUrl = null,
            durationMs = null,
            mediaType = "VIDEO",
            hasVideo = "YES",
            resolveStatus = "AVAILABLE",
            downloadUrl = "https://cdn.example.com/$entryId.mp4",
            requestHeadersJson = null,
            mimeType = "video/mp4",
            qualityLabel = null,
            width = null,
            height = null,
            bitrate = null,
            sortIndex = 0,
            isPreferred = true,
            checkedAt = clock.now,
            expiresAt = null,
            errorReason = null,
        )
    }

    private class RecordingPendingDeleteMediaCleanup(
        private val database: AppDatabase,
    ) : PendingDeleteMediaCleanup {
        data class Call(val entryId: Long, val downloadAssetIds: List<Long>)

        val calls = mutableListOf<Call>()
        val entryWasPresentAtCleanup = mutableListOf<Boolean>()

        override suspend fun cleanup(entryId: Long, downloadAssetIds: List<Long>) {
            calls += Call(entryId, downloadAssetIds)
            entryWasPresentAtCleanup += database.urlEntryDao().findById(entryId) != null
        }
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
