package jp.mimac.urlsaver.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.content.pm.PackageManager
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.foundation.Image
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.outlined.LinkOff
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Sell
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import java.util.UUID
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import jp.mimac.urlsaver.BuildConfig
import jp.mimac.urlsaver.R
import jp.mimac.urlsaver.data.ChatGptPersonalLinkSyncSettings
import jp.mimac.urlsaver.data.ChatGptSyncEligibilitySnapshot
import jp.mimac.urlsaver.data.ChatGptSyncResult
import jp.mimac.urlsaver.domain.SharedTagAccountDeletionResult
import jp.mimac.urlsaver.domain.SharedTagAuthResult
import jp.mimac.urlsaver.domain.BillingPeriod
import jp.mimac.urlsaver.domain.FeatureEntitlements
import jp.mimac.urlsaver.domain.PlanType
import jp.mimac.urlsaver.domain.SharedTagInviteAcceptanceResult
import jp.mimac.urlsaver.domain.SharedTagInvitePreviewResult
import jp.mimac.urlsaver.domain.LinkedIdentityProvider
import jp.mimac.urlsaver.domain.LinkedIdentityProviderRefreshFailure
import jp.mimac.urlsaver.domain.LinkedIdentityProviderState
import jp.mimac.urlsaver.domain.UsageSummary
import jp.mimac.urlsaver.domain.isPaidCourse
import jp.mimac.urlsaver.domain.HomeBackgroundStyle
import jp.mimac.urlsaver.ui.theme.homeBackgroundGradientColors
import jp.mimac.urlsaver.ui.theme.homeBackgroundLabel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val CONTACT_SUPPORT_SUCCESS_MESSAGE = "問い合わせを受け付けました"

internal enum class ChatGptSyncPendingAction {
    ENABLE,
    DISABLE,
    SYNC_NOW,
}

