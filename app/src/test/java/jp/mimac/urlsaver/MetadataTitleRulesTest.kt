package jp.mimac.urlsaver

import jp.mimac.urlsaver.domain.MetadataTitleRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MetadataTitleRulesTest {
    @Test
    fun normalizeInstagramTitle_rejectsEngagementAndDateWrappers() {
        assertNull(
            MetadataTitleRules.normalizeInstagramTitle(
                "October 12, 2026: 1,234 likes, 42 comments - nasa on Instagram",
            ),
        )
        assertNull(
            MetadataTitleRules.normalizeInstagramTitle(
                "2026年10月12日・123件のいいね、8件のコメント Instagram",
            ),
        )
    }

    @Test
    fun normalizeInstagramTitle_keepsRealTitleAndRemovesBrandSuffix() {
        assertEquals(
            "Apollo launch",
            MetadataTitleRules.normalizeInstagramTitle("Apollo launch • Instagram photos and videos"),
        )
        assertEquals("100 likesのお祝い", MetadataTitleRules.normalizeInstagramTitle("100 likesのお祝い"))
    }

    @Test
    fun unwrapInstagramCaption_removesOnlyRecognizedEngagementEnvelopes() {
        assertEquals(
            "2026年10月の散歩。3匹の猫と出会った。",
            MetadataTitleRules.unwrapInstagramCaption(
                "1,234 likes, 56 comments - example on October 12, 2026: “2026年10月の散歩。3匹の猫と出会った。”",
            ),
        )
        assertEquals(
            "秋の散歩。2026年の記録。",
            MetadataTitleRules.unwrapInstagramCaption(
                "いいね！123件、コメント4件 - example 2026年10月12日: 「秋の散歩。2026年の記録。」",
            ),
        )
        assertNull(MetadataTitleRules.unwrapInstagramCaption("2026年10月12日、100 likesのお祝い。"))
        assertNull(MetadataTitleRules.unwrapInstagramCaption("A real caption with 12 likes and a date."))
        assertEquals(
            "2026年の長い投稿の冒頭",
            MetadataTitleRules.unwrapInstagramCaption("1,234 likes, 56 comments - example on October 12, 2026: \"2026年の長い投稿の冒頭"),
        )
    }

    @Test
    fun isInstagramAuthorTitle_matchesAuthorAndProfileDecorations() {
        assertTrue(MetadataTitleRules.isInstagramAuthorTitle("OpenAI", "openai"))
        assertTrue(MetadataTitleRules.isInstagramAuthorTitle("NASA (@nasa)", null))
        assertTrue(MetadataTitleRules.isInstagramAuthorTitle("example on Instagram", null))
        assertFalse(MetadataTitleRules.isInstagramAuthorTitle("Apollo launch", "NASA"))
    }

    @Test
    fun shortContentTitle_doesNotSplitEmojiAtTheLengthLimit() {
        assertEquals(
            "x".repeat(98) + "…",
            MetadataTitleRules.shortContentTitle("x".repeat(98) + "😀" + "tail"),
        )
        assertEquals(
            "x".repeat(97) + "😀…",
            MetadataTitleRules.shortContentTitle("x".repeat(97) + "😀" + "tail"),
        )
    }

    @Test
    fun shortContentTitle_prefersFirstSentenceAndCapsLongText() {
        assertEquals(
            "A useful caption.",
            MetadataTitleRules.shortContentTitle("A useful caption. More context follows.", "Other summary"),
        )
        assertEquals(100, MetadataTitleRules.shortContentTitle("x".repeat(130))?.length)
        assertTrue(MetadataTitleRules.shortContentTitle("x".repeat(130))?.endsWith("…") == true)
        assertEquals(
            "2026年10月の散歩。",
            MetadataTitleRules.shortContentTitle(
                "1,234 likes, 56 comments - example on October 12, 2026: “2026年10月の散歩。3匹の猫と出会った。”",
            ),
        )
        assertEquals(
            "2026.10.12の散歩を記録。",
            MetadataTitleRules.shortContentTitle("2026.10.12の散歩を記録。続き"),
        )
    }
}
