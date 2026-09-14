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
import jp.mimac.urlsaver.domain.DefaultEntitlementResolver
import jp.mimac.urlsaver.domain.LaunchStandardPlan
import jp.mimac.urlsaver.domain.MetadataError
import jp.mimac.urlsaver.domain.MetadataBodyKind
import jp.mimac.urlsaver.domain.MetadataState
import jp.mimac.urlsaver.domain.RecordState
import jp.mimac.urlsaver.domain.ServiceType
import jp.mimac.urlsaver.domain.ShareSaveResult
import jp.mimac.urlsaver.util.AppClock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
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
            tagDao = db.tagDao(),
            clock = clock,
            scheduler = scheduler,
            usageSummaryDataSource = DefaultUsageSummaryDataSource(
                urlEntryDao = db.urlEntryDao(),
                tagDao = db.tagDao(),
                authSessionProvider = FakeAuthSessionProvider(),
                entitlementResolver = DefaultEntitlementResolver(
                    defaultEntitlements = LaunchStandardPlan.entitlements,
                ),
            ),
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
        assertEquals(86_403_000L, pendingUntil)
        val pending = db.urlEntryDao().findById(entryId)!!
        assertEquals(RecordState.PENDING_DELETE, pending.recordState)
        assertEquals(null, pending.pendingDeletionUntil)
        assertEquals(archivedAt, pending.archivedAt)

        clock.now = 4_000L
        assertTrue(repository.restore(entryId))
        val restored = db.urlEntryDao().findById(entryId)!!
        assertEquals(RecordState.ARCHIVED, restored.recordState)
        assertEquals(archivedAt, restored.archivedAt)
        assertEquals(null, restored.pendingDeletionUntil)
    }

    @Test
    fun markPendingDeleteEntries_usesOneDeadlineAndReportsRejectedIds() = runBlocking {
        val first = repository.saveFromManualInput("https://example.com/batch-delete-first").entryId!!
        val second = repository.saveFromManualInput("https://example.com/batch-delete-second").entryId!!
        val alreadyPending = repository.saveFromManualInput("https://example.com/batch-delete-pending").entryId!!
        repository.markPendingDelete(alreadyPending)

        clock.now = 20_000L
        val results = repository.markPendingDeleteEntries(
            listOf(first, second, alreadyPending, Long.MAX_VALUE),
        )

        assertEquals(setOf(first, second), results.keys)
        assertEquals(setOf(86_420_000L), results.values.toSet())
        assertEquals(null, db.urlEntryDao().findById(first)?.pendingDeletionUntil)
        assertEquals(null, db.urlEntryDao().findById(second)?.pendingDeletionUntil)
        assertEquals(RecordState.PENDING_DELETE, db.urlEntryDao().findById(alreadyPending)?.recordState)
    }

    @Test
    fun startPendingDeleteUndoWindow_resetsEveryPendingEntryToOneDisplayRelativeDeadline() = runBlocking {
        val first = repository.saveFromManualInput("https://example.com/undo-window-first").entryId!!
        val second = repository.saveFromManualInput("https://example.com/undo-window-second").entryId!!
        repository.markPendingDeleteEntries(listOf(first, second))

        clock.now = 40_000L
        val deadlines = repository.startPendingDeleteUndoWindow(listOf(first, second, Long.MAX_VALUE))

        assertEquals(mapOf(first to 45_000L, second to 45_000L), deadlines)
        assertEquals(45_000L, db.urlEntryDao().findById(first)?.pendingDeletionUntil)
        assertEquals(45_000L, db.urlEntryDao().findById(second)?.pendingDeletionUntil)
    }

    @Test
    fun provisionalPendingDeleteCannotFinalizeOrCleanupBeforeUndoIsDisplayed() = runBlocking {
        val entryId = repository.saveFromManualInput("https://example.com/provisional-delete").entryId!!
        repository.markPendingDelete(entryId, gracePeriodMillis = 5_000L)

        clock.now = 20_000L
        repository.finalizePendingDelete(entryId)
        repository.cleanupExpiredPendingDeletes()
        val provisional = db.urlEntryDao().findById(entryId)
        assertEquals(RecordState.PENDING_DELETE, provisional?.recordState)
        assertEquals(null, provisional?.pendingDeletionUntil)

        val deadline = repository.startPendingDeleteUndoWindow(listOf(entryId)).getValue(entryId)
        assertEquals(25_000L, deadline)
        clock.now = 24_999L
        repository.finalizePendingDelete(entryId)
        assertEquals(RecordState.PENDING_DELETE, db.urlEntryDao().findById(entryId)?.recordState)
        clock.now = 25_000L
        repository.finalizePendingDelete(entryId)
        assertEquals(null, db.urlEntryDao().findById(entryId))
    }

    @Test
    fun restoreAndFinalizeRace_isAtomicAndNeverDeletesASuccessfullyRestoredEntry() = runBlocking {
        repeat(100) { iteration ->
            clock.now = 100_000L + iteration
            val entryId = repository.saveFromManualInput(
                "https://example.com/restore-finalize-race-$iteration",
            ).entryId!!
            repository.markPendingDelete(entryId, gracePeriodMillis = 0)
            repository.startPendingDeleteUndoWindow(listOf(entryId), gracePeriodMillis = 0)

            val restored = coroutineScope {
                val start = CompletableDeferred<Unit>()
                val restore = async(Dispatchers.IO) {
                    start.await()
                    repository.restore(entryId)
                }
                val finalize = async(Dispatchers.IO) {
                    start.await()
                    repository.finalizePendingDelete(entryId)
                }
                start.complete(Unit)
                val didRestore = restore.await()
                finalize.await()
                didRestore
            }

            val persisted = db.urlEntryDao().findById(entryId)
            assertEquals(
                "restore=true must linearize before conditional finalize (iteration=$iteration)",
                restored,
                persisted != null,
            )
            if (restored) {
                assertEquals(RecordState.ACTIVE, persisted?.recordState)
                assertEquals(null, persisted?.pendingDeletionUntil)
            }
        }
    }

    @Test
    fun startupRestoresOnlyProvisionalPendingDeleteAndKeepsActiveUndoDeadline() = runBlocking {
        val provisional = repository.saveFromManualInput("https://example.com/provisional-restart").entryId!!
        val activeUndo = repository.saveFromManualInput("https://example.com/active-undo-restart").entryId!!
        repository.markPendingDeleteEntries(listOf(provisional, activeUndo))
        clock.now = 50_000L
        repository.startPendingDeleteUndoWindow(listOf(activeUndo))

        repository.restoreProvisionalPendingDeletes()

        assertEquals(RecordState.ACTIVE, db.urlEntryDao().findById(provisional)?.recordState)
        assertEquals(null, db.urlEntryDao().findById(provisional)?.pendingDeletionUntil)
        assertEquals(RecordState.PENDING_DELETE, db.urlEntryDao().findById(activeUndo)?.recordState)
        assertEquals(55_000L, db.urlEntryDao().findById(activeUndo)?.pendingDeletionUntil)
    }

    @Test
    fun batchArchiveAndPendingDelete_tenThousandEntriesCompleteWithinFiveSecondsEach() = runBlocking {
        db.openHelper.writableDatabase.execSQL(
            """
            WITH RECURSIVE sequence(value) AS (
                SELECT 1
                UNION ALL
                SELECT value + 1 FROM sequence WHERE value < 10000
            )
            INSERT INTO url_entries (
                originalUrl, normalizedUrl, displayUrl, openUrl,
                normalizedHost, rawSourceHost, collectionId, serviceType, contentContext,
                memo, localProvenanceCount, sharedReferenceCount,
                metadataState, recordState, createdAt, updatedAt
            )
            SELECT
                'https://batch.example/' || value,
                'https://batch.example/' || value,
                'batch.example/' || value,
                'https://batch.example/' || value,
                'batch.example', 'batch.example', 1, 'WEB', 'STANDARD',
                '', 1, 0, 'READY', 'ACTIVE', value, value
            FROM sequence
            """.trimIndent(),
        )
        val entryIds = db.urlEntryDao().loadAllEntries().map { it.id }
        assertEquals(10_000, entryIds.size)

        val archiveStartedAt = System.nanoTime()
        val archivedIds = repository.archiveEntries(entryIds)
        val archiveElapsedMillis = (System.nanoTime() - archiveStartedAt) / 1_000_000

        assertEquals(10_000, archivedIds.size)
        assertTrue(
            "1万件のアーカイブが5秒を超えました: ${archiveElapsedMillis}ms",
            archiveElapsedMillis < 5_000,
        )

        db.openHelper.writableDatabase.execSQL(
            """
            UPDATE url_entries
            SET recordState = 'ACTIVE', archivedAt = NULL,
                pendingDeletionUntil = NULL, updatedAt = createdAt
            """.trimIndent(),
        )
        clock.now = 30_000L

        val pendingStartedAt = System.nanoTime()
        val pendingDeletions = repository.markPendingDeleteEntries(entryIds)
        val pendingElapsedMillis = (System.nanoTime() - pendingStartedAt) / 1_000_000

        assertEquals(10_000, pendingDeletions.size)
        assertEquals(setOf(86_430_000L), pendingDeletions.values.toSet())
        assertTrue(
            "1万件の削除待ち設定が5秒を超えました: ${pendingElapsedMillis}ms",
            pendingElapsedMillis < 5_000,
        )
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
    fun listProjection_boundsBody_butDetailAndDatabaseSearchKeepFullBody() = runBlocking {
        val id = db.urlEntryDao().insert(
            UrlEntryEntity(
                originalUrl = "https://example.com/large-list-row",
                normalizedUrl = "https://example.com/large-list-row",
                displayUrl = "example.com/large-list-row",
                openUrl = "https://example.com/large-list-row",
                normalizedHost = "example.com",
                rawSourceHost = "example.com",
                serviceType = ServiceType.WEB,
                contentContext = ContentContext.STANDARD,
                fetchedBody = "prefix-${"x".repeat(700)}-deep-search-marker",
                bodySummary = "summary",
                memo = "memo",
                metadataState = MetadataState.READY,
                recordState = RecordState.ACTIVE,
                createdAt = 100L,
                updatedAt = 100L,
            ),
        )

        val listEntry = repository.observeActiveEntries().first().single { it.id == id }
        assertEquals(512, listEntry.fetchedBody?.length)
        assertEquals("memo", listEntry.memo)

        val detailEntry = repository.loadEntry(id)!!
        assertEquals("prefix-${"x".repeat(700)}-deep-search-marker", detailEntry.fetchedBody)
        assertTrue(repository.searchEntryIds("deep-search-marker", RecordState.ACTIVE).contains(id))
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