internal fun chatGptSyncConfirmationBody(
    action: ChatGptSyncPendingAction,
    eligibility: ChatGptSyncEligibilitySnapshot,
): String {
    if (action == ChatGptSyncPendingAction.DISABLE) {
        return "同期をオフにします。\n" +
            "対象 ${eligibility.eligibleEntries.size}件。\n" +
            "除外 ${eligibility.excludedCount}件。\n" +
            "今回の送信なし。以後、リンクは送信されません。"
    }
    val hosts = eligibility.eligibleEntries
        .map { it.normalizedHost }
        .distinct()
        .sorted()
    val targetSummary = when {
        hosts.isEmpty() -> "送信対象はありません"
        hosts.size <= 3 -> hosts.joinToString("、")
        else -> "${hosts.take(3).joinToString("、")}、ほか${hosts.size - 3}ホスト"
    }
    val enablePrefix = if (action == ChatGptSyncPendingAction.ENABLE) "同期をオンにし、" else ""
    return "${enablePrefix}対象 ${eligibility.eligibleEntries.size}件を同期します。\n" +
        "除外 ${eligibility.excludedCount}件。\n" +
        "送信対象概要: $targetSummary\n" +
        "本文やプロンプトは送信しません。"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SharedTagCloudAuthScreen(
    viewModel: SharedTagAuthViewModel,
    homeBackgroundStyle: HomeBackgroundStyle,
    onHomeBackgroundStyleChange: (HomeBackgroundStyle) -> Unit,
    onBack: () -> Unit,
    initialPromoCode: String? = null,
) {
    val context = LocalContext.current
    val cloudState by viewModel.cloudState.collectAsStateWithLifecycle()
    val linkedIdentityProviders by viewModel.linkedIdentityProviders.collectAsStateWithLifecycle()
    val authSessionRevision by viewModel.authSessionRevision.collectAsStateWithLifecycle()
    val profile by viewModel.profile.collectAsStateWithLifecycle()
    val usageSummary by viewModel.usageSummary.collectAsStateWithLifecycle()
    val pendingInvite by viewModel.pendingInvite.collectAsStateWithLifecycle()
    val entitlements by viewModel.entitlements.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val chatGptSyncSettings by viewModel.chatGptSyncSettings.collectAsStateWithLifecycle()
    val chatGptSyncEligibility by viewModel.chatGptSyncEligibility.collectAsStateWithLifecycle()
    val localCleanupPending by viewModel.localAccountCleanupPending.collectAsStateWithLifecycle()
    val aiTransparencyViewModel = if (BuildConfig.DEBUG && BuildConfig.AI_TRANSPARENCY_ENABLED) {
        viewModel<AiTransparencyViewModel>(
            key = "profile_ai_transparency",
            factory = SimpleFactory {
                AiTransparencyViewModel(context.appContainer().aiTransparencyRepository)
            },
        )
    } else {
        null
    }

    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var displayName by remember { mutableStateOf("") }
    var profileMessage by remember { mutableStateOf<String?>(null) }
    var accountMessage by remember { mutableStateOf<String?>(null) }
    var planMessage by remember { mutableStateOf<String?>(null) }
    var supportMessage by remember { mutableStateOf<String?>(null) }
    var isSubmitting by remember { mutableStateOf(false) }
    var isSavingProfile by remember { mutableStateOf(false) }
    var profileMessageIsError by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var isRetryingLocalCleanup by remember { mutableStateOf(false) }
    var showContactPage by remember { mutableStateOf(false) }
    var contactEmail by remember { mutableStateOf("") }
    var contactName by remember { mutableStateOf("") }
    var contactBody by remember { mutableStateOf("") }
    var contactIdempotencyKey by remember { mutableStateOf(UUID.randomUUID().toString()) }
    var contactAttemptFingerprint by remember { mutableStateOf<String?>(null) }
    var contactError by remember { mutableStateOf<String?>(null) }
    var isSendingContact by remember { mutableStateOf(false) }
    var promoCode by remember { mutableStateOf(initialPromoCode.orEmpty()) }
    var promoMessage by remember { mutableStateOf<String?>(null) }
    var isRedeemingPromoCode by remember { mutableStateOf(false) }
    var isPurchasing by remember { mutableStateOf(false) }
    var showAiTransparencySheet by remember { mutableStateOf(false) }
    var showDisplayColorPicker by rememberSaveable { mutableStateOf(false) }
    var showUsageDetails by remember { mutableStateOf(false) }
    var showPlanDetails by rememberSaveable { mutableStateOf(false) }
    var showAccountDetails by rememberSaveable { mutableStateOf(false) }
    var showChatGptSyncDetails by rememberSaveable { mutableStateOf(false) }
    var draftAvatarBase64 by remember { mutableStateOf<String?>(null) }
    val avatarBitmap = remember(draftAvatarBase64) { draftAvatarBase64.toImageBitmapOrNull() }
    val isAvatarChanged = draftAvatarBase64 != profile.avatarBase64

    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val bytes = readAvatarBytes(context, uri)
            if (bytes == null) {
                profileMessage = "プロフィール写真を読み込めませんでした"
                profileMessageIsError = true
            } else {
                draftAvatarBase64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                profileMessage = "プロフィール写真を選択しました"
                profileMessageIsError = false
            }
        }
    }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        accountMessage = if (granted) {
            "共有タグの新着通知を有効にしました"
        } else {
            "通知は端末の設定からいつでも有効にできます"
        }
    }

    LaunchedEffect(profile.displayName, profile.avatarBase64) {
        if (isSavingProfile || profileMessageIsError) return@LaunchedEffect
        if (displayName != profile.displayName) displayName = profile.displayName
        if (draftAvatarBase64 != profile.avatarBase64) draftAvatarBase64 = profile.avatarBase64
    }

    LaunchedEffect(cloudState.isSignedIn, showAccountDetails, authSessionRevision) {
        if (cloudState.isSignedIn && showAccountDetails) {
            viewModel.refreshLinkedIdentityProviders()
        }
    }

    LaunchedEffect(initialPromoCode) {
        if (!initialPromoCode.isNullOrBlank()) {
            promoCode = initialPromoCode
            promoMessage = "メールの優待コードを読み込みました"
            showPlanDetails = true
        }
    }

    LaunchedEffect(Unit) {
        viewModel.refreshPendingInvite()
    }

    fun openContactPage() {
        contactEmail = cloudState.signedInEmail.orEmpty().ifBlank { email }
        contactName = profile.trimmedDisplayName
        contactBody = ""
        contactIdempotencyKey = UUID.randomUUID().toString()
        contactAttemptFingerprint = null
        contactError = null
        supportMessage = null
        showContactPage = true
    }

    fun contactInputChanged() {
        if (contactAttemptFingerprint != null) {
            contactIdempotencyKey = UUID.randomUUID().toString()
            contactAttemptFingerprint = null
        }
        contactError = null
    }

    fun sendContact() {
        if (isSendingContact) return
        contactAttemptFingerprint = listOf(
            contactEmail.trim(),
            contactName.trim(),
            contactBody.trim(),
        ).joinToString("\u0000")
        scope.launch {
            isSendingContact = true
            contactError = null
            try {
                when (val result = viewModel.sendContactSupport(
                    contactEmail,
                    contactName,
                    contactBody,
                    contactIdempotencyKey,
                )) {
                    ContactSupportUiResult.Success -> {
                        contactBody = ""
                        showContactPage = false
                        supportMessage = CONTACT_SUPPORT_SUCCESS_MESSAGE
                    }
                    is ContactSupportUiResult.Failure -> {
                        contactError = result.message
                    }
                }
            } finally {
                isSendingContact = false
            }
        }
    }

    fun redeemPromoCode() {
        scope.launch {
            isRedeemingPromoCode = true
            promoMessage = when (val result = viewModel.redeemPromoCode(promoCode)) {
                PromoCodeApplyResult.Success -> {
                    promoCode = ""
                    "優待Proを受け取りました。使用状況に反映されます。"
                }
                PromoCodeApplyResult.InvalidCode -> "優待コードを入力してください"
                PromoCodeApplyResult.AuthRequired -> "優待を受け取るにはサインインが必要です"
                is PromoCodeApplyResult.Failure -> result.message
            }
            isRedeemingPromoCode = false
        }
    }

    fun purchasePaidCourse(planType: PlanType, billingPeriod: BillingPeriod) {
        showPlanDetails = true
        val activity = context.findActivity()
        if (activity == null) {
            planMessage = "購入画面を開けませんでした"
            return
        }
        scope.launch {
            isPurchasing = true
            planMessage = when (val result = viewModel.purchasePaidCourse(activity, planType, billingPeriod)) {
                PaidCoursePurchaseUiResult.Started -> "購入画面を開きました"
                PaidCoursePurchaseUiResult.AuthRequired -> "購入にはサインインが必要です"
                PaidCoursePurchaseUiResult.ProductUnavailable -> "このコースは現在購入できません"
                PaidCoursePurchaseUiResult.Unavailable -> "購入機能がこのビルドで利用できません"
                is PaidCoursePurchaseUiResult.Failure -> result.message.ifBlank { "購入画面を開けませんでした" }
            }
            isPurchasing = false
        }
    }

    fun acceptPendingInvite() {
        scope.launch {
            isSubmitting = true
            accountMessage = when (val result = viewModel.acceptPendingInvite()) {
                is SharedTagInviteAcceptanceResult.Success -> {
                    if (result.inviteType == jp.mimac.urlsaver.domain.SharedInviteType.GROUP) {
                        "保留中のグループ招待に参加しました。同期後にグループ一覧へ表示されます。"
                    } else {
                        "保留中の共有タグ招待に参加しました。同期後にタグ一覧へ表示されます。"
                    }
                }
                SharedTagInviteAcceptanceResult.AuthRequired -> "参加するにはサインインが必要です"
                SharedTagInviteAcceptanceResult.InvalidInvite -> "保留中の招待リンクが無効か期限切れです"
                is SharedTagInviteAcceptanceResult.Failure -> result.message
            }
            showAccountDetails = true
            isSubmitting = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (showContactPage) "問い合わせ" else "プロフィール") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    scrolledContainerColor = MaterialTheme.colorScheme.background,
                ),
                windowInsets = TopAppBarDefaults.windowInsets.only(WindowInsetsSides.Horizontal),
                navigationIcon = {
                    IconButton(onClick = { if (showContactPage) showContactPage = false else onBack() }) {
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
                .padding(start = 20.dp, top = 8.dp, end = 20.dp, bottom = 24.dp),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 420.dp)
                    .fillMaxHeight()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (showContactPage) {
                    ContactSupportPage(
                        email = contactEmail,
                        name = contactName,
                        body = contactBody,
                        error = contactError,
                        onEmailChange = {
                            contactEmail = it
                            contactInputChanged()
                        },
                        onNameChange = {
                            contactName = it
                            contactInputChanged()
                        },
                        onBodyChange = {
                            contactBody = it
                            contactInputChanged()
                        },
                        isSending = isSendingContact,
                        onSend = ::sendContact,
                    )
                    return@Column
                }

                ProfileCard(
                    displayName = profile.trimmedDisplayName,
                    signedInEmail = cloudState.signedInEmail,
                    draftDisplayName = displayName,
                    avatarBitmap = avatarBitmap,
                    canSaveProfile = displayName.trim() != profile.trimmedDisplayName || isAvatarChanged,
                    isAvatarChanged = isAvatarChanged,
                    isSaving = isSavingProfile,
                    profileMessage = profileMessage,
                    profileMessageIsError = profileMessageIsError,
                    onDisplayNameChange = {
                        displayName = it
                        profileMessage = null
                        profileMessageIsError = false
                    },
                    onSaveProfile = { onSaved ->
                        if (!isSavingProfile) {
                            isSavingProfile = true
                            scope.launch {
                                try {
                                    viewModel.saveProfile(displayName, draftAvatarBase64)
                                    profileMessage = "プロフィールを保存しました"
                                    profileMessageIsError = false
                                    onSaved()
                                } catch (cancellation: CancellationException) {
                                    throw cancellation
                                } catch (_: Exception) {
                                    profileMessage = "プロフィールを保存できませんでした"
                                    profileMessageIsError = true
                                } finally {
                                    isSavingProfile = false
                                }
                            }
                        }
                    },
                    onPickAvatar = { imagePicker.launch("image/*") },
                    onRemoveAvatar = {
                        draftAvatarBase64 = null
                        profileMessage = "プロフィール写真を削除対象にしました"
                        profileMessageIsError = false
                    },
                    onCancelAvatarChange = {
                        draftAvatarBase64 = profile.avatarBase64
                        profileMessage = null
                        profileMessageIsError = false
                    },
                )

                localCleanupPending?.let { pending ->
                    LocalAccountCleanupCard(
                        pending = pending,
                        isRetrying = isRetryingLocalCleanup,
                        onRetry = {
                            scope.launch {
                                isRetryingLocalCleanup = true
                                try {
                                    accountMessage = when (val result = viewModel.retryLocalAccountCleanup()) {
                                        SharedTagAccountDeletionResult.Success -> "アカウント削除と端末内データの消去が完了しました"
                                        is SharedTagAccountDeletionResult.LocalCleanupRequired -> localCleanupStatusMessage(result)
                                        else -> "アカウントは削除済みです。端末内データの消去を再試行できませんでした"
                                    }
                                } finally {
                                    isRetryingLocalCleanup = false
                                }
                            }
                        },
                    )
                }

                pendingInvite?.let {
                    PendingInviteResumeCard(
                        isSignedIn = cloudState.isSignedIn,
                        isSubmitting = isSubmitting,
                        onAccept = ::acceptPendingInvite,
                        onClear = {
                            viewModel.clearPendingInvite()
                            accountMessage = "保留中の招待を解除しました"
                        },
                    )
                }

                UsageSummaryCard(
                    usageSummary = usageSummary,
                    backgroundStyle = homeBackgroundStyle,
                    planLabel = profilePlanLabel(entitlements.planType),
                    expanded = showUsageDetails,
                    onToggleExpanded = { showUsageDetails = !showUsageDetails },
                )

                DisplaySettingsCard(
                    selectedStyle = homeBackgroundStyle,
                    onOpenColorPicker = { showDisplayColorPicker = true },
                )

                ProfileAccountAndShareCard(
                    cloudState = cloudState,
                    expanded = showAccountDetails,
                    accountMessage = accountMessage,
                    onToggleAccountDetails = { showAccountDetails = !showAccountDetails },
                    planDetailsExpanded = showPlanDetails,
                    onTogglePlanDetails = { showPlanDetails = !showPlanDetails },
                    accountDetails = {
                        if (!cloudState.isConfigured) {
                            Text(
                                text = "このビルドでは共有タグのクラウド同期が設定されていません。",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else if (cloudState.isSignedIn) {
                            Text("ログイン方法を追加", style = MaterialTheme.typography.titleSmall)
                            Text("現在のアカウントに連携し、Android・iPhoneで同じ保存内容を使えます。", style = MaterialTheme.typography.bodySmall)
                            listOf(LinkedIdentityProvider.GOOGLE, LinkedIdentityProvider.APPLE).forEach { provider ->
                                LinkedIdentityProviderAccountRow(
                                    provider = provider,
                                    state = linkedIdentityProviders,
                                    isSubmitting = isSubmitting,
                                    onLink = {
                                        scope.launch {
                                            isSubmitting = true
                                            val result = if (provider == LinkedIdentityProvider.GOOGLE) {
                                                viewModel.googleIdentityLinkUrl()
                                            } else {
                                                viewModel.appleIdentityLinkUrl()
                                            }
                                            result.fold(
                                                onSuccess = { url ->
                                                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                                                        .onSuccess {
                                                            accountMessage = "${provider.displayName} の連携を完了してください。元のアカウントは保持しています。"
                                                        }
                                                        .onFailure { accountMessage = "${provider.displayName} の連携画面を開けませんでした" }
                                                },
                                                onFailure = { accountMessage = it.message ?: "ログイン方法を追加できませんでした" },
                                            )
                                            isSubmitting = false
                                        }
                                    },
                                )
                            }
                            LinkedIdentityProviderStatusMessage(
                                state = linkedIdentityProviders,
                                onRetry = { scope.launch { viewModel.refreshLinkedIdentityProviders() } },
                            )
                            OutlinedButton(
                                onClick = {},
                                enabled = false,
                                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("auth_link_chatgpt"),
                            ) {
                                AuthProviderButtonContent(
                                    iconRes = R.drawable.ai_chatgpt,
                                    label = "ChatGPTで続ける（準備中）",
                                    darkIconBacking = true,
                                )
                            }
                            TextButton(onClick = { scope.launch { viewModel.cancelIdentityLink(); accountMessage = "ログイン方法の追加をキャンセルしました。元のアカウントは保持しています。" } }, enabled = !isSubmitting) { Text("ログイン方法の追加をキャンセル") }
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                                PackageManager.PERMISSION_GRANTED
                            ) {
                                OutlinedButton(
                                    onClick = {
                                        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                    },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(min = 48.dp),
                                ) {
                                    Text("共有タグの新着通知を有効にする")
                                }
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                TextButton(
                                    onClick = {
                                        scope.launch {
                                            viewModel.signOut()
                                            accountMessage = "サインアウトしました"
                                        }
                                    },
                                    modifier = Modifier.weight(1f),
                                ) {
                                    Text("サインアウト")
                                }
                                TextButton(
                                    onClick = { showDeleteDialog = true },
                                    modifier = Modifier.weight(1f),
                                ) {
                                    Text(
                                        "アカウント削除",
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                }
                            }
                        } else {
                            Text(
                                text = "共有タグに参加するにはサインインが必要です",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            SharedTagAuthForm(
                                email = email,
                                password = password,
                                isSubmitting = isSubmitting,
                                message = null,
                                onEmailChange = {
                                    email = it
                                    accountMessage = null
                                },
                                onPasswordChange = {
                                    password = it
                                    accountMessage = null
                                },
                                onGoogleSignIn = {
                                    val url = viewModel.googleOAuthUrl()
                                    if (url == null) {
                                        accountMessage = "Googleサインインを開始できませんでした。クラウド設定を確認してください。"
                                        return@SharedTagAuthForm
                                    }
                                    runCatching {
                                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                                    }.onFailure {
                                        accountMessage = "Googleサインイン画面を開けませんでした"
                                    }
                                },
                                onAppleSignIn = {
                                    val url = viewModel.appleOAuthUrl()
                                    if (url == null) accountMessage = "Appleサインインを開始できませんでした"
                                    else runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                                        .onFailure { accountMessage = "Appleサインイン画面を開けませんでした" }
                                },
                                onSignIn = {
                                    scope.launch {
                                        isSubmitting = true
                                        accountMessage = viewModel.signIn(email, password).toUiMessage(
                                            success = "サインインしました。プロフィールと共有タグを使えます。",
                                            emailConfirmation = "確認メールの手続きが必要です。メール確認後にサインインしてください。",
                                        )
                                        showAccountDetails = true
                                        isSubmitting = false
                                    }
                                },
                                onSignUp = {
                                    scope.launch {
                                        isSubmitting = true
                                        accountMessage = viewModel.signUp(email, password).toUiMessage(
                                            success = "新規登録が完了しました。プロフィールと共有タグを使えます。",
                                            emailConfirmation = "確認メールを送信しました。確認後にサインインしてください。",
                                        )
                                        showAccountDetails = true
                                        isSubmitting = false
                                    }
                                },
                                onResendConfirmation = {
                                    scope.launch {
                                        isSubmitting = true
                                        accountMessage = viewModel.resendEmailConfirmation(email).toUiMessage(
                                            success = "確認メールを再送しました。メール確認後にサインインしてください。",
                                            emailConfirmation = "確認メールを再送しました。メール確認後にサインインしてください。",
                                        )
                                        isSubmitting = false
                                    }
                                },
                                onPasswordRecovery = {
                                    scope.launch {
                                        isSubmitting = true
                                        accountMessage = viewModel.sendPasswordRecovery(email).toUiMessage(
                                            success = "パスワード再設定メールを送信しました。",
                                            emailConfirmation = "パスワード再設定メールを送信しました。メールの案内に従ってください。",
                                        )
                                        isSubmitting = false
                                    }
                                },
                            )
                        }
                    },
                    planDetails = {
                        if (cloudState.isConfigured && !entitlements.planType.isPaidCourse) {
                            PaidCourseSection(
                                isEnabled = !isSubmitting && !isPurchasing,
                                isPurchasing = isPurchasing,
                                showMonthlyOptions = !entitlements.planType.isPaidCourse,
                                onPurchase = ::purchasePaidCourse,
                            )
                        } else if (!cloudState.isConfigured) {
                            Text(
                                text = "このビルドでは共有タグのクラウド同期が設定されていません。",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        OutlinedButton(onClick = {
                            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/account/subscriptions"))) }
                                .onFailure { planMessage = "契約管理を開けませんでした" }
                        }, modifier = Modifier.fillMaxWidth()) { Text("契約を管理") }
                        OutlinedButton(onClick = { scope.launch {
                            isSubmitting = true
                            planMessage = viewModel.restorePaidCourses().fold({ "購入状況を確認しました" }, { it.message ?: "購入を復元できませんでした" })
                            isSubmitting = false
                        } }, enabled = !isSubmitting && cloudState.isSignedIn, modifier = Modifier.fillMaxWidth()) { Text("購入を復元") }
                        PromoCodeSection(
                            promoCode = promoCode,
                            promoMessage = promoMessage,
                            isRedeemingPromoCode = isRedeemingPromoCode,
                            canRedeemPromoCode = viewModel.canApplyPromoCode(promoCode),
                            onPromoCodeChange = {
                                promoCode = it
                                promoMessage = null
                            },
                            onRedeemPromoCode = ::redeemPromoCode,
                        )
                        planMessage?.let { ProfileStatusMessage(message = it) }
                    },
                    chatGptContent = {
                        ChatGptPersonalLinkSyncCard(
                            viewModel = viewModel,
                            cloudState = cloudState,
                            settings = chatGptSyncSettings,
                            eligibility = chatGptSyncEligibility,
                            expanded = showChatGptSyncDetails,
                            onToggleExpanded = { showChatGptSyncDetails = !showChatGptSyncDetails },
                        )
                    },
                )

                if (aiTransparencyViewModel != null) {
                    OutlinedButton(
                        onClick = { showAiTransparencySheet = true },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp),
                    ) {
                        Text("AI動作の確認（デバッグ）")
                    }
                }

                SupportCard(
                    message = supportMessage,
                    onOpenContact = ::openContactPage,
                )
            }
        }
    }

    if (showAiTransparencySheet && aiTransparencyViewModel != null) {
        ModalBottomSheet(
            onDismissRequest = { showAiTransparencySheet = false },
        ) {
            AiTransparencyPreviewScreen(
                viewModel = aiTransparencyViewModel,
                modifier = Modifier.fillMaxHeight(),
            )
        }
    }

    HomeBackgroundColorSheet(
        visible = showDisplayColorPicker,
        selectedStyle = homeBackgroundStyle,
        onDismiss = { showDisplayColorPicker = false },
        onSelect = { style ->
            onHomeBackgroundStyleChange(style)
            showDisplayColorPicker = false
        },
    )

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            isSubmitting = true
                            showDeleteDialog = false
                            accountMessage = when (val result = viewModel.deleteAccount()) {
                                SharedTagAccountDeletionResult.Success -> "アカウントを削除しました"
                                SharedTagAccountDeletionResult.AuthRequired -> "アカウント削除にはサインインが必要です"
                                SharedTagAccountDeletionResult.OwnerTransferRequired ->
                                    "共有タグ詳細の参加者からオーナー権限を先に移譲してください"
                                is SharedTagAccountDeletionResult.LocalCleanupRequired -> {
                                    localCleanupStatusMessage(result)
                                }
                                is SharedTagAccountDeletionResult.Failure -> result.message
                            }
                            showAccountDetails = true
                            isSubmitting = false
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
            title = { Text("アカウント削除") },
            text = {
                Text("共有タグのクラウドアカウントを削除します。他のメンバーがいる共有タグのオーナー権限は、共有タグ詳細の参加者から先に移譲してください。")
            },
        )
    }
}

