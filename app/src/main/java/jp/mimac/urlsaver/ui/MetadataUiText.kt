package jp.mimac.urlsaver.ui

import jp.mimac.urlsaver.domain.MetadataError
import jp.mimac.urlsaver.domain.MetadataBodyKind
import jp.mimac.urlsaver.domain.MetadataState
import jp.mimac.urlsaver.domain.ServiceType
import jp.mimac.urlsaver.domain.UrlRules

data class MetadataDetailMessage(
    val title: String,
    val body: String? = null,
)

const val METADATA_PENDING_DELAY_THRESHOLD_MILLIS = 15 * 60 * 1000L

fun metadataListStatusText(
    state: MetadataState,
    error: MetadataError? = null,
    serviceType: ServiceType? = null,
): String? {
    return when (state) {
        MetadataState.PENDING -> "取得中"
        MetadataState.FAILED -> "一時的に取得できません"
        MetadataState.UNAVAILABLE -> if (isLikelyServiceRestriction(error, serviceType)) {
            "自動取得に制限あり"
        } else {
            "自動取得できません"
        }
        MetadataState.READY -> null
    }
}

fun metadataDetailMessage(
    state: MetadataState,
    error: MetadataError?,
    isPendingDelayed: Boolean = false,
    serviceType: ServiceType? = null,
): MetadataDetailMessage? {
    return when (state) {
        MetadataState.PENDING -> if (isPendingDelayed) {
            MetadataDetailMessage(
                title = "情報の更新に時間がかかっています",
                body = "通信状況や端末状態で遅れることがあります。時間をおいて再取得してください。",
            )
        } else {
            MetadataDetailMessage(
                title = "情報を更新中です",
            )
        }
        MetadataState.FAILED -> MetadataDetailMessage(
            title = "一時的に情報を取得できませんでした",
            body = failedReasonText(error),
        )
        MetadataState.UNAVAILABLE -> MetadataDetailMessage(
            title = if (error == MetadataError.OVERSIZED) {
                "URLを保存しました"
            } else if (isLikelyServiceRestriction(error, serviceType)) {
                "${serviceLabelForRestriction(serviceType)}では自動取得に制限があります"
            } else {
                "このURLは自動取得できません"
            },
            body = unavailableReasonText(error, serviceType),
        )
        MetadataState.READY -> null
    }
}

fun metadataErrorDisplay(error: MetadataError): String {
    return when (error) {
        MetadataError.TIMEOUT -> "タイムアウトしました"
        MetadataError.NETWORK_IO -> "ネットワーク通信に失敗しました"
        MetadataError.UNSUPPORTED_SCHEME -> "このURL形式は情報取得対象外です"
        MetadataError.SCHEDULER_UNAVAILABLE -> "情報取得機能を開始できませんでした"
        MetadataError.HTTP_404 -> "ページが見つかりませんでした"
        MetadataError.HTTP_4XX -> "アクセスできないページでした"
        MetadataError.HTTP_5XX -> "サーバーエラーが発生しました"
        MetadataError.PARSE_FAILED -> "ページ形式を解析できませんでした"
        MetadataError.NON_HTML -> "ページ情報を取得できない形式でした"
        MetadataError.OVERSIZED -> "ページサイズが大きすぎました"
        MetadataError.TOO_MANY_REDIRECTS -> "リダイレクトが多すぎました"
    }
}

fun metadataBodyUnavailableMessage(serviceType: ServiceType): String {
    return when (serviceType) {
        ServiceType.YOUTUBE -> "YouTubeの投稿内容を公開していない動画では、内容を表示できない場合があります。"
        ServiceType.INSTAGRAM -> "Instagramの公開範囲やページ構成により、投稿内容を取得できない場合があります。"
        ServiceType.X -> "公開状態や制限により、投稿内容を取得できない場合があります。"
        ServiceType.TIKTOK,
        -> "公開状態や制限により、投稿内容を取得できない場合があります。"
        ServiceType.WEB,
        ServiceType.ALL,
        -> "このページでは概要または本文抜粋を抽出できませんでした。"
    }
}

fun metadataSummaryUnavailableMessage(): String {
    return "本文が取得できなかったため、要点を表示できませんでした。"
}

fun metadataReadyWithoutContentMessage(serviceType: ServiceType): MetadataDetailMessage {
    return MetadataDetailMessage(
        title = metadataUnavailableTitle(serviceType),
        body = metadataBodyUnavailableMessage(serviceType),
    )
}

