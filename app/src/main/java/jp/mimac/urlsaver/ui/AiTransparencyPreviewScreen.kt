package jp.mimac.urlsaver.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AssistChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import jp.mimac.urlsaver.data.AiDiffProposal
import jp.mimac.urlsaver.data.AiDraft
import jp.mimac.urlsaver.data.AiSendPreview
import jp.mimac.urlsaver.data.AiSendReceipt
import jp.mimac.urlsaver.data.AiTransparencyFeature

@Composable
internal fun AiTransparencyPreviewScreen(
    preview: AiSendPreview,
    receipt: AiSendReceipt?,
    draft: AiDraft?,
    diffProposal: AiDiffProposal?,
    modifier: Modifier = Modifier,
) {
    if (!AiTransparencyFeature.isEnabled) {
        Text(
            text = "AI Preview is disabled",
            modifier = modifier.padding(24.dp),
            style = MaterialTheme.typography.bodyMedium,
        )
        return
    }

    Column(
        modifier = modifier.padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = "AI Preview",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AssistChip(onClick = {}, label = { Text("eligible ${preview.eligibleCount}") })
            AssistChip(onClick = {}, label = { Text("blocked ${preview.blockedCount}") })
            AssistChip(onClick = {}, label = { Text(if (preview.canSend) "safe" else "blocked") })
        }
        preview.sources.forEach { source ->
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(source.title, style = MaterialTheme.typography.titleSmall)
                Text(source.normalizedUrl, style = MaterialTheme.typography.bodySmall)
                Text(
                    text = if (source.aiEligible) {
                        "AI対象"
                    } else {
                        "除外: ${source.exclusionReasons.joinToString()}"
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        receipt?.let {
            HorizontalDivider()
            Text(
                text = "Receipt ${it.receiptId}",
                modifier = Modifier.fillMaxWidth(),
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                text = "request=${it.requestSizeBucket} response=${it.responseSizeBucket}",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        draft?.let {
            HorizontalDivider()
            Text(it.title, style = MaterialTheme.typography.titleSmall)
            Text(it.body, style = MaterialTheme.typography.bodyMedium)
        }
        diffProposal?.let {
            HorizontalDivider()
            Text(
                text = "Diff proposal: ${it.operations.size} operations / applied=${it.applied}",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}