private fun localCleanupStatusMessage(
    result: SharedTagAccountDeletionResult.LocalCleanupRequired,
): String {
    val pendingItems = buildList {
        if (result.aiDataPending) add("端末内AIデータ")
        if (result.sessionPending) add("ログイン情報")
        if (result.syncWorkCancellationPending) add("共有タグ同期処理")
        if (result.sharedDataCleanupPending) add("共有タグ同期データ")
        if (result.pendingInviteCleanupPending) add("保留中の招待")
        if (result.entitlementCleanupPending) add("利用権限キャッシュ")
        if (result.personalLinkSettingsCleanupPending) add("ChatGPT連携設定")
    }
    val pendingLabel = pendingItems.joinToString("と").ifBlank { "端末内データ" }
    return "アカウントは削除済みです。${pendingLabel}の消去が完了していません。"
}

@Composable
private fun PromoCodeSection(
    promoCode: String,
    promoMessage: String?,
    isRedeemingPromoCode: Boolean,
    canRedeemPromoCode: Boolean,
    onPromoCodeChange: (String) -> Unit,
    onRedeemPromoCode: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "優待コード",
            style = MaterialTheme.typography.titleMedium,
        )
        OutlinedTextField(
            value = promoCode,
            onValueChange = onPromoCodeChange,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        Button(
            onClick = onRedeemPromoCode,
            enabled = canRedeemPromoCode && !isRedeemingPromoCode,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
        ) {
            Text(if (isRedeemingPromoCode) "受け取り中…" else "優待Proを受け取る")
        }
        promoMessage?.let {
            Text(
                text = it,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun PendingInviteResumeCard(
    isSignedIn: Boolean,
    isSubmitting: Boolean,
    onAccept: () -> Unit,
    onClear: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        tonalElevation = 1.dp,
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = "保留中の招待",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = if (isSignedIn) {
                    "保留中の共有招待に参加できます。"
                } else {
                    "招待を保留しています。サインイン後に参加できます。"
                },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = onAccept,
                    enabled = isSignedIn && !isSubmitting,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(if (isSignedIn) "参加する" else "サインイン後に参加")
                }
                TextButton(
                    onClick = onClear,
                    enabled = !isSubmitting,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("保留を解除")
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SharedTagInviteScreen(
    viewModel: SharedTagInviteViewModel,
    onBack: () -> Unit,
    onInviteJoined: (Long) -> Unit,
) {
    val context = LocalContext.current
    val cloudState by viewModel.cloudState.collectAsStateWithLifecycle()
    val previewResult by viewModel.previewResult.collectAsStateWithLifecycle()
    val joinedTag by viewModel.joinedTag.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var accepted by remember { mutableStateOf(false) }
    var isSubmitting by remember { mutableStateOf(false) }
    var showAuthForm by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.loadPreview()
    }

    LaunchedEffect(joinedTag?.id) {
        val tagId = joinedTag?.id ?: return@LaunchedEffect
        onInviteJoined(tagId)
    }

    fun acceptInvite() {
        scope.launch {
            isSubmitting = true
            when (val result = viewModel.acceptInvite()) {
                is SharedTagInviteAcceptanceResult.Success -> {
                    accepted = true
                    message = if (result.inviteType == jp.mimac.urlsaver.domain.SharedInviteType.GROUP) {
                        "グループ「${result.displayName}」に参加しました。同期後にグループ一覧へ表示されます。"
                    } else {
                        "参加しました。同期が終わるとタグを開きます。"
                    }
                }
                SharedTagInviteAcceptanceResult.AuthRequired -> {
                    viewModel.savePendingInvite()
                    message = "参加するにはサインインが必要です"
                }
                SharedTagInviteAcceptanceResult.InvalidInvite -> {
                    message = "招待リンクが無効か期限切れです"
                }
                is SharedTagInviteAcceptanceResult.Failure -> {
                    message = result.message
                }
            }
            isSubmitting = false
        }
    }

    fun handleJoinClick() {
        if (!cloudState.isSignedIn) {
            viewModel.savePendingInvite()
            showAuthForm = true
            message = "招待を保留しました。サインイン後にこの画面から参加できます。"
            return
        }
        acceptInvite()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {},
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
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(
                modifier = Modifier.widthIn(max = 420.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                if (!viewModel.hasInviteToken()) {
                    InviteInfoState("共有招待リンクを開けませんでした")
                    return@Column
                }

                if (!cloudState.isConfigured) {
                    InviteInfoState("このビルドではクラウド共有が設定されていません。")
                    return@Column
                }

                when (val preview = previewResult) {
                    null -> {
                        CircularProgressIndicator()
                        Text(
                            text = "招待リンクを確認しています",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    SharedTagInvitePreviewResult.InvalidInvite -> {
                        InviteInfoState("招待リンクが無効か期限切れです")
                    }
                    is SharedTagInvitePreviewResult.Failure -> {
                        InviteInfoState(preview.message)
                    }
                    is SharedTagInvitePreviewResult.Success -> {
                        SharedTagInviteConfirmContent(
                            displayName = preview.displayName,
                            isGroup = preview.inviteType == jp.mimac.urlsaver.domain.SharedInviteType.GROUP,
                            isSubmitting = isSubmitting,
                            accepted = accepted,
                            message = message,
                            onJoin = ::handleJoinClick,
                            onDecline = onBack,
                        )
                    }
                }

                if (showAuthForm && previewResult is SharedTagInvitePreviewResult.Success && !cloudState.isSignedIn) {
                    SharedTagAuthForm(
                        email = email,
                        password = password,
                        isSubmitting = isSubmitting,
                        message = message,
                        onEmailChange = {
                            email = it
                            message = null
                        },
                        onPasswordChange = {
                            password = it
                            message = null
                        },
                        onGoogleSignIn = {
                            val url = viewModel.googleOAuthUrl()
                            if (url == null) {
                                message = "Googleサインインを開始できませんでした。クラウド設定を確認してください。"
                                return@SharedTagAuthForm
                            }
                            runCatching {
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                            }.onFailure {
                                message = "Googleサインイン画面を開けませんでした"
                            }
                        },
                        onAppleSignIn = {
                            val url = viewModel.appleOAuthUrl()
                            if (url == null) message = "Appleサインインを開始できませんでした"
                            else runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                                .onFailure { message = "Appleサインイン画面を開けませんでした" }
                        },
                        onSignIn = {
                            scope.launch {
                                isSubmitting = true
                                when (val result = viewModel.signIn(email, password)) {
                                    is SharedTagAuthResult.Success -> {
                                        message = "サインインしました。参加しています…"
                                        showAuthForm = false
                                        isSubmitting = false
                                        acceptInvite()
                                    }
                                    else -> {
                                        message = result.toUiMessage(
                                            success = "サインインしました",
                                            emailConfirmation = "確認メールの手続きが必要です。メール確認後にサインインしてください。",
                                        )
                                        isSubmitting = false
                                    }
                                }
                            }
                        },
                        onSignUp = {
                            scope.launch {
                                isSubmitting = true
                                val result = viewModel.signUp(email, password)
                                message = result.toUiMessage(
                                    success = "アカウントを作成しました。参加しています…",
                                    emailConfirmation = "確認メールを送信しました。確認後にこの招待リンクをもう一度開いてください。",
                                )
                                if (result is SharedTagAuthResult.Success) {
                                    showAuthForm = false
                                    isSubmitting = false
                                    acceptInvite()
                                } else {
                                    isSubmitting = false
                                }
                            }
                        },
                        onResendConfirmation = {
                            scope.launch {
                                isSubmitting = true
                                message = viewModel.resendEmailConfirmation(email).toUiMessage(
                                    success = "確認メールを再送しました。メール確認後にこの招待リンクをもう一度開いてください。",
                                    emailConfirmation = "確認メールを再送しました。メール確認後にこの招待リンクをもう一度開いてください。",
                                )
                                isSubmitting = false
                            }
                        },
                        onPasswordRecovery = {
                            scope.launch {
                                isSubmitting = true
                                message = viewModel.sendPasswordRecovery(email).toUiMessage(
                                    success = "パスワード再設定メールを送信しました。",
                                    emailConfirmation = "パスワード再設定メールを送信しました。メールの案内に従ってください。",
                                )
                                isSubmitting = false
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun SharedTagInviteConfirmContent(
    displayName: String,
    isGroup: Boolean,
    isSubmitting: Boolean,
    accepted: Boolean,
    message: String?,
    onJoin: () -> Unit,
    onDecline: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = if (isGroup) "グループに参加しますか？" else "共有タグに参加しますか？",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        Text(
            text = "「$displayName」",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        Text(
            text = if (isGroup) {
                "参加すると、このグループ内の共有タグとURL一覧が同期されます。"
            } else {
                "参加すると、この共有タグのURL一覧が同期されます。"
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        Button(
            onClick = onJoin,
            enabled = !isSubmitting && !accepted,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (accepted) "同期中…" else "参加する")
        }
        OutlinedButton(
            onClick = onDecline,
            enabled = !isSubmitting && !accepted,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("参加しない")
        }
        if (accepted) {
            CircularProgressIndicator()
        }
        message?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
    }
}

@Composable
private fun ProfileStatusMessage(message: String, isError: Boolean = false) {
    val isContactSupportSuccess = message == CONTACT_SUPPORT_SUCCESS_MESSAGE
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = if (isError) {
            MaterialTheme.colorScheme.errorContainer
        } else if (isContactSupportSuccess) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (isContactSupportSuccess) {
                Icon(
                    imageVector = Icons.Outlined.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(22.dp),
                )
            }
            Text(
                text = message,
                style = if (isContactSupportSuccess) {
                    MaterialTheme.typography.titleSmall
                } else {
                    MaterialTheme.typography.bodyMedium
                },
                fontWeight = if (isContactSupportSuccess) FontWeight.Bold else FontWeight.Normal,
                color = if (isError) {
                    MaterialTheme.colorScheme.onErrorContainer
                } else if (isContactSupportSuccess) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
    }
}

@Composable
private fun LocalAccountCleanupCard(
    pending: SharedTagAccountDeletionResult.LocalCleanupRequired,
    isRetrying: Boolean,
    onRetry: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.errorContainer,
        shape = MaterialTheme.shapes.large,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("アカウントは削除済みです", style = MaterialTheme.typography.titleMedium)
            Text(localCleanupStatusMessage(pending))
            OutlinedButton(
                onClick = onRetry,
                enabled = !isRetrying,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp),
            ) {
                Text(if (isRetrying) "端末内データを消去中…" else "端末内データの消去を再試行")
            }
        }
    }
}

@Composable
private fun DisplaySettingsCard(
    selectedStyle: HomeBackgroundStyle,
    onOpenColorPicker: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("表示設定", style = MaterialTheme.typography.titleMedium)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(MaterialTheme.shapes.medium)
                    .clickable(onClick = onOpenColorPicker)
                    .heightIn(min = 56.dp)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Surface(
                    modifier = Modifier.size(40.dp),
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.secondaryContainer,
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Outlined.Palette,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                    }
                }
                Text("カラー", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                Box(
                    modifier = Modifier
                        .size(30.dp)
                        .clip(CircleShape)
                        .background(Brush.verticalGradient(homeBackgroundGradientColors(selectedStyle)))
                        .border(
                            1.dp,
                            MaterialTheme.colorScheme.outlineVariant,
                            CircleShape,
                        ),
                )
                Text(
                    text = homeBackgroundLabel(selectedStyle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Icon(
                    imageVector = Icons.Outlined.ExpandMore,
                    contentDescription = "カラーを選ぶ",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeBackgroundColorSheet(
    visible: Boolean,
    selectedStyle: HomeBackgroundStyle,
    onDismiss: () -> Unit,
    onSelect: (HomeBackgroundStyle) -> Unit,
) {
    if (!visible) return

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("表示カラー", style = MaterialTheme.typography.titleLarge)
            HomeBackgroundStyle.entries.forEach { style ->
                val selected = style == selectedStyle
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(MaterialTheme.shapes.medium)
                        .selectable(
                            selected = selected,
                            role = Role.RadioButton,
                            onClick = { onSelect(style) },
                        )
                        .heightIn(min = 56.dp)
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(MaterialTheme.shapes.small)
                            .background(Brush.verticalGradient(homeBackgroundGradientColors(style)))
                            .border(
                                width = if (selected) 2.dp else 1.dp,
                                color = if (selected) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.outlineVariant,
                                shape = MaterialTheme.shapes.small,
                            ),
                    )
                    Text(
                        text = homeBackgroundLabel(style),
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    if (selected) {
                        Icon(
                            imageVector = Icons.Outlined.Check,
                            contentDescription = "選択中",
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ProfileAccountAndShareCard(
    cloudState: jp.mimac.urlsaver.domain.SharedTagCloudState,
    expanded: Boolean,
    accountMessage: String?,
    onToggleAccountDetails: () -> Unit,
    planDetailsExpanded: Boolean,
    onTogglePlanDetails: () -> Unit,
    accountDetails: @Composable ColumnScope.() -> Unit,
    planDetails: @Composable ColumnScope.() -> Unit,
    chatGptContent: @Composable ColumnScope.() -> Unit,
) {
    val accountStatus = when {
        !cloudState.isConfigured -> "このビルドでは共有タグのクラウド同期が設定されていません。"
        cloudState.isSignedIn -> cloudState.signedInEmail?.takeIf { it.isNotBlank() } ?: "サインイン済み"
        else -> "未サインイン · 共有タグにはサインインが必要です"
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("アカウント・共有", style = MaterialTheme.typography.titleMedium)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(MaterialTheme.shapes.medium)
                    .clickable(enabled = cloudState.isConfigured, onClick = onToggleAccountDetails)
                    .heightIn(min = 60.dp)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Surface(
                    modifier = Modifier.size(40.dp),
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primaryContainer,
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Outlined.AccountCircle,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                }
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("共有タグのアカウント", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        text = accountStatus,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (cloudState.isConfigured) MaterialTheme.colorScheme.onSurfaceVariant
                        else MaterialTheme.colorScheme.error,
                        maxLines = 2,
                    )
                }
                if (cloudState.isConfigured) {
                    Icon(
                        imageVector = if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                        contentDescription = if (expanded) "アカウント設定を閉じる" else "アカウント設定を開く",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (expanded && cloudState.isConfigured) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp), content = accountDetails)
            }
            accountMessage?.let { ProfileStatusMessage(message = it) }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 60.dp)
                    .semantics(mergeDescendants = true) {
                        role = Role.Button
                        stateDescription = if (planDetailsExpanded) "展開済み" else "折りたたみ"
                    }
                    .clickable(onClick = onTogglePlanDetails)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Surface(
                    modifier = Modifier.size(40.dp),
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.tertiaryContainer,
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Outlined.Sell,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onTertiaryContainer,
                        )
                    }
                }
                Text(
                    text = "プラン・優待",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Icon(
                    imageVector = if (planDetailsExpanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                    contentDescription = if (planDetailsExpanded) "プラン・優待を閉じる" else "プラン・優待を開く",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (planDetailsExpanded) {
                Column(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    content = planDetails,
                )
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
            chatGptContent()
        }
    }
}

@Composable
private fun SupportCard(
    message: String?,
    onOpenContact: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("サポート", style = MaterialTheme.typography.titleMedium)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(MaterialTheme.shapes.medium)
                    .clickable(onClick = onOpenContact)
                    .heightIn(min = 56.dp)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Surface(
                    modifier = Modifier.size(40.dp),
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.tertiaryContainer,
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.HelpOutline,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onTertiaryContainer,
                        )
                    }
                }
                Text("問い合わせ", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                    contentDescription = "問い合わせを開く",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.graphicsLayer(rotationZ = 180f),
                )
            }
            message?.let { ProfileStatusMessage(message = it) }
        }
    }
}

@Composable
private fun ProfileCard(
    displayName: String,
    signedInEmail: String?,
    draftDisplayName: String,
    avatarBitmap: ImageBitmap?,
    canSaveProfile: Boolean,
    isAvatarChanged: Boolean,
    isSaving: Boolean,
    profileMessage: String?,
    profileMessageIsError: Boolean,
    onDisplayNameChange: (String) -> Unit,
    onSaveProfile: (onSaved: () -> Unit) -> Unit,
    onPickAvatar: () -> Unit,
    onRemoveAvatar: () -> Unit,
    onCancelAvatarChange: () -> Unit,
) {
    var isEditingDisplayName by rememberSaveable { mutableStateOf(false) }
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                ProfileAvatar(
                    avatarBitmap = avatarBitmap,
                    modifier = Modifier.size(72.dp),
                )
                TextButton(
                    onClick = onPickAvatar,
                    enabled = !isSaving,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text(if (avatarBitmap == null) "写真を追加" else "写真を変更")
                }
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                if (isEditingDisplayName) {
                    OutlinedTextField(
                        value = draftDisplayName,
                        onValueChange = onDisplayNameChange,
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text("表示名") },
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(
                            onClick = {
                                onDisplayNameChange(displayName)
                                isEditingDisplayName = false
                            },
                            modifier = Modifier.heightIn(min = 48.dp),
                        ) {
                            Text("キャンセル")
                        }
                        TextButton(
                            onClick = {
                                onSaveProfile { isEditingDisplayName = false }
                            },
                            enabled = canSaveProfile && !isSaving,
                            modifier = Modifier.heightIn(min = 48.dp),
                        ) {
                            Icon(Icons.Outlined.Check, contentDescription = null)
                            Spacer(Modifier.size(4.dp))
                            Text(if (isSaving) "保存中…" else "保存")
                        }
                    }
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = displayName.ifBlank { "プロフィール名" },
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.titleLarge,
                            maxLines = 2,
                        )
                        IconButton(
                            onClick = { isEditingDisplayName = true },
                            modifier = Modifier.size(48.dp),
                        ) {
                            Icon(Icons.Outlined.Edit, contentDescription = "表示名を編集")
                        }
                    }
                    signedInEmail?.takeIf { it.isNotBlank() }?.let { email ->
                        Text(
                            text = email,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (avatarBitmap != null) {
                TextButton(
                    onClick = onRemoveAvatar,
                    enabled = !isSaving,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text("写真を削除")
                }
            }
            if (isAvatarChanged) {
                TextButton(
                    onClick = onCancelAvatarChange,
                    enabled = !isSaving,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text("写真変更を取り消す")
                }
            }
        }
        if (!isEditingDisplayName && canSaveProfile) {
            Button(
                onClick = { onSaveProfile {} },
                enabled = !isSaving,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp),
            ) {
                Text(if (isSaving) "保存中…" else "プロフィールを保存")
            }
        }
        profileMessage?.let {
            ProfileStatusMessage(message = it, isError = profileMessageIsError)
        }
    }
}

@Composable
private fun ChatGptPersonalLinkSyncCard(
    viewModel: SharedTagAuthViewModel,
    cloudState: jp.mimac.urlsaver.domain.SharedTagCloudState,
    settings: ChatGptPersonalLinkSyncSettings,
    eligibility: ChatGptSyncEligibilitySnapshot,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var isLoading by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var pendingAction by remember { mutableStateOf<ChatGptSyncPendingAction?>(null) }
    val gateOpen = viewModel.isChatGptSyncOperationEnabled
    val canOperate = gateOpen && cloudState.isSignedIn && !isLoading
    val statusSummary = when {
        !gateOpen -> "現在は利用できません"
        !cloudState.isSignedIn -> "サインインが必要です"
        isLoading -> "同期中…"
        settings.enabled -> "同期オン · 対象 ${eligibility.eligibleEntries.size}件 / 除外 ${eligibility.excludedCount}件"
        else -> "同期オフ · 対象 ${eligibility.eligibleEntries.size}件 / 除外 ${eligibility.excludedCount}件"
    }

    fun resultMessage(result: ChatGptSyncResult): String {
        return when (result) {
            is ChatGptSyncResult.Success -> "同期しました（送信 ${result.syncedCount}件 / 除外 ${result.excludedCount}件）"
            is ChatGptSyncResult.Partial -> "一部を同期しました（送信 ${result.syncedCount}件 / 失敗 ${result.failedCount}件 / 除外 ${result.excludedCount}件）"
            ChatGptSyncResult.AuthRequired -> "りんばむのアカウントでサインインしてください"
            ChatGptSyncResult.GateOff -> "現在は利用できません"
            ChatGptSyncResult.NotEnabled -> "同期をオンにするまでリンクは送信されません"
            is ChatGptSyncResult.Failure -> result.message
        }
    }

    fun runSync(action: suspend () -> ChatGptSyncResult) {
        scope.launch {
            isLoading = true
            message = null
            try {
                message = resultMessage(action())
                viewModel.refreshChatGptSyncEligibility()
            } finally {
                isLoading = false
            }
        }
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = gateOpen && !isLoading, onClick = onToggleExpanded)
                .heightIn(min = 60.dp)
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Surface(
                modifier = Modifier.size(40.dp),
                shape = CircleShape,
                color = if (gateOpen) MaterialTheme.colorScheme.secondaryContainer
                else MaterialTheme.colorScheme.errorContainer,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = if (gateOpen) Icons.Outlined.Refresh else Icons.Outlined.LinkOff,
                        contentDescription = null,
                        tint = if (gateOpen) MaterialTheme.colorScheme.onSecondaryContainer
                        else MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.size(22.dp),
                    )
                }
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = "ChatGPTに保存リンクを同期",
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    text = statusSummary,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (gateOpen) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                    maxLines = 2,
                )
            }
            if (gateOpen) {
                Icon(
                    imageVector = if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                    contentDescription = if (expanded) "詳細を閉じる" else "詳細を開く",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (gateOpen) {
            message?.let { currentMessage ->
                ProfileStatusMessage(message = currentMessage)
            }
        }

        if (expanded && gateOpen) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!cloudState.isSignedIn) {
                    Text(
                        text = "りんばむのアカウントでサインインしてください",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text("同期対象 ${eligibility.eligibleEntries.size}件")
                Text("除外 ${eligibility.excludedCount}件")
                eligibility.exclusionsByReason.forEach { (reason, count) ->
                    Text(
                        text = "除外理由: ${chatGptExclusionReasonLabel(reason)} ${count}件",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (!settings.enabled) {
                    Text(
                        text = "同期をオンにするまでリンクは送信されません",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                settings.lastSyncedAt?.let { syncedAt ->
                    Text(
                        text = "最終同期: ${android.text.format.DateFormat.format("yyyy/MM/dd HH:mm", syncedAt)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Button(
                    onClick = { pendingAction = ChatGptSyncPendingAction.ENABLE },
                    enabled = canOperate && !settings.enabled,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp),
                ) {
                    Text("同期をオンにする")
                }
                OutlinedButton(
                    onClick = { pendingAction = ChatGptSyncPendingAction.DISABLE },
                    enabled = canOperate && settings.enabled,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp),
                ) {
                    Text("同期をオフにする")
                }
                Button(
                    onClick = { pendingAction = ChatGptSyncPendingAction.SYNC_NOW },
                    enabled = canOperate && settings.enabled,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp),
                ) {
                    Text(if (isLoading) "同期中…" else "今すぐ同期")
                }
                OutlinedButton(
                    onClick = { pendingAction = ChatGptSyncPendingAction.SYNC_NOW },
                    enabled = canOperate && settings.enabled,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp),
                ) {
                    Text("再試行")
                }
            }
        }
    }

    pendingAction?.let { action ->
        AlertDialog(
            onDismissRequest = { pendingAction = null },
            title = {
                Text(
                    when (action) {
                        ChatGptSyncPendingAction.ENABLE -> "同期をオンにする"
                        ChatGptSyncPendingAction.DISABLE -> "同期をオフにする"
                        ChatGptSyncPendingAction.SYNC_NOW -> "リンクを同期する"
                    },
                )
            },
            text = {
                Text(chatGptSyncConfirmationBody(action, eligibility))
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingAction = null
                        runSync {
                            when (action) {
                                ChatGptSyncPendingAction.ENABLE -> viewModel.setChatGptPersonalLinkSync(
                                    enabled = true,
                                    contentFetchEnabled = false,
                                )
                                ChatGptSyncPendingAction.DISABLE -> viewModel.setChatGptPersonalLinkSync(
                                    enabled = false,
                                    contentFetchEnabled = false,
                                )
                                ChatGptSyncPendingAction.SYNC_NOW -> viewModel.syncChatGptPersonalLinksNow()
                            }
                        }
                    },
                ) {
                    Text("確認して実行")
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingAction = null }) {
                    Text("キャンセル")
                }
            },
        )
    }
}

private fun chatGptExclusionReasonLabel(reason: String): String {
    return when (reason) {
        "active_required" -> "アーカイブまたは保存状態が対象外"
        "no_local_provenance" -> "端末保存なし"
        "shared_reference" -> "共有参照あり"
        "pending_delete" -> "削除保留中"
        "shared_tag_allocation" -> "共有タグ割り当てあり"
        else -> "対象外条件"
    }
}

@Composable
private fun UsageSummaryCard(
    usageSummary: UsageSummary,
    backgroundStyle: HomeBackgroundStyle,
    planLabel: String,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = homeBackgroundGradientColors(backgroundStyle)[1],
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .semantics(mergeDescendants = true) {
                        role = Role.Button
                        stateDescription = if (expanded) "展開済み" else "折りたたみ"
                    }
                    .clickable(onClick = onToggleExpanded)
                    .padding(horizontal = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "利用状況",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                )
                Icon(
                    imageVector = if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                    contentDescription = if (expanded) "利用状況を閉じる" else "利用状況を開く",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (expanded) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)),
                ) {
                    Column(modifier = Modifier.padding(horizontal = 14.dp)) {
                        UsageMetricRow("保存リンク", usageSummary.personalUrlCount.toString())
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
                        UsageMetricRow("共有タグ", usageSummary.sharedTagCount.toString())
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
                        UsageMetricRow("グループ", usageSummary.sharedTagGroupCount.toString())
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
                        UsageMetricRow("現在のプラン", planLabel)
                    }
                }
            }
        }
    }
}

