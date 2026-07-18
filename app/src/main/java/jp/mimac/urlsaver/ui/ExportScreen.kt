package jp.mimac.urlsaver.ui

import android.content.ClipData
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Label
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.IosShare
import androidx.compose.material.icons.outlined.Tag
import androidx.compose.material.icons.outlined.Today
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import jp.mimac.urlsaver.data.ChatGptExportPreviewEntry
import jp.mimac.urlsaver.data.ExportRecordStateFilter
import jp.mimac.urlsaver.data.PreparedExportArchive
import jp.mimac.urlsaver.data.ExportScope
import jp.mimac.urlsaver.data.ExportTagOption
import jp.mimac.urlsaver.data.ExportOutputFormat
import jp.mimac.urlsaver.domain.ServiceType
import jp.mimac.urlsaver.domain.SharedTagScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate
import java.util.UUID

internal fun exportTodayDateInput(today: LocalDate = LocalDate.now()): String = today.toString()

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExportScreen(
    viewModel: ExportViewModel,
    onBack: () -> Unit,
    showSharedTagExportPreset: Boolean,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val availableTags by viewModel.availableTags.collectAsStateWithLifecycle()
    val chatGptUiState by viewModel.chatGptUiState.collectAsStateWithLifecycle()
    val availableChatGptTags by viewModel.availableChatGptTags.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) {
        pruneCachedExportArchives(context)
    }

    var exportMode by remember { mutableStateOf(ExportMode.STANDARD) }
    var isExporting by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var successMessage by remember { mutableStateOf<String?>(null) }
    var selectedFormat by remember { mutableStateOf(ExportOutputFormat.ZIP) }
    var selectedDestination by remember { mutableStateOf(ExportDestination.SHARE_SHEET) }
    var pendingFileArchive by remember { mutableStateOf<PreparedExportArchive?>(null) }
    var chatGptShareError by remember { mutableStateOf<String?>(null) }
    var chatGptShareSuccessMessage by remember { mutableStateOf<String?>(null) }
    val zipDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/zip"),
    ) { uri: Uri? ->
        val archive = pendingFileArchive
        pendingFileArchive = null
        if (uri == null || archive == null) return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.openOutputStream(uri)?.use { output ->
                output.write(archive.bytes)
            } ?: error("ファイルを開けませんでした")
        }.fold(
            onSuccess = { successMessage = "${archive.fileName} を保存しました" },
            onFailure = { throwable -> error = throwable.message ?: "ファイルに保存できませんでした" },
        )
    }
    val jsonDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json"),
    ) { uri: Uri? ->
        val archive = pendingFileArchive
        pendingFileArchive = null
        if (uri == null || archive == null) return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.openOutputStream(uri)?.use { output ->
                output.write(archive.bytes)
            } ?: error("ファイルを開けませんでした")
        }.fold(
            onSuccess = { successMessage = "${archive.fileName} を保存しました" },
            onFailure = { throwable -> error = throwable.message ?: "ファイルに保存できませんでした" },
        )
    }
    val selectedItems = remember(uiState, availableTags) {
        buildSelectedExportItems(uiState.scope, uiState.selectedTagIds, availableTags, uiState.serviceType)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = "エクスポート",
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = "ChatGPT、\nCodex、Claudeにも\n共有できるよ！",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f),
                            textAlign = TextAlign.End,
                            maxLines = 3,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    scrolledContainerColor = Color.Transparent,
                ),
                windowInsets = WindowInsets(0.dp),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "戻る")
                    }
                },
            )
        },
        containerColor = Color.Transparent,
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .padding(paddingValues)
                .fillMaxSize(),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .widthIn(max = 560.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(start = 18.dp, top = 0.dp, end = 18.dp, bottom = 8.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                ExportModeSelector(
                    selectedMode = exportMode,
                    onModeSelected = { mode ->
                        exportMode = mode
                        error = null
                        successMessage = null
                        chatGptShareError = null
                        chatGptShareSuccessMessage = null
                    },
                )

                when (exportMode) {
                    ExportMode.STANDARD -> {
                ExportSectionLabel("選択中")
                ExportChipRow {
                    selectedItems.forEach { item ->
                        SelectedExportChip(label = item.label) {
                            when (item.kind) {
                                SelectedExportItemKind.SCOPE -> {
                                    viewModel.selectScope(ExportScope.ALL)
                                }
                                SelectedExportItemKind.SERVICE -> {
                                    viewModel.selectServiceType(null)
                                }
                                SelectedExportItemKind.TAG -> {
                                    item.tagId?.let(viewModel::toggleTagSelection)
                                    if (uiState.selectedTagIds.size <= 1) {
                                        viewModel.selectScope(ExportScope.ALL)
                                    }
                                }
                            }
                        }
                    }
                }

                ExportSectionLabel("クイック選択")
                ExportChipRow {
                    ExportPresetTile("すべて", Icons.Outlined.Archive, uiState.scope == ExportScope.ALL && uiState.serviceType == null) {
                        viewModel.selectScope(ExportScope.ALL)
                        viewModel.selectServiceType(null)
                    }
                    if (shouldShowSharedTagExportPreset(showSharedTagExportPreset)) {
                        ExportPresetTile("共有タグだけ", Icons.Outlined.IosShare, uiState.scope == ExportScope.SHARED_TAGS_ONLY) {
                            viewModel.selectScope(ExportScope.SHARED_TAGS_ONLY)
                        }
                    }
                    ExportPresetTile("今日", Icons.Outlined.Today, false) {
                        val today = exportTodayDateInput()
                        viewModel.setDateFromInput(today)
                        viewModel.setDateToInput(today)
                    }
                    servicePresetOrder.forEach { serviceType ->
                        ExportPresetTile(serviceType.displayName, Icons.AutoMirrored.Outlined.Label, uiState.serviceType == serviceType) {
                            viewModel.selectScope(ExportScope.ALL)
                            viewModel.selectServiceType(serviceType)
                        }
                    }
                }

                ExportSectionLabel("タグを選択")
                if (availableTags.isEmpty()) {
                    Text(
                        text = "選択できるタグがありません",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    ExportTagGrid(
                        tags = availableTags,
                        selectedTagIds = uiState.selectedTagIds,
                        onToggle = { tag ->
                            if (uiState.scope != ExportScope.MULTIPLE_TAGS && uiState.scope != ExportScope.SINGLE_TAG) {
                                viewModel.selectScope(ExportScope.MULTIPLE_TAGS)
                            }
                            viewModel.toggleTagSelection(tag.id)
                        },
                    )
                }

                if (error != null) {
                    Text(
                        text = error.orEmpty(),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                if (successMessage != null) {
                    Text(
                        text = successMessage.orEmpty(),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                ExportActionSheet(
                    selectedFormat = selectedFormat,
                    onFormatSelected = { selectedFormat = it },
                    selectedDestination = selectedDestination,
                    onDestinationSelected = { selectedDestination = it },
                    isExporting = isExporting,
                    onExport = {
                        scope.launch {
                            isExporting = true
                            error = null
                            successMessage = null
                            val result = viewModel.prepareExport(selectedFormat)
                            result.fold(
                                onSuccess = { archive ->
                                    when (selectedDestination) {
                                        ExportDestination.SHARE_SHEET -> {
                                            shareExportArchive(context, archive)
                                            successMessage = "${archive.entryCount}件を書き出しました"
                                        }
                                        ExportDestination.FILE -> {
                                            pendingFileArchive = archive
                                            when (archive.mimeType) {
                                                ExportOutputFormat.JSON.mimeType -> jsonDocumentLauncher.launch(archive.fileName)
                                                else -> zipDocumentLauncher.launch(archive.fileName)
                                            }
                                        }
                                    }
                                },
                                onFailure = { throwable ->
                                    error = throwable.message ?: "エクスポートできませんでした"
                                },
                            )
                            isExporting = false
                        }
                    }
                )
                    }

                    ExportMode.CHAT_GPT -> {
                        ChatGptExportContent(
                            availableTags = availableChatGptTags,
                            uiState = chatGptUiState,
                            preparedArchive = chatGptUiState.preparedArchive,
                            isPreparingArchive = chatGptUiState.isArchivePreparing,
                            error = chatGptShareError ?: chatGptUiState.archiveError,
                            successMessage =
                                chatGptShareSuccessMessage ?: chatGptUiState.archiveSuccessMessage,
                            onToggleTag = { tagId ->
                                chatGptShareError = null
                                chatGptShareSuccessMessage = null
                                viewModel.toggleChatGptTagSelection(tagId)
                            },
                            onRetryPreview = {
                                chatGptShareError = null
                                chatGptShareSuccessMessage = null
                                viewModel.retryChatGptPreview()
                            },
                            onContentConfirmedChange = viewModel::setChatGptContentConfirmed,
                            onPrepareArchive = {
                                chatGptShareError = null
                                chatGptShareSuccessMessage = null
                                viewModel.prepareChatGptExport()
                            },
                            onSendToChatGpt = {
                                chatGptUiState.preparedArchive?.let { archive ->
                                    scope.launch {
                                        runCatching {
                                            shareChatGptArchive(context, archive)
                                        }.fold(
                                            onSuccess = {
                                                chatGptShareError = null
                                                chatGptShareSuccessMessage =
                                                    "共有を開始しました。ChatGPTで質問を入力して送信してください"
                                            },
                                            onFailure = { throwable ->
                                                chatGptShareSuccessMessage = null
                                                chatGptShareError = throwable.message
                                                    ?: "共有画面を開けませんでした。もう一度お試しください。"
                                            },
                                        )
                                    }
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

internal fun shouldShowSharedTagExportPreset(isSharedTagCloudEnabled: Boolean): Boolean =
    isSharedTagCloudEnabled

internal fun isChatGptZipCreationEnabled(
    selectedTagCount: Int,
    targetCount: Int,
    isContentConfirmed: Boolean,
    isPreviewLoading: Boolean,
    isPreparingArchive: Boolean,
): Boolean {
    return selectedTagCount > 0 &&
        targetCount > 0 &&
        isContentConfirmed &&
        !isPreviewLoading &&
        !isPreparingArchive
}

internal enum class ChatGptDirectShareOutcome {
    STARTED,
    ACTIVITY_NOT_FOUND,
    SECURITY_ERROR,
    OTHER_ERROR,
}

internal fun shouldFallbackToChatGptChooser(outcome: ChatGptDirectShareOutcome): Boolean {
    return outcome != ChatGptDirectShareOutcome.STARTED
}

@Composable
private fun ExportModeSelector(
    selectedMode: ExportMode,
    onModeSelected: (ExportMode) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ExportMode.entries.forEach { mode ->
            val selected = mode == selectedMode
            val selectedColor = exportSelectedColor()
            Surface(
                onClick = { onModeSelected(mode) },
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 52.dp)
                    .semantics {
                        this.selected = selected
                        role = Role.Tab
                        stateDescription = if (selected) "選択中" else "未選択"
                    },
                shape = MaterialTheme.shapes.large,
                color = if (selected) {
                    exportSelectedBackground(0.18f)
                } else {
                    MaterialTheme.colorScheme.surface
                },
                border = BorderStroke(
                    width = if (selected) 1.6.dp else 1.dp,
                    color = if (selected) selectedColor else MaterialTheme.colorScheme.outlineVariant,
                ),
            ) {
                Box(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = mode.label,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = if (selected) selectedColor else MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

@Composable
private fun ChatGptExportContent(
    availableTags: List<ExportTagOption>,
    uiState: ChatGptExportUiState,
    preparedArchive: PreparedExportArchive?,
    isPreparingArchive: Boolean,
    error: String?,
    successMessage: String?,
    onToggleTag: (Long) -> Unit,
    onRetryPreview: () -> Unit,
    onContentConfirmedChange: (Boolean) -> Unit,
    onPrepareArchive: () -> Unit,
    onSendToChatGpt: () -> Unit,
) {
    val preview = preparedArchive?.chatGptPreview ?: uiState.preview
    val targetCount = preview?.entries?.size ?: 0

    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 2.dp),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "ChatGPTに聞く",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = "りんばむでは質問を入力しません。確認したリンクをZIPにして渡し、質問とモデル選択はChatGPTの通常のトーク画面で行います。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    ExportSectionLabel("1. 渡したい自作タグを選択")
    if (availableTags.isEmpty()) {
        Text(
            text = "URLが付いた自作タグがありません",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    } else {
        ExportTagGrid(
            tags = availableTags,
            selectedTagIds = uiState.selectedTagIds,
            onToggle = { tag -> onToggleTag(tag.id) },
        )
    }

    ExportSectionLabel("2. 渡す内容を確認")
    ChatGptContentBoundaryCard()

    when {
        uiState.selectedTagIds.isEmpty() -> {
            Text(
                text = "自作タグを1つ以上選ぶと、対象URLを全件表示します。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        uiState.isPreviewLoading -> {
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                Text("対象URLを確認しています")
            }
        }
        uiState.previewError != null -> {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = uiState.previewError,
                    color = MaterialTheme.colorScheme.error,
                )
                OutlinedButton(onClick = onRetryPreview) {
                    Text("もう一度確認")
                }
            }
        }
        preview != null -> {
            val snapshotLabel = if (preparedArchive != null) "生成後の対象" else "対象"
            Text(
                text = "$snapshotLabel ${preview.entries.size}件 / 除外 ${preview.excludedCount}件",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            if (preview.selectedTagNames.isNotEmpty()) {
                Text(
                    text = "ZIPに入る自作タグ名（伏せ字後）: ${preview.selectedTagNames.joinToString("、")}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (preview.excludedCount > 0) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    chatGptExclusionReasonOrder.forEach { reason ->
                        val count = preview.exclusionsByReason[reason].orZero()
                        if (count > 0) {
                            Text(
                                text = "・${chatGptExclusionReasonLabel(reason)}: ${count}件",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            if (preview.entries.isEmpty()) {
                Text(
                    text = "選択した自作タグに、ChatGPTへ渡せるURLがありません。",
                    color = MaterialTheme.colorScheme.error,
                )
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    itemsIndexed(
                        items = preview.entries,
                        key = { index, entry -> "${entry.publicSafeId}:$index" },
                    ) { index, entry ->
                        ChatGptPreviewEntryCard(index = index + 1, entry = entry)
                    }
                }
            }
        }
    }

    if (preview != null && preview.entries.isNotEmpty() && !uiState.isPreviewLoading) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .toggleable(
                    value = uiState.isContentConfirmed,
                    role = Role.Checkbox,
                    onValueChange = onContentConfirmedChange,
                )
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Checkbox(
                checked = uiState.isContentConfirmed,
                onCheckedChange = null,
            )
            Text(
                text = "対象URLと表示内容を確認し、未知の秘密が含まれていないことを確認しました",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }

    ExportSectionLabel("3. ChatGPT用ファイルを作成")
    Button(
        onClick = onPrepareArchive,
        enabled = isChatGptZipCreationEnabled(
            selectedTagCount = uiState.selectedTagIds.size,
            targetCount = targetCount,
            isContentConfirmed = uiState.isContentConfirmed,
            isPreviewLoading = uiState.isPreviewLoading,
            isPreparingArchive = isPreparingArchive,
        ),
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp),
    ) {
        if (isPreparingArchive) {
            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            Text(
                text = "ZIPを作成しています",
                modifier = Modifier.padding(start = 10.dp),
            )
        } else {
            Text("ChatGPT用ZIPを作成")
        }
    }

    if (error != null) {
        Text(text = error, color = MaterialTheme.colorScheme.error)
    }
    if (successMessage != null) {
        Text(
            text = successMessage,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    ExportSectionLabel("4. ChatGPTに送る")
    if (preparedArchive == null) {
        Text(
            text = "先に対象を確認してZIPを作成してください。作成しただけでは共有されません。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    } else {
        OutlinedCard(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surface),
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = preparedArchive.fileName,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "生成時点の対象 ${preparedArchive.entryCount}件。送信後、ChatGPTで質問を入力してください。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        OutlinedButton(
            onClick = onSendToChatGpt,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
        ) {
            Icon(
                imageVector = Icons.Outlined.IosShare,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
            )
            Text(
                text = "ChatGPTに送る",
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }
}

@Composable
private fun ChatGptContentBoundaryCard() {
    OutlinedCard(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "含まれるもの（固定）",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = "URL、タイトル、自作タグ、保存日時、メモ抜粋、取得できた著者・要約・抜粋などの保存時点情報",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                text = "含まれないもの（固定）",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = "質問、PDF・画像本体、取得本文全文、共有タグと参加者、削除待ち・アーカイブ・共有参照のURL",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                text = "伏せ字になるもの",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = "メールアドレス、電話番号、token・secret、JWT、Supabase情報、端末内パスは、検出できた範囲を伏せ字にします。共有前に対象URLと表示内容を確認してください。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ChatGptPreviewEntryCard(
    index: Int,
    entry: ChatGptExportPreviewEntry,
) {
    OutlinedCard(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = "$index. ${entry.effectiveTitle}",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = entry.normalizedUrl,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (entry.localTagNames.isNotEmpty()) {
                Text(
                    text = "自作タグ: ${entry.localTagNames.joinToString("、")}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = "ZIPに入る伏せ字後の内容",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 6.dp),
            )
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.48f),
                shape = MaterialTheme.shapes.small,
            ) {
                SelectionContainer {
                    Text(
                        text = entry.archiveEntryJson,
                        modifier = Modifier.padding(10.dp),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

private fun Int?.orZero(): Int = this ?: 0

private fun chatGptExclusionReasonLabel(reason: String): String {
    return when (reason) {
        "pending_delete" -> "削除待ち"
        "archived_or_not_active" -> "アーカイブなど保存中ではないURL"
        "no_local_provenance" -> "この端末で保存した履歴なし"
        "shared_reference_or_tag_allocation" -> "共有参照または共有タグ割り当てあり"
        else -> "安全条件の対象外"
    }
}

private val chatGptExclusionReasonOrder = listOf(
    "pending_delete",
    "archived_or_not_active",
    "no_local_provenance",
    "shared_reference_or_tag_allocation",
)

@Composable
private fun ExportSectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
    )
}

@Composable
private fun ExportPresetTile(
    label: String,
    icon: ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val selectedColor = exportSelectedColor()
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.large,
        color = if (selected) exportSelectedBackground(0.18f) else MaterialTheme.colorScheme.surface,
        border = BorderStroke(
            width = 1.dp,
            color = if (selected) selectedColor else MaterialTheme.colorScheme.outlineVariant,
        ),
        tonalElevation = if (selected) 2.dp else 0.dp,
    ) {
        Row(
            modifier = Modifier
                .heightIn(min = 48.dp)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                icon,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = if (selected) selectedColor else MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = if (selected) selectedColor else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun SelectedExportChip(
    label: String,
    onRemove: () -> Unit,
) {
    val selectedColor = exportSelectedColor()
    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        color = exportSelectedBackground(0.18f),
        border = BorderStroke(1.dp, selectedColor.copy(alpha = 0.45f)),
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp, end = 8.dp, top = 7.dp, bottom = 7.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = selectedColor,
            )
            Icon(
                imageVector = Icons.Outlined.Close,
                contentDescription = "$label を外す",
                modifier = Modifier
                    .size(16.dp)
                    .clickable(onClick = onRemove),
                tint = selectedColor,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ExportTagGrid(
    tags: List<ExportTagOption>,
    selectedTagIds: Set<Long>,
    onToggle: (ExportTagOption) -> Unit,
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        tags.forEach { tag ->
            ExportTagCell(
                tag = tag,
                selected = tag.id in selectedTagIds,
                onClick = { onToggle(tag) },
            )
        }
    }
}

@Composable
private fun ExportTagCell(
    tag: ExportTagOption,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val selectedColor = exportSelectedColor()
    OutlinedCard(
        onClick = onClick,
        modifier = Modifier
            .heightIn(min = 48.dp)
            .widthIn(max = 280.dp)
            .semantics {
                this.selected = selected
                role = Role.Checkbox
                stateDescription = if (selected) "選択中" else "未選択"
            },
        colors = CardDefaults.outlinedCardColors(
            containerColor = if (selected) {
                exportSelectedBackground(0.14f)
            } else {
                MaterialTheme.colorScheme.surface
            },
        ),
        border = BorderStroke(
            width = if (selected) 1.6.dp else 1.dp,
            color = if (selected) selectedColor else MaterialTheme.colorScheme.outlineVariant,
        ),
    ) {
        Row(
            modifier = Modifier
                .heightIn(min = 48.dp)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = if (selected) Icons.Outlined.Check else Icons.Outlined.Tag,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = if (selected) selectedColor else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = tag.name,
                modifier = Modifier.widthIn(max = 220.dp),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun ExportActionSheet(
    selectedFormat: ExportOutputFormat,
    onFormatSelected: (ExportOutputFormat) -> Unit,
    selectedDestination: ExportDestination,
    onDestinationSelected: (ExportDestination) -> Unit,
    isExporting: Boolean,
    onExport: () -> Unit,
) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 3.dp),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            ExportSectionLabel("書き出し形式")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ExportOutputFormat.entries.forEach { format ->
                    ExportFormatButton(
                        format = format,
                        selected = selectedFormat == format,
                        onClick = { onFormatSelected(format) },
                    )
                }
            }

            ExportSectionLabel("保存先")
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ExportDestination.entries.forEach { destination ->
                    ExportDestinationRow(
                        destination = destination,
                        selected = selectedDestination == destination,
                        onClick = { onDestinationSelected(destination) },
                    )
                }
            }

            Button(
                onClick = onExport,
                enabled = !isExporting,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
            ) {
                if (isExporting) {
                    CircularProgressIndicator(
                        modifier = Modifier.height(18.dp),
                        strokeWidth = 2.dp,
                    )
                } else {
                    Text("書き出す")
                }
            }
        }
    }
}

@Composable
private fun RowScope.ExportFormatButton(
    format: ExportOutputFormat,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val selectedColor = exportSelectedColor()
    Surface(
        onClick = onClick,
        modifier = Modifier
            .weight(1f)
            .height(68.dp),
        shape = MaterialTheme.shapes.large,
        color = if (selected) exportSelectedBackground(0.16f) else MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(
            width = if (selected) 1.6.dp else 1.dp,
            color = if (selected) selectedColor else MaterialTheme.colorScheme.outlineVariant,
        ),
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                format.icon,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = if (selected) selectedColor else MaterialTheme.colorScheme.onSurface,
            )
            Text(
                format.label,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = if (selected) selectedColor else MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
private fun ExportDestinationRow(
    destination: ExportDestination,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val selectedColor = exportSelectedColor()
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.large,
        color = if (selected) exportSelectedBackground(0.14f) else MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, if (selected) selectedColor else MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp)
                .padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                destination.icon,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = if (selected) selectedColor else MaterialTheme.colorScheme.onSurface,
            )
            Text(
                destination.label,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = if (selected) selectedColor else MaterialTheme.colorScheme.onSurface,
            )
            if (selected) {
                Icon(
                    Icons.Outlined.Check,
                    contentDescription = "選択中",
                    modifier = Modifier.size(18.dp),
                    tint = selectedColor,
                )
            }
        }
    }
}

@Composable
private fun exportSelectedBackground(alpha: Float): Color = Color(0xFF65B0FF).copy(alpha = alpha)

@Composable
private fun exportSelectedColor(): Color {
    return if (isSystemInDarkTheme()) {
        Color(0xFF8BC3FF)
    } else {
        Color(0xFF1F6FD1)
    }
}

@Composable
private fun ExportChipRow(
    content: @Composable () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        content()
    }
}

private enum class ExportDestination(val label: String, val icon: ImageVector) {
    SHARE_SHEET("共有シート", Icons.Outlined.IosShare),
    FILE("ファイルに保存", Icons.Outlined.Folder),
}

private enum class ExportMode(val label: String) {
    STANDARD("通常のエクスポート"),
    CHAT_GPT("ChatGPTに聞く"),
}

private val servicePresetOrder = listOf(
    ServiceType.TIKTOK,
    ServiceType.INSTAGRAM,
    ServiceType.YOUTUBE,
    ServiceType.X,
    ServiceType.WEB,
)

private data class SelectedExportItem(
    val label: String,
    val kind: SelectedExportItemKind,
    val tagId: Long? = null,
)

private enum class SelectedExportItemKind {
    SCOPE,
    SERVICE,
    TAG,
}

private fun buildSelectedExportItems(
    scope: ExportScope,
    selectedTagIds: Set<Long>,
    availableTags: List<ExportTagOption>,
    serviceType: ServiceType?,
): List<SelectedExportItem> {
    val items = mutableListOf<SelectedExportItem>()
    when (scope) {
        ExportScope.ALL -> if (serviceType == null && selectedTagIds.isEmpty()) {
            SelectedExportItem("すべて", SelectedExportItemKind.SCOPE)
        } else {
            null
        }
        ExportScope.SINGLE_TAG, ExportScope.MULTIPLE_TAGS -> null
        ExportScope.SHARED_TAGS_ONLY -> SelectedExportItem("共有タグ", SelectedExportItemKind.SCOPE)
    }?.let { items += it }
    serviceType?.let { items += SelectedExportItem(it.displayName, SelectedExportItemKind.SERVICE) }
    items += availableTags
        .filter { it.id in selectedTagIds }
        .map { SelectedExportItem(it.name, SelectedExportItemKind.TAG, tagId = it.id) }
    return items.distinctBy { "${it.kind}:${it.tagId}:${it.label}" }
}

private fun recordStateLabel(filter: ExportRecordStateFilter): String {
    return when (filter) {
        ExportRecordStateFilter.ACTIVE -> "保存中"
        ExportRecordStateFilter.ARCHIVED -> "アーカイブ"
        ExportRecordStateFilter.BOTH -> "両方"
    }
}

private fun exportScopeLabel(scope: ExportScope): String {
    return when (scope) {
        ExportScope.ALL -> "すべて"
        ExportScope.SINGLE_TAG -> "タグを1つ選択"
        ExportScope.MULTIPLE_TAGS -> "複数タグを選択"
        ExportScope.SHARED_TAGS_ONLY -> "共有タグのみ"
    }
}

private fun exportTagOptionLabel(tag: ExportTagOption): String {
    val scope = when (tag.scope) {
        SharedTagScope.LOCAL_ONLY -> "通常タグ"
        SharedTagScope.SYNCED -> "共有タグ"
    }
    return "${tag.name}・$scope"
}

private suspend fun shareExportArchive(
    context: Context,
    archive: PreparedExportArchive,
) {
    val uri = cacheExportArchive(context, archive)
    val shareIntent = buildArchiveShareIntent(context, archive, uri)
    context.startActivity(Intent.createChooser(shareIntent, "エクスポートを共有"))
}

private suspend fun shareChatGptArchive(
    context: Context,
    archive: PreparedExportArchive,
) {
    val uri = cacheExportArchive(context, archive)
    val directIntent = buildChatGptDirectShareIntent(context, archive, uri)
    val directOutcome = try {
        context.startActivity(directIntent)
        ChatGptDirectShareOutcome.STARTED
    } catch (_: ActivityNotFoundException) {
        ChatGptDirectShareOutcome.ACTIVITY_NOT_FOUND
    } catch (_: SecurityException) {
        ChatGptDirectShareOutcome.SECURITY_ERROR
    } catch (_: RuntimeException) {
        ChatGptDirectShareOutcome.OTHER_ERROR
    }
    if (!shouldFallbackToChatGptChooser(directOutcome)) return

    val fallbackIntent = buildArchiveShareIntent(context, archive, uri)
    val chooser = Intent.createChooser(fallbackIntent, "ChatGPT用ZIPを共有").apply {
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(chooser)
}

internal suspend fun cacheExportArchive(
    context: Context,
    archive: PreparedExportArchive,
): Uri {
    val appContext = context.applicationContext
    val targetFile = writeCachedExportArchive(appContext, archive)
    return FileProvider.getUriForFile(
        appContext,
        "${appContext.packageName}.fileprovider",
        targetFile,
    )
}

internal suspend fun writeCachedExportArchive(
    context: Context,
    archive: PreparedExportArchive,
): File {
    require(!archive.fileName.startsWith("rinbam-chatgpt-") || archive.bytes.size <= MAX_CHATGPT_ARCHIVE_BYTES) {
        "ChatGPT用ZIPが大きすぎます（上限 ${MAX_CHATGPT_ARCHIVE_BYTES / BYTES_PER_MIB} MiB）。タグを分けてお試しください。"
    }
    return withContext(Dispatchers.IO) {
        val exportDir = File(context.cacheDir, "exports").apply { mkdirs() }
        pruneCachedExportArchives(exportDir)
        val safeArchiveName = File(archive.fileName).name
        val shareDirectory = File(exportDir, UUID.randomUUID().toString()).apply { mkdirs() }
        val targetFile = File(shareDirectory, safeArchiveName)
        targetFile.writeBytes(archive.bytes)
        targetFile
    }
}

internal data class CachedExportFileInfo(
    val name: String,
    val lastModified: Long,
)

internal fun cachedExportFileNamesToPrune(
    files: List<CachedExportFileInfo>,
    nowEpochMillis: Long,
    maxAgeMillis: Long = CACHED_EXPORT_MAX_AGE_MILLIS,
    maxRetainedFiles: Int = CACHED_EXPORT_MAX_FILES - 1,
): Set<String> {
    val expiryBoundary = nowEpochMillis - maxAgeMillis
    val expired = files.filter { file -> file.lastModified < expiryBoundary }
    val retained = files.filterNot { file -> file in expired }
    val overflowCount = (retained.size - maxRetainedFiles).coerceAtLeast(0)
    val overflow = retained
        .sortedWith(compareBy<CachedExportFileInfo> { file -> file.lastModified }.thenBy { file -> file.name })
        .take(overflowCount)
    return (expired + overflow).mapTo(mutableSetOf()) { file -> file.name }
}

private fun pruneCachedExportArchives(
    exportDir: File,
    nowEpochMillis: Long = System.currentTimeMillis(),
) {
    val files = exportDir.listFiles()?.toList().orEmpty()
    val namesToPrune = cachedExportFileNamesToPrune(
        files = files.map { file ->
            CachedExportFileInfo(name = file.name, lastModified = file.lastModified())
        },
        nowEpochMillis = nowEpochMillis,
    )
    files.filter { file -> file.name in namesToPrune }.forEach { file ->
        if (file.isDirectory) file.deleteRecursively() else file.delete()
    }
}

internal suspend fun pruneCachedExportArchives(context: Context) {
    withContext(Dispatchers.IO) {
        pruneCachedExportArchives(File(context.cacheDir, "exports"))
    }
}

internal fun buildArchiveShareIntent(
    context: Context,
    archive: PreparedExportArchive,
    uri: Uri,
): Intent {
    return Intent(Intent.ACTION_SEND).apply {
        type = archive.mimeType
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_SUBJECT, archive.fileName)
        clipData = ClipData.newUri(context.contentResolver, archive.fileName, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}

internal fun buildChatGptDirectShareIntent(
    context: Context,
    archive: PreparedExportArchive,
    uri: Uri,
): Intent = buildArchiveShareIntent(context, archive, uri).apply {
    setPackage(CHATGPT_ANDROID_PACKAGE)
}

internal const val CHATGPT_ANDROID_PACKAGE = "com.openai.chatgpt"
private const val CACHED_EXPORT_MAX_FILES = 64
private const val CACHED_EXPORT_MAX_AGE_MILLIS = 7L * 24L * 60L * 60L * 1_000L
internal const val BYTES_PER_MIB = 1024 * 1024
internal const val MAX_CHATGPT_ARCHIVE_BYTES = 25 * BYTES_PER_MIB

private val ExportOutputFormat.label: String
    get() = when (this) {
        ExportOutputFormat.ZIP -> "ZIP"
        ExportOutputFormat.JSON -> "JSON"
    }

private val ExportOutputFormat.icon: ImageVector
    get() = when (this) {
        ExportOutputFormat.ZIP -> Icons.Outlined.Archive
        ExportOutputFormat.JSON -> Icons.Outlined.Description
    }
