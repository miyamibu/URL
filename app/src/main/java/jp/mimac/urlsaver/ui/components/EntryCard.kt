package jp.mimac.urlsaver.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import jp.mimac.urlsaver.data.UrlEntryEntity
import jp.mimac.urlsaver.domain.ContentContext
import jp.mimac.urlsaver.domain.EntryCardDisplayMode
import jp.mimac.urlsaver.domain.MetadataState
import jp.mimac.urlsaver.domain.ServiceType
import jp.mimac.urlsaver.domain.UrlRules
import jp.mimac.urlsaver.ui.formatTimestamp
import jp.mimac.urlsaver.ui.metadataListStatusText
import jp.mimac.urlsaver.ui.preferredDisplayTitle
import jp.mimac.urlsaver.ui.serviceLabelForList
import jp.mimac.urlsaver.ui.serviceTypeForUi
import jp.mimac.urlsaver.ui.theme.OrbitTokens
import java.time.ZoneId

@Composable
fun EntryCard(
    entry: UrlEntryEntity,
    timestampMillis: Long,
    timestampLabel: String = "保存",
    displayMode: EntryCardDisplayMode = EntryCardDisplayMode.RICH,
    showDisplayUrl: Boolean = true,
    selected: Boolean = false,
    localTagNames: List<String> = emptyList(),
    onLongClick: (() -> Unit)? = null,
    footerContent: (@Composable ColumnScope.() -> Unit)? = null,
    onClick: () -> Unit,
) {
    val showThumbnail = displayMode == EntryCardDisplayMode.RICH && !entry.thumbnailUrl.isNullOrBlank()
    val showDescription = displayMode == EntryCardDisplayMode.RICH
    val descriptionText = entry.description ?: entry.bodySummary
    val serviceAccentBrush = serviceAccentBrush(entry.serviceType)
    val titleTextStyle = if (displayMode == EntryCardDisplayMode.RICH) {
        MaterialTheme.typography.titleMedium
    } else {
        MaterialTheme.typography.titleSmall
    }
    val titleMaxLines = if (displayMode == EntryCardDisplayMode.COMPACT) 2 else 3
    val visibleLocalTagNames = entryCardVisibleLocalTagNames(localTagNames)

    Surface(
        modifier = Modifier
            .testTag("entry_card_${entry.id}")
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .entryCardClickable(
                onClick = onClick,
                onLongClick = onLongClick,
            ),
        shape = RoundedCornerShape(OrbitTokens.radiusPanel),
        color = if (selected) OrbitTokens.panelStrong else MaterialTheme.colorScheme.surface,
        border = BorderStroke(
            if (selected) 1.5.dp else 1.dp,
            if (selected) MaterialTheme.colorScheme.primary else OrbitTokens.outline,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    color = if (selected) OrbitTokens.panelStrong else MaterialTheme.colorScheme.surface,
                    shape = RoundedCornerShape(OrbitTokens.radiusPanel),
                ),
        ) {
            if (showThumbnail) {
                AsyncImage(
                    model = entry.thumbnailUrl,
                    contentDescription = "サムネイル",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(188.dp)
                        .clip(
                            RoundedCornerShape(
                                topStart = OrbitTokens.radiusCardMedia,
                                topEnd = OrbitTokens.radiusCardMedia,
                            ),
                        )
                        .background(OrbitTokens.panelStrong),
                )
            }

            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .padding(top = 4.dp)
                            .width(7.dp)
                            .height(64.dp)
                            .background(
                                brush = serviceAccentBrush,
                                shape = RoundedCornerShape(999.dp),
                            ),
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.Top,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            ServiceBadge(
                                serviceType = serviceTypeForUi(entry.serviceType),
                                badgeImageUrl = entry.badgeImageUrl,
                                modifier = Modifier.padding(top = 1.dp),
                            )
                            if (visibleLocalTagNames.isEmpty()) {
                                Row(
                                    modifier = Modifier.weight(1f),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    Text(
                                        text = entryCardHeaderFallbackText(entry),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.weight(1f, fill = false),
                                    )
                                    if (entry.contentContext != ContentContext.STANDARD) {
                                        Surface(
                                            shape = RoundedCornerShape(999.dp),
                                            color = OrbitTokens.panelStrong,
                                            border = BorderStroke(1.dp, OrbitTokens.outline),
                                        ) {
                                            Text(
                                                text = entry.contentContext.label,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                                            )
                                        }
                                    }
                                }
                                Column(horizontalAlignment = Alignment.End) {
                                    MetadataStatusDot(entry.metadataState)
                                    Text(
                                        text = "$timestampLabel ${formatTimestamp(timestampMillis, ZoneId.of("Asia/Tokyo"))}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(top = 10.dp),
                                    )
                                }
                            } else {
                                EntryCardLocalTagFlow(
                                    tagNames = visibleLocalTagNames,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                        Text(
                            text = preferredDisplayTitle(
                                userTitle = entry.userTitle,
                                fetchedTitle = entry.fetchedTitle,
                                serviceType = entry.serviceType,
                                normalizedHost = entry.normalizedHost,
                                bodySummary = entry.bodySummary,
                                fetchedBody = entry.fetchedBody,
                                description = entry.description,
                            ),
                            maxLines = titleMaxLines,
                            overflow = TextOverflow.Ellipsis,
                            style = titleTextStyle,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 10.dp),
                        )
                    }
                }

                if (showDescription && !descriptionText.isNullOrBlank()) {
                    Text(
                        text = descriptionText,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodyMedium,
                        color = OrbitTokens.textMutedStrong,
                        modifier = Modifier.padding(top = 14.dp),
                    )
                }

                if (showDisplayUrl && (displayMode == EntryCardDisplayMode.COMPACT || !showThumbnail)) {
                    Text(
                        text = entry.displayUrl,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }

                entryCardMetadataStatusText(entry)?.let { statusText ->
                    Text(
                        text = statusText,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (entry.metadataState == MetadataState.FAILED || entry.metadataState == MetadataState.UNAVAILABLE) {
                            OrbitTokens.danger
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier.padding(top = 10.dp),
                    )
                }

                footerContent?.invoke(this)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EntryCardLocalTagFlow(
    tagNames: List<String>,
    modifier: Modifier = Modifier,
) {
    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        tagNames.forEach { tagName ->
            Surface(
                shape = RoundedCornerShape(999.dp),
                color = OrbitTokens.panelStrong,
                border = BorderStroke(1.dp, OrbitTokens.outline),
            ) {
                Text(
                    text = tagName,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .widthIn(max = 150.dp)
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                )
            }
        }
    }
}

private fun entryCardHeaderFallbackText(entry: UrlEntryEntity): String {
    entry.fetchedAuthorName?.trim()?.takeIf { it.isNotBlank() }?.let { return it }
    return serviceLabelForList(entry.serviceType, entry.normalizedHost)
}

internal fun entryCardVisibleLocalTagNames(localTagNames: List<String>): List<String> {
    return localTagNames
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .distinctBy { it.lowercase() }
}

internal fun entryCardUsesLocalTagHeader(localTagNames: List<String>): Boolean {
    return entryCardVisibleLocalTagNames(localTagNames).isNotEmpty()
}

@OptIn(ExperimentalFoundationApi::class)
private fun Modifier.entryCardClickable(
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
): Modifier {
    return if (onLongClick != null) {
        combinedClickable(
            onClick = onClick,
            onLongClick = onLongClick,
        )
    } else {
        clickable(onClick = onClick)
    }
}

internal fun entryCardMetadataStatusText(entry: UrlEntryEntity): String? {
    return metadataListStatusText(
        state = entry.metadataState,
        error = entry.metadataError,
        serviceType = entry.serviceType,
    )
}

@Composable
private fun MetadataStatusDot(state: MetadataState) {
    Box(
        modifier = Modifier
            .size(10.dp)
            .border(
                width = 1.dp,
                color = OrbitTokens.background,
                shape = CircleShape,
            )
            .background(
                color = metadataDotColor(state),
                shape = CircleShape,
            ),
    )
}

private fun metadataDotColor(state: MetadataState): Color {
    return when (state) {
        MetadataState.PENDING -> OrbitTokens.metadataPending
        MetadataState.READY -> OrbitTokens.metadataReady
        MetadataState.FAILED -> OrbitTokens.metadataFailed
        MetadataState.UNAVAILABLE -> OrbitTokens.metadataUnavailable
    }
}

private fun serviceAccentBrush(serviceType: ServiceType): Brush {
    return when (serviceTypeForUi(serviceType)) {
        ServiceType.YOUTUBE -> Brush.verticalGradient(
            colors = listOf(
                OrbitTokens.serviceAccentVideo,
                OrbitTokens.serviceAccentVideo,
            ),
        )
        ServiceType.INSTAGRAM -> Brush.verticalGradient(
            colors = listOf(
                Color(0xFF8A3AB9),
                Color(0xFFE95950),
                Color(0xFFFCAF45),
            ),
        )
        ServiceType.X -> Brush.verticalGradient(
            colors = listOf(
                OrbitTokens.serviceAccentX,
                OrbitTokens.serviceAccentX,
            ),
        )
        ServiceType.TIKTOK -> Brush.verticalGradient(
            colors = listOf(
                Color(0xFF111111),
                Color(0xFF24F6F0),
                Color(0xFFFFFFFF),
                Color(0xFFFF3353),
                Color(0xFF111111),
            ),
        )
        ServiceType.WEB,
        ServiceType.ALL,
        -> Brush.verticalGradient(
            colors = listOf(
                OrbitTokens.serviceAccentWeb,
                OrbitTokens.serviceAccentWeb,
            ),
        )
    }
}