@Composable
private fun UsageMetricRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = label,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

private fun profilePlanLabel(planType: PlanType): String = when (planType) {
    PlanType.FREE -> "無料プラン"
    PlanType.LAUNCH_STANDARD -> "Standard"
    PlanType.STANDARD -> "Standard"
    PlanType.PRO -> "Pro"
    PlanType.PROMO_PRO -> "優待Pro"
}

@Composable
private fun ContactSupportPage(
    email: String,
    name: String,
    body: String,
    error: String?,
    onEmailChange: (String) -> Unit,
    onNameChange: (String) -> Unit,
    onBodyChange: (String) -> Unit,
    isSending: Boolean,
    onSend: () -> Unit,
) {
    OutlinedTextField(
        value = email,
        onValueChange = onEmailChange,
        label = { Text("メールアドレス") },
        modifier = Modifier.fillMaxWidth(),
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
            keyboardType = KeyboardType.Email,
            imeAction = ImeAction.Next,
        ),
        singleLine = true,
    )
    OutlinedTextField(
        value = name,
        onValueChange = onNameChange,
        label = { Text("氏名") },
        modifier = Modifier.fillMaxWidth(),
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
            imeAction = ImeAction.Next,
        ),
        singleLine = true,
    )
    OutlinedTextField(
        value = body,
        onValueChange = onBodyChange,
        label = { Text("問い合わせ内容") },
        modifier = Modifier
            .fillMaxWidth()
            .height(180.dp),
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
            imeAction = ImeAction.Default,
        ),
        minLines = 5,
    )
    error?.let {
        Text(
            text = it,
            color = MaterialTheme.colorScheme.error,
        )
    }
    Button(
        onClick = onSend,
        enabled = !isSending,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(if (isSending) "送信中..." else "送信")
    }
}

