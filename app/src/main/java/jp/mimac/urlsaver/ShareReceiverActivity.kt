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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import jp.mimac.urlsaver.ads.AdsManager
import jp.mimac.urlsaver.app.AppContainer
import jp.mimac.urlsaver.data.SHARE_DEGRADATION_TRUNCATED_TO_FIRST_URL
import jp.mimac.urlsaver.data.SHARE_DEGRADATION_TRUNCATED_TO_MAX_URLS
import jp.mimac.urlsaver.domain.AssignTagResult
import jp.mimac.urlsaver.domain.CreateTagResult
import jp.mimac.urlsaver.domain.SaveResult
import jp.mimac.urlsaver.domain.SharedTagScope
import jp.mimac.urlsaver.domain.ShareExtractionResult
import jp.mimac.urlsaver.domain.ShareSaveResult
import jp.mimac.urlsaver.domain.TagWithCount
import jp.mimac.urlsaver.domain.TagSharePayload
import jp.mimac.urlsaver.domain.UrlRules
import jp.mimac.urlsaver.domain.normalizeSharedTagName
import jp.mimac.urlsaver.domain.tryDecodeTagSharePayload
import jp.mimac.urlsaver.ui.theme.UrlSaverTheme
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
            setContent {
                UrlSaverTheme {
                    ShareReceiverContent(
                        payload = sharePayload,
                        container = container,
                        onFinish = { finish() },
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
    var selectedLocalTagIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var newTagName by remember { mutableStateOf("") }
    var tagCreateError by remember { mutableStateOf<String?>(null) }
    var isSaving by remember { mutableStateOf(false) }
    var resultMessage by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

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
                    resultMessage != null -> {
                        ShareReceiverResultContent(
                            message = requireNotNull(resultMessage),
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
                            isSaving = isSaving,
                            onCancel = onFinish,
                            onImport = {
                                scope.launch {
                                    isSaving = true
                                    val result = container.tagRepository.importTag(payload.payload)
                                    isSaving = false
                                    resultMessage = buildString {
                                        append("タグ『${result.tagName}』を取り込みました")
                                        append("（新規${result.created}件・追加${result.merged}件")
                                        if (result.failed > 0) append("・失敗${result.failed}件")
                                        append("）")
                                    }
                                }
                            },
                        )
                    }
                    payload is ShareReceiverPayload.Pending -> {
                        ShareReceiverPendingContent(
                            localTags = localTags,
                            selectedLocalTagIds = selectedLocalTagIds,
                            newTagName = newTagName,
                            tagCreateError = tagCreateError,
                            isSaving = isSaving,
                            onToggleLocalTag = { tagId ->
                                selectedLocalTagIds = if (tagId in selectedLocalTagIds) {
                                    selectedLocalTagIds - tagId
                                } else {
                                    selectedLocalTagIds + tagId
                                }
                            },
                            onNewTagNameChange = {
                                newTagName = it
                                tagCreateError = null
                            },
                            onCreateTag = {
                                scope.launch {
                                    val normalizedName = normalizeSharedTagName(newTagName)
                                    when (val result = container.tagRepository.createLocalTagWithResult(normalizedName)) {
                                        is CreateTagResult.Success -> {
                                            selectedLocalTagIds = selectedLocalTagIds + result.tagId
                                            newTagName = ""
                                            tagCreateError = null
                                        }
                                        CreateTagResult.Duplicate -> {
                                            val duplicateId = container.tagRepository.findLocalTagIdByName(normalizedName)
                                            if (duplicateId != null) {
                                                selectedLocalTagIds = selectedLocalTagIds + duplicateId
                                                newTagName = ""
                                                tagCreateError = null
                                            } else {
                                                tagCreateError = "同じ名前のタグがあります"
                                            }
                                        }
                                        CreateTagResult.InvalidName -> tagCreateError = "タグ名を入力してください"
                                        is CreateTagResult.LimitReached -> tagCreateError = result.message
                                        CreateTagResult.Failed -> tagCreateError = "タグを作成できませんでした"
                                    }
                                }
                            },
                            onCancel = onFinish,
                            onSave = {
                                scope.launch {
                                    isSaving = true
                                    val saveResult = savePendingShare(
                                        payload = payload,
                                        container = container,
                                        selectedLocalTagIds = selectedLocalTagIds,
                                    )
                                    if (saveResult.meaningfulAction) {
                                        AdsManager.registerMeaningfulActionAndMaybeShow(context)
                                    }
                                    isSaving = false
                                    resultMessage = saveResult.message
                                }
                            },
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
    isSaving: Boolean,
    onToggleLocalTag: (Long) -> Unit,
    onNewTagNameChange: (String) -> Unit,
    onCreateTag: () -> Unit,
    onCancel: () -> Unit,
    onSave: () -> Unit,
) {
    Text(
        text = "保存先タグ",
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.Bold,
    )
    Spacer(Modifier.height(18.dp))

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
        enabled = !isSaving && newTagName.trim().isNotEmpty(),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 54.dp),
    ) {
        Text("＋", style = MaterialTheme.typography.titleLarge)
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
            enabled = !isSaving,
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
                Text("保存", style = MaterialTheme.typography.titleLarge)
            }
        }
    }
}

