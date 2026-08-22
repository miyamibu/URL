package jp.mimac.urlsaver.data

import android.content.Context
import jp.mimac.urlsaver.domain.RecordState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.security.MessageDigest
import java.util.UUID

data class ChatGptPersonalLinkSyncSettings(
    val enabled: Boolean = false,
    val contentFetchEnabled: Boolean = false,
    val lastSyncedAt: Long? = null,
    val lastErrorMessage: String? = null,
)

data class ChatGptSyncEligibility(
    val entryId: Long,
    val eligible: Boolean,
    val exclusionReasons: List<String>,
)

data class ChatGptSyncEligibilitySnapshot(
    val eligibleEntries: List<UrlEntryEntity>,
    val excludedCount: Int,
    val exclusionsByReason: Map<String, Int>,
)

interface ChatGptPersonalLinkSyncSettingsStore {
    val revision: StateFlow<Long>
    fun snapshot(authUserId: String?): ChatGptPersonalLinkSyncSettings
    fun setEnabled(authUserId: String, enabled: Boolean, contentFetchEnabled: Boolean)
    fun markSyncSuccess(authUserId: String, syncedAt: Long)
    fun markSyncFailure(authUserId: String, message: String)
    fun clear(authUserId: String)
}

class SharedPreferencesChatGptPersonalLinkSyncSettingsStore(
    context: Context,
) : ChatGptPersonalLinkSyncSettingsStore {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val revisionState = MutableStateFlow(0L)

    override val revision: StateFlow<Long> = revisionState

    override fun snapshot(authUserId: String?): ChatGptPersonalLinkSyncSettings {
        val scope = accountScope(authUserId) ?: return ChatGptPersonalLinkSyncSettings()
        return read(scope)
    }

    override fun setEnabled(authUserId: String, enabled: Boolean, contentFetchEnabled: Boolean) {
        val scope = requireAccountScope(authUserId)
        commit {
            putBoolean(scopedKey(KEY_ENABLED, scope), enabled)
            putBoolean(scopedKey(KEY_CONTENT_FETCH_ENABLED, scope), enabled && contentFetchEnabled)
            remove(scopedKey(KEY_LAST_ERROR_MESSAGE, scope))
        }
    }

    override fun markSyncSuccess(authUserId: String, syncedAt: Long) {
        val scope = requireAccountScope(authUserId)
        commit {
            putLong(scopedKey(KEY_LAST_SYNCED_AT, scope), syncedAt)
            remove(scopedKey(KEY_LAST_ERROR_MESSAGE, scope))
        }
    }

    override fun markSyncFailure(authUserId: String, message: String) {
        val scope = requireAccountScope(authUserId)
        commit {
            putString(scopedKey(KEY_LAST_ERROR_MESSAGE, scope), message.take(MAX_ERROR_LENGTH))
        }
    }

    override fun clear(authUserId: String) {
        val scope = requireAccountScope(authUserId)
        commit {
            remove(scopedKey(KEY_ENABLED, scope))
            remove(scopedKey(KEY_CONTENT_FETCH_ENABLED, scope))
            remove(scopedKey(KEY_LAST_SYNCED_AT, scope))
            remove(scopedKey(KEY_LAST_ERROR_MESSAGE, scope))
        }
    }

    private fun read(scope: String): ChatGptPersonalLinkSyncSettings =
        ChatGptPersonalLinkSyncSettings(
            enabled = prefs.getBoolean(scopedKey(KEY_ENABLED, scope), false),
            contentFetchEnabled = false,
            lastSyncedAt = scopedKey(KEY_LAST_SYNCED_AT, scope).let { key ->
                if (prefs.contains(key)) prefs.getLong(key, 0L) else null
            },
            lastErrorMessage = prefs.getString(scopedKey(KEY_LAST_ERROR_MESSAGE, scope), null),
        )

    private fun commit(edit: android.content.SharedPreferences.Editor.() -> Unit) {
        val committed = prefs.edit().apply(edit).commit()
        check(committed) { "Could not persist ChatGPT personal link sync settings" }
        revisionState.value += 1L
    }

    private fun requireAccountScope(authUserId: String): String =
        requireNotNull(accountScope(authUserId)) { "authUserId must not be blank" }

    private fun accountScope(authUserId: String?): String? {
        val normalized = authUserId?.trim()?.takeIf(String::isNotEmpty) ?: return null
        return MessageDigest.getInstance("SHA-256")
            .digest(normalized.toByteArray(Charsets.UTF_8))
            .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }

    private fun scopedKey(key: String, scope: String): String = "$key.$scope"

    private companion object {
        const val PREFS_NAME = "chatgpt_personal_link_sync"
        const val KEY_ENABLED = "enabled"
        const val KEY_CONTENT_FETCH_ENABLED = "content_fetch_enabled"
        const val KEY_LAST_SYNCED_AT = "last_synced_at"
        const val KEY_LAST_ERROR_MESSAGE = "last_error_message"
        const val MAX_ERROR_LENGTH = 240
    }
}

