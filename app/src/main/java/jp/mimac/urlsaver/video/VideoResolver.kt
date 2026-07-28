package jp.mimac.urlsaver.video

import jp.mimac.urlsaver.data.UrlEntryEntity
import jp.mimac.urlsaver.domain.ServiceType
import org.json.JSONObject
import java.io.ByteArrayOutputStream

interface VideoResolver {
    suspend fun resolve(entry: UrlEntryEntity): VideoResolveResult
}

class BackendVideoResolver(
    private val backendBaseUrl: String,
) : VideoResolver {
    override suspend fun resolve(entry: UrlEntryEntity): VideoResolveResult {
        val provider = providerFor(entry.serviceType)
            ?: return VideoResolveResult("generic", emptyList(), "UNKNOWN", "UNAVAILABLE", "UNSUPPORTED_SERVICE")
        val baseUrl = backendBaseUrl.trim().trimEnd('/')
        if (baseUrl.isBlank()) {
            return VideoResolveResult(provider, emptyList(), "UNKNOWN", "UNAVAILABLE", "MEDIA_RESOLVER_BACKEND_UNCONFIGURED")
        }
        val payload = postJson(
            url = "$baseUrl/resolve",
            json = """
                {
                  "provider": "$provider",
                  "url": "${entry.openUrl.escapeJson()}",
                  "serviceType": "${entry.serviceType.name}"
                }
            """.trimIndent(),
        )
        val objectValue = JSONObject(payload)
        if (!objectValue.optBoolean("ok", false)) {
            return VideoResolveResult(
                provider = provider,
                assets = emptyList(),
                hasVideo = "UNKNOWN",
                resolveStatus = "FAILED",
                errorReason = mediaResolveErrorReason(
                    error = objectValue.optString("error").takeIf { it.isNotBlank() },
                    message = objectValue.optString("message").takeIf { it.isNotBlank() },
                ),
            )
        }
        val assetsArray = objectValue.optJSONArray("assets")
        val parsedAssets = (0 until (assetsArray?.length() ?: 0)).mapNotNull { index ->
            val asset = assetsArray?.optJSONObject(index) ?: return@mapNotNull null
            val downloadUrl = asset.optString("downloadUrl")
                .normalizeMediaUrl()
                .takeIf { it.startsWith("http://") || it.startsWith("https://") }
                ?: return@mapNotNull null
            val mediaType = asset.optString("mediaType").takeIf { it == "IMAGE" || it == "VIDEO" }
                ?: if (downloadUrl.substringBefore('?').endsWith(".mp4", ignoreCase = true)) "VIDEO" else "IMAGE"
            ResolvedVideoAsset(
                providerAssetId = asset.optString("providerAssetId").takeIf { it.isNotBlank() } ?: "$provider:${entry.id}:$index",
                sourceUrl = entry.openUrl,
                canonicalPostUrl = asset.optString("canonicalPostUrl").takeIf { it.isNotBlank() } ?: entry.openUrl,
                authorName = asset.optString("authorName").takeIf { it.isNotBlank() } ?: entry.fetchedAuthorName,
                title = asset.optString("title").takeIf { it.isNotBlank() } ?: entry.fetchedTitle ?: entry.userTitle,
                bodyText = entry.fetchedBody,
                thumbnailUrl = asset.optString("thumbnailUrl").normalizeMediaUrl().takeIf { it.isNotBlank() } ?: entry.thumbnailUrl,
                durationMs = asset.optLong("durationMs").takeIf { it > 0 },
                mediaType = mediaType,
                downloadUrl = downloadUrl,
                requestHeadersJson = null,
                mimeType = asset.optString("mimeType").takeIf { it.isNotBlank() } ?: if (mediaType == "VIDEO") "video/mp4" else "image/jpeg",
                qualityLabel = asset.optString("qualityLabel").takeIf { it.isNotBlank() },
                width = asset.optInt("width").takeIf { it > 0 },
                height = asset.optInt("height").takeIf { it > 0 },
                bitrate = asset.optInt("bitrate").takeIf { it > 0 },
                sortIndex = asset.optInt("sortIndex", index),
                isPreferred = asset.optBoolean("isPreferred", index == 0),
                expiresAt = asset.optLong("expiresAt").takeIf { it > 0 },
                errorReason = null,
            )
        }
        val assets = parsedAssets
            .mapIndexed { index, asset -> index to asset }
            .sortedWith(compareBy<Pair<Int, ResolvedVideoAsset>> { it.second.sortIndex }.thenBy { it.first })
            .mapIndexed { index, pair -> pair.second.copy(sortIndex = index, isPreferred = index == 0) }

        return VideoResolveResult(
            provider = provider,
            assets = assets,
            hasVideo = if (assets.any { it.mediaType == "VIDEO" }) "YES" else "NO",
            resolveStatus = if (assets.isNotEmpty()) "AVAILABLE" else "UNAVAILABLE",
            errorReason = if (assets.isEmpty()) "MEDIA_ASSET_NOT_FOUND" else null,
        )
    }

    private fun providerFor(serviceType: ServiceType): String? {
        return when (serviceType) {
            ServiceType.YOUTUBE -> "youtube"
            ServiceType.TIKTOK -> "tiktok"
            ServiceType.INSTAGRAM -> "instagram"
            else -> null
        }
    }

    private fun postJson(url: String, json: String): String {
        val response = MediaNetworkPolicy.openPostResponse(
            rawUrl = url,
            body = json.toByteArray(Charsets.UTF_8),
            connectTimeoutMillis = 20_000,
            readTimeoutMillis = 60_000,
            headers = mapOf(
                "User-Agent" to "Rinbam Android",
                "Content-Type" to "application/json; charset=UTF-8",
                "Accept" to "application/json",
            ),
        )
        return try {
            response.connection.useLimitedUtf8(MediaNetworkPolicy.MAX_RESOLVER_RESPONSE_BYTES)
        } finally {
            response.connection.disconnect()
        }
    }

    private fun String.escapeJson(): String = replace("\\", "\\\\").replace("\"", "\\\"")

}

private fun java.net.HttpURLConnection.useLimitedUtf8(maxBytes: Long): String {
    val output = ByteArrayOutputStream()
    inputStream.use { input ->
        val buffer = ByteArray(16 * 1024)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            if (total > maxBytes - read) {
                throw MediaNetworkPolicy.InvalidResponseException("MEDIA_RESOLVER_RESPONSE_TOO_LARGE")
            }
            output.write(buffer, 0, read)
            total += read
        }
    }
    return output.toByteArray().toString(Charsets.UTF_8)
}

private fun String.normalizeMediaUrl(): String {
    return replace("\\%", "%").trim()
}

internal fun mediaResolveErrorReason(error: String?, message: String?): String {
    val lowerText = listOfNotNull(error, message).joinToString(" ").lowercase()
    return when {
        error == "AUTH_REQUIRED" -> "AUTH_REQUIRED"
        "sign in" in lowerText || "cookies" in lowerText || "login" in lowerText || "not a bot" in lowerText -> "AUTH_REQUIRED"
        error != null -> error
        message != null -> message
        else -> "MEDIA_RESOLVER_FAILED"
    }
}
