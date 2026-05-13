package jp.mimac.urlsaver.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import jp.mimac.urlsaver.BuildConfig
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ViewList
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.IosShare
import androidx.compose.material.icons.outlined.LinkOff
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Sell
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import coil.compose.AsyncImage
import jp.mimac.urlsaver.R
import jp.mimac.urlsaver.ads.AdsManager
import jp.mimac.urlsaver.data.CollectionEntity
import jp.mimac.urlsaver.data.DEFAULT_COLLECTION_ID
import jp.mimac.urlsaver.data.UrlEntryEntity
import jp.mimac.urlsaver.domain.DetailEffect
import jp.mimac.urlsaver.domain.EntryCardDisplayMode
import jp.mimac.urlsaver.domain.MainNavigationEvent
import jp.mimac.urlsaver.domain.MetadataState
import jp.mimac.urlsaver.domain.RecordState
import jp.mimac.urlsaver.domain.AssignTagResult
import jp.mimac.urlsaver.domain.CreateTagResult
import jp.mimac.urlsaver.domain.SharedTagNameValidationError
import jp.mimac.urlsaver.domain.SharedTagMemberRole
import jp.mimac.urlsaver.domain.SharedTagRecord
import jp.mimac.urlsaver.domain.SharedTagScope
import jp.mimac.urlsaver.domain.ServiceType
import jp.mimac.urlsaver.domain.ShareSaveResult
import jp.mimac.urlsaver.domain.SnackbarEvent
import jp.mimac.urlsaver.domain.SnackbarEventKind
import jp.mimac.urlsaver.domain.UrlRules
import jp.mimac.urlsaver.domain.normalizeSharedTagName
import jp.mimac.urlsaver.domain.validateSharedTagName
import jp.mimac.urlsaver.ui.components.OrbitActionButton
import jp.mimac.urlsaver.ui.components.OrbitActionStyle
import jp.mimac.urlsaver.ui.components.OrbitActionText
import jp.mimac.urlsaver.ui.components.OrbitPanel
import jp.mimac.urlsaver.ui.components.OrbitPanelTone
import jp.mimac.urlsaver.ui.components.OrbitSectionLabel
import jp.mimac.urlsaver.ui.components.CollectionFilterRow
import jp.mimac.urlsaver.ui.components.EntryCard
import jp.mimac.urlsaver.ui.components.ServiceBadge
import jp.mimac.urlsaver.ui.components.ServiceFilterRow
import jp.mimac.urlsaver.ui.components.TagFilterRow
import jp.mimac.urlsaver.ui.ads.BannerAdSlot
import jp.mimac.urlsaver.ui.theme.OrbitTokens
import jp.mimac.urlsaver.ui.theme.AppThemeMode
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.ZoneId
import kotlin.math.abs

fun shouldShowSharedTagCloudEntryPoints(
    isConfigured: Boolean,
    hasSharedTags: Boolean,
    hasPendingInvite: Boolean = false,
): Boolean = isConfigured || hasSharedTags || hasPendingInvite

@Composable
fun UrlSaverRoot(
    activityViewModel: MainActivityViewModel,
    themeMode: AppThemeMode,
    onThemeModeChange: (AppThemeMode) -> Unit,
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
                is MainNavigationEvent.NavigateToTagDetail -> {
                    navController.navigate(Routes.tagDetail(event.tagId)) {
                        launchSingleTop = true
                    }
                }
                is MainNavigationEvent.NavigateToInvite -> {
                    navController.navigate(Routes.invite(event.inviteToken)) {
                        launchSingleTop = true
                    }
                }
                MainNavigationEvent.NavigateToCloudAuth -> {
                    navController.navigate(Routes.CLOUD_AUTH) {
                        launchSingleTop = true
                    }
                }
            }
        }
    }

    val backStackEntry by navController.currentBackStackEntryAsState()
    val resolvedRoute = remember(backStackEntry) {
        val destination = backStackEntry?.destination?.route
        when (destination) {
            Routes.DETAIL_PATTERN -> {
                val id = backStackEntry?.arguments?.getLong("entryId")
                if (id != null) "detail/$id" else destination
            }
            Routes.TAG_DETAIL_PATTERN -> {
                val id = backStackEntry?.arguments?.getLong("tagId")
                if (id != null) "tag/$id" else destination
            }
            Routes.INVITE_PATTERN -> {
                val token = backStackEntry?.arguments?.getString("inviteToken")
                if (token != null) "invite/$token" else destination
            }
            else -> destination
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
            urlSaverNavGraph(
                context = context,
                navController = navController,
                activityViewModel = activityViewModel,
                themeMode = themeMode,
                onThemeModeChange = onThemeModeChange,
                resolvedRoute = resolvedRoute,
            )
        }
    }
}

private val MainTopBarActionIconSize = 30.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun orbitTopAppBarColors() = TopAppBarDefaults.topAppBarColors(
    containerColor = Color.Transparent,
    scrolledContainerColor = MaterialTheme.colorScheme.background,
    navigationIconContentColor = MaterialTheme.colorScheme.onBackground,
    titleContentColor = MaterialTheme.colorScheme.onBackground,
    actionIconContentColor = MaterialTheme.colorScheme.onBackground,
)

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun compactTopAppBarInsets() = TopAppBarDefaults.windowInsets.only(WindowInsetsSides.Horizontal)

private fun androidx.navigation.NavGraphBuilder.urlSaverNavGraph(
    context: Context,
    navController: androidx.navigation.NavHostController,
    activityViewModel: MainActivityViewModel,
    themeMode: AppThemeMode,
    onThemeModeChange: (AppThemeMode) -> Unit,
    resolvedRoute: String?,
) {
    composable(Routes.MAIN) {
        val vm: MainListViewModel = viewModel(
            factory = SimpleFactory {
                MainListViewModel(
                    repository = context.appContainer().repository,
                    displayModeStore = context.appContainer().entryCardDisplayModeStore,
                    serviceFilterOrderStore = context.appContainer().serviceFilterOrderStore,
                    topFilterOrderStore = context.appContainer().topFilterOrderStore,
                )
            },
        )
        val tagVm: TagListViewModel = viewModel(
            factory = SimpleFactory {
                TagListViewModel(context.appContainer().tagRepository)
            },
        )
        MainScreen(
            viewModel = vm,
            tagViewModel = tagVm,
            themeMode = themeMode,
            onThemeModeChange = onThemeModeChange,
            onOpenArchive = { navController.navigate(Routes.ARCHIVE) },
            onOpenDetail = { navController.navigate(Routes.detail(it)) },
            onOpenTagDetail = { navController.navigate(Routes.tagDetail(it)) },
            onManualResult = { result, entryId ->
                activityViewModel.onManualSaveResult(result, entryId, resolvedRoute)
            },
            onArchiveEntry = { entryId ->
                activityViewModel.onDetailEffect(DetailEffect.NavigateBackAfterArchive(entryId))
            },
            onPendingDeleteEntry = { entryId, pendingUntil ->
                activityViewModel.startDeleteTimer(entryId, pendingUntil)
                activityViewModel.onDetailEffect(DetailEffect.NavigateBackAfterPendingDelete(entryId))
            },
            onBatchArchiveEntries = { entryIds ->
                activityViewModel.onBatchArchive(entryIds)
            },
            onBatchPendingDeleteEntries = { pendingDeletions ->
                activityViewModel.onBatchPendingDelete(pendingDeletions)
            },
            onArchiveFailed = { activityViewModel.notifyArchiveFailed() },
            onDeleteFailed = { activityViewModel.notifyDeleteFailed() },
            onCollectionDeleted = { activityViewModel.notifyCollectionDeleted() },
            onCollectionDeleteFailed = { activityViewModel.notifyCollectionDeleteFailed() },
        )
    }

    composable(Routes.ARCHIVE) {
        val vm: ArchiveViewModel = viewModel(
            factory = SimpleFactory {
                ArchiveViewModel(
                    repository = context.appContainer().repository,
                    displayModeStore = context.appContainer().entryCardDisplayModeStore,
                    serviceFilterOrderStore = context.appContainer().serviceFilterOrderStore,
                    topFilterOrderStore = context.appContainer().topFilterOrderStore,
                )
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
                DetailViewModel(
                    entryId = entryId,
                    repository = context.appContainer().repository,
                    tagRepository = context.appContainer().tagRepository,
                )
            },
        )
        DetailScreen(
            entryId = entryId,
            viewModel = vm,
            onBack = {
                val popped = navController.popBackStack()
                if (!popped) {
                    navController.navigate(Routes.MAIN) {
                        launchSingleTop = true
                        popUpTo(Routes.MAIN) { inclusive = false }
                    }
                }
            },
            onOpenMain = {
                navController.navigate(Routes.MAIN) {
                    launchSingleTop = true
                    popUpTo(Routes.MAIN) { inclusive = false }
                }
            },
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
            onOpenFailed = { activityViewModel.notifyOpenFailed() },
            onMetadataRetryUnavailable = { activityViewModel.notifyMetadataRetryUnavailable() },
        )
    }

    composable(
        route = Routes.TAG_DETAIL_PATTERN,
        arguments = listOf(navArgument("tagId") { type = NavType.LongType }),
    ) { tagBackStack ->
        val tagId = tagBackStack.arguments?.getLong("tagId") ?: return@composable
        val vm: TagDetailViewModel = viewModel(
            key = "tag_$tagId",
            factory = SimpleFactory {
                TagDetailViewModel(
                    tagId = tagId,
                    tagRepository = context.appContainer().tagRepository,
                    urlRepository = context.appContainer().repository,
                    displayModeStore = context.appContainer().entryCardDisplayModeStore,
                )
            },
        )
        TagDetailScreen(
            viewModel = vm,
            onBack = { navController.popBackStack() },
            onOpenDetail = { navController.navigate(Routes.detail(it)) },
            onOpenCloudAuth = { navController.navigate(Routes.CLOUD_AUTH) },
        )
    }

    composable(Routes.CLOUD_AUTH) {
        val vm: SharedTagAuthViewModel = viewModel(
            factory = SimpleFactory {
                SharedTagAuthViewModel(
                    tagRepository = context.appContainer().tagRepository,
                    userProfileStore = context.appContainer().userProfileStore,
                )
            },
        )
        SharedTagCloudAuthScreen(
            viewModel = vm,
            themeMode = themeMode,
            onThemeModeChange = onThemeModeChange,
            onBack = { navController.popBackStack() },
            showInviteCodeSection = false,
        )
    }

    composable(Routes.EXPORT) {
        val vm: ExportViewModel = viewModel(
            factory = SimpleFactory {
                ExportViewModel(context.appContainer().exportRepository)
            },
        )
        ExportScreen(
            viewModel = vm,
            onBack = { navController.popBackStack() },
            showSharedTagExportPreset = BuildConfig.SHARED_TAG_CLOUD_ENABLED,
        )
    }

    composable(
        route = Routes.INVITE_PATTERN,
        arguments = listOf(navArgument("inviteToken") { type = NavType.StringType }),
    ) { inviteBackStack ->
        val inviteToken = inviteBackStack.arguments?.getString("inviteToken").orEmpty()
        val vm: SharedTagInviteViewModel = viewModel(
            key = "invite_$inviteToken",
            factory = SimpleFactory {
                SharedTagInviteViewModel(
                    inviteToken = inviteToken,
                    tagRepository = context.appContainer().tagRepository,
                )
            },
        )
        SharedTagInviteScreen(
            viewModel = vm,
            onBack = { navController.popBackStack() },
            onInviteJoined = { tagId ->
                navController.navigate(Routes.tagDetail(tagId)) {
                    popUpTo(Routes.MAIN) { inclusive = false }
                    launchSingleTop = true
                }
            },
        )
    }
}

