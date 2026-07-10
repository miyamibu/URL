package jp.mimac.urlsaver

import jp.mimac.urlsaver.video.mediaResolveErrorReason
import org.junit.Assert.assertEquals
import org.junit.Test

class MediaResolveErrorReasonTest {
    @Test
    fun youtubeBotCheckMessageReturnsAuthRequired() {
        val result = mediaResolveErrorReason(
            error = "YOUTUBE_DIRECT_RESOLVE_TIMEOUT",
            message = "Sign in to confirm you are not a bot. Use --cookies for authentication.",
        )

        assertEquals("AUTH_REQUIRED", result)
    }

    @Test
    fun instagramCookieErrorReturnsAuthRequired() {
        val result = mediaResolveErrorReason(
            error = "ERROR: [Instagram] empty media response. use --cookies for authentication",
            message = null,
        )

        assertEquals("AUTH_REQUIRED", result)
    }

    @Test
    fun regularBackendErrorKeepsErrorCode() {
        val result = mediaResolveErrorReason(
            error = "MEDIA_NOT_FOUND",
            message = "No video could be found.",
        )

        assertEquals("MEDIA_NOT_FOUND", result)
    }
}
