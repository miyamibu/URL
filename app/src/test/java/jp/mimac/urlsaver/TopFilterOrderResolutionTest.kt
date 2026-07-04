package jp.mimac.urlsaver

import jp.mimac.urlsaver.data.CollectionEntity
import jp.mimac.urlsaver.domain.ServiceType
import jp.mimac.urlsaver.domain.TagWithCount
import jp.mimac.urlsaver.ui.components.mergedTopFilterTokens
import jp.mimac.urlsaver.ui.components.resolvedTopFilterTokens
import org.junit.Assert.assertEquals
import org.junit.Test

class TopFilterOrderResolutionTest {

    @Test
    fun resolvedTokens_keepMovedAllAndCollectionPositions() {
        val collections = listOf(
            CollectionEntity(id = 10, name = "自作A", sortOrder = 1, createdAt = 0, updatedAt = 0),
            CollectionEntity(id = 11, name = "自作B", sortOrder = 2, createdAt = 0, updatedAt = 0),
        )

        val tokens = resolvedTopFilterTokens(
            serviceOrder = listOf(ServiceType.YOUTUBE, ServiceType.X, ServiceType.INSTAGRAM, ServiceType.WEB),
            collections = collections,
            topFilterOrderTokens = listOf(
                "service_YOUTUBE",
                "all",
                "collection_11",
                "collection_10",
                "service_X",
                "service_INSTAGRAM",
                "service_WEB",
            ),
        )

        assertEquals(
            listOf(
                "service_YOUTUBE",
                "all",
                "collection_11",
                "collection_10",
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
                "collection_10",
                "all",
                "service_YOUTUBE",
                "service_X",
            ),
            latestTokens = listOf(
                "all",
                "collection_10",
                "collection_11",
                "service_YOUTUBE",
                "service_X",
                "service_INSTAGRAM",
            ),
        )

        assertEquals(
            listOf(
                "collection_10",
                "all",
                "service_YOUTUBE",
                "service_X",
                "collection_11",
                "service_INSTAGRAM",
            ),
            merged,
        )
    }

    @Test
    fun resolvedTokens_insertNewLocalTagsBeforeServiceFilters() {
        val tokens = resolvedTopFilterTokens(
            serviceOrder = listOf(ServiceType.YOUTUBE, ServiceType.X),
            collections = emptyList(),
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
