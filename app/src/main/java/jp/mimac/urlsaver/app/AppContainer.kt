package jp.mimac.urlsaver.app

import android.content.Context
import android.util.Log
import androidx.work.WorkerFactory
import androidx.work.WorkManager
import jp.mimac.urlsaver.BuildConfig
import jp.mimac.urlsaver.billing.GooglePlayBillingService
import jp.mimac.urlsaver.data.AppDatabase
import jp.mimac.urlsaver.data.AiTransparencyRepository
import jp.mimac.urlsaver.data.ChatGptPersonalLinkSyncRepository
import jp.mimac.urlsaver.data.ConfiguredContactSupportClient
import jp.mimac.urlsaver.data.ContactSupportClient
import jp.mimac.urlsaver.data.DataStoreEntryCardDisplayModeStore
import jp.mimac.urlsaver.data.DataStoreEntitlementGrantStore
import jp.mimac.urlsaver.data.DefaultTagRepository
import jp.mimac.urlsaver.data.DefaultUrlRepository
import jp.mimac.urlsaver.data.EntryCardDisplayModeStore
import jp.mimac.urlsaver.data.EntitlementGrantRepository
import jp.mimac.urlsaver.data.EntitlementGrantStore
import jp.mimac.urlsaver.data.ExportRepository
import jp.mimac.urlsaver.data.MetadataScheduler
import jp.mimac.urlsaver.data.MetadataWorkScheduler
import jp.mimac.urlsaver.data.DataStoreServiceFilterOrderStore
import jp.mimac.urlsaver.data.DataStoreTopFilterOrderStore
import jp.mimac.urlsaver.data.ServiceFilterOrderStore
import jp.mimac.urlsaver.data.DefaultExportRepository
import jp.mimac.urlsaver.data.LocalAccountCleanupStore
import jp.mimac.urlsaver.data.PendingInviteStore
import jp.mimac.urlsaver.data.SharedTagAuthRemoteDataSource
import jp.mimac.urlsaver.data.SharedPreferencesSharedTagAuthSessionProvider
import jp.mimac.urlsaver.data.SharedPreferencesPendingInviteStore
import jp.mimac.urlsaver.data.SharedPreferencesLocalAccountCleanupStore
import jp.mimac.urlsaver.data.SharedPreferencesSharedTagOAuthStateStore
import jp.mimac.urlsaver.data.SharedPreferencesChatGptPersonalLinkSyncSettingsStore
import jp.mimac.urlsaver.data.SharedTagAuthSessionProvider
import jp.mimac.urlsaver.data.SharedTagSyncCoordinator
import jp.mimac.urlsaver.data.SharedTagSyncRemoteConfig
import jp.mimac.urlsaver.data.SharedTagSyncRemoteDataSource
import jp.mimac.urlsaver.data.SharedTagSyncScheduler
import jp.mimac.urlsaver.data.SupabaseSharedTagAuthRemoteDataSource
import jp.mimac.urlsaver.data.SupabaseChatGptPersonalLinkRemoteDataSource
import jp.mimac.urlsaver.data.SupabaseEntitlementGrantRemoteDataSource
import jp.mimac.urlsaver.data.SupabaseSharedTagSyncRemoteDataSource
import jp.mimac.urlsaver.data.SupabaseStorePurchaseRemoteDataSource
import jp.mimac.urlsaver.data.WorkManagerSharedTagSyncScheduler
import jp.mimac.urlsaver.data.TagRepository
import jp.mimac.urlsaver.data.TopFilterOrderStore
import jp.mimac.urlsaver.data.UserProfileStore
import jp.mimac.urlsaver.data.UrlRepository
import jp.mimac.urlsaver.data.DataStoreUserProfileStore
import jp.mimac.urlsaver.data.DefaultUsageSummaryDataSource
import jp.mimac.urlsaver.data.DefaultVideoRepository
import jp.mimac.urlsaver.data.UsageSummaryDataSource
import jp.mimac.urlsaver.domain.DefaultEntitlementResolver
import jp.mimac.urlsaver.domain.BuildVariantEntitlementOverrides
import jp.mimac.urlsaver.util.AppClock
import jp.mimac.urlsaver.util.SystemAppClock
import jp.mimac.urlsaver.video.BackendVideoResolver
import jp.mimac.urlsaver.worker.MetadataFetcher
import jp.mimac.urlsaver.worker.UrlSaverWorkerFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.net.URI
import java.net.URLEncoder

