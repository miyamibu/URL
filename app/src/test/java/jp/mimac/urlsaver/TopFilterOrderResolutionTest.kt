package jp.mimac.urlsaver

import jp.mimac.urlsaver.domain.ServiceType
import jp.mimac.urlsaver.domain.TagWithCount
import jp.mimac.urlsaver.ui.components.mergedTopFilterTokens
import jp.mimac.urlsaver.ui.components.resolvedTopFilterTokens
import org.junit.Assert.assertEquals
import org.junit.Test

class TopFilterOrderResolutionTest {

    @Test
    fun resolvedTokens_keepMovedAllAndServicePositions() {
        val tokens = resolvedTopFilterTokens(
            serviceOrder = listOf(ServiceType.YOUTUBE, ServiceType.X, ServiceType.INSTAGRAM, ServiceType.WEB),
            topFilterOrderTokens = listOf(
                "service_YOUTUBE",
                "all",
                "service_X",
                "service_INSTAGRAM",
                "service_WEB",
            ),
        )

        assertEquals(
            listOf(
                "service_YOUTUBE",
                "all",
                "service_X",
                "service_INSTAGRAM",
                "service_WEB",
            ),
            tokens,
        )
    }

    @Test
    fun mergedTokens_keepStoredOrderAndAppendNewItems() {
        val merged = mergedTopFilterTokens(
            currentTokens = listOf(
                "all",
                "service_YOUTUBE",
                "service_X",
            ),
            latestTokens = listOf(
                "all",
                "service_YOUTUBE",
                "service_X",
                "service_INSTAGRAM",
            ),
        )

        assertEquals(
            listOf(
                "all",
                "service_YOUTUBE",
                "service_X",
                "service_INSTAGRAM",
            ),
            merged,
        )
    }

    @Test
    fun resolvedTokens_insertNewLocalTagsBeforeServiceFilters() {
        val tokens = resolvedTopFilterTokens(
            serviceOrder = listOf(ServiceType.YOUTUBE, ServiceType.X),
            localTags = listOf(
                TagWithCount(id = 20, name = "新しい自作", urlCount = 0),
                TagWithCount(id = 10, name = "既存自作", urlCount = 1),
            ),
            topFilterOrderTokens = listOf(
                "local_tag_10",
                "all",
                "service_YOUTUBE",
                "service_X",
            ),
        )

        assertEquals(
            listOf(
                "local_tag_10",
                "local_tag_20",
                "all",
                "service_YOUTUBE",
                "service_X",
            ),
            tokens,
        )
    }
}
