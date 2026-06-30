package jp.mimac.urlsaver.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.LinkOff
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import jp.mimac.urlsaver.R
import jp.mimac.urlsaver.data.PendingInviteRecord
import jp.mimac.urlsaver.domain.SharedTagAccountDeletionResult
import jp.mimac.urlsaver.domain.SharedTagAuthResult
import jp.mimac.urlsaver.domain.BillingPeriod
import jp.mimac.urlsaver.domain.FeatureEntitlements
import jp.mimac.urlsaver.domain.PlanType
import jp.mimac.urlsaver.domain.SharedTagInviteAcceptanceResult
import jp.mimac.urlsaver.domain.SharedTagInvitePreviewResult
import jp.mimac.urlsaver.domain.UsageSummary
import jp.mimac.urlsaver.ui.theme.AppThemeMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val CONTACT_SUPPORT_SUCCESS_MESSAGE = "問い合わせを送信完了しました"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SharedTagCloudAuthScreen(
    viewModel: SharedTagAuthViewModel,
    themeMode: AppThemeMode,
    onThemeModeChange: (AppThemeMode) -> Unit,
    onBack: () -> Unit,
    initialPromoCode: String? = null,
) {
    val context = LocalContext.current
    val cloudState by viewModel.cloudState.collectAsStateWithLifecycle()
    val profile by viewModel.profile.collectAsStateWithLifecycle()
    val usageSummary by viewModel.usageSummary.collectAsStateWithLifecycle()
    val pendingInvite by viewModel.pendingInvite.collectAsStateWithLifecycle()
    val entitlements = viewModel.entitlements
    val scope = rememberCoroutineScope()

    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var displayName by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var isSubmitting by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showContactPage by remember { mutableStateOf(false) }
    var contactEmail by remember { mutableStateOf("") }
    var contactName by remember { mutableStateOf("") }
    var contactBody by remember { mutableStateOf("") }
    var contactError by remember { mutableStateOf<String?>(null) }
    var isSendingContact by remember { mutableStateOf(false) }
    var promoCode by remember { mutableStateOf(initialPromoCode.orEmpty()) }
    var promoMessage by remember { mutableStateOf<String?>(null) }
    var isRedeemingPromoCode by remember { mutableStateOf(false) }
    var isPurchasing by remember { mutableStateOf(false) }
    var draftAvatarBase64 by remember { mutableStateOf<String?>(null) }
    val avatarBitmap = remember(draftAvatarBase64) { draftAvatarBase64.toImageBitmapOrNull() }
    val isAvatarChanged = draftAvatarBase64 != profile.avatarBase64

    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val bytes = readAvatarBytes(context, uri)
            if (bytes == null) {
                message = "プロフィール写真を読み込めませんでした"
            } else {
                draftAvatarBase64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                message = "プロフィール写真を選択しました"
            }
        }
    }

    LaunchedEffect(profile.displayName) {
        if (displayName != profile.displayName) {
            displayName = profile.displayName
        }
    }

    LaunchedEffect(profile.avatarBase64) {
        if (draftAvatarBase64 != profile.avatarBase64) {
            draftAvatarBase64 = profile.avatarBase64
        }
    }

    LaunchedEffect(initialPromoCode) {
        if (!initialPromoCode.isNullOrBlank()) {
            promoCode = initialPromoCode
            promoMessage = "メールの優待コードを読み込みました"
        }
    }

    LaunchedEffect(Unit) {
        viewModel.refreshPendingInvite()
    }

    fun openContactPage() {
        contactEmail = cloudState.signedInEmail.orEmpty().ifBlank { email }
        contactName = profile.trimmedDisplayName
        contactBody = ""
        contactError = null
        showContactPage = true
    }

    fun sendContact() {
        if (isSendingContact) return
        scope.launch {
            isSendingContact = true
            contactError = null
            try {
                when (val result = viewModel.sendContactSupport(contactEmail, contactName, contactBody)) {
                    ContactSupportUiResult.Success -> {
                        contactBody = ""
                        showContactPage = false
                        message = CONTACT_SUPPORT_SUCCESS_MESSAGE
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
        val activity = context.findActivity()
        if (activity == null) {
            message = "購入画面を開けませんでした"
            return
        }
        scope.launch {
            isPurchasing = true
            message = when (val result = viewModel.purchasePaidCourse(activity, planType, billingPeriod)) {
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
            message = when (val result = viewModel.acceptPendingInvite()) {
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
            isSubmitting = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (showContactPage) "問い合わせ" else "プロフィール") },
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
                .padding(start = 24.dp, top = 8.dp, end = 24.dp, bottom = 24.dp),
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
                            contactError = null
                        },
                        onNameChange = {
                            contactName = it
                            contactError = null
                        },
                        onBodyChange = {
                            contactBody = it
                            contactError = null
                        },
                        isSending = isSendingContact,
                        onSend = ::sendContact,
                    )
                    return@Column
                }

                ProfileCard(
                    displayName = profile.trimmedDisplayName.ifBlank {
                        "プロフィール名"
                    },
                    signedInEmail = cloudState.signedInEmail,
                    draftDisplayName = displayName,
                    avatarBitmap = avatarBitmap,
                    canSaveProfile = displayName.trim() != profile.trimmedDisplayName || isAvatarChanged,
                    onDisplayNameChange = {
                        displayName = it
                        message = null
                    },
                    onSaveProfile = {
                        scope.launch {
                            viewModel.saveProfile(displayName, draftAvatarBase64)
                            message = "プロフィールを保存しました"
                        }
                    },
                    onPickAvatar = { imagePicker.launch("image/*") },
                    onRemoveAvatar = {
                        draftAvatarBase64 = null
                        message = "プロフィール写真を削除対象にしました"
                    },
                    themeMode = themeMode,
                    onThemeModeChange = onThemeModeChange,
                )
                pendingInvite?.let { record ->
                    PendingInviteResumeCard(
                        record = record,
                        isSignedIn = cloudState.isSignedIn,
                        isSubmitting = isSubmitting,
                        onAccept = ::acceptPendingInvite,
                        onClear = {
                            viewModel.clearPendingInvite()
                            message = "保留中の招待を削除しました"
                        },
                    )
                }
                UsageSummaryCard(
                    usageSummary = usageSummary,
                )
                if (entitlements.subscriptionEnabled) {
                    OutlinedButton(
                        onClick = {},
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Plus プラン")
                    }
                }

                Button(
                    onClick = ::openContactPage,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                ) {
                    Text("問い合わせ")
                }
                message?.let {
                    ProfileStatusMessage(message = it)
                }

                if (!cloudState.isConfigured) {
                    Text("このビルドでは共有タグのクラウド同期が設定されていません。")
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
                    return@Column
                }

                if (cloudState.isSignedIn) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                            Button(
                                onClick = {
                                    scope.launch {
                                        viewModel.signOut()
                                        message = "サインアウトしました"
                                    }
                                },
                            modifier = Modifier
                                .weight(1f)
                                .height(56.dp),
                        ) {
                            Text("サインアウト")
                        }
                        OutlinedButton(
                            onClick = { showDeleteDialog = true },
                            modifier = Modifier
                                .weight(1f)
                                .height(56.dp),
                        ) {
                            Text("アカウント削除")
                        }
                    }
                    PaidCourseSection(
                        isEnabled = !isSubmitting && !isPurchasing,
                        isPurchasing = isPurchasing,
                        onPurchase = ::purchasePaidCourse,
                    )
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
                    return@Column
                }

                Text(
                    text = "共有タグに参加するにはサインインが必要です",
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    softWrap = false,
                )
                SharedTagAuthForm(
                    email = email,
                    password = password,
                    isSubmitting = isSubmitting,
                    message = null,
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
                    onSignIn = {
                        scope.launch {
                            isSubmitting = true
                            message = viewModel.signIn(email, password).toUiMessage(
                                success = "サインインしました。プロフィールと共有タグを使えます。",
                                emailConfirmation = "確認メールの手続きが必要です。メール確認後にサインインしてください。",
                            )
                            isSubmitting = false
                        }
                    },
                    onSignUp = {
                        scope.launch {
                            isSubmitting = true
                            message = viewModel.signUp(email, password).toUiMessage(
                                success = "新規登録が完了しました。プロフィールと共有タグを使えます。",
                                emailConfirmation = "確認メールを送信しました。確認後にサインインしてください。",
                            )
                            isSubmitting = false
                        }
                    },
                    onResendConfirmation = {
                        scope.launch {
                            isSubmitting = true
                            message = viewModel.resendEmailConfirmation(email).toUiMessage(
                                success = "確認メールを再送しました。メール確認後にサインインしてください。",
                                emailConfirmation = "確認メールを再送しました。メール確認後にサインインしてください。",
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
                PaidCourseSection(
                    isEnabled = !isSubmitting && !isPurchasing,
                    isPurchasing = isPurchasing,
                    onPurchase = ::purchasePaidCourse,
                )
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
            }
        }
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            isSubmitting = true
                            showDeleteDialog = false
                            message = when (val result = viewModel.deleteAccount()) {
                                SharedTagAccountDeletionResult.Success -> "アカウントを削除しました"
                                SharedTagAccountDeletionResult.AuthRequired -> "アカウント削除にはサインインが必要です"
                                SharedTagAccountDeletionResult.OwnerTransferRequired ->
                                    "共有タグ詳細の参加者からオーナー権限を先に移譲してください"
                                is SharedTagAccountDeletionResult.Failure -> result.message
                            }
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
    record: PendingInviteRecord,
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
                    "前回開いた共有タグ招待を再開できます。"
                } else {
                    "前回開いた共有タグ招待を保存しています。サインイン後に参加できます。"
                },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (record.inviteToken.isNotBlank()) {
                Text(
                    text = "招待情報を保存済みです",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
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
                    Text("削除")
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
private fun ProfileStatusMessage(message: String) {
    val isContactSupportSuccess = message == CONTACT_SUPPORT_SUCCESS_MESSAGE
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = if (isContactSupportSuccess) {
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
                color = if (isContactSupportSuccess) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
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
    onDisplayNameChange: (String) -> Unit,
    onSaveProfile: () -> Unit,
    onPickAvatar: () -> Unit,
    onRemoveAvatar: () -> Unit,
    themeMode: AppThemeMode,
    onThemeModeChange: (AppThemeMode) -> Unit,
) {
    var isEditingDisplayName by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            ProfileAvatar(
                avatarBitmap = avatarBitmap,
                modifier = Modifier.size(88.dp),
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    if (isEditingDisplayName) {
                        OutlinedTextField(
                            value = draftDisplayName,
                            onValueChange = onDisplayNameChange,
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            label = { Text("表示名") },
                        )
                        IconButton(
                            onClick = {
                                onSaveProfile()
                                isEditingDisplayName = false
                            },
                            enabled = canSaveProfile,
                        ) {
                            Icon(Icons.Outlined.Check, contentDescription = "表示名を保存")
                        }
                        IconButton(
                            onClick = {
                                onDisplayNameChange(displayName)
                                isEditingDisplayName = false
                            },
                        ) {
                            Icon(Icons.Outlined.Close, contentDescription = "表示名の編集を取り消す")
                        }
                    } else {
                        Text(
                            text = displayName,
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = { isEditingDisplayName = true }) {
                            Icon(Icons.Outlined.Edit, contentDescription = "表示名を編集")
                        }
                    }
                }
                if (!signedInEmail.isNullOrBlank()) {
                    Text(
                        text = signedInEmail,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onPickAvatar) {
                        Text(if (avatarBitmap == null) "写真を追加" else "写真を変更")
                    }
                    if (avatarBitmap != null) {
                        TextButton(onClick = onRemoveAvatar) {
                            Text("削除")
                        }
                    }
                }
            }
        }

        ThemeModeSelector(
            selectedMode = themeMode,
            onModeChange = onThemeModeChange,
        )

        if (!isEditingDisplayName) {
            Button(
                onClick = onSaveProfile,
                enabled = canSaveProfile,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
            ) {
                Text("プロフィールを保存")
            }
        }
    }
}

@Composable
private fun UsageSummaryCard(
    usageSummary: UsageSummary,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "使用状況",
            style = MaterialTheme.typography.titleMedium,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            UsageMetricTile(
                title = "保存タグ",
                body = usageSummary.personalUrlCount.toString(),
                modifier = Modifier.weight(1f),
            )
            UsageMetricTile(
                title = "共有タグ",
                body = usageSummary.sharedTagCount.toString(),
                modifier = Modifier.weight(1f),
            )
            UsageMetricTile(
                title = "グループ",
                body = usageSummary.sharedTagGroupCount.toString(),
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun UsageMetricTile(
    title: String,
    body: String,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
            Text(
                text = body,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun ThemeModeSelector(
    selectedMode: AppThemeMode,
    onModeChange: (AppThemeMode) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = "画面モード",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            AppThemeMode.entries.forEach { mode ->
                val selected = selectedMode == mode
                if (selected) {
                    Button(
                        onClick = { onModeChange(mode) },
                        modifier = Modifier
                            .weight(1f)
                            .height(56.dp),
                    ) {
                        Text(mode.label)
                    }
                } else {
                    OutlinedButton(
                        onClick = { onModeChange(mode) },
                        modifier = Modifier
                            .weight(1f)
                            .height(56.dp),
                    ) {
                        Text(mode.label)
                    }
                }
            }
        }
    }
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
    onSignIn: () -> Unit,
    onSignUp: () -> Unit,
    onResendConfirmation: () -> Unit,
    onPasswordRecovery: () -> Unit,
) {
    var passwordVisible by remember { mutableStateOf(false) }

    OutlinedButton(
        onClick = onGoogleSignIn,
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp),
        enabled = !isSubmitting,
    ) {
        Text("Googleで続ける")
    }
    Text(
        text = "Googleで登録済みの場合は、メール/パスワードではなくGoogleでサインインしてください。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
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
private fun PaidCourseSection(
    isEnabled: Boolean,
    isPurchasing: Boolean,
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
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PaidCourseButton(
                label = "Standard 月額",
                enabled = isEnabled,
                isPurchasing = isPurchasing,
                modifier = Modifier.weight(1f),
                onClick = { onPurchase(PlanType.STANDARD, BillingPeriod.MONTHLY) },
            )
            PaidCourseButton(
                label = "Standard 年払い",
                enabled = isEnabled,
                isPurchasing = isPurchasing,
                modifier = Modifier.weight(1f),
                onClick = { onPurchase(PlanType.STANDARD, BillingPeriod.YEARLY) },
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PaidCourseButton(
                label = "Pro 月額",
                enabled = isEnabled,
                isPurchasing = isPurchasing,
                modifier = Modifier.weight(1f),
                onClick = { onPurchase(PlanType.PRO, BillingPeriod.MONTHLY) },
            )
            PaidCourseButton(
                label = "Pro 年払い",
                enabled = isEnabled,
                isPurchasing = isPurchasing,
                modifier = Modifier.weight(1f),
                onClick = { onPurchase(PlanType.PRO, BillingPeriod.YEARLY) },
            )
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
        modifier = modifier.height(56.dp),
    ) {
        Text(if (isPurchasing) "処理中" else label)
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