class AppContainer(context: Context) {
    private val appContext = context.applicationContext
    internal val database = AppDatabase.create(appContext)
    private val clock: AppClock = SystemAppClock
    private val entitlementRefreshScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val instagramPublicOEmbedEndpointBuilder: (String) -> String = { targetUrl ->
        val encodedUrl = URLEncoder.encode(targetUrl, Charsets.UTF_8.name())
        "$INSTAGRAM_PUBLIC_OEMBED_ENDPOINT?omitscript=true&url=$encodedUrl"
    }
    private val instagramOEmbedEndpointBuilder: ((String) -> String)? by lazy {
        val token = BuildConfig.INSTAGRAM_OEMBED_ACCESS_TOKEN.trim()
        if (token.isBlank()) {
            null
        } else {
            { targetUrl ->
                val encodedUrl = URLEncoder.encode(targetUrl, Charsets.UTF_8.name())
                val encodedToken = URLEncoder.encode(token, Charsets.UTF_8.name())
                "$INSTAGRAM_OEMBED_ENDPOINT?omitscript=true&url=$encodedUrl&access_token=$encodedToken"
            }
        }
    }
    private val instagramCaptionedEmbedEndpointBuilder: (String) -> String = { targetUrl ->
        buildInstagramCaptionedEmbedUrl(targetUrl)
    }
    private val metadataFetcher: MetadataFetcher by lazy {
        MetadataFetcher(
            userAgent = "UrlSaver/${BuildConfig.VERSION_NAME}",
            instagramPublicOEmbedEndpointBuilder = instagramPublicOEmbedEndpointBuilder,
            instagramOEmbedEndpointBuilder = instagramOEmbedEndpointBuilder,
            instagramCaptionedEmbedEndpointBuilder = instagramCaptionedEmbedEndpointBuilder,
        )
    }
    private val scheduler: MetadataScheduler by lazy {
        runCatching { MetadataWorkScheduler(WorkManager.getInstance(appContext)) }
            .getOrElse { error ->
                if (BuildConfig.DEBUG) {
                    throw IllegalStateException("WorkManager initialization failed; metadata scheduling is unavailable.", error)
                }
                Log.e(TAG, "WorkManager initialization failed. Metadata scheduling will report explicit failure.", error)
                UnavailableMetadataScheduler(error)
            }
    }
    val sharedTagAuthSessionProvider: SharedTagAuthSessionProvider by lazy {
        SharedPreferencesSharedTagAuthSessionProvider(appContext)
    }
    val entryCardDisplayModeStore: EntryCardDisplayModeStore by lazy {
        DataStoreEntryCardDisplayModeStore(appContext)
    }
    val serviceFilterOrderStore: ServiceFilterOrderStore by lazy {
        DataStoreServiceFilterOrderStore(appContext)
    }
    val topFilterOrderStore: TopFilterOrderStore by lazy {
        DataStoreTopFilterOrderStore(appContext)
    }
    val userProfileStore: UserProfileStore by lazy {
        DataStoreUserProfileStore(appContext)
    }
    val pendingInviteStore: PendingInviteStore by lazy {
        SharedPreferencesPendingInviteStore(appContext)
    }
    val localAccountCleanupStore: LocalAccountCleanupStore by lazy {
        SharedPreferencesLocalAccountCleanupStore(appContext)
    }
    private val sharedTagSyncRemoteConfig: SharedTagSyncRemoteConfig by lazy {
        SharedTagSyncRemoteConfig(
            enabled = BuildConfig.SHARED_TAG_CLOUD_ENABLED,
            supabaseUrl = BuildConfig.SUPABASE_URL,
            anonKey = BuildConfig.SUPABASE_ANON_KEY,
            inviteLinkBaseUrl = BuildConfig.INVITE_LINK_BASE_URL,
        )
    }
    private val sharedTagAuthRemoteDataSource: SharedTagAuthRemoteDataSource by lazy {
        SupabaseSharedTagAuthRemoteDataSource(
            config = sharedTagSyncRemoteConfig,
            oauthStateStore = SharedPreferencesSharedTagOAuthStateStore(appContext),
        )
    }
    private val sharedTagSyncRemoteDataSource: SharedTagSyncRemoteDataSource by lazy {
        SupabaseSharedTagSyncRemoteDataSource(
            config = sharedTagSyncRemoteConfig,
            authSessionProvider = sharedTagAuthSessionProvider,
            authRemoteDataSource = sharedTagAuthRemoteDataSource,
        )
    }
    private val entitlementGrantStore: EntitlementGrantStore by lazy {
        DataStoreEntitlementGrantStore(appContext)
    }
    val entitlementGrantRepository: EntitlementGrantRepository by lazy {
        EntitlementGrantRepository(
            authSessionProvider = sharedTagAuthSessionProvider,
            remoteDataSource = SupabaseEntitlementGrantRemoteDataSource(
                config = sharedTagSyncRemoteConfig,
                authSessionProvider = sharedTagAuthSessionProvider,
                authRemoteDataSource = sharedTagAuthRemoteDataSource,
            ),
            grantStore = entitlementGrantStore,
            clock = clock,
        )
    }
    val googlePlayBillingService: GooglePlayBillingService by lazy {
        GooglePlayBillingService(
            context = appContext,
            authSessionProvider = sharedTagAuthSessionProvider,
            remoteDataSource = SupabaseStorePurchaseRemoteDataSource(
                config = sharedTagSyncRemoteConfig,
                authSessionProvider = sharedTagAuthSessionProvider,
                authRemoteDataSource = sharedTagAuthRemoteDataSource,
            ),
        )
    }
    val chatGptPersonalLinkSyncRepository: ChatGptPersonalLinkSyncRepository by lazy {
        ChatGptPersonalLinkSyncRepository(
            authSessionProvider = sharedTagAuthSessionProvider,
            urlEntryDao = database.urlEntryDao(),
            tagDao = database.tagDao(),
            settingsStore = SharedPreferencesChatGptPersonalLinkSyncSettingsStore(appContext),
            remoteDataSource = SupabaseChatGptPersonalLinkRemoteDataSource(
                config = sharedTagSyncRemoteConfig,
                authSessionProvider = sharedTagAuthSessionProvider,
                authRemoteDataSource = sharedTagAuthRemoteDataSource,
            ),
            operationEnabled = BuildConfig.CHATGPT_PERSONAL_LINK_SYNC_OPERATION_ENABLED &&
                sharedTagSyncRemoteConfig.isConfigured,
        )
    }
    val aiTransparencyRepository: AiTransparencyRepository by lazy {
        AiTransparencyRepository(
            database = database,
            aiTransparencyDao = database.aiTransparencyDao(),
            urlEntryDao = database.urlEntryDao(),
            tagDao = database.tagDao(),
            nowIso = { java.time.Instant.ofEpochMilli(clock.nowEpochMillis()).toString() },
            nowMillis = { clock.nowEpochMillis() },
        )
    }
    private val sharedTagSyncScheduler: SharedTagSyncScheduler by lazy {
        WorkManagerSharedTagSyncScheduler(WorkManager.getInstance(appContext))
    }
    private val sharedTagSyncCoordinator: SharedTagSyncCoordinator by lazy {
        SharedTagSyncCoordinator(
            database = database,
            tagDao = database.tagDao(),
            syncDao = database.sharedTagSyncDao(),
            urlEntryDao = database.urlEntryDao(),
            authSessionProvider = sharedTagAuthSessionProvider,
            remoteDataSource = sharedTagSyncRemoteDataSource,
            clock = clock,
            metadataScheduler = scheduler,
        )
    }
    private val usageSummaryDataSource: UsageSummaryDataSource by lazy {
        DefaultUsageSummaryDataSource(
            urlEntryDao = database.urlEntryDao(),
            tagDao = database.tagDao(),
            syncDao = database.sharedTagSyncDao(),
            authSessionProvider = sharedTagAuthSessionProvider,
            entitlementResolver = DefaultEntitlementResolver(
                grantsProvider = {
                    val now = clock.nowEpochMillis()
                    entitlementGrantRepository.currentGrantsSnapshot() +
                        BuildVariantEntitlementOverrides.grants(now)
                },
            ),
        )
    }
    val contactSupportClient: ContactSupportClient by lazy {
        ConfiguredContactSupportClient(BuildConfig.CONTACT_SUPPORT_ENDPOINT_URL)
    }
    private val workManager: WorkManager by lazy {
        WorkManager.getInstance(appContext)
    }
    val videoRepository: DefaultVideoRepository by lazy {
        DefaultVideoRepository(
            appContext = appContext,
            videoAssetDao = database.videoAssetDao(),
            videoDownloadDao = database.videoDownloadDao(),
            workManager = workManager,
            clock = clock,
        )
    }
    private val videoResolver: BackendVideoResolver by lazy {
        BackendVideoResolver(BuildConfig.MEDIA_RESOLVER_BACKEND_URL)
    }

