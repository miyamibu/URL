package jp.mimac.urlsaver.ui

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ExitToApp
import androidx.compose.material.icons.automirrored.outlined.ViewList
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.IosShare
import androidx.compose.material.icons.outlined.LinkOff
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.ViewAgenda
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.core.content.FileProvider
import jp.mimac.urlsaver.domain.EntryCardDisplayMode
import jp.mimac.urlsaver.domain.AssignTagResult
import jp.mimac.urlsaver.domain.SharedTagInviteCreationResult
import jp.mimac.urlsaver.domain.SharedTagMemberRecord
import jp.mimac.urlsaver.domain.SharedTagMemberRole
import jp.mimac.urlsaver.domain.SharedTagOwnershipTransferResult
import jp.mimac.urlsaver.domain.SharedTagScope
import jp.mimac.urlsaver.ui.components.EntryCard
import jp.mimac.urlsaver.ui.components.OrbitActionButton
import jp.mimac.urlsaver.ui.components.OrbitActionStyle
import jp.mimac.urlsaver.ui.theme.OrbitTokens
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import kotlin.math.abs

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TagDetailScreen(
    viewModel: TagDetailViewModel,
    onBack: () -> Unit,
    onOpenDetail: (Long) -> Unit,
    onOpenCloudAuth: () -> Unit,
) {
    val tag by viewModel.tag.collectAsStateWithLifecycle()
    val entries by viewModel.entries.collectAsStateWithLifecycle()
    val members by viewModel.members.collectAsStateWithLifecycle()
    val availableEntriesToAdd by viewModel.availableEntriesToAdd.collectAsStateWithLifecycle()
    val cloudState by viewModel.cloudState.collectAsStateWithLifecycle()
    val entryCardDisplayMode by viewModel.entryCardDisplayMode.collectAsStateWithLifecycle()
    val isSyncing by viewModel.isSyncing.collectAsStateWithLifecycle()
    val syncNotice by viewModel.syncNotice.collectAsStateWithLifecycle()
    val syncNoticeIsError by viewModel.syncNoticeIsError.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var showAddEntrySheet by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showLeaveDialog by remember { mutableStateOf(false) }
    var showShareOptionsDialog by remember { mutableStateOf(false) }
    var showLocalTagShareDialog by remember { mutableStateOf(false) }
    var pendingOwnershipTransferMember by remember { mutableStateOf<SharedTagMemberRecord?>(null) }
    var shareError by remember { mutableStateOf<String?>(null) }
    var memberRemoveError by remember { mutableStateOf<String?>(null) }
    var ownershipTransferError by remember { mutableStateOf<String?>(null) }
    var entryRemoveError by remember { mutableStateOf<String?>(null) }
    var entryAddError by remember { mutableStateOf<String?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }
    val addEntrySheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    LaunchedEffect(tag?.id, tag?.scope) {
        if (tag?.scope == SharedTagScope.SYNCED) {
            viewModel.syncSharedTagNow(showSuccessNotice = false)
        }
    }

    val currentTag = tag
    if (currentTag == null) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("共有タグ") },
                    windowInsets = TopAppBarDefaults.windowInsets.only(WindowInsetsSides.Horizontal),
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "戻る")
                        }
                    },
                )
            },
        ) { paddingValues ->
            Box(
                modifier = Modifier
                    .padding(paddingValues)
                    .fillMaxSize()
                    .padding(24.dp),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.LinkOff,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = "共有タグが見つかりませんでした",
                        style = MaterialTheme.typography.titleMedium,
                        textAlign = TextAlign.Center,
                    )
                    Text(
                        text = "削除されたか、まだ読み込めていない可能性があります",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
        return
    }
    val listState = remember(currentTag.id) { LazyListState() }
    var pendingRemovedEntryIds by remember(currentTag.id) { mutableStateOf<Set<Long>>(emptySet()) }
    val visibleEntries = entries.filterNot { it.id in pendingRemovedEntryIds }

    LaunchedEffect(currentTag.id) {
        listState.scrollToItem(0)
    }

    LaunchedEffect(currentTag.id, currentTag.scope, cloudState.isSignedIn) {
        if (currentTag.scope == SharedTagScope.LOCAL_ONLY && cloudState.isSignedIn) {
            viewModel.migrateToCloud()
        }
    }

    val isLocalTag = currentTag.scope == SharedTagScope.LOCAL_ONLY
    val tagKindLabel = if (isLocalTag) "自作タグ" else "共有タグ"
    val canEditEntries = isLocalTag ||
        currentTag.currentUserRole == SharedTagMemberRole.OWNER ||
        currentTag.currentUserRole == SharedTagMemberRole.EDITOR
    val canShareInvite = currentTag.scope == SharedTagScope.SYNCED &&
        currentTag.currentUserRole == SharedTagMemberRole.OWNER
    val canShareTag = currentTag.scope == SharedTagScope.SYNCED && canShareInvite
    val canDeleteTag = isLocalTag ||
        (currentTag.scope == SharedTagScope.SYNCED && currentTag.currentUserRole == SharedTagMemberRole.OWNER)
    val canLeaveSharedTag = currentTag.scope == SharedTagScope.SYNCED &&
        currentTag.currentUserRole != null &&
        currentTag.currentUserRole != SharedTagMemberRole.OWNER

    fun launchShare(text: String, chooserTitle: String) {
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        context.startActivity(Intent.createChooser(shareIntent, chooserTitle))
    }

    fun launchLocalTagShare(payloadText: String): Boolean {
        val shareIntent = runCatching {
            val exportDirectory = File(context.cacheDir, "exports").apply { mkdirs() }
            val exportFile = File.createTempFile("tag-share-", ".json", exportDirectory).apply {
                writeText(payloadText, Charsets.UTF_8)
            }
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                exportFile,
            )
            Intent(Intent.ACTION_SEND).apply {
                type = "application/json"
                putExtra(Intent.EXTRA_STREAM, uri)
                clipData = ClipData.newRawUri("自作タグ", uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }.getOrElse {
            Intent(Intent.ACTION_SEND).apply {
                type = "application/json"
                putExtra(Intent.EXTRA_TEXT, payloadText)
            }
        }
        return runCatching {
            context.startActivity(Intent.createChooser(shareIntent, "自作タグを共有"))
            true
        }.getOrDefault(false)
    }

    fun scheduleEntryRemoval(entryId: Long) {
        if (entryId in pendingRemovedEntryIds) return
        entryRemoveError = null
        pendingRemovedEntryIds = pendingRemovedEntryIds + entryId
        scope.launch {
            val result = withTimeoutOrNull(5_000L) {
                snackbarHostState.showSnackbar(
                    message = "${tagKindLabel}から外しました",
                    actionLabel = "元に戻す",
                    duration = SnackbarDuration.Indefinite,
                )
            }
            if (result == SnackbarResult.ActionPerformed) {
                pendingRemovedEntryIds = pendingRemovedEntryIds - entryId
                return@launch
            }
            val removed = viewModel.removeEntryFromTag(entryId)
            pendingRemovedEntryIds = pendingRemovedEntryIds - entryId
            if (!removed) {
                entryRemoveError = "${tagKindLabel}から外せませんでした"
            }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {},
                windowInsets = TopAppBarDefaults.windowInsets.only(WindowInsetsSides.Horizontal),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "戻る")
                    }
                },
                actions = {
                    SharedTagHeaderIconButton(onClick = { viewModel.toggleEntryCardDisplayMode() }) {
                        Icon(
                            imageVector = if (entryCardDisplayMode == EntryCardDisplayMode.RICH) {
                                Icons.AutoMirrored.Outlined.ViewList
                            } else {
                                Icons.Outlined.ViewAgenda
                            },
                            contentDescription = if (entryCardDisplayMode == EntryCardDisplayMode.RICH) {
                                "画像なし表示へ切り替える"
                            } else {
                                "画像つき表示へ切り替える"
                            },
                            modifier = Modifier.size(SharedTagHeaderIconSize),
                        )
                    }
                    if (currentTag.scope == SharedTagScope.SYNCED) {
                        SharedTagHeaderIconButton(
                            enabled = !isSyncing,
                            onClick = { viewModel.syncSharedTagNow() },
                        ) {
                            if (isSyncing) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(24.dp),
                                    strokeWidth = 2.dp,
                                )
                            } else {
                                Icon(
                                    Icons.Outlined.Refresh,
                                    contentDescription = "共有タグを更新",
                                    modifier = Modifier.size(SharedTagHeaderIconSize),
                                )
                            }
                        }
                    }
                    if (canEditEntries) {
                        SharedTagHeaderIconButton(onClick = { showAddEntrySheet = true }) {
                            Icon(
                                Icons.Outlined.Add,
                                contentDescription = "保存済みURLを追加",
                                modifier = Modifier.size(SharedTagHeaderIconSize),
                            )
                        }
                    }
                    if (canLeaveSharedTag) {
                        SharedTagHeaderIconButton(onClick = { showLeaveDialog = true }) {
                            Icon(
                                Icons.AutoMirrored.Outlined.ExitToApp,
                                contentDescription = "この共有タグから抜ける",
                                modifier = Modifier.size(SharedTagHeaderIconSize),
                            )
                        }
                    }
                    if (canShareTag) {
                        SharedTagHeaderIconButton(
                            onClick = { showShareOptionsDialog = true },
                        ) {
                            Icon(
                                Icons.Outlined.IosShare,
                                contentDescription = "タグを共有",
                                modifier = Modifier.size(SharedTagHeaderIconSize),
                            )
                        }
                    }
                    if (canDeleteTag) {
                        SharedTagHeaderIconButton(onClick = { showDeleteDialog = true }) {
                            Icon(
                                Icons.Outlined.Delete,
                                contentDescription = "${tagKindLabel}を削除",
                                modifier = Modifier.size(SharedTagHeaderIconSize),
                            )
                        }
                    }
                },
            )
        },
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .padding(paddingValues)
                .fillMaxSize(),
            contentAlignment = Alignment.TopCenter,
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .widthIn(max = 680.dp),
            ) {
                item {
                    SharedTagInlineTitle(
                        title = currentTag.name,
                        isSyncing = isSyncing,
                        syncNotice = syncNotice,
                        syncNoticeIsError = syncNoticeIsError,
                    )
                }
                if (currentTag.scope == SharedTagScope.SYNCED && cloudState.isConfigured && !cloudState.isSignedIn) {
                    item {
                        CloudSignInBanner(onOpenCloudAuth = onOpenCloudAuth)
                    }
                }
                if (currentTag.scope == SharedTagScope.SYNCED) {
                    item {
                        SyncedTagInfo(
                            shareError = shareError,
                        )
                    }
                    item {
                        SharedTagMembersPanel(
                            members = members,
                            canTransferOwnership = currentTag.currentUserRole == SharedTagMemberRole.OWNER,
                            onTransferOwnership = { member ->
                                ownershipTransferError = null
                                pendingOwnershipTransferMember = member
                            },
                        )
                        ownershipTransferError?.let { message ->
                            Text(
                                text = message,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                            )
                        }
                        memberRemoveError?.let { message ->
                            Text(
                                text = message,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                            )
                        }
                    }
                }
                if (currentTag.scope == SharedTagScope.SYNCED) {
                    item {
                        Spacer(Modifier.height(2.dp))
                    }
                }
                item {
                    SharedTagEntryCountRow(
                        count = visibleEntries.size,
                    )
                }
                if (isLocalTag) {
                    item {
                        OutlinedButton(
                            onClick = { showLocalTagShareDialog = true },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 4.dp)
                                .height(52.dp),
                        ) {
                            Icon(Icons.Outlined.IosShare, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("自作タグを共有")
                        }
                    }
                }
                if (visibleEntries.isEmpty()) {
                    item {
                        TagEmptyPlaceholder(isLocalTag = isLocalTag)
                    }
                } else {
                    items(visibleEntries, key = { it.id }) { entry ->
                        SwipeableTagEntry(
                            entry = entry,
                            displayMode = entryCardDisplayMode,
                            canRemove = canEditEntries,
                            onClick = { onOpenDetail(entry.id) },
                            onRemove = { scheduleEntryRemoval(entry.id) },
                        )
                    }
                }
                if (canDeleteTag) {
                    item {
                        TextButton(
                            onClick = { showDeleteDialog = true },
                            modifier = Modifier.padding(horizontal = 8.dp),
                        ) {
                            Text("この${tagKindLabel}を削除")
                        }
                    }
                }
                entryRemoveError?.let { message ->
                    item {
                        Text(
                            text = message,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        )
                    }
                }
            }
        }
    }

    if (showShareOptionsDialog) {
        AlertDialog(
            onDismissRequest = { showShareOptionsDialog = false },
            title = { Text("タグを共有") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (canShareInvite) {
                        TextButton(
                            onClick = {
                                showShareOptionsDialog = false
                                scope.launch {
                                    when (val result = viewModel.createInviteLink()) {
                                        is SharedTagInviteCreationResult.Success -> {
                                            shareError = null
                                            launchShare(
                                                text = inviteShareText(result.inviteUrl),
                                                chooserTitle = "共有招待リンクを共有",
                                            )
                                        }

                                        SharedTagInviteCreationResult.AuthRequired -> {
                                            shareError = "招待リンクを作るにはサインインが必要です"
                                        }

                                        SharedTagInviteCreationResult.NotSharedTag -> {
                                            shareError = "この共有タグはまだクラウド共有ではありません"
                                        }

                                        SharedTagInviteCreationResult.OwnerOnly -> {
                                            shareError = "招待リンクを共有できるのはオーナーだけです"
                                        }

                                        SharedTagInviteCreationResult.SyncPending -> {
                                            shareError = "同期が終わってから招待リンクを共有してください"
                                        }

                                        is SharedTagInviteCreationResult.Failure -> {
                                            shareError = result.message
                                        }
                                    }
                                }
                            },
                        ) {
                            Text("クラウド招待リンクを共有")
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showShareOptionsDialog = false }) {
                    Text("閉じる")
                }
            },
        )
    }

    if (showLocalTagShareDialog) {
        AlertDialog(
            onDismissRequest = { showLocalTagShareDialog = false },
            title = { Text("自作タグを共有") },
            text = {
                Text(
                    "タグ『${currentTag.name}』とURL ${viewModel.eligibleLocalTagShareEntryCount()}件を共有します。タイトル、メモ、共有タグの情報は含まれません。",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showLocalTagShareDialog = false
                        scope.launch {
                            val payload = viewModel.buildTagSharePayloadText()
                            if (payload == null) {
                                snackbarHostState.showSnackbar("自作タグを共有できませんでした")
                            } else if (launchLocalTagShare(payload)) {
                                snackbarHostState.showSnackbar("共有先を開きました")
                            } else {
                                snackbarHostState.showSnackbar("共有先を開けませんでした")
                            }
                        }
                    },
                ) {
                    Text("共有先を選ぶ")
                }
            },
            dismissButton = {
                TextButton(onClick = { showLocalTagShareDialog = false }) {
                    Text("キャンセル")
                }
            },
        )
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("${tagKindLabel}を削除") },
            text = {
                Text(
                    if (isLocalTag) {
                        "この自作タグをこの端末から削除します。タグ内のURL自体は削除されません。"
                    } else {
                        "この共有タグを削除すると、参加中メンバーの一覧からも外れます。タグ内のURL自体は削除されません。"
                    },
                )
            },
            confirmButton = {
                CenteredDeleteDialogActions(
                    onCancel = { showDeleteDialog = false },
                    onDelete = {
                        scope.launch {
                            viewModel.deleteTag()
                            showDeleteDialog = false
                            onBack()
                        }
                    },
                )
            },
        )
    }

    if (showLeaveDialog) {
        AlertDialog(
            onDismissRequest = { showLeaveDialog = false },
            title = { Text("共有タグから抜ける") },
            text = { Text("この端末の共有タグ一覧から外れます。共有タグ自体や他の参加者のURLは削除されません。") },
            confirmButton = {
                Button(
                    onClick = {
                        scope.launch {
                            val left = viewModel.leaveSharedTag()
                            if (left) {
                                showLeaveDialog = false
                                onBack()
                            } else {
                                memberRemoveError = "共有タグから抜けられませんでした。権限や同期状態を確認してください。"
                                showLeaveDialog = false
                            }
                        }
                    },
                ) {
                    Text("抜ける")
                }
            },
            dismissButton = {
                TextButton(onClick = { showLeaveDialog = false }) {
                    Text("キャンセル")
                }
            },
        )
    }

    pendingOwnershipTransferMember?.let { member ->
        AlertDialog(
            onDismissRequest = { pendingOwnershipTransferMember = null },
            title = { Text("オーナー権限を移譲") },
            text = {
                Text(
                    "${sharedTagMemberLabel(member)}へオーナー権限を移します。移譲後、あなたは編集者になり、共有タグの削除や招待リンク作成はできなくなります。",
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        scope.launch {
                            when (val result = viewModel.transferOwnership(member.userId)) {
                                SharedTagOwnershipTransferResult.Success -> {
                                    ownershipTransferError = null
                                    pendingOwnershipTransferMember = null
                                    snackbarHostState.showSnackbar("オーナー権限を移譲しました")
                                }
                                SharedTagOwnershipTransferResult.AuthRequired -> {
                                    ownershipTransferError = "オーナー権限の移譲にはサインインが必要です"
                                    pendingOwnershipTransferMember = null
                                }
                                SharedTagOwnershipTransferResult.OwnerOnly -> {
                                    ownershipTransferError = "オーナー権限を移譲できるのは現在のオーナーだけです"
                                    pendingOwnershipTransferMember = null
                                }
                                SharedTagOwnershipTransferResult.InvalidTarget -> {
                                    ownershipTransferError = "移譲先は参加中のメンバーを選んでください"
                                    pendingOwnershipTransferMember = null
                                }
                                is SharedTagOwnershipTransferResult.Failure -> {
                                    ownershipTransferError = result.message
                                    pendingOwnershipTransferMember = null
                                }
                            }
                        }
                    },
                ) {
                    Text("移譲する")
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingOwnershipTransferMember = null }) {
                    Text("キャンセル")
                }
            },
        )
    }

    if (showAddEntrySheet) {
        LaunchedEffect(Unit) {
            addEntrySheetState.expand()
        }
        ModalBottomSheet(
            sheetState = addEntrySheetState,
            onDismissRequest = {
                showAddEntrySheet = false
                entryAddError = null
            },
            dragHandle = null,
            containerColor = MaterialTheme.colorScheme.background,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight()
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 24.dp),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp, bottom = 16.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        modifier = Modifier
                            .width(72.dp)
                            .height(8.dp)
                            .clip(RoundedCornerShape(50))
                            .background(MaterialTheme.colorScheme.outlineVariant),
                    )
                }
                Text(
                    text = "保存済みURLを追加",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                if (!entryAddError.isNullOrBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = entryAddError.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Spacer(Modifier.height(16.dp))
                if (!canEditEntries) {
                    Text(
                        text = "この${tagKindLabel}では URL を追加できません",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else if (availableEntriesToAdd.isEmpty()) {
                    Text(
                        text = "追加できる保存済みURLはありません",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentPadding = PaddingValues(bottom = 20.dp),
                    ) {
                        items(availableEntriesToAdd, key = { it.id }) { entry ->
                            AddEntryCandidateCard(
                                entry = entry,
                                displayMode = entryCardDisplayMode,
                                enabled = canEditEntries,
                                tagKindLabel = tagKindLabel,
                                onClick = { onOpenDetail(entry.id) },
                                onAdd = {
                                    scope.launch {
                                        when (val result = viewModel.addEntryToTag(entry.id)) {
                                            AssignTagResult.Success,
                                            AssignTagResult.AlreadyAssigned,
                                            -> {
                                                showAddEntrySheet = false
                                                entryAddError = null
                                            }
                                            is AssignTagResult.LimitReached -> {
                                                entryAddError = result.message
                                            }
                                            AssignTagResult.Failed -> {
                                                entryAddError = "この${tagKindLabel}にはURLを追加できませんでした"
                                            }
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
}

private val SharedTagHeaderButtonSize = 46.dp
private val SharedTagHeaderIconSize = 30.dp

@Composable
private fun CenteredDeleteDialogActions(
    onCancel: () -> Unit,
    onDelete: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        OutlinedButton(
            onClick = onCancel,
            modifier = Modifier
                .fillMaxWidth()
                .height(58.dp),
            shape = RoundedCornerShape(18.dp),
        ) {
            Text("キャンセルする")
        }
        Button(
            onClick = onDelete,
            modifier = Modifier
                .fillMaxWidth()
                .height(58.dp),
            shape = RoundedCornerShape(18.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.error,
                contentColor = MaterialTheme.colorScheme.onError,
            ),
        ) {
            Text("削除する")
        }
    }
}

@Composable
private fun SharedTagHeaderIconButton(
    onClick: () -> Unit,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.size(SharedTagHeaderButtonSize),
    ) {
        content()
    }
}

@Composable
private fun CloudSignInBanner(
    onOpenCloudAuth: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "クラウド共有を使うにはサインインが必要です",
            style = MaterialTheme.typography.bodyMedium,
        )
        OutlinedButton(onClick = onOpenCloudAuth) {
            Text("サインイン")
        }
    }
}

@Composable
private fun SharedTagInlineTitle(
    title: String,
    isSyncing: Boolean,
    syncNotice: String?,
    syncNoticeIsError: Boolean,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .padding(top = 14.dp, bottom = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        SharedTagTitleSyncStatus(
            isSyncing = isSyncing,
            syncNotice = syncNotice,
            syncNoticeIsError = syncNoticeIsError,
        )
    }
}

@Composable
private fun SharedTagTitleSyncStatus(
    isSyncing: Boolean,
    syncNotice: String?,
    syncNoticeIsError: Boolean,
) {
    when {
        isSyncing -> Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(14.dp),
                strokeWidth = 2.dp,
            )
            Text(
                text = "同期中",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }

        !syncNotice.isNullOrBlank() -> Text(
            text = if (syncNoticeIsError) "更新できませんでした" else syncNotice,
            style = MaterialTheme.typography.bodySmall,
            color = if (syncNoticeIsError) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurface
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun SharedTagEntryCountRow(
    count: Int,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "${count}件",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun TagEmptyPlaceholder(
    isLocalTag: Boolean,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = Icons.Outlined.LinkOff,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = if (isLocalTag) {
                "この自作タグにはまだURLがありません"
            } else {
                "この共有タグにはまだURLがありません"
            },
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Clip,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = if (isLocalTag) {
                "詳細画面からURLに自作タグを追加すると、ここにまとまって表示されます"
            } else {
                "詳細画面からURLに共有タグを追加すると、ここにまとまって表示されます"
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun SyncedTagInfo(
    shareError: String?,
) {
    if (shareError.isNullOrBlank()) return

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = shareError,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
}

private fun inviteShareText(inviteUrl: String): String {
    val token = runCatching { Uri.parse(inviteUrl).lastPathSegment.orEmpty() }.getOrDefault("")
    if (token.isBlank()) return inviteUrl
    return """
        URL Saverの共有タグに参加:
        $inviteUrl

        開けない場合:
        urlsaver://invite/$token
    """.trimIndent()
}

@Composable
private fun AddEntryCandidateCard(
    entry: jp.mimac.urlsaver.data.UrlEntryEntity,
    displayMode: EntryCardDisplayMode,
    enabled: Boolean,
    tagKindLabel: String,
    onClick: () -> Unit,
    onAdd: () -> Unit,
) {
    EntryCard(
        entry = entry,
        timestampMillis = entry.createdAt,
        displayMode = displayMode,
        showDisplayUrl = false,
        footerContent = {
            OrbitActionButton(
                onClick = onAdd,
                enabled = enabled,
                style = OrbitActionStyle.PRIMARY,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
            ) {
                Text("この${tagKindLabel}に追加")
            }
        },
        onClick = onClick,
    )
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun SwipeableTagEntry(
    entry: jp.mimac.urlsaver.data.UrlEntryEntity,
    displayMode: EntryCardDisplayMode,
    canRemove: Boolean,
    onClick: () -> Unit,
    onRemove: () -> Unit,
) {
    val dismissState = rememberSwipeToDismissBoxState(
        positionalThreshold = { totalDistance -> totalDistance * ENTRY_CARD_SWIPE_ACTION_THRESHOLD_FRACTION },
        confirmValueChange = { targetValue ->
            when (targetValue) {
                SwipeToDismissBoxValue.StartToEnd,
                SwipeToDismissBoxValue.EndToStart -> {
                    if (canRemove) {
                        onRemove()
                    }
                    false
                }

                SwipeToDismissBoxValue.Settled -> true
            }
        },
    )

    SwipeToDismissBox(
        state = dismissState,
        enableDismissFromStartToEnd = canRemove,
        enableDismissFromEndToStart = canRemove,
        backgroundContent = {
            TagEntryRemoveSwipeBackground(
                direction = dismissState.dismissDirection,
                swipeOffsetPx = runCatching { dismissState.requireOffset() }.getOrDefault(0f),
            )
        },
    ) {
        EntryCard(
            entry = entry,
            timestampMillis = entry.createdAt,
            displayMode = displayMode,
            showDisplayUrl = false,
            onClick = onClick,
        )
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun TagEntryRemoveSwipeBackground(
    direction: SwipeToDismissBoxValue,
    swipeOffsetPx: Float,
) {
    val isActive = direction != SwipeToDismissBoxValue.Settled
    val density = LocalDensity.current
    val revealedWidth = with(density) { abs(swipeOffsetPx).toDp() }
    val alignment = if (direction == SwipeToDismissBoxValue.EndToStart) {
        Alignment.CenterEnd
    } else {
        Alignment.CenterStart
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        if (isActive && revealedWidth > 0.dp) {
            Box(
                modifier = Modifier
                    .align(alignment)
                    .fillMaxHeight()
                    .width(revealedWidth)
                    .background(
                        color = OrbitTokens.dangerSurface,
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(OrbitTokens.radiusPanel),
                    )
                    .padding(horizontal = 16.dp, vertical = 20.dp),
                contentAlignment = alignment,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (direction == SwipeToDismissBoxValue.EndToStart) {
                        Text(text = "外す", style = MaterialTheme.typography.labelLarge)
                        Icon(imageVector = Icons.Outlined.LinkOff, contentDescription = null)
                    } else {
                        Icon(imageVector = Icons.Outlined.LinkOff, contentDescription = null)
                        Text(text = "外す", style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }
    }
}

@Composable
private fun SharedTagMembersPanel(
    members: List<SharedTagMemberRecord>,
    canTransferOwnership: Boolean,
    onTransferOwnership: (SharedTagMemberRecord) -> Unit,
) {
    var selectedMember by remember { mutableStateOf<SharedTagMemberRecord?>(null) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "参加者 ${members.size}名",
            style = MaterialTheme.typography.titleSmall,
        )
        if (members.isEmpty()) {
            Text(
                text = "同期が完了すると参加者が表示されます。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalAlignment = Alignment.Top,
            ) {
                members.forEach { member ->
                    SharedTagMemberAvatarItem(
                        member = member,
                        onClick = { selectedMember = member },
                    )
                }
            }
        }
    }

    selectedMember?.let { member ->
        SharedTagMemberProfileDialog(
            member = member,
            canTransferOwnership = canTransferOwnership &&
                !member.isCurrentUser &&
                member.role != SharedTagMemberRole.OWNER,
            onTransferOwnership = {
                selectedMember = null
                onTransferOwnership(member)
            },
            onDismiss = { selectedMember = null },
        )
    }
}

@Composable
private fun SharedTagMemberAvatarItem(
    member: SharedTagMemberRecord,
    onClick: () -> Unit,
) {
    val label = sharedTagMemberLabel(member)
    Column(
        modifier = Modifier
            .width(76.dp)
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Box(
            modifier = Modifier
                .size(54.dp)
                .clip(CircleShape)
                .background(
                    if (member.isCurrentUser) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = memberAvatarText(member),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = if (member.isCurrentUser) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            text = sharedTagRoleLabel(member.role),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun SharedTagMemberProfileDialog(
    member: SharedTagMemberRecord,
    canTransferOwnership: Boolean,
    onTransferOwnership: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("プロフィール") },
        text = {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .clip(CircleShape)
                        .background(
                            if (member.isCurrentUser) {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant
                            },
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = memberAvatarText(member),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = if (member.isCurrentUser) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
                Text(
                    text = sharedTagMemberLabel(member),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                )
                Text(
                    text = if (member.isCurrentUser) "自分のプロフィール" else "共有タグの参加者",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                Text(
                    text = "権限: ${sharedTagRoleLabel(member.role)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (canTransferOwnership) {
                    TextButton(onClick = onTransferOwnership) {
                        Text("オーナー権限を移譲")
                    }
                }
                TextButton(onClick = onDismiss) {
                    Text("閉じる")
                }
            }
        },
    )
}

private fun sharedTagMemberLabel(member: SharedTagMemberRecord): String {
    if (member.isCurrentUser) return "自分"
    return "ユーザー ${shortSharedTagUserId(member.userId)}"
}

private fun memberAvatarText(member: SharedTagMemberRecord): String {
    if (member.isCurrentUser) return "自"
    val seed = member.userId.firstOrNull()?.uppercaseChar()?.toString()
    return seed ?: "U"
}

private fun shortSharedTagUserId(userId: String): String {
    return if (userId.length <= 8) userId else "${userId.take(8)}..."
}

private fun sharedTagRoleLabel(role: SharedTagMemberRole): String {
    return when (role) {
        SharedTagMemberRole.OWNER -> "オーナー"
        SharedTagMemberRole.EDITOR -> "編集者"
        SharedTagMemberRole.VIEWER -> "閲覧者"
    }
}
