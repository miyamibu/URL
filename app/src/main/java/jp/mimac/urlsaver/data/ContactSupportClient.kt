package jp.mimac.urlsaver.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

interface ContactSupportClient {
    suspend fun send(request: ContactSupportRequest): ContactSupportResult
}

@Serializable
data class ContactSupportRequest(
    val email: String,
    val name: String,
    val message: String,
    val platform: String,
    val appVersion: String,
    val buildType: String,
    val isSignedIn: Boolean,
    val authUserId: String? = null,
    val idempotencyKey: String = UUID.randomUUID().toString(),
)

sealed interface ContactSupportResult {
    data class Success(val requestId: String) : ContactSupportResult
    data class Failure(val message: String) : ContactSupportResult
}

class ConfiguredContactSupportClient(
    private val endpointUrl: String,
    private val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    },
) : ContactSupportClient {
    override suspend fun send(request: ContactSupportRequest): ContactSupportResult = withContext(Dispatchers.IO) {
        val trimmedEndpoint = endpointUrl.trim()
        if (trimmedEndpoint.isBlank()) {
            return@withContext ContactSupportResult.Failure("問い合わせ送信先が設定されていません")
        }

        runCatching {
            val connection = (URL(trimmedEndpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Idempotency-Key", request.idempotencyKey)
            }
            connection.outputStream.use { stream ->
                stream.write(json.encodeToString(request).toByteArray(Charsets.UTF_8))
            }

            val responseCode = connection.responseCode
            val body = runCatching {
                val stream = if (responseCode in 200..299) connection.inputStream else connection.errorStream
                stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            }.getOrDefault("")

            if (responseCode in 200..299) {
                val parsed = runCatching { json.decodeFromString<ContactSupportAcceptedResponse>(body) }.getOrNull()
                return@withContext ContactSupportResult.Success(parsed?.requestId.orEmpty())
            }

            val serverError = runCatching { json.decodeFromString<ContactSupportErrorResponse>(body).error }
                .getOrNull()
                ?.takeIf { it.isNotBlank() }
            val errorMessage = normalizeErrorMessage(responseCode, serverError)
            ContactSupportResult.Failure(errorMessage)
        }.getOrElse { error ->
            ContactSupportResult.Failure(
                when (error) {
                    is IOException -> "通信に失敗しました。接続を確認して再度お試しください"
                    else -> "問い合わせを送信できませんでした"
                },
            )
        }
    }

    @Serializable
    private data class ContactSupportAcceptedResponse(
        @SerialName("requestId")
        val requestId: String = "",
        val status: String = "",
    )

    @Serializable
    private data class ContactSupportErrorResponse(
        val error: String = "",
    )

    private companion object {
        const val CONNECT_TIMEOUT_MS = 15_000
        const val READ_TIMEOUT_MS = 30_000

        fun normalizeErrorMessage(responseCode: Int, serverError: String?): String {
            val normalized = serverError?.trim().orEmpty()
            return when {
                responseCode == 429 || normalized.equals("rate_limited", ignoreCase = true) ->
                    "短時間に問い合わせが多すぎます。少し時間をおいて再度お試しください。"
                normalized.equals("missing_required_fields", ignoreCase = true) ->
                    "メールアドレス、氏名、問い合わせ内容を入力してください。"
                normalized.equals("invalid_email", ignoreCase = true) ->
                    "メールアドレスの形式を確認してください。"
                normalized.equals("message_too_long", ignoreCase = true) ->
                    "問い合わせ内容が長すぎます。短くして再度お試しください。"
                normalized.contains("resend", ignoreCase = true) ->
                    "問い合わせを送信できませんでした。時間をおいて再度お試しください。"
                normalized.isNotBlank() && normalized.any { it.code > 127 } ->
                    normalized
                responseCode == HttpURLConnection.HTTP_BAD_REQUEST ->
                    "入力内容を確認してください。"
                responseCode == 502 ->
                    "問い合わせを送信できませんでした。時間をおいて再度お試しください。"
                else ->
                    "問い合わせを送信できませんでした。時間をおいて再度お試しください。"
            }
        }
    }
}
