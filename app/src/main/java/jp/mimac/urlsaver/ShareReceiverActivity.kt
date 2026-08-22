package jp.mimac.urlsaver

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.ViewModelProvider
import jp.mimac.urlsaver.ads.AdsManager
import jp.mimac.urlsaver.app.AppContainer
import jp.mimac.urlsaver.data.SHARE_DEGRADATION_TRUNCATED_TO_FIRST_URL
import jp.mimac.urlsaver.data.SHARE_DEGRADATION_TRUNCATED_TO_MAX_URLS
import jp.mimac.urlsaver.domain.SharedTagScope
import jp.mimac.urlsaver.domain.ShareExtractionResult
import jp.mimac.urlsaver.domain.ShareSaveResult
import jp.mimac.urlsaver.domain.TagWithCount
import jp.mimac.urlsaver.domain.TagSharePayload
import jp.mimac.urlsaver.domain.UrlRules
import jp.mimac.urlsaver.domain.normalizeSharedTagName
import jp.mimac.urlsaver.domain.tryDecodeTagSharePayload
import jp.mimac.urlsaver.ui.theme.UrlSaverTheme
import jp.mimac.urlsaver.ui.SavedStateFactory
import kotlinx.coroutines.launch

class ShareReceiverActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setFinishOnTouchOutside(false)

        lifecycleScope.launch {
            val container = (application as UrlSaverApp).container
            val nonUrlRedirect = ShareReceiverEntrypointRouter.resolve(
                activity = this@ShareReceiverActivity,
                sourceIntent = intent,
                tagRepository = container.tagRepository,
            )
            if (nonUrlRedirect != null) {
                startActivity(nonUrlRedirect)
                finish()
                return@launch
            }

            val sharePayload = buildSharePayload(intent)
            val receiverViewModel = ViewModelProvider(
                this@ShareReceiverActivity,
                SavedStateFactory { savedStateHandle ->
                    ShareReceiverViewModel(
                        operations = RepositoryShareReceiverOperations(container),
                        savedStateHandle = savedStateHandle,
                    )
                },
            )[ShareReceiverViewModel::class.java]
            (sharePayload as? ShareReceiverPayload.Pending)?.let(receiverViewModel::initialize)
            setContent {
                UrlSaverTheme {
                    ShareReceiverContent(
                        payload = sharePayload,
                        container = container,
                        viewModel = receiverViewModel,
                        onFinish = {
                            receiverViewModel.discard()
                            finish()
                        },
                    )
                }
            }
        }
    }

    private fun buildSharePayload(sourceIntent: Intent): ShareReceiverPayload {
        val sharedText = sourceIntent.getStringExtra(Intent.EXTRA_TEXT)
            ?: sourceIntent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
        val tagPayload = sharedText
            ?.takeIf { it.toByteArray(Charsets.UTF_8).size <= UrlRules.MAX_INPUT_TEXT_BYTES }
            ?.let(::tryDecodeTagSharePayload)
            ?.takeIf { payload ->
                payload.tag.isNotBlank() && payload.urls.size <= UrlRules.MAX_BATCH_SAVE_URLS_PER_INTAKE
            }
        if (tagPayload != null) {
            return ShareReceiverPayload.TagImport(tagPayload)
        }

        val isSendMultiple = sourceIntent.action == Intent.ACTION_SEND_MULTIPLE
        var degradationNotice: String? = null

        if (isSendMultiple) {
            val extractedBatch = UrlRules.extractAllFromIntent(sourceIntent)
            val extractedUrls = extractedBatch.urls
            val sharedMemo = UrlRules.extractMemoWithoutUrlsFromIntent(sourceIntent)
            return when {
                extractedUrls.isEmpty() -> {
                    when (val extracted = UrlRules.extractFromIntent(sourceIntent)) {
                        ShareExtractionResult.InputTooLarge -> ShareReceiverPayload.Error(ShareSaveResult.INPUT_TOO_LARGE)
                        ShareExtractionResult.InvalidUrl -> {
                            val text = UrlRules.extractTextFallbackFromIntent(sourceIntent)
                            if (text == null) {
                                ShareReceiverPayload.Error(ShareSaveResult.INVALID_URL)
                            } else {
                                ShareReceiverPayload.Pending(
                                    urls = listOf(text),
                                    isBatch = false,
                                    degradationNotice = null,
                                )
                            }
                        }
                        ShareExtractionResult.NoUrlFound -> {
                            val text = UrlRules.extractTextFallbackFromIntent(sourceIntent)
                            if (text == null) {
                                ShareReceiverPayload.Error(ShareSaveResult.NO_URL_FOUND)
                            } else {
                                ShareReceiverPayload.Pending(
                                    urls = listOf(text),
                                    isBatch = false,
                                    degradationNotice = null,
                                )
                            }
                        }
                        is ShareExtractionResult.Found -> ShareReceiverPayload.Pending(
                            urls = listOf(extracted.url),
                            isBatch = false,
                            memo = sharedMemo,
                            degradationNotice = null,
                        )
                    }
                }
                extractedUrls.size == 1 -> ShareReceiverPayload.Pending(
                    urls = extractedUrls,
                    isBatch = false,
                    memo = sharedMemo,
                    degradationNotice = null,
                )
                else -> {
                    if (extractedBatch.truncatedToMaxUrls) {
                        degradationNotice = SHARE_DEGRADATION_TRUNCATED_TO_MAX_URLS
                    }
                    ShareReceiverPayload.Pending(
                        urls = extractedUrls,
                        isBatch = true,
                        memo = sharedMemo,
                        degradationNotice = degradationNotice,
                    )
                }
            }
        }

        if (UrlRules.countValidUrlsInIntent(sourceIntent) > 1) {
            degradationNotice = SHARE_DEGRADATION_TRUNCATED_TO_FIRST_URL
        }
        return when (val extracted = UrlRules.extractFromIntent(sourceIntent)) {
            ShareExtractionResult.InputTooLarge -> ShareReceiverPayload.Error(ShareSaveResult.INPUT_TOO_LARGE)
            ShareExtractionResult.InvalidUrl -> {
                val text = UrlRules.extractTextFallbackFromIntent(sourceIntent)
                if (text == null) {
                    ShareReceiverPayload.Error(ShareSaveResult.INVALID_URL)
                } else {
                    ShareReceiverPayload.Pending(
                        urls = listOf(text),
                        isBatch = false,
                        degradationNotice = null,
                    )
                }
            }
            ShareExtractionResult.NoUrlFound -> {
                val text = UrlRules.extractTextFallbackFromIntent(sourceIntent)
                if (text == null) {
                    ShareReceiverPayload.Error(ShareSaveResult.NO_URL_FOUND)
                } else {
                    ShareReceiverPayload.Pending(
                        urls = listOf(text),
                        isBatch = false,
                        degradationNotice = null,
                    )
                }
            }
            is ShareExtractionResult.Found -> ShareReceiverPayload.Pending(
                urls = listOf(extracted.url),
                isBatch = false,
                memo = UrlRules.extractMemoWithoutUrlsFromIntent(sourceIntent),
                degradationNotice = degradationNotice,
            )
        }
    }
}

