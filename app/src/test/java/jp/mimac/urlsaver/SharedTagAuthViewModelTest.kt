package jp.mimac.urlsaver

import jp.mimac.urlsaver.data.EntitlementGrantRemoteDataSource
import jp.mimac.urlsaver.data.EntitlementGrantRepository
import jp.mimac.urlsaver.data.EntitlementGrantStore
import jp.mimac.urlsaver.data.ContactSupportClient
import jp.mimac.urlsaver.data.ContactSupportRequest
import jp.mimac.urlsaver.data.ContactSupportResult
import jp.mimac.urlsaver.data.LocalAccountCleanupMarker
import jp.mimac.urlsaver.data.LocalAccountCleanupStore
import jp.mimac.urlsaver.data.SharedPreferencesSharedTagAuthSessionProvider
import jp.mimac.urlsaver.data.SharedTagAuthSession
import jp.mimac.urlsaver.data.SharedTagAuthSessionProvider
import jp.mimac.urlsaver.data.TagRepository
import jp.mimac.urlsaver.data.UrlEntryEntity
import jp.mimac.urlsaver.data.UserProfileStore
import jp.mimac.urlsaver.domain.AssignTagResult
import jp.mimac.urlsaver.domain.CreateTagResult
import jp.mimac.urlsaver.domain.EntitlementGrant
import jp.mimac.urlsaver.domain.EntitlementSource
import jp.mimac.urlsaver.domain.FeatureEntitlements
import jp.mimac.urlsaver.domain.LaunchStandardPlan
import jp.mimac.urlsaver.domain.MigrateSharedTagResult
import jp.mimac.urlsaver.domain.PlanType
import jp.mimac.urlsaver.domain.SharedTagAccountDeletionResult
import jp.mimac.urlsaver.domain.SharedTagAuthResult
import jp.mimac.urlsaver.domain.SharedTagCloudState
import jp.mimac.urlsaver.domain.SharedTagInviteAcceptanceResult
import jp.mimac.urlsaver.domain.SharedTagInviteCreationResult
import jp.mimac.urlsaver.domain.SharedTagInvitePreviewResult
import jp.mimac.urlsaver.domain.SharedTagMemberRecord
import jp.mimac.urlsaver.domain.SharedTagOwnershipTransferResult
import jp.mimac.urlsaver.domain.SharedTagRecord
import jp.mimac.urlsaver.domain.TagImportResult
import jp.mimac.urlsaver.domain.TagSharePayload
import jp.mimac.urlsaver.domain.TagWithCount
import jp.mimac.urlsaver.domain.UsageSummary
import jp.mimac.urlsaver.domain.UserProfile
import jp.mimac.urlsaver.ui.PromoCodeApplyResult
import jp.mimac.urlsaver.ui.ContactSupportUiResult
import jp.mimac.urlsaver.ui.SharedTagAuthViewModel
import jp.mimac.urlsaver.util.AppClock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SharedTagAuthViewModelTest {

    @Test
    fun canApplyPromoCode_blankInputIsRejected() {
        val viewModel = SharedTagAuthViewModel(
            tagRepository = FakeTagRepository(),
            userProfileStore = FakeUserProfileStore(),
            entitlementGrantRepository = fakeEntitlementGrantRepository(),
        )

        assertFalse(viewModel.canApplyPromoCode("  "))
        assertTrue(viewModel.canApplyPromoCode("MIBU100"))
    }

    @Test
    fun redeemPromoCode_appliesPromoThroughEntitlementRepository() = runBlocking {
        val viewModel = SharedTagAuthViewModel(
            tagRepository = FakeTagRepository(),
            userProfileStore = FakeUserProfileStore(),
            entitlementGrantRepository = fakeEntitlementGrantRepository(
                redeemResult = listOf(
                    EntitlementGrant(
                        planType = PlanType.PROMO_PRO,
                        source = EntitlementSource.STORE_PROMO_CODE,
                        startsAt = 0L,
                    ),
                ),
            ),
        )

        val result = viewModel.redeemPromoCode("MIBU100")

        assertTrue(result is PromoCodeApplyResult.Success)
    }

    @Test
    fun sendContactSupport_blankInputIsRejected() = runBlocking {
        val viewModel = SharedTagAuthViewModel(
            tagRepository = FakeTagRepository(),
            userProfileStore = FakeUserProfileStore(),
            entitlementGrantRepository = fakeEntitlementGrantRepository(),
            contactSupportClient = FakeContactSupportClient(ContactSupportResult.Success("req-1")),
        )

        val result = viewModel.sendContactSupport("user@example.com", "", "body")

        assertTrue(result is ContactSupportUiResult.Failure)
    }

    @Test
    fun sendContactSupport_successReturnsSuccess() = runBlocking {
        val viewModel = SharedTagAuthViewModel(
            tagRepository = FakeTagRepository(),
            userProfileStore = FakeUserProfileStore(),
            entitlementGrantRepository = fakeEntitlementGrantRepository(),
            contactSupportClient = FakeContactSupportClient(ContactSupportResult.Success("req-1")),
        )

        val result = viewModel.sendContactSupport("user@example.com", "User", "body")

        assertTrue(result is ContactSupportUiResult.Success)
    }

    @Test
    fun sendContactSupport_failureReturnsMessage() = runBlocking {
        val viewModel = SharedTagAuthViewModel(
            tagRepository = FakeTagRepository(),
            userProfileStore = FakeUserProfileStore(),
            entitlementGrantRepository = fakeEntitlementGrantRepository(),
            contactSupportClient = FakeContactSupportClient(ContactSupportResult.Failure("failed")),
        )

        val result = viewModel.sendContactSupport("user@example.com", "User", "body")

        assertEquals("failed", (result as ContactSupportUiResult.Failure).message)
    }

    @Test
    fun localCleanupPending_restoresWhenViewModelIsRecreated() {
        val store = InMemoryLocalAccountCleanupStore().apply {
            save(aiDataPending = true, sessionPending = false)
        }

        fun createViewModel() = SharedTagAuthViewModel(
            tagRepository = FakeTagRepository(),
            userProfileStore = FakeUserProfileStore(),
            entitlementGrantRepository = fakeEntitlementGrantRepository(),
            localAccountCleanupStore = store,
        )

        val firstViewModel = createViewModel()
        val recreatedViewModel = createViewModel()

        val expected = SharedTagAccountDeletionResult.LocalCleanupRequired(
            aiDataPending = true,
            sessionPending = false,
        )
        assertEquals(expected, firstViewModel.localAccountCleanupPending.value)
        assertEquals(expected, recreatedViewModel.localAccountCleanupPending.value)
    }

    private fun fakeEntitlementGrantRepository(
        redeemResult: List<EntitlementGrant> = emptyList(),
    ): EntitlementGrantRepository {
        return EntitlementGrantRepository(
            authSessionProvider = FakeSessionProvider(
                SharedTagAuthSession(
                    authUserId = "user-1",
                    accessToken = "access-token",
                ),
            ),
            remoteDataSource = FakeEntitlementGrantRemoteDataSource(redeemResult),
            grantStore = FakeEntitlementGrantStore(),
            clock = FakeClock,
        )
    }
}

