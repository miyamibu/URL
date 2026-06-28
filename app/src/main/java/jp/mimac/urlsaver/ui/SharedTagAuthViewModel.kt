package jp.mimac.urlsaver.ui

import android.app.Activity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import jp.mimac.urlsaver.BuildConfig
import jp.mimac.urlsaver.billing.BillingPurchaseResult
import jp.mimac.urlsaver.billing.GooglePlayBillingService
import jp.mimac.urlsaver.data.ChatGptPersonalLinkSyncRepository
import jp.mimac.urlsaver.data.ChatGptPersonalLinkSyncSettings
import jp.mimac.urlsaver.data.ChatGptSyncResult
import jp.mimac.urlsaver.data.ContactSupportClient
import jp.mimac.urlsaver.data.ContactSupportRequest
import jp.mimac.urlsaver.data.ContactSupportResult
import jp.mimac.urlsaver.data.EntitlementGrantRepository
import jp.mimac.urlsaver.data.PromoCodeRedemptionResult
import jp.mimac.urlsaver.data.SharedTagAuthSessionProvider
import jp.mimac.urlsaver.data.TagRepository
import jp.mimac.urlsaver.data.UserProfileStore
import jp.mimac.urlsaver.domain.BillingPeriod
import jp.mimac.urlsaver.domain.FeatureEntitlements
import jp.mimac.urlsaver.domain.PlanType
import jp.mimac.urlsaver.domain.SharedTagAccountDeletionResult
import jp.mimac.urlsaver.domain.SharedTagAuthResult
import jp.mimac.urlsaver.domain.SharedTagCloudState
import jp.mimac.urlsaver.domain.UsageSummary
import jp.mimac.urlsaver.domain.UserProfile
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import java.util.Base64

