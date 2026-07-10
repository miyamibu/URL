package jp.mimac.urlsaver.video

data class VideoResolveResult(
    val provider: String,
    val assets: List<ResolvedVideoAsset>,
    val hasVideo: String,
    val resolveStatus: String,
    val errorReason: String?,
)

data class ResolvedVideoAsset(
    val providerAssetId: String,
    val sourceUrl: String,
    val canonicalPostUrl: String?,
    val authorName: String?,
    val title: String?,
    val bodyText: String?,
    val thumbnailUrl: String?,
    val durationMs: Long?,
    val mediaType: String,
    val downloadUrl: String?,
    val requestHeadersJson: String?,
    val mimeType: String?,
    val qualityLabel: String?,
    val width: Int?,
    val height: Int?,
    val bitrate: Int?,
    val sortIndex: Int,
    val isPreferred: Boolean,
    val expiresAt: Long?,
    val errorReason: String?,
)