private class FakeContactSupportClient(
    private val result: ContactSupportResult,
) : ContactSupportClient {
    override suspend fun send(request: ContactSupportRequest): ContactSupportResult = result
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

private class FakeUserProfileStore : UserProfileStore {
    private val state = MutableStateFlow(UserProfile())

    override fun observeProfile(): Flow<UserProfile> = state

    override suspend fun saveDisplayName(displayName: String) {
        state.value = state.value.copy(displayName = displayName)
    }

    override suspend fun saveAvatarBase64(avatarBase64: String?) {
        state.value = state.value.copy(avatarBase64 = avatarBase64)
    }
}

private class FakeEntitlementGrantRemoteDataSource(
    private val redeemResult: List<EntitlementGrant>,
) : EntitlementGrantRemoteDataSource {
    override suspend fun fetchGrants(session: SharedTagAuthSession): List<EntitlementGrant> = emptyList()

    override suspend fun redeemPromoCode(
        session: SharedTagAuthSession,
        code: String,
    ): List<EntitlementGrant> = redeemResult
}

private class FakeEntitlementGrantStore : EntitlementGrantStore {
    override suspend fun loadLastKnownGrants(
        authUserId: String,
        currentTimeMillis: Long,
    ): List<EntitlementGrant> = emptyList()

    override suspend fun saveLastKnownGrants(
        authUserId: String,
        grants: List<EntitlementGrant>,
        fetchedAtMillis: Long,
    ) = Unit

    override fun cachedGrantsSnapshot(
        authUserId: String?,
        currentTimeMillis: Long,
    ): List<EntitlementGrant> = emptyList()
}

private class FakeSessionProvider(
    session: SharedTagAuthSession?,
) : SharedTagAuthSessionProvider {
    private val state = MutableStateFlow(session)
    override val session: StateFlow<SharedTagAuthSession?> = state

    override fun updateSession(newSession: SharedTagAuthSession?) {
        state.value = newSession
    }
}

private object FakeClock : AppClock {
    override fun nowEpochMillis(): Long = 5_000L
}

private class FakeTagRepository(
    private val entitlements: FeatureEntitlements = LaunchStandardPlan.entitlements,
    private val acceptInviteResult: SharedTagInviteAcceptanceResult = SharedTagInviteAcceptanceResult.AuthRequired,
) : TagRepository {
    override val isSyncAvailable: Flow<Boolean> = flowOf(false)
    override val cloudState: Flow<SharedTagCloudState> = flowOf(
        SharedTagCloudState(
            isConfigured = false,
            isSignedIn = false,
            signedInEmail = null,
        ),
    )

    override fun observeAllTagsWithCount(): Flow<List<TagWithCount>> = flowOf(emptyList())
    override fun observeTagsForEntry(entryId: Long): Flow<List<SharedTagRecord>> = flowOf(emptyList())
    override fun observeEntriesForTag(tagId: Long): Flow<List<UrlEntryEntity>> = flowOf(emptyList())
    override fun observeTag(tagId: Long): Flow<SharedTagRecord?> = flowOf(null)
    override fun observeTagByRemoteId(remoteTagId: String): Flow<SharedTagRecord?> = flowOf(null)
    override fun observeMembersForTag(tagId: Long): Flow<List<SharedTagMemberRecord>> = flowOf(emptyList())
    override fun observeUsageSummary(): Flow<UsageSummary> = flowOf(
        UsageSummary(
            personalUrlCount = 0,
            normalTagCount = 0,
            sharedTagCount = 0,
            sharedTagUsages = emptyList(),
        ),
    )

    override fun featureEntitlements(): FeatureEntitlements = entitlements

    override suspend fun createTag(name: String): Long? = null
    override suspend fun createTagWithResult(name: String): CreateTagResult = CreateTagResult.Failed
    override suspend fun createLocalTagWithResult(name: String): CreateTagResult = CreateTagResult.Failed
    override suspend fun createSyncedTagWithResult(name: String): CreateTagResult = CreateTagResult.Failed
    override suspend fun deleteTag(tagId: Long) = Unit
    override suspend fun assignTag(tagId: Long, entryId: Long) = Unit
    override suspend fun assignTagWithResult(tagId: Long, entryId: Long): AssignTagResult = AssignTagResult.Failed
    override suspend fun removeTag(tagId: Long, entryId: Long) = Unit
    override suspend fun exportTag(tagId: Long): TagSharePayload? = null
    override suspend fun importTag(payload: TagSharePayload): TagImportResult {
        return TagImportResult(
            tagId = 0L,
            tagName = "",
            created = 0,
            merged = 0,
            duplicateSkipped = 0,
            failed = 0,
            cancelled = true,
            message = "not implemented",
        )
    }

    override suspend fun migrateLocalTagToCloud(tagId: Long): MigrateSharedTagResult = MigrateSharedTagResult.Failed
    override suspend fun triggerSync(): Boolean = false
    override suspend fun triggerSyncIfStale(minIntervalMillis: Long): Boolean = false
    override suspend fun signIn(email: String, password: String): SharedTagAuthResult =
        SharedTagAuthResult.Failure("not implemented")
    override suspend fun signUp(email: String, password: String): SharedTagAuthResult =
        SharedTagAuthResult.Failure("not implemented")
    override suspend fun signOut() = Unit
    override suspend fun deleteAccount(): SharedTagAccountDeletionResult = SharedTagAccountDeletionResult.AuthRequired
    override suspend fun retryLocalAccountCleanup(): SharedTagAccountDeletionResult =
        SharedTagAccountDeletionResult.AuthRequired
    override suspend fun createInviteLink(tagId: Long): SharedTagInviteCreationResult =
        SharedTagInviteCreationResult.AuthRequired
    override suspend fun previewInvite(inviteToken: String): SharedTagInvitePreviewResult =
        SharedTagInvitePreviewResult.Success(tagName = "joined-tag")
    override suspend fun acceptInvite(inviteToken: String): SharedTagInviteAcceptanceResult =
        acceptInviteResult
    override suspend fun transferOwnership(tagId: Long, newOwnerUserId: String): SharedTagOwnershipTransferResult =
        SharedTagOwnershipTransferResult.AuthRequired
    override suspend fun leaveSharedTag(tagId: Long): Boolean = false
    override suspend fun removeMember(tagId: Long, userId: String): Boolean = false
}