interface ChatGptPersonalLinkRemoteDataSource {
    suspend fun setSyncEnabled(
        session: SharedTagAuthSession,
        enabled: Boolean,
        contentFetchEnabled: Boolean,
    )

    suspend fun applyOps(
        session: SharedTagAuthSession,
        operations: List<ChatGptPersonalLinkSyncOperation>,
    ): ApplyPersonalLinkOpsResponse
}

class SupabaseChatGptPersonalLinkRemoteDataSource(
    private val config: SharedTagSyncRemoteConfig,
    private val authSessionProvider: SharedTagAuthSessionProvider,
    private val authRemoteDataSource: SharedTagAuthRemoteDataSource,
    private val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    },
) : ChatGptPersonalLinkRemoteDataSource {
    override suspend fun setSyncEnabled(
        session: SharedTagAuthSession,
        enabled: Boolean,
        contentFetchEnabled: Boolean,
    ) {
        withContext(Dispatchers.IO) {
            executeRpc(
                path = "/rest/v1/rpc/set_personal_link_chatgpt_sync",
                session = session,
                requestBody = json.encodeToString(
                    SetPersonalLinkChatGptSyncPayload(
                        enabled = enabled,
                        contentFetchEnabled = false,
                    ),
                ),
            )
        }
    }

    override suspend fun applyOps(
        session: SharedTagAuthSession,
        operations: List<ChatGptPersonalLinkSyncOperation>,
    ): ApplyPersonalLinkOpsResponse {
        val response = withContext(Dispatchers.IO) {
            executeRpc(
                path = "/rest/v1/rpc/apply_personal_link_ops",
                session = session,
                requestBody = json.encodeToString(ChatGptPersonalLinkOpsPayload(ops = operations)),
            )
        }
        return json.decodeFromString(response)
    }

    private suspend fun executeRpc(
        path: String,
        session: SharedTagAuthSession,
        requestBody: String,
        allowRefresh: Boolean = true,
    ): String {
        check(config.isConfigured) { "Supabase ChatGPT personal link sync is not configured." }
        val url = URL(config.supabaseUrl.trimEnd('/') + path)
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("apikey", config.anonKey)
            setRequestProperty("Authorization", "Bearer ${session.accessToken}")
        }

        connection.outputStream.use { it.write(requestBody.toByteArray(Charsets.UTF_8)) }
        val responseCode = connection.responseCode
        val body = runCatching {
            val stream = if (responseCode in 200..299) connection.inputStream else connection.errorStream
            stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        }.getOrDefault("")
        if (responseCode == HttpURLConnection.HTTP_UNAUTHORIZED &&
            allowRefresh &&
            !session.refreshToken.isNullOrBlank()
        ) {
            val refreshedSession = authRemoteDataSource.refreshSession(requireNotNull(session.refreshToken))
            authSessionProvider.updateSession(refreshedSession)
            return executeRpc(path, refreshedSession, requestBody, allowRefresh = false)
        }
        if (responseCode !in 200..299) {
            throw IOException("Supabase ChatGPT personal link RPC failed ($responseCode)")
        }
        return body
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 15_000
        const val READ_TIMEOUT_MS = 30_000
    }
}

