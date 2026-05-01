package jp.mimac.urlsaver

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import jp.mimac.urlsaver.data.AppDatabase
import jp.mimac.urlsaver.data.DefaultTagRepository
import jp.mimac.urlsaver.data.DefaultUsageSummaryDataSource
import jp.mimac.urlsaver.data.MetadataScheduler
import jp.mimac.urlsaver.data.SharedTagAuthRemoteDataSource
import jp.mimac.urlsaver.data.SharedTagAuthRemoteResult
import jp.mimac.urlsaver.data.SharedTagAuthSession
import jp.mimac.urlsaver.data.SharedTagAuthSessionProvider
import jp.mimac.urlsaver.data.SharedTagSyncCoordinator
import jp.mimac.urlsaver.data.SharedTagMemberEntity
import jp.mimac.urlsaver.data.SharedTagSyncRemoteConfig
import jp.mimac.urlsaver.data.SharedTagSyncRemoteDataSource
import jp.mimac.urlsaver.data.SharedTagSyncScheduler
import jp.mimac.urlsaver.data.TagEntity
import jp.mimac.urlsaver.data.TagUrlCrossRef
import jp.mimac.urlsaver.data.UrlEntryEntity
import jp.mimac.urlsaver.domain.AcceptSharedTagInviteResponse
import jp.mimac.urlsaver.domain.ApplySharedTagOpsResponse
import jp.mimac.urlsaver.domain.ContentContext
import jp.mimac.urlsaver.domain.CreateTagResult
import jp.mimac.urlsaver.domain.CreateSharedTagInviteResponse
import jp.mimac.urlsaver.domain.MetadataState
import jp.mimac.urlsaver.domain.PullSharedTagSnapshotResponse
import jp.mimac.urlsaver.domain.RecordState
import jp.mimac.urlsaver.domain.SharedTagMemberRole
import jp.mimac.urlsaver.domain.SharedTagMemberStatus
import jp.mimac.urlsaver.domain.SharedTagInviteCreationResult
import jp.mimac.urlsaver.domain.SharedTagSyncOperationType
import jp.mimac.urlsaver.domain.SharedTagSyncOperation
import jp.mimac.urlsaver.domain.SharedTagScope
import jp.mimac.urlsaver.domain.SharedTagSyncStatus
import jp.mimac.urlsaver.domain.ServiceType
import jp.mimac.urlsaver.domain.TagSharePayload
import jp.mimac.urlsaver.domain.TagShareUrl
import jp.mimac.urlsaver.domain.AssignTagResult
import jp.mimac.urlsaver.domain.TransferSharedTagOwnershipResponse
import jp.mimac.urlsaver.util.AppClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TagRepositoryTest {

    private lateinit var db: AppDatabase
    private lateinit var repository: DefaultTagRepository
    private lateinit var authProvider: FakeAuthSessionProvider
    private lateinit var syncScheduler: FakeSyncScheduler
    private val scheduler = FakeScheduler()
    private val clock = FakeClock(1_000L)

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        authProvider = FakeAuthSessionProvider()
        syncScheduler = FakeSyncScheduler()
        repository = DefaultTagRepository(
            database = db,
            tagDao = db.tagDao(),
            syncDao = db.sharedTagSyncDao(),
            urlEntryDao = db.urlEntryDao(),
            clock = clock,
            metadataScheduler = scheduler,
            authSessionProvider = authProvider,
            authRemoteDataSource = FakeAuthRemoteDataSource(),
            syncScheduler = syncScheduler,
            syncCoordinator = SharedTagSyncCoordinator(
                database = db,
                tagDao = db.tagDao(),
                syncDao = db.sharedTagSyncDao(),
                urlEntryDao = db.urlEntryDao(),
                authSessionProvider = authProvider,
                remoteDataSource = FakeRemoteDataSource(),
                clock = clock,
                metadataScheduler = scheduler,
            ),
            remoteDataSource = FakeRemoteDataSource(),
            remoteConfig = SharedTagSyncRemoteConfig(
                enabled = true,
                supabaseUrl = "https://example.supabase.co",
                anonKey = "anon",
            ),
            usageSummaryDataSource = DefaultUsageSummaryDataSource(
                urlEntryDao = db.urlEntryDao(),
                tagDao = db.tagDao(),
                authSessionProvider = authProvider,
            ),
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun createTag_validatesBlankTooLongDuplicateAndSuccess() = runBlocking {
        assertNull(repository.createTag("   "))
        assertNull(repository.createTag("a".repeat(51)))

        val createdId = repository.createTag(" shared ")
        assertNotNull(createdId)

        val duplicateId = repository.createTag("shared")
        assertNull(duplicateId)
    }

    @Test
    fun createTag_signedInStillCreatesLocalTagByDefault() = runBlocking {
        authProvider.updateSession(
            SharedTagAuthSession(
                authUserId = "00000000-0000-0000-0000-000000000111",
                accessToken = "token",
            ),
        )

        val createdId = repository.createTag("local-by-default")
        val created = db.tagDao().findTagById(requireNotNull(createdId))

        assertEquals(SharedTagScope.LOCAL_ONLY, created?.scope)
    }

    @Test
    fun createTagWithResult_blocksAtNormalTagLimit() = runBlocking {
        repeat(10) { index ->
            val result = repository.createTagWithResult("local-limit-$index")
            assertTrue(result is CreateTagResult.Success)
        }

        val blocked = repository.createTagWithResult("local-limit-over")
        assertTrue(blocked is CreateTagResult.LimitReached)
    }

    @Test
    fun assignTagWithResult_blocksAtSharedTagUrlLimitPerTag() = runBlocking {
        val authUserId = "00000000-0000-0000-0000-000000000777"
        authProvider.updateSession(
            SharedTagAuthSession(
                authUserId = authUserId,
                accessToken = "token",
            ),
        )
        val tagId = db.tagDao().insertTag(
            TagEntity(
                name = "shared-limit",
                createdAt = 10L,
                scope = SharedTagScope.SYNCED,
                authUserId = authUserId,
                remoteTagId = "remote-shared-limit",
                syncStatus = SharedTagSyncStatus.SYNCED,
            ),
        )

        repeat(20) { index ->
            val entryId = insertEntry(
                url = "https://example.com/shared-limit-$index",
                createdAt = 100L + index,
            )
            val assigned = repository.assignTagWithResult(tagId, entryId)
            assertEquals(AssignTagResult.Success, assigned)
        }

        val overflowEntryId = insertEntry(
            url = "https://example.com/shared-limit-overflow",
            createdAt = 999L,
        )
        val blocked = repository.assignTagWithResult(tagId, overflowEntryId)
        assertTrue(blocked is AssignTagResult.LimitReached)
    }

    @Test
    fun importTag_tracksCreatedMergedSkippedFailed_andOnlyEnqueuesNewEntries() = runBlocking {
        val targetTagId = db.tagDao().insertTag(TagEntity(name = "shared-import", createdAt = 10L))
        val alreadyTaggedEntryId = insertEntry(
            url = "https://example.com/already-tagged",
            userTitle = "keep title",
            memo = "keep memo",
            createdAt = 10L,
        )
        val mergeOnlyEntryId = insertEntry(
            url = "https://example.com/merge-me",
            userTitle = "existing title",
            memo = "existing memo",
            createdAt = 11L,
        )
        db.tagDao().insertCrossRef(TagUrlCrossRef(tagId = targetTagId, entryId = alreadyTaggedEntryId))

        val result = repository.importTag(
            TagSharePayload(
                urlsaverVersion = 1,
                tag = "shared-import",
                exportedAt = 1_234L,
                urls = listOf(
                    TagShareUrl(
                        url = "https://example.com/new-entry",
                        title = "Imported title",
                        memo = "Imported memo",
                    ),
                    TagShareUrl(
                        url = "https://example.com/merge-me",
                        title = "Should not replace",
                        memo = "Should not replace",
                    ),
                    TagShareUrl(
                        url = "https://example.com/already-tagged",
                        title = "Ignored title",
                        memo = "Ignored memo",
                    ),
                    TagShareUrl(
                        url = "not-a-url",
                        title = "Broken",
                        memo = "Broken",
                    ),
                ),
            ),
        )

        assertEquals(targetTagId, result.tagId)
        assertEquals("shared-import", result.tagName)
        assertEquals(1, result.created)
        assertEquals(1, result.merged)
        assertEquals(1, result.duplicateSkipped)
        assertEquals(1, result.failed)

        val newEntry = db.urlEntryDao().findByNormalizedUrl("https://example.com/new-entry")!!
        assertEquals("Imported title", newEntry.userTitle)
        assertEquals("Imported memo", newEntry.memo)

        val existingEntry = db.urlEntryDao().findById(mergeOnlyEntryId)!!
        assertEquals("existing title", existingEntry.userTitle)
        assertEquals("existing memo", existingEntry.memo)

        val targetEntries = db.tagDao().getEntriesForTag(targetTagId).map { it.id }.toSet()
        assertTrue(targetEntries.contains(newEntry.id))
        assertTrue(targetEntries.contains(mergeOnlyEntryId))
        assertTrue(targetEntries.contains(alreadyTaggedEntryId))
        assertEquals(listOf(newEntry.id), scheduler.enqueued)
    }

    @Test
    fun createInviteLink_usesPublicInviteBaseUrlNotSupabaseUrl() = runBlocking {
        val authUserId = "00000000-0000-0000-0000-000000000777"
        authProvider.updateSession(
            SharedTagAuthSession(
                authUserId = authUserId,
                accessToken = "token",
            ),
        )
        val repositoryWithCloud = createRepository(
            remoteConfig = SharedTagSyncRemoteConfig(
                enabled = true,
                supabaseUrl = "https://example.supabase.co",
                anonKey = "anon-key",
            ),
        )
        val tagId = db.tagDao().insertTag(
            TagEntity(
                name = "invite-public-base",
                createdAt = 10L,
                scope = SharedTagScope.SYNCED,
                authUserId = authUserId,
                remoteTagId = "remote-tag",
                syncStatus = SharedTagSyncStatus.SYNCED,
            ),
        )
        db.sharedTagSyncDao().upsertMembers(
            listOf(
                SharedTagMemberEntity(
                    tagId = tagId,
                    authUserId = authUserId,
                    userId = authUserId,
                    role = SharedTagMemberRole.OWNER,
                    status = SharedTagMemberStatus.ACTIVE,
                    createdAt = 10L,
                    updatedAt = 10L,
                ),
            ),
        )

        val result = repositoryWithCloud.createInviteLink(tagId)

        assertTrue(result is SharedTagInviteCreationResult.Success)
        assertEquals(
            "https://urlsaver.app/invite/invite-token",
            (result as SharedTagInviteCreationResult.Success).inviteUrl,
        )
    }

    @Test
    fun triggerSyncIfStale_enqueuesOnlyWhenLastPullIsOldEnough() = runBlocking {
        authProvider.updateSession(
            SharedTagAuthSession(
                authUserId = "00000000-0000-0000-0000-000000000222",
                accessToken = "token",
            ),
        )

        assertTrue(repository.triggerSyncIfStale(minIntervalMillis = 60_000L))
        assertTrue(syncScheduler.enqueued.isEmpty())

        db.sharedTagSyncDao().upsertSyncState(
            jp.mimac.urlsaver.data.SharedTagSyncStateEntity(
                authUserId = "00000000-0000-0000-0000-000000000222",
                clientId = "client-1",
                lastPulledAt = clock.now,
            ),
        )

        assertEquals(false, repository.triggerSyncIfStale(minIntervalMillis = 60_000L))
        assertEquals(0, syncScheduler.enqueued.size)
    }

    @Test
    fun assignSyncedTag_whenCloudConfigMissing_stillAddsLocalLink() = runBlocking {
        val authUserId = "00000000-0000-0000-0000-000000000333"
        authProvider.updateSession(
            SharedTagAuthSession(
                authUserId = authUserId,
                accessToken = "token",
            ),
        )
        val repositoryWithoutCloud = createRepository(
            remoteConfig = SharedTagSyncRemoteConfig(
                enabled = false,
                supabaseUrl = "",
                anonKey = "",
            ),
        )
        val tagId = db.tagDao().insertTag(
            TagEntity(
                name = "synced-offline",
                createdAt = 10L,
                scope = SharedTagScope.SYNCED,
                authUserId = authUserId,
                remoteTagId = "remote-tag-1",
                syncStatus = SharedTagSyncStatus.SYNCED,
            ),
        )
        val entryId = insertEntry(
            url = "https://example.com/offline-shared",
            createdAt = 11L,
        )

        repositoryWithoutCloud.assignTag(tagId = tagId, entryId = entryId)

        val ref = db.tagDao().findCrossRef(tagId, entryId)
        assertNotNull(ref)
        assertEquals(SharedTagScope.SYNCED, ref?.scope)
        assertEquals(authUserId, ref?.authUserId)
        assertEquals("https://example.com/offline-shared", ref?.normalizedUrl)
        assertTrue(db.sharedTagSyncDao().getPendingOutbox(authUserId).isEmpty())
        assertTrue(syncScheduler.enqueued.isEmpty())
    }

    @Test
    fun removeMember_ownerMarksMemberRemovedAndQueuesSync() = runBlocking {
        val authUserId = "00000000-0000-0000-0000-000000000444"
        val memberUserId = "00000000-0000-0000-0000-000000000555"
        authProvider.updateSession(
            SharedTagAuthSession(
                authUserId = authUserId,
                accessToken = "token",
            ),
        )
        val tagId = db.tagDao().insertTag(
            TagEntity(
                name = "team",
                createdAt = 10L,
                scope = SharedTagScope.SYNCED,
                authUserId = authUserId,
                remoteTagId = "remote-team",
                syncStatus = SharedTagSyncStatus.SYNCED,
            ),
        )
        db.sharedTagSyncDao().upsertMembers(
            listOf(
                SharedTagMemberEntity(
                    tagId = tagId,
                    authUserId = authUserId,
                    userId = authUserId,
                    role = SharedTagMemberRole.OWNER,
                    status = SharedTagMemberStatus.ACTIVE,
                    createdAt = 10L,
                    updatedAt = 10L,
                ),
                SharedTagMemberEntity(
                    tagId = tagId,
                    authUserId = authUserId,
                    userId = memberUserId,
                    role = SharedTagMemberRole.EDITOR,
                    status = SharedTagMemberStatus.ACTIVE,
                    createdAt = 10L,
                    updatedAt = 10L,
                ),
            ),
        )

        assertEquals(2, repository.observeMembersForTag(tagId).first().size)
        clock.now = 20L

        assertTrue(repository.removeMember(tagId, memberUserId))

        val visibleMembers = repository.observeMembersForTag(tagId).first()
        assertEquals(listOf(authUserId), visibleMembers.map { it.userId })
        val removedMember = db.tagDao().findMember(tagId, authUserId, memberUserId)
        assertEquals(SharedTagMemberStatus.REMOVED, removedMember?.status)
        assertEquals(20L, removedMember?.updatedAt)
        val pending = db.sharedTagSyncDao().getPendingOutbox(authUserId)
        assertEquals(1, pending.size)
        assertEquals(SharedTagSyncOperationType.REMOVE_MEMBER, pending.first().operationType)
        assertEquals(listOf(authUserId), syncScheduler.enqueued)
    }

    private suspend fun insertEntry(
        url: String,
        userTitle: String? = null,
        memo: String = "",
        createdAt: Long,
    ): Long {
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
                userTitle = userTitle,
                memo = memo,
                metadataState = MetadataState.PENDING,
                recordState = RecordState.ACTIVE,
                createdAt = createdAt,
                updatedAt = createdAt,
            ),
        )
    }

    private fun createRepository(
        remoteConfig: SharedTagSyncRemoteConfig,
    ): DefaultTagRepository {
        return DefaultTagRepository(
            database = db,
            tagDao = db.tagDao(),
            syncDao = db.sharedTagSyncDao(),
            urlEntryDao = db.urlEntryDao(),
            clock = clock,
            metadataScheduler = scheduler,
            authSessionProvider = authProvider,
            authRemoteDataSource = FakeAuthRemoteDataSource(),
            syncScheduler = syncScheduler,
            syncCoordinator = SharedTagSyncCoordinator(
                database = db,
                tagDao = db.tagDao(),
                syncDao = db.sharedTagSyncDao(),
                urlEntryDao = db.urlEntryDao(),
                authSessionProvider = authProvider,
                remoteDataSource = FakeRemoteDataSource(),
                clock = clock,
                metadataScheduler = scheduler,
            ),
            remoteDataSource = FakeRemoteDataSource(),
            remoteConfig = remoteConfig,
            usageSummaryDataSource = DefaultUsageSummaryDataSource(
                urlEntryDao = db.urlEntryDao(),
                tagDao = db.tagDao(),
                authSessionProvider = authProvider,
            ),
        )
    }

    private class FakeScheduler : MetadataScheduler {
        val enqueued = mutableListOf<Long>()

        override fun enqueueMetadata(entryId: Long) {
            enqueued += entryId
        }
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

    private class FakeAuthRemoteDataSource : SharedTagAuthRemoteDataSource {
        override suspend fun signUp(email: String, password: String): SharedTagAuthRemoteResult {
            return SharedTagAuthRemoteResult.SignedIn(
                SharedTagAuthSession(
                    authUserId = "auth-sign-up",
                    accessToken = "access-token",
                    refreshToken = "refresh-token",
                    userEmail = email,
                ),
            )
        }

        override suspend fun signIn(email: String, password: String): SharedTagAuthRemoteResult {
            return SharedTagAuthRemoteResult.SignedIn(
                SharedTagAuthSession(
                    authUserId = "auth-sign-in",
                    accessToken = "access-token",
                    refreshToken = "refresh-token",
                    userEmail = email,
                ),
            )
        }

        override suspend fun refreshSession(refreshToken: String): SharedTagAuthSession {
            return SharedTagAuthSession(
                authUserId = "auth-refresh",
                accessToken = "new-access-token",
                refreshToken = refreshToken,
                userEmail = "refresh@example.com",
            )
        }
    }

    private class FakeRemoteDataSource : SharedTagSyncRemoteDataSource {
        override suspend fun applyOps(
            session: SharedTagAuthSession,
            operations: List<SharedTagSyncOperation>,
        ): ApplySharedTagOpsResponse = ApplySharedTagOpsResponse(emptyList())

        override suspend fun pullSnapshot(session: SharedTagAuthSession): PullSharedTagSnapshotResponse {
            return PullSharedTagSnapshotResponse(
                pulledAt = "2026-04-20T00:00:00Z",
                normalizationVersion = 1,
                tags = emptyList(),
                members = emptyList(),
                urls = emptyList(),
            )
        }

        override suspend fun createInvite(
            session: SharedTagAuthSession,
            remoteTagId: String,
            role: String,
        ): CreateSharedTagInviteResponse {
            return CreateSharedTagInviteResponse(
                tagId = remoteTagId,
                inviteToken = "invite-token",
                expiresAt = "2026-05-01T00:00:00Z",
                role = role,
            )
        }

        override suspend fun acceptInvite(
            session: SharedTagAuthSession,
            inviteToken: String,
        ): AcceptSharedTagInviteResponse {
            return AcceptSharedTagInviteResponse(
                tagId = "remote-tag",
                tagName = "joined-tag",
                role = "editor",
                status = "active",
            )
        }

        override suspend fun transferOwnership(
            session: SharedTagAuthSession,
            remoteTagId: String,
            newOwnerUserId: String,
        ): TransferSharedTagOwnershipResponse {
            return TransferSharedTagOwnershipResponse(
                tagId = remoteTagId,
                previousOwnerUserId = session.authUserId,
                newOwnerUserId = newOwnerUserId,
            )
        }

        override suspend fun deleteAccount(session: SharedTagAuthSession) = Unit
    }

    private class FakeSyncScheduler : SharedTagSyncScheduler {
        val enqueued = mutableListOf<String>()

        override fun enqueue(authUserId: String) {
            enqueued += authUserId
        }
    }
}
