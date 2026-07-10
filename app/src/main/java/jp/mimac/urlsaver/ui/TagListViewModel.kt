package jp.mimac.urlsaver.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import jp.mimac.urlsaver.data.TagRepository
import jp.mimac.urlsaver.domain.CreateSharedTagGroupResult
import jp.mimac.urlsaver.domain.CreateTagResult
import jp.mimac.urlsaver.domain.SharedTagGroupInviteCreationResult
import jp.mimac.urlsaver.domain.SharedTagGroupMemberRecord
import jp.mimac.urlsaver.domain.SharedTagGroupMutationResult
import jp.mimac.urlsaver.domain.SharedTagGroupRecord
import jp.mimac.urlsaver.domain.SharedTagGroupTagRecord
import jp.mimac.urlsaver.domain.SharedTagMemberRole
import jp.mimac.urlsaver.domain.TagWithCount
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

class TagListViewModel(
    private val tagRepository: TagRepository,
) : ViewModel() {

    val tags: StateFlow<List<TagWithCount>> = tagRepository.observeAllTagsWithCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val groups: StateFlow<List<SharedTagGroupRecord>> = tagRepository.observeGroups()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    suspend fun createSharedTag(name: String): CreateTagResult {
        return tagRepository.createSyncedTagWithResult(name)
    }

    suspend fun deleteTag(tagId: Long) {
        tagRepository.deleteTag(tagId)
    }

    suspend fun createGroup(name: String): CreateSharedTagGroupResult {
        return tagRepository.createGroupWithResult(name)
    }

    fun observeGroupMembers(groupId: Long): Flow<List<SharedTagGroupMemberRecord>> {
        return tagRepository.observeGroupMembers(groupId)
    }

    fun observeGroupTags(groupId: Long): Flow<List<SharedTagGroupTagRecord>> {
        return tagRepository.observeGroupTags(groupId)
    }

    suspend fun addTagToGroup(groupId: Long, tagId: Long): Boolean {
        return tagRepository.addTagToGroup(groupId, tagId)
    }

    suspend fun removeTagFromGroup(groupId: Long, tagId: Long): Boolean {
        return tagRepository.removeTagFromGroup(groupId, tagId)
    }

    suspend fun createGroupInviteLink(
        groupId: Long,
        role: String,
    ): SharedTagGroupInviteCreationResult {
        return tagRepository.createGroupInviteLink(groupId, role)
    }

    suspend fun renameGroup(groupId: Long, name: String): SharedTagGroupMutationResult {
        return tagRepository.renameGroup(groupId, name)
    }

    suspend fun deleteGroup(groupId: Long): SharedTagGroupMutationResult {
        return tagRepository.deleteGroup(groupId)
    }

    suspend fun changeGroupMemberRole(
        groupId: Long,
        userId: String,
        role: SharedTagMemberRole,
    ): SharedTagGroupMutationResult {
        return tagRepository.changeGroupMemberRole(groupId, userId, role)
    }

    suspend fun transferGroupOwnership(groupId: Long, userId: String): SharedTagGroupMutationResult {
        return tagRepository.transferGroupOwnership(groupId, userId)
    }

    suspend fun removeGroupMember(groupId: Long, userId: String): SharedTagGroupMutationResult {
        return tagRepository.removeGroupMember(groupId, userId)
    }
}
