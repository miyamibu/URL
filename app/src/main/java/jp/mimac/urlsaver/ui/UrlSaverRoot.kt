package jp.mimac.urlsaver.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Matrix
import android.graphics.BitmapFactory
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.view.Surface
import android.view.TextureView
import jp.mimac.urlsaver.BuildConfig
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ViewList
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ChecklistRtl
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.IosShare
import androidx.compose.material.icons.outlined.LinkOff
import androidx.compose.material.icons.outlined.Group
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Sell
import androidx.compose.material.icons.outlined.ViewAgenda
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScaffoldDefaults
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
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
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
import jp.mimac.urlsaver.data.LocalTagEntryRef
import jp.mimac.urlsaver.data.UrlEntryEntity
import jp.mimac.urlsaver.data.VideoDownloadEntity
import jp.mimac.urlsaver.domain.DetailEffect
import jp.mimac.urlsaver.domain.EntryCardDisplayMode
import jp.mimac.urlsaver.domain.MainNavigationEvent
import jp.mimac.urlsaver.domain.MetadataState
import jp.mimac.urlsaver.domain.RecordState
import jp.mimac.urlsaver.domain.AssignTagResult
import jp.mimac.urlsaver.domain.CreateTagResult
import jp.mimac.urlsaver.domain.CreateSharedTagGroupResult
import jp.mimac.urlsaver.domain.SharedTagNameValidationError
import jp.mimac.urlsaver.domain.SharedTagMemberRole
import jp.mimac.urlsaver.domain.SharedTagGroupInviteCreationResult
import jp.mimac.urlsaver.domain.SharedTagGroupMemberRecord
import jp.mimac.urlsaver.domain.SharedTagGroupMutationResult
import jp.mimac.urlsaver.domain.SharedTagGroupRecord
import jp.mimac.urlsaver.domain.SharedTagGroupTagRecord
import jp.mimac.urlsaver.domain.SharedTagRecord
import jp.mimac.urlsaver.domain.SharedTagScope
import jp.mimac.urlsaver.domain.SharedTagSyncStatus
import jp.mimac.urlsaver.domain.ServiceType
import jp.mimac.urlsaver.domain.ShareSaveResult
import jp.mimac.urlsaver.domain.SnackbarEvent
import jp.mimac.urlsaver.domain.SnackbarEventKind
import jp.mimac.urlsaver.domain.TagWithCount
import jp.mimac.urlsaver.domain.UrlRules
import jp.mimac.urlsaver.video.AppMediaStore
import java.io.File
import jp.mimac.urlsaver.domain.normalizeSharedTagName
import jp.mimac.urlsaver.domain.validateSharedTagName
import jp.mimac.urlsaver.ui.components.OrbitActionButton
import jp.mimac.urlsaver.ui.components.OrbitActionStyle
import jp.mimac.urlsaver.ui.components.OrbitActionText
import jp.mimac.urlsaver.ui.components.OrbitFilterChip
import jp.mimac.urlsaver.ui.components.OrbitPanel
import jp.mimac.urlsaver.ui.components.OrbitPanelTone
import jp.mimac.urlsaver.ui.components.OrbitSectionLabel
import jp.mimac.urlsaver.ui.components.EntryCard
import jp.mimac.urlsaver.ui.components.ServiceBadge
import jp.mimac.urlsaver.ui.components.ServiceFilterRow
import jp.mimac.urlsaver.ui.components.TagFilterRow
import jp.mimac.urlsaver.ui.ads.BannerAdSlot
import jp.mimac.urlsaver.ui.theme.OrbitTokens
import jp.mimac.urlsaver.ui.theme.AppThemeMode
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.min

