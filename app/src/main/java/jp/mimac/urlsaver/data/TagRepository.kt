package jp.mimac.urlsaver.data

import jp.mimac.urlsaver.domain.SharedTagAuthResult
import jp.mimac.urlsaver.domain.SharedTagAccountDeletionResult
import jp.mimac.urlsaver.domain.SharedTagCloudState
import jp.mimac.urlsaver.domain.SharedTagInviteAcceptanceResult
import jp.mimac.urlsaver.domain.SharedTagInviteCreationResult
import jp.mimac.urlsaver.domain.SharedTagInvitePreviewResult
import jp.mimac.urlsaver.domain.TagImportResult
import jp.mimac.urlsaver.domain.TagSharePayload
import jp.mimac.urlsaver.domain.SharedTagMemberRecord
import jp.mimac.urlsaver.domain.SharedTagOwnershipTransferResult
import jp.mimac.urlsaver.domain.SharedTagRecord
import jp.mimac.urlsaver.domain.TagWithCount
import jp.mimac.urlsaver.domain.AssignTagResult
import jp.mimac.urlsaver.domain.CreateTagResult
import jp.mimac.urlsaver.domain.FeatureEntitlements
import jp.mimac.urlsaver.domain.MigrateSharedTagResult
import jp.mimac.urlsaver.domain.UsageSummary
import kotlinx.coroutines.flow.Flow

interface TagRepository {
    fun observeAllTagsWithCount(): Flow<List<TagWithCount>>
    fun observeTagsForEntry(entryId: Long): Flow<List<SharedTagRecord>>
    fun observeEntriesForTag(tagId: Long): Flow<List<UrlEntryEntity>>
    fun observeTag(tagId: Long): Flow<SharedTagRecord?>
    fun observeTagByRemoteId(remoteTagId: String): Flow<SharedTagRecord?>
    fun observeMembersForTag(tagId: Long): Flow<List<SharedTagMemberRecord>>
    val isSyncAvailable: Flow<Boolean>
    val cloudState: Flow<SharedTagCloudState>
    fun observeUsageSummary(): Flow<UsageSummary>
    fun featureEntitlements(): FeatureEntitlements

    suspend fun createTag(name: String): Long?
    suspend fun createTagWithResult(name: String): CreateTagResult
    suspend fun deleteTag(tagId: Long)
    suspend fun assignTag(tagId: Long, entryId: Long)
    suspend fun assignTagWithResult(tagId: Long, entryId: Long): AssignTagResult
    suspend fun removeTag(tagId: Long, entryId: Long)
    suspend fun exportTag(tagId: Long): TagSharePayload?
    suspend fun importTag(payload: TagSharePayload): TagImportResult
    suspend fun migrateLocalTagToCloud(tagId: Long): MigrateSharedTagResult
    suspend fun triggerSync(): Boolean
    suspend fun triggerSyncIfStale(minIntervalMillis: Long = 60_000L): Boolean
    suspend fun signIn(email: String, password: String): SharedTagAuthResult
    suspend fun signUp(email: String, password: String): SharedTagAuthResult
    suspend fun signOut()
    suspend fun deleteAccount(): SharedTagAccountDeletionResult
    suspend fun createInviteLink(tagId: Long): SharedTagInviteCreationResult
    suspend fun previewInvite(inviteToken: String): SharedTagInvitePreviewResult
    suspend fun acceptInvite(inviteToken: String): SharedTagInviteAcceptanceResult
    suspend fun leaveSharedTag(tagId: Long): Boolean
    suspend fun transferOwnership(tagId: Long, newOwnerUserId: String): SharedTagOwnershipTransferResult
    suspend fun removeMember(tagId: Long, userId: String): Boolean
}
