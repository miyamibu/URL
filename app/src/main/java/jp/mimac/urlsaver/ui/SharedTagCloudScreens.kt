package jp.mimac.urlsaver.ui

import android.content.Context
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
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.LinkOff
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import jp.mimac.urlsaver.domain.SharedTagAccountDeletionResult
import jp.mimac.urlsaver.domain.SharedTagAuthResult
import jp.mimac.urlsaver.domain.FeatureEntitlements
import jp.mimac.urlsaver.domain.SharedTagInviteAcceptanceResult
import jp.mimac.urlsaver.domain.UsageSummary
import jp.mimac.urlsaver.ui.theme.AppThemeMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SharedTagCloudAuthScreen(
    viewModel: SharedTagAuthViewModel,
    themeMode: AppThemeMode,
    onThemeModeChange: (AppThemeMode) -> Unit,
    onBack: () -> Unit,
    showInviteCodeSection: Boolean = false,
) {
    val context = LocalContext.current
    val cloudState by viewModel.cloudState.collectAsStateWithLifecycle()
    val profile by viewModel.profile.collectAsStateWithLifecycle()
    val usageSummary by viewModel.usageSummary.collectAsStateWithLifecycle()
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
    var inviteCode by remember { mutableStateOf("") }
    var inviteMessage by remember { mutableStateOf<String?>(null) }
    var isApplyingInviteCode by remember { mutableStateOf(false) }
    val avatarBitmap = remember(profile.avatarBase64) { profile.avatarBase64.toImageBitmapOrNull() }

    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val bytes = readAvatarBytes(context, uri)
            if (bytes == null) {
                message = "プロフィール写真を読み込めませんでした"
            } else {
                viewModel.saveAvatarBytes(bytes)
                message = "プロフィール写真を保存しました"
            }
        }
    }

    LaunchedEffect(profile.displayName) {
        if (displayName != profile.displayName) {
            displayName = profile.displayName
        }
    }

    fun openContactPage() {
        contactEmail = cloudState.signedInEmail.orEmpty().ifBlank { email }
        contactName = profile.trimmedDisplayName
        contactBody = ""
        contactError = null
        showContactPage = true
    }

    fun sendContact() {
        val trimmedEmail = contactEmail.trim()
        val trimmedName = contactName.trim()
        val trimmedBody = contactBody.trim()
        if (trimmedEmail.isBlank() || trimmedName.isBlank() || trimmedBody.isBlank()) {
            contactError = "メールアドレス、氏名、問い合わせ内容を入力してください。"
            return
        }
        contactError = null
        val intent = Intent(Intent.ACTION_SENDTO).apply {
            data = Uri.parse("mailto:")
            putExtra(Intent.EXTRA_SUBJECT, "URL Saver 問い合わせ")
            putExtra(
                Intent.EXTRA_TEXT,
                """
                メールアドレス:
                $trimmedEmail

                氏名:
                $trimmedName

                問い合わせ内容:
                $trimmedBody
                """.trimIndent(),
            )
        }
        runCatching {
            context.startActivity(Intent.createChooser(intent, "問い合わせを送信"))
        }.onFailure {
            contactError = "メールアプリを開けませんでした"
        }
    }

    fun applyInviteCode() {
        scope.launch {
            isApplyingInviteCode = true
            inviteMessage = when (val result = viewModel.applyInviteCode(inviteCode)) {
                InviteCodeApplyResult.InvalidCode -> "コードを入力してください"
                is InviteCodeApplyResult.NotAvailable -> result.message
            }
            isApplyingInviteCode = false
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
                        onSend = ::sendContact,
                    )
                    return@Column
                }

                ProfileCard(
                    displayName = profile.trimmedDisplayName.ifBlank {
                        "プロフィール未設定"
                    },
                    signedInEmail = cloudState.signedInEmail,
                    draftDisplayName = displayName,
                    avatarBitmap = avatarBitmap,
                    onDisplayNameChange = {
                        displayName = it
                        message = null
                    },
                    onSaveProfile = {
                        scope.launch {
                            viewModel.saveDisplayName(displayName)
                            message = "プロフィールを保存しました"
                        }
                    },
                    onPickAvatar = { imagePicker.launch("image/*") },
                    onRemoveAvatar = {
                        scope.launch {
                            viewModel.saveAvatarBytes(null)
                            message = "プロフィール写真を削除しました"
                        }
                    },
                    themeMode = themeMode,
                    onThemeModeChange = onThemeModeChange,
                )
                UsageSummaryCard(
                    usageSummary = usageSummary,
                    entitlements = entitlements,
                )
                if (entitlements.subscriptionEnabled) {
                    OutlinedButton(
                        onClick = {},
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Plus プラン")
                    }
                }

                if (!cloudState.isConfigured) {
                    Text("このビルドでは共有タグのクラウド同期が設定されていません。")
                    if (showInviteCodeSection) {
                        InviteCodeSection(
                            inviteCode = inviteCode,
                            inviteMessage = inviteMessage,
                            isApplyingInviteCode = isApplyingInviteCode,
                            canApplyInviteCode = viewModel.canApplyInviteCode(inviteCode),
                            onInviteCodeChange = {
                                inviteCode = it
                                inviteMessage = null
                            },
                            onApplyInviteCode = ::applyInviteCode,
                        )
                    }
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
                    Button(
                        onClick = ::openContactPage,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp),
                    ) {
                        Text("問い合わせ")
                    }
                    message?.let {
                        Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (showInviteCodeSection) {
                        InviteCodeSection(
                            inviteCode = inviteCode,
                            inviteMessage = inviteMessage,
                            isApplyingInviteCode = isApplyingInviteCode,
                            canApplyInviteCode = viewModel.canApplyInviteCode(inviteCode),
                            onInviteCodeChange = {
                                inviteCode = it
                                inviteMessage = null
                            },
                            onApplyInviteCode = ::applyInviteCode,
                        )
                    }
                    return@Column
                }

                message?.let {
                    Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }

                Text(
                    text = "共有タグに参加するにはサインインが必要です",
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = "未サインインのため共有タグは同期されません。アプリ削除後や機種変更後でも、同じメールアドレスでサインインし直すと共有タグを復元できます。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "通常のURL保存はサインインしなくても使えます。共有タグの同期、招待参加、複数端末での共有だけサインインが必要です。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                )
                if (showInviteCodeSection) {
                    InviteCodeSection(
                        inviteCode = inviteCode,
                        inviteMessage = inviteMessage,
                        isApplyingInviteCode = isApplyingInviteCode,
                        canApplyInviteCode = viewModel.canApplyInviteCode(inviteCode),
                        onInviteCodeChange = {
                            inviteCode = it
                            inviteMessage = null
                        },
                        onApplyInviteCode = ::applyInviteCode,
                    )
                }
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
private fun InviteCodeSection(
    inviteCode: String,
    inviteMessage: String?,
    isApplyingInviteCode: Boolean,
    canApplyInviteCode: Boolean,
    onInviteCodeChange: (String) -> Unit,
    onApplyInviteCode: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "招待コード",
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = "コードをお持ちの方は入力してください",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = inviteCode,
            onValueChange = onInviteCodeChange,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        Button(
            onClick = onApplyInviteCode,
            enabled = canApplyInviteCode && !isApplyingInviteCode,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
        ) {
            Text(if (isApplyingInviteCode) "適用中…" else "適用する")
        }
        inviteMessage?.let {
            Text(
                text = it,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
    val cloudState by viewModel.cloudState.collectAsStateWithLifecycle()
    val joinedTag by viewModel.joinedTag.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var accepted by remember { mutableStateOf(false) }
    var isSubmitting by remember { mutableStateOf(false) }

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
                    message = "参加しました。同期が終わるとタグを開きます。"
                }
                SharedTagInviteAcceptanceResult.AuthRequired -> {
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("共有タグに参加") },
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
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (!viewModel.hasInviteToken()) {
                    InviteInfoState("共有招待リンクを開けませんでした")
                    return@Column
                }

                if (!cloudState.isConfigured) {
                    InviteInfoState("このビルドではクラウド共有が設定されていません。")
                    return@Column
                }

                if (!cloudState.isSignedIn) {
                    Text(
                        text = "共有タグの招待を受け取りました",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = "参加するにはサインインが必要です。アカウントがない場合はここから作成できます。",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
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
                        onSignIn = {
                            scope.launch {
                                isSubmitting = true
                                when (val result = viewModel.signIn(email, password)) {
                                    is SharedTagAuthResult.Success -> {
                                        message = "サインインしました。招待に参加しています…"
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
                                    success = "アカウントを作成しました。招待に参加しています…",
                                    emailConfirmation = "確認メールを送信しました。確認後にこの招待リンクをもう一度開いてください。",
                                )
                                if (result is SharedTagAuthResult.Success) {
                                    isSubmitting = false
                                    acceptInvite()
                                } else {
                                    isSubmitting = false
                                }
                            }
                        },
                    )
                } else {
                    Text(
                        text = "共有タグの招待を受け取りました",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = "参加すると、この共有タグの URL 一覧だけが同期されます。",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (accepted && joinedTag == null) {
                        CircularProgressIndicator()
                    }
                    Button(
                        onClick = ::acceptInvite,
                        enabled = !isSubmitting && !accepted,
                    ) {
                        Text(if (accepted) "同期中…" else "招待に参加")
                    }
                    message?.let {
                        Text(
                            text = it,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ProfileCard(
    displayName: String,
    signedInEmail: String?,
    draftDisplayName: String,
    avatarBitmap: ImageBitmap?,
    onDisplayNameChange: (String) -> Unit,
    onSaveProfile: () -> Unit,
    onPickAvatar: () -> Unit,
    onRemoveAvatar: () -> Unit,
    themeMode: AppThemeMode,
    onThemeModeChange: (AppThemeMode) -> Unit,
) {
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
                modifier = Modifier.size(76.dp),
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = displayName,
                    style = MaterialTheme.typography.titleMedium,
                )
                if (!signedInEmail.isNullOrBlank()) {
                    Text(
                        text = signedInEmail,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            TextButton(onClick = onPickAvatar) {
                Text(if (avatarBitmap == null) "写真を追加" else "写真を変更")
            }
            if (avatarBitmap != null) {
                TextButton(onClick = onRemoveAvatar) {
                    Text("削除")
                }
            }
        }

        OutlinedTextField(
            value = draftDisplayName,
            onValueChange = onDisplayNameChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("表示名") },
            singleLine = true,
        )

        ThemeModeSelector(
            selectedMode = themeMode,
            onModeChange = onThemeModeChange,
        )

        Button(
            onClick = onSaveProfile,
            enabled = draftDisplayName.trim() != displayName.trim(),
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
        ) {
            Text("プロフィールを保存")
        }
    }
}

@Composable
private fun UsageSummaryCard(
    usageSummary: UsageSummary,
    entitlements: FeatureEntitlements,
) {
    val limits = entitlements.limits
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
                title = "通常タグで保存URL",
                body = "${usageSummary.personalUrlCount} / ${limits.personalUrlLimit}",
                modifier = Modifier.weight(1f),
            )
            UsageMetricTile(
                title = "共有タグURL",
                body = usageSummary.sharedTagUsages
                    .takeIf { it.isNotEmpty() }
                    ?.joinToString(separator = "\n") { usage ->
                        "${usage.tagName} ${usage.urlCount} / ${usage.limit}"
                    }
                    ?: "共有タグなし",
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
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
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
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text("メールアプリで送信")
    }
}

@Composable
private fun ProfileAvatar(
    avatarBitmap: ImageBitmap?,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        if (avatarBitmap != null) {
            Image(
                bitmap = avatarBitmap,
                contentDescription = "プロフィール写真",
                modifier = Modifier
                    .fillMaxSize()
                    .clip(CircleShape),
                contentScale = ContentScale.Crop,
            )
        } else {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Outlined.AccountCircle,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(0.78f),
                    tint = MaterialTheme.colorScheme.primary,
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
    onSignIn: () -> Unit,
    onSignUp: () -> Unit,
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
        visualTransformation = PasswordVisualTransformation(),
        singleLine = true,
        enabled = !isSubmitting,
    )
    Spacer(Modifier.height(4.dp))
    Button(
        onClick = onSignIn,
        modifier = Modifier.fillMaxWidth(),
        enabled = !isSubmitting && email.isNotBlank() && password.length >= 6,
    ) {
        Text("サインイン")
    }
    TextButton(
        onClick = onSignUp,
        enabled = !isSubmitting && email.isNotBlank() && password.length >= 6,
    ) {
        Text("新規登録")
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

private fun String?.toImageBitmapOrNull(): ImageBitmap? {
    if (this.isNullOrBlank()) return null
    val bytes = runCatching { Base64.decode(this, Base64.DEFAULT) }.getOrNull() ?: return null
    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
    return bitmap.asImageBitmap()
}