class SharedTagAuthViewModel(
    private val tagRepository: TagRepository,
    private val userProfileStore: UserProfileStore,
    private val entitlementGrantRepository: EntitlementGrantRepository,
    private val googlePlayBillingService: GooglePlayBillingService? = null,
    private val chatGptPersonalLinkSyncRepository: ChatGptPersonalLinkSyncRepository? = null,
    private val contactSupportClient: ContactSupportClient = object : ContactSupportClient {
        override suspend fun send(request: ContactSupportRequest): ContactSupportResult {
            return ContactSupportResult.Failure("問い合わせ送信先が設定されていません")
        }
    },
    private val authSessionProvider: SharedTagAuthSessionProvider? = null,
) : ViewModel() {

    val cloudState: StateFlow<SharedTagCloudState> = tagRepository.cloudState
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            SharedTagCloudState(isConfigured = false, isSignedIn = false),
        )
    val profile: StateFlow<UserProfile> = userProfileStore.observeProfile()
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            UserProfile(),
        )
    val usageSummary: StateFlow<UsageSummary> = tagRepository.observeUsageSummary()
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            UsageSummary(
                personalUrlCount = 0,
                normalTagCount = 0,
                sharedTagCount = 0,
                sharedTagUsages = emptyList(),
            ),
        )
    val entitlements: FeatureEntitlements = tagRepository.featureEntitlements()
    val chatGptSyncSettings: StateFlow<ChatGptPersonalLinkSyncSettings> =
        (chatGptPersonalLinkSyncRepository?.settings ?: flowOf(ChatGptPersonalLinkSyncSettings()))
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5_000),
                ChatGptPersonalLinkSyncSettings(),
            )

    suspend fun signIn(email: String, password: String): SharedTagAuthResult {
        return tagRepository.signIn(email, password)
    }

    suspend fun signUp(email: String, password: String): SharedTagAuthResult {
        return tagRepository.signUp(email, password)
    }

    suspend fun resendEmailConfirmation(email: String): SharedTagAuthResult {
        return tagRepository.resendEmailConfirmation(email)
    }

    suspend fun sendPasswordRecovery(email: String): SharedTagAuthResult {
        return tagRepository.sendPasswordRecovery(email)
    }

    fun googleOAuthUrl(): String? {
        return tagRepository.googleOAuthUrl()
    }

    suspend fun signOut() {
        tagRepository.signOut()
    }

    suspend fun deleteAccount(): SharedTagAccountDeletionResult {
        return tagRepository.deleteAccount()
    }

    suspend fun saveDisplayName(displayName: String) {
        userProfileStore.saveDisplayName(displayName)
        tagRepository.syncSharedProfileDisplayName(displayName)
    }

    suspend fun saveProfile(displayName: String, avatarBase64: String?) {
        userProfileStore.saveDisplayName(displayName)
        userProfileStore.saveAvatarBase64(avatarBase64)
        tagRepository.syncSharedProfileDisplayName(displayName)
    }

    suspend fun saveAvatarBytes(bytes: ByteArray?) {
        userProfileStore.saveAvatarBase64(
            bytes?.let { Base64.getEncoder().encodeToString(it) },
        )
    }

    fun canApplyPromoCode(code: String): Boolean = code.trim().isNotEmpty()

    suspend fun redeemPromoCode(code: String): PromoCodeApplyResult {
        return when (val result = entitlementGrantRepository.redeemPromoCode(code)) {
            is PromoCodeRedemptionResult.Success -> PromoCodeApplyResult.Success
            PromoCodeRedemptionResult.InvalidCode -> PromoCodeApplyResult.InvalidCode
            PromoCodeRedemptionResult.AuthRequired -> PromoCodeApplyResult.AuthRequired
            is PromoCodeRedemptionResult.Failure -> PromoCodeApplyResult.Failure(result.message)
        }
    }

    suspend fun purchasePaidCourse(
        activity: Activity,
        planType: PlanType,
        billingPeriod: BillingPeriod,
    ): PaidCoursePurchaseUiResult {
        val billingService = googlePlayBillingService ?: return PaidCoursePurchaseUiResult.Unavailable
        return when (val result = billingService.launchPurchase(activity, planType, billingPeriod)) {
            BillingPurchaseResult.Started -> PaidCoursePurchaseUiResult.Started
            BillingPurchaseResult.AuthRequired -> PaidCoursePurchaseUiResult.AuthRequired
            BillingPurchaseResult.ProductUnavailable -> PaidCoursePurchaseUiResult.ProductUnavailable
            is BillingPurchaseResult.Failure -> PaidCoursePurchaseUiResult.Failure(result.message)
        }
    }

    suspend fun setChatGptPersonalLinkSync(
        enabled: Boolean,
        contentFetchEnabled: Boolean,
    ): ChatGptSyncResult {
        val repository = chatGptPersonalLinkSyncRepository
            ?: return ChatGptSyncResult.Failure("ChatGPT連携はこのビルドで利用できません")
        return repository.setEnabled(enabled, contentFetchEnabled)
    }

    suspend fun sendContactSupport(
        email: String,
        name: String,
        body: String,
    ): ContactSupportUiResult {
        val trimmedEmail = email.trim()
        val trimmedName = name.trim()
        val trimmedBody = body.trim()
        if (trimmedEmail.isBlank() || trimmedName.isBlank() || trimmedBody.isBlank()) {
            return ContactSupportUiResult.Failure("メールアドレス、氏名、問い合わせ内容を入力してください。")
        }
        val state = cloudState.value
        val result = contactSupportClient.send(
            ContactSupportRequest(
                email = trimmedEmail,
                name = trimmedName,
                message = trimmedBody,
                platform = "android",
                appVersion = BuildConfig.VERSION_NAME,
                buildType = if (BuildConfig.DEBUG) "debug" else "release",
                isSignedIn = state.isSignedIn,
                authUserId = authSessionProvider?.session?.value?.authUserId,
            ),
        )
        return when (result) {
            is ContactSupportResult.Success -> ContactSupportUiResult.Success
            is ContactSupportResult.Failure -> ContactSupportUiResult.Failure(result.message)
        }
    }
}

sealed interface PromoCodeApplyResult {
    data object Success : PromoCodeApplyResult
    data object InvalidCode : PromoCodeApplyResult
    data object AuthRequired : PromoCodeApplyResult
    data class Failure(val message: String) : PromoCodeApplyResult
}

sealed interface PaidCoursePurchaseUiResult {
    data object Started : PaidCoursePurchaseUiResult
    data object AuthRequired : PaidCoursePurchaseUiResult
    data object ProductUnavailable : PaidCoursePurchaseUiResult
    data object Unavailable : PaidCoursePurchaseUiResult
    data class Failure(val message: String) : PaidCoursePurchaseUiResult
}

sealed interface ContactSupportUiResult {
    data object Success : ContactSupportUiResult
    data class Failure(val message: String) : ContactSupportUiResult
}
