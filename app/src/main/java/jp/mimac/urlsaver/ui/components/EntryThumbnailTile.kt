package jp.mimac.urlsaver.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import jp.mimac.urlsaver.data.UrlEntryEntity
import jp.mimac.urlsaver.domain.EntryCardDisplayMode
import jp.mimac.urlsaver.domain.TagColorChoice
import jp.mimac.urlsaver.domain.UrlRules

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun EntryThumbnailTile(
    entry: UrlEntryEntity,
    showsInfo: Boolean,
    isSelected: Boolean,
    localTagNames: List<String>,
    localTagColorChoices: Map<String, TagColorChoice>,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    var imageUnavailable by remember(entry.thumbnailUrl) {
        mutableStateOf(entry.thumbnailUrl.isNullOrBlank())
    }
    val title = UrlRules.effectiveTitle(entry.userTitle, entry.fetchedTitle, entry.serviceType, entry.normalizedHost)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("entry_thumbnail_${entry.id}")
            .semantics(mergeDescendants = true) {
                selected = isSelected
                contentDescription = if (imageUnavailable) "$title、サムネイル未取得" else title
            }
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .then(
                    if (isSelected) {
                        Modifier.border(3.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(8.dp))
                    } else {
                        Modifier
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Outlined.Image,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(40.dp),
            )
            if (!entry.thumbnailUrl.isNullOrBlank()) {
                AsyncImage(
                    model = entry.thumbnailUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    onError = { imageUnavailable = true },
                    onSuccess = { imageUnavailable = false },
                    modifier = Modifier.fillMaxWidth().aspectRatio(1f),
                )
            }
            if (isSelected) {
                Icon(
                    imageVector = Icons.Outlined.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp)
                        .background(MaterialTheme.colorScheme.primary, CircleShape)
                        .padding(4.dp)
                        .size(20.dp),
                )
            }
        }
        if (showsInfo) {
            EntryCard(
                entry = entry,
                timestampMillis = entry.createdAt,
                displayMode = EntryCardDisplayMode.COMPACT,
                showDisplayUrl = false,
                selected = isSelected,
                localTagNames = localTagNames,
                localTagColorChoices = localTagColorChoices,
                onClick = onClick,
                onLongClick = onLongClick,
                outerHorizontalPadding = 0.dp,
                outerVerticalPadding = 0.dp,
            )
        }
    }
}
