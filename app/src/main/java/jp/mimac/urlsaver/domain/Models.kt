package jp.mimac.urlsaver.domain

enum class ServiceType(val displayName: String) {
    ALL("すべて"),
    YOUTUBE("YouTube"),
    TIKTOK("TikTok"),
    X("X"),
    INSTAGRAM("Instagram"),
    WEB("Webサイト"),
}

enum class ContentContext(val label: String) {
    STANDARD(""),
    VIDEO("動画"),
    SHORTS("ショート"),
    LIVE("ライブ"),
    MUSIC("音楽"),
    POST("投稿"),
    REEL("リール"),
    PROFILE("プロフィール"),
    SOUND("音源"),
    HASHTAG("ハッシュタグ"),
}

enum class RecordState {
    ACTIVE,
    ARCHIVED,
    PENDING_DELETE,
}

enum class MetadataState {
    PENDING,
    READY,
    FAILED,
    UNAVAILABLE,
}

enum class MetadataError {
    TIMEOUT,
    NETWORK_IO,
    SCHEDULER_UNAVAILABLE,
    HTTP_404,
    HTTP_4XX,
    HTTP_5XX,
    PARSE_FAILED,
    NON_HTML,
    OVERSIZED,
    TOO_MANY_REDIRECTS,
}

enum class ShareSaveResult {
    BATCH_PROCESSED,
    CREATED,
    DUPLICATE_ACTIVE,
    DUPLICATE_ARCHIVED,
    RESTORED_FROM_PENDING_DELETE,
    SAVE_FAILED,
    INVALID_URL,
    NO_URL_FOUND,
}

data class SaveRequest(
    val originalInput: String,
)

data class SaveResult(
    val result: ShareSaveResult,
    val entryId: Long? = null,
    val normalizedUrl: String? = null,
)

data class ParsedUrl(
    val originalUrl: String,
    val normalizedUrl: String,
    val displayUrl: String,
    val openUrl: String,
    val normalizedHost: String,
    val rawSourceHost: String,
    val serviceType: ServiceType,
    val contentContext: ContentContext,
)

sealed interface ShareExtractionResult {
    data class Found(val url: String) : ShareExtractionResult
    data object NoUrlFound : ShareExtractionResult
    data object InvalidUrl : ShareExtractionResult
}

enum class SnackbarEventKind {
    INFO,
    UNDO_PENDING_DELETE,
    UNDO_ARCHIVE,
    OPEN_ARCHIVE,
    OPEN_EXISTING,
    UNDO_TITLE_EDIT,
}

enum class SnackbarTargetRoute {
    ARCHIVE,
    MAIN,
}

data class SnackbarEvent(
    val kind: SnackbarEventKind,
    val message: String,
    val actionLabel: String? = null,
    val duration: androidx.compose.material3.SnackbarDuration? = null,
    val customDurationMillis: Long? = null,
    val entryId: Long? = null,
    val targetRoute: SnackbarTargetRoute? = null,
)

sealed interface DetailEffect {
    data class NavigateBackAfterPendingDelete(val entryId: Long) : DetailEffect
    data class NavigateBackAfterArchive(val entryId: Long) : DetailEffect
    data class NavigateBackAfterRestore(val entryId: Long) : DetailEffect
    data class TitleEdited(val entryId: Long, val oldTitle: String?) : DetailEffect
}

sealed interface MainNavigationEvent {
    data object NavigateToArchive : MainNavigationEvent
    data object NavigateToMain : MainNavigationEvent
    data class NavigateToDetail(val entryId: Long) : MainNavigationEvent
}
