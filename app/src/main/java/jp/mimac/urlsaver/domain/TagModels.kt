package jp.mimac.urlsaver.domain

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

data class TagWithCount(
    val id: Long,
    val name: String,
    val urlCount: Int,
    val scope: SharedTagScope = SharedTagScope.LOCAL_ONLY,
    val authUserId: String? = null,
    val remoteTagId: String? = null,
    val syncStatus: SharedTagSyncStatus = SharedTagSyncStatus.LOCAL_ONLY,
    val currentUserRole: SharedTagMemberRole? = null,
)

data class SharedTagRecord(
    val id: Long,
    val name: String,
    val scope: SharedTagScope = SharedTagScope.LOCAL_ONLY,
    val authUserId: String? = null,
    val remoteTagId: String? = null,
    val syncStatus: SharedTagSyncStatus = SharedTagSyncStatus.LOCAL_ONLY,
    val currentUserRole: SharedTagMemberRole? = null,
)

data class SharedTagMemberRecord(
    val tagId: Long,
    val userId: String,
    val role: SharedTagMemberRole,
    val status: SharedTagMemberStatus,
    val isCurrentUser: Boolean,
)

data class SharedTagGroupRecord(
    val id: Long,
    val remoteGroupId: String,
    val name: String,
    val currentUserRole: SharedTagMemberRole?,
    val tagCount: Int = 0,
    val memberCount: Int = 0,
    val lastSyncedAt: Long? = null,
)

data class SharedTagGroupMemberRecord(
    val groupId: Long,
    val userId: String,
    val displayName: String?,
    val role: SharedTagMemberRole,
    val status: SharedTagMemberStatus,
    val isCurrentUser: Boolean,
)

data class SharedTagGroupTagRecord(
    val groupId: Long,
    val tagId: Long,
    val tagName: String,
    val currentUserRole: SharedTagMemberRole?,
)

@Serializable
data class TagSharePayload(
    @SerialName("urlsaver_version") val urlsaverVersion: Int = 1,
    val tag: String,
    @SerialName("exported_at") val exportedAt: Long,
    val urls: List<TagShareUrl>,
)

@Serializable
data class TagShareUrl(
    val url: String,
    val title: String? = null,
    val memo: String? = null,
)

data class TagImportResult(
    val tagId: Long,
    val tagName: String,
    val created: Int,
    val merged: Int,
    val duplicateSkipped: Int,
    val failed: Int,
    val cancelled: Boolean = false,
    val message: String? = null,
)

sealed interface CreateTagResult {
    data class Success(val tagId: Long) : CreateTagResult
    data object InvalidName : CreateTagResult
    data object Duplicate : CreateTagResult
    data class LimitReached(val message: String) : CreateTagResult
    data object Failed : CreateTagResult
}

sealed interface CreateSharedTagGroupResult {
    data object Success : CreateSharedTagGroupResult
    data object InvalidName : CreateSharedTagGroupResult
    data object AuthRequired : CreateSharedTagGroupResult
    data class LimitReached(val message: String) : CreateSharedTagGroupResult
    data class Failed(val message: String? = null) : CreateSharedTagGroupResult
}

sealed interface SharedTagGroupMutationResult {
    data object Success : SharedTagGroupMutationResult
    data object AuthRequired : SharedTagGroupMutationResult
    data object OwnerOnly : SharedTagGroupMutationResult
    data object InvalidTarget : SharedTagGroupMutationResult
    data class Failure(val message: String? = null) : SharedTagGroupMutationResult
}

sealed interface AssignTagResult {
    data object Success : AssignTagResult
    data object AlreadyAssigned : AssignTagResult
    data class LimitReached(val message: String) : AssignTagResult
    data object Failed : AssignTagResult
}

sealed interface MigrateSharedTagResult {
    data object Success : MigrateSharedTagResult
    data object NotEligible : MigrateSharedTagResult
    data class LimitReached(val message: String) : MigrateSharedTagResult
    data object Failed : MigrateSharedTagResult
}

enum class SharedTagNameValidationError {
    BLANK,
    TOO_LONG,
}

enum class SharedTagScope {
    LOCAL_ONLY,
    SYNCED,
}

enum class SharedTagSyncStatus {
    LOCAL_ONLY,
    PENDING_PUSH,
    SYNCED,
    SYNC_ERROR,
}

enum class SharedTagMemberRole {
    OWNER,
    EDITOR,
    VIEWER,
}

enum class SharedTagMemberStatus {
    ACTIVE,
    INVITED,
    REMOVED,
}

