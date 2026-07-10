package jp.mimac.urlsaver

import jp.mimac.urlsaver.data.UrlEntryEntity
import jp.mimac.urlsaver.domain.ContentContext
import jp.mimac.urlsaver.domain.MetadataError
import jp.mimac.urlsaver.domain.MetadataState
import jp.mimac.urlsaver.domain.RecordState
import jp.mimac.urlsaver.domain.ServiceType
import jp.mimac.urlsaver.ui.components.entryCardMetadataStatusText
import org.junit.Assert.assertEquals
import org.junit.Test

class EntryCardMetadataTextTest {
    @Test
    fun entryCardMetadataStatusText_usesMetadataUiTextContract() {
        val failedEntry = buildEntry(
            metadataState = MetadataState.FAILED,
            metadataError = MetadataError.TIMEOUT,
            serviceType = ServiceType.WEB,
        )
        assertEquals("一時的に取得できません", entryCardMetadataStatusText(failedEntry))

        val restrictedUnavailableEntry = buildEntry(
            metadataState = MetadataState.UNAVAILABLE,
            metadataError = MetadataError.HTTP_4XX,
            serviceType = ServiceType.X,
        )
        assertEquals("自動取得に制限あり", entryCardMetadataStatusText(restrictedUnavailableEntry))

        val genericUnavailableEntry = buildEntry(
            metadataState = MetadataState.UNAVAILABLE,
            metadataError = MetadataError.HTTP_404,
            serviceType = ServiceType.WEB,
        )
        assertEquals("自動取得できません", entryCardMetadataStatusText(genericUnavailableEntry))
    }

    private fun buildEntry(
        metadataState: MetadataState,
        metadataError: MetadataError?,
        serviceType: ServiceType,
    ): UrlEntryEntity {
        return UrlEntryEntity(
            id = 1L,
            originalUrl = "https://example.com",
            normalizedUrl = "https://example.com",
            displayUrl = "example.com",
            openUrl = "https://example.com",
            normalizedHost = "example.com",
            rawSourceHost = "example.com",
            serviceType = serviceType,
            contentContext = ContentContext.STANDARD,
            metadataState = metadataState,
            metadataError = metadataError,
            recordState = RecordState.ACTIVE,
            createdAt = 1L,
            updatedAt = 1L,
        )
    }
}
