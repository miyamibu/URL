package jp.mimac.urlsaver.ui

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
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import jp.mimac.urlsaver.data.ExportRecordStateFilter
import jp.mimac.urlsaver.data.PreparedExportArchive
import jp.mimac.urlsaver.data.ExportScope
import jp.mimac.urlsaver.data.ExportTagOption
import jp.mimac.urlsaver.data.ExportOutputFormat
import jp.mimac.urlsaver.domain.ServiceType
import jp.mimac.urlsaver.domain.SharedTagScope
import kotlinx.coroutines.launch
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExportScreen(
    viewModel: ExportViewModel,
    onBack: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val availableTags by viewModel.availableTags.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var isExporting by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var successMessage by remember { mutableStateOf<String?>(null) }
    var selectedFormat by remember { mutableStateOf(ExportOutputFormat.ZIP) }
    var selectedDestination by remember { mutableStateOf(ExportDestination.SHARE_SHEET) }
    var pendingFileArchive by remember { mutableStateOf<PreparedExportArchive?>(null) }
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
                    ExportPresetTile("共有タグだけ", Icons.Outlined.IosShare, uiState.scope == ExportScope.SHARED_TAGS_ONLY) {
                        viewModel.selectScope(ExportScope.SHARED_TAGS_ONLY)
                    }
                    ExportPresetTile("今日", Icons.Outlined.Today, false) {
                        viewModel.setDateFromInput("2026-04-30")
                        viewModel.setDateToInput("2026-04-30")
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
        }
    }
}

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
            .widthIn(max = 280.dp),
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

private fun shareExportArchive(
    context: Context,
    archive: PreparedExportArchive,
) {
    val exportDir = File(context.cacheDir, "exports").apply { mkdirs() }
    exportDir.listFiles()?.forEach(File::delete)
    val targetFile = File(exportDir, archive.fileName)
    targetFile.writeBytes(archive.bytes)
    val uri = FileProvider.getUriForFile(
        context,
        "${context.packageName}.fileprovider",
        targetFile,
    )
    val shareIntent = Intent(Intent.ACTION_SEND).apply {
        type = archive.mimeType
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_SUBJECT, archive.fileName)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(shareIntent, "エクスポートを共有"))
}

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
