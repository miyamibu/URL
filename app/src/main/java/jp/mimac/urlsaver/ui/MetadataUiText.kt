package jp.mimac.urlsaver.ui

import jp.mimac.urlsaver.domain.MetadataError
import jp.mimac.urlsaver.domain.MetadataState

data class MetadataDetailMessage(
    val title: String,
    val body: String? = null,
)

fun metadataListStatusText(state: MetadataState): String? {
    return when (state) {
        MetadataState.PENDING -> "取得中"
        MetadataState.FAILED,
        MetadataState.UNAVAILABLE,
        -> "更新できませんでした"
        MetadataState.READY -> null
    }
}

fun metadataDetailMessage(
    state: MetadataState,
    error: MetadataError?,
): MetadataDetailMessage? {
    return when (state) {
        MetadataState.PENDING -> MetadataDetailMessage(
            title = "情報を更新中です",
        )
        MetadataState.FAILED -> MetadataDetailMessage(
            title = "情報を更新できませんでした",
            body = failedReasonText(error),
        )
        MetadataState.UNAVAILABLE -> MetadataDetailMessage(
            title = "情報を更新できませんでした",
            body = unavailableReasonText(error),
        )
        MetadataState.READY -> null
    }
}

fun metadataErrorDisplay(error: MetadataError): String {
    return when (error) {
        MetadataError.TIMEOUT -> "タイムアウトしました"
        MetadataError.NETWORK_IO -> "ネットワーク通信に失敗しました"
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

private fun failedReasonText(error: MetadataError?): String? {
    return when (error) {
        MetadataError.TIMEOUT -> "時間をおいて再取得してください。"
        MetadataError.NETWORK_IO -> "通信環境を確認して再取得してください。"
        MetadataError.SCHEDULER_UNAVAILABLE -> "アプリを再起動して再取得してください。"
        MetadataError.HTTP_5XX -> "相手先サーバーの一時的な問題の可能性があります。"
        else -> null
    }
}

private fun unavailableReasonText(error: MetadataError?): String? {
    return when (error) {
        MetadataError.NON_HTML -> "このURLではページ情報を取得できません。"
        MetadataError.OVERSIZED -> "ページサイズが大きく情報取得対象外でした。"
        MetadataError.TOO_MANY_REDIRECTS -> "転送が多く情報を取得できませんでした。"
        MetadataError.HTTP_404 -> "ページが見つからないため情報を取得できませんでした。"
        MetadataError.HTTP_4XX -> "アクセス制限のため情報を取得できませんでした。"
        MetadataError.PARSE_FAILED -> "ページ形式の都合で情報を抽出できませんでした。"
        MetadataError.SCHEDULER_UNAVAILABLE -> "情報取得機能を開始できませんでした。"
        else -> null
    }
}

