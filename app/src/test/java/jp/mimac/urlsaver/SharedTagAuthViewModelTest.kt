package jp.mimac.urlsaver

import jp.mimac.urlsaver.data.TagRepository
import jp.mimac.urlsaver.data.UrlEntryEntity
import jp.mimac.urlsaver.data.UserProfileStore
import jp.mimac.urlsaver.domain.AssignTagResult
import jp.mimac.urlsaver.domain.CreateTagResult
import jp.mimac.urlsaver.domain.FeatureEntitlements
import jp.mimac.urlsaver.domain.LaunchStandardPlan
import jp.mimac.urlsaver.domain.MigrateSharedTagResult
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
import jp.mimac.urlsaver.ui.InviteCodeApplyResult
import jp.mimac.urlsaver.ui.SharedTagAuthViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SharedTagAuthViewModelTest {

    @Test
    fun canApplyInviteCode_blankInputIsRejected() {
        val viewModel = SharedTagAuthViewModel(
            tagRepository = FakeTagRepository(),
            userProfileStore = FakeUserProfileStore(),
        )

        assertFalse(viewModel.canApplyInviteCode("  "))
        assertTrue(viewModel.canApplyInviteCode("MIBU100"))
    }

    @Test
    fun applyInviteCode_returnsNotAvailableForNow() = runBlocking {
        val viewModel = SharedTagAuthViewModel(
            tagRepository = FakeTagRepository(),
            userProfileStore = FakeUserProfileStore(),
        )

        val result = viewModel.applyInviteCode("MIBU100")

        assertTrue(result is InviteCodeApplyResult.NotAvailable)
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

private class FakeTagRepository(
    private val entitlements: FeatureEntitlements = LaunchStandardPlan.entitlements,
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
    override suspend fun createInviteLink(tagId: Long): SharedTagInviteCreationResult =
        SharedTagInviteCreationResult.AuthRequired
    override suspend fun previewInvite(inviteToken: String): SharedTagInvitePreviewResult =
        SharedTagInvitePreviewResult.Success(tagName = "joined-tag")
    override suspend fun acceptInvite(inviteToken: String): SharedTagInviteAcceptanceResult =
        SharedTagInviteAcceptanceResult.AuthRequired
    override suspend fun transferOwnership(tagId: Long, newOwnerUserId: String): SharedTagOwnershipTransferResult =
        SharedTagOwnershipTransferResult.AuthRequired
    override suspend fun leaveSharedTag(tagId: Long): Boolean = false
    override suspend fun removeMember(tagId: Long, userId: String): Boolean = false
}
