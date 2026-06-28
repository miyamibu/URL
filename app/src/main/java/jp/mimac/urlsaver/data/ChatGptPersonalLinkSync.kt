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
            contentFetchEnabled = prefs.getBoolean(KEY_CONTENT_FETCH_ENABLED, false),
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
                        contentFetchEnabled = contentFetchEnabled,
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
            throw IOException("Supabase ChatGPT personal link RPC failed ($responseCode): $body")
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
) {
    val settings: StateFlow<ChatGptPersonalLinkSyncSettings> = settingsStore.settings

    suspend fun setEnabled(enabled: Boolean, contentFetchEnabled: Boolean): ChatGptSyncResult {
        val session = authSessionProvider.session.value ?: return ChatGptSyncResult.AuthRequired
        return runCatching {
            remoteDataSource.setSyncEnabled(session, enabled, contentFetchEnabled)
            settingsStore.setEnabled(enabled, contentFetchEnabled)
            if (enabled) syncCurrentSnapshot(session, contentFetchEnabled)
            ChatGptSyncResult.Success
        }.getOrElse { error ->
            settingsStore.markSyncFailure(error.message ?: "ChatGPT連携を更新できませんでした")
            ChatGptSyncResult.Failure(error.message ?: "ChatGPT連携を更新できませんでした")
        }
    }

    private suspend fun syncCurrentSnapshot(
        session: SharedTagAuthSession,
        contentFetchEnabled: Boolean,
    ) {
        val operations = urlEntryDao.loadAllEntries()
            .filter { it.localProvenanceCount > 0 && it.recordState in setOf(RecordState.ACTIVE, RecordState.ARCHIVED) }
            .map { entry ->
                val tags = tagDao.getVisibleTagsForEntry(entry.id, session.authUserId).map { it.name }
                entry.toChatGptOperation(tags, contentFetchEnabled)
            }
        if (operations.isNotEmpty()) {
            remoteDataSource.applyOps(session, operations)
        }
        settingsStore.markSyncSuccess(System.currentTimeMillis())
    }

    private fun UrlEntryEntity.toChatGptOperation(
        tags: List<String>,
        contentFetchEnabled: Boolean,
    ): ChatGptPersonalLinkSyncOperation {
        return ChatGptPersonalLinkSyncOperation(
            opId = UUID.randomUUID().toString().lowercase(),
            clientEntryId = "android:$id",
            url = normalizedUrl,
            title = userTitle ?: fetchedTitle,
            memo = memo.takeIf { it.isNotBlank() },
            tags = tags,
            metadata = buildJsonObject {
                put("normalized_host", JsonPrimitive(normalizedHost))
                put("raw_source_host", JsonPrimitive(rawSourceHost))
                put("service_type", JsonPrimitive(serviceType.name.lowercase()))
                put("description", JsonPrimitive(description))
                put("thumbnail_url", JsonPrimitive(thumbnailUrl))
                put("content_fetch_allowed", JsonPrimitive(contentFetchEnabled))
            },
            extractedText = if (contentFetchEnabled) fetchedBody else null,
            isArchived = recordState == RecordState.ARCHIVED,
            updatedAt = Instant.ofEpochMilli(updatedAt).toString(),
        )
    }
}

sealed interface ChatGptSyncResult {
    data object Success : ChatGptSyncResult
    data object AuthRequired : ChatGptSyncResult
    data class Failure(val message: String) : ChatGptSyncResult
}

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