@Composable
@Suppress("UNUSED_PARAMETER")
private fun ProfileAvatar(
    avatarBitmap: ImageBitmap?,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            if (avatarBitmap != null) {
                Image(
                    bitmap = avatarBitmap,
                    contentDescription = "プロフィール写真",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            } else {
                Image(
                    painter = painterResource(id = R.drawable.default_profile_pig),
                    contentDescription = "プロフィール写真",
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(6.dp),
                    contentScale = ContentScale.Fit,
                )
            }
        }
    }
}

@Composable
private fun SharedTagAuthForm(
    email: String,
    password: String,
    isSubmitting: Boolean,
    message: String?,
    onEmailChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onGoogleSignIn: () -> Unit,
    onAppleSignIn: () -> Unit,
    onSignIn: () -> Unit,
    onSignUp: () -> Unit,
    onResendConfirmation: () -> Unit,
    onPasswordRecovery: () -> Unit,
) {
    var passwordVisible by remember { mutableStateOf(false) }
    var emailExpanded by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }

    OutlinedButton(
        onClick = onGoogleSignIn,
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .testTag("auth_google"),
        enabled = !isSubmitting,
    ) {
        AuthProviderButtonContent(R.drawable.ic_auth_google, "Googleで続ける")
    }
    OutlinedButton(
        onClick = onAppleSignIn,
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("auth_apple"),
        enabled = !isSubmitting,
    ) {
        AuthProviderButtonContent(
            iconRes = LinkedIdentityProvider.APPLE.iconResForSurface(MaterialTheme.colorScheme.surface),
            label = "Appleで続ける",
        )
    }
    OutlinedButton(
        onClick = {},
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("auth_chatgpt"),
        enabled = false,
    ) {
        AuthProviderButtonContent(
            iconRes = R.drawable.ai_chatgpt,
            label = "ChatGPTで続ける（準備中）",
            darkIconBacking = true,
        )
    }
    TextButton(onClick = { emailExpanded = !emailExpanded }, modifier = Modifier.fillMaxWidth().testTag("auth_email")) {
        Text(if (emailExpanded) "メールで続ける ▲" else "メールで続ける ▼")
    }
    if (emailExpanded) {
    OutlinedTextField(
        value = email,
        onValueChange = onEmailChange,
        label = { Text("メールアドレス") },
        modifier = Modifier.fillMaxWidth(),
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
            keyboardType = KeyboardType.Email,
            imeAction = ImeAction.Next,
        ),
        singleLine = true,
        enabled = !isSubmitting,
    )
    OutlinedTextField(
        value = password,
        onValueChange = onPasswordChange,
        label = { Text("パスワード") },
        modifier = Modifier.fillMaxWidth(),
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
            keyboardType = KeyboardType.Password,
            imeAction = ImeAction.Done,
        ),
        visualTransformation = if (passwordVisible) {
            VisualTransformation.None
        } else {
            PasswordVisualTransformation()
        },
        trailingIcon = {
            IconButton(onClick = { passwordVisible = !passwordVisible }) {
                Icon(
                    imageVector = if (passwordVisible) {
                        Icons.Outlined.VisibilityOff
                    } else {
                        Icons.Outlined.Visibility
                    },
                    contentDescription = if (passwordVisible) {
                        "パスワードを隠す"
                    } else {
                        "パスワードを表示"
                    },
                )
            }
        },
        singleLine = true,
        enabled = !isSubmitting,
    )
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Button(
            onClick = onSignIn,
            modifier = Modifier.weight(1f),
            enabled = !isSubmitting && email.isNotBlank() && password.length >= 6,
        ) {
            Text("サインイン")
        }
        OutlinedButton(
            onClick = onSignUp,
            modifier = Modifier.weight(1f),
            enabled = !isSubmitting && email.isNotBlank() && password.length >= 6,
        ) {
            Text("新規登録")
        }
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        TextButton(
            onClick = onResendConfirmation,
            modifier = Modifier.weight(1f),
            enabled = !isSubmitting && email.isNotBlank(),
        ) {
            Text("確認メール再送")
        }
        TextButton(
            onClick = onPasswordRecovery,
            modifier = Modifier.weight(1f),
            enabled = !isSubmitting && email.isNotBlank(),
        ) {
            Text("パスワード再設定")
        }
    }
    }
    if (isSubmitting) {
        CircularProgressIndicator()
    }
    message?.let {
        Text(
            text = it,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun LinkedIdentityProviderAccountRow(
    provider: LinkedIdentityProvider,
    state: LinkedIdentityProviderState,
    isSubmitting: Boolean,
    onLink: () -> Unit,
) {
    val currentAuthUserId = when (state) {
        is LinkedIdentityProviderState.Verified -> state.snapshot.authUserId
        is LinkedIdentityProviderState.LastConfirmed -> state.snapshot.authUserId
        is LinkedIdentityProviderState.Loading -> state.authUserId
        is LinkedIdentityProviderState.Error -> state.authUserId
        is LinkedIdentityProviderState.Unknown -> null
    }
    val snapshot = when (state) {
        is LinkedIdentityProviderState.Verified -> state.snapshot
        is LinkedIdentityProviderState.LastConfirmed -> state.snapshot
        is LinkedIdentityProviderState.Loading -> state.lastConfirmed
        is LinkedIdentityProviderState.Error -> state.lastConfirmed
        is LinkedIdentityProviderState.Unknown -> null
    }?.takeIf { it.authUserId == currentAuthUserId }
    val isFresh = state is LinkedIdentityProviderState.Verified && snapshot != null
    val isLinked = snapshot?.providers?.contains(provider) == true
    if (isFresh && !isLinked) {
        OutlinedButton(
            onClick = onLink,
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("auth_link_${provider.supabaseValue}"),
            enabled = !isSubmitting,
        ) {
            val iconRes = provider.iconResForSurface(MaterialTheme.colorScheme.surface)
            AuthProviderButtonContent(
                iconRes = iconRes,
                label = "${provider.displayName} を連携する",
            )
        }
    } else {
        val status = when {
            snapshot == null -> "未確認"
            isFresh && isLinked -> "連携済み"
            isLinked -> "前回: 連携済み"
            else -> "前回: 未連携"
        }
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .testTag("auth_link_${provider.supabaseValue}_status"),
            shape = MaterialTheme.shapes.small,
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp)
                    .semantics(mergeDescendants = true) {
                        stateDescription = status
                    },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                val iconRes = provider.iconResForSurface(MaterialTheme.colorScheme.surface)
                AuthProviderIcon(
                    iconRes = iconRes,
                )
                Text(provider.displayName, modifier = Modifier.weight(1f), maxLines = 1)
                Text(status, maxLines = 2, style = MaterialTheme.typography.bodySmall)
                if (isFresh && isLinked) {
                    Icon(Icons.Outlined.Check, contentDescription = null)
                }
            }
        }
    }
}

