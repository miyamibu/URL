package jp.mimac.urlsaver

import jp.mimac.urlsaver.data.UrlEntryEntity
import jp.mimac.urlsaver.domain.ContentContext
import jp.mimac.urlsaver.domain.MetadataState
import jp.mimac.urlsaver.domain.RecordState
import jp.mimac.urlsaver.domain.ServiceType
import jp.mimac.urlsaver.ui.buildListSearchUiState
import org.junit.Assert.assertEquals
import org.junit.Test

class ListSearchStateTest {

    @Test
    fun selectedAll_returnsAllEntries() {
        val web = entry(id = 1, serviceType = ServiceType.WEB)
        val youtube = entry(id = 2, serviceType = ServiceType.YOUTUBE, displayUrl = "youtube.com/watch?v=z")
        val state = buildListSearchUiState(
            entries = listOf(web, youtube),
            selectedService = ServiceType.ALL,
        )

        assertEquals(2, state.globalCount)
        assertEquals(2, state.scopeCount)
        assertEquals(2, state.entries.size)
    }

    @Test
    fun selectedService_filtersEntriesAndCounts() {
        val web = entry(id = 1, serviceType = ServiceType.WEB)
        val youtube = entry(id = 2, serviceType = ServiceType.YOUTUBE, displayUrl = "youtube.com/watch?v=z")

        val state = buildListSearchUiState(
            entries = listOf(web, youtube),
            selectedService = ServiceType.YOUTUBE,
        )

        assertEquals(2, state.globalCount)
        assertEquals(1, state.scopeCount)
        assertEquals(1, state.entries.size)
        assertEquals(youtube.id, state.entries.single().id)
    }

    @Test
    fun selectedService_withNoMatches_returnsEmptyScopedList() {
        val web = entry(id = 1, serviceType = ServiceType.WEB)

        val state = buildListSearchUiState(
            entries = listOf(web),
            selectedService = ServiceType.YOUTUBE,
        )

        assertEquals(1, state.globalCount)
        assertEquals(0, state.scopeCount)
        assertEquals(0, state.entries.size)
    }

    private fun entry(
        id: Long = 1,
        serviceType: ServiceType = ServiceType.WEB,
        userTitle: String? = null,
        fetchedTitle: String? = null,
        memo: String = "",
        displayUrl: String = "example.com/path",
        normalizedHost: String = "example.com",
    ): UrlEntryEntity {
        val normalizedUrl = "https://$displayUrl"
        return UrlEntryEntity(
            id = id,
            originalUrl = normalizedUrl,
            normalizedUrl = normalizedUrl,
            displayUrl = displayUrl,
            openUrl = normalizedUrl,
            normalizedHost = normalizedHost,
            rawSourceHost = normalizedHost,
            serviceType = serviceType,
            contentContext = ContentContext.STANDARD,
            userTitle = userTitle,
            fetchedTitle = fetchedTitle,
            memo = memo,
            metadataState = MetadataState.PENDING,
            recordState = RecordState.ACTIVE,
            createdAt = 1L,
            updatedAt = 1L,
        )
    }
}