fun metadataSummarySectionLabel(
    serviceType: ServiceType,
    fetchedBody: String?,
    fetchedBodyKind: MetadataBodyKind? = null,
): String {
    return when (serviceType) {
        ServiceType.YOUTUBE,
        ServiceType.INSTAGRAM,
        ServiceType.X,
        ServiceType.TIKTOK,
        -> "投稿内容"
        ServiceType.WEB,
        ServiceType.ALL,
        -> when (resolveWebBodyKind(fetchedBodyKind, fetchedBody)) {
            MetadataBodyKind.WEB_DESCRIPTION -> "概要"
            MetadataBodyKind.WEB_EXCERPT -> "本文抜粋の要点"
            else -> "概要"
        }
    }
}

fun metadataBodySectionLabel(
    serviceType: ServiceType,
    fetchedBody: String?,
    fetchedBodyKind: MetadataBodyKind? = null,
): String {
    return when (serviceType) {
        ServiceType.YOUTUBE,
        ServiceType.INSTAGRAM,
        ServiceType.X,
        ServiceType.TIKTOK,
        -> "投稿内容"
        ServiceType.WEB,
        ServiceType.ALL,
        -> when (resolveWebBodyKind(fetchedBodyKind, fetchedBody)) {
            MetadataBodyKind.WEB_DESCRIPTION -> "概要"
            MetadataBodyKind.WEB_EXCERPT -> "本文抜粋"
            else -> "概要"
        }
    }
}

fun preferredDisplayTitle(
    userTitle: String?,
    fetchedTitle: String?,
    serviceType: ServiceType,
    normalizedHost: String,
    bodySummary: String?,
    fetchedBody: String?,
    description: String?,
): String {
    if (!userTitle.isNullOrBlank()) return userTitle
    if (UrlRules.isTextCardHost(normalizedHost)) {
        return fetchedTitle?.takeIf { it.isNotBlank() }
            ?: preferredMetadataContentText(
                fetchedBody = fetchedBody,
                bodySummary = bodySummary,
                description = description,
            )?.let(UrlRules::textCardTitle)
            ?: "テキスト"
    }

    if (serviceType == ServiceType.X || serviceType == ServiceType.INSTAGRAM || serviceType == ServiceType.TIKTOK) {
        preferredMetadataContentText(
            fetchedBody = fetchedBody,
            bodySummary = bodySummary,
            description = description,
        )?.let { return it }
    }

    return UrlRules.effectiveTitle(
        userTitle = userTitle,
        fetchedTitle = fetchedTitle,
        serviceType = serviceType,
        normalizedHost = normalizedHost,
    )
}

fun preferredMetadataContentText(
    fetchedBody: String?,
    bodySummary: String?,
    description: String?,
): String? {
    return firstNonBlankTrimmed(fetchedBody, bodySummary, description)
}

fun isReadyWithoutFetchedContent(
    state: MetadataState,
    bodySummary: String?,
    fetchedBody: String?,
): Boolean {
    return state == MetadataState.READY &&
        bodySummary.isNullOrBlank() &&
        fetchedBody.isNullOrBlank()
}

fun shouldHideBodyAsDuplicateSummary(
    bodySummary: String?,
    fetchedBody: String?,
): Boolean {
    val summary = normalizeForComparison(bodySummary) ?: return false
    val body = normalizeForComparison(fetchedBody) ?: return false
    if (summary == body) return true

    val summaryWithoutEllipsis = summary.removeSuffix("…").trim()
    if (summaryWithoutEllipsis.isBlank()) return false
    return body.startsWith(summaryWithoutEllipsis)
}

private fun failedReasonText(error: MetadataError?): String? {
    return when (error) {
        MetadataError.TIMEOUT -> "通信が混み合っている可能性があります。時間をおいて再取得してください。"
        MetadataError.NETWORK_IO -> "通信環境を確認して再取得してください。"
        MetadataError.SCHEDULER_UNAVAILABLE -> "端末側で処理を開始できませんでした。アプリを再起動して再取得してください。"
        MetadataError.HTTP_5XX -> "相手先サーバーの一時的な問題の可能性があります。時間をおいて再取得してください。"
        else -> "時間をおいて再取得してください。"
    }
}