@Suppress("UNUSED_PARAMETER")
fun shouldShowSharedTagCloudEntryPoints(
    isConfigured: Boolean,
    hasSharedTags: Boolean,
    hasPendingInvite: Boolean = false,
): Boolean = true

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
                is MainNavigationEvent.NavigateToPromoCode -> {
                    navController.navigate(Routes.cloudAuth(event.code)) {
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
                snackbarHostState = snackbarHostState,
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
    snackbarHostState: SnackbarHostState,
    themeMode: AppThemeMode,
    onThemeModeChange: (AppThemeMode) -> Unit,
    resolvedRoute: String?,
) {
    composable(Routes.MAIN) {
        val vm: MainListViewModel = viewModel(
            factory = SimpleFactory {
                MainListViewModel(
                    repository = context.appContainer().repository,
                    tagRepository = context.appContainer().tagRepository,
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
            snackbarHostState = snackbarHostState,
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
                    tagRepository = context.appContainer().tagRepository,
                )
            },
        )
        ArchiveScreen(
            viewModel = vm,
            onBack = { navController.popBackStack() },
            onOpenDetail = { navController.navigate(Routes.detail(it)) },
            onRestoreEntry = { entryId ->
                activityViewModel.onDetailEffect(DetailEffect.NavigateBackAfterRestore(entryId))
            },
            onPendingDeleteEntry = { entryId, pendingUntil ->
                activityViewModel.onBatchPendingDelete(mapOf(entryId to pendingUntil))
            },
            onDeleteFailed = { activityViewModel.notifyDeleteFailed() },
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
                    videoRepository = context.appContainer().videoRepository,
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

    composable(
        route = Routes.CLOUD_AUTH_PATTERN,
        arguments = listOf(
            navArgument("promoCode") {
                type = NavType.StringType
                nullable = true
                defaultValue = null
            },
        ),
    ) { cloudBackStack ->
        val initialPromoCode = cloudBackStack.arguments?.getString("promoCode")
        val vm: SharedTagAuthViewModel = viewModel(
            factory = SimpleFactory {
                SharedTagAuthViewModel(
                    tagRepository = context.appContainer().tagRepository,
                    userProfileStore = context.appContainer().userProfileStore,
                    entitlementGrantRepository = context.appContainer().entitlementGrantRepository,
                    googlePlayBillingService = context.appContainer().googlePlayBillingService,
                    chatGptPersonalLinkSyncRepository = context.appContainer().chatGptPersonalLinkSyncRepository,
                    contactSupportClient = context.appContainer().contactSupportClient,
                    authSessionProvider = context.appContainer().sharedTagAuthSessionProvider,
                    pendingInviteStore = context.appContainer().pendingInviteStore,
                    localAccountCleanupStore = context.appContainer().localAccountCleanupStore,
                )
            },
        )
        SharedTagCloudAuthScreen(
            viewModel = vm,
            themeMode = themeMode,
            onThemeModeChange = onThemeModeChange,
            onBack = { navController.popBackStack() },
            initialPromoCode = initialPromoCode,
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
                    pendingInviteStore = context.appContainer().pendingInviteStore,
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
    snackbarHostState: SnackbarHostState,
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
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val localTagEntryRefs by viewModel.localTagEntryRefs.collectAsStateWithLifecycle()
    val sharedTags by tagViewModel.tags.collectAsStateWithLifecycle()
    val sharedTagGroups by tagViewModel.groups.collectAsStateWithLifecycle()
    val selectedService by viewModel.selectedServiceFlow.collectAsStateWithLifecycle()
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
                    entitlementGrantRepository = context.appContainer().entitlementGrantRepository,
                    googlePlayBillingService = context.appContainer().googlePlayBillingService,
                    chatGptPersonalLinkSyncRepository = context.appContainer().chatGptPersonalLinkSyncRepository,
                    contactSupportClient = context.appContainer().contactSupportClient,
                    authSessionProvider = context.appContainer().sharedTagAuthSessionProvider,
                    pendingInviteStore = context.appContainer().pendingInviteStore,
                    localAccountCleanupStore = context.appContainer().localAccountCleanupStore,
                )
            },
        )
    val profileCloudState by profileVm.cloudState.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val mainListState = rememberLazyListState()
    val visibleSharedTags = remember(sharedTags) {
        sharedTags.filter { tag ->
            tag.scope == SharedTagScope.SYNCED
        }
    }
    val localSaveTags = remember(sharedTags) {
        sharedTags
            .filter { tag ->
                tag.scope == SharedTagScope.LOCAL_ONLY
            }
            .sortedByDescending { tag -> tag.id }
            .distinctBy { normalizeSharedTagName(it.name) }
    }
    val localTagNamesByEntryId = remember(localSaveTags, localTagEntryRefs) {
        val namesById = localSaveTags.associate { it.id to it.name }
        localTagEntryRefs
            .groupBy(
                keySelector = { it.entryId },
                valueTransform = { namesById[it.tagId] },
            )
            .mapValues { (_, names) ->
                names.filterNotNull().distinctBy { it.lowercase() }.sorted()
            }
    }
    val showSharedTagCloudUi = true

    LaunchedEffect(Unit) {
        profileVm.refreshPendingInvite()
    }
    var selectionModeActive by rememberSaveable { mutableStateOf(false) }
    var batchLocalTagSheetVisible by rememberSaveable { mutableStateOf(false) }
    var selectedMainLocalTagId by rememberSaveable { mutableStateOf<Long?>(null) }
    var searchBarVisible by rememberSaveable { mutableStateOf(false) }
    var searchQueryLocal by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(localSaveTags, selectedMainLocalTagId) {
        val selectedTagId = selectedMainLocalTagId ?: return@LaunchedEffect
        if (localSaveTags.none { it.id == selectedTagId }) {
            selectedMainLocalTagId = null
        }
    }
    val localTagFilteredEntries = remember(uiState.entries, localTagEntryRefs, selectedMainLocalTagId) {
        val selectedTagId = selectedMainLocalTagId
        if (selectedTagId == null) {
            uiState.entries
        } else {
            val matchingEntryIds = localTagEntryRefs
                .filter { it.tagId == selectedTagId }
                .mapTo(mutableSetOf()) { it.entryId }
            uiState.entries.filter { it.id in matchingEntryIds }
        }
    }
    val searchFilteredEntries = remember(localTagFilteredEntries, searchQueryLocal, sharedTags, localTagEntryRefs) {
        filterEntriesBySearch(
            entries = localTagFilteredEntries,
            query = searchQueryLocal,
            tags = sharedTags,
            localTagEntryRefs = localTagEntryRefs,
        )
    }
    val displayedUiState = remember(uiState, searchQueryLocal, selectedMainLocalTagId, searchFilteredEntries) {
        if (searchQueryLocal.isBlank() && selectedMainLocalTagId == null) {
            uiState
        } else {
            uiState.copy(entries = searchFilteredEntries, scopeCount = searchFilteredEntries.size)
        }
    }

    var manualInputState by remember { mutableStateOf(ManualInputUiState()) }
    var showPrivacyDialog by remember { mutableStateOf(false) }
    var showUsageGuide by rememberSaveable { mutableStateOf(false) }
    var sharedTagDialogState by remember { mutableStateOf(SharedTagDialogUiState()) }
    var sharedTagGroupDialogState by remember { mutableStateOf(SharedTagDialogUiState()) }
    var manualLocalTagDialogState by remember { mutableStateOf(SharedTagDialogUiState()) }
    var createLocalTagForMainFilter by remember { mutableStateOf(false) }
    var renameLocalTagDialogState by remember { mutableStateOf(SharedTagDialogUiState()) }
    var pendingRenameLocalTagId by remember { mutableStateOf<Long?>(null) }
    var groupRenameDialogState by remember { mutableStateOf(SharedTagDialogUiState()) }
    var pendingGroupAction by remember { mutableStateOf<GroupActionConfirmation?>(null) }
    var mainPane by rememberSaveable { mutableStateOf(MainPane.URLS) }
    var selectedSharedTagGroupId by rememberSaveable { mutableStateOf<Long?>(null) }
    var showExportSheet by rememberSaveable { mutableStateOf(false) }
    val exportSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var showChatGptSheet by rememberSaveable { mutableStateOf(false) }
    val chatGptSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var showProfileSheet by rememberSaveable { mutableStateOf(false) }
    val profileSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var showMainMenu by rememberSaveable { mutableStateOf(false) }
    var showLocalTagManagerSheet by rememberSaveable { mutableStateOf(false) }
    var pendingDeleteLocalTag by remember { mutableStateOf<TagWithCount?>(null) }
    var selectedEntryIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var previousTopEntryId by remember { mutableStateOf<Long?>(null) }
    var previousVisibleEntryCount by remember { mutableStateOf(0) }
    val selectedSharedTagGroup = sharedTagGroups.firstOrNull { it.id == selectedSharedTagGroupId }
    BackHandler(enabled = !selectionModeActive && searchBarVisible) {
        searchQueryLocal = ""
        searchBarVisible = false
    }
    BackHandler(enabled = showUsageGuide) {
        showUsageGuide = false
    }
    BackHandler(enabled = selectionModeActive) {
        selectedEntryIds = emptySet()
        selectionModeActive = false
    }
    LaunchedEffect(sharedTagGroups, selectedSharedTagGroupId) {
        if (selectedSharedTagGroupId != null && selectedSharedTagGroup == null) {
            selectedSharedTagGroupId = null
        }
    }
    val selectedGroupMembers by remember(selectedSharedTagGroupId) {
        val groupId = selectedSharedTagGroupId
        if (groupId == null) {
            flowOf(emptyList())
        } else {
            tagViewModel.observeGroupMembers(groupId)
        }
    }.collectAsStateWithLifecycle(initialValue = emptyList())
    val selectedGroupTags by remember(selectedSharedTagGroupId) {
        val groupId = selectedSharedTagGroupId
        if (groupId == null) {
            flowOf(emptyList())
        } else {
            tagViewModel.observeGroupTags(groupId)
        }
    }.collectAsStateWithLifecycle(initialValue = emptyList())

    LaunchedEffect(uiState.entries) {
        val visibleIds = uiState.entries.map { it.id }.toSet()
        selectedEntryIds = selectedEntryIds.intersect(visibleIds)
        if (selectedEntryIds.isEmpty()) {
            selectionModeActive = false
        }
    }

    LaunchedEffect(uiState.entries, selectedService) {
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
        manualInputState = ManualInputUiState(visible = true)
    }

    fun closeCreateManualLocalTagDialog() {
        manualLocalTagDialogState = SharedTagDialogUiState()
        createLocalTagForMainFilter = false
    }

    fun closeRenameLocalTagDialog() {
        renameLocalTagDialogState = SharedTagDialogUiState()
        pendingRenameLocalTagId = null
    }

    fun closeCreateSharedTagDialog() {
        sharedTagDialogState = SharedTagDialogUiState()
    }

    fun closeCreateSharedTagGroupDialog() {
        sharedTagGroupDialogState = SharedTagDialogUiState()
    }

    fun submitManualSave() {
        scope.launch {
            manualInputState = manualInputState.copy(isSaving = true)
            try {
                val submitResult = viewModel.submitManualInput(
                    input = manualInputState.inputText,
                    localTagIds = manualInputState.selectedLocalTagIds,
                )
                val result = submitResult.saveResult
                val entryId = submitResult.entryId
                when (result) {
                    ShareSaveResult.INPUT_TOO_LARGE,
                    ShareSaveResult.INVALID_URL,
                    ShareSaveResult.NO_URL_FOUND,
                    ShareSaveResult.PERSONAL_URL_LIMIT_REACHED,
                    -> manualInputState = manualInputState.copy(inputError = result)
                    else -> {
                        onManualResult(result, entryId)
                        if (submitResult.failedTagAssignmentCount > 0) {
                            snackbarHostState.showSnackbar(
                                "一部のタグを追加できませんでした",
                                duration = SnackbarDuration.Short,
                            )
                        }
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

    fun confirmCreateManualLocalTag() {
        scope.launch {
            when (val created = viewModel.createLocalTag(manualLocalTagDialogState.name)) {
                is CreateTagResult.Success -> {
                    if (createLocalTagForMainFilter) {
                        selectedMainLocalTagId = created.tagId
                        viewModel.selectService(ServiceType.ALL)
                    } else {
                        manualInputState = manualInputState.copy(
                            selectedLocalTagIds = manualInputState.selectedLocalTagIds + created.tagId,
                            localTagError = null,
                        )
                    }
                    closeCreateManualLocalTagDialog()
                }
                CreateTagResult.InvalidName -> {
                    manualLocalTagDialogState = manualLocalTagDialogState.copy(error = "タグ名を入力してください")
                }
                CreateTagResult.Duplicate -> {
                    val duplicateId = localSaveTags.firstOrNull {
                        normalizeSharedTagName(it.name) == normalizeSharedTagName(manualLocalTagDialogState.name)
                    }?.id
                    if (duplicateId != null) {
                        if (createLocalTagForMainFilter) {
                            selectedMainLocalTagId = duplicateId
                            viewModel.selectService(ServiceType.ALL)
                        } else {
                            manualInputState = manualInputState.copy(
                                selectedLocalTagIds = manualInputState.selectedLocalTagIds + duplicateId,
                                localTagError = null,
                            )
                        }
                        closeCreateManualLocalTagDialog()
                    } else {
                        manualLocalTagDialogState = manualLocalTagDialogState.copy(error = "同じ名前のタグがあります")
                    }
                }
                is CreateTagResult.LimitReached -> {
                    manualLocalTagDialogState = manualLocalTagDialogState.copy(error = created.message)
                }
                CreateTagResult.Failed -> {
                    manualLocalTagDialogState = manualLocalTagDialogState.copy(error = "タグを作成できませんでした")
                }
            }
        }
    }

    fun confirmRenameLocalTag() {
        val tagId = pendingRenameLocalTagId ?: return
        scope.launch {
            when (val renamed = viewModel.renameLocalTag(tagId, renameLocalTagDialogState.name)) {
                is CreateTagResult.Success -> closeRenameLocalTagDialog()
                CreateTagResult.InvalidName -> {
                    renameLocalTagDialogState = renameLocalTagDialogState.copy(error = "タグ名を入力してください")
                }
                CreateTagResult.Duplicate -> {
                    renameLocalTagDialogState = renameLocalTagDialogState.copy(error = "同じ名前のタグがあります")
                }
                is CreateTagResult.LimitReached -> {
                    renameLocalTagDialogState = renameLocalTagDialogState.copy(error = renamed.message)
                }
                CreateTagResult.Failed -> {
                    renameLocalTagDialogState = renameLocalTagDialogState.copy(error = "タグ名を変更できませんでした")
                }
            }
        }
    }

    fun confirmCreateSharedTag() {
        scope.launch {
            val error = when (validateSharedTagName(sharedTagDialogState.name)) {
                SharedTagNameValidationError.BLANK -> "共有タグ名を入力してください"
                SharedTagNameValidationError.TOO_LONG -> "共有タグ名は50文字以内で入力してください"
                null -> null
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

    fun confirmCreateSharedTagGroup() {
        scope.launch {
            val normalizedName = normalizeSharedTagName(sharedTagGroupDialogState.name)
            val error = when (validateSharedTagName(sharedTagGroupDialogState.name)) {
                SharedTagNameValidationError.BLANK -> "グループ名を入力してください"
                SharedTagNameValidationError.TOO_LONG -> "グループ名は50文字以内で入力してください"
                null -> null
            }
            sharedTagGroupDialogState = sharedTagGroupDialogState.copy(error = error)
            if (error != null) return@launch
            when (val created = tagViewModel.createGroup(normalizedName)) {
                CreateSharedTagGroupResult.Success -> closeCreateSharedTagGroupDialog()
                CreateSharedTagGroupResult.InvalidName -> {
                    sharedTagGroupDialogState = sharedTagGroupDialogState.copy(error = "グループ名を入力してください")
                }
                CreateSharedTagGroupResult.AuthRequired -> {
                    sharedTagGroupDialogState = sharedTagGroupDialogState.copy(error = "共有タグクラウドにログインしてください")
                }
                is CreateSharedTagGroupResult.LimitReached -> {
                    sharedTagGroupDialogState = sharedTagGroupDialogState.copy(error = created.message)
                }
                is CreateSharedTagGroupResult.Failed -> {
                    sharedTagGroupDialogState = sharedTagGroupDialogState.copy(
                        error = created.message ?: "グループを作成できませんでした",
                    )
                }
            }
        }
    }

    fun addTagToSelectedGroup(tagId: Long) {
        val group = selectedSharedTagGroup ?: return
        scope.launch {
            val added = tagViewModel.addTagToGroup(group.id, tagId)
            snackbarHostState.showSnackbar(
                if (added) "共有タグをグループに追加しました" else "共有タグをグループに追加できませんでした",
                duration = SnackbarDuration.Short,
            )
        }
    }

    fun removeTagFromSelectedGroup(tagId: Long) {
        val group = selectedSharedTagGroup ?: return
        scope.launch {
            val removed = tagViewModel.removeTagFromGroup(group.id, tagId)
            snackbarHostState.showSnackbar(
                if (removed) "共有タグをグループから外しました" else "共有タグをグループから外せませんでした",
                duration = SnackbarDuration.Short,
            )
        }
    }

    fun createSelectedGroupInvite(role: SharedTagMemberRole) {
        val group = selectedSharedTagGroup ?: return
        scope.launch {
            when (val result = tagViewModel.createGroupInviteLink(group.id, role.name.lowercase())) {
                is SharedTagGroupInviteCreationResult.Success -> {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("URL Saver group invite", result.inviteUrl))
                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, result.inviteUrl)
                    }
                    runCatching {
                        context.startActivity(Intent.createChooser(shareIntent, "グループ招待リンクを共有"))
                    }
                    snackbarHostState.showSnackbar(
                        "グループ招待リンクをコピーしました",
                        duration = SnackbarDuration.Short,
                    )
                }
                SharedTagGroupInviteCreationResult.AuthRequired -> snackbarHostState.showSnackbar(
                    "招待リンクを作るにはサインインが必要です",
                    duration = SnackbarDuration.Short,
                )
                SharedTagGroupInviteCreationResult.OwnerOnly -> snackbarHostState.showSnackbar(
                    "グループ招待リンクを作れるのはオーナーだけです",
                    duration = SnackbarDuration.Short,
                )
                is SharedTagGroupInviteCreationResult.Failure -> snackbarHostState.showSnackbar(
                    result.message,
                    duration = SnackbarDuration.Short,
                )
            }
        }
    }

    fun groupMutationMessage(result: SharedTagGroupMutationResult, success: String, fallback: String): String {
        return when (result) {
            SharedTagGroupMutationResult.Success -> success
            SharedTagGroupMutationResult.AuthRequired -> "共有タグクラウドにログインしてください"
            SharedTagGroupMutationResult.OwnerOnly -> "この操作はグループオーナーだけができます"
            SharedTagGroupMutationResult.InvalidTarget -> "対象を確認してからもう一度お試しください"
            is SharedTagGroupMutationResult.Failure -> result.message ?: fallback
        }
    }

    fun renameSelectedGroup(name: String) {
        val group = selectedSharedTagGroup ?: return
        scope.launch {
            val result = tagViewModel.renameGroup(group.id, name)
            if (result == SharedTagGroupMutationResult.Success) {
                groupRenameDialogState = SharedTagDialogUiState()
            } else {
                groupRenameDialogState = groupRenameDialogState.copy(
                    error = groupMutationMessage(result, "", "グループ名を変更できませんでした"),
                )
            }
        }
    }

    fun confirmGroupAction(action: GroupActionConfirmation) {
        val group = selectedSharedTagGroup ?: return
        scope.launch {
            val result = when (action) {
                is GroupActionConfirmation.ChangeRole -> tagViewModel.changeGroupMemberRole(
                    group.id,
                    action.member.userId,
                    action.role,
                )
                is GroupActionConfirmation.RemoveMember -> tagViewModel.removeGroupMember(group.id, action.member.userId)
                is GroupActionConfirmation.TransferOwnership -> tagViewModel.transferGroupOwnership(group.id, action.member.userId)
                GroupActionConfirmation.DeleteGroup -> tagViewModel.deleteGroup(group.id)
                is GroupActionConfirmation.RemoveTag -> {
                    val removed = tagViewModel.removeTagFromGroup(group.id, action.tag.tagId)
                    if (removed) SharedTagGroupMutationResult.Success else SharedTagGroupMutationResult.Failure("共有タグをグループから外せませんでした")
                }
            }
            if (result == SharedTagGroupMutationResult.Success && action == GroupActionConfirmation.DeleteGroup) {
                selectedSharedTagGroupId = null
            }
            pendingGroupAction = null
            snackbarHostState.showSnackbar(
                groupMutationMessage(result, action.successMessage, action.failureMessage),
                duration = SnackbarDuration.Short,
            )
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
                selectionModeActive = false
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
                selectionModeActive = false
                onBatchPendingDeleteEntries(pendingDeletions)
            } else {
                onDeleteFailed()
            }
        }
    }

    fun assignSelectedEntriesToLocalTags(tags: Collection<TagWithCount>) {
        val entryIds = selectedEntryIds
        if (entryIds.isEmpty() || tags.isEmpty()) return
        scope.launch {
            var assignedCount = 0
            tags.forEach { tag ->
                assignedCount += viewModel.assignTagToEntries(tag.id, entryIds)
            }
            if (assignedCount > 0) {
                batchLocalTagSheetVisible = false
                selectedEntryIds = emptySet()
                selectionModeActive = false
                snackbarHostState.showSnackbar("タグを追加しました", duration = SnackbarDuration.Short)
            } else {
                snackbarHostState.showSnackbar("タグを追加できませんでした", duration = SnackbarDuration.Short)
            }
        }
    }

    fun returnToMainHome() {
        selectedEntryIds = emptySet()
        selectionModeActive = false
        batchLocalTagSheetVisible = false
        selectedMainLocalTagId = null
        searchQueryLocal = ""
        searchBarVisible = false
        mainPane = MainPane.URLS
        showUsageGuide = false
        viewModel.selectService(ServiceType.ALL)
        scope.launch {
            mainListState.animateScrollToItem(0)
        }
    }

    fun startSelectionFromVisibleEntries() {
        val visibleEntryIds = displayedUiState.entries.map { it.id }.toSet()
        selectedEntryIds = visibleEntryIds
        selectionModeActive = visibleEntryIds.isNotEmpty()
    }

    fun deleteLocalTag(tag: TagWithCount) {
        scope.launch {
            val deleted = viewModel.deleteLocalTag(tag.id)
            snackbarHostState.showSnackbar(
                if (deleted) "タグを削除しました" else "タグを削除できませんでした",
                duration = SnackbarDuration.Short,
            )
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
        localTags = localSaveTags,
        selectedLocalTagIds = manualInputState.selectedLocalTagIds,
        manualLocalTagError = manualInputState.localTagError,
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
        onSelectLocalTag = { tagId ->
            manualInputState = manualInputState.copy(
                selectedLocalTagIds = if (tagId in manualInputState.selectedLocalTagIds) {
                    manualInputState.selectedLocalTagIds - tagId
                } else {
                    manualInputState.selectedLocalTagIds + tagId
                },
                localTagError = null,
            )
        },
        onRequestCreateLocalTag = {
            createLocalTagForMainFilter = false
            manualLocalTagDialogState = SharedTagDialogUiState(visible = true)
        },
        onSave = { submitManualSave() },
    )

    LocalTagManagementSheet(
        visible = showLocalTagManagerSheet,
        localTags = localSaveTags,
        onDismiss = { showLocalTagManagerSheet = false },
        onRequestRename = { tag ->
            showLocalTagManagerSheet = false
            pendingRenameLocalTagId = tag.id
            renameLocalTagDialogState = SharedTagDialogUiState(
                visible = true,
                name = tag.name,
            )
        },
        onRequestDelete = { tag ->
            showLocalTagManagerSheet = false
            pendingDeleteLocalTag = tag
        },
    )

    BatchLocalTagAssignmentSheet(
        visible = batchLocalTagSheetVisible,
        localTags = localSaveTags,
        selectedCount = selectedEntryIds.size,
        onDismiss = { batchLocalTagSheetVisible = false },
        onApply = { tags -> assignSelectedEntriesToLocalTags(tags) },
    )

    pendingDeleteLocalTag?.let { tag ->
        AlertDialog(
            onDismissRequest = { pendingDeleteLocalTag = null },
            title = { Text("タグを削除") },
            text = { Text("「${tag.name}」を削除します。URL自体は削除されません。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingDeleteLocalTag = null
                        deleteLocalTag(tag)
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                ) {
                    Text("削除する")
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteLocalTag = null }) {
                    Text("キャンセル")
                }
            },
        )
    }

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

    CreateSharedTagDialog(
        visible = manualLocalTagDialogState.visible,
        newSharedTagName = manualLocalTagDialogState.name,
        createSharedTagError = manualLocalTagDialogState.error,
        title = "タグを作成",
        body = "保存時に選べる自作タグを作成します。",
        nameLabel = "タグ名",
        placeholder = "仕事 / 後で読む / 学習 など",
        onDismiss = { closeCreateManualLocalTagDialog() },
        onNameChange = {
            manualLocalTagDialogState = manualLocalTagDialogState.copy(
                name = it,
                error = null,
            )
        },
        onConfirm = { confirmCreateManualLocalTag() },
    )

    CreateSharedTagDialog(
        visible = renameLocalTagDialogState.visible && pendingRenameLocalTagId != null,
        newSharedTagName = renameLocalTagDialogState.name,
        createSharedTagError = renameLocalTagDialogState.error,
        title = "タグ名を変更",
        body = "自作タグの名前を変更します。",
        nameLabel = "タグ名",
        placeholder = "新しいタグ名",
        confirmLabel = "変更",
        onDismiss = { closeRenameLocalTagDialog() },
        onNameChange = {
            renameLocalTagDialogState = renameLocalTagDialogState.copy(
                name = it,
                error = null,
            )
        },
        onConfirm = { confirmRenameLocalTag() },
    )

    CreateSharedTagDialog(
        visible = sharedTagGroupDialogState.visible && showSharedTagCloudUi,
        newSharedTagName = sharedTagGroupDialogState.name,
        createSharedTagError = sharedTagGroupDialogState.error,
        title = "グループを作成",
        body = "グループに招待すると、グループ内の共有タグをまとめて共有できます。",
        nameLabel = "グループ名",
        placeholder = "家族 / チーム / 旅行共有 など",
        onDismiss = { closeCreateSharedTagGroupDialog() },
        onNameChange = {
            sharedTagGroupDialogState = sharedTagGroupDialogState.copy(
                name = it,
                error = null,
            )
        },
        onConfirm = { confirmCreateSharedTagGroup() },
    )

    CreateSharedTagDialog(
        visible = groupRenameDialogState.visible && selectedSharedTagGroup != null,
        newSharedTagName = groupRenameDialogState.name,
        createSharedTagError = groupRenameDialogState.error,
        title = "グループ名を変更",
        body = "参加者に表示されるグループ名を変更します。",
        nameLabel = "グループ名",
        placeholder = "新しいグループ名",
        onDismiss = { groupRenameDialogState = SharedTagDialogUiState() },
        onNameChange = {
            groupRenameDialogState = groupRenameDialogState.copy(name = it, error = null)
        },
        onConfirm = { renameSelectedGroup(groupRenameDialogState.name) },
    )

    pendingGroupAction?.let { action ->
        AlertDialog(
            onDismissRequest = { pendingGroupAction = null },
            title = { Text(action.title) },
            text = { Text(action.body) },
            confirmButton = {
                TextButton(
                    onClick = { confirmGroupAction(action) },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = if (action.isDangerous) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.primary
                        },
                    ),
                ) {
                    Text(action.confirmLabel)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingGroupAction = null }) {
                    Text("キャンセル")
                }
            },
        )
    }

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

    if (showChatGptSheet) {
        ModalBottomSheet(
            onDismissRequest = { showChatGptSheet = false },
            sheetState = chatGptSheetState,
            dragHandle = { ExportSheetDragHandle() },
        ) {
            ChatGptExportScreen(
                viewModel = exportVm,
                onBack = { showChatGptSheet = false },
            )
        }
    }

    if (showProfileSheet) {
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
            )
        }
    }

    val isGroupPane = mainPane == MainPane.GROUPS && showSharedTagCloudUi && !showUsageGuide
    val isSearchActive = searchBarVisible || searchQueryLocal.isNotBlank()
    val showMainBottomBar = !selectionModeActive && selectedEntryIds.isEmpty() && !showUsageGuide && !isGroupPane && !isSearchActive
    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            contentWindowInsets = if (isGroupPane) {
                WindowInsets(0.dp)
            } else {
                ScaffoldDefaults.contentWindowInsets
            },
            topBar = {
                if (!isGroupPane) {
                    TopAppBar(
                        title = {
                            Text(
                                text = "りんばむ",
                                modifier = Modifier.clickable { returnToMainHome() },
                            )
                        },
                        navigationIcon = {
                            Box {
                                IconButton(onClick = { showMainMenu = true }) {
                                    Icon(
                                        Icons.Outlined.Menu,
                                        contentDescription = "メニュー",
                                        modifier = Modifier.size(MainTopBarActionIconSize),
                                    )
                                }
                                DropdownMenu(
                                    expanded = showMainMenu,
                                    onDismissRequest = { showMainMenu = false },
                                ) {
                                    DropdownMenuItem(
                                        text = { Text("プロフィール") },
                                        leadingIcon = {
                                            Icon(Icons.Outlined.AccountCircle, contentDescription = null)
                                        },
                                        onClick = {
                                            showMainMenu = false
                                            showProfileSheet = true
                                        },
                                    )
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                if (entryCardDisplayMode == EntryCardDisplayMode.RICH) {
                                                    "画像なし表示に切り替える"
                                                } else {
                                                    "画像つき表示に切り替える"
                                                },
                                            )
                                        },
                                        leadingIcon = {
                                            Icon(
                                                imageVector = if (entryCardDisplayMode == EntryCardDisplayMode.RICH) {
                                                    Icons.AutoMirrored.Outlined.ViewList
                                                } else {
                                                    Icons.Outlined.ViewAgenda
                                                },
                                                contentDescription = null,
                                            )
                                        },
                                        onClick = {
                                            showMainMenu = false
                                            viewModel.toggleEntryCardDisplayMode()
                                        },
                                    )
                                    DropdownMenuItem(
                                        text = { Text("選択") },
                                        leadingIcon = {
                                            Icon(Icons.Outlined.ChecklistRtl, contentDescription = null)
                                        },
                                        onClick = {
                                            showMainMenu = false
                                            if (!selectionModeActive) {
                                                startSelectionFromVisibleEntries()
                                            }
                                        },
                                    )
                                    DropdownMenuItem(
                                        text = { Text("使い方") },
                                        leadingIcon = {
                                            Icon(Icons.AutoMirrored.Outlined.MenuBook, contentDescription = null)
                                        },
                                        onClick = {
                                            showMainMenu = false
                                            selectedEntryIds = emptySet()
                                            selectionModeActive = false
                                            searchQueryLocal = ""
                                            searchBarVisible = false
                                            mainPane = MainPane.URLS
                                            showUsageGuide = true
                                        },
                                    )
                                    DropdownMenuItem(
                                        text = { Text("データの取り扱い") },
                                        onClick = {
                                            showMainMenu = false
                                            showPrivacyDialog = true
                                        },
                                    )
                                }
                            }
                        },
                        colors = orbitTopAppBarColors(),
                        windowInsets = compactTopAppBarInsets(),
                        actions = {
                            IconButton(onClick = {
                                if (showUsageGuide) {
                                    showUsageGuide = false
                                    searchBarVisible = true
                                } else if (searchBarVisible) {
                                    searchQueryLocal = ""
                                    searchBarVisible = false
                                } else {
                                    searchBarVisible = true
                                }
                            }) {
                                Icon(
                                    Icons.Outlined.Search,
                                    contentDescription = "検索",
                                    modifier = Modifier.size(MainTopBarActionIconSize),
                                )
                            }
                        },
                    )
                }
            },
            bottomBar = {},
        ) { paddingValues ->
            Box(
                modifier = Modifier
                    .padding(paddingValues)
                .then(if (showMainBottomBar) Modifier.padding(bottom = 156.dp) else Modifier)
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background),
                contentAlignment = Alignment.TopCenter,
            ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .widthIn(max = OrbitTokens.contentMaxWidth)
            ) {
                AnimatedVisibility(visible = searchBarVisible && mainPane == MainPane.URLS) {
                    OutlinedTextField(
                        value = searchQueryLocal,
                        onValueChange = { searchQueryLocal = it },
                        trailingIcon = {
                            if (searchQueryLocal.isNotEmpty()) {
                                IconButton(onClick = {
                                    searchQueryLocal = ""
                                    searchBarVisible = false
                                }) {
                                    Icon(Icons.Outlined.Close, contentDescription = "クリア")
                                }
                            }
                        },
                        placeholder = { Text("検索") },
                        modifier = Modifier
                            .testTag("main_search_input")
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                        singleLine = true,
                    )
                }
                if (showUsageGuide) {
                    UsageGuideContent(
                        onBack = {
                            showUsageGuide = false
                            mainPane = MainPane.URLS
                        },
                    )
                } else if (mainPane == MainPane.GROUPS && showSharedTagCloudUi) {
                    if (selectedSharedTagGroup == null) {
                        SharedTagGroupListContent(
                            groups = sharedTagGroups,
                            isSignedIn = profileCloudState.isSignedIn,
                            onBack = { mainPane = MainPane.URLS },
                            onOpenCloudAuth = { showProfileSheet = true },
                            onCreateGroup = {
                                sharedTagGroupDialogState = sharedTagGroupDialogState.copy(
                                    visible = true,
                                    error = null,
                                )
                            },
                            onOpenGroup = { group ->
                                selectedSharedTagGroupId = group.id
                            },
                        )
                    } else {
                        SharedTagGroupDetailContent(
                            group = selectedSharedTagGroup,
                            allTags = sharedTags,
                            groupTags = selectedGroupTags,
                            members = selectedGroupMembers,
                            onBack = { selectedSharedTagGroupId = null },
                            onOpenTagDetail = onOpenTagDetail,
                            onAddTag = { addTagToSelectedGroup(it) },
                            onRemoveTag = { pendingGroupAction = GroupActionConfirmation.RemoveTag(it) },
                            onCreateInvite = { createSelectedGroupInvite(it) },
                            onRenameGroup = {
                                groupRenameDialogState = SharedTagDialogUiState(
                                    visible = true,
                                    name = selectedSharedTagGroup.name,
                                )
                            },
                            onDeleteGroup = { pendingGroupAction = GroupActionConfirmation.DeleteGroup },
                            onChangeMemberRole = { member, role ->
                                pendingGroupAction = GroupActionConfirmation.ChangeRole(member, role)
                            },
                            onTransferOwnership = { member ->
                                pendingGroupAction = GroupActionConfirmation.TransferOwnership(member)
                            },
                            onRemoveMember = { member ->
                                pendingGroupAction = GroupActionConfirmation.RemoveMember(member)
                            },
                        )
                    }
                } else {
                    MainListContent(
                        uiState = displayedUiState,
                        localSaveTags = localSaveTags,
                        localTagNamesByEntryId = localTagNamesByEntryId,
                        sharedTags = visibleSharedTags,
                        showSharedTagCloudUi = showSharedTagCloudUi,
                        selectedService = selectedService,
                        selectedLocalTagId = selectedMainLocalTagId,
                        serviceFilterOrder = serviceFilterOrder,
                        topFilterOrderTokens = topFilterOrderTokens,
                        selectionModeActive = selectionModeActive,
                        selectedEntryIds = selectedEntryIds,
                        entryCardDisplayMode = entryCardDisplayMode,
                        mainListState = mainListState,
                        onSelectService = {
                            selectedMainLocalTagId = null
                            viewModel.selectService(it)
                        },
                        onReorderServices = { serviceOrder ->
                            viewModel.reorderServices(serviceOrder)
                        },
                        onReorderTopFilters = { tokens ->
                            viewModel.reorderTopFilters(tokens)
                        },
                        onSelectLocalTag = { tagId ->
                            selectedMainLocalTagId = tagId
                            if (tagId != null) {
                                viewModel.selectService(ServiceType.ALL)
                            }
                        },
                        onRequestCreateLocalTag = {
                            createLocalTagForMainFilter = true
                            manualLocalTagDialogState = SharedTagDialogUiState(visible = true)
                        },
                        onRequestRenameLocalTag = { tag ->
                            pendingRenameLocalTagId = tag.id
                            renameLocalTagDialogState = SharedTagDialogUiState(
                                visible = true,
                                name = tag.name,
                            )
                        },
                        onOpenTagDetail = onOpenTagDetail,
                        onRequestCreateSharedTag = {
                            if (profileCloudState.isSignedIn) {
                                sharedTagDialogState = sharedTagDialogState.copy(
                                    visible = true,
                                    error = null,
                                )
                            } else {
                                showProfileSheet = true
                            }
                        },
                                    onOpenGroups = null,
                        onStartEntrySelection = { entryId ->
                            selectionModeActive = true
                            selectedEntryIds = setOf(entryId)
                        },
                        onToggleEntrySelection = { entryId ->
                            toggleEntrySelection(entryId)
                        },
                        onSelectAllEntries = {
                            selectionModeActive = true
                            selectedEntryIds = displayedUiState.entries.map { it.id }.toSet()
                        },
                        onClearEntrySelection = {
                            selectedEntryIds = emptySet()
                            selectionModeActive = false
                        },
                        onArchiveSelectedEntries = { archiveSelectedEntries() },
                        onRequestTagSelectedEntries = {
                            if (selectedEntryIds.isNotEmpty()) {
                                batchLocalTagSheetVisible = true
                            }
                        },
                        onDeleteSelectedEntries = { deleteSelectedEntries() },
                        onOpenDetail = onOpenDetail,
                        onArchiveActiveEntry = { entryId -> archiveActiveEntry(entryId) },
                        onPendingDeleteActiveEntry = { entryId -> pendingDeleteActiveEntry(entryId) },
                    )
                }
            }
        }
        }
        if (showMainBottomBar) {
            MainBottomNavBar(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .offset(y = 4.dp),
                onOpenGroups = {
                    showUsageGuide = false
                    mainPane = MainPane.GROUPS
                    selectedSharedTagGroupId = null
                },
                onExport = { showExportSheet = true },
                onOpenChatGpt = { showChatGptSheet = true },
                onAdd = { openManualInput() },
                onTagManage = { showLocalTagManagerSheet = true },
                onOpenArchive = onOpenArchive,
            )
        }
    }

}

