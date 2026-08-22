package jp.mimac.urlsaver

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import jp.mimac.urlsaver.data.UrlEntryEntity
import jp.mimac.urlsaver.domain.ContentContext
import jp.mimac.urlsaver.domain.MetadataState
import jp.mimac.urlsaver.domain.RecordState
import jp.mimac.urlsaver.domain.ServiceType
import jp.mimac.urlsaver.ui.components.EntryCard
import jp.mimac.urlsaver.ui.components.EntryCardContainerColorKey
import jp.mimac.urlsaver.ui.components.EntryCardSupportingColorKey
import jp.mimac.urlsaver.ui.components.EntryCardTitleColorKey
import jp.mimac.urlsaver.ui.theme.OrbitTokens
import jp.mimac.urlsaver.ui.theme.UrlSaverTheme
import org.junit.Rule
import org.junit.Test

class EntryCardContrastComposeTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun renderedCardsExposeThemeAdaptiveColorsForEveryThemeAndSelectionState() {
        composeRule.setContent {
            Column {
                CardFixture(id = 1, darkTheme = false, selected = false)
                CardFixture(id = 2, darkTheme = false, selected = true)
                CardFixture(id = 3, darkTheme = true, selected = false)
                CardFixture(id = 4, darkTheme = true, selected = true)
            }
        }

        assertCardColors(
            id = 1,
            container = Color(0xFFFFFFFF),
            title = Color(0xFF102033),
            supporting = Color(0xFF506176),
        )
        assertCardColors(
            id = 2,
            container = Color(0xFFE7EDF5),
            title = Color(0xFF102033),
            supporting = Color(0xFF506176),
        )
        assertCardColors(
            id = 3,
            container = OrbitTokens.panel,
            title = OrbitTokens.textPrimary,
            supporting = OrbitTokens.textMutedStrong,
        )
        assertCardColors(
            id = 4,
            container = OrbitTokens.panelSoft,
            title = OrbitTokens.textPrimary,
            supporting = OrbitTokens.textMutedStrong,
        )
    }

    private fun assertCardColors(id: Long, container: Color, title: Color, supporting: Color) {
        composeRule.onNodeWithTag("entry_card_$id")
            .assert(SemanticsMatcher.expectValue(EntryCardContainerColorKey, container.toArgb()))
            .assert(SemanticsMatcher.expectValue(EntryCardTitleColorKey, title.toArgb()))
            .assert(SemanticsMatcher.expectValue(EntryCardSupportingColorKey, supporting.toArgb()))
    }
}

@androidx.compose.runtime.Composable
private fun CardFixture(id: Long, darkTheme: Boolean, selected: Boolean) {
    UrlSaverTheme(darkTheme = darkTheme) {
        EntryCard(
            entry = UrlEntryEntity(
                id = id,
                originalUrl = "https://example.com/$id",
                normalizedUrl = "https://example.com/$id",
                displayUrl = "example.com/$id",
                openUrl = "https://example.com/$id",
                normalizedHost = "example.com",
                rawSourceHost = "example.com",
                serviceType = ServiceType.WEB,
                contentContext = ContentContext.STANDARD,
                fetchedTitle = "Example Domain",
                bodySummary = "Readable supporting text",
                metadataState = MetadataState.READY,
                recordState = RecordState.ACTIVE,
                createdAt = 1L,
                updatedAt = 1L,
            ),
            timestampMillis = 1L,
            selected = selected,
            onClick = {},
        )
    }
}