@Composable
private fun ExportSheetDragHandle() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp, bottom = 2.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .width(42.dp)
                .height(5.dp)
                .background(
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    shape = RoundedCornerShape(99.dp),
                ),
        )
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun MainScreen(
    viewModel: MainListViewModel,
    tagViewModel: TagListViewModel,
    themeMode: AppThemeMode,
    onThemeModeChange: (AppThemeMode) -> Unit,
    onOpenArchive: () -> Unit,
    onOpenDetail: (Long) -> Unit,
    onOpenTagDetail: (Long) -> Unit,
    onManualResult: (ShareSaveResult, Long?) -> Unit,
    onArchiveEntry: (Long) -> Unit,
    onPendingDeleteEntry: (Long, Long) -> Unit,
    onBatchArchiveEntries: (List<Long>) -> Unit,
    onBatchPendingDeleteEntries: (Map<Long, Long>) -> Unit,
    onArchiveFailed: () -> Unit,
    onDeleteFailed: () -> Unit,
    onCollectionDeleted: () -> Unit,
    onCollectionDeleteFailed: () -> Unit,
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val collections by viewModel.collections.collectAsStateWithLifecycle()
    val sharedTags by tagViewModel.tags.collectAsStateWithLifecycle()
    val selectedService by viewModel.selectedServiceFlow.collectAsStateWithLifecycle()
    val selectedCollectionId by viewModel.selectedCollectionIdFlow.collectAsStateWithLifecycle()
    val serviceFilterOrder by viewModel.serviceFilterOrder.collectAsStateWithLifecycle()
    val topFilterOrderTokens by viewModel.topFilterOrderTokens.collectAsStateWithLifecycle()
    val entryCardDisplayMode by viewModel.entryCardDisplayMode.collectAsStateWithLifecycle()
    val exportVm: ExportViewModel = viewModel(
        key = "main_export_sheet",
        factory = SimpleFactory {
            ExportViewModel(context.appContainer().exportRepository)
        },
    )
    val profileVm: SharedTagAuthViewModel = viewModel(
        key = "main_profile_sheet",
        factory = SimpleFactory {
            SharedTagAuthViewModel(
                tagRepository = context.appContainer().tagRepository,
                userProfileStore = context.appContainer().userProfileStore,
            )
        },
    )
    val sharedTagCloudState by profileVm.cloudState.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val mainListState = rememberLazyListState()
    val customCollections = remember(collections) {
        collections.filter { it.id != DEFAULT_COLLECTION_ID }
    }
    val localCollectionNameSet = remember(customCollections) {
        customCollections.map { normalizeSharedTagName(it.name) }.toSet()
    }
    val visibleSharedTags = remember(sharedTags, localCollectionNameSet) {
        sharedTags.filter { tag ->
            tag.scope == SharedTagScope.SYNCED &&
                normalizeSharedTagName(tag.name) !in localCollectionNameSet
        }
    }
    val showSharedTagCloudUi = shouldShowSharedTagCloudEntryPoints(
        isConfigured = sharedTagCloudState.isConfigured,
        hasSharedTags = visibleSharedTags.isNotEmpty(),
    )

    var manualInputState by remember { mutableStateOf(ManualInputUiState()) }
    var showPrivacyDialog by remember { mutableStateOf(false) }
    var createCollectionState by remember { mutableStateOf(CreateCollectionDialogUiState()) }
    var sharedTagDialogState by remember { mutableStateOf(SharedTagDialogUiState()) }
    var showExportSheet by rememberSaveable { mutableStateOf(false) }
    val exportSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var showProfileSheet by rememberSaveable { mutableStateOf(false) }
    val profileSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var showLocalTagManagerSheet by rememberSaveable { mutableStateOf(false) }
    var pendingDeleteCollection by remember { mutableStateOf<CollectionEntity?>(null) }
    var selectedEntryIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var previousTopEntryId by remember { mutableStateOf<Long?>(null) }
    var previousVisibleEntryCount by remember { mutableStateOf(0) }

    LaunchedEffect(uiState.entries) {
        val visibleIds = uiState.entries.map { it.id }.toSet()
        selectedEntryIds = selectedEntryIds.intersect(visibleIds)
    }

    LaunchedEffect(uiState.entries, selectedService, selectedCollectionId) {
        val currentTopEntryId = uiState.entries.firstOrNull()?.id
        val visibleEntryCount = uiState.entries.size
        val addedToTop = currentTopEntryId != null &&
            currentTopEntryId != previousTopEntryId &&
            visibleEntryCount > previousVisibleEntryCount
        if (
            addedToTop &&
            (mainListState.firstVisibleItemIndex > 0 || mainListState.firstVisibleItemScrollOffset > 0)
        ) {
            mainListState.animateScrollToItem(0)
        }
        previousTopEntryId = currentTopEntryId
        previousVisibleEntryCount = visibleEntryCount
    }

    fun openManualInput() {
        manualInputState = ManualInputUiState(
            visible = true,
            selectedCollectionId = customCollections.firstOrNull { it.id == selectedCollectionId }?.id,
        )
    }

    fun closeCreateCollectionDialog() {
        createCollectionState = CreateCollectionDialogUiState()
    }

    fun closeCreateSharedTagDialog() {
        sharedTagDialogState = SharedTagDialogUiState()
    }

    fun submitManualSave() {
        scope.launch {
            manualInputState = manualInputState.copy(isSaving = true)
            try {
                val targetCollectionId = manualInputState.selectedCollectionId
                val (result, entryId) = viewModel.submitManualInput(manualInputState.inputText, targetCollectionId)
                when (result) {
                    ShareSaveResult.INPUT_TOO_LARGE,
                    ShareSaveResult.INVALID_URL,
                    ShareSaveResult.NO_URL_FOUND,
                    ShareSaveResult.PERSONAL_URL_LIMIT_REACHED,
                    -> manualInputState = manualInputState.copy(inputError = result)
                    else -> {
                        onManualResult(result, entryId)
                        if (result == ShareSaveResult.CREATED || result == ShareSaveResult.RESTORED_FROM_PENDING_DELETE) {
                            AdsManager.registerMeaningfulActionAndMaybeShow(context)
                        }
                        manualInputState = ManualInputUiState()
                    }
                }
            } finally {
                manualInputState = manualInputState.copy(isSaving = false)
            }
        }
    }

    fun confirmCreateCollection() {
        scope.launch {
            val result = viewModel.createCollection(createCollectionState.name)
            when {
                result.success && result.collectionId != null -> {
                    manualInputState = manualInputState.copy(
                        selectedCollectionId = result.collectionId,
                        collectionError = null,
                    )
                    closeCreateCollectionDialog()
                }

                result.duplicateName -> {
                    if (result.collectionId != null) {
                        manualInputState = manualInputState.copy(
                            selectedCollectionId = result.collectionId,
                            collectionError = null,
                        )
                        closeCreateCollectionDialog()
                    } else {
                        createCollectionState = createCollectionState.copy(error = "同じ名前のタグがあります")
                    }
                }

                result.invalidName -> {
                    createCollectionState = createCollectionState.copy(error = "タグ名は1〜40文字で入力してください")
                }

                else -> {
                    createCollectionState = createCollectionState.copy(error = "タグを作成できませんでした")
                }
            }
        }
    }

    fun confirmCreateSharedTag() {
        scope.launch {
            val normalizedName = normalizeSharedTagName(sharedTagDialogState.name)
            val error = when {
                normalizedName in localCollectionNameSet -> {
                    "同じ名前の通常タグがあります。通常タグとして追加してください"
                }
                else -> when (validateSharedTagName(sharedTagDialogState.name)) {
                    SharedTagNameValidationError.BLANK -> "共有タグ名を入力してください"
                    SharedTagNameValidationError.TOO_LONG -> "共有タグ名は50文字以内で入力してください"
                    null -> null
                }
            }
            sharedTagDialogState = sharedTagDialogState.copy(error = error)
            if (error != null) return@launch

            when (val created = tagViewModel.createSharedTag(sharedTagDialogState.name)) {
                is CreateTagResult.Success -> closeCreateSharedTagDialog()
                CreateTagResult.InvalidName -> {
                    sharedTagDialogState = sharedTagDialogState.copy(error = "共有タグ名を入力してください")
                }
                CreateTagResult.Duplicate -> {
                    sharedTagDialogState = sharedTagDialogState.copy(error = "同じ名前の共有タグがあります")
                }
                is CreateTagResult.LimitReached -> {
                    sharedTagDialogState = sharedTagDialogState.copy(error = created.message)
                }
                CreateTagResult.Failed -> {
                    sharedTagDialogState = sharedTagDialogState.copy(error = "共有タグを作成できませんでした")
                }
            }
        }
    }

    fun reorderCollections(collectionIds: List<Long>) {
        scope.launch {
            viewModel.reorderCollections(collectionIds)
        }
    }

    fun toggleSelectedCollection(targetId: Long?) {
        if (selectedCollectionId == targetId) {
            viewModel.selectCollection(null)
        } else {
            viewModel.selectCollection(targetId)
        }
    }

    fun archiveActiveEntry(entryId: Long) {
        scope.launch {
            val archived = viewModel.archiveEntry(entryId)
            if (archived) {
                AdsManager.registerMeaningfulActionAndMaybeShow(context)
                onArchiveEntry(entryId)
            } else {
                onArchiveFailed()
            }
        }
    }

    fun pendingDeleteActiveEntry(entryId: Long) {
        scope.launch {
            val pendingUntil = viewModel.markPendingDelete(entryId)
            if (pendingUntil != null) {
                onPendingDeleteEntry(entryId, pendingUntil)
            } else {
                onDeleteFailed()
            }
        }
    }

    fun toggleEntrySelection(entryId: Long) {
        selectedEntryIds = if (entryId in selectedEntryIds) {
            selectedEntryIds - entryId
        } else {
            selectedEntryIds + entryId
        }
    }

    fun archiveSelectedEntries() {
        scope.launch {
            val archivedIds = viewModel.archiveEntries(selectedEntryIds)
            if (archivedIds.isNotEmpty()) {
                selectedEntryIds = emptySet()
                onBatchArchiveEntries(archivedIds)
            } else {
                onArchiveFailed()
            }
        }
    }

    fun deleteSelectedEntries() {
        scope.launch {
            val pendingDeletions = viewModel.markPendingDeleteEntries(selectedEntryIds)
            if (pendingDeletions.isNotEmpty()) {
                selectedEntryIds = emptySet()
                onBatchPendingDeleteEntries(pendingDeletions)
            } else {
                onDeleteFailed()
            }
        }
    }

    fun deleteCollection(collection: CollectionEntity) {
        scope.launch {
            val deleted = viewModel.deleteCollection(collection.id)
            if (deleted) {
                onCollectionDeleted()
            } else {
                onCollectionDeleteFailed()
            }
        }
    }

    if (showPrivacyDialog) {
        AlertDialog(
            onDismissRequest = { showPrivacyDialog = false },
            title = { Text(stringResource(R.string.privacy_dialog_title)) },
            text = { Text(stringResource(R.string.privacy_dialog_body)) },
            confirmButton = {
                TextButton(onClick = { showPrivacyDialog = false }) {
                    Text(stringResource(R.string.privacy_dialog_close))
                }
            },
        )
    }

    ManualInputSheet(
        visible = manualInputState.visible,
        inputText = manualInputState.inputText,
        inputError = manualInputState.inputError,
        customCollections = customCollections,
        selectedTargetCollectionId = manualInputState.selectedCollectionId,
        manualCollectionError = manualInputState.collectionError,
        isManualSaving = manualInputState.isSaving,
        onDismiss = {
            manualInputState = ManualInputUiState()
        },
        onInputChange = {
            manualInputState = manualInputState.copy(
                inputText = it,
                inputError = null,
            )
        },
        onPaste = {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val pasted = clipboard.primaryClip
                ?.takeIf { it.itemCount > 0 }
                ?.getItemAt(0)
                ?.coerceToText(context)
                ?.toString()
                .orEmpty()
                .trim()
            if (pasted.isNotEmpty()) {
                manualInputState = manualInputState.copy(
                    inputText = pasted,
                    inputError = null,
                )
            }
        },
        onSelectCollection = { targetId ->
            manualInputState = manualInputState.copy(
                collectionError = null,
                selectedCollectionId = if (manualInputState.selectedCollectionId == targetId) null else targetId,
            )
        },
        onRequestCreateCollection = {
            createCollectionState = createCollectionState.copy(
                visible = true,
                error = null,
            )
        },
        onSave = { submitManualSave() },
    )

    LocalTagManagementSheet(
        visible = showLocalTagManagerSheet,
        collections = customCollections,
        onDismiss = { showLocalTagManagerSheet = false },
        onRequestDelete = { collection ->
            showLocalTagManagerSheet = false
            pendingDeleteCollection = collection
        },
    )

    pendingDeleteCollection?.let { collection ->
        AlertDialog(
            onDismissRequest = { pendingDeleteCollection = null },
            title = { Text("タグを削除") },
            text = {
                Text("「${collection.name}」を削除します。URL自体は削除されず、受信箱に戻ります。")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingDeleteCollection = null
                        deleteCollection(collection)
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) {
                    Text("削除する")
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteCollection = null }) {
                    Text("キャンセル")
                }
            },
        )
    }

    CreateCollectionDialog(
        visible = createCollectionState.visible,
        newCollectionName = createCollectionState.name,
        createCollectionError = createCollectionState.error,
        onDismiss = { closeCreateCollectionDialog() },
        onNameChange = {
            createCollectionState = createCollectionState.copy(
                name = it,
                error = null,
            )
        },
        onConfirm = { confirmCreateCollection() },
    )

    CreateSharedTagDialog(
        visible = sharedTagDialogState.visible && showSharedTagCloudUi,
        newSharedTagName = sharedTagDialogState.name,
        createSharedTagError = sharedTagDialogState.error,
        onDismiss = { closeCreateSharedTagDialog() },
        onNameChange = {
            sharedTagDialogState = sharedTagDialogState.copy(
                name = it,
                error = null,
            )
        },
        onConfirm = { confirmCreateSharedTag() },
    )

    if (showExportSheet) {
        ModalBottomSheet(
            onDismissRequest = { showExportSheet = false },
            sheetState = exportSheetState,
            dragHandle = { ExportSheetDragHandle() },
        ) {
            ExportScreen(
                viewModel = exportVm,
                onBack = { showExportSheet = false },
                showSharedTagExportPreset = BuildConfig.SHARED_TAG_CLOUD_ENABLED,
            )
        }
    }

    if (showProfileSheet && showSharedTagCloudUi) {
        ModalBottomSheet(
            onDismissRequest = { showProfileSheet = false },
            sheetState = profileSheetState,
            dragHandle = null,
        ) {
            SharedTagCloudAuthScreen(
                viewModel = profileVm,
                themeMode = themeMode,
                onThemeModeChange = onThemeModeChange,
                onBack = { showProfileSheet = false },
                showInviteCodeSection = true,
            )
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("保存したURL") },
                colors = orbitTopAppBarColors(),
                windowInsets = compactTopAppBarInsets(),
                actions = {
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
                            modifier = Modifier.size(MainTopBarActionIconSize),
                        )
                    }
                    if (showSharedTagCloudUi) {
                        IconButton(onClick = { showProfileSheet = true }) {
                            Icon(
                                Icons.Outlined.AccountCircle,
                                contentDescription = "プロフィール",
                                modifier = Modifier.size(MainTopBarActionIconSize),
                            )
                        }
                    }
                    IconButton(onClick = { showExportSheet = true }) {
                        Icon(
                            Icons.Outlined.IosShare,
                            contentDescription = "エクスポート",
                            modifier = Modifier.size(MainTopBarActionIconSize),
                        )
                    }
                    IconButton(onClick = onOpenArchive) {
                        Icon(
                            Icons.Outlined.Archive,
                            contentDescription = "アーカイブ",
                            modifier = Modifier.size(MainTopBarActionIconSize),
                        )
                    }
                    IconButton(onClick = { showLocalTagManagerSheet = true }) {
                        Icon(
                            Icons.Outlined.Sell,
                            contentDescription = "タグ管理",
                            modifier = Modifier.size(MainTopBarActionIconSize),
                        )
                    }
                },
            )
        },
        bottomBar = {
            if (selectedEntryIds.isEmpty()) {
                MainAddUrlBar(onClick = { openManualInput() })
            }
        },
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .padding(paddingValues)
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .widthIn(max = OrbitTokens.contentMaxWidth)
            ) {
                MainListContent(
                    uiState = uiState,
                    customCollections = customCollections,
                    sharedTags = visibleSharedTags,
                    showSharedTagCloudUi = showSharedTagCloudUi,
                    selectedService = selectedService,
                    selectedCollectionId = selectedCollectionId,
                    serviceFilterOrder = serviceFilterOrder,
                topFilterOrderTokens = topFilterOrderTokens,
                selectedEntryIds = selectedEntryIds,
                entryCardDisplayMode = entryCardDisplayMode,
                mainListState = mainListState,
                onSelectService = { viewModel.selectService(it) },
                onReorderServices = { serviceOrder ->
                    viewModel.reorderServices(serviceOrder)
                    },
                    onReorderTopFilters = { tokens ->
                        viewModel.reorderTopFilters(tokens)
                    },
                    onSelectCollection = { targetId -> toggleSelectedCollection(targetId) },
                    onRequestCreateCollection = {
                        createCollectionState = createCollectionState.copy(
                            visible = true,
                            error = null,
                        )
                    },
                    onReorderCollections = { collectionIds ->
                        reorderCollections(collectionIds)
                    },
                    onOpenTagDetail = onOpenTagDetail,
                    onRequestCreateSharedTag = {
                        sharedTagDialogState = sharedTagDialogState.copy(
                            visible = true,
                            error = null,
                        )
                    },
                    onStartEntrySelection = { entryId ->
                        selectedEntryIds = setOf(entryId)
                    },
                    onToggleEntrySelection = { entryId ->
                        toggleEntrySelection(entryId)
                    },
                    onSelectAllEntries = {
                        selectedEntryIds = uiState.entries.map { it.id }.toSet()
                    },
                    onClearEntrySelection = {
                        selectedEntryIds = emptySet()
                    },
                    onArchiveSelectedEntries = { archiveSelectedEntries() },
                    onDeleteSelectedEntries = { deleteSelectedEntries() },
                    onOpenDetail = onOpenDetail,
                    onArchiveActiveEntry = { entryId -> archiveActiveEntry(entryId) },
                    onPendingDeleteActiveEntry = { entryId -> pendingDeleteActiveEntry(entryId) },
                )
            }
        }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun ManualInputSheet(
    visible: Boolean,
    inputText: String,
    inputError: ShareSaveResult?,
    customCollections: List<jp.mimac.urlsaver.data.CollectionEntity>,
    selectedTargetCollectionId: Long?,
    manualCollectionError: String?,
    isManualSaving: Boolean,
    onDismiss: () -> Unit,
    onInputChange: (String) -> Unit,
    onPaste: () -> Unit,
    onSelectCollection: (Long?) -> Unit,
    onRequestCreateCollection: () -> Unit,
    onSave: () -> Unit,
) {
    if (!visible) return

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.padding(16.dp)) {
            OutlinedTextField(
                value = inputText,
                onValueChange = onInputChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("manual_input_field"),
                label = { Text("URL") },
                placeholder = { Text("https://example.com") },
                singleLine = true,
                maxLines = 1,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Uri,
                    imeAction = ImeAction.Done,
                ),
                isError = inputError == ShareSaveResult.INVALID_URL ||
                    inputError == ShareSaveResult.NO_URL_FOUND ||
                    inputError == ShareSaveResult.INPUT_TOO_LARGE ||
                    inputError == ShareSaveResult.PERSONAL_URL_LIMIT_REACHED,
                supportingText = {
                    when (inputError) {
                        ShareSaveResult.INVALID_URL -> Text("URL形式が正しくありません。https:// から始まるURLを入力してください")
                        ShareSaveResult.NO_URL_FOUND -> Text("入力内にURLが見つかりませんでした。URLをそのまま貼り付けてください")
                        ShareSaveResult.INPUT_TOO_LARGE -> Text("入力が長すぎます。256KB以内のテキストでURLを貼り付けてください")
                        ShareSaveResult.PERSONAL_URL_LIMIT_REACHED -> Text("ローンチ版の保存上限に達しました。不要なURLを整理してから追加してください。")
                        else -> Unit
                    }
                },
            )
            Spacer(Modifier.height(12.dp))
            TextButton(
                onClick = onPaste,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("クリップボードを貼り付け")
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = "タグ",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(bottom = 6.dp),
            )
            if (customCollections.isEmpty()) {
                Text(
                    text = "タグがまだありません。必要なら作成してください",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                )
            } else {
                CollectionFilterRow(
                    collections = customCollections,
                    selectedCollectionId = selectedTargetCollectionId,
                    includeAllOption = false,
                    onSelect = { targetId -> onSelectCollection(targetId) },
                )
            }
            if (!manualCollectionError.isNullOrBlank()) {
                Text(
                    text = manualCollectionError,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                )
            }
            Spacer(Modifier.height(6.dp))
            TextButton(
                onClick = onRequestCreateCollection,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("＋ タグを作成")
            }
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = onSave,
                enabled = inputText.trim().isNotEmpty() && !isManualSaving,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("manual_input_save"),
            ) {
                if (isManualSaving) {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .width(16.dp)
                            .height(16.dp),
                        strokeWidth = 2.dp,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("保存中…")
                } else {
                    Text("保存")
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
private fun LocalTagManagementSheet(
    visible: Boolean,
    collections: List<CollectionEntity>,
    onDismiss: () -> Unit,
    onRequestDelete: (CollectionEntity) -> Unit,
) {
    if (!visible) return

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "タグ管理",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleLarge,
                )
                TextButton(onClick = onDismiss) {
                    Text("閉じる")
                }
            }

            if (collections.isEmpty()) {
                OrbitPanel(tone = OrbitPanelTone.SOFT) {
                    Text(
                        text = "削除できるタグはまだありません",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    collections.forEach { collection ->
                        Surface(
                            shape = RoundedCornerShape(22.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            border = BorderStroke(1.dp, OrbitTokens.outline),
                        ) {
                            Row(
                                modifier = Modifier.padding(start = 14.dp, top = 8.dp, end = 8.dp, bottom = 8.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = collection.name,
                                    modifier = Modifier.widthIn(max = 120.dp),
                                    style = MaterialTheme.typography.titleSmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                TextButton(
                                    onClick = { onRequestDelete(collection) },
                                    modifier = Modifier.height(40.dp),
                                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp),
                                    colors = ButtonDefaults.textButtonColors(
                                        contentColor = MaterialTheme.colorScheme.error,
                                    ),
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.Delete,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp),
                                    )
                                    Spacer(Modifier.width(4.dp))
                                    Text(
                                        text = "削除",
                                        style = MaterialTheme.typography.titleSmall,
                                    )
                                }
                            }
                        }
                    }
                }
            }

        }
    }
}

