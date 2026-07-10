package jp.mimac.urlsaver.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.LocalIndication
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import jp.mimac.urlsaver.domain.TagWithCount
import jp.mimac.urlsaver.ui.theme.OrbitTokens

@Composable
fun TagFilterRow(
    tags: List<TagWithCount>,
    onOpenTag: (Long) -> Unit,
    onCreateTag: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        OrbitSectionLabel(
            text = "共有タグ",
            modifier = Modifier.padding(
                start = OrbitTokens.screenHorizontalPadding,
                end = OrbitTokens.screenHorizontalPadding,
                top = 4.dp,
                bottom = 4.dp,
            ),
        )
        LazyRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = OrbitTokens.screenHorizontalPadding),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item(key = "create_shared_tag") {
                TagChip(
                    label = "+",
                    labelFontSize = 28.sp,
                    onClick = onCreateTag,
                )
            }
            items(tags, key = { it.id }) { tag ->
                TagChip(
                    label = tag.name,
                    onClick = { onOpenTag(tag.id) },
                )
            }
        }
    }
}

@Composable
private fun TagChip(
    label: String,
    compact: Boolean = false,
    labelFontSize: androidx.compose.ui.unit.TextUnit = androidx.compose.ui.unit.TextUnit.Unspecified,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()

    OrbitFilterChip(
        label = label,
        selected = pressed,
        compact = compact,
        labelFontSize = labelFontSize,
        modifier = Modifier
            .clickable(
                indication = LocalIndication.current,
                interactionSource = interactionSource,
                onClick = onClick,
            ),
    )
}