@Composable
private fun ShareReceiverContent(
    payload: ShareReceiverPayload,
    container: AppContainer,
    viewModel: ShareReceiverViewModel,
    onFinish: () -> Unit,
) {
    val context = LocalContext.current
    val allTags by container.tagRepository.observeAllTagsWithCount().collectAsState(initial = emptyList())
    val localTags = remember(allTags) {
        allTags
            .filter { tag -> tag.scope == SharedTagScope.LOCAL_ONLY }
            .sortedByDescending { tag -> tag.id }
            .distinctBy { tag -> normalizeSharedTagName(tag.name) }
    }
    val receiverState by viewModel.uiState.collectAsState()
    var tagImportIsSaving by remember { mutableStateOf(false) }
    var tagImportResultMessage by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(viewModel) {
        viewModel.meaningfulActions.collect {
            AdsManager.registerMeaningfulActionAndMaybeShow(context)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.36f)),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Surface(
            color = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding(),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 24.dp),
            ) {
                when {
                    receiverState.resultMessage != null || tagImportResultMessage != null -> {
                        ShareReceiverResultContent(
                            message = receiverState.resultMessage ?: requireNotNull(tagImportResultMessage),
                            onFinish = onFinish,
                        )
                    }
                    payload is ShareReceiverPayload.Error -> {
                        ShareReceiverErrorContent(
                            result = payload.result,
                            onFinish = onFinish,
                        )
                    }
                    payload is ShareReceiverPayload.TagImport -> {
                        ShareReceiverTagImportContent(
                            payload = payload.payload,
                            isSaving = tagImportIsSaving,
                            onCancel = onFinish,
                            onImport = {
                                scope.launch {
                                    tagImportIsSaving = true
                                    val result = container.tagRepository.importTag(payload.payload)
                                    tagImportIsSaving = false
                                    tagImportResultMessage = buildString {
                                        append("タグ『${result.tagName}』を取り込みました")
                                        append("（新規${result.created}件・追加${result.merged}件")
                                        if (result.failed > 0) append("・失敗${result.failed}件")
                                        append("）")
                                    }
                                }
                            },
                        )
                    }
                    receiverState.hasPendingShare -> {
                        ShareReceiverPendingContent(
                            localTags = localTags,
                            selectedLocalTagIds = receiverState.selectedLocalTagIds,
                            newTagName = receiverState.newTagName,
                            tagCreateError = receiverState.tagCreateError,
                            retryMessage = receiverState.retryMessage,
                            isTagSelectionLocked = receiverState.isTagSelectionLocked,
                            isCreatingTag = receiverState.isCreatingTag,
                            isSaving = receiverState.isSaving,
                            onToggleLocalTag = viewModel::toggleLocalTag,
                            onNewTagNameChange = viewModel::updateNewTagName,
                            onCreateTag = viewModel::createLocalTag,
                            onCancel = onFinish,
                            onSave = viewModel::savePendingShare,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ShareReceiverTagImportContent(
    payload: TagSharePayload,
    isSaving: Boolean,
    onCancel: () -> Unit,
    onImport: () -> Unit,
) {
    Text(
        text = "自作タグを受け取る",
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.Bold,
    )
    Spacer(Modifier.height(12.dp))
    Text(
        text = "タグ『${payload.tag}』のURL ${payload.urls.size}件を取り込みます。確認するまで保存しません。",
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(20.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        TextButton(
            onClick = onCancel,
            enabled = !isSaving,
            modifier = Modifier
                .weight(1f)
                .heightIn(min = 56.dp),
        ) {
            Text("キャンセル", style = MaterialTheme.typography.titleMedium)
        }
        Button(
            onClick = onImport,
            enabled = !isSaving,
            modifier = Modifier
                .weight(1f)
                .heightIn(min = 56.dp),
        ) {
            Text(if (isSaving) "取り込み中…" else "取り込む", style = MaterialTheme.typography.titleMedium)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ShareReceiverPendingContent(
    localTags: List<TagWithCount>,
    selectedLocalTagIds: Set<Long>,
    newTagName: String,
    tagCreateError: String?,
    retryMessage: String?,
    isTagSelectionLocked: Boolean,
    isCreatingTag: Boolean,
    isSaving: Boolean,
    onToggleLocalTag: (Long) -> Unit,
    onNewTagNameChange: (String) -> Unit,
    onCreateTag: () -> Unit,
    onCancel: () -> Unit,
    onSave: () -> Unit,
) {
    val isTagEditingEnabled = !isTagSelectionLocked && !isCreatingTag
    Text(
        text = "保存先タグ",
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.Bold,
    )
    if (isTagSelectionLocked) {
        Text(
            text = "保存処理を開始したため、この操作が完了するまでタグ選択は変更できません。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp),
        )
    } else if (isCreatingTag) {
        Text(
            text = "タグを作成しています。完了後に保存できます。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
    Spacer(Modifier.height(18.dp))
    if (!retryMessage.isNullOrBlank()) {
        Surface(
            color = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = retryMessage,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            )
        }
        Spacer(Modifier.height(14.dp))
    }

    if (localTags.isEmpty()) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = "タグがまだありません。必要なら作成できます。",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 14.dp),
            )
        }
    } else {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            localTags.forEach { tag ->
                ShareReceiverTagRow(
                    tag = tag,
                    selected = tag.id in selectedLocalTagIds,
                    enabled = isTagEditingEnabled,
                    onClick = { onToggleLocalTag(tag.id) },
                )
            }
        }
    }
    Spacer(Modifier.height(54.dp))
    OutlinedTextField(
        value = newTagName,
        onValueChange = onNewTagNameChange,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 62.dp),
        textStyle = MaterialTheme.typography.titleMedium,
        placeholder = { Text("新しいタグ名", style = MaterialTheme.typography.titleMedium) },
        enabled = isTagEditingEnabled,
        singleLine = true,
        isError = tagCreateError != null,
        supportingText = {
            if (tagCreateError != null) {
                Text(tagCreateError)
            }
        },
    )
    TextButton(
        onClick = onCreateTag,
        enabled = isTagEditingEnabled && newTagName.trim().isNotEmpty(),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 54.dp),
    ) {
        Text(if (isCreatingTag) "作成中…" else "＋", style = MaterialTheme.typography.titleLarge)
    }
    Spacer(Modifier.height(8.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        TextButton(
            onClick = onCancel,
            enabled = !isSaving,
            modifier = Modifier
                .weight(1f)
                .heightIn(min = 56.dp),
        ) {
            Text("キャンセル", style = MaterialTheme.typography.titleLarge)
        }
        Button(
            onClick = onSave,
            enabled = !isSaving && !isCreatingTag,
            contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
            modifier = Modifier
                .weight(1f)
                .heightIn(min = 56.dp)
                .testTag("share_receiver_save"),
        ) {
            if (isSaving) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                )
                Spacer(Modifier.width(8.dp))
                Text("保存中…", style = MaterialTheme.typography.titleLarge)
            } else {
                Text(
                    if (retryMessage == null) "保存" else "失敗分を再試行",
                    style = MaterialTheme.typography.titleLarge,
                )
            }
        }
    }
}

@Composable
private fun ShareReceiverTagRow(
    tag: TagWithCount,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = if (selected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        },
        border = BorderStroke(
            width = 1.dp,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
        ),
        modifier = Modifier
            .wrapContentWidth()
            .alpha(if (enabled) 1f else 0.58f)
            .clip(RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, onClick = onClick),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 13.dp),
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(28.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface)
                    .border(
                        width = 1.dp,
                        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                        shape = RoundedCornerShape(14.dp),
                    ),
            ) {
                if (selected) {
                    Icon(
                        imageVector = Icons.Outlined.Check,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Text(
                text = tag.name,
                modifier = Modifier.widthIn(max = 184.dp),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun ShareReceiverResultContent(
    message: String,
    onFinish: () -> Unit,
) {
    Spacer(Modifier.height(56.dp))
    Text(
        text = message,
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.Bold,
        textAlign = TextAlign.Center,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(32.dp))
    Button(
        onClick = onFinish,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 58.dp),
    ) {
        Text("完了", style = MaterialTheme.typography.titleLarge)
    }
}

@Composable
private fun ShareReceiverErrorContent(
    result: ShareSaveResult,
    onFinish: () -> Unit,
) {
    Text(
        text = "保存できませんでした",
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.Bold,
    )
    Spacer(Modifier.height(12.dp))
    Text(
        text = shareReceiverErrorMessage(result),
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(20.dp))
    Button(
        onClick = onFinish,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text("完了", style = MaterialTheme.typography.titleMedium)
    }
}

private fun shareReceiverErrorMessage(result: ShareSaveResult): String {
    return when (result) {
        ShareSaveResult.INPUT_TOO_LARGE -> "共有内容が長すぎるため処理できませんでした"
        ShareSaveResult.INVALID_URL -> "有効なURLではありませんでした"
        ShareSaveResult.NO_URL_FOUND -> "保存できる内容が見つかりませんでした"
        else -> "保存できませんでした"
    }
}

internal sealed interface ShareReceiverPayload {
    data class Pending(
        val urls: List<String>,
        val isBatch: Boolean,
        val memo: String? = null,
        val degradationNotice: String?,
    ) : ShareReceiverPayload

    data class Error(val result: ShareSaveResult) : ShareReceiverPayload

    data class TagImport(val payload: TagSharePayload) : ShareReceiverPayload
}
