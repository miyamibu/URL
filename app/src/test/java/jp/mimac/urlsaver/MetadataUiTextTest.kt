package jp.mimac.urlsaver

import jp.mimac.urlsaver.domain.MetadataError
import jp.mimac.urlsaver.domain.MetadataBodyKind
import jp.mimac.urlsaver.domain.MetadataState
import jp.mimac.urlsaver.domain.ServiceType
import jp.mimac.urlsaver.ui.isDelayedPendingMetadata
import jp.mimac.urlsaver.ui.isReadyWithoutFetchedContent
import jp.mimac.urlsaver.ui.metadataBodyUnavailableMessage
import jp.mimac.urlsaver.ui.metadataBodySectionLabel
import jp.mimac.urlsaver.ui.metadataDetailMessage
import jp.mimac.urlsaver.ui.metadataErrorDisplay
import jp.mimac.urlsaver.ui.metadataListStatusText
import jp.mimac.urlsaver.ui.metadataReadyWithoutContentMessage
import jp.mimac.urlsaver.ui.metadataSummarySectionLabel
import jp.mimac.urlsaver.ui.metadataSummaryUnavailableMessage
import jp.mimac.urlsaver.ui.preferredDisplayTitle
import jp.mimac.urlsaver.ui.shouldHideBodyAsDuplicateSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MetadataUiTextTest {

    @Test
    fun preferredDisplayTitle_prefersXPostContentOverAuthorName() {
        val title = preferredDisplayTitle(
            userTitle = null,
            fetchedTitle = "OpenAI",
            serviceType = ServiceType.X,
            normalizedHost = "x.com",
            bodySummary = "要点",
            fetchedBody = "これは投稿本文です",
            description = "説明文",
        )

        assertEquals("これは投稿本文です", title)
    }

    @Test
    fun preferredDisplayTitle_keepsUserTitleAsHighestPriority() {
        val title = preferredDisplayTitle(
            userTitle = "自分で付けたタイトル",
            fetchedTitle = "OpenAI",
            serviceType = ServiceType.X,
            normalizedHost = "x.com",
            bodySummary = "要点",
            fetchedBody = "これは投稿本文です",
            description = "説明文",
        )

        assertEquals("自分で付けたタイトル", title)
    }

    @Test
    fun preferredDisplayTitle_prefersInstagramCaptionOverAuthorName() {
        val title = preferredDisplayTitle(
            userTitle = null,
            fetchedTitle = "author_name",
            serviceType = ServiceType.INSTAGRAM,
            normalizedHost = "instagram.com",
            bodySummary = "短い要点",
            fetchedBody = "これはInstagramの投稿内容です",
            description = "説明文",
        )

        assertEquals("これはInstagramの投稿内容です", title)
    }

    @Test
    fun preferredDisplayTitle_prefersTikTokPostContentOverAuthorName() {
        val title = preferredDisplayTitle(
            userTitle = null,
            fetchedTitle = "creator",
            serviceType = ServiceType.TIKTOK,
            normalizedHost = "tiktok.com",
            bodySummary = "短い要点",
            fetchedBody = "これはTikTokの投稿内容です",
            description = "説明文",
        )

        assertEquals("これはTikTokの投稿内容です", title)
    }

    @Test
    fun metadataListStatusText_splitsRetryableAndUnavailable() {
        assertEquals("取得中", metadataListStatusText(MetadataState.PENDING))
        assertEquals("一時的に取得できません", metadataListStatusText(MetadataState.FAILED))
        assertEquals("自動取得できません", metadataListStatusText(MetadataState.UNAVAILABLE))
        assertEquals(
            "自動取得に制限あり",
            metadataListStatusText(
                state = MetadataState.UNAVAILABLE,
                error = MetadataError.HTTP_4XX,
                serviceType = ServiceType.X,
            ),
        )
        assertNull(metadataListStatusText(MetadataState.READY))
    }

    @Test
    fun metadataDetailMessage_usesUserFacingMessages() {
        val pending = metadataDetailMessage(MetadataState.PENDING, null, isPendingDelayed = false)
        assertEquals("情報を更新中です", pending?.title)
        assertNull(pending?.body)

        val delayedPending = metadataDetailMessage(MetadataState.PENDING, null, isPendingDelayed = true)
        assertEquals("情報の更新に時間がかかっています", delayedPending?.title)
        assertEquals("通信状況や端末状態で遅れることがあります。時間をおいて再取得してください。", delayedPending?.body)

        val failed = metadataDetailMessage(MetadataState.FAILED, MetadataError.NETWORK_IO, isPendingDelayed = false)
        assertEquals("一時的に情報を取得できませんでした", failed?.title)
        assertEquals("通信環境を確認して再取得してください。", failed?.body)

        val schedulerUnavailable = metadataDetailMessage(
            MetadataState.FAILED,
            MetadataError.SCHEDULER_UNAVAILABLE,
            isPendingDelayed = false,
        )
        assertEquals("一時的に情報を取得できませんでした", schedulerUnavailable?.title)
        assertEquals("端末側で処理を開始できませんでした。アプリを再起動して再取得してください。", schedulerUnavailable?.body)

        val unavailable = metadataDetailMessage(
            MetadataState.UNAVAILABLE,
            MetadataError.PARSE_FAILED,
            isPendingDelayed = false,
        )
        assertEquals("このURLは自動取得できません", unavailable?.title)
        assertEquals("ページ形式の都合で情報を抽出できませんでした。", unavailable?.body)

        val socialRestricted = metadataDetailMessage(
            state = MetadataState.UNAVAILABLE,
            error = MetadataError.HTTP_4XX,
            isPendingDelayed = false,
            serviceType = ServiceType.INSTAGRAM,
        )
        assertEquals("Instagramでは自動取得に制限があります", socialRestricted?.title)
        assertEquals("Instagram側のアクセス制限により、自動取得できない場合があります。", socialRestricted?.body)

        val oversized = metadataDetailMessage(
            MetadataState.UNAVAILABLE,
            MetadataError.OVERSIZED,
            isPendingDelayed = false,
        )
        assertEquals("URLを保存しました", oversized?.title)
        assertEquals("ページが大きいため、内容の自動取得はできませんでした。", oversized?.body)
    }

    @Test
    fun metadataErrorDisplay_keepsFriendlyTextWithRawCodeSupport() {
        assertEquals("ページ形式を解析できませんでした", metadataErrorDisplay(MetadataError.PARSE_FAILED))
        assertEquals("ページが見つかりませんでした", metadataErrorDisplay(MetadataError.HTTP_404))
        assertEquals("リダイレクトが多すぎました", metadataErrorDisplay(MetadataError.TOO_MANY_REDIRECTS))
        assertEquals("情報取得機能を開始できませんでした", metadataErrorDisplay(MetadataError.SCHEDULER_UNAVAILABLE))
        assertEquals("このURL形式は情報取得対象外です", metadataErrorDisplay(MetadataError.UNSUPPORTED_SCHEME))
    }

    @Test
    fun isDelayedPendingMetadata_usesRequestedTimestampOnlyForPending() {
        assertEquals(
            true,
            isDelayedPendingMetadata(
                state = MetadataState.PENDING,
                metadataRequestedAt = 1_000L,
                nowEpochMillis = 1_000L + 15 * 60 * 1000L,
            ),
        )
        assertEquals(
            false,
            isDelayedPendingMetadata(
                state = MetadataState.PENDING,
                metadataRequestedAt = null,
                nowEpochMillis = Long.MAX_VALUE,
            ),
        )
        assertEquals(
            false,
            isDelayedPendingMetadata(
                state = MetadataState.READY,
                metadataRequestedAt = 1_000L,
                nowEpochMillis = Long.MAX_VALUE,
            ),
        )
    }

    @Test
    fun metadataContentUnavailableText_isServiceAware() {
        assertEquals(
            "YouTubeの投稿内容を公開していない動画では、内容を表示できない場合があります。",
            metadataBodyUnavailableMessage(ServiceType.YOUTUBE),
        )
        assertEquals(
            "Instagramの公開範囲やページ構成により、投稿内容を取得できない場合があります。",
            metadataBodyUnavailableMessage(ServiceType.INSTAGRAM),
        )
        assertEquals(
            "本文が取得できなかったため、要点を表示できませんでした。",
            metadataSummaryUnavailableMessage(),
        )
    }

    @Test
    fun readyWithoutFetchedContentMessage_andStateCheck_matchExpectedScope() {
        val message = metadataReadyWithoutContentMessage(ServiceType.INSTAGRAM)
        assertEquals("投稿内容を取得できませんでした", message.title)
        assertEquals(
            "Instagramの公開範囲やページ構成により、投稿内容を取得できない場合があります。",
            message.body,
        )

        assertEquals(
            true,
            isReadyWithoutFetchedContent(
                state = MetadataState.READY,
                bodySummary = null,
                fetchedBody = " ",
            ),
        )
        assertEquals(
            false,
            isReadyWithoutFetchedContent(
                state = MetadataState.READY,
                bodySummary = "要約あり",
                fetchedBody = null,
            ),
        )
        assertEquals(
            false,
            isReadyWithoutFetchedContent(
                state = MetadataState.FAILED,
                bodySummary = null,
                fetchedBody = null,
            ),
        )
    }

    @Test
    fun metadataSectionLabels_areServiceAware() {
        assertEquals(
            "投稿内容",
            metadataSummarySectionLabel(
                ServiceType.YOUTUBE,
                fetchedBody = "説明文",
                fetchedBodyKind = MetadataBodyKind.YOUTUBE_DESCRIPTION,
            ),
        )
        assertEquals(
            "投稿内容",
            metadataBodySectionLabel(
                ServiceType.YOUTUBE,
                fetchedBody = "説明文",
                fetchedBodyKind = MetadataBodyKind.YOUTUBE_DESCRIPTION,
            ),
        )
        assertEquals(
            "投稿内容",
            metadataSummarySectionLabel(
                ServiceType.X,
                fetchedBody = "post text",
                fetchedBodyKind = MetadataBodyKind.X_POST_TEXT,
            ),
        )
        assertEquals(
            "投稿内容",
            metadataBodySectionLabel(
                ServiceType.X,
                fetchedBody = "post text",
                fetchedBodyKind = MetadataBodyKind.X_POST_TEXT,
            ),
        )
        assertEquals(
            "投稿内容",
            metadataSummarySectionLabel(
                ServiceType.TIKTOK,
                fetchedBody = "post text",
                fetchedBodyKind = MetadataBodyKind.WEB_DESCRIPTION,
            ),
        )
        assertEquals(
            "投稿内容",
            metadataBodySectionLabel(
                ServiceType.TIKTOK,
                fetchedBody = "post text",
                fetchedBodyKind = MetadataBodyKind.WEB_DESCRIPTION,
            ),
        )
        assertEquals(
            "概要",
            metadataSummarySectionLabel(
                ServiceType.WEB,
                fetchedBody = "本文らしい長文でも種別がdescriptionなら概要",
                fetchedBodyKind = MetadataBodyKind.WEB_DESCRIPTION,
            ),
        )
        assertEquals(
            "本文抜粋の要点",
            metadataSummarySectionLabel(
                ServiceType.WEB,
                fetchedBody = "短い本文でも種別がexcerptなら本文抜粋",
                fetchedBodyKind = MetadataBodyKind.WEB_EXCERPT,
            ),
        )
        assertEquals(
            "本文抜粋",
            metadataBodySectionLabel(
                ServiceType.WEB,
                fetchedBody = "短い本文でも種別がexcerptなら本文抜粋",
                fetchedBodyKind = MetadataBodyKind.WEB_EXCERPT,
            ),
        )
    }

    @Test
    fun metadataSectionLabels_webFallback_usesHeuristicOnlyWhenKindIsNull() {
        assertEquals(
            "概要",
            metadataBodySectionLabel(
                serviceType = ServiceType.WEB,
                fetchedBody = "短い説明です",
                fetchedBodyKind = null,
            ),
        )
        assertEquals(
            "本文抜粋",
            metadataBodySectionLabel(
                serviceType = ServiceType.WEB,
                fetchedBody = "本文1。\n\n本文2。\n\n本文3。",
                fetchedBodyKind = null,
            ),
        )
    }

    @Test
    fun shouldHideBodyAsDuplicateSummary_matchesPrefixAndEllipsisRules() {
        assertEquals(
            true,
            shouldHideBodyAsDuplicateSummary(
                bodySummary = "最初の文です。",
                fetchedBody = "最初の文です。 次の文です。",
            ),
        )
        assertEquals(
            true,
            shouldHideBodyAsDuplicateSummary(
                bodySummary = "長い本文の冒頭…",
                fetchedBody = "長い本文の冒頭から続く本文です。",
            ),
        )
        assertEquals(
            false,
            shouldHideBodyAsDuplicateSummary(
                bodySummary = "別の要約",
                fetchedBody = "本文は違う内容です。",
            ),
        )
    }
}