@Composable
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
private fun ManualInputSheet(
    visible: Boolean,
    inputText: String,
    inputError: ShareSaveResult?,
    localTags: List<jp.mimac.urlsaver.domain.TagWithCount>,
    selectedLocalTagIds: Set<Long>,
    manualLocalTagError: String?,
    isManualSaving: Boolean,
    onDismiss: () -> Unit,
    onInputChange: (String) -> Unit,
    onPaste: () -> Unit,
    onSelectLocalTag: (Long) -> Unit,
    onRequestCreateLocalTag: () -> Unit,
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
                label = { Text("URL / テキスト") },
                placeholder = { Text("https://example.com または残したいメモ") },
                singleLine = true,
                maxLines = 1,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Text,
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
                        ShareSaveResult.INPUT_TOO_LARGE -> Text("入力が長すぎます。256KB以内のURLまたはテキストにしてください")
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
            if (localTags.isEmpty()) {
                Text(
                    text = "タグがまだありません。必要なら作成してください",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                )
            } else {
                PackedTagAssignmentFlow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalSpacing = 8.dp,
                    verticalSpacing = 8.dp,
                ) {
                    localTags.forEach { tag ->
                        val selected = tag.id in selectedLocalTagIds
                        Surface(
                            shape = RoundedCornerShape(999.dp),
                            color = if (selected) {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                OrbitTokens.panelStrong
                            },
                            contentColor = if (selected) {
                                MaterialTheme.colorScheme.onPrimaryContainer
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                            border = BorderStroke(1.dp, OrbitTokens.outline),
                            modifier = Modifier.clickable { onSelectLocalTag(tag.id) },
                        ) {
                            Text(
                                text = tag.name,
                                style = MaterialTheme.typography.labelMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier
                                    .widthIn(max = 180.dp)
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                            )
                        }
                    }
                }
            }
            if (!manualLocalTagError.isNullOrBlank()) {
                Text(
                    text = manualLocalTagError,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                )
            }
            Spacer(Modifier.height(6.dp))
            TextButton(
                onClick = onRequestCreateLocalTag,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("+")
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
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
private fun LocalTagManagementSheet(
    visible: Boolean,
    localTags: List<TagWithCount>,
    onDismiss: () -> Unit,
    onRequestRename: (TagWithCount) -> Unit,
    onRequestDelete: (TagWithCount) -> Unit,
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

            if (localTags.isEmpty()) {
                OrbitPanel(tone = OrbitPanelTone.SOFT) {
                    Text(
                        text = "削除できるタグはまだありません",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                PackedTagAssignmentFlow(
                    horizontalSpacing = 8.dp,
                    verticalSpacing = 8.dp,
                ) {
                    localTags.forEach { tag ->
                        Surface(
                            shape = RoundedCornerShape(22.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            border = BorderStroke(1.dp, OrbitTokens.outline),
                            modifier = Modifier.combinedClickable(
                                onClick = {},
                                onDoubleClick = { onRequestRename(tag) },
                            ),
                        ) {
                            Row(
                                modifier = Modifier.padding(start = 14.dp, top = 8.dp, end = 8.dp, bottom = 8.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(modifier = Modifier.widthIn(max = 160.dp)) {
                                    Text(
                                        text = tag.name,
                                        style = MaterialTheme.typography.titleSmall,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        text = "${tag.urlCount}件",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                    )
                                }
                                IconButton(
                                    onClick = { onRequestDelete(tag) },
                                    modifier = Modifier.height(40.dp),
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.Delete,
                                        contentDescription = "削除",
                                        tint = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.size(18.dp),
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
private fun CreateSharedTagDialog(
    visible: Boolean,
    newSharedTagName: String,
    createSharedTagError: String?,
    title: String = "共有タグを作成",
    body: String = "共有タグを作るにはサインインが必要です。先に共有タグクラウド画面でサインインしてください。",
    nameLabel: String = "共有タグ名",
    placeholder: String = "チーム共有 / 旅行 / 見返す など",
    confirmLabel: String = "作成",
    onDismiss: () -> Unit,
    onNameChange: (String) -> Unit,
    onConfirm: () -> Unit,
) {
    if (!visible) return

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = body,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = newSharedTagName,
                    onValueChange = onNameChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(nameLabel) },
                    placeholder = { Text(placeholder) },
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
                Text(confirmLabel)
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
private fun UsageGuideContent(
    onBack: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = 16.dp,
            end = 16.dp,
            top = 12.dp,
            bottom = 28.dp,
        ),
    ) {
        item {
            TextButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                    contentDescription = null,
                )
                Spacer(Modifier.width(8.dp))
                Text("戻る")
            }
            Spacer(Modifier.height(12.dp))
            Text(
                text = "使い方",
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.ExtraBold,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "りんばむの基本から便利な使い方、AIとの連携までまとめました。\n最初だけ読んでも、あとで見返しても大丈夫です。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(22.dp))
        }
        item {
            UsageGuideSectionHeader("まず覚える")
            UsageGuideRow(
                marker = "1",
                markerColor = Color(0xFF16A34A),
                icon = { Icon(Icons.Outlined.IosShare, contentDescription = null) },
                iconColor = Color(0xFF128A2E),
                iconBackground = Color(0xFFEAF7ED),
                title = "Safariや他アプリから保存",
                body = "Safariや他のアプリの共有から、りんばむを選ぶだけで保存できます。",
            ) {
                ShareToRinbamPreview()
            }
            UsageGuideRow(
                marker = "2",
                markerColor = Color(0xFF16A34A),
                icon = { Icon(Icons.Outlined.Sell, contentDescription = null) },
                iconColor = Color(0xFF128A2E),
                iconBackground = Color(0xFFEAF7ED),
                title = "タグで整理",
                body = "自作タグを付けて、テーマごとに見つけやすく整理できます。",
            ) {
                GuideTagChipsPreview()
            }
            UsageGuideRow(
                marker = "3",
                markerColor = MaterialTheme.colorScheme.primary,
                icon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                iconColor = MaterialTheme.colorScheme.primary,
                iconBackground = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                title = "検索で見つける",
                body = "キーワードやタグで検索して、見たいURLをすぐに見つけられます。",
            ) {
                GuideSearchPreview()
            }
        }
        item {
            UsageGuideSectionHeader("便利な操作")
            UsageGuideRow(
                marker = "4",
                markerColor = Color(0xFFF97316),
                icon = { Icon(Icons.Outlined.Edit, contentDescription = null) },
                iconColor = Color(0xFFF97316),
                iconBackground = Color(0xFFFFF2DF),
                title = "自作タグ名を変更",
                body = "自作タグをダブルタップすると、名前を変更できます。",
            ) {
                GuideRenameTagPreview()
            }
            UsageGuideRow(
                marker = "5",
                markerColor = Color(0xFFF97316),
                icon = { Icon(Icons.Outlined.Archive, contentDescription = null) },
                iconColor = Color(0xFFF97316),
                iconBackground = Color(0xFFFFF2DF),
                title = "カードをスライド",
                body = "カードを横にスライドすると、アーカイブや削除ができます。",
            ) {
                GuideSwipePreview()
            }
        }
        item {
            UsageGuideSectionHeader("共有とAI")
            UsageGuideRow(
                marker = "6",
                markerColor = Color(0xFF7C3AED),
                icon = { Icon(Icons.Outlined.Group, contentDescription = null) },
                iconColor = Color(0xFF7C3AED),
                iconBackground = Color(0xFFF3E8FF),
                title = "共有タグを使う",
                body = "家族やチームとタグを共有して、いっしょに整理できます。",
            ) {
                GuideSharedTagsPreview()
            }
            UsageGuideRow(
                marker = "7",
                markerColor = MaterialTheme.colorScheme.primary,
                icon = { Icon(Icons.Outlined.IosShare, contentDescription = null) },
                iconColor = MaterialTheme.colorScheme.primary,
                iconBackground = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                title = "エクスポートでAIに渡す",
                body = "エクスポートしたデータをClaudeやChatGPTに渡して活用できます。",
            ) {
                GuideAIExportPreview()
            }
            UsageGuideNote()
        }
    }
}

@Composable
private fun UsageGuideSectionHeader(title: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 2.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.ExtraBold,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Box(
            modifier = Modifier
                .weight(1f)
                .height(1.dp)
                .background(OrbitTokens.outline.copy(alpha = 0.7f)),
        )
    }
}

@Composable
private fun UsageGuideRow(
    marker: String,
    markerColor: Color,
    icon: @Composable () -> Unit,
    iconColor: Color,
    iconBackground: Color,
    title: String,
    body: String,
    preview: @Composable () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .background(markerColor, shape = RoundedCornerShape(50)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = marker,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.ExtraBold,
                        color = Color.White,
                    )
                }
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .background(iconBackground, shape = RoundedCornerShape(14.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    CompositionLocalProvider(androidx.compose.material3.LocalContentColor provides iconColor) {
                        Box(modifier = Modifier.size(28.dp), contentAlignment = Alignment.Center) {
                            icon()
                        }
                    }
                }
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.ExtraBold,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                    Text(
                        text = body,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Box(modifier = Modifier.padding(start = 40.dp)) {
                preview()
            }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 40.dp)
                .height(1.dp)
                .background(OrbitTokens.outline.copy(alpha = 0.45f)),
        )
    }
}

@Composable
private fun GuidePreviewSurface(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(12.dp))
            .border(1.dp, OrbitTokens.outline.copy(alpha = 0.7f), RoundedCornerShape(12.dp))
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        content()
    }
}

@Composable
private fun ShareToRinbamPreview() {
    GuidePreviewSurface {
        Row(
            horizontalArrangement = Arrangement.spacedBy(7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MiniAppIcon("Safari", "S")
            MiniAppIcon("他アプリ", "…")
            Text("→", color = MaterialTheme.colorScheme.onSurfaceVariant)
            MiniIconBox("共有") { Icon(Icons.Outlined.IosShare, contentDescription = null) }
            Text("→", color = MaterialTheme.colorScheme.onSurfaceVariant)
            RinbamAppIcon()
        }
    }
}

@Composable
private fun GuideTagChipsPreview() {
    GuidePreviewSurface {
        Text("タグ", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            MiniChip("旅行", Color(0xFFE5F6E7), Color(0xFF128A2E))
            MiniChip("レシピ", Color(0xFFEAF2FF), MaterialTheme.colorScheme.primary)
            MiniChip("仕事", MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.onSurfaceVariant)
            MiniChip("+", MaterialTheme.colorScheme.surface, MaterialTheme.colorScheme.onSurface)
        }
    }
}

@Composable
private fun GuideSearchPreview() {
    GuidePreviewSurface {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f), RoundedCornerShape(10.dp))
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(Icons.Outlined.Search, contentDescription = null, modifier = Modifier.size(16.dp))
            Text("温泉", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            Text("×", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            MiniChip("旅行", Color(0xFFE5F6E7), Color(0xFF128A2E))
            MiniChip("温泉", Color(0xFFEAF2FF), MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun GuideRenameTagPreview() {
    GuidePreviewSurface {
        Text("ダブルタップ", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
        Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
            MiniChip("旅行", Color(0xFFE5F6E7), Color(0xFF128A2E))
            MiniChip("レシピ", Color(0xFFEAF2FF), MaterialTheme.colorScheme.primary)
            Text("→", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                text = "旅行",
                modifier = Modifier
                    .border(1.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(8.dp))
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
private fun GuideSwipePreview() {
    GuidePreviewSurface {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("右へスワイプでアーカイブ", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            Text("→", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.ExtraBold)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            ActionBlock("アーカイブ", Color(0xFF144339), Icons.Outlined.Archive)
            DetailedMiniUrlCard(Modifier.weight(1f))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            DetailedMiniUrlCard(Modifier.weight(1f))
            ActionBlock("削除", MaterialTheme.colorScheme.error, Icons.Outlined.Delete)
        }
    }
}

@Composable
private fun GuideSharedTagsPreview() {
    GuidePreviewSurface {
        Text("共有タグ", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            MiniChip("家族旅行", Color(0xFFE5F6E7), Color(0xFF128A2E))
            MiniChip("読みたい本", Color(0xFFF3E8FF), Color(0xFF7C3AED))
            MiniChip("勉強会", Color(0xFFEAF2FF), MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun GuideAIExportPreview() {
    GuidePreviewSurface {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            MiniOutlinedPanel(Modifier.weight(1f)) {
                Text("エクスポート形式", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ExportFileChip("ZIP", Color(0xFF16A34A))
                    ExportFileChip("JSON", MaterialTheme.colorScheme.primary)
                }
            }
            Text("→", color = MaterialTheme.colorScheme.onSurfaceVariant)
            MiniOutlinedPanel(Modifier.weight(1f)) {
                Text("AIに渡す", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                Text("Claude", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.ExtraBold)
                Text("ChatGPT など", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.ExtraBold)
            }
        }
    }
}

@Composable
private fun UsageGuideNote() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp, bottom = 24.dp)
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.10f), RoundedCornerShape(10.dp))
            .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.22f), RoundedCornerShape(10.dp))
            .padding(14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("✦", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold, color = MaterialTheme.colorScheme.primary)
        Text(
            text = "もっと詳しい使い方や、よくある質問は「使い方」を随時更新しています。\nブックマークからいつでも見返せます。",
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun MiniAppIcon(label: String, text: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(9.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Text(text, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.ExtraBold, color = MaterialTheme.colorScheme.primary)
        }
        Text(label, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun MiniIconBox(label: String, icon: @Composable () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.75f), RoundedCornerShape(9.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.size(17.dp), contentAlignment = Alignment.Center) { icon() }
        }
        Text(label, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun RinbamAppIcon() {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(11.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.AutoMirrored.Outlined.MenuBook, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
        }
        Text("りんばむ", style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun MiniChip(text: String, background: Color, foreground: Color) {
    Text(
        text = text,
        modifier = Modifier
            .background(background, RoundedCornerShape(99.dp))
            .padding(horizontal = 6.dp, vertical = 3.dp),
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.ExtraBold,
        color = foreground,
        maxLines = 1,
    )
}

@Composable
private fun ActionBlock(label: String, color: Color, imageVector: androidx.compose.ui.graphics.vector.ImageVector) {
    Column(
        modifier = Modifier
            .width(60.dp)
            .height(50.dp)
            .background(color, RoundedCornerShape(8.dp)),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(imageVector, contentDescription = null, tint = Color.White, modifier = Modifier.size(15.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = Color.White)
    }
}

@Composable
private fun DetailedMiniUrlCard(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .height(50.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f), RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            modifier = Modifier
                .size(30.dp)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.5f), RoundedCornerShape(6.dp)),
        )
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("週末に行きたい温泉まとめ10選", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("example.com/trip/10", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                MiniChip("旅行", Color(0xFFE5F6E7), Color(0xFF128A2E))
                MiniChip("温泉", Color(0xFFEAF2FF), MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@Composable
private fun ExportFileChip(label: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Box(
            modifier = Modifier
                .size(24.dp)
                .border(2.dp, color, RoundedCornerShape(5.dp)),
        )
        Text(label, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

@Composable
private fun MiniOutlinedPanel(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = modifier
            .border(1.dp, OrbitTokens.outline.copy(alpha = 0.65f), RoundedCornerShape(10.dp))
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        content = content,
    )
}

@Composable
private fun MainEntryList(
    entries: List<UrlEntryEntity>,
    listState: androidx.compose.foundation.lazy.LazyListState,
    selectionModeActive: Boolean,
    selectedEntryIds: Set<Long>,
    entryCardDisplayMode: EntryCardDisplayMode,
    localTagNamesByEntryId: Map<Long, List<String>>,
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
            val selectionMode = selectionModeActive || selectedEntryIds.isNotEmpty()
            val selected = entry.id in selectedEntryIds
            if (entry.recordState == RecordState.ACTIVE && !selectionMode) {
                SwipeableMainEntry(
                    entry = entry,
                    displayMode = entryCardDisplayMode,
                    localTagNames = localTagNamesByEntryId[entry.id].orEmpty(),
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
                    localTagNames = localTagNamesByEntryId[entry.id].orEmpty(),
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
@OptIn(ExperimentalFoundationApi::class)
private fun MainListContent(
    uiState: ListFilterUiState,
    localSaveTags: List<jp.mimac.urlsaver.domain.TagWithCount>,
    localTagNamesByEntryId: Map<Long, List<String>>,
    sharedTags: List<jp.mimac.urlsaver.domain.TagWithCount>,
    showSharedTagCloudUi: Boolean,
    selectedService: ServiceType,
    selectedLocalTagId: Long?,
    serviceFilterOrder: List<ServiceType>,
    topFilterOrderTokens: List<String>,
    selectionModeActive: Boolean,
    selectedEntryIds: Set<Long>,
    entryCardDisplayMode: EntryCardDisplayMode,
    mainListState: androidx.compose.foundation.lazy.LazyListState,
    onSelectService: (ServiceType) -> Unit,
    onReorderServices: (List<ServiceType>) -> Unit,
    onReorderTopFilters: (List<String>) -> Unit,
    onSelectLocalTag: (Long?) -> Unit,
    onRequestCreateLocalTag: () -> Unit,
    onRequestRenameLocalTag: (TagWithCount) -> Unit,
    onOpenTagDetail: (Long) -> Unit,
    onRequestCreateSharedTag: () -> Unit,
    onOpenGroups: (() -> Unit)?,
    onStartEntrySelection: (Long) -> Unit,
    onToggleEntrySelection: (Long) -> Unit,
    onSelectAllEntries: () -> Unit,
    onClearEntrySelection: () -> Unit,
    onArchiveSelectedEntries: () -> Unit,
    onRequestTagSelectedEntries: () -> Unit,
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
        localTags = localSaveTags,
        selectedLocalTagId = selectedLocalTagId,
        onSelectService = onSelectService,
        onSelectLocalTag = onSelectLocalTag,
        onCreateLocalTag = onRequestCreateLocalTag,
        onRenameLocalTag = onRequestRenameLocalTag,
        onReorderTopFilters = onReorderTopFilters,
    )
    Spacer(modifier = Modifier.height(8.dp))
    if (showSharedTagCloudUi) {
        TagFilterRow(
            tags = sharedTags,
            onOpenTag = onOpenTagDetail,
            onCreateTag = onRequestCreateSharedTag,
        )
        if (onOpenGroups != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onOpenGroups) {
                    Icon(
                        imageVector = Icons.Outlined.Group,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("グループ管理")
                }
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
    }
    if (selectionModeActive || selectedEntryIds.isNotEmpty()) {
        EntrySelectionBar(
            selectedCount = selectedEntryIds.size,
            allSelected = uiState.entries.isNotEmpty() && selectedEntryIds.size == uiState.entries.size,
            onSelectAll = onSelectAllEntries,
            onArchive = onArchiveSelectedEntries,
            onTag = onRequestTagSelectedEntries,
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
                selectionModeActive = selectionModeActive,
                selectedEntryIds = selectedEntryIds,
                entryCardDisplayMode = entryCardDisplayMode,
                localTagNamesByEntryId = localTagNamesByEntryId,
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
    val selectedLocalTagIds: Set<Long> = emptySet(),
    val localTagError: String? = null,
    val isSaving: Boolean = false,
)

private data class SharedTagDialogUiState(
    val visible: Boolean = false,
    val name: String = "",
    val error: String? = null,
)

private enum class MainPane {
    URLS,
    GROUPS,
}

@Composable
private fun SharedTagGroupListContent(
    groups: List<SharedTagGroupRecord>,
    isSignedIn: Boolean,
    onBack: () -> Unit,
    onOpenCloudAuth: () -> Unit,
    onCreateGroup: () -> Unit,
    onOpenGroup: (SharedTagGroupRecord) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        Icons.AutoMirrored.Outlined.ArrowBack,
                        contentDescription = "戻る",
                        modifier = Modifier.size(34.dp),
                    )
                }
                Text(
                    text = "グループ",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                )
                TextButton(onClick = onOpenCloudAuth) {
                    Text(if (isSignedIn) "プロフィール" else "サインイン")
                }
                Button(onClick = onCreateGroup) {
                    Icon(
                        imageVector = Icons.Outlined.Add,
                        contentDescription = null,
                        modifier = Modifier.size(22.dp),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "作成",
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
        if (groups.isEmpty()) {
            item {
                EmptyState(title = "グループはまだありません")
            }
        } else {
            items(groups, key = { it.id }) { group ->
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpenGroup(group) },
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 1.dp,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            text = group.name,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = when (group.currentUserRole) {
                                SharedTagMemberRole.OWNER -> "あなたはオーナーです"
                                SharedTagMemberRole.EDITOR -> "あなたは編集者です"
                                SharedTagMemberRole.VIEWER -> "あなたは閲覧者です"
                                null -> "権限を同期中です"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = "共有タグ ${group.tagCount}件 / メンバー ${group.memberCount}人",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = if (group.lastSyncedAt == null) "同期状態を確認中" else "同期済み",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SharedTagGroupDetailContent(
    group: SharedTagGroupRecord,
    allTags: List<TagWithCount>,
    groupTags: List<SharedTagGroupTagRecord>,
    members: List<SharedTagGroupMemberRecord>,
    onBack: () -> Unit,
    onOpenTagDetail: (Long) -> Unit,
    onAddTag: (Long) -> Unit,
    onRemoveTag: (SharedTagGroupTagRecord) -> Unit,
    onCreateInvite: (SharedTagMemberRole) -> Unit,
    onRenameGroup: () -> Unit,
    onDeleteGroup: () -> Unit,
    onChangeMemberRole: (SharedTagGroupMemberRecord, SharedTagMemberRole) -> Unit,
    onTransferOwnership: (SharedTagGroupMemberRecord) -> Unit,
    onRemoveMember: (SharedTagGroupMemberRecord) -> Unit,
) {
    var selectedTab by rememberSaveable(group.id) { mutableStateOf(SharedTagGroupDetailTab.MANAGE) }
    val groupTagIds = remember(groupTags) { groupTags.map { it.tagId }.toSet() }
    val addableTags = remember(allTags, groupTagIds) {
        allTags
            .filter { tag ->
                tag.scope == SharedTagScope.SYNCED &&
                    tag.syncStatus == SharedTagSyncStatus.SYNCED &&
                    tag.currentUserRole == SharedTagMemberRole.OWNER &&
                    tag.id !in groupTagIds
            }
            .sortedBy { it.name }
    }
    val tagUrlCounts = remember(allTags) { allTags.associate { it.id to it.urlCount } }
    val isOwner = group.currentUserRole == SharedTagMemberRole.OWNER
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        Icons.AutoMirrored.Outlined.ArrowBack,
                        contentDescription = "戻る",
                        modifier = Modifier.size(34.dp),
                    )
                }
                Text(
                        text = "グループ",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                    )
                }
                GroupDetailSummaryCard(
                    group = group,
                    groupTags = groupTags,
                    members = members,
                    isOwner = isOwner,
                    onRenameGroup = onRenameGroup,
                )
            }
        }
        item {
            SharedTagGroupDetailTabSwitcher(
                selectedTab = selectedTab,
                onSelectTab = { selectedTab = it },
            )
        }
        when (selectedTab) {
            SharedTagGroupDetailTab.MANAGE -> {
                item {
                    GroupManagePanel(
                        groupId = group.id,
                        isOwner = isOwner,
                        groupTags = groupTags,
                        addableTags = addableTags,
                        tagUrlCounts = tagUrlCounts,
                        onAddTag = onAddTag,
                        onRemoveTag = onRemoveTag,
                        onCreateInvite = onCreateInvite,
                        onDeleteGroup = onDeleteGroup,
                    )
                }
            }
            SharedTagGroupDetailTab.TAGS -> {
                if (groupTags.isEmpty()) {
                    item { EmptyState(title = "このグループには共有タグがありません") }
                } else {
                    items(groupTags, key = { "${it.groupId}:${it.tagId}" }) { tag ->
                        SharedTagGroupTagCard(
                            tag = tag,
                            urlCount = tagUrlCounts[tag.tagId],
                            onClick = { onOpenTagDetail(tag.tagId) },
                        )
                    }
                }
            }
            SharedTagGroupDetailTab.MEMBERS -> {
                item {
                    Text("メンバー", style = MaterialTheme.typography.titleMedium)
                }
                if (members.isEmpty()) {
                    item { EmptyState(title = "メンバー情報を同期中です") }
                } else {
                    items(members, key = { "${it.groupId}:${it.userId}" }) { member ->
                        SharedTagGroupMemberRow(
                            member = member,
                            canManage = isOwner && !member.isCurrentUser,
                            onChangeRole = { role -> onChangeMemberRole(member, role) },
                            onTransferOwnership = { onTransferOwnership(member) },
                            onRemove = { onRemoveMember(member) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun GroupDetailSummaryCard(
    group: SharedTagGroupRecord,
    groupTags: List<SharedTagGroupTagRecord>,
    members: List<SharedTagGroupMemberRecord>,
    isOwner: Boolean,
    onRenameGroup: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Box(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 18.dp),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(end = 56.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = group.name,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "権限:${sharedTagRoleLabel(group.currentUserRole)} / 共有タグ ${groupTags.size}件 / メンバー ${members.size}人",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Clip,
                )
            }
            IconButton(
                enabled = isOwner,
                onClick = onRenameGroup,
                modifier = Modifier.align(Alignment.CenterEnd),
            ) {
                Icon(Icons.Outlined.Edit, contentDescription = "グループ名を変更")
            }
        }
    }
}

@Composable
private fun SharedTagGroupDetailTabSwitcher(
    selectedTab: SharedTagGroupDetailTab,
    onSelectTab: (SharedTagGroupDetailTab) -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp),
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f),
    ) {
        Row(
            modifier = Modifier.padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SharedTagGroupDetailTab.entries.forEach { tab ->
                val selected = selectedTab == tab
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(24.dp))
                        .background(
                            if (selected) {
                                MaterialTheme.colorScheme.surface
                            } else {
                                Color.Transparent
                            },
                        )
                        .clickable { onSelectTab(tab) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = tab.label,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = if (selected) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun GroupRoleGuideText() {
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("権限の違い", style = MaterialTheme.typography.titleSmall)
        GroupRoleGuideLine(
            label = "オーナー:",
            body = "グループ設定、招待、メンバー管理、配下共有タグの管理ができます。",
        )
        GroupRoleGuideLine(
            label = "編集者:",
            body = "配下共有タグにURLを追加・削除できます。",
        )
        GroupRoleGuideLine(
            label = "閲覧者:",
            body = "配下共有タグとURLを見られます。編集はできません。",
        )
    }
}

@Composable
private fun GroupRoleGuideLine(
    label: String,
    body: String,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = label,
            modifier = Modifier.width(72.dp),
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            text = body,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun SharedTagGroupAddableTagRow(
    tag: TagWithCount,
    onAdd: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = tag.name,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "${tag.urlCount}件",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            OutlinedButton(
                onClick = onAdd,
                modifier = Modifier.widthIn(min = 64.dp),
            ) {
                Text("追加")
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SharedTagGroupMemberRow(
    member: SharedTagGroupMemberRecord,
    canManage: Boolean,
    onChangeRole: (SharedTagMemberRole) -> Unit,
    onTransferOwnership: () -> Unit,
    onRemove: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = memberLabel(member),
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = member.userId.take(8),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Surface(
                    shape = RoundedCornerShape(999.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer,
                ) {
                    Text(
                        text = sharedTagRoleLabel(member.role),
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
            }
            if (canManage) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (member.role != SharedTagMemberRole.EDITOR) {
                        OutlinedButton(onClick = { onChangeRole(SharedTagMemberRole.EDITOR) }) {
                            Text("編集者にする")
                        }
                    }
                    if (member.role != SharedTagMemberRole.VIEWER) {
                        OutlinedButton(onClick = { onChangeRole(SharedTagMemberRole.VIEWER) }) {
                            Text("閲覧者にする")
                        }
                    }
                    OutlinedButton(onClick = onTransferOwnership) {
                        Text("オーナー移譲")
                    }
                    OutlinedButton(onClick = onRemove) {
                        Text("削除")
                    }
                }
            }
        }
    }
}

@Composable
private fun GroupManagePanel(
    groupId: Long,
    isOwner: Boolean,
    groupTags: List<SharedTagGroupTagRecord>,
    addableTags: List<TagWithCount>,
    tagUrlCounts: Map<Long, Int>,
    onAddTag: (Long) -> Unit,
    onRemoveTag: (SharedTagGroupTagRecord) -> Unit,
    onCreateInvite: (SharedTagMemberRole) -> Unit,
    onDeleteGroup: () -> Unit,
) {
    var contentMode by rememberSaveable(groupId) { mutableStateOf(GroupManageContentMode.ROLE_GUIDE) }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                GroupManageActionButton(
                    text = "編集者招待",
                    enabled = isOwner,
                    onClick = { onCreateInvite(SharedTagMemberRole.EDITOR) },
                    modifier = Modifier.weight(1f),
                )
                GroupManageActionButton(
                    text = "閲覧者招待",
                    enabled = isOwner,
                    onClick = { onCreateInvite(SharedTagMemberRole.VIEWER) },
                    modifier = Modifier.weight(1f),
                )
                GroupManageActionButton(
                    text = "共有タグ",
                    onClick = { contentMode = GroupManageContentMode.TAG_MANAGEMENT },
                    modifier = Modifier.weight(1f),
                )
            }
            if (!isOwner) {
                Text(
                    text = "招待リンクを作成できるのはグループオーナーだけです。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            when (contentMode) {
                GroupManageContentMode.ROLE_GUIDE -> {
                    GroupRoleGuideText()
                    Text("同期エラーがある場合は、再同期後にこの画面へ反映されます。", style = MaterialTheme.typography.bodySmall)
                }
                GroupManageContentMode.TAG_MANAGEMENT -> {
                    GroupTagManagementContent(
                        isOwner = isOwner,
                        groupTags = groupTags,
                        addableTags = addableTags,
                        tagUrlCounts = tagUrlCounts,
                        onAddTag = onAddTag,
                        onRemoveTag = onRemoveTag,
                        onBackToRoleGuide = { contentMode = GroupManageContentMode.ROLE_GUIDE },
                    )
                }
            }
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.errorContainer,
            ) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("危険な操作", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onErrorContainer)
                    Text("グループを削除すると、配下タグのまとめ共有とグループ招待が無効になります。", color = MaterialTheme.colorScheme.onErrorContainer)
                    OutlinedButton(enabled = isOwner, onClick = onDeleteGroup) {
                        Text("グループを削除")
                    }
                }
            }
            if (!isOwner) {
                Text("管理操作はグループオーナーだけができます。", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun GroupTagManagementContent(
    isOwner: Boolean,
    groupTags: List<SharedTagGroupTagRecord>,
    addableTags: List<TagWithCount>,
    tagUrlCounts: Map<Long, Int>,
    onAddTag: (Long) -> Unit,
    onRemoveTag: (SharedTagGroupTagRecord) -> Unit,
    onBackToRoleGuide: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("共有タグ管理", style = MaterialTheme.typography.titleSmall)
            TextButton(onClick = onBackToRoleGuide) {
                Text("権限の違いに戻る")
            }
        }
        if (groupTags.isEmpty()) {
            Text(
                text = "このグループには共有タグがありません",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            groupTags.forEach { tag ->
                SharedTagGroupTagRow(
                    tag = tag,
                    urlCount = tagUrlCounts[tag.tagId],
                    canRemove = isOwner || tag.currentUserRole == SharedTagMemberRole.OWNER,
                    onRemove = { onRemoveTag(tag) },
                )
            }
        }
        Text("共有タグを追加", style = MaterialTheme.typography.titleSmall)
        if (addableTags.isEmpty()) {
            Text(
                text = "追加できるオーナー権限の共有タグはありません。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            addableTags.forEach { tag ->
                SharedTagGroupAddableTagRow(tag = tag, onAdd = { onAddTag(tag.id) })
            }
        }
    }
}

private enum class GroupManageContentMode {
    ROLE_GUIDE,
    TAG_MANAGEMENT,
}

private fun memberLabel(member: SharedTagGroupMemberRecord): String {
    return when {
        member.isCurrentUser -> "あなた"
        !member.displayName.isNullOrBlank() -> member.displayName
        else -> "メンバー ${member.userId.take(8)}"
    }
}

@Composable
private fun GroupManageActionButton(
    text: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    OrbitActionButton(
        onClick = onClick,
        modifier = modifier.height(58.dp),
        enabled = enabled,
        style = OrbitActionStyle.SECONDARY,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 2.dp, vertical = 0.dp),
    ) {
        Text(
            text = text,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Clip,
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
        )
    }
}

private enum class SharedTagGroupDetailTab(val label: String) {
    MANAGE("管理"),
    TAGS("共有タグ"),
    MEMBERS("メンバー"),
}

private sealed interface GroupActionConfirmation {
    val title: String
    val body: String
    val confirmLabel: String
    val successMessage: String
    val failureMessage: String
    val isDangerous: Boolean

    data class RemoveTag(val tag: SharedTagGroupTagRecord) : GroupActionConfirmation {
        override val title = "共有タグを外す"
        override val body = "「${tag.tagName}」をこのグループから外します。タグ自体やURLは削除されません。"
        override val confirmLabel = "外す"
        override val successMessage = "共有タグをグループから外しました"
        override val failureMessage = "共有タグをグループから外せませんでした"
        override val isDangerous = true
    }

    data class ChangeRole(
        val member: SharedTagGroupMemberRecord,
        val role: SharedTagMemberRole,
    ) : GroupActionConfirmation {
        override val title = "権限を変更"
        override val body = "「${memberLabel(member)}」を${sharedTagRoleLabel(role)}に変更します。"
        override val confirmLabel = "変更する"
        override val successMessage = "メンバー権限を変更しました"
        override val failureMessage = "メンバー権限を変更できませんでした"
        override val isDangerous = false
    }

    data class TransferOwnership(val member: SharedTagGroupMemberRecord) : GroupActionConfirmation {
        override val title = "オーナー権限を移譲"
        override val body = "「${memberLabel(member)}」をグループオーナーにします。移譲後、あなたは編集者になります。"
        override val confirmLabel = "移譲する"
        override val successMessage = "グループオーナーを移譲しました"
        override val failureMessage = "グループオーナーを移譲できませんでした"
        override val isDangerous = true
    }

    data class RemoveMember(val member: SharedTagGroupMemberRecord) : GroupActionConfirmation {
        override val title = "メンバーを削除"
        override val body = "「${memberLabel(member)}」をこのグループから削除します。"
        override val confirmLabel = "削除する"
        override val successMessage = "メンバーを削除しました"
        override val failureMessage = "メンバーを削除できませんでした"
        override val isDangerous = true
    }

    data object DeleteGroup : GroupActionConfirmation {
        override val title = "グループを削除"
        override val body = "このグループを削除します。配下タグのまとめ共有とグループ招待は無効になります。"
        override val confirmLabel = "削除する"
        override val successMessage = "グループを削除しました"
        override val failureMessage = "グループを削除できませんでした"
        override val isDangerous = true
    }
}

@Composable
private fun SharedTagGroupTagRow(
    tag: SharedTagGroupTagRecord,
    urlCount: Int?,
    canRemove: Boolean,
    onRemove: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = tag.tagName,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = buildString {
                        append("あなたのタグ権限: ${sharedTagRoleLabel(tag.currentUserRole)}")
                        if (urlCount != null) append(" / ${urlCount}件")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            OutlinedButton(
                enabled = canRemove,
                onClick = onRemove,
            ) {
                Text("外す")
            }
        }
    }
}

@Composable
private fun SharedTagGroupTagCard(
    tag: SharedTagGroupTagRecord,
    urlCount: Int?,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = tag.tagName,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = buildString {
                    append(sharedTagRoleLabel(tag.currentUserRole))
                    if (urlCount != null) append(" / ${urlCount}件")
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun sharedTagRoleLabel(role: SharedTagMemberRole?): String {
    return when (role) {
        SharedTagMemberRole.OWNER -> "オーナー"
        SharedTagMemberRole.EDITOR -> "編集者"
        SharedTagMemberRole.VIEWER -> "閲覧者"
        null -> "同期中"
    }
}

private enum class MainSwipeAction {
    ARCHIVE,
    PENDING_DELETE,
}

private enum class ArchiveSwipeAction {
    RESTORE,
    PENDING_DELETE,
}

@Composable
private fun EntrySelectionBar(
    selectedCount: Int,
    allSelected: Boolean,
    onSelectAll: () -> Unit,
    onArchive: () -> Unit,
    onTag: () -> Unit,
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
            text = "${selectedCount}件",
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
                .padding(horizontal = 12.dp)
                .semantics { contentDescription = "すべて選択" },
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
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
                contentDescription = "タグ",
                enabled = selectedCount > 0,
                onClick = onTag,
                icon = {
                    Icon(
                        imageVector = Icons.Outlined.Sell,
                        contentDescription = null,
                        tint = Color.White.copy(alpha = if (selectedCount > 0) 0.95f else 0.35f),
                        modifier = Modifier.size(28.dp),
                    )
                },
            )
            SelectionIconButton(
                contentDescription = "アーカイブ",
                enabled = selectedCount > 0,
                onClick = onArchive,
                icon = {
                    Icon(
                        imageVector = Icons.Outlined.Archive,
                        contentDescription = null,
                        tint = Color.White.copy(alpha = if (selectedCount > 0) 0.95f else 0.35f),
                        modifier = Modifier.size(30.dp),
                    )
                },
            )
            SelectionIconButton(
                contentDescription = "削除",
                enabled = selectedCount > 0,
                onClick = onDelete,
                icon = {
                    Icon(
                        imageVector = Icons.Outlined.Delete,
                        contentDescription = null,
                        tint = if (selectedCount > 0) OrbitTokens.danger else Color.White.copy(alpha = 0.35f),
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
@OptIn(ExperimentalLayoutApi::class)
private fun BatchLocalTagAssignmentSheet(
    visible: Boolean,
    localTags: List<TagWithCount>,
    selectedCount: Int,
    onDismiss: () -> Unit,
    onApply: (List<TagWithCount>) -> Unit,
) {
    if (!visible) return
    var selectedTagIds by remember(visible, localTags) { mutableStateOf<Set<Long>>(emptySet()) }
    val selectedTags = localTags.filter { tag -> tag.id in selectedTagIds }
    AlertDialog(
        onDismissRequest = {
            selectedTagIds = emptySet()
            onDismiss()
        },
        title = { Text("タグを追加") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "${selectedCount}件に追加する自作タグを選びます。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (localTags.isEmpty()) {
                    Text(
                        text = "自作タグがまだありません。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    PackedTagAssignmentFlow(
                        horizontalSpacing = 8.dp,
                        verticalSpacing = 8.dp,
                    ) {
                        localTags.forEach { tag ->
                            val selected = tag.id in selectedTagIds
                            Surface(
                                shape = RoundedCornerShape(50),
                                color = if (selected) MaterialTheme.colorScheme.primaryContainer else OrbitTokens.panelSoft,
                                border = BorderStroke(
                                    1.dp,
                                    if (selected) MaterialTheme.colorScheme.primary else OrbitTokens.outline,
                                ),
                                modifier = Modifier.clickable {
                                    selectedTagIds = if (selected) {
                                        selectedTagIds - tag.id
                                    } else {
                                        selectedTagIds + tag.id
                                    }
                                },
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                ) {
                                    if (selected) {
                                        Icon(
                                            imageVector = Icons.Outlined.Check,
                                            contentDescription = null,
                                            modifier = Modifier.size(16.dp),
                                        )
                                    }
                                    Text(
                                        text = tag.name,
                                        style = MaterialTheme.typography.labelLarge,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.widthIn(max = 180.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = selectedTags.isNotEmpty(),
                onClick = {
                    onApply(selectedTags)
                    selectedTagIds = emptySet()
                },
            ) {
                Text("追加")
            }
        },
        dismissButton = {
            TextButton(onClick = {
                selectedTagIds = emptySet()
                onDismiss()
            }) {
                Text("キャンセル")
            }
        },
    )
}

@Composable
private fun SelectionIconButton(
    contentDescription: String,
    enabled: Boolean = true,
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
            .clickable(enabled = enabled, onClick = onClick)
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
    localTagNames: List<String>,
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
            localTagNames = localTagNames,
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
private fun SwipeableArchiveEntry(
    entry: UrlEntryEntity,
    displayMode: EntryCardDisplayMode,
    onClick: () -> Unit,
    onSwipeAction: (ArchiveSwipeAction) -> Unit,
) {
    val dismissState = rememberSwipeToDismissBoxState(
        positionalThreshold = { totalDistance -> totalDistance * ENTRY_CARD_SWIPE_ACTION_THRESHOLD_FRACTION },
        confirmValueChange = { targetValue ->
            when (targetValue) {
                SwipeToDismissBoxValue.StartToEnd -> {
                    onSwipeAction(ArchiveSwipeAction.RESTORE)
                    false
                }

                SwipeToDismissBoxValue.EndToStart -> {
                    onSwipeAction(ArchiveSwipeAction.PENDING_DELETE)
                    false
                }

                SwipeToDismissBoxValue.Settled -> true
            }
        },
    )

    SwipeToDismissBox(
        state = dismissState,
        modifier = Modifier.testTag("archive_entry_swipe_${entry.id}"),
        enableDismissFromStartToEnd = true,
        enableDismissFromEndToStart = true,
        backgroundContent = {
            ArchiveSwipeBackground(
                direction = dismissState.dismissDirection,
                swipeOffsetPx = runCatching { dismissState.requireOffset() }.getOrDefault(0f),
            )
        },
    ) {
        EntryCard(
            entry = entry,
            timestampMillis = entry.createdAt,
            timestampLabel = "アーカイブ",
            displayMode = displayMode,
            showDisplayUrl = false,
            onClick = onClick,
        )
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun ArchiveSwipeBackground(
    direction: SwipeToDismissBoxValue,
    swipeOffsetPx: Float,
) {
    val isRestore = direction == SwipeToDismissBoxValue.StartToEnd
    val isDelete = direction == SwipeToDismissBoxValue.EndToStart
    val density = LocalDensity.current
    val color = when {
        isRestore -> OrbitTokens.secondarySurface
        isDelete -> OrbitTokens.dangerSurface
        else -> OrbitTokens.panelSoft
    }
    val icon = when {
        isRestore -> Icons.AutoMirrored.Outlined.ArrowBack
        isDelete -> Icons.Outlined.Delete
        else -> Icons.AutoMirrored.Outlined.ArrowBack
    }
    val label = when {
        isRestore -> "戻す"
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
        if ((isRestore || isDelete) && revealedWidth > 0.dp) {
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
    onRestoreEntry: (Long) -> Unit,
    onPendingDeleteEntry: (Long, Long) -> Unit,
    onDeleteFailed: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val allTagsWithCount by viewModel.allTagsWithCount.collectAsStateWithLifecycle()
    val selectedService by viewModel.selectedServiceFlow.collectAsStateWithLifecycle()
    val selectedLocalTagId by viewModel.selectedLocalTagIdFlow.collectAsStateWithLifecycle()
    val serviceFilterOrder by viewModel.serviceFilterOrder.collectAsStateWithLifecycle()
    val topFilterOrderTokens by viewModel.topFilterOrderTokens.collectAsStateWithLifecycle()
    val entryCardDisplayMode by viewModel.entryCardDisplayMode.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val localSaveTags = remember(allTagsWithCount) {
        allTagsWithCount
            .filter { tag ->
                tag.scope == SharedTagScope.LOCAL_ONLY
            }
            .distinctBy { normalizeSharedTagName(it.name) }
    }
    LaunchedEffect(localSaveTags, selectedLocalTagId) {
        val selectedTagId = selectedLocalTagId ?: return@LaunchedEffect
        if (localSaveTags.none { it.id == selectedTagId }) {
            viewModel.selectLocalTag(null)
        }
    }
    var createLocalTagState by remember { mutableStateOf(SharedTagDialogUiState()) }
    var renameLocalTagState by remember { mutableStateOf(SharedTagDialogUiState()) }
    var pendingRenameLocalTagId by remember { mutableStateOf<Long?>(null) }

    fun closeCreateLocalTagDialog() {
        createLocalTagState = SharedTagDialogUiState()
    }

    fun closeRenameLocalTagDialog() {
        renameLocalTagState = SharedTagDialogUiState()
        pendingRenameLocalTagId = null
    }

    fun confirmCreateLocalTag() {
        scope.launch {
            when (val result = viewModel.createLocalTag(createLocalTagState.name)) {
                is CreateTagResult.Success -> {
                    viewModel.selectLocalTag(result.tagId)
                    closeCreateLocalTagDialog()
                }

                CreateTagResult.InvalidName -> {
                    createLocalTagState = createLocalTagState.copy(error = "タグ名を入力してください")
                }

                CreateTagResult.Duplicate -> {
                    val duplicateId = localSaveTags.firstOrNull {
                        normalizeSharedTagName(it.name) == normalizeSharedTagName(createLocalTagState.name)
                    }?.id
                    if (duplicateId != null) {
                        viewModel.selectLocalTag(duplicateId)
                        closeCreateLocalTagDialog()
                    } else {
                        createLocalTagState = createLocalTagState.copy(error = "同じ名前のタグがあります")
                    }
                }

                is CreateTagResult.LimitReached -> {
                    createLocalTagState = createLocalTagState.copy(error = result.message)
                }

                CreateTagResult.Failed -> {
                    createLocalTagState = createLocalTagState.copy(error = "タグを作成できませんでした")
                }
            }
        }
    }

    fun confirmRenameLocalTag() {
        val tagId = pendingRenameLocalTagId ?: return
        scope.launch {
            when (val result = viewModel.renameLocalTag(tagId, renameLocalTagState.name)) {
                is CreateTagResult.Success -> closeRenameLocalTagDialog()
                CreateTagResult.InvalidName -> {
                    renameLocalTagState = renameLocalTagState.copy(error = "タグ名を入力してください")
                }
                CreateTagResult.Duplicate -> {
                    renameLocalTagState = renameLocalTagState.copy(error = "同じ名前のタグがあります")
                }
                is CreateTagResult.LimitReached -> {
                    renameLocalTagState = renameLocalTagState.copy(error = result.message)
                }
                CreateTagResult.Failed -> {
                    renameLocalTagState = renameLocalTagState.copy(error = "タグ名を変更できませんでした")
                }
            }
        }
    }

    fun restoreArchivedEntry(entryId: Long) {
        scope.launch {
            if (viewModel.restoreEntry(entryId)) {
                onRestoreEntry(entryId)
            }
        }
    }

    fun pendingDeleteArchivedEntry(entryId: Long) {
        scope.launch {
            val pendingUntil = viewModel.markPendingDelete(entryId)
            if (pendingUntil != null) {
                onPendingDeleteEntry(entryId, pendingUntil)
            } else {
                onDeleteFailed()
            }
        }
    }

    CreateSharedTagDialog(
        visible = createLocalTagState.visible,
        newSharedTagName = createLocalTagState.name,
        createSharedTagError = createLocalTagState.error,
        title = "タグを作成",
        body = "保存済みURLを絞り込める自作タグを作成します。",
        nameLabel = "タグ名",
        placeholder = "仕事 / 後で読む / 学習 など",
        onDismiss = { closeCreateLocalTagDialog() },
        onNameChange = {
            createLocalTagState = createLocalTagState.copy(
                name = it,
                error = null,
            )
        },
        onConfirm = { confirmCreateLocalTag() },
    )

    CreateSharedTagDialog(
        visible = renameLocalTagState.visible && pendingRenameLocalTagId != null,
        newSharedTagName = renameLocalTagState.name,
        createSharedTagError = renameLocalTagState.error,
        title = "タグ名を変更",
        body = "自作タグの名前を変更します。",
        nameLabel = "タグ名",
        placeholder = "新しいタグ名",
        confirmLabel = "変更",
        onDismiss = { closeRenameLocalTagDialog() },
        onNameChange = {
            renameLocalTagState = renameLocalTagState.copy(
                name = it,
                error = null,
            )
        },
        onConfirm = { confirmRenameLocalTag() },
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
                    localTags = localSaveTags,
                    selectedLocalTagId = selectedLocalTagId,
                    onSelectService = { viewModel.selectService(it) },
                    onReorderServices = { serviceOrder ->
                        viewModel.reorderServices(serviceOrder)
                    },
                    onReorderTopFilters = { tokens ->
                        viewModel.reorderTopFilters(tokens)
                    },
                    onSelectLocalTag = { tagId ->
                        viewModel.selectLocalTag(
                            if (selectedLocalTagId == tagId) null else tagId,
                        )
                        if (tagId != null) {
                            viewModel.selectService(ServiceType.ALL)
                        }
                    },
                    onCreateLocalTag = {
                        createLocalTagState = createLocalTagState.copy(
                            visible = true,
                            error = null,
                        )
                    },
                    onRenameLocalTag = { tag ->
                        pendingRenameLocalTagId = tag.id
                        renameLocalTagState = SharedTagDialogUiState(
                            visible = true,
                            name = tag.name,
                        )
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
                                SwipeableArchiveEntry(
                                    entry = entry,
                                    displayMode = entryCardDisplayMode,
                                    onClick = { onOpenDetail(entry.id) },
                                    onSwipeAction = { action ->
                                        when (action) {
                                            ArchiveSwipeAction.RESTORE -> restoreArchivedEntry(entry.id)
                                            ArchiveSwipeAction.PENDING_DELETE -> pendingDeleteArchivedEntry(entry.id)
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
    val videoAssets by viewModel.videoAssets.collectAsStateWithLifecycle()
    val preferredVideoAsset by viewModel.preferredVideoAsset.collectAsStateWithLifecycle()
    val latestVideoDownload by viewModel.latestVideoDownload.collectAsStateWithLifecycle()
    val savedVideoDownloads by viewModel.savedVideoDownloads.collectAsStateWithLifecycle()
    val allTagsWithCount by viewModel.allTagsWithCount.collectAsStateWithLifecycle()
    val cloudState by viewModel.cloudState.collectAsStateWithLifecycle()
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
    var showMediaViewer by rememberSaveable { mutableStateOf(false) }
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
    val assignedLocalTagNameSet = remember(assignedLocalTags) {
        assignedLocalTags.mapTo(mutableSetOf()) { normalizeSharedTagName(it.name) }
    }
    val availableLocalTags = remember(
        allTagsWithCount,
        assignedTagIds,
        assignedLocalTagNameSet,
    ) {
        allTagsWithCount
            .asSequence()
            .filter { it.scope == SharedTagScope.LOCAL_ONLY }
            .filterNot { it.id in assignedTagIds }
            .filter { tag -> normalizeSharedTagName(tag.name) !in assignedLocalTagNameSet }
            .distinctBy { normalizeSharedTagName(it.name) }
            .toList()
    }
    val localTagNameSet = remember(allTagsWithCount) {
        allTagsWithCount
            .filter { it.scope == SharedTagScope.LOCAL_ONLY }
            .map { normalizeSharedTagName(it.name) }
            .toSet()
    }
    val localReservedTagNameSet = remember(localTagNameSet) { localTagNameSet }
    val assignedLocalTagItems = remember(assignedLocalTags, locallyRemovedTagIds) {
        assignedLocalTags
            .filterNot { it.id in locallyRemovedTagIds }
            .distinctBy { normalizeSharedTagName(it.name) }
            .map { DetailTagItem.LocalTag(it) }
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
    val savedMediaItems = remember(savedVideoDownloads, context) {
        savedVideoDownloads.mapNotNull { it.toSavedMediaItem(context) }
    }

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
    val isTextCard = UrlRules.isTextCardHost(current.normalizedHost)
    val textCardBody = current.fetchedBody?.takeIf { it.isNotBlank() } ?: current.originalUrl
    val showSummarySection = hasSummary
    val showBodySection = hasBody && !hideBodyAsDuplicateSummary
    val isSocialExpandableContent = current.serviceType == ServiceType.YOUTUBE ||
        current.serviceType == ServiceType.TIKTOK ||
        current.serviceType == ServiceType.X ||
        current.serviceType == ServiceType.INSTAGRAM
    val socialContentText = preferredMetadataContentText(
        fetchedBody = current.fetchedBody,
        bodySummary = current.bodySummary,
        description = current.description,
    )
    val summarySectionLabel = if (isTextCard) {
        "タイトル"
    } else {
        metadataSummarySectionLabel(
        serviceType = current.serviceType,
        fetchedBody = current.fetchedBody,
        fetchedBodyKind = current.fetchedBodyKind,
        )
    }
    val bodySectionLabel = if (isTextCard) {
        "本文"
    } else {
        metadataBodySectionLabel(
        serviceType = current.serviceType,
        fetchedBody = current.fetchedBody,
        fetchedBodyKind = current.fetchedBodyKind,
        )
    }
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
    val supportedMediaService = current.serviceType in setOf(
        ServiceType.INSTAGRAM,
    )
    val canOfferMediaAction = current.localProvenanceCount > 0 &&
        current.recordState == RecordState.ACTIVE &&
        BuildConfig.ALLOW_LOCAL_MEDIA_DOWNLOADS &&
        supportedMediaService
    val hasSavedInternalMedia = savedMediaItems.isNotEmpty()
    val hasDownloadableMediaAsset = videoAssets.any { it.resolveStatus == "AVAILABLE" && !it.downloadUrl.isNullOrBlank() }
    val mediaUnavailable = canOfferMediaAction &&
        videoAssets.isNotEmpty() &&
        !hasDownloadableMediaAsset &&
        videoAssets.any { it.resolveStatus == "FAILED" || it.resolveStatus == "UNAVAILABLE" }
    val mediaUnavailableMessage = if (mediaUnavailable) {
        mediaResolveFailureMessage(
            reason = videoAssets.firstOrNull { it.resolveStatus == "FAILED" || it.resolveStatus == "UNAVAILABLE" }?.errorReason,
            serviceType = current.serviceType,
        )
    } else {
        null
    }
    val mediaActionLabel = when {
        hasSavedInternalMedia -> "メディアを開く"
        latestVideoDownload?.status == "DOWNLOADING" -> "保存中... ${latestVideoDownload?.progress ?: 0}%"
        latestVideoDownload?.status == "QUEUED" -> "保存待ち"
        mediaUnavailable -> "取得できませんでした"
        else -> "メディアを保存"
    }
    val mediaActionEnabled = hasSavedInternalMedia ||
        (canOfferMediaAction && latestVideoDownload?.status !in setOf("DOWNLOADING", "QUEUED"))
    val showMediaAction = hasSavedInternalMedia || canOfferMediaAction || preferredVideoAsset != null
    LaunchedEffect(current.metadataState) {
        if (current.metadataState != MetadataState.PENDING) {
            retryRequested = false
        }
    }

    if (showMediaViewer && savedMediaItems.isNotEmpty()) {
        AppMediaViewerDialog(
            items = savedMediaItems,
            title = effectiveTitle,
            onDismiss = { showMediaViewer = false },
        )
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
                        PackedTagAssignmentFlow(
                            horizontalSpacing = 10.dp,
                            verticalSpacing = 10.dp,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            assignedLocalTagItems.forEach { item ->
                                DetailTagAssignmentOptionRow(
                                    label = item.name,
                                    actionLabel = "外す",
                                    compact = true,
                                    enabled = item.canRemove,
                                    actionTestTag = "detail_local_tag_remove_tag_${item.tag.id}",
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
                    if (availableLocalTags.isEmpty()) {
                        Text(
                            text = "追加できるタグはありません",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        PackedTagAssignmentFlow(
                            horizontalSpacing = 10.dp,
                            verticalSpacing = 10.dp,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            availableLocalTags.forEach { tag ->
                                DetailTagAssignmentOptionRow(
                                    label = tag.name,
                                    actionLabel = "追加",
                                    compact = true,
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
                    if (isTextCard) {
                        OrbitActionButton(
                            onClick = {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                clipboard.setPrimaryClip(ClipData.newPlainText("text", textCardBody))
                                onCopySuccess()
                            },
                            style = OrbitActionStyle.PRIMARY,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            OrbitActionText("コピー")
                        }
                    } else {
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
                    }

                    if (showMediaAction) {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            OrbitActionButton(
                                onClick = {
                                    if (hasSavedInternalMedia) {
                                        showMediaViewer = true
                                    } else if (!mediaUnavailable) {
                                        viewModel.onSaveVideoClicked()
                                    }
                                },
                                enabled = mediaActionEnabled,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                OrbitActionText(mediaActionLabel)
                            }
                            mediaUnavailableMessage?.let { message ->
                                Text(
                                    text = message,
                                    modifier = Modifier.fillMaxWidth(),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
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
                        title = "自作タグ",
                        emptyText = "まだ自作タグは付いていません",
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
                            if (!isTextCard) {
                                DetailValue(label = "正規化URL", value = current.normalizedUrl)
                            }
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

private data class SavedMediaItem(
    val uri: Uri,
    val mediaType: String,
    val fileName: String,
    val dimensions: MediaDimensions?,
)

private data class VideoTexturePlayback(
    val uri: Uri,
    val player: MediaPlayer?,
)

private data class MediaDimensions(
    val width: Int,
    val height: Int,
)

private fun VideoDownloadEntity.toSavedMediaItem(context: Context): SavedMediaItem? {
    val file = AppMediaStore.fileForLocalUri(context, localUri) ?: return null
    if (!file.exists() || !file.isFile) return null
    val mediaType = mediaTypeFromFile(file)
    return SavedMediaItem(
        uri = Uri.fromFile(file),
        mediaType = mediaType,
        fileName = fileName ?: file.name,
        dimensions = readMediaDimensions(file, mediaType),
    )
}

private fun mediaTypeFromFile(file: File): String {
    val extension = file.extension.lowercase()
    return when (extension) {
        "jpg", "jpeg", "png", "webp", "gif", "heic", "heif" -> "IMAGE"
        else -> "VIDEO"
    }
}

private fun mediaResolveFailureMessage(reason: String?, serviceType: ServiceType): String {
    return when (reason) {
        "AUTH_REQUIRED" -> "${serviceType.displayName}側の認証が必要なため、現在はメディアを取得できません。"
        "MEDIA_RESOLVER_BACKEND_UNCONFIGURED" -> "メディア保存サーバーが未設定です。"
        "MEDIA_NOT_FOUND",
        "MEDIA_ASSET_NOT_FOUND",
        -> "この投稿から保存できるメディアを見つけられませんでした。"
        "FORMAT_UNAVAILABLE" -> "この投稿のメディア形式は現在保存できません。"
        "YOUTUBE_DIRECT_RESOLVE_TIMEOUT" -> "YouTube側の確認により、現在はメディアを取得できません。"
        else -> "メディアを取得できませんでした。時間をおいて再度お試しください。"
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AppMediaViewerDialog(
    items: List<SavedMediaItem>,
    title: String,
    onDismiss: () -> Unit,
) {
    val pagerState = rememberPagerState(pageCount = { items.size })
    val carouselAspectRatio = remember(items) { stableMediaAspectRatio(items) }
    val dismissThresholdPx = with(LocalDensity.current) { 96.dp.toPx() }
    var downwardDragPx by remember { mutableStateOf(0f) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.48f)),
            contentAlignment = Alignment.BottomCenter,
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight()
                    .pointerInput(onDismiss) {
                        detectVerticalDragGestures(
                            onDragStart = { downwardDragPx = 0f },
                            onVerticalDrag = { _, dragAmount ->
                                downwardDragPx = (downwardDragPx + dragAmount).coerceAtLeast(0f)
                                if (downwardDragPx >= dismissThresholdPx) {
                                    downwardDragPx = 0f
                                    onDismiss()
                                }
                            },
                            onDragEnd = { downwardDragPx = 0f },
                            onDragCancel = { downwardDragPx = 0f },
                        )
                    },
                shape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp),
                color = MaterialTheme.colorScheme.background,
                contentColor = MaterialTheme.colorScheme.onBackground,
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp)
                        .padding(
                            top = 10.dp,
                            bottom = WindowInsets.navigationBars
                                .only(WindowInsetsSides.Bottom)
                                .asPaddingValues()
                                .calculateBottomPadding() + 24.dp,
                        ),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Box(
                        modifier = Modifier.fillMaxWidth(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(
                            modifier = Modifier
                                .width(72.dp)
                                .height(8.dp)
                                .background(
                                    OrbitTokens.outline.copy(alpha = 0.72f),
                                    RoundedCornerShape(50),
                                ),
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .horizontalScroll(rememberScrollState()),
                        ) {
                            Text(
                                text = title,
                                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                                maxLines = 1,
                                overflow = TextOverflow.Clip,
                                color = MaterialTheme.colorScheme.onBackground,
                            )
                        }
                        IconButton(
                            onClick = onDismiss,
                            modifier = Modifier
                                .size(40.dp)
                                .background(MaterialTheme.colorScheme.surfaceVariant, CircleShape)
                                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape),
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Close,
                                contentDescription = "閉じる",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    if (items.isEmpty()) {
                        OrbitPanel(tone = OrbitPanelTone.SOFT) {
                            Text(
                                text = "保存済みメディアはありません",
                                modifier = Modifier.fillMaxWidth(),
                                textAlign = TextAlign.Center,
                                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(Modifier.weight(1f))
                    } else {
                        BoxWithConstraints(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f)
                                .offset(y = 64.dp),
                        ) {
                            val mediaAspectRatio = carouselAspectRatio.takeIf { it > 0f } ?: 1f
                            val indicatorReserve = if (items.size > 1 && maxHeight > 46.dp) 46.dp else 0.dp
                            val availableMediaHeight = maxHeight - indicatorReserve
                            val mediaHeightByRatio = maxWidth / mediaAspectRatio
                            val stableMediaFrameHeight = if (mediaHeightByRatio < availableMediaHeight) {
                                mediaHeightByRatio
                            } else {
                                availableMediaHeight
                            }
                            val maxIndicatorTop = (maxHeight - 88.dp).coerceAtLeast(0.dp)
                            val indicatorTopOffset = (stableMediaFrameHeight + 14.dp).coerceAtMost(maxIndicatorTop)

                            HorizontalPager(
                                state = pagerState,
                                key = { page -> items[page].uri.toString() },
                                modifier = Modifier.fillMaxSize(),
                            ) { page ->
                                AppMediaPreview(
                                    item = items[page],
                                    frameAspectRatio = carouselAspectRatio,
                                    reserveIndicatorSpace = items.size > 1,
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(horizontal = 2.dp),
                                )
                            }
                            if (items.size > 1) {
                                AppMediaFixedPageIndicator(
                                    currentPage = pagerState.currentPage,
                                    pageCount = items.size,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(24.dp)
                                        .offset(y = indicatorTopOffset),
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
private fun AppMediaFixedPageIndicator(
    currentPage: Int,
    pageCount: Int,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center,
    ) {
        AppMediaPageIndicator(
            currentPage = currentPage,
            pageCount = pageCount,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun AppMediaPageIndicator(
    currentPage: Int,
    pageCount: Int,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(pageCount) { index ->
            val selected = index == currentPage
            Box(
                modifier = Modifier
                    .size(width = 14.dp, height = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .background(
                            color = if (selected) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.32f)
                            },
                            shape = CircleShape,
                        ),
                )
            }
        }
    }
}

@Composable
private fun AppMediaPreview(
    item: SavedMediaItem,
    frameAspectRatio: Float,
    reserveIndicatorSpace: Boolean,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter,
    ) {
        val mediaAspectRatio = frameAspectRatio.takeIf { it > 0f } ?: 1f
        val indicatorReserve = if (reserveIndicatorSpace && maxHeight > 46.dp) 46.dp else 0.dp
        val availableMediaHeight = maxHeight - indicatorReserve
        val mediaHeightByRatio = maxWidth / mediaAspectRatio
        val mediaHeight = if (mediaHeightByRatio < availableMediaHeight) mediaHeightByRatio else availableMediaHeight

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(mediaHeight)
                .clip(RoundedCornerShape(24.dp))
                .background(Color.Black),
            contentAlignment = Alignment.Center,
        ) {
            if (item.mediaType == "IMAGE") {
                AsyncImage(
                    model = item.uri,
                    contentDescription = item.fileName,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                AndroidView(
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(24.dp)),
                    factory = { context ->
                        TextureView(context)
                    },
                    update = { textureView ->
                        val currentPlayback = textureView.tag as? VideoTexturePlayback
                        if (currentPlayback?.uri != item.uri) {
                            currentPlayback?.player?.release()
                            textureView.tag = VideoTexturePlayback(uri = item.uri, player = null)
                            textureView.surfaceTextureListener = videoTextureListener(textureView, item.uri)
                            if (textureView.isAvailable) {
                                textureView.surfaceTexture?.let { surfaceTexture ->
                                    startTextureVideo(textureView, item.uri, surfaceTexture)
                                }
                            }
                        }
                        textureView.setOnClickListener {
                            val player = (textureView.tag as? VideoTexturePlayback)?.player ?: return@setOnClickListener
                            if (player.isPlaying) {
                                player.pause()
                            } else {
                                player.start()
                            }
                        }
                    },
                    onRelease = { textureView ->
                        (textureView.tag as? VideoTexturePlayback)?.player?.release()
                        textureView.surfaceTextureListener = null
                        textureView.tag = null
                    },
                )
            }
        }
    }
}

private fun stableMediaAspectRatio(items: List<SavedMediaItem>): Float {
    val first = items.firstOrNull()
    return first?.dimensions?.aspectRatio()
        ?: if (first?.mediaType == "VIDEO") 9f / 16f else 1f
}

private fun MediaDimensions.aspectRatio(): Float? {
    return if (width > 0 && height > 0) width.toFloat() / height.toFloat() else null
}

private fun readMediaDimensions(file: File, mediaType: String): MediaDimensions? {
    return if (mediaType == "IMAGE") readImageDimensions(file) else readVideoDimensions(file)
}

private fun readImageDimensions(file: File): MediaDimensions? {
    val options = BitmapFactory.Options().apply {
        inJustDecodeBounds = true
    }
    BitmapFactory.decodeFile(file.path, options)
    return if (options.outWidth > 0 && options.outHeight > 0) {
        MediaDimensions(options.outWidth, options.outHeight)
    } else {
        null
    }
}

private fun readVideoDimensions(file: File): MediaDimensions? {
    val retriever = MediaMetadataRetriever()
    return try {
        retriever.setDataSource(file.absolutePath)
        val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull()
        val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull()
        if (width == null || height == null || width <= 0 || height <= 0) {
            null
        } else {
            val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            if (rotation % 180 == 0) MediaDimensions(width, height) else MediaDimensions(height, width)
        }
    } catch (_: RuntimeException) {
        null
    } finally {
        runCatching { retriever.release() }
    }
}

private fun videoTextureListener(textureView: TextureView, uri: Uri): TextureView.SurfaceTextureListener {
    return object : TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
            startTextureVideo(textureView, uri, surface)
        }

        override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
            val player = (textureView.tag as? VideoTexturePlayback)?.player
            applyTextureVideoFit(textureView, player?.videoWidth ?: 0, player?.videoHeight ?: 0)
        }

        override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
            (textureView.tag as? VideoTexturePlayback)?.player?.release()
            textureView.tag = VideoTexturePlayback(uri = uri, player = null)
            return true
        }

        override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
    }
}

private fun readImageDimensions(uri: Uri): MediaDimensions? {
    val path = uri.path ?: return null
    val options = BitmapFactory.Options().apply {
        inJustDecodeBounds = true
    }
    BitmapFactory.decodeFile(path, options)
    return if (options.outWidth > 0 && options.outHeight > 0) {
        MediaDimensions(options.outWidth, options.outHeight)
    } else {
        null
    }
}

private fun startTextureVideo(textureView: TextureView, uri: Uri, surfaceTexture: SurfaceTexture) {
    (textureView.tag as? VideoTexturePlayback)?.player?.release()
    val surface = Surface(surfaceTexture)
    val player = MediaPlayer()
    try {
        player.setDataSource(textureView.context, uri)
        player.setSurface(surface)
        player.isLooping = false
        player.setOnPreparedListener { preparedPlayer ->
            applyTextureVideoFit(textureView, preparedPlayer.videoWidth, preparedPlayer.videoHeight)
            preparedPlayer.start()
        }
        player.setOnCompletionListener { completedPlayer ->
            completedPlayer.seekTo(0)
        }
        player.setOnErrorListener { erroredPlayer, _, _ ->
            erroredPlayer.release()
            textureView.tag = VideoTexturePlayback(uri = uri, player = null)
            true
        }
        textureView.tag = VideoTexturePlayback(uri = uri, player = player)
        player.prepareAsync()
    } catch (_: RuntimeException) {
        player.release()
        textureView.tag = VideoTexturePlayback(uri = uri, player = null)
    } finally {
        surface.release()
    }
}

private fun applyTextureVideoFit(textureView: TextureView, videoWidth: Int, videoHeight: Int) {
    val viewWidth = textureView.width
    val viewHeight = textureView.height
    if (videoWidth <= 0 || videoHeight <= 0 || viewWidth <= 0 || viewHeight <= 0) {
        textureView.setTransform(null)
        return
    }

    val scale = min(viewWidth.toFloat() / videoWidth.toFloat(), viewHeight.toFloat() / videoHeight.toFloat())
    val scaleX = (videoWidth.toFloat() * scale) / viewWidth.toFloat()
    val scaleY = (videoHeight.toFloat() * scale) / viewHeight.toFloat()
    val matrix = Matrix().apply {
        setScale(scaleX, scaleY, viewWidth / 2f, viewHeight / 2f)
    }
    textureView.setTransform(matrix)
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
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                DetailTagSectionLabel(
                    text = title,
                    modifier = Modifier.weight(1f),
                )
                DetailTagEditButton(
                    onClick = onEdit,
                    modifier = if (editButtonTestTag == null) {
                        Modifier.width(78.dp)
                    } else {
                        Modifier.width(78.dp).testTag(editButtonTestTag)
                    },
                )
            }

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
    compact: Boolean = false,
    enabled: Boolean = true,
    actionTestTag: String? = null,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp)
    Row(
        modifier = (if (compact) {
            modifier.widthIn(max = 260.dp)
        } else {
            modifier.fillMaxWidth()
        })
            .background(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = shape,
            )
            .border(
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                shape = shape,
            )
            .padding(
                start = if (compact) 12.dp else 16.dp,
                top = if (compact) 9.dp else 10.dp,
                end = if (compact) 8.dp else 10.dp,
                bottom = if (compact) 9.dp else 10.dp,
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(if (compact) 6.dp else 8.dp),
    ) {
        Text(
            text = label,
            modifier = if (compact) Modifier.widthIn(max = 132.dp) else Modifier.weight(1f),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = if (compact) 2 else 1,
            overflow = TextOverflow.Ellipsis,
        )
        Button(
            onClick = onClick,
            enabled = enabled,
            modifier = (if (actionTestTag == null) {
                Modifier
            } else {
                Modifier.testTag(actionTestTag)
            })
                .height(if (compact) 38.dp else 40.dp)
                .widthIn(min = if (compact) 54.dp else 72.dp),
            shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                horizontal = if (compact) 10.dp else 14.dp,
                vertical = 0.dp,
            ),
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
private fun PackedTagAssignmentFlow(
    horizontalSpacing: Dp,
    verticalSpacing: Dp,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    val horizontalSpacingPx = with(density) { horizontalSpacing.roundToPx() }
    val verticalSpacingPx = with(density) { verticalSpacing.roundToPx() }

    Layout(
        content = content,
        modifier = modifier,
    ) { measurables, constraints ->
        val maxWidth = if (constraints.hasBoundedWidth) constraints.maxWidth else Constraints.Infinity
        val childConstraints = constraints.copy(minWidth = 0, minHeight = 0)
        val placeables = measurables.map { measurable -> measurable.measure(childConstraints) }
        val remaining = placeables.indices.toMutableList()
        val rows = mutableListOf<List<Int>>()

        while (remaining.isNotEmpty()) {
            val row = mutableListOf(remaining.removeAt(0))
            var rowWidth = placeables[row.first()].width

            var cursor = 0
            while (cursor < remaining.size) {
                val candidateIndex = remaining[cursor]
                val candidateWidth = placeables[candidateIndex].width
                val nextWidth = rowWidth + horizontalSpacingPx + candidateWidth
                if (nextWidth <= maxWidth) {
                    row.add(candidateIndex)
                    rowWidth = nextWidth
                    remaining.removeAt(cursor)
                } else {
                    cursor += 1
                }
            }
            rows.add(row)
        }

        val rowHeights = rows.map { row ->
            row.maxOfOrNull { index -> placeables[index].height } ?: 0
        }
        val contentWidth = rows.maxOfOrNull { row ->
            row.sumOf { index -> placeables[index].width } +
                horizontalSpacingPx * (row.size - 1).coerceAtLeast(0)
        } ?: 0
        val contentHeight = rowHeights.sum() + verticalSpacingPx * (rows.size - 1).coerceAtLeast(0)
        val layoutWidth = if (constraints.hasBoundedWidth) {
            constraints.maxWidth
        } else {
            contentWidth.coerceAtLeast(constraints.minWidth)
        }
        val layoutHeight = contentHeight.coerceIn(constraints.minHeight, constraints.maxHeight)

        layout(layoutWidth, layoutHeight) {
            var y = 0
            rows.forEachIndexed { rowIndex, row ->
                var x = 0
                row.forEach { index ->
                    placeables[index].placeRelative(x = x, y = y)
                    x += placeables[index].width + horizontalSpacingPx
                }
                y += rowHeights[rowIndex] + verticalSpacingPx
            }
        }
    }
}

@Composable
private fun DetailTagSectionLabel(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        modifier = modifier,
        style = MaterialTheme.typography.labelMedium.copy(
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.2.sp,
        ),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun DetailTagEditButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Button(
        onClick = onClick,
        modifier = modifier
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
) {
    var expanded by rememberSaveable(text) { mutableStateOf(false) }
    OrbitPanel(tone = OrbitPanelTone.SOFT, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
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
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 20.dp),
            )
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
private fun MainBottomNavBar(
    modifier: Modifier = Modifier,
    onOpenGroups: () -> Unit,
    onExport: () -> Unit,
    onOpenChatGpt: () -> Unit,
    onAdd: () -> Unit,
    onTagManage: () -> Unit,
    onOpenArchive: () -> Unit,
) {
    val navigationBarHeight = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val bottomFillHeight = if (navigationBarHeight < 32.dp) 32.dp else navigationBarHeight
    val bottomBarColor = MaterialTheme.colorScheme.surfaceColorAtElevation(3.dp)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(156.dp + bottomFillHeight),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .height(76.dp + bottomFillHeight)
                .align(Alignment.BottomCenter),
            color = bottomBarColor,
            tonalElevation = 0.dp,
            shadowElevation = 0.dp,
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(76.dp)
                        .align(Alignment.TopCenter),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        MainBottomNavItem(
                            icon = Icons.Outlined.Groups,
                            label = "グループ",
                            onClick = onOpenGroups,
                        )
                    }
                    Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        MainBottomNavItem(
                            icon = Icons.Outlined.IosShare,
                            label = "エクスポート",
                            onClick = onExport,
                        )
                    }
                    Spacer(modifier = Modifier.width(76.dp))
                    Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        MainBottomNavItem(
                            icon = Icons.Outlined.Sell,
                            label = "タグ",
                            onClick = onTagManage,
                        )
                    }
                    Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        MainBottomNavItem(
                            icon = Icons.Outlined.Archive,
                            label = "アーカイブ",
                            onClick = onOpenArchive,
                        )
                    }
                }
            }
        }
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .offset(y = 61.dp)
                .size(76.dp)
                .background(
                    color = MaterialTheme.colorScheme.primary,
                    shape = RoundedCornerShape(99.dp),
                )
                .clickable(onClick = onAdd)
                .semantics { contentDescription = "追加" },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "+",
                color = MaterialTheme.colorScheme.onPrimary,
                fontSize = 48.sp,
                fontWeight = FontWeight.Black,
                lineHeight = 48.sp,
                textAlign = TextAlign.Center,
            )
        }
        Surface(
            onClick = onOpenChatGpt,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 8.dp, end = 14.dp)
                .heightIn(min = 48.dp)
                .semantics { contentDescription = "ChatGPT" },
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.primaryContainer,
            shadowElevation = 3.dp,
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Outlined.ChatBubbleOutline,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Text(
                    text = "ChatGPT",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun MainBottomNavItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    selected: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val tint = when {
        !enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
        selected -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val baseStyle = MaterialTheme.typography.labelSmall
    var labelScale by remember(label) { mutableStateOf(1f) }
    Column(
        modifier = Modifier
            .offset(y = (-2).dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, onClick = onClick, role = Role.Button)
            .then(
                if (selected) {
                    Modifier.semantics(mergeDescendants = true) { this.selected = true }
                } else {
                    Modifier
                },
            )
            .semantics { contentDescription = label }
            .padding(horizontal = 4.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(36.dp))
        Text(
            text = label,
            style = baseStyle,
            fontSize = baseStyle.fontSize * labelScale,
            color = tint,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
            onTextLayout = { result ->
                if (result.hasVisualOverflow && labelScale > 0.7f) {
                    labelScale -= 0.05f
                }
            },
        )
    }
}

fun filterEntriesBySearch(
    entries: List<UrlEntryEntity>,
    query: String,
    tags: List<TagWithCount>,
    localTagEntryRefs: List<LocalTagEntryRef> = emptyList(),
): List<UrlEntryEntity> {
    val normalizedQuery = query.trim().lowercase()
    if (normalizedQuery.isBlank()) return entries
    val tagNamesById = tags.associate { it.id to it.name.lowercase() }
    val tagIdsByEntryId = localTagEntryRefs.groupBy(
        keySelector = { it.entryId },
        valueTransform = { it.tagId },
    )
    return entries.filter { entry ->
        val entryTagNames = tagIdsByEntryId[entry.id].orEmpty().mapNotNull { tagNamesById[it] }
        entry.normalizedUrl.lowercase().contains(normalizedQuery) ||
            entry.originalUrl.lowercase().contains(normalizedQuery) ||
            entry.displayUrl.lowercase().contains(normalizedQuery) ||
            entry.userTitle.orEmpty().lowercase().contains(normalizedQuery) ||
            entry.fetchedTitle.orEmpty().lowercase().contains(normalizedQuery) ||
            entry.fetchedAuthorName.orEmpty().lowercase().contains(normalizedQuery) ||
            entry.fetchedBody.orEmpty().lowercase().contains(normalizedQuery) ||
            entry.bodySummary.orEmpty().lowercase().contains(normalizedQuery) ||
            entry.description.orEmpty().lowercase().contains(normalizedQuery) ||
            entry.memo.lowercase().contains(normalizedQuery) ||
            entry.normalizedHost.lowercase().contains(normalizedQuery) ||
            filterLabelForService(entry.serviceType).lowercase().contains(normalizedQuery) ||
            entryTagNames.any { it.contains(normalizedQuery) }
    }
}

@Composable
private fun OnboardingGuideOverlay(onFinish: () -> Unit) {
    var pageIndex by rememberSaveable { mutableStateOf(0) }
    val page = onboardingGuidePages[pageIndex]
    val isLast = pageIndex == onboardingGuidePages.lastIndex

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .semantics { contentDescription = "使い方ガイド" },
    ) {
        val density = LocalDensity.current
        val widthPx = with(density) { maxWidth.toPx() }
        val heightPx = with(density) { maxHeight.toPx() }
        val canvasSize = Size(widthPx, heightPx)
        val spotlight = page.spotlight(canvasSize)
        val arrow = page.arrowOffset(canvasSize)
        val panelTopPadding = with(density) {
            guidePanelTopPaddingPx(
                spotlight = spotlight,
                canvasSize = canvasSize,
                panelOnTop = page.panelOnTop,
                pageIndex = pageIndex,
            ).toDp()
        }

        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen },
        ) {
            drawRect(Color.Black.copy(alpha = 0.72f))
            drawRoundRect(
                color = Color.Transparent,
                topLeft = Offset(spotlight.left, spotlight.top),
                size = Size(spotlight.width, spotlight.height),
                cornerRadius = CornerRadius(22.dp.toPx(), 22.dp.toPx()),
                blendMode = BlendMode.Clear,
            )
        }

        Text(
            text = page.arrowText,
            modifier = Modifier
                .padding(
                    start = with(density) { arrow.x.toDp() },
                    top = with(density) { arrow.y.toDp() },
                ),
            style = if (page.arrowLarge) {
                MaterialTheme.typography.displayLarge
            } else {
                MaterialTheme.typography.displaySmall
            },
            color = Color.White,
        )

        OnboardingGuidePanel(
            page = page,
            pageIndex = pageIndex,
            pageCount = onboardingGuidePages.size,
            isLast = isLast,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(horizontal = 20.dp)
                .padding(top = panelTopPadding),
            onSkip = onFinish,
            onNext = {
                if (isLast) {
                    onFinish()
                } else {
                    pageIndex += 1
                }
            },
        )
    }
}

private data class OnboardingGuidePage(
    val title: String,
    val body: String,
    val spotlight: (Size) -> Rect,
    val arrowOffset: (Size) -> Offset,
    val arrowText: String = "↓",
    val panelOnTop: Boolean = false,
    val bodyStrong: Boolean = false,
    val arrowLarge: Boolean = false,
)

private val onboardingGuidePages = listOf(
    OnboardingGuidePage(
        title = "自作タグを作成",
        body = "＋を押すと、自分用のタグを作れます。保存するURLを用途ごとに整理できます。",
        spotlight = { _ -> Rect(left = 42f, top = 320f, right = 231f, bottom = 441f) },
        arrowOffset = { _ -> Offset(220f, 386f) },
        arrowText = "↖",
    ),
    OnboardingGuidePage(
        title = "タグを移動",
        body = "タグを長押ししたまま左右へ動かすと、好きな順番に並び替えできます。",
        spotlight = { size -> Rect(left = 252f, top = 329f, right = size.width - 28f, bottom = 450f) },
        arrowOffset = { size -> Offset(size.width * 0.50f - 28f, 461f) },
        arrowText = "↑",
    ),
    OnboardingGuidePage(
        title = "共有タグ",
        body = "共有タグはサインイン後に使えます。招待されたタグのURL一覧だけを端末間で同期します。",
        spotlight = { _ -> Rect(left = 42f, top = 548f, right = 231f, bottom = 660f) },
        arrowOffset = { _ -> Offset(220f, 610f) },
        arrowText = "↖",
    ),
    OnboardingGuidePage(
        title = "問い合わせ場所",
        body = "共有タグクラウド画面から、不具合や改善点を送れます。",
        spotlight = { size -> Rect(left = 42f, top = size.height - 740f, right = size.width - 42f, bottom = size.height - 614f) },
        arrowOffset = { size -> Offset(size.width * 0.50f - 47f, size.height - 970f) },
        panelOnTop = true,
        arrowLarge = true,
    ),
    OnboardingGuidePage(
        title = "称賛のお気持ちも受け付けております！",
        body = "あまり怒らないでね、、、",
        spotlight = { size -> Rect(left = 42f, top = size.height - 740f, right = size.width - 42f, bottom = size.height - 614f) },
        arrowOffset = { size -> Offset(size.width * 0.50f - 47f, size.height - 970f) },
        panelOnTop = true,
        bodyStrong = true,
        arrowLarge = true,
    ),
)

private fun guidePanelTopPaddingPx(
    spotlight: Rect,
    canvasSize: Size,
    panelOnTop: Boolean,
    pageIndex: Int,
): Float {
    val minimumTop = if (panelOnTop) 180f else 96f
    val maxTop = (canvasSize.height * if (panelOnTop) 0.36f else 0.38f)
        .coerceAtLeast(minimumTop)
    val preferredTop = if (panelOnTop) {
        spotlight.top - 760f
    } else {
        spotlight.bottom + 96f
    }
    val requestedOffset = when (pageIndex) {
        0, 1, 2 -> 215f
        3, 4 -> -248f
        else -> 0f
    }
    return (preferredTop + requestedOffset).coerceIn(minimumTop, maxTop)
}

@Composable
private fun OnboardingGuidePanel(
    page: OnboardingGuidePage,
    pageIndex: Int,
    pageCount: Int,
    isLast: Boolean,
    modifier: Modifier,
    onSkip: () -> Unit,
    onNext: () -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        tonalElevation = 8.dp,
        shadowElevation = 16.dp,
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                text = "${pageIndex + 1}/$pageCount",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = page.title,
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = page.body,
                style = if (page.bodyStrong) {
                    MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.ExtraBold)
                } else {
                    MaterialTheme.typography.bodyLarge
                },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onSkip) {
                    Text("スキップ")
                }
                Button(onClick = onNext) {
                    Text(if (isLast) "はじめる" else "次へ")
                }
            }
        }
    }
}