@Composable
private fun CreateCollectionDialog(
    visible: Boolean,
    newCollectionName: String,
    createCollectionError: String?,
    onDismiss: () -> Unit,
    onNameChange: (String) -> Unit,
    onConfirm: () -> Unit,
) {
    if (!visible) return

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("タグを作成") },
        text = {
            OutlinedTextField(
                value = newCollectionName,
                onValueChange = onNameChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("名前") },
                placeholder = { Text("仕事 / 後で読む / 学習 など") },
                supportingText = {
                    if (!createCollectionError.isNullOrBlank()) {
                        Text(createCollectionError)
                    }
                },
                isError = !createCollectionError.isNullOrBlank(),
            )
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                enabled = newCollectionName.trim().isNotEmpty(),
            ) {
                Text("作成")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("キャンセル")
            }
        },
    )
}

@Composable
private fun CreateSharedTagDialog(
    visible: Boolean,
    newSharedTagName: String,
    createSharedTagError: String?,
    onDismiss: () -> Unit,
    onNameChange: (String) -> Unit,
    onConfirm: () -> Unit,
) {
    if (!visible) return

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("共有タグを作成") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "サインイン中は作成後すぐクラウド共有できます。未サインインの場合はこの端末だけで使われます。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = newSharedTagName,
                    onValueChange = onNameChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("共有タグ名") },
                    placeholder = { Text("チーム共有 / 旅行 / 見返す など") },
                    supportingText = {
                        if (!createSharedTagError.isNullOrBlank()) {
                            Text(createSharedTagError)
                        }
                    },
                    isError = !createSharedTagError.isNullOrBlank(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                enabled = newSharedTagName.trim().isNotEmpty(),
            ) {
                Text("作成")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("キャンセル")
            }
        },
    )
}

