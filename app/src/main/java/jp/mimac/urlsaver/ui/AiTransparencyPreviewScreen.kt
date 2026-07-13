package jp.mimac.urlsaver.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import jp.mimac.urlsaver.data.AiTransparencyFeature

@Composable
internal fun AiTransparencyPreviewScreen(
    viewModel: AiTransparencyViewModel,
    modifier: Modifier = Modifier,
) {
    if (!AiTransparencyFeature.isEnabled) {
        return
    }

    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var showDiffConfirm by remember { mutableStateOf(false) }
    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = "AI動作の確認（デバッグ）",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
        )
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.secondaryContainer,
            shape = MaterialTheme.shapes.medium,
        ) {
            Text(
                text = "この画面は端末内のモックです。外部には送信しません。",
                modifier = Modifier.padding(16.dp),
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            OutlinedButton(
                onClick = viewModel::refresh,
                enabled = !uiState.isLoading,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp),
            ) {
                Text("再読み込み")
            }
            Button(
                onClick = viewModel::runLocalMock,
                enabled = !uiState.isLoading && uiState.preview != null,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp),
            ) {
                Text("モックを実行")
            }
        }
        if (uiState.isLoading) {
            CircularProgressIndicator()
        }
        uiState.message?.let { message ->
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        HorizontalDivider()
        Text("送信前の確認", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        uiState.preview?.let { preview ->
            Text("確認対象 ${preview.sourceCount}件")
            Text("送信対象 ${preview.eligibleCount}件 / 除外 ${preview.blockedCount}件")
            preview.sources
                .asSequence()
                .flatMap { it.exclusionReasons.asSequence() }
                .groupingBy { it }
                .eachCount()
                .forEach { (reason, count) ->
                    Text(
                        text = "除外理由: ${exclusionReasonLabel(reason)} ${count}件",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
        }

        HorizontalDivider()
        Text("処理記録", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        uiState.receipt?.let { receipt ->
            Text("送信対象 ${receipt.sentSourceIds.size}件 / 除外 ${receipt.blockedSourceIds.size}件")
            Text("安全確認: 本文・プロンプトは記録していません")
            Text(
                text = "記録サイズ: ${receipt.requestSizeBucket} / ${receipt.responseSizeBucket}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } ?: Text(
            text = "まだ処理記録はありません",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        HorizontalDivider()
        Text("下書き", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        uiState.draft?.let { draft ->
            Text(draft.title, style = MaterialTheme.typography.titleSmall)
            Text(draft.body, style = MaterialTheme.typography.bodyMedium)
        } ?: Text(
            text = "まだ下書きはありません",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        HorizontalDivider()
        Text("変更案", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        uiState.diffProposal?.let { proposal ->
            Text("変更項目 ${proposal.operations.size}件")
            if (proposal.operations.isEmpty()) {
                Text(
                    text = "今回のモックには変更項目がありません",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                proposal.operations.forEach { operation ->
                    Text(
                        text = diffFieldLabel(operation.field),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        text = "変更前: ${operation.before?.takeIf { it.isNotBlank() } ?: "（空）"}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = "変更後: ${operation.after?.takeIf { it.isNotBlank() } ?: "（空）"}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Button(
                    onClick = { showDiffConfirm = true },
                    enabled = !uiState.isLoading && !proposal.applied,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp),
                ) {
                    Text("変更案を反映")
                }
            }
        } ?: Text(
            text = "まだ変更案はありません",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    if (showDiffConfirm) {
        AlertDialog(
            onDismissRequest = { showDiffConfirm = false },
            title = { Text("変更案を反映") },
            text = {
                Text("変更直前に対象条件と現在値を再確認します。1件でも条件を満たさない場合は、すべて反映しません。")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDiffConfirm = false
                        viewModel.applyDiffProposal(confirm = true)
                    },
                ) {
                    Text("確認して反映")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDiffConfirm = false }) {
                    Text("キャンセル")
                }
            },
        )
    }
}

private fun exclusionReasonLabel(reason: String): String {
    return when (reason) {
        "shared_tag_default_excluded" -> "共有タグ付き"
        "archived_default_excluded" -> "アーカイブ済み"
        "pending_delete_excluded" -> "削除保留中"
        "no_local_provenance" -> "端末保存なし"
        else -> "対象外条件"
    }
}

private fun diffFieldLabel(field: String): String {
    return when (field) {
        "userTitle" -> "タイトルの変更案"
        "memo" -> "メモの変更案"
        else -> "許可されていない変更案"
    }
}
