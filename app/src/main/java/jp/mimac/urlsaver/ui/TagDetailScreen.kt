package jp.mimac.urlsaver.ui

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ViewList
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.IosShare
import androidx.compose.material.icons.outlined.LinkOff
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.ViewAgenda
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
import jp.mimac.urlsaver.domain.EntryCardDisplayMode
import jp.mimac.urlsaver.domain.AssignTagResult
import jp.mimac.urlsaver.domain.MigrateSharedTagResult
import jp.mimac.urlsaver.domain.SharedTagInviteCreationResult
import jp.mimac.urlsaver.domain.SharedTagMemberRecord
import jp.mimac.urlsaver.domain.SharedTagMemberRole
import jp.mimac.urlsaver.domain.SharedTagOwnershipTransferResult
import jp.mimac.urlsaver.domain.SharedTagScope
import jp.mimac.urlsaver.ui.components.EntryCard
import jp.mimac.urlsaver.ui.theme.OrbitTokens
import kotlinx.coroutines.launch
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

    var showMigrateDialog by remember { mutableStateOf(false) }
    var showAddEntrySheet by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showLeaveDialog by remember { mutableStateOf(false) }
    var showInfoDialog by remember { mutableStateOf(false) }
    var migrateError by remember { mutableStateOf<String?>(null) }
    var pendingOwnershipTransferMember by remember { mutableStateOf<SharedTagMemberRecord?>(null) }
    var shareError by remember { mutableStateOf<String?>(null) }
    var memberRemoveError by remember { mutableStateOf<String?>(null) }
    var ownershipTransferError by remember { mutableStateOf<String?>(null) }
    var entryRemoveError by remember { mutableStateOf<String?>(null) }
    var entryAddError by remember { mutableStateOf<String?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }

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

    LaunchedEffect(currentTag.id) {
        listState.scrollToItem(0)
    }

    LaunchedEffect(currentTag.id, currentTag.scope, isSyncing, syncNotice) {
        if (currentTag.scope == SharedTagScope.SYNCED && (isSyncing || !syncNotice.isNullOrBlank())) {
            listState.scrollToItem(0)
        }
    }

    val canEditEntries = currentTag.scope == SharedTagScope.LOCAL_ONLY ||
        currentTag.currentUserRole == SharedTagMemberRole.OWNER ||
        currentTag.currentUserRole == SharedTagMemberRole.EDITOR
    val canShareInvite = currentTag.scope == SharedTagScope.SYNCED &&
        currentTag.currentUserRole == SharedTagMemberRole.OWNER
    val canDeleteSharedTag = currentTag.scope == SharedTagScope.SYNCED &&
        currentTag.currentUserRole == SharedTagMemberRole.OWNER
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

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(currentTag.name) },
                windowInsets = TopAppBarDefaults.windowInsets.only(WindowInsetsSides.Horizontal),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "戻る")
                    }
                },
                actions = {
                    if (currentTag.scope == SharedTagScope.SYNCED) {
                        IconButton(onClick = { showInfoDialog = true }) {
                            Icon(
                                Icons.Outlined.Info,
                                contentDescription = "共有タグの説明",
                            )
                        }
                    }
                    IconButton(onClick = { viewModel.toggleEntryCardDisplayMode() }) {
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
                        )
                    }
                    if (currentTag.scope == SharedTagScope.SYNCED) {
                        IconButton(
                            enabled = !isSyncing,
                            onClick = { viewModel.syncSharedTagNow() },
                        ) {
                            if (isSyncing) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    strokeWidth = 2.dp,
                                )
                            } else {
                                Icon(
                                    Icons.Outlined.Refresh,
                                    contentDescription = "共有タグを更新",
                                )
                            }
                        }
                    }
                    if (canEditEntries) {
                        IconButton(onClick = { showAddEntrySheet = true }) {
                            Icon(Icons.Outlined.Add, contentDescription = "保存済みURLを追加")
                        }
                    }
                    if (currentTag.scope == SharedTagScope.LOCAL_ONLY || canShareInvite) {
                        IconButton(
                            onClick = {
                                if (currentTag.scope == SharedTagScope.LOCAL_ONLY) {
                                    shareError = null
                                    launchShare(
                                        text = viewModel.buildLocalShareLink(),
                                        chooserTitle = "共有フォルダを共有",
                                    )
                                } else {
                                    scope.launch {
                                        when (val result = viewModel.createInviteLink()) {
                                            is SharedTagInviteCreationResult.Success -> {
                                                shareError = null
                                                launchShare(
                                                    text = "UrlSaver の共有タグに参加する\n${result.inviteUrl}",
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
                                }
                            },
                        ) {
                            Icon(
                                Icons.Outlined.IosShare,
                                contentDescription = if (currentTag.scope == SharedTagScope.LOCAL_ONLY) {
                                    "共有フォルダリンクを共有"
                                } else {
                                    "共有招待リンクを共有"
                                },
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
            if (entries.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .widthIn(max = 680.dp),
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
                            text = "この共有タグにはまだURLがありません",
                            style = MaterialTheme.typography.titleMedium,
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                            overflow = TextOverflow.Clip,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = "詳細画面からURLに共有タグを追加すると、ここにまとまって表示されます",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        if (currentTag.scope == SharedTagScope.SYNCED) {
                            Spacer(Modifier.height(12.dp))
                            SyncedTagInfo(
                                shareError = shareError,
                                isSyncing = isSyncing,
                                syncNotice = syncNotice,
                                syncNoticeIsError = syncNoticeIsError,
                            )
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
                                    textAlign = TextAlign.Center,
                                )
                            }
                            memberRemoveError?.let { message ->
                                Text(
                                    text = message,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                    textAlign = TextAlign.Center,
                                )
                            }
                            if (canLeaveSharedTag) {
                                TextButton(onClick = { showLeaveDialog = true }) {
                                    Text("この共有タグから抜ける")
                                }
                            }
                        }
                        if (currentTag.scope == SharedTagScope.LOCAL_ONLY && cloudState.isConfigured && !cloudState.isSignedIn) {
                            Spacer(Modifier.height(12.dp))
                            OutlinedButton(onClick = onOpenCloudAuth) {
                                Text("クラウド共有にサインイン")
                            }
                        } else if (currentTag.scope == SharedTagScope.LOCAL_ONLY && cloudState.isSignedIn) {
                            Spacer(Modifier.height(12.dp))
                            OutlinedButton(onClick = { showMigrateDialog = true }) {
                                Text("クラウド共有へ移行")
                            }
                        }
                    }
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .widthIn(max = 680.dp),
                ) {
                    if (currentTag.scope == SharedTagScope.LOCAL_ONLY && cloudState.isConfigured && !cloudState.isSignedIn) {
                        item {
                            CloudSignInBanner(onOpenCloudAuth = onOpenCloudAuth)
                        }
                    } else if (currentTag.scope == SharedTagScope.LOCAL_ONLY && cloudState.isSignedIn) {
                        item {
                            MigrateBanner(
                                migrateError = migrateError,
                                onMigrate = { showMigrateDialog = true },
                            )
                        }
                    }
                    if (currentTag.scope == SharedTagScope.SYNCED) {
                        item {
                            SyncedTagInfo(
                                shareError = shareError,
                                isSyncing = isSyncing,
                                syncNotice = syncNotice,
                                syncNoticeIsError = syncNoticeIsError,
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
                        Text(
                            text = "${entries.size}件",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                    }
                    if (canDeleteSharedTag) {
                        item {
                            TextButton(
                                onClick = { showDeleteDialog = true },
                                modifier = Modifier.padding(horizontal = 8.dp),
                            ) {
                                Text("この共有タグを削除")
                            }
                        }
                    }
                    if (canLeaveSharedTag) {
                        item {
                            TextButton(
                                onClick = { showLeaveDialog = true },
                                modifier = Modifier.padding(horizontal = 8.dp),
                            ) {
                                Text("この共有タグから抜ける")
                            }
                        }
                    }
                    items(entries, key = { it.id }) { entry ->
                        SwipeableTagEntry(
                            entry = entry,
                            displayMode = entryCardDisplayMode,
                            canRemove = canEditEntries,
                            onClick = { onOpenDetail(entry.id) },
                            onRemove = {
                                entryRemoveError = null
                                scope.launch {
                                    val removed = viewModel.removeEntryFromTag(entry.id)
                                    if (!removed) {
                                        entryRemoveError = "共有タグから外せませんでした"
                                    }
                                }
                            },
                        )
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
    }

    if (showInfoDialog) {
        AlertDialog(
            onDismissRequest = { showInfoDialog = false },
            title = { Text("共有タグについて") },
            text = { Text(sharedTagInfoMessage(currentTag.currentUserRole)) },
            confirmButton = {
                TextButton(onClick = { showInfoDialog = false }) {
                    Text("閉じる")
                }
            },
        )
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("共有タグを削除") },
            text = { Text("この共有タグを削除すると、参加中メンバーの一覧からも外れます。") },
            confirmButton = {
                Button(
                    onClick = {
                        scope.launch {
                            viewModel.deleteTag()
                            showDeleteDialog = false
                            onBack()
                        }
                    },
                ) {
                    Text("削除する")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text("キャンセル")
                }
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

    if (showMigrateDialog) {
        AlertDialog(
            onDismissRequest = { showMigrateDialog = false },
            title = { Text("クラウド共有へ移行") },
            text = {
                Text("この共有タグを同期対象に切り替えます。共有リンクは同じ端末上のこのアプリでのみ開けます。")
            },
            confirmButton = {
                Button(
                    onClick = {
                        scope.launch {
                            migrateError = when (val migrated = viewModel.migrateToCloud()) {
                                MigrateSharedTagResult.Success -> null
                                is MigrateSharedTagResult.LimitReached -> migrated.message
                                MigrateSharedTagResult.NotEligible,
                                MigrateSharedTagResult.Failed,
                                -> "クラウド共有への移行を開始できませんでした"
                            }
                            showMigrateDialog = false
                        }
                    },
                ) {
                    Text("移行する")
                }
            },
            dismissButton = {
                TextButton(onClick = { showMigrateDialog = false }) {
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
                    "${sharedTagMemberLabel(member)}へオーナー権限を移します。" +
                        "移譲後、あなたは編集者になり、共有タグの削除や招待リンク作成はできなくなります。",
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
        ModalBottomSheet(
            onDismissRequest = {
                showAddEntrySheet = false
                entryAddError = null
            },
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "保存済みURLを追加",
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "まだこの共有タグに入っていない保存済みURLを後から追加できます",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (!entryAddError.isNullOrBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = entryAddError.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Spacer(Modifier.height(12.dp))
                if (!canEditEntries) {
                    Text(
                        text = "この共有タグでは URL を追加できません",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                } else if (availableEntriesToAdd.isEmpty()) {
                    Text(
                        text = "追加できる保存済みURLはありません",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(420.dp),
                    ) {
                        items(availableEntriesToAdd, key = { it.id }) { entry ->
                            EntryCard(
                                entry = entry,
                                timestampMillis = entry.createdAt,
                                displayMode = entryCardDisplayMode,
                                showDisplayUrl = false,
                                footerContent = {
                                    TextButton(
                                        onClick = {
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
                                                        entryAddError = "この共有タグにはURLを追加できませんでした"
                                                    }
                                                }
                                            }
                                        },
                                        modifier = Modifier
                                            .align(Alignment.End)
                                            .padding(top = 4.dp),
                                    ) {
                                        Text("この共有タグに追加")
                                    }
                                },
                                onClick = { onOpenDetail(entry.id) },
                            )
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                }
            }
        }
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
        Text(
            text = "通常の共有タグはこの端末だけで使われます。必要なタグだけ後からクラウド共有へ移行できます。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedButton(onClick = onOpenCloudAuth) {
            Text("サインイン")
        }
    }
}

@Composable
private fun MigrateBanner(
    migrateError: String?,
    onMigrate: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "このローカル共有タグはクラウド共有へ移行できます",
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            text = "右上の共有ボタンでは、この端末内の共有フォルダを開くリンクを共有できます。クラウド共有へ移行すると、オーナーは別端末向けの招待リンクを作れます。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedButton(onClick = onMigrate) {
            Text("クラウド共有へ移行")
        }
        migrateError?.let { message ->
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun SyncedTagInfo(
    shareError: String?,
    isSyncing: Boolean,
    syncNotice: String?,
    syncNoticeIsError: Boolean,
) {
    if (!isSyncing && syncNotice.isNullOrBlank() && shareError.isNullOrBlank()) return

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (isSyncing) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
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
                )
            }
        }
        syncNotice?.let { message ->
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = if (syncNoticeIsError) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )
        }
        shareError?.let { message ->
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

private fun sharedTagInfoMessage(role: SharedTagMemberRole?): String {
    val roleText = when (role) {
        SharedTagMemberRole.OWNER -> "あなたはオーナーです。招待リンクの共有、参加者の削除、タグ削除ができます。"
        SharedTagMemberRole.EDITOR -> "あなたは編集者です。URL の追加と削除、共有タグから抜ける操作ができます。"
        SharedTagMemberRole.VIEWER -> "あなたは閲覧者です。URL の閲覧と共有タグから抜ける操作ができます。"
        null -> "同期が完了すると権限情報が表示されます。"
    }
    return "この共有タグでは URL 一覧だけを同期します。\n\n$roleText"
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
            text = "参加者 ${members.size}人",
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
