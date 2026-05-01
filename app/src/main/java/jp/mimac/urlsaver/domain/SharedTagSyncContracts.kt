package jp.mimac.urlsaver.domain

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class SharedTagSyncOperation(
    @SerialName("op_id") val opId: String,
    @SerialName("client_id") val clientId: String,
    val type: SharedTagSyncOperationType,
    @SerialName("submitted_at") val submittedAt: Long,
    @SerialName("tag_id") val tagId: String? = null,
    val name: String? = null,
    @SerialName("url_id") val urlId: String? = null,
    @SerialName("raw_url") val rawUrl: String? = null,
    @SerialName("normalized_url") val normalizedUrl: String? = null,
    @SerialName("normalization_version") val normalizationVersion: Int? = null,
    @SerialName("user_id") val userId: String? = null,
    val role: String? = null,
)

@Serializable
enum class SharedTagSyncOperationType {
    @SerialName("create_tag")
    CREATE_TAG,

    @SerialName("rename_tag")
    RENAME_TAG,

    @SerialName("delete_tag")
    DELETE_TAG,

    @SerialName("add_url_to_tag")
    ADD_URL_TO_TAG,

    @SerialName("remove_url_from_tag")
    REMOVE_URL_FROM_TAG,

    @SerialName("invite_member")
    INVITE_MEMBER,

    @SerialName("change_member_role")
    CHANGE_MEMBER_ROLE,

    @SerialName("remove_member")
    REMOVE_MEMBER,
}

@Serializable
data class ApplySharedTagOpsResponse(
    val results: List<SharedTagOpApplyResult>,
)

@Serializable
data class SharedTagOpApplyResult(
    @SerialName("op_id") val opId: String,
    val status: String,
    @SerialName("tag_id") val tagId: String? = null,
    @SerialName("url_id") val urlId: String? = null,
    @SerialName("normalized_url") val normalizedUrl: String? = null,
    @SerialName("user_id") val userId: String? = null,
)

@Serializable
data class PullSharedTagSnapshotResponse(
    @SerialName("pulled_at") val pulledAt: String,
    @SerialName("normalization_version") val normalizationVersion: Int,
    val tags: List<RemoteSharedTag>,
    val members: List<RemoteSharedTagMember>,
    val urls: List<RemoteSharedTagUrl>,
)

@Serializable
data class RemoteSharedTag(
    val id: String,
    val name: String,
    @SerialName("created_by") val createdBy: String,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String,
    @SerialName("deleted_at") val deletedAt: String? = null,
    val version: Long,
)

@Serializable
data class RemoteSharedTagMember(
    @SerialName("tag_id") val tagId: String,
    @SerialName("user_id") val userId: String,
    val role: String,
    val status: String,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String,
)

@Serializable
data class RemoteSharedTagUrl(
    val id: String,
    @SerialName("tag_id") val tagId: String,
    @SerialName("raw_url") val rawUrl: String,
    @SerialName("normalized_url") val normalizedUrl: String,
    @SerialName("normalization_version") val normalizationVersion: Int,
    @SerialName("added_by") val addedBy: String,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String,
    @SerialName("deleted_at") val deletedAt: String? = null,
)

@Serializable
data class CreateSharedTagInviteResponse(
    @SerialName("tag_id") val tagId: String,
    @SerialName("invite_token") val inviteToken: String,
    @SerialName("expires_at") val expiresAt: String,
    val role: String,
)

@Serializable
data class PreviewSharedTagInviteResponse(
    @SerialName("tag_name") val tagName: String,
)

@Serializable
data class AcceptSharedTagInviteResponse(
    @SerialName("tag_id") val tagId: String,
    @SerialName("tag_name") val tagName: String,
    val role: String,
    val status: String,
)

@Serializable
data class TransferSharedTagOwnershipResponse(
    @SerialName("tag_id") val tagId: String,
    @SerialName("previous_owner_user_id") val previousOwnerUserId: String,
    @SerialName("new_owner_user_id") val newOwnerUserId: String,
)
