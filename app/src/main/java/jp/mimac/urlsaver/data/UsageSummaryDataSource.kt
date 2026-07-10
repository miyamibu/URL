package jp.mimac.urlsaver.data

import jp.mimac.urlsaver.domain.DefaultEntitlementResolver
import jp.mimac.urlsaver.domain.EntitlementResolver
import jp.mimac.urlsaver.domain.FeatureEntitlements
import jp.mimac.urlsaver.domain.LimitChecker
import jp.mimac.urlsaver.domain.SharedTagUsage
import jp.mimac.urlsaver.domain.UsageSummary
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf

interface UsageSummaryDataSource {
    val entitlements: FeatureEntitlements
    val limitChecker: LimitChecker

    suspend fun getUsageSummary(): UsageSummary
    fun observeUsageSummary(): Flow<UsageSummary>
}

@OptIn(ExperimentalCoroutinesApi::class)
class DefaultUsageSummaryDataSource(
    private val urlEntryDao: UrlEntryDao,
    private val tagDao: TagDao,
    private val syncDao: SharedTagSyncDao? = null,
    private val authSessionProvider: SharedTagAuthSessionProvider,
    private val entitlementResolver: EntitlementResolver = DefaultEntitlementResolver(),
) : UsageSummaryDataSource {

    override val entitlements: FeatureEntitlements
        get() = entitlementResolver.resolve()

    override val limitChecker: LimitChecker
        get() = LimitChecker(entitlements)

    override suspend fun getUsageSummary(): UsageSummary {
        val authUserId = authSessionProvider.session.value?.authUserId
        val currentEntitlements = entitlements
        return UsageSummary(
            personalUrlCount = urlEntryDao.countPersonalSavedEntries(),
            normalTagCount = tagDao.countLocalOnlyTags(),
            sharedTagCount = tagDao.countVisibleSyncedTags(authUserId),
            sharedTagGroupCount = authUserId?.let { syncDao?.countGroups(it) } ?: 0,
            sharedTagUsages = tagDao.getVisibleSyncedTagUrlCounts(authUserId).map { usage ->
                SharedTagUsage(
                    tagId = usage.tagId,
                    tagName = usage.tagName,
                    urlCount = usage.urlCount,
                    limit = currentEntitlements.limits.sharedTagUrlLimitPerTag,
                )
            },
        )
    }

    override fun observeUsageSummary(): Flow<UsageSummary> {
        return authSessionProvider.session.flatMapLatest { session ->
            val authUserId = session?.authUserId
            val sharedTagCountFlow = if (authUserId == null) {
                flowOf(0)
            } else {
                tagDao.observeVisibleSyncedTagCount(authUserId)
            }
            val sharedTagUsageFlow = if (authUserId == null) {
                flowOf(emptyList())
            } else {
                tagDao.observeVisibleSyncedTagUrlCounts(authUserId)
            }
            val sharedTagGroupCountFlow = if (authUserId == null || syncDao == null) {
                flowOf(0)
            } else {
                syncDao.observeGroupCount(authUserId)
            }
            combine(
                urlEntryDao.observePersonalSavedEntriesCount(),
                tagDao.observeLocalOnlyTagCount(),
                sharedTagCountFlow,
                sharedTagUsageFlow,
                sharedTagGroupCountFlow,
            ) { personalUrlCount, normalTagCount, sharedTagCount, sharedTagUsages, sharedTagGroupCount ->
                val currentEntitlements = entitlements
                UsageSummary(
                    personalUrlCount = personalUrlCount,
                    normalTagCount = normalTagCount,
                    sharedTagCount = sharedTagCount,
                    sharedTagGroupCount = sharedTagGroupCount,
                    sharedTagUsages = sharedTagUsages.map { usage ->
                        SharedTagUsage(
                            tagId = usage.tagId,
                            tagName = usage.tagName,
                            urlCount = usage.urlCount,
                            limit = currentEntitlements.limits.sharedTagUrlLimitPerTag,
                        )
                    },
                )
            }
        }
    }
}
