package jp.mimac.urlsaver.data

import jp.mimac.urlsaver.domain.SharedTagAuthResult
import jp.mimac.urlsaver.domain.SharedTagAccountDeletionResult
import jp.mimac.urlsaver.domain.SharedTagCloudState
import jp.mimac.urlsaver.domain.SharedTagInviteAcceptanceResult
import jp.mimac.urlsaver.domain.SharedTagInviteCreationResult
import jp.mimac.urlsaver.domain.SharedTagInvitePreviewResult
import jp.mimac.urlsaver.domain.SharedTagGroupInviteCreationResult
import jp.mimac.urlsaver.domain.SharedTagGroupMemberRecord
import jp.mimac.urlsaver.domain.SharedTagGroupMutationResult
import jp.mimac.urlsaver.domain.SharedTagGroupRecord
import jp.mimac.urlsaver.domain.SharedTagGroupTagRecord
import jp.mimac.urlsaver.domain.SharedTagMemberRole
import jp.mimac.urlsaver.domain.TagImportResult
import jp.mimac.urlsaver.domain.TagSharePayload
import jp.mimac.urlsaver.domain.SharedTagMemberRecord
import jp.mimac.urlsaver.domain.SharedTagOwnershipTransferResult
import jp.mimac.urlsaver.domain.SharedTagRecord
import jp.mimac.urlsaver.domain.TagWithCount
import jp.mimac.urlsaver.domain.AssignTagResult
import jp.mimac.urlsaver.domain.CreateTagResult
import jp.mimac.urlsaver.domain.CreateSharedTagGroupResult
import jp.mimac.urlsaver.domain.FeatureEntitlements
import jp.mimac.urlsaver.domain.MigrateSharedTagResult
import jp.mimac.urlsaver.domain.UsageSummary
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

interface TagRepository {
    fun observeAllTagsWithCount(): Flow<List<TagWithCount>>
    fun observeTagsForEntry(entryId: Long): Flow<List<SharedTagRecord>>
    fun observeEntriesForTag(tagId: Long): Flow<List<UrlEntryEntity>>
    fun observeTag(tagId: Long): Flow<SharedTagRecord?>
    fun observeTagByRemoteId(remoteTagId: String): Flow<SharedTagRecord?>
    fun observeMembersForTag(tagId: Long): Flow<List<SharedTagMemberRecord>>
    fun observeGroups(): Flow<List<SharedTagGroupRecord>> = flowOf(emptyList())
    fun observeGroupMembers(groupId: Long): Flow<List<SharedTagGroupMemberRecord>> = flowOf(emptyList())
    fun observeGroupTags(groupId: Long): Flow<List<SharedTagGroupTagRecord>> = flowOf(emptyList())
    val isSyncAvailable: Flow<Boolean>
    val cloudState: Flow<SharedTagCloudState>
    fun observeUsageSummary(): Flow<UsageSummary>
    fun featureEntitlements(): FeatureEntitlements

    suspend fun createTag(name: String): Long?
    suspend fun createTagWithResult(name: String): CreateTagResult
    suspend fun createLocalTagWithResult(name: String): CreateTagResult
    suspend fun createSyncedTagWithResult(name: String): CreateTagResult
    suspend fun findLocalTagIdByName(name: String): Long? = null
    suspend fun renameLocalTagWithResult(tagId: Long, name: String): CreateTagResult = CreateTagResult.Failed
    suspend fun deleteTag(tagId: Long)
    suspend fun assignTag(tagId: Long, entryId: Long)
    suspend fun assignTagWithResult(tagId: Long, entryId: Long): AssignTagResult
    suspend fun removeTag(tagId: Long, entryId: Long)
    suspend fun exportTag(tagId: Long): TagSharePayload?
    suspend fun importTag(payload: TagSharePayload): TagImportResult
    suspend fun migrateLocalTagToCloud(tagId: Long): MigrateSharedTagResult
    suspend fun triggerSync(): Boolean
    suspend fun triggerSyncIfStale(minIntervalMillis: Long = 60_000L): Boolean
    fun googleOAuthUrl(): String? = null
    suspend fun handleOAuthCallback(callbackUrl: String): SharedTagAuthResult =
        SharedTagAuthResult.Failure("Googleサインインを開始できませんでした")
    suspend fun signIn(email: String, password: String): SharedTagAuthResult
    suspend fun signUp(email: String, password: String): SharedTagAuthResult
    suspend fun resendEmailConfirmation(email: String): SharedTagAuthResult =
        SharedTagAuthResult.Failure("確認メールを再送できませんでした")
    suspend fun sendPasswordRecovery(email: String): SharedTagAuthResult =
        SharedTagAuthResult.Failure("パスワード再設定メールを送信できませんでした")
    suspend fun signOut()
    suspend fun deleteAccount(): SharedTagAccountDeletionResult
    suspend fun retryLocalAccountCleanup(): SharedTagAccountDeletionResult
    suspend fun createInviteLink(tagId: Long): SharedTagInviteCreationResult
    suspend fun createGroup(name: String): Boolean = false
    suspend fun createGroupWithResult(name: String): CreateSharedTagGroupResult =
        if (createGroup(name)) CreateSharedTagGroupResult.Success else CreateSharedTagGroupResult.Failed()
    suspend fun addTagToGroup(groupId: Long, tagId: Long): Boolean = false
    suspend fun removeTagFromGroup(groupId: Long, tagId: Long): Boolean = false
    suspend fun createGroupInviteLink(groupId: Long, role: String): SharedTagGroupInviteCreationResult =
        SharedTagGroupInviteCreationResult.Failure("グループ招待リンクを作成できませんでした")
    suspend fun renameGroup(groupId: Long, name: String): SharedTagGroupMutationResult =
        SharedTagGroupMutationResult.Failure("グループ名を変更できませんでした")
    suspend fun deleteGroup(groupId: Long): SharedTagGroupMutationResult =
        SharedTagGroupMutationResult.Failure("グループを削除できませんでした")
    suspend fun changeGroupMemberRole(
        groupId: Long,
        userId: String,
        role: SharedTagMemberRole,
    ): SharedTagGroupMutationResult = SharedTagGroupMutationResult.Failure("メンバー権限を変更できませんでした")
    suspend fun transferGroupOwnership(groupId: Long, newOwnerUserId: String): SharedTagGroupMutationResult =
        SharedTagGroupMutationResult.Failure("グループオーナーを移譲できませんでした")
    suspend fun removeGroupMember(groupId: Long, userId: String): SharedTagGroupMutationResult =
        SharedTagGroupMutationResult.Failure("メンバーを削除できませんでした")
    suspend fun syncSharedProfileDisplayName(displayName: String): Boolean = false
    suspend fun previewInvite(inviteToken: String): SharedTagInvitePreviewResult
    suspend fun acceptInvite(inviteToken: String): SharedTagInviteAcceptanceResult
    suspend fun transferOwnership(tagId: Long, newOwnerUserId: String): SharedTagOwnershipTransferResult
    suspend fun leaveSharedTag(tagId: Long): Boolean
    suspend fun removeMember(tagId: Long, userId: String): Boolean
}