    init {
        entitlementRefreshScope.launch {
            sharedTagAuthSessionProvider.session.collectLatest { session ->
                if (session != null) {
                    entitlementGrantRepository.refreshForCurrentSession()
                }
            }
        }
    }

    val repository: UrlRepository by lazy {
        DefaultUrlRepository(
            database = database,
            dao = database.urlEntryDao(),
            tagDao = database.tagDao(),
            clock = clock,
            scheduler = scheduler,
            usageSummaryDataSource = usageSummaryDataSource,
        )
    }

    val tagRepository: TagRepository by lazy {
        DefaultTagRepository(
            database = database,
            tagDao = database.tagDao(),
            syncDao = database.sharedTagSyncDao(),
            urlEntryDao = database.urlEntryDao(),
            clock = clock,
            metadataScheduler = scheduler,
            authSessionProvider = sharedTagAuthSessionProvider,
            authRemoteDataSource = sharedTagAuthRemoteDataSource,
            syncScheduler = sharedTagSyncScheduler,
            syncCoordinator = sharedTagSyncCoordinator,
            remoteDataSource = sharedTagSyncRemoteDataSource,
            remoteConfig = sharedTagSyncRemoteConfig,
            usageSummaryDataSource = usageSummaryDataSource,
            aiLocalDataClearer = aiTransparencyRepository,
            localAccountCleanupStore = localAccountCleanupStore,
        )
    }

