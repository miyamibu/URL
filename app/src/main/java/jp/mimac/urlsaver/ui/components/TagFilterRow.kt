package jp.mimac.urlsaver.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.LocalIndication
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import jp.mimac.urlsaver.domain.TagWithCount
import jp.mimac.urlsaver.domain.TagColorChoice
import jp.mimac.urlsaver.domain.TagColorKey
import jp.mimac.urlsaver.domain.choiceFor
import jp.mimac.urlsaver.ui.theme.OrbitTokens

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TagFilterRow(
    tags: List<TagWithCount>,
    onOpenTag: (Long) -> Unit,
    onCreateTag: () -> Unit,
    headingColor: Color? = null,
    tagColorChoices: Map<String, TagColorChoice> = emptyMap(),
    onEditColor: ((TagWithCount) -> Unit)? = null,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        OrbitSectionLabel(
            text = "共有タグ",
            color = headingColor,
            modifier = Modifier.padding(
                start = OrbitTokens.screenHorizontalPadding,
                end = OrbitTokens.screenHorizontalPadding,
                top = 6.dp,
                bottom = 8.dp,
            ),
        )
        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = OrbitTokens.screenHorizontalPadding),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            item(key = "create_shared_tag") {
                TagChip(
                    label = "+",
                    labelFontSize = 24.sp,
                    contentDescription = "共有タグを作成",
                    onClick = onCreateTag,
                )
            }
            items(tags, key = { it.id }) { tag ->
                TagChip(
                    label = tag.name,
                    contentDescription = tag.name,
                    tagColorChoice = tagColorChoices.choiceFor(TagColorKey.forTag(tag)),
                    onClick = { onOpenTag(tag.id) },
                    onDoubleClick = onEditColor?.let { editColor -> { editColor(tag) } },
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TagChip(
    label: String,
    compact: Boolean = false,
    labelFontSize: androidx.compose.ui.unit.TextUnit = androidx.compose.ui.unit.TextUnit.Unspecified,
    contentDescription: String? = null,
    tagColorChoice: TagColorChoice? = null,
    onClick: () -> Unit,
    onDoubleClick: (() -> Unit)? = null,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()

    OrbitFilterChip(
        label = label,
        selected = pressed,
        compact = compact,
        labelFontSize = labelFontSize,
        tagColorChoice = tagColorChoice,
        modifier = Modifier
            .semantics {
                if (contentDescription != null) {
                    this.contentDescription = contentDescription
                }
            }
            .then(
                if (onDoubleClick != null) {
                    Modifier.combinedClickable(
                        indication = LocalIndication.current,
                        interactionSource = interactionSource,
                        onClick = onClick,
                        onDoubleClick = onDoubleClick,
                    )
                } else {
                    Modifier.clickable(
                        indication = LocalIndication.current,
                        interactionSource = interactionSource,
                        onClick = onClick,
                    )
                },
            ),
    )
}