private fun unavailableReasonText(error: MetadataError?, serviceType: ServiceType?): String? {
    return when (error) {
        MetadataError.UNSUPPORTED_SCHEME -> "このURL形式は自動取得対象外です。"
        MetadataError.NON_HTML -> if (isMajorSocialService(serviceType)) {
            "${serviceLabelForRestriction(serviceType)}の共有ページは、情報を公開していない場合があります。"
        } else {
            "このURLはHTMLページではないため、自動取得できません。"
        }
        MetadataError.OVERSIZED -> "ページが大きいため、内容の自動取得はできませんでした。"
        MetadataError.TOO_MANY_REDIRECTS -> "転送が多く、情報取得を完了できませんでした。"
        MetadataError.HTTP_404 -> "ページが見つからないため、自動取得できませんでした。"
        MetadataError.HTTP_4XX -> if (isLikelyServiceRestriction(error, serviceType)) {
            "${serviceLabelForRestriction(serviceType)}側のアクセス制限により、自動取得できない場合があります。"
        } else {
            "アクセス制限のため、自動取得できませんでした。"
        }
        MetadataError.PARSE_FAILED -> if (isMajorSocialService(serviceType)) {
            "${serviceLabelForRestriction(serviceType)}のページ構成上、自動取得できない場合があります。"
        } else {
            "ページ形式の都合で情報を抽出できませんでした。"
        }
        MetadataError.SCHEDULER_UNAVAILABLE -> "情報取得機能を開始できませんでした。"
        else -> null
    }
}

private fun isLikelyServiceRestriction(error: MetadataError?, serviceType: ServiceType?): Boolean {
    if (!isMajorSocialService(serviceType)) return false
    return error in setOf(
        MetadataError.HTTP_4XX,
        MetadataError.PARSE_FAILED,
        MetadataError.NON_HTML,
        MetadataError.TOO_MANY_REDIRECTS,
    )
}

private fun isMajorSocialService(serviceType: ServiceType?): Boolean {
    return serviceType in setOf(
        ServiceType.YOUTUBE,
        ServiceType.X,
        ServiceType.INSTAGRAM,
    )
}

private fun serviceLabelForRestriction(serviceType: ServiceType?): String {
    return when (serviceType) {
        ServiceType.YOUTUBE -> "YouTube"
        ServiceType.X -> "X"
        ServiceType.INSTAGRAM -> "Instagram"
        ServiceType.TIKTOK -> "このサイト"
        ServiceType.WEB,
        ServiceType.ALL,
        null,
        -> "このサイト"
    }
}

private fun metadataUnavailableTitle(serviceType: ServiceType): String {
    return when (serviceType) {
        ServiceType.YOUTUBE,
        ServiceType.INSTAGRAM,
        ServiceType.X,
        ServiceType.TIKTOK,
        -> "投稿内容を取得できませんでした"
        ServiceType.WEB,
        ServiceType.ALL,
        -> "概要を取得できませんでした"
    }
}

private fun looksLikeWebDescription(fetchedBody: String?): Boolean {
    val body = fetchedBody?.trim()?.takeIf { it.isNotBlank() } ?: return true
    if (body.contains("\n\n")) return false
    return body.length <= 280
}

private fun resolveWebBodyKind(
    fetchedBodyKind: MetadataBodyKind?,
    fetchedBody: String?,
): MetadataBodyKind? {
    return when (fetchedBodyKind) {
        MetadataBodyKind.WEB_DESCRIPTION,
        MetadataBodyKind.WEB_EXCERPT,
        -> fetchedBodyKind
        else -> if (looksLikeWebDescription(fetchedBody)) {
            MetadataBodyKind.WEB_DESCRIPTION
        } else {
            MetadataBodyKind.WEB_EXCERPT
        }
    }
}

private fun normalizeForComparison(value: String?): String? {
    return value
        ?.replace('\u00A0', ' ')
        ?.replace(Regex("\\s+"), " ")
        ?.trim()
        ?.takeIf { it.isNotBlank() }
}

private fun firstNonBlankTrimmed(vararg values: String?): String? {
    return values.firstNotNullOfOrNull { value ->
        value?.trim()?.takeIf { it.isNotBlank() }
    }
}

fun isDelayedPendingMetadata(
    state: MetadataState,
    metadataRequestedAt: Long?,
    nowEpochMillis: Long,
    gracePeriodMillis: Long = METADATA_PENDING_DELAY_THRESHOLD_MILLIS,
): Boolean {
    if (state != MetadataState.PENDING) return false
    val requestedAt = metadataRequestedAt ?: return false
    return nowEpochMillis - requestedAt >= gracePeriodMillis
}