class ChatGptPersonalLinkSyncRepository(
    private val authSessionProvider: SharedTagAuthSessionProvider,
    private val urlEntryDao: UrlEntryDao,
    private val tagDao: TagDao,
    private val settingsStore: ChatGptPersonalLinkSyncSettingsStore,
    private val remoteDataSource: ChatGptPersonalLinkRemoteDataSource,
    private val operationEnabled: Boolean = true,
    private val accountOperationFence: AccountOperationFence = AccountOperationFence(),
) {
    val settings: Flow<ChatGptPersonalLinkSyncSettings> = combine(
        authSessionProvider.session,
        settingsStore.revision,
    ) { session, _ ->
        settingsStore.snapshot(session?.authUserId)
    }.distinctUntilChanged()
    val isOperationEnabled: Boolean get() = operationEnabled

    @Suppress("UNUSED_PARAMETER")
    suspend fun setEnabled(enabled: Boolean, contentFetchEnabled: Boolean): ChatGptSyncResult {
        if (!operationEnabled) return ChatGptSyncResult.GateOff
        return accountOperationFence.withAccountOperation(
            authUserId = { authSessionProvider.session.value?.authUserId },
            blockedResult = { ChatGptSyncResult.AuthRequired },
        ) {
            val session = authSessionProvider.session.value ?: return@withAccountOperation ChatGptSyncResult.AuthRequired
            try {
                remoteDataSource.setSyncEnabled(session, enabled, contentFetchEnabled = false)
                if (!isCurrentSession(session)) return@withAccountOperation ChatGptSyncResult.AuthRequired
                settingsStore.setEnabled(session.authUserId, enabled, contentFetchEnabled = false)
                if (enabled) {
                    syncCurrentSnapshot(session)
                } else {
                    ChatGptSyncResult.Success(targetCount = 0, excludedCount = 0, syncedCount = 0)
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                if (isCurrentSession(session)) {
                    runCatching { settingsStore.markSyncFailure(session.authUserId, SAFE_FAILURE_MESSAGE) }
                }
                ChatGptSyncResult.Failure(SAFE_FAILURE_MESSAGE)
            }
        }
    }

    suspend fun syncNow(): ChatGptSyncResult {
        if (!operationEnabled) return ChatGptSyncResult.GateOff
        return accountOperationFence.withAccountOperation(
            authUserId = { authSessionProvider.session.value?.authUserId },
            blockedResult = { ChatGptSyncResult.AuthRequired },
        ) {
            val session = authSessionProvider.session.value ?: return@withAccountOperation ChatGptSyncResult.AuthRequired
            if (!settingsStore.snapshot(session.authUserId).enabled) {
                return@withAccountOperation ChatGptSyncResult.NotEnabled
            }
            try {
                syncCurrentSnapshot(session)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                if (isCurrentSession(session)) {
                    runCatching { settingsStore.markSyncFailure(session.authUserId, SAFE_FAILURE_MESSAGE) }
                }
                ChatGptSyncResult.Failure(SAFE_FAILURE_MESSAGE)
            }
        }
    }

    suspend fun eligibilitySnapshot(): ChatGptSyncEligibilitySnapshot {
        val entries = urlEntryDao.loadAllEntries()
        val eligibility = entries.map { entry ->
            val sharedTagAllocation = tagDao.countActiveSyncedRefsForEntry(entry.id) > 0
            val reasons = buildList {
                if (entry.recordState != RecordState.ACTIVE) {
                    add("active_required")
                }
                if (entry.localProvenanceCount <= 0) add("no_local_provenance")
                if (entry.sharedReferenceCount != 0) add("shared_reference")
                if (entry.pendingDeletionUntil != null) add("pending_delete")
                if (sharedTagAllocation) add("shared_tag_allocation")
            }
            ChatGptSyncEligibility(
                entryId = entry.id,
                eligible = reasons.isEmpty(),
                exclusionReasons = reasons,
            ) to entry
        }
        val eligibleEntries = eligibility.filter { it.first.eligible }.map { it.second }
        val exclusionsByReason = eligibility
            .asSequence()
            .flatMap { (result, _) -> result.exclusionReasons.asSequence() }
            .groupingBy { it }
            .eachCount()
        return ChatGptSyncEligibilitySnapshot(
            eligibleEntries = eligibleEntries,
            excludedCount = eligibility.count { !it.first.eligible },
            exclusionsByReason = exclusionsByReason,
        )
    }

    private suspend fun syncCurrentSnapshot(session: SharedTagAuthSession): ChatGptSyncResult {
        if (!isCurrentSession(session)) return ChatGptSyncResult.AuthRequired
        val snapshot = eligibilitySnapshot()
        val operations = snapshot.eligibleEntries
            .map { entry ->
                val tags = tagDao.getLocalOnlyTagsForEntry(entry.id).map { it.name }
                entry.toChatGptOperation(tags)
            }
        if (operations.isNotEmpty()) {
            if (!isCurrentSession(session)) return ChatGptSyncResult.AuthRequired
            val response = remoteDataSource.applyOps(session, operations)
            if (!isCurrentSession(session)) return ChatGptSyncResult.AuthRequired
            val appliedCount = response.appliedCount.coerceIn(0, operations.size)
            val failedCount = (operations.size - appliedCount).coerceAtLeast(0)
            settingsStore.markSyncSuccess(session.authUserId, System.currentTimeMillis())
            if (failedCount > 0) {
                return ChatGptSyncResult.Partial(
                    targetCount = operations.size,
                    excludedCount = snapshot.excludedCount,
                    syncedCount = appliedCount,
                    failedCount = failedCount,
                )
            }
            return ChatGptSyncResult.Success(
                targetCount = operations.size,
                excludedCount = snapshot.excludedCount,
                syncedCount = appliedCount,
            )
        }
        if (!isCurrentSession(session)) return ChatGptSyncResult.AuthRequired
        settingsStore.markSyncSuccess(session.authUserId, System.currentTimeMillis())
        return ChatGptSyncResult.Success(
            targetCount = 0,
            excludedCount = snapshot.excludedCount,
            syncedCount = 0,
        )
    }

    private fun isCurrentSession(session: SharedTagAuthSession): Boolean =
        authSessionProvider.session.value?.authUserId == session.authUserId

    private fun UrlEntryEntity.toChatGptOperation(
        tags: List<String>,
    ): ChatGptPersonalLinkSyncOperation {
        return ChatGptPersonalLinkSyncOperation(
            opId = UUID.randomUUID().toString().lowercase(),
            clientEntryId = AiTransparencyPolicy.publicSafeIdForEntry(this),
            url = normalizedUrl,
            title = userTitle ?: fetchedTitle,
            memo = memo.takeIf { it.isNotBlank() },
            tags = tags,
            metadata = buildJsonObject {
                put("normalized_host", JsonPrimitive(normalizedHost))
                put("service_type", JsonPrimitive(serviceType.name.lowercase()))
            },
            extractedText = null,
            isArchived = false,
            updatedAt = Instant.ofEpochMilli(updatedAt).toString(),
        )
    }
}

sealed interface ChatGptSyncResult {
    data class Success(
        val targetCount: Int,
        val excludedCount: Int,
        val syncedCount: Int,
    ) : ChatGptSyncResult
    data class Partial(
        val targetCount: Int,
        val excludedCount: Int,
        val syncedCount: Int,
        val failedCount: Int,
    ) : ChatGptSyncResult
    data object AuthRequired : ChatGptSyncResult
    data object GateOff : ChatGptSyncResult
    data object NotEnabled : ChatGptSyncResult
    data class Failure(val message: String) : ChatGptSyncResult
}

private const val SAFE_FAILURE_MESSAGE = "外部接続を更新できませんでした"

@Serializable
private data class SetPersonalLinkChatGptSyncPayload(
    @SerialName("p_enabled") val enabled: Boolean,
    @SerialName("p_content_fetch_enabled") val contentFetchEnabled: Boolean,
)

@Serializable
private data class ChatGptPersonalLinkOpsPayload(
    val ops: List<ChatGptPersonalLinkSyncOperation>,
)

@Serializable
data class ChatGptPersonalLinkSyncOperation(
    @SerialName("op_id") val opId: String,
    @SerialName("client_entry_id") val clientEntryId: String,
    @SerialName("operation") val operation: String = "upsert_link",
    @SerialName("url") val url: String,
    @SerialName("title") val title: String? = null,
    @SerialName("memo") val memo: String? = null,
    @SerialName("tags") val tags: List<String> = emptyList(),
    @SerialName("metadata") val metadata: JsonObject = JsonObject(emptyMap()),
    @SerialName("extracted_text") val extractedText: String? = null,
    @SerialName("is_archived") val isArchived: Boolean = false,
    @SerialName("updated_at") val updatedAt: String,
)

@Serializable
data class ApplyPersonalLinkOpsResponse(
    val status: String? = null,
    val results: List<ApplyPersonalLinkOpResult> = emptyList(),
    @SerialName("applied_count") val appliedCount: Int = 0,
)

@Serializable
data class ApplyPersonalLinkOpResult(
    val status: String,
)