@Composable
private fun LinkedIdentityProviderStatusMessage(
    state: LinkedIdentityProviderState,
    onRetry: () -> Unit,
) {
    val message = when (state) {
        is LinkedIdentityProviderState.Unknown -> "ログイン方法の状態はまだ確認できていません。"
        is LinkedIdentityProviderState.LastConfirmed -> "前回確認した状態を表示しています。最新の状態を確認してください。"
        is LinkedIdentityProviderState.Loading -> "ログイン方法を確認しています…"
        is LinkedIdentityProviderState.Error -> if (state.lastConfirmed == null) {
            "ログイン方法を確認できませんでした。連携状態は未確定です。"
        } else {
            "最新の状態を確認できませんでした。前回確認した状態を表示しています。"
        }
        is LinkedIdentityProviderState.Verified -> return
    }
    val isLoading = state is LinkedIdentityProviderState.Loading
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (isLoading) CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            TextButton(
                onClick = onRetry,
                enabled = !isLoading,
                modifier = Modifier.testTag("auth_providers_retry"),
            ) {
                Icon(Icons.Outlined.Refresh, contentDescription = null)
                Text("再確認")
            }
        }
        if (state is LinkedIdentityProviderState.Error && state.reason == LinkedIdentityProviderRefreshFailure.SIGNED_OUT) {
            Text(
                text = "サインイン後にログイン方法を確認できます。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun AuthProviderButtonContent(
    iconRes: Int,
    label: String,
    darkIconBacking: Boolean = false,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        AuthProviderIcon(iconRes, darkIconBacking)
        Spacer(Modifier.size(12.dp))
        Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun AuthProviderIcon(
    iconRes: Int,
    darkBacking: Boolean = false,
) {
    val isAppleLogo = iconRes == R.drawable.ic_auth_apple_glyph_black || iconRes == R.drawable.ic_auth_apple_glyph_white
    Box(modifier = Modifier.size(32.dp), contentAlignment = Alignment.Center) {
        if (darkBacking) {
            Surface(
                modifier = Modifier.size(24.dp),
                shape = CircleShape,
                color = Color.Black,
            ) {
                Image(
                    painter = painterResource(iconRes),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize().padding(2.dp),
                    contentScale = ContentScale.Fit,
                )
            }
        } else {
            Image(
                painter = painterResource(iconRes),
                contentDescription = null,
                modifier = if (isAppleLogo) Modifier.requiredSize(64.dp) else Modifier.size(24.dp),
                contentScale = ContentScale.Fit,
            )
        }
    }
}

private val LinkedIdentityProvider.displayName: String
    get() = when (this) {
        LinkedIdentityProvider.GOOGLE -> "Google"
        LinkedIdentityProvider.APPLE -> "Apple"
    }

private fun LinkedIdentityProvider.iconResForSurface(surfaceColor: Color): Int = when (this) {
    LinkedIdentityProvider.GOOGLE -> R.drawable.ic_auth_google
    LinkedIdentityProvider.APPLE -> if (surfaceColor.luminance() >= 0.5f) {
        R.drawable.ic_auth_apple_glyph_black
    } else {
        R.drawable.ic_auth_apple_glyph_white
    }
}

@Composable
private fun PaidCourseSection(
    isEnabled: Boolean,
    isPurchasing: Boolean,
    showMonthlyOptions: Boolean,
    onPurchase: (PlanType, BillingPeriod) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "有料コース",
            style = MaterialTheme.typography.titleMedium,
        )
        if (showMonthlyOptions) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                PaidCourseButton(
                    label = "Standard 月額",
                    enabled = isEnabled,
                    isPurchasing = isPurchasing,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { onPurchase(PlanType.STANDARD, BillingPeriod.MONTHLY) },
                )
                PaidCourseButton(
                    label = "Standard 年払い",
                    enabled = isEnabled,
                    isPurchasing = isPurchasing,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { onPurchase(PlanType.STANDARD, BillingPeriod.YEARLY) },
                )
            }
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                PaidCourseButton(
                    label = "Pro 月額",
                    enabled = isEnabled,
                    isPurchasing = isPurchasing,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { onPurchase(PlanType.PRO, BillingPeriod.MONTHLY) },
                )
                PaidCourseButton(
                    label = "Pro 年払い",
                    enabled = isEnabled,
                    isPurchasing = isPurchasing,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { onPurchase(PlanType.PRO, BillingPeriod.YEARLY) },
                )
            }
        } else {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                PaidCourseButton(
                    label = "Standard 年払い",
                    enabled = isEnabled,
                    isPurchasing = isPurchasing,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { onPurchase(PlanType.STANDARD, BillingPeriod.YEARLY) },
                )
                PaidCourseButton(
                    label = "Pro 年払い",
                    enabled = isEnabled,
                    isPurchasing = isPurchasing,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { onPurchase(PlanType.PRO, BillingPeriod.YEARLY) },
                )
            }
        }
    }
}

