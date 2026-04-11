package jp.mimac.urlsaver.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import jp.mimac.urlsaver.domain.DetailEffect
import jp.mimac.urlsaver.domain.MainNavigationEvent
import jp.mimac.urlsaver.domain.MetadataState
import jp.mimac.urlsaver.domain.RecordState
import jp.mimac.urlsaver.domain.ServiceType
import jp.mimac.urlsaver.domain.ShareSaveResult
import jp.mimac.urlsaver.domain.SnackbarEvent
import jp.mimac.urlsaver.domain.SnackbarEventKind
import jp.mimac.urlsaver.domain.UrlRules
import jp.mimac.urlsaver.ui.components.EntryCard
import jp.mimac.urlsaver.ui.components.ServiceFilterRow
import jp.mimac.urlsaver.ui.components.ServiceIcon
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun UrlSaverRoot(
    activityViewModel: MainActivityViewModel,
    onRouteChanged: (String?) -> Unit,
) {
    val context = LocalContext.current
    val navController = rememberNavController()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    var currentSnackbarKind by remember { mutableStateOf<SnackbarEventKind?>(null) }

    suspend fun showEvent(event: SnackbarEvent) {
        currentSnackbarKind = event.kind

        val duration = if (event.customDurationMillis != null) {
            SnackbarDuration.Indefinite
        } else {
            event.duration ?: SnackbarDuration.Short
        }

        val timerJob = if (event.customDurationMillis != null) {
            scope.launch {
                delay(event.customDurationMillis)
                snackbarHostState.currentSnackbarData?.dismiss()
            }
        } else {
            null
        }

        val result = snackbarHostState.showSnackbar(
            message = event.message,
            actionLabel = event.actionLabel,
            duration = duration,
        )

        timerJob?.cancel()

        if (result == SnackbarResult.ActionPerformed) {
            activityViewModel.onSnackbarAction(event)
        } else {
            activityViewModel.onSnackbarDismissed(event)
        }

        currentSnackbarKind = null
    }

    LaunchedEffect(Unit) {
        activityViewModel.snackbarEvents.collect { event ->
            showEvent(event)
        }
    }

    LaunchedEffect(Unit) {
        activityViewModel.snackbarControlEvents.collect { control ->
            if (control is SnackbarControlEvent.DismissUndoTitle &&
                currentSnackbarKind == SnackbarEventKind.UNDO_TITLE_EDIT
            ) {
                snackbarHostState.currentSnackbarData?.dismiss()
            }
        }
    }

    LaunchedEffect(Unit) {
        activityViewModel.navigationEvents.collect { event ->
            when (event) {
                MainNavigationEvent.NavigateToArchive -> {
                    navController.navigate(Routes.ARCHIVE) {
                        launchSingleTop = true
                    }
                }
                MainNavigationEvent.NavigateToMain -> {
                    navController.navigate(Routes.MAIN) {
                        launchSingleTop = true
                    }
                }
                is MainNavigationEvent.NavigateToDetail -> {
                    navController.navigate(Routes.detail(event.entryId)) {
                        launchSingleTop = true
                    }
                }
            }
        }
    }

    val backStackEntry by navController.currentBackStackEntryAsState()
    val resolvedRoute = remember(backStackEntry) {
        val destination = backStackEntry?.destination?.route
        if (destination == Routes.DETAIL_PATTERN) {
            val id = backStackEntry?.arguments?.getLong("entryId")
            if (id != null) "detail/$id" else destination
        } else {
            destination
        }
    }

    LaunchedEffect(resolvedRoute) {
        onRouteChanged(resolvedRoute)
        activityViewModel.onRouteChanged(resolvedRoute)
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { paddingValues ->
        NavHost(
            navController = navController,
            startDestination = Routes.MAIN,
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
        ) {
            composable(Routes.MAIN) {
                val vm: MainListViewModel = viewModel(
                    factory = SimpleFactory {
                        MainListViewModel(context.appContainer().repository)
                    },
                )
                MainScreen(
                    viewModel = vm,
                    onOpenArchive = { navController.navigate(Routes.ARCHIVE) },
                    onOpenDetail = { navController.navigate(Routes.detail(it)) },
                    onManualResult = { result, entryId ->
                        activityViewModel.onManualSaveResult(result, entryId, resolvedRoute)
                    },
                )
            }

            composable(Routes.ARCHIVE) {
                val vm: ArchiveViewModel = viewModel(
                    factory = SimpleFactory {
                        ArchiveViewModel(context.appContainer().repository)
                    },
                )
                ArchiveScreen(
                    viewModel = vm,
                    onBack = { navController.popBackStack() },
                    onOpenDetail = { navController.navigate(Routes.detail(it)) },
                )
            }

            composable(
                route = Routes.DETAIL_PATTERN,
                arguments = listOf(navArgument("entryId") { type = NavType.LongType }),
            ) { entryBackStack ->
                val entryId = entryBackStack.arguments?.getLong("entryId") ?: return@composable
                val vm: DetailViewModel = viewModel(
                    key = "detail_$entryId",
                    factory = SimpleFactory {
                        DetailViewModel(entryId, context.appContainer().repository)
                    },
                )
                DetailScreen(
                    entryId = entryId,
                    viewModel = vm,
                    onBack = { navController.popBackStack() },
                    onDetailEffect = { effect ->
                        activityViewModel.onDetailEffect(effect)
                        when (effect) {
                            is DetailEffect.NavigateBackAfterPendingDelete -> navController.popBackStack()
                            is DetailEffect.NavigateBackAfterArchive,
                            is DetailEffect.NavigateBackAfterRestore,
                            -> navController.popBackStack(route = Routes.MAIN, inclusive = false)
                            is DetailEffect.TitleEdited -> Unit
                        }
                    },
                    onScheduleDeleteTimer = { pendingUntil ->
                        activityViewModel.startDeleteTimer(entryId, pendingUntil)
                    },
                    onTitleEditStarted = { activityViewModel.onTitleEditStarted() },
                    onCopySuccess = { activityViewModel.notifyCopySuccess() },
                    onMemoSaved = { activityViewModel.notifyMemoSaved() },
                )
            }
        }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun MainScreen(
    viewModel: MainListViewModel,
    onOpenArchive: () -> Unit,
    onOpenDetail: (Long) -> Unit,
    onManualResult: (ShareSaveResult, Long?) -> Unit,
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val selected by viewModel.selectedServiceFlow.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    var showSheet by remember { mutableStateOf(false) }
    var inputText by remember { mutableStateOf("") }
    var inputError by remember { mutableStateOf<ShareSaveResult?>(null) }

    if (showSheet) {
        ModalBottomSheet(
            onDismissRequest = {
                showSheet = false
                inputText = ""
                inputError = null
            },
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                OutlinedTextField(
                    value = inputText,
                    onValueChange = {
                        inputText = it
                        inputError = null
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("manual_input_field"),
                    label = { Text("URL") },
                    placeholder = { Text("https://example.com") },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Uri,
                        imeAction = ImeAction.Done,
                    ),
                    isError = inputError == ShareSaveResult.INVALID_URL || inputError == ShareSaveResult.NO_URL_FOUND,
                    supportingText = {
                        when (inputError) {
                            ShareSaveResult.INVALID_URL -> Text("URL形式が正しくありません。http:// または https:// から始まるURLを入力してください")
                            ShareSaveResult.NO_URL_FOUND -> Text("入力内にURLが見つかりませんでした。URLをそのまま貼り付けてください")
                            else -> Unit
                        }
                    },
                )
                Spacer(Modifier.height(12.dp))
                TextButton(
                    onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        val pasted = clipboard.primaryClip
                            ?.takeIf { it.itemCount > 0 }
                            ?.getItemAt(0)
                            ?.coerceToText(context)
                            ?.toString()
                            .orEmpty()
                            .trim()
                        if (pasted.isNotEmpty()) {
                            inputText = pasted
                            inputError = null
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("クリップボードを貼り付け")
                }
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = {
                        scope.launch {
                            val (result, entryId) = viewModel.submitManualInput(inputText)
                            when (result) {
                                ShareSaveResult.INVALID_URL,
                                ShareSaveResult.NO_URL_FOUND,
                                -> inputError = result
                                else -> {
                                    onManualResult(result, entryId)
                                    showSheet = false
                                    inputText = ""
                                    inputError = null
                                }
                            }
                        }
                    },
                    enabled = inputText.trim().isNotEmpty(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("manual_input_save"),
                ) {
                    Text("保存")
                }
                Spacer(Modifier.height(12.dp))
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("保存したURL") },
                actions = {
                    IconButton(onClick = onOpenArchive) {
                        Icon(Icons.Outlined.Archive, contentDescription = "アーカイブ")
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = {
                showSheet = true
                inputText = ""
                inputError = null
            }) {
                Icon(Icons.Outlined.Link, contentDescription = "手動追加")
            }
        },
    ) { paddingValues ->
        Column(modifier = Modifier.padding(paddingValues)) {
            ServiceFilterRow(
                selectedService = selected,
                onSelect = { viewModel.selectService(it) },
            )
            Text(
                text = "${uiState.scopeCount}件",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
            Spacer(modifier = Modifier.height(8.dp))

            when {
                uiState.globalCount == 0 -> {
                    EmptyState(
                        title = "まだ保存したURLはありません",
                        body = "SNSやブラウザの共有、または手動追加から保存できます",
                    )
                }

                uiState.scopeCount == 0 -> {
                    EmptyState(
                        title = "このサービスの保存URLはありません",
                        body = "フィルターを変更してください",
                    )
                }

                else -> {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(uiState.entries, key = { it.id }) { entry ->
                            EntryCard(
                                entry = entry,
                                timestampMillis = entry.createdAt,
                                onClick = { onOpenDetail(entry.id) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun ArchiveScreen(
    viewModel: ArchiveViewModel,
    onBack: () -> Unit,
    onOpenDetail: (Long) -> Unit,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val selected by viewModel.selectedServiceFlow.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("アーカイブ") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "戻る")
                    }
                },
            )
        },
    ) { paddingValues ->
        Column(modifier = Modifier.padding(paddingValues)) {
            ServiceFilterRow(
                selectedService = selected,
                onSelect = { viewModel.selectService(it) },
            )
            Text(
                text = "${uiState.scopeCount}件",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
            Spacer(modifier = Modifier.height(8.dp))

            when {
                uiState.globalCount == 0 -> {
                    EmptyState(
                        title = "アーカイブしたURLはまだありません",
                        body = "アーカイブしたURLがここに表示されます",
                    )
                }

                uiState.scopeCount == 0 -> {
                    EmptyState(
                        title = "このサービスのアーカイブはありません",
                        body = "フィルターを変更してください",
                    )
                }

                else -> {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(uiState.entries, key = { it.id }) { entry ->
                            EntryCard(
                                entry = entry,
                                timestampMillis = entry.archivedAt ?: entry.updatedAt,
                                onClick = { onOpenDetail(entry.id) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun DetailScreen(
    entryId: Long,
    viewModel: DetailViewModel,
    onBack: () -> Unit,
    onDetailEffect: (DetailEffect) -> Unit,
    onScheduleDeleteTimer: (Long) -> Unit,
    onTitleEditStarted: () -> Unit,
    onCopySuccess: () -> Unit,
    onMemoSaved: () -> Unit,
) {
    val entry by viewModel.entry.collectAsStateWithLifecycle(initialValue = null)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var isEditingTitle by rememberSaveable { mutableStateOf(false) }
    var titleInput by rememberSaveable { mutableStateOf("") }
    var titleTooLong by rememberSaveable { mutableStateOf(false) }

    var showMemoDialog by rememberSaveable { mutableStateOf(false) }
    var memoInput by rememberSaveable { mutableStateOf("") }
    var memoTooLong by rememberSaveable { mutableStateOf(false) }

    var retryRequested by rememberSaveable { mutableStateOf(false) }

    var detailsExpanded by rememberSaveable { mutableStateOf(false) }

    BackHandler(enabled = isEditingTitle) {
        isEditingTitle = false
        titleInput = entry?.userTitle.orEmpty()
        titleTooLong = false
    }

    LaunchedEffect(entryId) {
        viewModel.effects.collect { effect ->
            onDetailEffect(effect)
        }
    }

    val current = entry ?: return
    val effectiveTitle = UrlRules.effectiveTitle(
        userTitle = current.userTitle,
        fetchedTitle = current.fetchedTitle,
        serviceType = current.serviceType,
        normalizedHost = current.normalizedHost,
    )
    val metadataMessage = metadataDetailMessage(current.metadataState, current.metadataError)
    LaunchedEffect(current.metadataState) {
        if (current.metadataState != MetadataState.PENDING) {
            retryRequested = false
        }
    }

    if (showMemoDialog) {
        AlertDialog(
            onDismissRequest = {
                showMemoDialog = false
                memoInput = current.memo
                memoTooLong = false
            },
            title = { Text("メモを編集") },
            text = {
                OutlinedTextField(
                    value = memoInput,
                    onValueChange = {
                        memoInput = it
                        memoTooLong = it.trim().length > 2000
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("detail_memo_input"),
                    minLines = 4,
                    isError = memoTooLong,
                    supportingText = {
                        if (memoTooLong) {
                            Text("2000文字以内で入力してください")
                        }
                    },
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            when (viewModel.saveMemo(memoInput)) {
                                SaveMemoUiResult.Success -> {
                                    onMemoSaved()
                                    showMemoDialog = false
                                    memoTooLong = false
                                }
                                SaveMemoUiResult.TooLong -> memoTooLong = true
                                SaveMemoUiResult.Failed -> Unit
                            }
                        }
                    },
                ) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = {
                    showMemoDialog = false
                    memoInput = current.memo
                    memoTooLong = false
                }) { Text("キャンセル") }
            },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("詳細") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "戻る")
                    }
                },
            )
        },
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .padding(paddingValues)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            Row(verticalAlignment = Alignment.Top, modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.weight(1f)) {
                    if (isEditingTitle) {
                        OutlinedTextField(
                            value = titleInput,
                            onValueChange = {
                                titleInput = it
                                titleTooLong = it.trim().length > 120
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("detail_title_input"),
                            textStyle = MaterialTheme.typography.headlineSmall,
                            maxLines = 2,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(
                                onDone = {
                                    if (!titleTooLong) {
                                        scope.launch {
                                            when (viewModel.saveTitle(titleInput)) {
                                                SaveTitleUiResult.Success -> {
                                                    isEditingTitle = false
                                                    titleTooLong = false
                                                }
                                                SaveTitleUiResult.TooLong -> titleTooLong = true
                                                SaveTitleUiResult.Failed -> Unit
                                            }
                                        }
                                    }
                                },
                            ),
                            isError = titleTooLong,
                            supportingText = {
                                if (titleTooLong) {
                                    Text("120文字以内で入力してください")
                                }
                            },
                        )
                    } else {
                        Text(
                            text = effectiveTitle,
                            style = MaterialTheme.typography.headlineSmall,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }

                IconButton(
                    onClick = {
                        if (isEditingTitle) {
                            isEditingTitle = false
                            titleInput = current.userTitle.orEmpty()
                            titleTooLong = false
                        } else {
                            onTitleEditStarted()
                            isEditingTitle = true
                            titleInput = current.userTitle.orEmpty()
                            titleTooLong = false
                        }
                    },
                ) {
                    if (isEditingTitle) {
                        Icon(Icons.Outlined.Close, contentDescription = "編集をキャンセル")
                    } else {
                        Icon(Icons.Outlined.Edit, contentDescription = "タイトルを編集")
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            Text(text = current.displayUrl, style = MaterialTheme.typography.bodyMedium)

            Spacer(Modifier.height(12.dp))
            Text(text = "メモ", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(4.dp))
            Text(
                text = if (current.memo.isBlank()) "メモなし" else current.memo,
                style = MaterialTheme.typography.bodyMedium,
                color = if (current.memo.isBlank()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            )

            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                ServiceIcon(current.serviceType)
                Spacer(Modifier.width(8.dp))
                val serviceText = if (current.serviceType == ServiceType.WEB) {
                    current.normalizedHost
                } else {
                    current.serviceType.displayName
                }
                Text(text = serviceText, style = MaterialTheme.typography.bodyMedium)
            }

            if (metadataMessage != null) {
                Spacer(Modifier.height(12.dp))
                Text(
                    text = metadataMessage.title,
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (!metadataMessage.body.isNullOrBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = metadataMessage.body,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                val showRetry = current.metadataState == MetadataState.FAILED ||
                    current.metadataState == MetadataState.UNAVAILABLE ||
                    retryRequested
                if (showRetry) {
                    val retrying = retryRequested && current.metadataState == MetadataState.PENDING
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = {
                            if (!retrying) {
                                retryRequested = true
                                scope.launch {
                                    val accepted = viewModel.retryMetadata()
                                    if (!accepted) {
                                        retryRequested = false
                                    }
                                }
                            }
                        },
                        enabled = !retrying,
                    ) {
                        if (retrying) {
                            CircularProgressIndicator(
                                modifier = Modifier.width(16.dp).height(16.dp),
                                strokeWidth = 2.dp,
                            )
                            Spacer(Modifier.width(8.dp))
                            Text("再取得中…")
                        } else {
                            Text("再取得")
                        }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { context.tryOpenExternalUrl(current.openUrl) }) { Text("開く") }

                    Button(onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("url", current.openUrl))
                        onCopySuccess()
                    }) { Text("コピー") }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        if (current.recordState == RecordState.ARCHIVED) {
                            viewModel.unarchive()
                        } else {
                            viewModel.archive()
                        }
                    }) {
                        Text(if (current.recordState == RecordState.ARCHIVED) "アーカイブ解除" else "アーカイブ")
                    }

                    Button(onClick = {
                        if (current.recordState != RecordState.ARCHIVED) {
                            viewModel.deleteToPending(onScheduleDeleteTimer)
                        }
                    }) { Text("削除") }
                }

                Button(onClick = {
                    memoInput = current.memo
                    memoTooLong = false
                    showMemoDialog = true
                }) { Text("メモを編集") }
            }

            Spacer(Modifier.height(16.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("detail_section_toggle")
                    .clickable { detailsExpanded = !detailsExpanded },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "詳細情報",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    imageVector = if (detailsExpanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                    contentDescription = null,
                )
            }

            if (detailsExpanded) {
                Spacer(Modifier.height(8.dp))
                DetailValue(label = "originalUrl", value = current.originalUrl)
                DetailValue(label = "normalizedUrl", value = current.normalizedUrl)
                if (!current.canonicalId.isNullOrBlank()) {
                    DetailValue(label = "canonicalId", value = current.canonicalId)
                }
                if (current.metadataError != null) {
                    DetailValue(
                        label = "metadataError",
                        value = "${metadataErrorDisplay(current.metadataError)} (${current.metadataError.name})",
                    )
                }
                if (current.recordState != RecordState.ACTIVE && current.archivedAt != null) {
                    DetailValue(label = "archivedAt", value = current.archivedAt.toString())
                }
                if (current.metadataFetchedAt != null) {
                    DetailValue(label = "metadataFetchedAt", value = current.metadataFetchedAt.toString())
                }
            }
        }
    }
}

@Composable
private fun DetailValue(label: String, value: String) {
    Column(modifier = Modifier.padding(vertical = 4.dp)) {
        Text(text = label, style = MaterialTheme.typography.labelMedium)
        Text(text = value, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun EmptyState(
    title: String,
    body: String,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = Icons.Outlined.Info,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(text = title, style = MaterialTheme.typography.titleMedium)
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