data class SharedTagCloudState(
    val isConfigured: Boolean,
    val isSignedIn: Boolean,
    val signedInEmail: String? = null,
)

sealed interface SharedTagAuthResult {
    data class Success(val email: String?) : SharedTagAuthResult
    data object NeedsEmailConfirmation : SharedTagAuthResult
    data class Failure(val message: String) : SharedTagAuthResult
}

sealed interface SharedTagInviteCreationResult {
    data class Success(
        val inviteToken: String,
        val inviteUrl: String,
        val expiresAt: String,
    ) : SharedTagInviteCreationResult

    data object AuthRequired : SharedTagInviteCreationResult
    data object NotSharedTag : SharedTagInviteCreationResult
    data object OwnerOnly : SharedTagInviteCreationResult
    data object SyncPending : SharedTagInviteCreationResult
    data class Failure(val message: String) : SharedTagInviteCreationResult
}

sealed interface SharedTagGroupInviteCreationResult {
    data class Success(
        val inviteToken: String,
        val inviteUrl: String,
        val expiresAt: String,
    ) : SharedTagGroupInviteCreationResult

    data object AuthRequired : SharedTagGroupInviteCreationResult
    data object OwnerOnly : SharedTagGroupInviteCreationResult
    data class Failure(val message: String) : SharedTagGroupInviteCreationResult
}

sealed interface SharedTagInvitePreviewResult {
    data class Success(
        val displayName: String,
        val inviteType: SharedInviteType,
    ) : SharedTagInvitePreviewResult {
        constructor(tagName: String) : this(
            displayName = tagName,
            inviteType = SharedInviteType.TAG,
        )

        val tagName: String
            get() = displayName
    }

    data object InvalidInvite : SharedTagInvitePreviewResult
    data class Failure(val message: String) : SharedTagInvitePreviewResult
}

sealed interface SharedTagInviteAcceptanceResult {
    data class Success(
        val remoteId: String,
        val displayName: String,
        val inviteType: SharedInviteType,
    ) : SharedTagInviteAcceptanceResult {
        constructor(remoteTagId: String, tagName: String) : this(
            remoteId = remoteTagId,
            displayName = tagName,
            inviteType = SharedInviteType.TAG,
        )

        val remoteTagId: String
            get() = remoteId

        val tagName: String
            get() = displayName
    }

    data object AuthRequired : SharedTagInviteAcceptanceResult
    data object InvalidInvite : SharedTagInviteAcceptanceResult
    data class Failure(val message: String) : SharedTagInviteAcceptanceResult
}

enum class SharedInviteType {
    TAG,
    GROUP,
}

sealed interface SharedTagOwnershipTransferResult {
    data object Success : SharedTagOwnershipTransferResult
    data object AuthRequired : SharedTagOwnershipTransferResult
    data object OwnerOnly : SharedTagOwnershipTransferResult
    data object InvalidTarget : SharedTagOwnershipTransferResult
    data class Failure(val message: String) : SharedTagOwnershipTransferResult
}

sealed interface SharedTagAccountDeletionResult {
    data object Success : SharedTagAccountDeletionResult
    data object AuthRequired : SharedTagAccountDeletionResult
    data object OwnerTransferRequired : SharedTagAccountDeletionResult
    data class LocalCleanupRequired(
        val aiDataPending: Boolean,
        val sessionPending: Boolean,
    ) : SharedTagAccountDeletionResult
    data class Failure(val message: String) : SharedTagAccountDeletionResult
}

@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
val TagShareJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
    encodeDefaults = false
    explicitNulls = false
}

fun normalizeSharedTagName(raw: String): String = raw.trim()

fun validateSharedTagName(raw: String): SharedTagNameValidationError? {
    val normalized = normalizeSharedTagName(raw)
    return when {
        normalized.isBlank() -> SharedTagNameValidationError.BLANK
        normalized.length > MAX_SHARED_TAG_NAME_LENGTH -> SharedTagNameValidationError.TOO_LONG
        else -> null
    }
}

fun tryDecodeTagSharePayload(text: String): TagSharePayload? {
    val trimmed = text.trimStart()
    if (!trimmed.startsWith("{")) return null
    return runCatching {
        TagShareJson.decodeFromString<TagSharePayload>(trimmed)
    }.getOrNull()?.takeIf { it.urlsaverVersion == 1 }
}

const val MAX_SHARED_TAG_NAME_LENGTH = 50
const val SHARED_TAG_NORMALIZATION_VERSION = 1
const val SHARED_TAG_INVITE_ROLE = "editor"
