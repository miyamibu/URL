package jp.mimac.urlsaver.data

import android.content.Context
import jp.mimac.urlsaver.domain.RecordState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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
    val settings: StateFlow<ChatGptPersonalLinkSyncSettings>
    fun snapshot(): ChatGptPersonalLinkSyncSettings
    fun setEnabled(enabled: Boolean, contentFetchEnabled: Boolean)
    fun markSyncSuccess(syncedAt: Long)
    fun markSyncFailure(message: String)
}

class SharedPreferencesChatGptPersonalLinkSyncSettingsStore(
    context: Context,
) : ChatGptPersonalLinkSyncSettingsStore {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val state = MutableStateFlow(read())

    override val settings: StateFlow<ChatGptPersonalLinkSyncSettings> = state

    override fun snapshot(): ChatGptPersonalLinkSyncSettings = state.value

    override fun setEnabled(enabled: Boolean, contentFetchEnabled: Boolean) {
        prefs.edit()
            .putBoolean(KEY_ENABLED, enabled)
            .putBoolean(KEY_CONTENT_FETCH_ENABLED, enabled && contentFetchEnabled)
            .remove(KEY_LAST_ERROR_MESSAGE)
            .apply()
        state.value = read()
    }

    override fun markSyncSuccess(syncedAt: Long) {
        prefs.edit()
            .putLong(KEY_LAST_SYNCED_AT, syncedAt)
            .remove(KEY_LAST_ERROR_MESSAGE)
            .apply()
        state.value = read()
    }

    override fun markSyncFailure(message: String) {
        prefs.edit()
            .putString(KEY_LAST_ERROR_MESSAGE, message.take(MAX_ERROR_LENGTH))
            .apply()
        state.value = read()
    }

    private fun read(): ChatGptPersonalLinkSyncSettings =
        ChatGptPersonalLinkSyncSettings(
            enabled = prefs.getBoolean(KEY_ENABLED, false),
            contentFetchEnabled = false,
            lastSyncedAt = if (prefs.contains(KEY_LAST_SYNCED_AT)) prefs.getLong(KEY_LAST_SYNCED_AT, 0L) else null,
            lastErrorMessage = prefs.getString(KEY_LAST_ERROR_MESSAGE, null),
        )

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
) {
    val settings: StateFlow<ChatGptPersonalLinkSyncSettings> = settingsStore.settings
    val isOperationEnabled: Boolean get() = operationEnabled

    @Suppress("UNUSED_PARAMETER")
    suspend fun setEnabled(enabled: Boolean, contentFetchEnabled: Boolean): ChatGptSyncResult {
        if (!operationEnabled) return ChatGptSyncResult.GateOff
        val session = authSessionProvider.session.value ?: return ChatGptSyncResult.AuthRequired
        return runCatching {
            remoteDataSource.setSyncEnabled(session, enabled, contentFetchEnabled = false)
            settingsStore.setEnabled(enabled, contentFetchEnabled = false)
            if (enabled) {
                syncCurrentSnapshot(session)
            } else {
                ChatGptSyncResult.Success(targetCount = 0, excludedCount = 0, syncedCount = 0)
            }
        }.getOrElse {
            settingsStore.markSyncFailure(SAFE_FAILURE_MESSAGE)
            ChatGptSyncResult.Failure(SAFE_FAILURE_MESSAGE)
        }
    }

    suspend fun syncNow(): ChatGptSyncResult {
        if (!operationEnabled) return ChatGptSyncResult.GateOff
        if (!settingsStore.snapshot().enabled) return ChatGptSyncResult.NotEnabled
        val session = authSessionProvider.session.value ?: return ChatGptSyncResult.AuthRequired
        return runCatching { syncCurrentSnapshot(session) }.getOrElse {
            settingsStore.markSyncFailure(SAFE_FAILURE_MESSAGE)
            ChatGptSyncResult.Failure(SAFE_FAILURE_MESSAGE)
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
        val snapshot = eligibilitySnapshot()
        val operations = snapshot.eligibleEntries
            .map { entry ->
                val tags = tagDao.getLocalOnlyTagsForEntry(entry.id).map { it.name }
                entry.toChatGptOperation(tags)
            }
        if (operations.isNotEmpty()) {
            val response = remoteDataSource.applyOps(session, operations)
            val appliedCount = response.appliedCount.coerceIn(0, operations.size)
            val failedCount = (operations.size - appliedCount).coerceAtLeast(0)
            settingsStore.markSyncSuccess(System.currentTimeMillis())
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
        settingsStore.markSyncSuccess(System.currentTimeMillis())
        return ChatGptSyncResult.Success(
            targetCount = 0,
            excludedCount = snapshot.excludedCount,
            syncedCount = 0,
        )
    }

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