@Composable
private fun PaidCourseButton(
    label: String,
    enabled: Boolean,
    isPurchasing: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.heightIn(min = 56.dp).testTag("paid_course_${label.replace(" ", "_")}"),
    ) {
        Text(if (isPurchasing) "処理中" else label, maxLines = 1, softWrap = false)
    }
}

@Composable
private fun InviteInfoState(text: String) {
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Outlined.LinkOff, contentDescription = null)
        Text(
            text = text,
            style = MaterialTheme.typography.titleMedium,
        )
    }
}

private fun SharedTagAuthResult.toUiMessage(
    success: String,
    emailConfirmation: String,
): String {
    return when (this) {
        is SharedTagAuthResult.Success -> success
        SharedTagAuthResult.NeedsEmailConfirmation -> emailConfirmation
        is SharedTagAuthResult.Failure -> message
    }
}

private suspend fun readAvatarBytes(
    context: Context,
    uri: Uri,
): ByteArray? = withContext(Dispatchers.IO) {
    runCatching {
        context.contentResolver.openInputStream(uri)?.use { stream ->
            stream.readBytes()
        }
    }.getOrNull()
}

private tailrec fun Context.findActivity(): Activity? {
    return when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }
}

private fun String?.toImageBitmapOrNull(): ImageBitmap? {
    if (this.isNullOrBlank()) return null
    val bytes = runCatching { Base64.decode(this, Base64.DEFAULT) }.getOrNull() ?: return null
    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
    return bitmap.asImageBitmap()
}
