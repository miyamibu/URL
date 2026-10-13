package jp.mimac.urlsaver.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import jp.mimac.urlsaver.domain.TagColorChoice
import jp.mimac.urlsaver.ui.theme.tagColorSwatch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun TagColorEditorSheet(
    visible: Boolean,
    editorKey: String,
    tagName: String,
    selectedColor: TagColorChoice,
    isSharedTag: Boolean,
    saveError: String? = null,
    onDismiss: () -> Unit,
    onRename: (() -> Unit)? = null,
    onSave: (TagColorChoice) -> Unit,
) {
    if (!visible) return

    var pendingColor by rememberSaveable(editorKey, selectedColor) { mutableStateOf(selectedColor) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val colorScheme = MaterialTheme.colorScheme

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = colorScheme.surface,
        contentColor = colorScheme.onSurface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = if (isSharedTag) "共有タグの色" else "タグを編集",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = "色はこの端末の表示に反映されます",
                    style = MaterialTheme.typography.bodySmall,
                    color = colorScheme.onSurfaceVariant,
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                OrbitSectionLabel("名前")
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    color = colorScheme.surfaceVariant.copy(alpha = 0.62f),
                    border = BorderStroke(1.dp, colorScheme.outlineVariant),
                ) {
                    Row(
                        modifier = Modifier.padding(start = 14.dp, end = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = tagName,
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (!isSharedTag && onRename != null) {
                            IconButton(
                                onClick = onRename,
                                modifier = Modifier
                                    .size(48.dp)
                                    .testTag("tag_color_rename"),
                            ) {
                                Icon(Icons.Outlined.Edit, contentDescription = "タグ名を変更")
                            }
                        }
                    }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OrbitSectionLabel("カラー", modifier = Modifier.weight(1f))
                    Text(
                        text = "チェックは選択中の色",
                        style = MaterialTheme.typography.bodySmall,
                        color = colorScheme.onSurfaceVariant,
                    )
                }
                OutlinedButton(
                    onClick = { pendingColor = TagColorChoice.DEFAULT },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .semantics {
                            selected = pendingColor == TagColorChoice.DEFAULT
                            role = Role.RadioButton
                            contentDescription = "既定の色"
                        }
                        .testTag("tag_color_default"),
                    border = BorderStroke(
                        if (pendingColor == TagColorChoice.DEFAULT) 2.dp else 1.dp,
                        if (pendingColor == TagColorChoice.DEFAULT) colorScheme.primary else colorScheme.outline,
                    ),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp),
                ) {
                    Text("既定の色", modifier = Modifier.weight(1f), textAlign = TextAlign.Start)
                    if (pendingColor == TagColorChoice.DEFAULT) {
                        Icon(Icons.Outlined.Check, contentDescription = "選択中", tint = colorScheme.primary)
                    }
                }

                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                    maxItemsInEachRow = 4,
                ) {
                    TagColorChoice.CUSTOM.forEach { choice ->
                        val isSelected = pendingColor == choice
                        val swatch = tagColorSwatch(choice, colorScheme)
                        Surface(
                            modifier = Modifier
                                .size(54.dp)
                                .semantics {
                                    selected = isSelected
                                    role = Role.RadioButton
                                    contentDescription = choice.displayName
                                }
                                .clickable { pendingColor = choice }
                                .testTag("tag_color_${choice.storageValue}"),
                            shape = CircleShape,
                            color = swatch,
                            border = BorderStroke(
                                if (isSelected) 3.dp else 1.dp,
                                if (isSelected) colorScheme.primary else colorScheme.outlineVariant,
                            ),
                        ) {
                            if (isSelected) {
                                Box(
                                    modifier = Modifier.fillMaxWidth(),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(
                                        Icons.Outlined.Check,
                                        contentDescription = null,
                                        tint = Color(0xFF38354A),
                                    )
                                }
                            }
                        }
                    }
                }
            }

            if (!saveError.isNullOrBlank()) {
                Text(
                    text = saveError,
                    modifier = Modifier.fillMaxWidth(),
                    style = MaterialTheme.typography.bodySmall,
                    color = colorScheme.error,
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OrbitSectionLabel("プレビュー")
                OrbitFilterChip(
                    label = tagName,
                    selected = false,
                    compact = true,
                    tagColorChoice = pendingColor,
                    modifier = Modifier.testTag("tag_color_preview"),
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .weight(1f)
                        .height(52.dp)
                        .testTag("tag_color_cancel"),
                ) {
                    Text("キャンセル")
                }
                Button(
                    onClick = { onSave(pendingColor) },
                    modifier = Modifier
                        .weight(1f)
                        .height(52.dp)
                        .testTag("tag_color_save"),
                ) {
                    Text("保存")
                }
            }
        }
    }
}
