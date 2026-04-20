package jp.mimac.urlsaver

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import jp.mimac.urlsaver.data.AppDatabase
import jp.mimac.urlsaver.data.DefaultTagRepository
import jp.mimac.urlsaver.data.MetadataScheduler
import jp.mimac.urlsaver.data.SharedTagAuthSession
import jp.mimac.urlsaver.data.SharedTagAuthSessionProvider
import jp.mimac.urlsaver.data.SharedTagSyncCoordinator
import jp.mimac.urlsaver.data.SharedTagSyncRemoteConfig
import jp.mimac.urlsaver.data.SharedTagSyncRemoteDataSource
import jp.mimac.urlsaver.data.TagEntity
import jp.mimac.urlsaver.data.TagUrlCrossRef
import jp.mimac.urlsaver.data.UrlEntryEntity
import jp.mimac.urlsaver.domain.ApplySharedTagOpsResponse
import jp.mimac.urlsaver.domain.ContentContext
import jp.mimac.urlsaver.domain.MetadataState
import jp.mimac.urlsaver.domain.PullSharedTagSnapshotResponse
import jp.mimac.urlsaver.domain.RecordState
import jp.mimac.urlsaver.domain.RemoteSharedTag
import jp.mimac.urlsaver.domain.RemoteSharedTagMember
import jp.mimac.urlsaver.domain.RemoteSharedTagUrl
import jp.mimac.urlsaver.domain.SharedTagScope
import jp.mimac.urlsaver.domain.SharedTagSyncOperation
import jp.mimac.urlsaver.domain.SharedTagSyncStatus
import jp.mimac.urlsaver.domain.ServiceType
import jp.mimac.urlsaver.util.AppClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SharedTagSyncRepositoryTest {

    private lateinit var db: AppDatabase
    private lateinit var repository: DefaultTagRepository
    private lateinit var authProvider: FakeAuthSessionProvider
    private lateinit var remote: FakeRemoteDataSource
    private lateinit var coordinator: SharedTagSyncCoordinator
    private val scheduler = FakeScheduler()
    private val clock = FakeClock(10_000L)

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        authProvider = FakeAuthSessionProvider()
        remote = FakeRemoteDataSource()
        coordinator = SharedTagSyncCoordinator(
            database = db,
            tagDao = db.tagDao(),
            syncDao = db.sharedTagSyncDao(),
            urlEntryDao = db.urlEntryDao(),
            authSessionProvider = authProvider,
            remoteDataSource = remote,
            clock = clock,
            metadataScheduler = scheduler,
        )
        repository = DefaultTagRepository(
            database = db,
            tagDao = db.tagDao(),
            syncDao = db.sharedTagSyncDao(),
            urlEntryDao = db.urlEntryDao(),
            clock = clock,
            metadataScheduler = scheduler,
            authSessionProvider = authProvider,
            syncScheduler = null,
            syncCoordinator = coordinator,
            remoteConfig = SharedTagSyncRemoteConfig("https://example.supabase.co", "anon"),
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun syncedCreateAndReconcile_roundTripsThroughOutboxAndSnapshot() = runBlocking {
        authProvider.updateSession(SharedTagAuthSession(USER_A, "token-a"))

        val localTagId = repository.createTag("Team Links")
        assertNotNull(localTagId)
        val entryId = insertEntry("https://example.com/shared")
        repository.assignTag(requireNotNull(localTagId), entryId)

        val pendingBefore = db.sharedTagSyncDao().getPendingOutbox(USER_A)
        assertEquals(2, pendingBefore.size)

        val createdTag = db.tagDao().findTagById(requireNotNull(localTagId))!!
        val remoteTagId = requireNotNull(createdTag.remoteTagId)
        remote.snapshot = PullSharedTagSnapshotResponse(
            pulledAt = "2026-04-20T00:00:00Z",
            normalizationVersion = 1,
            tags = listOf(
                RemoteSharedTag(
                    id = remoteTagId,
                    name = "Team Links",
                    createdBy = USER_A,
                    createdAt = "2026-04-20T00:00:00Z",
                    updatedAt = "2026-04-20T00:00:00Z",
                    version = 2,
                ),
            ),
            members = listOf(
                RemoteSharedTagMember(
                    tagId = remoteTagId,
                    userId = USER_A,
                    role = "owner",
                    status = "active",
                    createdAt = "2026-04-20T00:00:00Z",
                    updatedAt = "2026-04-20T00:00:00Z",
                ),
            ),
            urls = listOf(
                RemoteSharedTagUrl(
                    id = "70000000-0000-0000-0000-000000000001",
                    tagId = remoteTagId,
                    rawUrl = "https://example.com/shared",
                    normalizedUrl = "https://example.com/shared",
                    normalizationVersion = 1,
                    addedBy = USER_A,
                    createdAt = "2026-04-20T00:00:00Z",
                    updatedAt = "2026-04-20T00:00:00Z",
                ),
            ),
        )

        assertTrue(coordinator.syncCurrentSession())

        val visibleTags = repository.observeAllTagsWithCount().first()
        assertEquals(1, visibleTags.size)
        assertEquals(SharedTagScope.SYNCED, visibleTags.first().scope)
        assertEquals(SharedTagSyncStatus.SYNCED, visibleTags.first().syncStatus)
        assertEquals(1, visibleTags.first().urlCount)
        assertTrue(db.sharedTagSyncDao().getPendingOutbox(USER_A).isEmpty())
    }

    @Test
    fun userSwitch_hidesPreviousUsersSyncedTags() = runBlocking {
        db.tagDao().insertTag(
            TagEntity(
                name = "user-a-tag",
                createdAt = 1L,
                scope = SharedTagScope.SYNCED,
                authUserId = USER_A,
                remoteTagId = "tag-a",
                syncStatus = SharedTagSyncStatus.SYNCED,
            ),
        )
        db.tagDao().insertTag(
            TagEntity(
                name = "user-b-tag",
                createdAt = 2L,
                scope = SharedTagScope.SYNCED,
                authUserId = USER_B,
                remoteTagId = "tag-b",
                syncStatus = SharedTagSyncStatus.SYNCED,
            ),
        )
        db.tagDao().insertTag(TagEntity(name = "local-tag", createdAt = 3L))

        authProvider.updateSession(SharedTagAuthSession(USER_A, "token-a"))
        val userATags = repository.observeAllTagsWithCount().first().map { it.name }
        assertEquals(listOf("local-tag", "user-a-tag"), userATags)

        authProvider.updateSession(SharedTagAuthSession(USER_B, "token-b"))
        val userBTags = repository.observeAllTagsWithCount().first().map { it.name }
        assertEquals(listOf("local-tag", "user-b-tag"), userBTags)
    }

    @Test
    fun migrateLocalTagToCloud_reusesSameLocalTagRowAndQueuesOps() = runBlocking {
        val localTagId = db.tagDao().insertTag(TagEntity(name = "local-only", createdAt = 1L))
        val entryId = insertEntry("https://example.com/migrate")
        db.tagDao().insertCrossRef(TagUrlCrossRef(tagId = localTagId, entryId = entryId))
        authProvider.updateSession(SharedTagAuthSession(USER_A, "token-a"))

        assertTrue(repository.migrateLocalTagToCloud(localTagId))

        val migrated = db.tagDao().findTagById(localTagId)!!
        assertEquals(SharedTagScope.SYNCED, migrated.scope)
        assertEquals(USER_A, migrated.authUserId)
        assertNotNull(migrated.remoteTagId)

        val pending = db.sharedTagSyncDao().getPendingOutbox(USER_A)
        assertEquals(2, pending.size)
    }

    @Test
    fun syncFailure_keepsOutboxAndMarksFailure() = runBlocking {
        authProvider.updateSession(SharedTagAuthSession(USER_A, "token-a"))
        remote.failApply = true

        val tagId = repository.createTag("needs-retry")
        assertNotNull(tagId)

        assertFalse(coordinator.syncCurrentSession())

        val pending = db.sharedTagSyncDao().getPendingOutbox(USER_A)
        assertEquals(1, pending.size)
        assertEquals(1, pending.first().attemptCount)
        assertEquals("PENDING", pending.first().state.name)
    }

    @Test
    fun syncForAuthUser_sessionMismatch_skipsRemoteAndDoesNotMutatePendingOps() = runBlocking {
        authProvider.updateSession(SharedTagAuthSession(USER_A, "token-a"))
        val tagId = repository.createTag("queued-for-user-a")
        assertNotNull(tagId)
        assertEquals(1, db.sharedTagSyncDao().getPendingOutbox(USER_A).size)

        authProvider.updateSession(SharedTagAuthSession(USER_B, "token-b"))
        assertTrue(coordinator.syncForAuthUser(USER_A))

        assertEquals(0, remote.applyCallCount)
        assertEquals(1, db.sharedTagSyncDao().getPendingOutbox(USER_A).size)
    }

    @Test
    fun syncForAuthUser_terminalApplyStatus_marksOutboxFailedAndDoesNotRetry() = runBlocking {
        authProvider.updateSession(SharedTagAuthSession(USER_A, "token-a"))
        remote.overrideApplyStatus = "rejected"
        val tagId = repository.createTag("terminal-failure")
        assertNotNull(tagId)

        assertTrue(coordinator.syncForAuthUser(USER_A))

        assertEquals(1, remote.applyCallCount)
        assertEquals(USER_A, remote.lastApplyAuthUserId)
        assertTrue(db.sharedTagSyncDao().getPendingOutbox(USER_A).isEmpty())
        val allOutbox = db.sharedTagSyncDao().getOutboxForUser(USER_A)
        assertEquals(1, allOutbox.size)
        assertEquals("FAILED", allOutbox.first().state.name)
        assertTrue(allOutbox.first().lastErrorMessage.orEmpty().contains("terminal status=rejected"))
    }

    @Test
    fun syncForAuthUser_unknownApplyStatus_failsSyncAndKeepsPendingForVisibility() = runBlocking {
        authProvider.updateSession(SharedTagAuthSession(USER_A, "token-a"))
        remote.overrideApplyStatus = "unexpected_status"
        val tagId = repository.createTag("unknown-status")
        assertNotNull(tagId)

        assertFalse(coordinator.syncForAuthUser(USER_A))

        val pending = db.sharedTagSyncDao().getPendingOutbox(USER_A)
        assertEquals(1, pending.size)
        assertEquals("PENDING", pending.first().state.name)
        assertEquals(1, pending.first().attemptCount)
        assertTrue(pending.first().lastErrorMessage.orEmpty().contains("unknown status=unexpected_status"))
    }

    private suspend fun insertEntry(url: String): Long {
        return db.urlEntryDao().insert(
            UrlEntryEntity(
                originalUrl = url,
                normalizedUrl = url,
                displayUrl = url.removePrefix("https://"),
                openUrl = url,
                normalizedHost = "example.com",
                rawSourceHost = "example.com",
                serviceType = ServiceType.WEB,
                contentContext = ContentContext.STANDARD,
                metadataState = MetadataState.PENDING,
                recordState = RecordState.ACTIVE,
                createdAt = clock.now,
                updatedAt = clock.now,
            ),
        )
    }

    private class FakeScheduler : MetadataScheduler {
        override fun enqueueMetadata(entryId: Long) = Unit
    }

    private class FakeClock(
        var now: Long,
    ) : AppClock {
        override fun nowEpochMillis(): Long = now
    }

    private class FakeAuthSessionProvider : SharedTagAuthSessionProvider {
        private val state = MutableStateFlow<SharedTagAuthSession?>(null)
        override val session: StateFlow<SharedTagAuthSession?> = state

        override fun updateSession(newSession: SharedTagAuthSession?) {
            state.value = newSession
        }
    }

    private class FakeRemoteDataSource : SharedTagSyncRemoteDataSource {
        var failApply: Boolean = false
        var overrideApplyStatus: String? = null
        var applyCallCount: Int = 0
        var lastApplyAuthUserId: String? = null
        var snapshot: PullSharedTagSnapshotResponse = PullSharedTagSnapshotResponse(
            pulledAt = "2026-04-20T00:00:00Z",
            normalizationVersion = 1,
            tags = emptyList(),
            members = emptyList(),
            urls = emptyList(),
        )

        override suspend fun applyOps(
            session: SharedTagAuthSession,
            operations: List<SharedTagSyncOperation>,
        ): ApplySharedTagOpsResponse {
            applyCallCount += 1
            lastApplyAuthUserId = session.authUserId
            if (failApply) error("apply failed")
            val status = overrideApplyStatus ?: "applied"
            return ApplySharedTagOpsResponse(
                results = operations.map { op ->
                    jp.mimac.urlsaver.domain.SharedTagOpApplyResult(
                        opId = op.opId,
                        status = status,
                        tagId = op.tagId,
                        urlId = op.urlId,
                        normalizedUrl = op.normalizedUrl,
                        userId = op.userId,
                    )
                },
            )
        }

        override suspend fun pullSnapshot(session: SharedTagAuthSession): PullSharedTagSnapshotResponse = snapshot
    }

    private companion object {
        const val USER_A = "00000000-0000-0000-0000-000000000001"
        const val USER_B = "00000000-0000-0000-0000-000000000002"
    }
}
