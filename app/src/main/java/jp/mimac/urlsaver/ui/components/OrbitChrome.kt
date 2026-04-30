package jp.mimac.urlsaver.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import jp.mimac.urlsaver.ui.theme.OrbitTokens

enum class OrbitPanelTone {
    DEFAULT,
    SOFT,
    STRONG,
}

enum class OrbitActionStyle {
    PRIMARY,
    SECONDARY,
    DANGER,
}

@Composable
fun OrbitPanel(
    modifier: Modifier = Modifier,
    tone: OrbitPanelTone = OrbitPanelTone.DEFAULT,
    contentPadding: PaddingValues = PaddingValues(20.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    val containerColor = when (tone) {
        OrbitPanelTone.DEFAULT -> MaterialTheme.colorScheme.surface
        OrbitPanelTone.SOFT -> MaterialTheme.colorScheme.surfaceVariant
        OrbitPanelTone.STRONG -> OrbitTokens.panelStrong
    }
    val contentColor = when (tone) {
        OrbitPanelTone.STRONG -> OrbitTokens.textPrimary
        OrbitPanelTone.SOFT -> MaterialTheme.colorScheme.onSurfaceVariant
        OrbitPanelTone.DEFAULT -> MaterialTheme.colorScheme.onSurface
    }
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(OrbitTokens.radiusPanel),
        color = containerColor,
        contentColor = contentColor,
        border = BorderStroke(1.dp, OrbitTokens.outline),
    ) {
        Column(
            modifier = Modifier.padding(contentPadding),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            content = content,
        )
    }
}

@Composable
fun OrbitSectionLabel(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        modifier = modifier,
        style = MaterialTheme.typography.labelLarge.copy(
            letterSpacing = 0.9.sp,
            fontWeight = FontWeight.SemiBold,
        ),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
fun OrbitFilterChip(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    labelFontSize: TextUnit = TextUnit.Unspecified,
) {
    val hasCustomLabelSize = labelFontSize != TextUnit.Unspecified
    Box(
        modifier = modifier
            .widthIn(min = if (compact) 44.dp else 72.dp, max = 220.dp)
            .height(44.dp)
            .background(
                color = if (selected) OrbitTokens.primarySoftSurface else OrbitTokens.panelSoft,
                shape = RoundedCornerShape(OrbitTokens.radiusChip),
            )
            .padding(
                horizontal = if (compact) 0.dp else 16.dp,
                vertical = if (hasCustomLabelSize) 0.dp else 10.dp,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge.copy(
                letterSpacing = if (compact) 0.sp else 0.8.sp,
                fontWeight = FontWeight.SemiBold,
                fontSize = when {
                    hasCustomLabelSize -> labelFontSize
                    compact && label == "+" -> 22.sp
                    else -> MaterialTheme.typography.labelLarge.fontSize
                },
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = if (selected) MaterialTheme.colorScheme.primary else OrbitTokens.segmentedInactiveText,
        )
    }
}

@Composable
fun OrbitActionButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    style: OrbitActionStyle = OrbitActionStyle.SECONDARY,
    contentPadding: PaddingValues = PaddingValues(horizontal = 20.dp, vertical = 18.dp),
    content: @Composable RowScope.() -> Unit,
) {
    val shape = RoundedCornerShape(OrbitTokens.radiusButton)
    when (style) {
        OrbitActionStyle.PRIMARY -> {
            Button(
                onClick = onClick,
                modifier = modifier.defaultMinSize(minHeight = 58.dp),
                enabled = enabled,
                shape = shape,
                colors = ButtonDefaults.buttonColors(
                    containerColor = OrbitTokens.primary,
                    contentColor = OrbitTokens.onPrimary,
                    disabledContainerColor = OrbitTokens.primary.copy(alpha = 0.4f),
                    disabledContentColor = OrbitTokens.onPrimary.copy(alpha = 0.7f),
                ),
                contentPadding = contentPadding,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                    content = content,
                )
            }
        }

        OrbitActionStyle.SECONDARY -> {
            OutlinedButton(
                onClick = onClick,
                modifier = modifier.defaultMinSize(minHeight = 58.dp),
                enabled = enabled,
                shape = shape,
                border = BorderStroke(1.dp, OrbitTokens.outlineStrong),
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = OrbitTokens.panelSoft,
                    contentColor = OrbitTokens.textPrimary,
                    disabledContainerColor = OrbitTokens.panelSoft.copy(alpha = 0.55f),
                    disabledContentColor = OrbitTokens.textPrimary.copy(alpha = 0.55f),
                ),
                contentPadding = contentPadding,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                    content = content,
                )
            }
        }

        OrbitActionStyle.DANGER -> {
            OutlinedButton(
                onClick = onClick,
                modifier = modifier.defaultMinSize(minHeight = 58.dp),
                enabled = enabled,
                shape = shape,
                border = BorderStroke(1.dp, OrbitTokens.danger.copy(alpha = 0.55f)),
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = OrbitTokens.dangerSurface,
                    contentColor = OrbitTokens.danger,
                    disabledContainerColor = OrbitTokens.dangerSurface.copy(alpha = 0.45f),
                    disabledContentColor = OrbitTokens.danger.copy(alpha = 0.55f),
                ),
                contentPadding = contentPadding,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                    content = content,
                )
            }
        }
    }
}

@Composable
fun OrbitActionText(
    text: String,
    emphasisColor: Color = Color.Unspecified,
) {
    Text(
        text = text,
        color = emphasisColor,
        style = MaterialTheme.typography.titleMedium.copy(
            fontWeight = FontWeight.SemiBold,
        ),
    )
}
