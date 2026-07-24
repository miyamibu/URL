package jp.mimac.urlsaver.video

import jp.mimac.urlsaver.data.UrlEntryEntity
import jp.mimac.urlsaver.domain.ServiceType
import jp.mimac.urlsaver.BuildConfig
import jp.mimac.urlsaver.worker.NetworkTargetPolicy
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

interface VideoResolver {
    suspend fun resolve(entry: UrlEntryEntity): VideoResolveResult
}

class BackendVideoResolver(
    private val backendBaseUrl: String,
) : VideoResolver {
    override suspend fun resolve(entry: UrlEntryEntity): VideoResolveResult {
        val provider = providerFor(entry.serviceType)
            ?: return VideoResolveResult("generic", emptyList(), "UNKNOWN", "UNAVAILABLE", "UNSUPPORTED_SERVICE")
        if (!BuildConfig.DEBUG) {
            return VideoResolveResult(
                provider = provider,
                assets = emptyList(),
                hasVideo = "UNKNOWN",
                resolveStatus = "UNAVAILABLE",
                errorReason = "MEDIA_RESOLVER_DISABLED_IN_RELEASE",
            )
        }
        val baseUrl = backendBaseUrl.trim().trimEnd('/')
        if (baseUrl.isBlank()) {
            return VideoResolveResult(provider, emptyList(), "UNKNOWN", "UNAVAILABLE", "MEDIA_RESOLVER_BACKEND_UNCONFIGURED")
        }
        val allowTestHosts = BuildConfig.DEBUG && BuildConfig.ALLOW_LOCAL_MEDIA_DOWNLOADS
        if (!NetworkTargetPolicy.isAllowed("$baseUrl/resolve", allowTestHosts)) {
            return VideoResolveResult(provider, emptyList(), "UNKNOWN", "UNAVAILABLE", "MEDIA_RESOLVER_TARGET_NOT_ALLOWED")
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
                ?.takeIf { NetworkTargetPolicy.isAllowed(it, allowTestHosts) }
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
        var current = URI(url)
        for (hop in 0 until 5) {
            if (!NetworkTargetPolicy.isAllowed(current.toString(), BuildConfig.DEBUG && BuildConfig.ALLOW_LOCAL_MEDIA_DOWNLOADS)) {
                error("MEDIA_RESOLVER_TARGET_NOT_ALLOWED")
            }
            val connection = (URL(current.toString()).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 20_000
                readTimeout = 60_000
                requestMethod = "POST"
                doOutput = true
                setRequestProperty("User-Agent", "Rinbam Android")
                setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                setRequestProperty("Accept", "application/json")
            }
            try {
                connection.outputStream.use { it.write(json.toByteArray(Charsets.UTF_8)) }
                val responseCode = connection.responseCode
                if (responseCode in 300..399) {
                    val location = connection.getHeaderField("Location") ?: error("MEDIA_RESOLVER_REDIRECT_INVALID")
                    current = current.resolve(location)
                    if (hop == 4) error("MEDIA_RESOLVER_TOO_MANY_REDIRECTS")
                    continue
                }
                val stream = if (responseCode in 200..299) connection.inputStream else connection.errorStream
                return stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            } finally {
                connection.disconnect()
            }
        }
        error("MEDIA_RESOLVER_TOO_MANY_REDIRECTS")
    }

    private fun String.escapeJson(): String = replace("\\", "\\\\").replace("\"", "\\\"")

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
