package jp.mimac.urlsaver

import jp.mimac.urlsaver.domain.MetadataError
import jp.mimac.urlsaver.domain.MetadataState
import jp.mimac.urlsaver.ui.metadataDetailMessage
import jp.mimac.urlsaver.ui.metadataErrorDisplay
import jp.mimac.urlsaver.ui.metadataListStatusText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MetadataUiTextTest {

    @Test
    fun metadataListStatusText_usesShortLabels() {
        assertEquals("取得中", metadataListStatusText(MetadataState.PENDING))
        assertEquals("更新できませんでした", metadataListStatusText(MetadataState.FAILED))
        assertEquals("更新できませんでした", metadataListStatusText(MetadataState.UNAVAILABLE))
        assertNull(metadataListStatusText(MetadataState.READY))
    }

    @Test
    fun metadataDetailMessage_usesUserFacingMessages() {
        val pending = metadataDetailMessage(MetadataState.PENDING, null)
        assertEquals("情報を更新中です", pending?.title)
        assertNull(pending?.body)

        val failed = metadataDetailMessage(MetadataState.FAILED, MetadataError.NETWORK_IO)
        assertEquals("情報を更新できませんでした", failed?.title)
        assertEquals("通信環境を確認して再取得してください。", failed?.body)

        val schedulerUnavailable = metadataDetailMessage(MetadataState.FAILED, MetadataError.SCHEDULER_UNAVAILABLE)
        assertEquals("情報を更新できませんでした", schedulerUnavailable?.title)
        assertEquals("アプリを再起動して再取得してください。", schedulerUnavailable?.body)

        val unavailable = metadataDetailMessage(MetadataState.UNAVAILABLE, MetadataError.PARSE_FAILED)
        assertEquals("情報を更新できませんでした", unavailable?.title)
        assertEquals("ページ形式の都合で情報を抽出できませんでした。", unavailable?.body)
    }

    @Test
    fun metadataErrorDisplay_keepsFriendlyTextWithRawCodeSupport() {
        assertEquals("ページ形式を解析できませんでした", metadataErrorDisplay(MetadataError.PARSE_FAILED))
        assertEquals("ページが見つかりませんでした", metadataErrorDisplay(MetadataError.HTTP_404))
        assertEquals("リダイレクトが多すぎました", metadataErrorDisplay(MetadataError.TOO_MANY_REDIRECTS))
        assertEquals("情報取得機能を開始できませんでした", metadataErrorDisplay(MetadataError.SCHEDULER_UNAVAILABLE))
    }
}