    val exportRepository: ExportRepository by lazy {
        DefaultExportRepository(
            urlEntryDao = database.urlEntryDao(),
            tagDao = database.tagDao(),
            collectionDao = database.collectionDao(),
            authSessionProvider = sharedTagAuthSessionProvider,
            syncBeforeExport = { sharedTagSyncCoordinator.syncCurrentSession() },
            clock = clock,
            appVersion = BuildConfig.VERSION_NAME,
        )
    }

    val workerFactory: WorkerFactory by lazy {
        UrlSaverWorkerFactory(
            repositoryProvider = { repository },
            metadataFetcherProvider = { metadataFetcher },
            clockProvider = { clock },
            sharedTagSyncCoordinatorProvider = { sharedTagSyncCoordinator },
            urlEntryDaoProvider = { database.urlEntryDao() },
            videoAssetDaoProvider = { database.videoAssetDao() },
            videoDownloadDaoProvider = { database.videoDownloadDao() },
            videoResolverProvider = { videoResolver },
        )
    }

    private class UnavailableMetadataScheduler(
        private val cause: Throwable,
    ) : MetadataScheduler {
        override fun enqueueMetadata(entryId: Long) {
            throw IllegalStateException("Metadata scheduling unavailable", cause)
        }
    }

    private companion object {
        const val TAG = "AppContainer"
        const val INSTAGRAM_PUBLIC_OEMBED_ENDPOINT = "https://www.instagram.com/api/v1/oembed/"
        const val INSTAGRAM_OEMBED_ENDPOINT = "https://graph.facebook.com/v22.0/instagram_oembed"

        fun buildInstagramCaptionedEmbedUrl(targetUrl: String): String {
            val uri = URI(targetUrl)
            val normalizedPath = uri.path.removeSuffix("/") + "/embed/captioned/"
            return URI(
                uri.scheme,
                uri.authority,
                normalizedPath,
                uri.query,
                null,
            ).toString()
        }
    }
}