@Composable
private fun MainEntryList(
    entries: List<UrlEntryEntity>,
    listState: androidx.compose.foundation.lazy.LazyListState,
    selectedEntryIds: Set<Long>,
    entryCardDisplayMode: EntryCardDisplayMode,
    onStartSelection: (Long) -> Unit,
    onToggleSelection: (Long) -> Unit,
    onOpenDetail: (Long) -> Unit,
    onArchiveActiveEntry: (Long) -> Unit,
    onPendingDeleteActiveEntry: (Long) -> Unit,
) {
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
    ) {
        items(entries, key = { it.id }) { entry ->
            val selectionMode = selectedEntryIds.isNotEmpty()
            val selected = entry.id in selectedEntryIds
            if (entry.recordState == RecordState.ACTIVE && !selectionMode) {
                SwipeableMainEntry(
                    entry = entry,
                    displayMode = entryCardDisplayMode,
                    onClick = { onOpenDetail(entry.id) },
                    onLongClick = { onStartSelection(entry.id) },
                    onSwipeAction = { action ->
                        when (action) {
                            MainSwipeAction.ARCHIVE -> onArchiveActiveEntry(entry.id)
                            MainSwipeAction.PENDING_DELETE -> onPendingDeleteActiveEntry(entry.id)
                        }
                    },
                )
            } else {
                EntryCard(
                    entry = entry,
                    timestampMillis = entry.createdAt,
                    displayMode = entryCardDisplayMode,
                    showDisplayUrl = false,
                    selected = selected,
                    onLongClick = { onStartSelection(entry.id) },
                    onClick = {
                        if (selectionMode) {
                            onToggleSelection(entry.id)
                        } else {
                            onOpenDetail(entry.id)
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun MainListContent(
    uiState: ListFilterUiState,
    customCollections: List<jp.mimac.urlsaver.data.CollectionEntity>,
    sharedTags: List<jp.mimac.urlsaver.domain.TagWithCount>,
    showSharedTagCloudUi: Boolean,
    selectedService: ServiceType,
    selectedCollectionId: Long?,
    serviceFilterOrder: List<ServiceType>,
    topFilterOrderTokens: List<String>,
    selectedEntryIds: Set<Long>,
    entryCardDisplayMode: EntryCardDisplayMode,
    mainListState: androidx.compose.foundation.lazy.LazyListState,
    onSelectService: (ServiceType) -> Unit,
    onReorderServices: (List<ServiceType>) -> Unit,
    onReorderTopFilters: (List<String>) -> Unit,
    onSelectCollection: (Long?) -> Unit,
    onRequestCreateCollection: () -> Unit,
    onReorderCollections: (List<Long>) -> Unit,
    onOpenTagDetail: (Long) -> Unit,
    onRequestCreateSharedTag: () -> Unit,
    onStartEntrySelection: (Long) -> Unit,
    onToggleEntrySelection: (Long) -> Unit,
    onSelectAllEntries: () -> Unit,
    onClearEntrySelection: () -> Unit,
    onArchiveSelectedEntries: () -> Unit,
    onDeleteSelectedEntries: () -> Unit,
    onOpenDetail: (Long) -> Unit,
    onArchiveActiveEntry: (Long) -> Unit,
    onPendingDeleteActiveEntry: (Long) -> Unit,
) {
    ServiceFilterRow(
        selectedService = selectedService,
        serviceOrder = serviceFilterOrder,
        topFilterOrderTokens = topFilterOrderTokens,
        onReorderServices = onReorderServices,
        collections = customCollections,
        selectedCollectionId = selectedCollectionId,
        onSelectService = onSelectService,
        onSelectCollection = onSelectCollection,
        onCreateCollection = onRequestCreateCollection,
        onReorderTopFilters = onReorderTopFilters,
        onReorderCollections = onReorderCollections,
    )
    Spacer(modifier = Modifier.height(8.dp))
    if (showSharedTagCloudUi) {
        TagFilterRow(
            tags = sharedTags,
            onOpenTag = onOpenTagDetail,
            onCreateTag = onRequestCreateSharedTag,
        )
        Spacer(modifier = Modifier.height(8.dp))
    }
    if (selectedEntryIds.isNotEmpty()) {
        EntrySelectionBar(
            selectedCount = selectedEntryIds.size,
            allSelected = uiState.entries.isNotEmpty() && selectedEntryIds.size == uiState.entries.size,
            onSelectAll = onSelectAllEntries,
            onArchive = onArchiveSelectedEntries,
            onDelete = onDeleteSelectedEntries,
            onCancel = onClearEntrySelection,
        )
        Spacer(modifier = Modifier.height(8.dp))
    }

    when {
        uiState.globalCount == 0 -> {
            EmptyState(
                title = stringResource(R.string.main_empty_title),
            )
        }

        uiState.scopeCount == 0 -> {
            EmptyState(
                title = stringResource(R.string.main_filtered_empty_title),
            )
        }

        else -> {
            MainEntryList(
                entries = uiState.entries,
                listState = mainListState,
                selectedEntryIds = selectedEntryIds,
                entryCardDisplayMode = entryCardDisplayMode,
                onStartSelection = onStartEntrySelection,
                onToggleSelection = onToggleEntrySelection,
                onOpenDetail = onOpenDetail,
                onArchiveActiveEntry = onArchiveActiveEntry,
                onPendingDeleteActiveEntry = onPendingDeleteActiveEntry,
            )
        }
    }
}

private data class ManualInputUiState(
    val visible: Boolean = false,
    val inputText: String = "",
    val inputError: ShareSaveResult? = null,
    val selectedCollectionId: Long? = null,
    val collectionError: String? = null,
    val isSaving: Boolean = false,
)

private data class CreateCollectionDialogUiState(
    val visible: Boolean = false,
    val name: String = "",
    val error: String? = null,
)

private data class SharedTagDialogUiState(
    val visible: Boolean = false,
    val name: String = "",
    val error: String? = null,
)

private enum class MainSwipeAction {
    ARCHIVE,
    PENDING_DELETE,
}

@Composable
private fun EntrySelectionBar(
    selectedCount: Int,
    allSelected: Boolean,
    onSelectAll: () -> Unit,
    onArchive: () -> Unit,
    onDelete: () -> Unit,
    onCancel: () -> Unit,
) {
    val buttonShape = androidx.compose.foundation.shape.RoundedCornerShape(50)
    Row(
        modifier = Modifier
            .padding(horizontal = 12.dp)
            .fillMaxWidth()
            .background(
                color = OrbitTokens.panelSoft,
                shape = androidx.compose.foundation.shape.RoundedCornerShape(OrbitTokens.radiusChip),
            )
            .border(
                width = 1.dp,
                color = OrbitTokens.outline,
                shape = androidx.compose.foundation.shape.RoundedCornerShape(OrbitTokens.radiusChip),
            )
            .padding(horizontal = 12.dp, vertical = 9.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "${selectedCount}件選択",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )

        Row(
            modifier = Modifier
                .height(36.dp)
                .background(OrbitTokens.panelStrong, buttonShape)
                .clickable(enabled = !allSelected, onClick = onSelectAll)
                .padding(horizontal = 10.dp)
                .semantics { contentDescription = "すべて選択" },
            horizontalArrangement = Arrangement.spacedBy(7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Outlined.Check,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.95f),
                modifier = Modifier.size(23.dp),
            )
            Text(
                text = "すべて選択",
                style = MaterialTheme.typography.labelLarge,
                color = Color.White.copy(alpha = if (allSelected) 0.45f else 0.95f),
                maxLines = 1,
            )
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SelectionIconButton(
                contentDescription = "アーカイブ",
                onClick = onArchive,
                icon = {
                    Icon(
                        imageVector = Icons.Outlined.Archive,
                        contentDescription = null,
                        tint = Color.White.copy(alpha = 0.95f),
                        modifier = Modifier.size(30.dp),
                    )
                },
            )
            SelectionIconButton(
                contentDescription = "削除",
                onClick = onDelete,
                icon = {
                    Icon(
                        imageVector = Icons.Outlined.Delete,
                        contentDescription = null,
                        tint = OrbitTokens.danger,
                        modifier = Modifier.size(30.dp),
                    )
                },
            )
            SelectionIconButton(
                contentDescription = "キャンセル",
                onClick = onCancel,
                icon = {
                    Icon(
                        imageVector = Icons.Outlined.Close,
                        contentDescription = null,
                        tint = Color.White.copy(alpha = 0.95f),
                        modifier = Modifier.size(30.dp),
                    )
                },
            )
        }
    }
}

@Composable
private fun SelectionIconButton(
    contentDescription: String,
    onClick: () -> Unit,
    icon: @Composable () -> Unit,
) {
    Box(
        modifier = Modifier
            .width(42.dp)
            .height(36.dp)
            .background(
                color = OrbitTokens.panelStrong,
                shape = androidx.compose.foundation.shape.RoundedCornerShape(50),
            )
            .clickable(onClick = onClick)
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        icon()
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun SwipeableMainEntry(
    entry: UrlEntryEntity,
    displayMode: EntryCardDisplayMode,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onSwipeAction: (MainSwipeAction) -> Unit,
) {
    val dismissState = rememberSwipeToDismissBoxState(
        positionalThreshold = { totalDistance -> totalDistance * ENTRY_CARD_SWIPE_ACTION_THRESHOLD_FRACTION },
        confirmValueChange = { targetValue ->
            when (targetValue) {
                SwipeToDismissBoxValue.StartToEnd -> {
                    onSwipeAction(MainSwipeAction.ARCHIVE)
                    false
                }

                SwipeToDismissBoxValue.EndToStart -> {
                    onSwipeAction(MainSwipeAction.PENDING_DELETE)
                    false
                }

                SwipeToDismissBoxValue.Settled -> true
            }
        },
    )

    SwipeToDismissBox(
        state = dismissState,
        modifier = Modifier.testTag("main_entry_swipe_${entry.id}"),
        enableDismissFromStartToEnd = true,
        enableDismissFromEndToStart = true,
        backgroundContent = {
            MainSwipeBackground(
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
            onLongClick = onLongClick,
            onClick = onClick,
        )
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun MainSwipeBackground(
    direction: SwipeToDismissBoxValue,
    swipeOffsetPx: Float,
) {
    val isArchive = direction == SwipeToDismissBoxValue.StartToEnd
    val isDelete = direction == SwipeToDismissBoxValue.EndToStart
    val density = LocalDensity.current
    val color = when {
        isArchive -> OrbitTokens.secondarySurface
        isDelete -> OrbitTokens.dangerSurface
        else -> OrbitTokens.panelSoft
    }
    val icon = when {
        isArchive -> Icons.Outlined.Archive
        isDelete -> Icons.Outlined.Delete
        else -> Icons.Outlined.Archive
    }
    val label = when {
        isArchive -> "アーカイブ"
        isDelete -> "削除"
        else -> "スワイプで操作"
    }
    val alignment = if (isDelete) Alignment.CenterEnd else Alignment.CenterStart
    val revealedWidth = with(density) { abs(swipeOffsetPx).toDp() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        if ((isArchive || isDelete) && revealedWidth > 0.dp) {
            Box(
                modifier = Modifier
                    .align(alignment)
                    .fillMaxHeight()
                    .width(revealedWidth)
                    .background(
                        color = color,
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(OrbitTokens.radiusPanel),
                    )
                    .padding(horizontal = 16.dp, vertical = 20.dp),
                contentAlignment = alignment,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (isDelete) {
                        Text(text = label, style = MaterialTheme.typography.labelLarge)
                        Icon(imageVector = icon, contentDescription = null)
                    } else {
                        Icon(imageVector = icon, contentDescription = null)
                        Text(text = label, style = MaterialTheme.typography.labelLarge)
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
    val collections by viewModel.collections.collectAsStateWithLifecycle()
    val selectedService by viewModel.selectedServiceFlow.collectAsStateWithLifecycle()
    val selectedCollectionId by viewModel.selectedCollectionIdFlow.collectAsStateWithLifecycle()
    val serviceFilterOrder by viewModel.serviceFilterOrder.collectAsStateWithLifecycle()
    val topFilterOrderTokens by viewModel.topFilterOrderTokens.collectAsStateWithLifecycle()
    val entryCardDisplayMode by viewModel.entryCardDisplayMode.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val customCollections = remember(collections) {
        collections.filter { it.id != DEFAULT_COLLECTION_ID }
    }
    var createCollectionState by remember { mutableStateOf(CreateCollectionDialogUiState()) }

    fun closeCreateCollectionDialog() {
        createCollectionState = CreateCollectionDialogUiState()
    }

    fun confirmCreateCollection() {
        scope.launch {
            val result = viewModel.createCollection(createCollectionState.name)
            when {
                result.success && result.collectionId != null -> {
                    viewModel.selectCollection(result.collectionId)
                    closeCreateCollectionDialog()
                }

                result.duplicateName -> {
                    if (result.collectionId != null) {
                        viewModel.selectCollection(result.collectionId)
                        closeCreateCollectionDialog()
                    } else {
                        createCollectionState = createCollectionState.copy(error = "同じ名前のタグがあります")
                    }
                }

                result.invalidName -> {
                    createCollectionState = createCollectionState.copy(error = "タグ名は1〜40文字で入力してください")
                }

                else -> {
                    createCollectionState = createCollectionState.copy(error = "タグを作成できませんでした")
                }
            }
        }
    }

    fun reorderCollections(collectionIds: List<Long>) {
        scope.launch {
            viewModel.reorderCollections(collectionIds)
        }
    }

    CreateCollectionDialog(
        visible = createCollectionState.visible,
        newCollectionName = createCollectionState.name,
        createCollectionError = createCollectionState.error,
        onDismiss = { closeCreateCollectionDialog() },
        onNameChange = {
            createCollectionState = createCollectionState.copy(
                name = it,
                error = null,
            )
        },
        onConfirm = { confirmCreateCollection() },
    )

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("アーカイブ") },
                colors = orbitTopAppBarColors(),
                windowInsets = compactTopAppBarInsets(),
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
                .background(MaterialTheme.colorScheme.background),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .widthIn(max = OrbitTokens.contentMaxWidth)
            ) {
                ServiceFilterRow(
                    selectedService = selectedService,
                    serviceOrder = serviceFilterOrder,
                    topFilterOrderTokens = topFilterOrderTokens,
                    collections = customCollections,
                    selectedCollectionId = selectedCollectionId,
                    onSelectService = { viewModel.selectService(it) },
                    onReorderServices = { serviceOrder ->
                        viewModel.reorderServices(serviceOrder)
                    },
                    onReorderTopFilters = { tokens ->
                        viewModel.reorderTopFilters(tokens)
                    },
                    onSelectCollection = { targetId ->
                        if (selectedCollectionId == targetId) {
                            viewModel.selectCollection(null)
                        } else {
                            viewModel.selectCollection(targetId)
                        }
                    },
                    onCreateCollection = {
                        createCollectionState = createCollectionState.copy(
                            visible = true,
                            error = null,
                        )
                    },
                    onReorderCollections = { collectionIds ->
                        reorderCollections(collectionIds)
                    },
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
                            title = "この保存先のアーカイブはありません",
                            body = "フィルターを変更してください",
                        )
                    }

                    else -> {
                        LazyColumn(modifier = Modifier.fillMaxSize()) {
                            items(uiState.entries, key = { it.id }) { entry ->
                                EntryCard(
                                    entry = entry,
                                    timestampMillis = entry.createdAt,
                                    timestampLabel = "アーカイブ",
                                    displayMode = entryCardDisplayMode,
                                    showDisplayUrl = false,
                                    onClick = { onOpenDetail(entry.id) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
private fun DetailScreen(
    entryId: Long,
    viewModel: DetailViewModel,
    onBack: () -> Unit,
    onOpenMain: () -> Unit,
    onDetailEffect: (DetailEffect) -> Unit,
    onScheduleDeleteTimer: (Long) -> Unit,
    onTitleEditStarted: () -> Unit,
    onCopySuccess: () -> Unit,
    onMemoSaved: () -> Unit,
    onOpenFailed: () -> Unit,
    onMetadataRetryUnavailable: () -> Unit,
) {
    val entry by viewModel.entry.collectAsStateWithLifecycle(initialValue = null)
    val assignedTags by viewModel.assignedTags.collectAsStateWithLifecycle()
    val allTagsWithCount by viewModel.allTagsWithCount.collectAsStateWithLifecycle()
    val cloudState by viewModel.cloudState.collectAsStateWithLifecycle()
    val collections by viewModel.collections.collectAsStateWithLifecycle()
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
    var showDeleteConfirmDialog by rememberSaveable { mutableStateOf(false) }
    var showAddLocalTagSheet by rememberSaveable { mutableStateOf(false) }
    var newLocalTagName by rememberSaveable { mutableStateOf("") }
    var localTagError by rememberSaveable { mutableStateOf<String?>(null) }
    var showAddSharedTagSheet by rememberSaveable { mutableStateOf(false) }
    var newSharedTagName by rememberSaveable { mutableStateOf("") }
    var sharedTagError by rememberSaveable { mutableStateOf<String?>(null) }
    var locallyRemovedCollectionIds by remember { mutableStateOf(emptySet<Long>()) }
    var locallyRemovedTagIds by remember { mutableStateOf(emptySet<Long>()) }
    var locallyAssignedTagIds by remember(entryId) { mutableStateOf(emptySet<Long>()) }
    val localTagSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    val current = entry
    val assignedLocalTags = remember(assignedTags) {
        assignedTags.filter { it.scope == SharedTagScope.LOCAL_ONLY }
    }
    val assignedSyncedTags = remember(assignedTags) {
        assignedTags.filter { it.scope == SharedTagScope.SYNCED }
    }
    val assignedTagIds = remember(assignedTags, locallyAssignedTagIds) {
        detailAssignedTagIds(
            assignedTags = assignedTags,
            locallyAssignedTagIds = locallyAssignedTagIds,
        )
    }
    LaunchedEffect(assignedTagIds) {
        locallyRemovedTagIds = locallyRemovedTagIds.intersect(assignedTagIds)
    }
    val customCollections = remember(collections) {
        collections.filter { it.id != DEFAULT_COLLECTION_ID }
    }
    val currentCollection = remember(current?.collectionId, customCollections) {
        customCollections.firstOrNull { it.id == current?.collectionId }
    }
    LaunchedEffect(current?.collectionId) {
        val currentCollectionId = current?.collectionId
        locallyRemovedCollectionIds = locallyRemovedCollectionIds.filterTo(mutableSetOf()) { it == currentCollectionId }
    }
    val localCollectionNameSet = remember(customCollections) {
        customCollections.map { normalizeSharedTagName(it.name) }.toSet()
    }
    val assignedLocalTagNameSet = remember(assignedLocalTags, currentCollection) {
        buildSet {
            currentCollection?.let { add(normalizeSharedTagName(it.name)) }
            assignedLocalTags.forEach { add(normalizeSharedTagName(it.name)) }
        }
    }
    val availableLocalTags = remember(
        allTagsWithCount,
        assignedTagIds,
        assignedLocalTagNameSet,
        localCollectionNameSet,
    ) {
        allTagsWithCount
            .asSequence()
            .filter { it.scope == SharedTagScope.LOCAL_ONLY }
            .filterNot { it.id in assignedTagIds }
            .filter { it.urlCount > 0 }
            .filter { tag -> normalizeSharedTagName(tag.name) !in assignedLocalTagNameSet }
            .filter { tag -> normalizeSharedTagName(tag.name) !in localCollectionNameSet }
            .distinctBy { normalizeSharedTagName(it.name) }
            .toList()
    }
    val localTagNameSet = remember(allTagsWithCount) {
        allTagsWithCount
            .filter { it.scope == SharedTagScope.LOCAL_ONLY }
            .map { normalizeSharedTagName(it.name) }
            .toSet()
    }
    val localReservedTagNameSet = remember(localTagNameSet, localCollectionNameSet) {
        localTagNameSet + localCollectionNameSet
    }
    val availableCollectionTags = remember(customCollections, current?.collectionId, assignedLocalTagNameSet) {
        detailAvailableCollectionTags(
            customCollections = customCollections,
            currentCollectionId = current?.collectionId,
            assignedLocalTagNameSet = assignedLocalTagNameSet,
        )
    }
    val assignedLocalTagItems = remember(
        assignedLocalTags,
        currentCollection,
        locallyRemovedCollectionIds,
        locallyRemovedTagIds,
    ) {
        val currentCollectionName = currentCollection?.let { normalizeSharedTagName(it.name) }
        buildList {
            if (currentCollection != null && currentCollection.id !in locallyRemovedCollectionIds) {
                add(DetailTagItem.Collection(currentCollection))
            }
            assignedLocalTags
                .filterNot { it.id in locallyRemovedTagIds }
                .filter { normalizeSharedTagName(it.name) != currentCollectionName }
                .distinctBy { normalizeSharedTagName(it.name) }
                .forEach { add(DetailTagItem.LocalTag(it)) }
        }
    }
    val visibleAssignedSyncedTags = remember(assignedSyncedTags, locallyRemovedTagIds) {
        assignedSyncedTags.filterNot { it.id in locallyRemovedTagIds }
    }
    val availableSharedTags = remember(allTagsWithCount, assignedTagIds, localReservedTagNameSet) {
        allTagsWithCount
            .filterNot { it.id in assignedTagIds }
            .filter { tag ->
                tag.scope == SharedTagScope.SYNCED &&
                    normalizeSharedTagName(tag.name) !in localReservedTagNameSet &&
                    (tag.currentUserRole == SharedTagMemberRole.OWNER ||
                    tag.currentUserRole == SharedTagMemberRole.EDITOR
                    )
            }
    }
    val showSharedTagCloudUi = shouldShowSharedTagCloudEntryPoints(
        isConfigured = cloudState.isConfigured,
        hasSharedTags = assignedSyncedTags.isNotEmpty() || availableSharedTags.isNotEmpty(),
    )

    fun requestSaveTitle() {
        if (titleTooLong) return
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

    fun requestMetadataReload() {
        if (retryRequested && current?.metadataState == MetadataState.PENDING) return
        retryRequested = true
        scope.launch {
            val accepted = viewModel.refreshMetadata()
            if (!accepted) {
                retryRequested = false
                onMetadataRetryUnavailable()
            }
        }
    }

    fun removeDetailTag(item: DetailTagItem) {
        when (item) {
            is DetailTagItem.Collection -> {
                val collectionName = normalizeSharedTagName(item.collection.name)
                val matchingLocalTagIds = assignedLocalTags
                    .filter { normalizeSharedTagName(it.name) == collectionName }
                    .map { it.id }
                locallyRemovedCollectionIds = locallyRemovedCollectionIds + item.collection.id
                locallyRemovedTagIds = locallyRemovedTagIds + matchingLocalTagIds
                viewModel.clearCollectionAndRemoveTags(matchingLocalTagIds)
            }
            is DetailTagItem.LocalTag -> {
                locallyRemovedTagIds = locallyRemovedTagIds + item.tag.id
                locallyAssignedTagIds = locallyAssignedTagIds - item.tag.id
                viewModel.removeTag(item.tag.id)
            }
            is DetailTagItem.SharedTag -> {
                locallyRemovedTagIds = locallyRemovedTagIds + item.tag.id
                locallyAssignedTagIds = locallyAssignedTagIds - item.tag.id
                viewModel.removeTag(item.tag.id)
            }
        }
    }

    BackHandler(enabled = isEditingTitle && current != null) {
        isEditingTitle = false
        titleInput = current?.userTitle.orEmpty()
        titleTooLong = false
    }

    LaunchedEffect(entryId) {
        viewModel.effects.collect { effect ->
            onDetailEffect(effect)
        }
    }

    if (current == null) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            topBar = {
                TopAppBar(
                    title = { Text("詳細") },
                    colors = orbitTopAppBarColors(),
                    windowInsets = compactTopAppBarInsets(),
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "戻る")
                        }
                    },
                )
            },
        ) { paddingValues ->
            DetailNotFoundState(
                modifier = Modifier
                    .padding(paddingValues)
                    .fillMaxSize()
                    .padding(24.dp),
                onOpenMain = onOpenMain,
            )
        }
        return
    }

    val effectiveTitle = preferredDisplayTitle(
        userTitle = current.userTitle,
        fetchedTitle = current.fetchedTitle,
        serviceType = current.serviceType,
        normalizedHost = current.normalizedHost,
        bodySummary = current.bodySummary,
        fetchedBody = current.fetchedBody,
        description = current.description,
    )
    val delayedPending = isDelayedPendingMetadata(
        state = current.metadataState,
        metadataRequestedAt = current.metadataRequestedAt,
        nowEpochMillis = System.currentTimeMillis(),
    )
    val hasSummary = !current.bodySummary.isNullOrBlank()
    val hasBody = !current.fetchedBody.isNullOrBlank()
    val hideBodyAsDuplicateSummary = shouldHideBodyAsDuplicateSummary(
        bodySummary = current.bodySummary,
        fetchedBody = current.fetchedBody,
    )
    val showSummarySection = hasSummary
    val showBodySection = hasBody && !hideBodyAsDuplicateSummary
    val isSocialExpandableContent = current.serviceType == ServiceType.X ||
        current.serviceType == ServiceType.INSTAGRAM
    val socialContentText = preferredMetadataContentText(
        fetchedBody = current.fetchedBody,
        bodySummary = current.bodySummary,
        description = current.description,
    )
    val summarySectionLabel = metadataSummarySectionLabel(
        serviceType = current.serviceType,
        fetchedBody = current.fetchedBody,
        fetchedBodyKind = current.fetchedBodyKind,
    )
    val bodySectionLabel = metadataBodySectionLabel(
        serviceType = current.serviceType,
        fetchedBody = current.fetchedBody,
        fetchedBodyKind = current.fetchedBodyKind,
    )
    val readyWithoutFetchedContent = isReadyWithoutFetchedContent(
        state = current.metadataState,
        bodySummary = current.bodySummary,
        fetchedBody = current.fetchedBody,
    )
    val missingXBadge = current.metadataState == MetadataState.READY &&
        current.serviceType == ServiceType.X &&
        current.badgeImageUrl.isNullOrBlank()
    val metadataMessage = if (missingXBadge) {
        MetadataDetailMessage(
            title = "投稿者アイコンを更新できます",
            body = "再取得すると、取得できる場合は投稿者のプロフィール画像を表示します。",
        )
    } else if (readyWithoutFetchedContent) {
        metadataReadyWithoutContentMessage(current.serviceType)
    } else {
        metadataDetailMessage(
            state = current.metadataState,
            error = current.metadataError,
            isPendingDelayed = delayedPending,
            serviceType = current.serviceType,
        )
    }
    val detailZoneId = ZoneId.of("Asia/Tokyo")
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
                    modifier = Modifier.testTag("detail_memo_save"),
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

    if (showDeleteConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirmDialog = false },
            title = { Text("削除しますか？") },
            text = { Text("このURLは削除待ちに移動します。5秒以内なら元に戻せます。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteConfirmDialog = false
                        if (current.recordState != RecordState.ARCHIVED) {
                            viewModel.deleteToPending(onScheduleDeleteTimer)
                        }
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                ) { Text("削除する") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirmDialog = false }) { Text("キャンセル") }
            },
        )
    }

    if (showAddSharedTagSheet && showSharedTagCloudUi) {
        ModalBottomSheet(
            onDismissRequest = {
                showAddSharedTagSheet = false
                newSharedTagName = ""
                sharedTagError = null
            },
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "共有タグを選ぶ / 作る",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    TextButton(
                        onClick = {
                            showAddSharedTagSheet = false
                            newSharedTagName = ""
                            sharedTagError = null
                        },
                        modifier = Modifier.testTag("detail_shared_tags_close"),
                    ) {
                        Text("閉じる")
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    text = "既存の共有タグに追加",
                    style = MaterialTheme.typography.titleSmall,
                )
                Spacer(Modifier.height(8.dp))
                if (availableSharedTags.isEmpty()) {
                    Text(
                        text = "未割り当ての共有タグはありません。新しく作成できます。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        availableSharedTags.forEach { tag ->
                            DetailTagAssignmentOptionRow(
                                label = tag.name,
                                actionLabel = "追加",
                                actionTestTag = "detail_shared_tag_add_${tag.id}",
                                onClick = {
                                    scope.launch {
                                        when (val assigned = viewModel.assignTag(tag.id)) {
                                            AssignTagResult.Success,
                                            AssignTagResult.AlreadyAssigned,
                                            -> {
                                                locallyAssignedTagIds = locallyAssignedTagIds + tag.id
                                                showAddSharedTagSheet = false
                                                newSharedTagName = ""
                                                sharedTagError = null
                                            }
                                            is AssignTagResult.LimitReached -> {
                                                sharedTagError = assigned.message
                                            }
                                            AssignTagResult.Failed -> {
                                                sharedTagError = "共有タグを追加できませんでした"
                                            }
                                        }
                                    }
                                },
                            )
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(
                    value = newSharedTagName,
                    onValueChange = {
                        newSharedTagName = it
                        sharedTagError = null
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("detail_shared_tag_name_input"),
                    label = { Text("新しい共有タグ") },
                    placeholder = { Text("あとで共有 / 旅行 / 調査 など") },
                    supportingText = {
                        if (!sharedTagError.isNullOrBlank()) {
                            Text(sharedTagError.orEmpty())
                        }
                    },
                    isError = !sharedTagError.isNullOrBlank(),
                )
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = {
                        scope.launch {
                            val normalizedName = normalizeSharedTagName(newSharedTagName)
                            if (normalizedName in localReservedTagNameSet) {
                                sharedTagError = "同じ名前の通常タグがあります。通常タグとして追加してください"
                                return@launch
                            }
                            when (val result = viewModel.createSharedAndAssignTag(newSharedTagName)) {
                                CreateAndAssignTagResult.Success -> {
                                    showAddSharedTagSheet = false
                                    newSharedTagName = ""
                                    sharedTagError = null
                                }
                                CreateAndAssignTagResult.Blank -> {
                                    sharedTagError = "共有タグ名を入力してください"
                                }
                                CreateAndAssignTagResult.TooLong -> {
                                    sharedTagError = "共有タグ名は50文字以内で入力してください"
                                }
                                CreateAndAssignTagResult.Duplicate -> {
                                    sharedTagError = "この共有タグはすでに割り当て済みです"
                                }
                                is CreateAndAssignTagResult.LimitReached -> {
                                    sharedTagError = result.message
                                }
                                CreateAndAssignTagResult.Failed -> {
                                    sharedTagError = "共有タグを追加できませんでした"
                                }
                            }
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("detail_shared_tag_create"),
                    enabled = newSharedTagName.trim().isNotEmpty(),
                ) {
                    Text("作成して追加")
                }
                Spacer(Modifier.height(12.dp))
            }
        }
    }

    if (showAddLocalTagSheet) {
        ModalBottomSheet(
            onDismissRequest = {
                showAddLocalTagSheet = false
                newLocalTagName = ""
                localTagError = null
            },
            sheetState = localTagSheetState,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.94f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "タグを編集",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    TextButton(
                        onClick = {
                            showAddLocalTagSheet = false
                            newLocalTagName = ""
                            localTagError = null
                        },
                    ) {
                        Text("閉じる")
                    }
                }

                OrbitPanel {
                    OrbitSectionLabel("新しいタグ")
                    OutlinedTextField(
                        value = newLocalTagName,
                        onValueChange = {
                            newLocalTagName = it
                            localTagError = null
                        },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("タグ名") },
                        supportingText = {
                            if (!localTagError.isNullOrBlank()) {
                                Text(localTagError.orEmpty())
                            }
                        },
                        isError = !localTagError.isNullOrBlank(),
                        singleLine = true,
                    )
                    Button(
                        onClick = {
                            scope.launch {
                                when (val result = viewModel.createLocalAndAssignTag(newLocalTagName)) {
                                    CreateAndAssignTagResult.Success -> {
                                        newLocalTagName = ""
                                        localTagError = null
                                    }
                                    CreateAndAssignTagResult.Blank -> {
                                        localTagError = "タグ名を入力してください"
                                    }
                                    CreateAndAssignTagResult.TooLong -> {
                                        localTagError = "タグ名は50文字以内で入力してください"
                                    }
                                    CreateAndAssignTagResult.Duplicate -> {
                                        localTagError = "このタグはすでに割り当て済みです"
                                    }
                                    is CreateAndAssignTagResult.LimitReached -> {
                                        localTagError = result.message
                                    }
                                    CreateAndAssignTagResult.Failed -> {
                                        localTagError = "タグを追加できませんでした"
                                    }
                                }
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp),
                        enabled = newLocalTagName.trim().isNotEmpty(),
                    ) {
                        Text("タグを作成して追加")
                    }
                }

                if (assignedLocalTagItems.isNotEmpty()) {
                    OrbitPanel {
                        OrbitSectionLabel("現在のタグ")
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            assignedLocalTagItems.forEach { item ->
                                DetailTagAssignmentOptionRow(
                                    label = item.name,
                                    actionLabel = "外す",
                                    enabled = item.canRemove,
                                    actionTestTag = when (item) {
                                        is DetailTagItem.Collection -> "detail_local_tag_remove_collection_${item.collection.id}"
                                        is DetailTagItem.LocalTag -> "detail_local_tag_remove_tag_${item.tag.id}"
                                        is DetailTagItem.SharedTag -> null
                                    },
                                    onClick = {
                                        removeDetailTag(item)
                                    },
                                )
                            }
                        }
                    }
                }

                OrbitPanel {
                    OrbitSectionLabel("追加できるタグ")
                    if (availableLocalTags.isEmpty() && availableCollectionTags.isEmpty()) {
                        Text(
                            text = "追加できるタグはありません",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            availableCollectionTags.forEach { collection ->
                                DetailTagAssignmentOptionRow(
                                    label = collection.name,
                                    actionLabel = "追加",
                                    actionTestTag = "detail_local_tag_add_collection_${collection.id}",
                                    onClick = {
                                        scope.launch {
                                            when (val result = viewModel.assignCollectionAndCreateLocalTag(collection)) {
                                                CreateAndAssignTagResult.Success -> {
                                                    newLocalTagName = ""
                                                    localTagError = null
                                                }
                                                CreateAndAssignTagResult.Blank -> {
                                                    localTagError = "タグ名を入力してください"
                                                }
                                                CreateAndAssignTagResult.TooLong -> {
                                                    localTagError = "タグ名は50文字以内で入力してください"
                                                }
                                                CreateAndAssignTagResult.Duplicate -> {
                                                    localTagError = "このタグはすでに割り当て済みです"
                                                }
                                                is CreateAndAssignTagResult.LimitReached -> {
                                                    localTagError = result.message
                                                }
                                                CreateAndAssignTagResult.Failed -> {
                                                    localTagError = "タグを追加できませんでした"
                                                }
                                            }
                                        }
                                    },
                                )
                            }
                            availableLocalTags.forEach { tag ->
                                DetailTagAssignmentOptionRow(
                                    label = tag.name,
                                    actionLabel = "追加",
                                    actionTestTag = "detail_local_tag_add_tag_${tag.id}",
                                    onClick = {
                                        scope.launch {
                                            when (val assigned = viewModel.assignTag(tag.id)) {
                                                AssignTagResult.Success,
                                                AssignTagResult.AlreadyAssigned,
                                                -> {
                                                    locallyAssignedTagIds = locallyAssignedTagIds + tag.id
                                                    newLocalTagName = ""
                                                    localTagError = null
                                                }
                                                is AssignTagResult.LimitReached -> {
                                                    localTagError = assigned.message
                                                }
                                                AssignTagResult.Failed -> {
                                                    localTagError = "タグを追加できませんでした"
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

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("詳細") },
                colors = orbitTopAppBarColors(),
                windowInsets = compactTopAppBarInsets(),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "戻る")
                    }
                },
                actions = {
                    val isReloading = retryRequested && current.metadataState == MetadataState.PENDING
                    IconButton(
                        enabled = !isReloading,
                        onClick = { requestMetadataReload() },
                    ) {
                        if (isReloading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp,
                            )
                        } else {
                            Icon(Icons.Outlined.Refresh, contentDescription = "読み込み")
                        }
                    }
                },
            )
        },
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .padding(paddingValues)
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .widthIn(max = OrbitTokens.contentMaxWidth)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = OrbitTokens.screenHorizontalPadding, vertical = 20.dp),
                verticalArrangement = Arrangement.spacedBy(OrbitTokens.sectionSpacing),
            ) {
                if (!current.thumbnailUrl.isNullOrBlank()) {
                    OrbitPanel(
                        tone = OrbitPanelTone.STRONG,
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
                    ) {
                        AsyncImage(
                            model = current.thumbnailUrl,
                            contentDescription = "サムネイル",
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(220.dp),
                        )
                    }
                }

                OrbitPanel(tone = OrbitPanelTone.STRONG) {
                    Row(
                        verticalAlignment = Alignment.Top,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
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
                                    textStyle = MaterialTheme.typography.titleLarge,
                                    maxLines = 2,
                                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                                    keyboardActions = KeyboardActions(onDone = { requestSaveTitle() }),
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
                                    style = MaterialTheme.typography.titleLarge,
                                    maxLines = 3,
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

                    if (isEditingTitle) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            OrbitActionButton(
                                onClick = {
                                    isEditingTitle = false
                                    titleInput = current.userTitle.orEmpty()
                                    titleTooLong = false
                                },
                                modifier = Modifier.weight(1f),
                            ) {
                                Icon(Icons.Outlined.Close, contentDescription = null)
                                Spacer(Modifier.width(6.dp))
                                OrbitActionText("キャンセル")
                            }
                            OrbitActionButton(
                                onClick = { requestSaveTitle() },
                                enabled = !titleTooLong,
                                style = OrbitActionStyle.PRIMARY,
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("detail_title_save"),
                            ) {
                                Icon(Icons.Outlined.Check, contentDescription = null)
                                Spacer(Modifier.width(6.dp))
                                OrbitActionText("保存")
                            }
                        }
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ServiceBadge(
                            serviceType = serviceTypeForUi(current.serviceType),
                            badgeImageUrl = current.badgeImageUrl,
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            text = detailServiceLabelForEntry(current),
                            style = MaterialTheme.typography.bodyLarge,
                            color = OrbitTokens.textMutedStrong,
                        )
                    }
                }

                val shouldShowFetchedContent = if (isSocialExpandableContent) {
                    !socialContentText.isNullOrBlank()
                } else {
                    showSummarySection || showBodySection
                }
                if (shouldShowFetchedContent) {
                    if (isSocialExpandableContent) {
                        ExpandableMetadataSection(
                            label = bodySectionLabel,
                            text = socialContentText.orEmpty(),
                        )
                    } else {
                        OrbitPanel {
                            if (showSummarySection) {
                                OrbitSectionLabel(summarySectionLabel)
                                Text(
                                    text = current.bodySummary.orEmpty(),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (showSummarySection && showBodySection) {
                                Spacer(Modifier.height(8.dp))
                            }
                            if (showBodySection) {
                                OrbitSectionLabel(bodySectionLabel)
                                Text(
                                    text = current.fetchedBody.orEmpty(),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }

                if (metadataMessage != null) {
                    val showRetry = current.metadataState == MetadataState.FAILED ||
                        current.metadataState == MetadataState.UNAVAILABLE ||
                        readyWithoutFetchedContent ||
                        missingXBadge ||
                        delayedPending ||
                        retryRequested
                    val retrying = retryRequested && current.metadataState == MetadataState.PENDING
                    OrbitPanel(tone = OrbitPanelTone.SOFT) {
                        Text(
                            text = metadataMessage.title,
                            style = MaterialTheme.typography.titleSmall,
                        )
                        if (!metadataMessage.body.isNullOrBlank()) {
                            Text(
                                text = metadataMessage.body,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (showRetry) {
                            OrbitActionButton(
                                onClick = {
                                    if (!retrying) requestMetadataReload()
                                },
                                enabled = !retrying,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                if (retrying) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(16.dp),
                                        strokeWidth = 2.dp,
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    OrbitActionText("再取得中…")
                                } else {
                                    OrbitActionText("再取得")
                                }
                            }
                        }
                    }
                }

                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        OrbitActionButton(
                            onClick = {
                                when (context.tryOpenExternalUrl(current.openUrl)) {
                                    OpenUrlResult.Success -> Unit
                                    OpenUrlResult.NoHandler,
                                    OpenUrlResult.Failed,
                                    -> onOpenFailed()
                                }
                            },
                            style = OrbitActionStyle.PRIMARY,
                            modifier = Modifier.weight(1f),
                        ) {
                            OrbitActionText("開く")
                        }

                        OrbitActionButton(
                            onClick = {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                clipboard.setPrimaryClip(ClipData.newPlainText("url", current.openUrl))
                                onCopySuccess()
                            },
                            modifier = Modifier.weight(1f),
                        ) {
                            OrbitActionText("コピー")
                        }
                    }

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        OrbitActionButton(
                            onClick = {
                                if (current.recordState == RecordState.ARCHIVED) {
                                    viewModel.unarchive()
                                } else {
                                    viewModel.archive()
                                    AdsManager.registerMeaningfulActionAndMaybeShow(context)
                                }
                            },
                            modifier = Modifier.weight(1f),
                        ) {
                            OrbitActionText(if (current.recordState == RecordState.ARCHIVED) "アーカイブ解除" else "アーカイブ")
                        }

                        OrbitActionButton(
                            onClick = { showDeleteConfirmDialog = true },
                            enabled = current.recordState != RecordState.ARCHIVED,
                            style = OrbitActionStyle.DANGER,
                            modifier = Modifier.weight(1f),
                        ) {
                            OrbitActionText("削除", emphasisColor = OrbitTokens.danger)
                        }
                    }

                    OrbitActionButton(
                        onClick = {
                            memoInput = current.memo
                            memoTooLong = false
                            showMemoDialog = true
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        OrbitActionText("メモを編集")
                    }
                }

                OrbitPanel(tone = OrbitPanelTone.SOFT) {
                    OrbitSectionLabel("メモ")
                    Text(
                        text = if (current.memo.isBlank()) stringResource(R.string.detail_memo_empty) else current.memo,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                memoInput = current.memo
                                memoTooLong = false
                                showMemoDialog = true
                            },
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (current.memo.isBlank()) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    DetailTagSummaryPanel(
                        title = "タグ",
                        emptyText = "まだタグは付いていません",
                        tags = assignedLocalTagItems,
                        onEdit = { showAddLocalTagSheet = true },
                        editButtonTestTag = "detail_local_tags_edit",
                        onRemove = ::removeDetailTag,
                        modifier = Modifier.weight(1f),
                    )
                    if (showSharedTagCloudUi) {
                        DetailTagSummaryPanel(
                            title = "共有タグ",
                            emptyText = "まだ共有タグは付いていません",
                            tags = visibleAssignedSyncedTags.map(DetailTagItem::SharedTag),
                            onEdit = { showAddSharedTagSheet = true },
                            editButtonTestTag = "detail_shared_tags_edit",
                            onRemove = ::removeDetailTag,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }

                OrbitPanel(
                    tone = OrbitPanelTone.SOFT,
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("detail_section_toggle")
                            .clickable { detailsExpanded = !detailsExpanded }
                            .padding(horizontal = 20.dp, vertical = 18.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OrbitSectionLabel(
                            text = "詳細情報",
                            modifier = Modifier.weight(1f),
                        )
                        Icon(
                            imageVector = if (detailsExpanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    if (detailsExpanded) {
                        Column(modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 20.dp)) {
                            DetailValue(label = "受信したURL", value = current.originalUrl)
                            DetailValue(
                                label = "保存時刻",
                                value = formatDetailDateTime(current.createdAt, detailZoneId),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DetailNotFoundState(
    modifier: Modifier = Modifier,
    onOpenMain: () -> Unit,
) {
    Box(
        modifier = modifier.testTag("detail_not_found"),
        contentAlignment = Alignment.Center,
    ) {
        OrbitPanel(
            modifier = Modifier.widthIn(max = 420.dp),
            tone = OrbitPanelTone.SOFT,
        ) {
            Icon(
                imageVector = Icons.Outlined.LinkOff,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
            Text(
                text = stringResource(R.string.detail_not_found_title),
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                text = stringResource(R.string.detail_not_found_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            OrbitActionButton(
                onClick = onOpenMain,
                style = OrbitActionStyle.PRIMARY,
                modifier = Modifier.fillMaxWidth(),
            ) {
                OrbitActionText(stringResource(R.string.detail_back_to_list))
            }
        }
    }
}

@Composable
private fun DetailValue(label: String, value: String) {
    Column(modifier = Modifier.padding(vertical = 6.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

private fun detailServiceLabelForEntry(entry: UrlEntryEntity): String {
    if (
        entry.serviceType == ServiceType.YOUTUBE ||
        entry.serviceType == ServiceType.X ||
        entry.serviceType == ServiceType.INSTAGRAM ||
        entry.serviceType == ServiceType.TIKTOK
    ) {
        entry.fetchedAuthorName?.trim()?.takeIf { it.isNotBlank() }?.let { return it }
        if (entry.serviceType != ServiceType.YOUTUBE) {
            entry.fetchedTitle?.trim()?.takeIf { it.isNotBlank() }?.let { return it }
        }
    }
    return serviceLabelForList(entry.serviceType, entry.normalizedHost)
}

internal fun detailAssignedTagIds(
    assignedTags: List<SharedTagRecord>,
    locallyAssignedTagIds: Set<Long>,
): Set<Long> {
    return assignedTags.mapTo(mutableSetOf()) { it.id } + locallyAssignedTagIds
}

internal fun detailAvailableCollectionTags(
    customCollections: List<CollectionEntity>,
    currentCollectionId: Long?,
    assignedLocalTagNameSet: Set<String>,
): List<CollectionEntity> {
    return customCollections.filter { collection ->
        collection.id != currentCollectionId &&
            normalizeSharedTagName(collection.name) !in assignedLocalTagNameSet
    }
}

@Composable
private fun DetailTagSummaryPanel(
    title: String,
    emptyText: String,
    tags: List<DetailTagItem>,
    onEdit: () -> Unit,
    onRemove: (DetailTagItem) -> Unit,
    editButtonTestTag: String? = null,
    modifier: Modifier = Modifier,
) {
    OrbitPanel(
        modifier = modifier.height(194.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxHeight(),
            verticalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            OrbitSectionLabel(
                text = title,
                modifier = Modifier.fillMaxWidth(),
            )
            DetailTagEditButton(
                onClick = onEdit,
                modifier = if (editButtonTestTag == null) {
                    Modifier
                } else {
                    Modifier.testTag(editButtonTestTag)
                },
            )

            if (tags.isEmpty()) {
                DetailTagValuePill(
                    text = emptyText,
                    isEmpty = true,
                    onRemove = null,
                )
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(91.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    tags.forEach { tag ->
                        DetailTagValuePill(
                            text = tag.name,
                            isEmpty = false,
                            onRemove = if (tag.canRemove) {
                                { onRemove(tag) }
                            } else {
                                null
                            },
                        )
                    }
                }
            }
        }
    }
}

private sealed interface DetailTagItem {
    val name: String
    val canRemove: Boolean

    data class Collection(val collection: CollectionEntity) : DetailTagItem {
        override val name: String = collection.name
        override val canRemove: Boolean = true
    }

    data class LocalTag(val tag: SharedTagRecord) : DetailTagItem {
        override val name: String = tag.name
        override val canRemove: Boolean = true
    }

    data class SharedTag(val tag: SharedTagRecord) : DetailTagItem {
        override val name: String = tag.name
        override val canRemove: Boolean = true
    }
}

@Composable
private fun DetailTagAssignmentOptionRow(
    label: String,
    actionLabel: String,
    enabled: Boolean = true,
    actionTestTag: String? = null,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp)
    Row(
        modifier = modifier
            .background(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = shape,
            )
            .border(
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                shape = shape,
            )
            .padding(start = 16.dp, top = 10.dp, end = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Button(
            onClick = onClick,
            enabled = enabled,
            modifier = (if (actionTestTag == null) {
                Modifier
            } else {
                Modifier.testTag(actionTestTag)
            }).height(40.dp),
            shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp, vertical = 0.dp),
        ) {
            Text(
                text = actionLabel,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun DetailTagEditButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Button(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .height(42.dp),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = OrbitTokens.panelStrong,
            contentColor = OrbitTokens.textPrimary,
        ),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 0.dp),
    ) {
        Text(
            text = "編集",
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun DetailTagValuePill(
    text: String,
    isEmpty: Boolean,
    onRemove: (() -> Unit)?,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(42.dp)
            .background(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
            )
            .padding(start = 8.dp, end = if (onRemove == null) 8.dp else 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Text(
            text = text,
            modifier = Modifier.weight(1f),
            style = if (isEmpty) MaterialTheme.typography.labelSmall else MaterialTheme.typography.labelMedium,
            color = if (isEmpty) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (onRemove != null) {
            IconButton(
                onClick = onRemove,
                modifier = Modifier.size(36.dp),
            ) {
                Icon(
                    imageVector = Icons.Outlined.Close,
                    contentDescription = "$text を外す",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ExpandableMetadataSection(
    label: String,
    text: String,
    previewLineLimit: Int = 4,
) {
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val density = LocalDensity.current
        val textMeasurer = rememberTextMeasurer()
        val bodyStyle = MaterialTheme.typography.bodyMedium
        val maxWidthPx = with(density) { maxWidth.roundToPx() }
        val measurement = remember(text, maxWidthPx, bodyStyle) {
            textMeasurer.measure(
                text = AnnotatedString(text),
                style = bodyStyle,
                constraints = Constraints(maxWidth = maxWidthPx),
            )
        }
        val fitsWithinPreview = measurement.lineCount <= previewLineLimit
        var expandedOverride by rememberSaveable(text) { mutableStateOf<Boolean?>(null) }
        val expanded = expandedOverride ?: fitsWithinPreview

        OrbitPanel(tone = OrbitPanelTone.SOFT, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expandedOverride = !expanded }
                    .padding(horizontal = 20.dp, vertical = 18.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OrbitSectionLabel(
                    text = label,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    imageVector = if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                    contentDescription = if (expanded) "${label}を閉じる" else "${label}を表示",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (expanded) {
                Text(
                    text = text,
                    style = bodyStyle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 20.dp),
                )
            }
        }
    }
}

@Composable
private fun EmptyState(
    title: String,
    body: String? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 4.dp, vertical = 24.dp),
        contentAlignment = Alignment.Center,
    ) {
        OrbitPanel(
            modifier = Modifier.widthIn(max = 420.dp),
            tone = OrbitPanelTone.SOFT,
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
                maxLines = 1,
                modifier = Modifier.fillMaxWidth(),
            )
            if (!body.isNullOrBlank()) {
                Text(
                    text = body,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (!actionLabel.isNullOrBlank() && onAction != null) {
                OrbitActionButton(
                    onClick = onAction,
                    style = OrbitActionStyle.PRIMARY,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    OrbitActionText(actionLabel)
                }
            }
        }
    }
}

@Composable
private fun MainAddUrlBar(onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = OrbitTokens.screenHorizontalPadding, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        BannerAdSlot(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 10.dp),
        )
        OrbitActionButton(
            onClick = onClick,
            modifier = Modifier
                .fillMaxWidth()
                .semantics { contentDescription = "手動追加" },
            style = OrbitActionStyle.PRIMARY,
        ) {
            Icon(Icons.Outlined.Add, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            OrbitActionText(stringResource(R.string.main_add_url))
        }
    }
}