@Composable
private fun ShareReceiverTagRow(
    tag: TagWithCount,
    selected: Boolean,
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
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
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

private suspend fun savePendingShare(
    payload: ShareReceiverPayload.Pending,
    container: AppContainer,
    selectedLocalTagIds: Set<Long>,
): ShareReceiverSaveOutcome {
    var tagAssignmentFailed = false

    if (payload.urls.size <= 1 && !payload.isBatch) {
        val result = container.repository.saveFromManualInput(
            payload.urls.first(),
            initialMemo = payload.memo,
        )
        if (shouldAssignShareTags(result.result, result.entryId) && selectedLocalTagIds.isNotEmpty()) {
            val assigned = assignLocalTags(
                entryId = requireNotNull(result.entryId),
                container = container,
                tagIds = selectedLocalTagIds,
            )
            tagAssignmentFailed = !assigned
        }
        return ShareReceiverSaveOutcome(
            message = shareReceiverResultMessage(result, payload.degradationNotice, tagAssignmentFailed),
            meaningfulAction = result.result == ShareSaveResult.CREATED ||
                result.result == ShareSaveResult.RESTORED_FROM_PENDING_DELETE,
        )
    }

    var created = 0
    var duplicate = 0
    var restored = 0
    var failed = 0
    payload.urls.forEach { url ->
        val result = container.repository.saveFromManualInput(
            url,
            initialMemo = payload.memo,
        )
        if (shouldAssignShareTags(result.result, result.entryId) && selectedLocalTagIds.isNotEmpty()) {
            val assigned = assignLocalTags(
                entryId = requireNotNull(result.entryId),
                container = container,
                tagIds = selectedLocalTagIds,
            )
            if (!assigned) tagAssignmentFailed = true
        }
        when (result.result) {
            ShareSaveResult.CREATED -> created += 1
            ShareSaveResult.DUPLICATE_ACTIVE,
            ShareSaveResult.DUPLICATE_ARCHIVED,
            -> duplicate += 1
            ShareSaveResult.RESTORED_FROM_PENDING_DELETE -> restored += 1
            ShareSaveResult.SAVE_FAILED,
            ShareSaveResult.INPUT_TOO_LARGE,
            ShareSaveResult.PERSONAL_URL_LIMIT_REACHED,
            ShareSaveResult.INVALID_URL,
            ShareSaveResult.NO_URL_FOUND,
            ShareSaveResult.BATCH_PROCESSED,
            -> failed += 1
        }
    }
    val message = buildString {
        append("${payload.urls.size}件を処理しました（新規$created / 既存$duplicate / 復元$restored / 失敗$failed）")
        payload.degradationNotice?.let { append("\n").append(degradationMessage(it)) }
        if (tagAssignmentFailed) append("\n保存しましたが、一部のタグ付けに失敗しました")
    }
    return ShareReceiverSaveOutcome(message = message, meaningfulAction = created > 0 || restored > 0)
}

private suspend fun assignLocalTags(
    entryId: Long,
    container: AppContainer,
    tagIds: Set<Long>,
): Boolean {
    var allSucceeded = true
    tagIds.forEach { tagId ->
            when (container.tagRepository.assignTagWithResult(tagId = tagId, entryId = entryId)) {
                AssignTagResult.Success,
                AssignTagResult.AlreadyAssigned,
                -> Unit
                is AssignTagResult.LimitReached,
                AssignTagResult.Failed,
                -> allSucceeded = false
            }
    }
    return allSucceeded
}

private fun shouldAssignShareTags(result: ShareSaveResult, entryId: Long?): Boolean {
    return entryId != null && when (result) {
        ShareSaveResult.CREATED,
        ShareSaveResult.DUPLICATE_ACTIVE,
        ShareSaveResult.RESTORED_FROM_PENDING_DELETE,
        -> true
        ShareSaveResult.BATCH_PROCESSED,
        ShareSaveResult.DUPLICATE_ARCHIVED,
        ShareSaveResult.PERSONAL_URL_LIMIT_REACHED,
        ShareSaveResult.SAVE_FAILED,
        ShareSaveResult.INPUT_TOO_LARGE,
        ShareSaveResult.INVALID_URL,
        ShareSaveResult.NO_URL_FOUND,
        -> false
    }
}

private fun shareReceiverResultMessage(
    result: SaveResult,
    degradationNotice: String?,
    tagAssignmentFailed: Boolean,
): String {
    return buildString {
        append(
            when (result.result) {
                ShareSaveResult.CREATED -> "保存しました"
                ShareSaveResult.DUPLICATE_ACTIVE -> "このURLはすでに保存されています"
                ShareSaveResult.DUPLICATE_ARCHIVED -> "このURLはアーカイブ済みです"
                ShareSaveResult.RESTORED_FROM_PENDING_DELETE -> "削除を取り消して復元しました"
                ShareSaveResult.PERSONAL_URL_LIMIT_REACHED -> "ローンチ版の保存上限に達しました。不要なURLを整理してから追加してください。"
                ShareSaveResult.SAVE_FAILED -> "保存できませんでした"
                ShareSaveResult.INPUT_TOO_LARGE -> "共有内容が長すぎるため処理できませんでした"
                ShareSaveResult.INVALID_URL -> "有効なURLではありませんでした"
                ShareSaveResult.NO_URL_FOUND -> "保存できる内容が見つかりませんでした"
                ShareSaveResult.BATCH_PROCESSED -> "保存しました"
            },
        )
        degradationNotice?.let { append("\n").append(degradationMessage(it)) }
        if (tagAssignmentFailed) append("\n保存しましたが、一部のタグ付けに失敗しました")
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

private fun degradationMessage(notice: String): String {
    return when (notice) {
        SHARE_DEGRADATION_TRUNCATED_TO_FIRST_URL -> "共有内容に複数URLが含まれていたため、1件目のみ保存しました"
        SHARE_DEGRADATION_TRUNCATED_TO_MAX_URLS -> "共有内容に多数のURLが含まれていたため、先頭${UrlRules.MAX_BATCH_SAVE_URLS_PER_INTAKE}件のみ処理しました"
        else -> ""
    }
}

private sealed interface ShareReceiverPayload {
    data class Pending(
        val urls: List<String>,
        val isBatch: Boolean,
        val memo: String? = null,
        val degradationNotice: String?,
    ) : ShareReceiverPayload

    data class Error(val result: ShareSaveResult) : ShareReceiverPayload

    data class TagImport(val payload: TagSharePayload) : ShareReceiverPayload
}

private data class ShareReceiverSaveOutcome(
    val message: String,
    val meaningfulAction: Boolean,
)
