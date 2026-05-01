package jp.mimac.urlsaver.data

import jp.mimac.urlsaver.domain.ApplySharedTagOpsResponse
import jp.mimac.urlsaver.domain.AcceptSharedTagInviteResponse
import jp.mimac.urlsaver.domain.CreateSharedTagInviteResponse
import jp.mimac.urlsaver.domain.PullSharedTagSnapshotResponse
import jp.mimac.urlsaver.domain.SharedTagSyncOperation
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

interface SharedTagSyncRemoteDataSource {
    suspend fun applyOps(
        session: SharedTagAuthSession,
        operations: List<SharedTagSyncOperation>,
    ): ApplySharedTagOpsResponse

    suspend fun pullSnapshot(session: SharedTagAuthSession): PullSharedTagSnapshotResponse

    suspend fun createInvite(
        session: SharedTagAuthSession,
        remoteTagId: String,
        role: String,
    ): CreateSharedTagInviteResponse

    suspend fun acceptInvite(
        session: SharedTagAuthSession,
        inviteToken: String,
    ): AcceptSharedTagInviteResponse

    suspend fun deleteAccount(
        session: SharedTagAuthSession,
    )
}

data class SharedTagSyncRemoteConfig(
    val enabled: Boolean,
    val supabaseUrl: String,
    val anonKey: String,
    val inviteLinkBaseUrl: String = DEFAULT_INVITE_LINK_BASE_URL,
) {
    val isConfigured: Boolean
        get() = enabled && supabaseUrl.isNotBlank() && anonKey.isNotBlank()

    val normalizedInviteLinkBaseUrl: String
        get() = inviteLinkBaseUrl.trim().trimEnd('/').ifBlank { DEFAULT_INVITE_LINK_BASE_URL }

    companion object {
        const val DEFAULT_INVITE_LINK_BASE_URL = "https://urlsaver.app"
    }
}

class SupabaseSharedTagSyncRemoteDataSource(
    private val config: SharedTagSyncRemoteConfig,
    private val authSessionProvider: SharedTagAuthSessionProvider,
    private val authRemoteDataSource: SharedTagAuthRemoteDataSource,
    private val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    },
) : SharedTagSyncRemoteDataSource {
    override suspend fun applyOps(
        session: SharedTagAuthSession,
        operations: List<SharedTagSyncOperation>,
    ): ApplySharedTagOpsResponse {
        val payload = json.encodeToString(mapOf("payload" to operations))
        val response = withContext(Dispatchers.IO) {
            executeRpc(
                path = "/rest/v1/rpc/apply_shared_tag_ops",
                session = session,
                requestBody = payload,
            )
        }
        return json.decodeFromString(response)
    }

    override suspend fun pullSnapshot(session: SharedTagAuthSession): PullSharedTagSnapshotResponse {
        val response = withContext(Dispatchers.IO) {
            executeRpc(
                path = "/rest/v1/rpc/pull_shared_tag_snapshot",
                session = session,
                requestBody = "{}",
            )
        }
        return json.decodeFromString(response)
    }

    override suspend fun createInvite(
        session: SharedTagAuthSession,
        remoteTagId: String,
        role: String,
    ): CreateSharedTagInviteResponse {
        val response = withContext(Dispatchers.IO) {
            executeRpc(
                path = "/rest/v1/rpc/create_shared_tag_invite",
                session = session,
                requestBody = json.encodeToString(
                    mapOf(
                        "p_tag_id" to remoteTagId,
                        "p_role" to role,
                    ),
                ),
            )
        }
        return json.decodeFromString(response)
    }

    override suspend fun acceptInvite(
        session: SharedTagAuthSession,
        inviteToken: String,
    ): AcceptSharedTagInviteResponse {
        val response = withContext(Dispatchers.IO) {
            executeRpc(
                path = "/rest/v1/rpc/accept_shared_tag_invite",
                session = session,
                requestBody = json.encodeToString(
                    mapOf("p_token" to inviteToken),
                ),
            )
        }
        return json.decodeFromString(response)
    }

    override suspend fun deleteAccount(session: SharedTagAuthSession) {
        withContext(Dispatchers.IO) {
            executeRpc(
                path = "/rest/v1/rpc/delete_my_account",
                session = session,
                requestBody = "{}",
            )
        }
    }

    private suspend fun executeRpc(
        path: String,
        session: SharedTagAuthSession,
        requestBody: String,
        allowRefresh: Boolean = true,
    ): String {
        check(config.isConfigured) { "Supabase shared tag sync is not configured." }
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
            return executeRpc(
                path = path,
                session = refreshedSession,
                requestBody = requestBody,
                allowRefresh = false,
            )
        }
        if (responseCode !in 200..299) {
            throw IOException("Supabase RPC failed ($responseCode): $body")
        }
        return body
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 15_000
        const val READ_TIMEOUT_MS = 30_000
    }
}
