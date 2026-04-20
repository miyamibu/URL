package jp.mimac.urlsaver.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import jp.mimac.urlsaver.data.UrlEntryEntity
import jp.mimac.urlsaver.data.UserLabelEntity
import jp.mimac.urlsaver.domain.ContentContext
import jp.mimac.urlsaver.domain.MetadataState
import jp.mimac.urlsaver.domain.ServiceType
import jp.mimac.urlsaver.domain.UrlRules
import jp.mimac.urlsaver.ui.formatTimestamp
import jp.mimac.urlsaver.ui.metadataListStatusText
import jp.mimac.urlsaver.ui.serviceLabelForList
import jp.mimac.urlsaver.ui.serviceTypeForUi
import jp.mimac.urlsaver.ui.theme.OrbitTokens
import java.time.ZoneId

@Composable
fun EntryCard(
    entry: UrlEntryEntity,
    timestampMillis: Long,
    timestampLabel: String = "保存",
    showDisplayUrl: Boolean = true,
    assignedLabel: UserLabelEntity? = null,
    onAddLabel: (() -> Unit)? = null,
    onAssignLabel: ((Long?) -> Unit)? = null,
    onClick: () -> Unit,
) {
    val showThumbnail = !entry.thumbnailUrl.isNullOrBlank()
    val descriptionText = entry.description ?: entry.bodySummary

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .clickable(onClick = onClick),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            if (showThumbnail) {
                AsyncImage(
                    model = entry.thumbnailUrl,
                    contentDescription = "サムネイル",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .aspectRatio(16f / 9f),
                )
            }

            Column(modifier = Modifier.padding(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    ServiceBadge(
                        serviceType = serviceTypeForUi(entry.serviceType),
                        badgeImageUrl = entry.badgeImageUrl,
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = UrlRules.effectiveTitle(
                                userTitle = entry.userTitle,
                                fetchedTitle = entry.fetchedTitle,
                                serviceType = entry.serviceType,
                                normalizedHost = entry.normalizedHost,
                            ),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                text = serviceLabelForList(entry.serviceType, entry.normalizedHost),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (entry.contentContext != ContentContext.STANDARD) {
                                Surface(
                                    shape = RoundedCornerShape(999.dp),
                                    color = MaterialTheme.colorScheme.surfaceVariant,
                                ) {
                                    Text(
                                        text = entry.contentContext.label,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                    )
                                }
                            }
                        }
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        MetadataStatusDot(entry.metadataState)
                        Text(
                            text = "$timestampLabel ${formatTimestamp(timestampMillis, ZoneId.of("Asia/Tokyo"))}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                }

                if (!descriptionText.isNullOrBlank()) {
                    Text(
                        text = descriptionText,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(top = 10.dp),
                    )
                }

                if (showDisplayUrl && !showThumbnail) {
                    Text(
                        text = entry.displayUrl,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }

                entryCardMetadataStatusText(entry)?.let { statusText ->
                    Text(
                        text = statusText,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (entry.metadataState == MetadataState.FAILED || entry.metadataState == MetadataState.UNAVAILABLE) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (assignedLabel != null) {
                        AssistChip(
                            onClick = {},
                            label = { Text(assignedLabel.name) },
                        )
                        if (onAssignLabel != null) {
                            IconButton(
                                onClick = { onAssignLabel(null) },
                                modifier = Modifier.size(32.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.Close,
                                    contentDescription = "ラベルを解除",
                                )
                            }
                        }
                    } else if (onAddLabel != null) {
                        OutlinedButton(onClick = onAddLabel) {
                            Text("+ ラベル")
                        }
                    }
                }
            }
        }
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
            .size(8.dp)
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

private fun serviceAccentColor(serviceType: ServiceType): Color {
    return when (serviceTypeForUi(serviceType)) {
        ServiceType.YOUTUBE -> OrbitTokens.serviceAccentVideo
        ServiceType.X -> OrbitTokens.serviceAccentX
        ServiceType.WEB,
        ServiceType.INSTAGRAM,
        ServiceType.TIKTOK,
        ServiceType.ALL,
        -> OrbitTokens.serviceAccentWeb
    }
}
