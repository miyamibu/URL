package jp.mimac.urlsaver

import android.content.Intent
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import jp.mimac.urlsaver.data.AppDatabase
import jp.mimac.urlsaver.data.AiLocalDataClearer
import jp.mimac.urlsaver.data.AiDraftEntity
import jp.mimac.urlsaver.data.AiDiffProposalEntity
import jp.mimac.urlsaver.data.AiReceiptEntity
import jp.mimac.urlsaver.data.AiTransparencyRepository
import jp.mimac.urlsaver.data.DefaultTagRepository
import jp.mimac.urlsaver.data.DefaultUsageSummaryDataSource
import jp.mimac.urlsaver.data.LocalAccountCleanupMarker
import jp.mimac.urlsaver.data.LocalAccountCleanupStore
import jp.mimac.urlsaver.data.MetadataUpdate
import jp.mimac.urlsaver.data.MetadataScheduler
import jp.mimac.urlsaver.data.SaveMemoResult
import jp.mimac.urlsaver.data.SaveTitleResult
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
import jp.mimac.urlsaver.data.UrlRepository
import jp.mimac.urlsaver.data.VideoAssetEntity
import jp.mimac.urlsaver.data.VideoDownloadEntity
import jp.mimac.urlsaver.data.VideoRepository
import jp.mimac.urlsaver.domain.AcceptSharedTagInviteResponse
import jp.mimac.urlsaver.domain.ApplySharedTagOpsResponse
import jp.mimac.urlsaver.domain.ContentContext
import jp.mimac.urlsaver.domain.CreateTagResult
import jp.mimac.urlsaver.domain.CreateSharedTagInviteResponse
import jp.mimac.urlsaver.domain.PreviewSharedTagInviteResponse
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
import jp.mimac.urlsaver.domain.TransferSharedTagOwnershipResponse
import jp.mimac.urlsaver.domain.AssignTagResult
import jp.mimac.urlsaver.domain.SharedTagAccountDeletionResult
import jp.mimac.urlsaver.domain.SaveResult
import jp.mimac.urlsaver.domain.ShareSaveResult
import jp.mimac.urlsaver.ui.CreateAndAssignTagResult
import jp.mimac.urlsaver.ui.DetailViewModel
import jp.mimac.urlsaver.util.AppClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    private lateinit var remote: FakeRemoteDataSource
    private lateinit var aiTransparencyRepository: AiTransparencyRepository
    private lateinit var aiLocalDataClearer: CountingAiLocalDataClearer
    private lateinit var localAccountCleanupStore: InMemoryLocalAccountCleanupStore
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
        remote = FakeRemoteDataSource()
        localAccountCleanupStore = InMemoryLocalAccountCleanupStore()
        aiTransparencyRepository = AiTransparencyRepository(
            database = db,
            aiTransparencyDao = db.aiTransparencyDao(),
            urlEntryDao = db.urlEntryDao(),
            tagDao = db.tagDao(),
            nowIso = { "2026-07-13T00:00:00Z" },
        )
        aiLocalDataClearer = CountingAiLocalDataClearer(aiTransparencyRepository)
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
            remoteDataSource = remote,
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
            aiLocalDataClearer = aiLocalDataClearer,
            localAccountCleanupStore = localAccountCleanupStore,
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
    fun deleteAccount_successClearsLocalAiTransparencyData() = runBlocking {
        val ids = seedAiTransparencyData()
        authProvider.updateSession(
            SharedTagAuthSession(
                authUserId = "delete-user-success",
                accessToken = "token",
            ),
        )

        val result = repository.deleteAccount()

        assertEquals(SharedTagAccountDeletionResult.Success, result)
        assertNull(db.aiTransparencyDao().findReceipt(ids.first))
        assertNull(db.aiTransparencyDao().findDraft(ids.second))
        assertNull(db.aiTransparencyDao().findDiffProposal(ids.third))
        assertNull(authProvider.session.value)
        assertEquals(1, remote.deleteAccountCallCount)
        assertEquals(1, aiLocalDataClearer.clearCallCount)
        assertEquals(1, authProvider.clearSessionCallCount)
        assertNull(localAccountCleanupStore.pending.value)
    }

    @Test
    fun deleteAccount_remoteFailureKeepsLocalAiTransparencyData() = runBlocking {
        val ids = seedAiTransparencyData()
        remote.deleteAccountFailure = IllegalStateException("remote delete failed")
        authProvider.updateSession(
            SharedTagAuthSession(
                authUserId = "delete-user-failure",
                accessToken = "token",
            ),
        )

        val result = repository.deleteAccount()

        assertTrue(result is SharedTagAccountDeletionResult.Failure)
        assertNotNull(db.aiTransparencyDao().findReceipt(ids.first))
        assertNotNull(db.aiTransparencyDao().findDraft(ids.second))
        assertNotNull(db.aiTransparencyDao().findDiffProposal(ids.third))
        assertNotNull(authProvider.session.value)
        assertEquals(1, remote.deleteAccountCallCount)
        assertEquals(0, aiLocalDataClearer.clearCallCount)
        assertEquals(0, authProvider.clearSessionCallCount)
        assertNull(localAccountCleanupStore.pending.value)
    }

    @Test
    fun deleteAccount_aiCleanupFailure_stillAttemptsSessionClear() = runBlocking {
        authProvider.updateSession(
            SharedTagAuthSession(
                authUserId = "delete-user-ai-failure",
                accessToken = "token",
            ),
        )
        aiLocalDataClearer.clearFailure = IllegalStateException("AI cleanup failed")

        val result = repository.deleteAccount()

        val pending = result as SharedTagAccountDeletionResult.LocalCleanupRequired
        assertTrue(pending.aiDataPending)
        assertFalse(pending.sessionPending)
        assertEquals(1, remote.deleteAccountCallCount)
        assertEquals(1, aiLocalDataClearer.clearCallCount)
        assertEquals(1, authProvider.clearSessionCallCount)
        assertNull(authProvider.session.value)
        assertEquals(
            LocalAccountCleanupMarker(aiDataPending = true, sessionPending = false),
            localAccountCleanupStore.pending.value,
        )
    }

    @Test
    fun retryLocalAccountCleanup_retriesOnlyLocalCleanup() = runBlocking {
        authProvider.updateSession(
            SharedTagAuthSession(
                authUserId = "delete-user-retry",
                accessToken = "token",
            ),
        )
        aiLocalDataClearer.clearFailure = IllegalStateException("AI cleanup failed")

        val firstResult = repository.deleteAccount()
        assertTrue(firstResult is SharedTagAccountDeletionResult.LocalCleanupRequired)

        aiLocalDataClearer.clearFailure = null
        val retryResult = repository.retryLocalAccountCleanup()

        assertEquals(SharedTagAccountDeletionResult.Success, retryResult)
        assertEquals(1, remote.deleteAccountCallCount)
        assertEquals(2, aiLocalDataClearer.clearCallCount)
        assertEquals(1, authProvider.clearSessionCallCount)
        assertNull(authProvider.session.value)
        assertNull(localAccountCleanupStore.pending.value)
    }

    @Test
    fun deleteAccount_sessionClearFailure_stillAttemptsAiCleanup() = runBlocking {
        authProvider.updateSession(
            SharedTagAuthSession(
                authUserId = "delete-user-session-failure",
                accessToken = "token",
            ),
        )
        authProvider.sessionClearFailure = IllegalStateException("session cleanup failed")

        val result = repository.deleteAccount()

        val pending = result as SharedTagAccountDeletionResult.LocalCleanupRequired
        assertFalse(pending.aiDataPending)
        assertTrue(pending.sessionPending)
        assertEquals(1, remote.deleteAccountCallCount)
        assertEquals(1, aiLocalDataClearer.clearCallCount)
        assertEquals(1, authProvider.clearSessionCallCount)
        assertNotNull(authProvider.session.value)
        assertEquals(
            LocalAccountCleanupMarker(aiDataPending = false, sessionPending = true),
            localAccountCleanupStore.pending.value,
        )
    }

    @Test
    fun retryLocalAccountCleanup_succeedsWhenSessionIsAlreadyNull() = runBlocking {
        val result = repository.retryLocalAccountCleanup()

        assertEquals(SharedTagAccountDeletionResult.Success, result)
        assertEquals(0, remote.deleteAccountCallCount)
        assertEquals(1, aiLocalDataClearer.clearCallCount)
        assertEquals(0, authProvider.clearSessionCallCount)
        assertNull(localAccountCleanupStore.pending.value)
    }

    @Test
    fun localCleanupMarker_survivesRepositoryRegeneration() = runBlocking {
        authProvider.updateSession(
            SharedTagAuthSession(
                authUserId = "delete-user-recreated-repository",
                accessToken = "token",
            ),
        )
        aiLocalDataClearer.clearFailure = IllegalStateException("AI cleanup failed")

        val firstResult = repository.deleteAccount()
        assertTrue(firstResult is SharedTagAccountDeletionResult.LocalCleanupRequired)

        val recreatedRepository = createRepository(
            remoteConfig = SharedTagSyncRemoteConfig(
                enabled = true,
                supabaseUrl = "https://example.supabase.co",
                anonKey = "anon",
            ),
        )
        aiLocalDataClearer.clearFailure = null

        assertEquals(SharedTagAccountDeletionResult.Success, recreatedRepository.retryLocalAccountCleanup())
        assertEquals(1, remote.deleteAccountCallCount)
        assertNull(localAccountCleanupStore.pending.value)
    }

    @Test
    fun createTag_signedInStillCreatesLocalTag() = runBlocking {
        val authUserId = "00000000-0000-0000-0000-000000000111"
        authProvider.updateSession(
            SharedTagAuthSession(
                authUserId = authUserId,
                accessToken = "token",
            ),
        )

        val createdId = repository.createTag("cloud-by-default")
        val created = db.tagDao().findTagById(requireNotNull(createdId))

        assertEquals(SharedTagScope.LOCAL_ONLY, created?.scope)
        assertNull(created?.authUserId)
        assertNull(created?.remoteTagId)
        assertEquals(SharedTagSyncStatus.LOCAL_ONLY, created?.syncStatus)
        val currentUserMember = db.tagDao().findCurrentUserMember(createdId, authUserId)
        assertNull(currentUserMember)
        val pending = db.sharedTagSyncDao().getPendingOutbox(authUserId)
        assertEquals(0, pending.size)
        assertTrue(syncScheduler.enqueued.isEmpty())
    }

    @Test
    fun createSyncedTagWithResult_signedInCreatesCloudSharedTag() = runBlocking {
        val authUserId = "00000000-0000-0000-0000-000000000111"
        authProvider.updateSession(
            SharedTagAuthSession(
                authUserId = authUserId,
                accessToken = "token",
            ),
        )

        val result = repository.createSyncedTagWithResult("cloud-explicit")
        assertTrue(result is CreateTagResult.Success)
        val createdId = (result as CreateTagResult.Success).tagId
        val created = db.tagDao().findTagById(createdId)

        assertEquals(SharedTagScope.SYNCED, created?.scope)
        assertEquals(authUserId, created?.authUserId)
        assertNotNull(created?.remoteTagId)
        assertEquals(SharedTagSyncStatus.PENDING_PUSH, created?.syncStatus)
        val currentUserMember = db.tagDao().findCurrentUserMember(createdId, authUserId)
        assertEquals(SharedTagMemberRole.OWNER, currentUserMember?.role)
        val pending = db.sharedTagSyncDao().getPendingOutbox(authUserId)
        assertEquals(1, pending.size)
        assertEquals(SharedTagSyncOperationType.CREATE_TAG, pending.first().operationType)
        assertEquals(listOf(authUserId), syncScheduler.enqueued)
    }

    @Test
    fun createLocalTagWithResult_signedInStillCreatesLocalTag() = runBlocking {
        authProvider.updateSession(
            SharedTagAuthSession(
                authUserId = "00000000-0000-0000-0000-000000000222",
                accessToken = "token",
            ),
        )

        val result = repository.createLocalTagWithResult("normal-detail-tag")
        assertTrue(result is CreateTagResult.Success)
        val created = db.tagDao().findTagById((result as CreateTagResult.Success).tagId)

        assertEquals(SharedTagScope.LOCAL_ONLY, created?.scope)
        assertNull(created?.remoteTagId)
        assertTrue(syncScheduler.enqueued.isEmpty())
    }

    @Test
    fun detailViewModel_createLocalAndAssignTag_signedInDoesNotCreateSharedTag() = runBlocking {
        val authUserId = "00000000-0000-0000-0000-000000000333"
        authProvider.updateSession(
            SharedTagAuthSession(
                authUserId = authUserId,
                accessToken = "token",
            ),
        )
        val entryId = insertEntry(
            url = "https://example.com/detail-local",
            createdAt = 2_000L,
        )
        val viewModel = DetailViewModel(
            entryId = entryId,
            repository = FakeUrlRepository(),
            tagRepository = repository,
            videoRepository = FakeVideoRepository(),
        )

        val result = viewModel.createLocalAndAssignTag("local-from-detail")

        assertEquals(CreateAndAssignTagResult.Success, result)
        val assignedTags = db.tagDao().getVisibleTagsForEntry(entryId, authUserId = null)
        assertEquals(listOf("local-from-detail"), assignedTags.map { it.name })
        val created = db.tagDao().findTagById(assignedTags.single().id)
        assertEquals(SharedTagScope.LOCAL_ONLY, created?.scope)
        assertNull(created?.remoteTagId)
        assertNull(created?.authUserId)
        assertTrue(syncScheduler.enqueued.isEmpty())
    }

    @Test
    fun localTagDuplicateAndAlreadyAssignedResults_areExplicit() = runBlocking {
        val entryId = insertEntry(
            url = "https://example.com/detail-existing-local",
            createdAt = 2_100L,
        )
        val existing = repository.createLocalTagWithResult("existing-detail-tag")
        assertTrue(existing is CreateTagResult.Success)
        val existingTagId = (existing as CreateTagResult.Success).tagId

        val duplicateCreate = repository.createLocalTagWithResult("existing-detail-tag")
        val firstAssign = repository.assignTagWithResult(existingTagId, entryId)
        val secondAssign = repository.assignTagWithResult(existingTagId, entryId)

        assertEquals(CreateTagResult.Duplicate, duplicateCreate)
        assertEquals(AssignTagResult.Success, firstAssign)
        assertEquals(AssignTagResult.AlreadyAssigned, secondAssign)
        assertEquals(listOf("existing-detail-tag"), db.tagDao().getVisibleTagsForEntry(entryId, authUserId = null).map { it.name })
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
            "https://miyamibu.xyz/invite/invite-token",
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
            aiLocalDataClearer = aiTransparencyRepository,
            localAccountCleanupStore = localAccountCleanupStore,
        )
    }

    private class InMemoryLocalAccountCleanupStore : LocalAccountCleanupStore {
        private val state = MutableStateFlow<LocalAccountCleanupMarker?>(null)
        override val pending: StateFlow<LocalAccountCleanupMarker?> = state

        override fun save(aiDataPending: Boolean, sessionPending: Boolean) {
            state.value = if (aiDataPending || sessionPending) {
                LocalAccountCleanupMarker(aiDataPending, sessionPending)
            } else {
                null
            }
        }

        override fun clear() {
            state.value = null
        }
    }

    private suspend fun seedAiTransparencyData(): Triple<String, String, String> {
        val receiptId = "delete-account-receipt"
        val draftId = "delete-account-draft"
        val proposalId = "delete-account-diff"
        db.aiTransparencyDao().upsertReceipt(
            AiReceiptEntity(
                receiptId = receiptId,
                actionKind = "EXPORT",
                destination = "internal-preview",
                generatedAtIso = "2026-07-13T00:00:00Z",
                redactionProfile = "ai-safe-v1",
                requestSizeBucket = "ZERO",
                responseSizeBucket = "ZERO",
                rawBodyIncluded = false,
                rawPromptIncluded = false,
            ),
        )
        db.aiTransparencyDao().upsertDraft(
            AiDraftEntity(
                draftId = draftId,
                receiptId = receiptId,
                generatedAtIso = "2026-07-13T00:00:00Z",
                title = "候補",
                body = "本文",
                citedSourceIdsJson = "[]",
                status = "DRAFT",
            ),
        )
        db.aiTransparencyDao().upsertDiffProposal(
            AiDiffProposalEntity(
                proposalId = proposalId,
                draftId = draftId,
                generatedAtIso = "2026-07-13T00:00:00Z",
                operationsJson = "[]",
                applied = false,
            ),
        )
        return Triple(receiptId, draftId, proposalId)
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

    private class CountingAiLocalDataClearer(
        private val delegate: AiLocalDataClearer,
    ) : AiLocalDataClearer {
        var clearCallCount: Int = 0
        var clearFailure: Throwable? = null

        override suspend fun clearLocalAiData() {
            clearCallCount += 1
            clearFailure?.let { throw it }
            delegate.clearLocalAiData()
        }
    }

    private class FakeUrlRepository : UrlRepository {
        override fun observeActiveEntries() = flowOf(emptyList<UrlEntryEntity>())
        override fun observeArchiveEntries() = flowOf(emptyList<UrlEntryEntity>())
        override fun observeEntry(entryId: Long) = flowOf<UrlEntryEntity?>(null)
        override suspend fun saveFromManualInput(input: String): SaveResult = SaveResult(ShareSaveResult.SAVE_FAILED)
        override suspend fun archive(entryId: Long): Boolean = false
        override suspend fun markPendingDelete(entryId: Long, gracePeriodMillis: Long): Long? = null
        override suspend fun saveFromIntent(intent: Intent): SaveResult = SaveResult(ShareSaveResult.SAVE_FAILED)
        override suspend fun unarchive(entryId: Long): Boolean = false
        override suspend fun finalizePendingDelete(entryId: Long) = Unit
        override suspend fun cleanupExpiredPendingDeletes() = Unit
        override suspend fun restore(entryId: Long): Boolean = false
        override suspend fun saveUserTitle(entryId: Long, rawTitle: String): SaveTitleResult = SaveTitleResult(false)
        override suspend fun restoreUserTitle(entryId: Long, oldTitle: String?): Boolean = false
        override suspend fun saveMemo(entryId: Long, rawMemo: String): SaveMemoResult = SaveMemoResult(false)
        override suspend fun applyCanonicalId(entryId: Long, canonicalId: String?) = Unit
        override suspend fun applyMetadataUpdate(entryId: Long, metadata: MetadataUpdate) = Unit
        override suspend fun retryMetadata(entryId: Long): Boolean = false
        override suspend fun loadEntry(entryId: Long): UrlEntryEntity? = null
    }

    private class FakeVideoRepository : VideoRepository {
        override fun observeAssetsForEntry(entryId: Long) = flowOf(emptyList<VideoAssetEntity>())
        override fun observePreferredAssetForEntry(entryId: Long) = flowOf<VideoAssetEntity?>(null)
        override fun observeLatestDownloadForEntry(entryId: Long) = flowOf<VideoDownloadEntity?>(null)
        override fun observeSavedDownloadsForEntry(entryId: Long) = flowOf(emptyList<VideoDownloadEntity>())
        override fun enqueueResolve(entryId: Long, autoDownload: Boolean) = Unit
        override fun enqueueDownloads(videoAssetIds: List<Long>) = Unit
        override suspend fun repairSavedDownloadsForEntry(entryId: Long): Int = 0
    }

    private class FakeAuthSessionProvider : SharedTagAuthSessionProvider {
        private val state = MutableStateFlow<SharedTagAuthSession?>(null)
        override val session: StateFlow<SharedTagAuthSession?> = state
        var clearSessionCallCount: Int = 0
        var sessionClearFailure: Throwable? = null

        override fun updateSession(newSession: SharedTagAuthSession?) {
            if (newSession == null) {
                clearSessionCallCount += 1
                sessionClearFailure?.let { throw it }
            }
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
        var deleteAccountFailure: Throwable? = null
        var deleteAccountCallCount: Int = 0

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

        override suspend fun previewInvite(inviteToken: String): PreviewSharedTagInviteResponse {
            return PreviewSharedTagInviteResponse(
                tagName = "joined-tag",
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

        override suspend fun deleteAccount(session: SharedTagAuthSession) {
            deleteAccountCallCount += 1
            deleteAccountFailure?.let { throw it }
        }
    }

    private class FakeSyncScheduler : SharedTagSyncScheduler {
        val enqueued = mutableListOf<String>()

        override fun enqueue(authUserId: String) {
            enqueued += authUserId
        }
    }
}
