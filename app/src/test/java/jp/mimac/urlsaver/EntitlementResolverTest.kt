package jp.mimac.urlsaver

import jp.mimac.urlsaver.domain.BuildVariantEntitlementOverrides
import jp.mimac.urlsaver.data.EntitlementGrantRemoteDataSource
import jp.mimac.urlsaver.data.EntitlementGrantRepository
import jp.mimac.urlsaver.data.EntitlementGrantStore
import jp.mimac.urlsaver.data.SharedTagAuthSession
import jp.mimac.urlsaver.data.SharedTagAuthSessionProvider
import jp.mimac.urlsaver.domain.DefaultEntitlementResolver
import jp.mimac.urlsaver.domain.EntitlementGrant
import jp.mimac.urlsaver.domain.EntitlementGrantStatus
import jp.mimac.urlsaver.domain.EntitlementSource
import jp.mimac.urlsaver.domain.LaunchStandardPlan
import jp.mimac.urlsaver.domain.LimitChecker
import jp.mimac.urlsaver.domain.LimitResult
import jp.mimac.urlsaver.domain.LimitTarget
import jp.mimac.urlsaver.domain.PlanType
import jp.mimac.urlsaver.domain.ProPlan
import jp.mimac.urlsaver.domain.SharedTagUsage
import jp.mimac.urlsaver.domain.UsageSummary
import jp.mimac.urlsaver.util.AppClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class EntitlementResolverTest {

    @Test
    fun resolve_withoutGrant_returnsLaunchStandard() {
        val resolver = DefaultEntitlementResolver(
            defaultEntitlements = LaunchStandardPlan.entitlements,
            grantsProvider = { emptyList() },
        )

        val resolved = resolver.resolve(currentTimeMillis = 1_000L)

        assertEquals(PlanType.LAUNCH_STANDARD, resolved.planType)
        assertEquals(200, resolved.limits.personalUrlLimit)
    }

    @Test
    fun resolve_withActiveAdminGrant_prioritizesGrantPlan() {
        val resolver = DefaultEntitlementResolver(
            defaultEntitlements = LaunchStandardPlan.entitlements,
            grantsProvider = {
                listOf(
                    EntitlementGrant(
                        planType = PlanType.PROMO_PRO,
                        source = EntitlementSource.ADMIN_GRANT,
                        startsAt = 0L,
                        endsAt = 10_000L,
                    ),
                )
            },
        )

        val resolved = resolver.resolve(currentTimeMillis = 5_000L)

        assertEquals(PlanType.PROMO_PRO, resolved.planType)
        assertEquals(10_000, resolved.limits.personalUrlLimit)
    }

    @Test
    fun resolve_withActiveProGrant_returnsPro() {
        val resolver = DefaultEntitlementResolver(
            defaultEntitlements = LaunchStandardPlan.entitlements,
            grantsProvider = {
                listOf(
                    EntitlementGrant(
                        planType = PlanType.PRO,
                        source = EntitlementSource.STORE_SUBSCRIPTION,
                        startsAt = 0L,
                    ),
                )
            },
        )

        val resolved = resolver.resolve(currentTimeMillis = 5_000L)

        assertEquals(PlanType.PRO, resolved.planType)
        assertEquals(10_000, resolved.limits.personalUrlLimit)
    }

    @Test
    fun resolve_withExpiredGrant_fallsBackToLaunchStandard() {
        val resolver = DefaultEntitlementResolver(
            defaultEntitlements = LaunchStandardPlan.entitlements,
            grantsProvider = {
                listOf(
                    EntitlementGrant(
                        planType = PlanType.PROMO_PRO,
                        source = EntitlementSource.ADMIN_GRANT,
                        startsAt = 0L,
                        endsAt = 1_000L,
                    ),
                )
            },
        )

        val resolved = resolver.resolve(currentTimeMillis = 5_000L)

        assertEquals(PlanType.LAUNCH_STANDARD, resolved.planType)
        assertEquals(200, resolved.limits.personalUrlLimit)
    }

    @Test
    fun resolve_withRevokedGrant_fallsBackToLaunchStandard() {
        val resolver = DefaultEntitlementResolver(
            defaultEntitlements = LaunchStandardPlan.entitlements,
            grantsProvider = {
                listOf(
                    EntitlementGrant(
                        planType = PlanType.PRO,
                        source = EntitlementSource.STORE_SUBSCRIPTION,
                        status = EntitlementGrantStatus.REVOKED,
                        startsAt = 0L,
                    ),
                )
            },
        )

        val resolved = resolver.resolve(currentTimeMillis = 5_000L)

        assertEquals(PlanType.LAUNCH_STANDARD, resolved.planType)
    }

    @Test
    fun resolve_withPendingGrant_fallsBackToLaunchStandard() {
        val resolver = DefaultEntitlementResolver(
            defaultEntitlements = LaunchStandardPlan.entitlements,
            grantsProvider = {
                listOf(
                    EntitlementGrant(
                        planType = PlanType.PRO,
                        source = EntitlementSource.STORE_SUBSCRIPTION,
                        status = EntitlementGrantStatus.PENDING,
                        startsAt = 0L,
                    ),
                )
            },
        )

        val resolved = resolver.resolve(currentTimeMillis = 5_000L)

        assertEquals(PlanType.LAUNCH_STANDARD, resolved.planType)
    }

    @Test
    fun resolve_withMultipleValidGrants_highestPlanWins() {
        val resolver = DefaultEntitlementResolver(
            defaultEntitlements = LaunchStandardPlan.entitlements,
            grantsProvider = {
                listOf(
                    EntitlementGrant(
                        planType = PlanType.FREE,
                        source = EntitlementSource.ADMIN_GRANT,
                        startsAt = 0L,
                    ),
                    EntitlementGrant(
                        planType = PlanType.PRO,
                        source = EntitlementSource.STORE_SUBSCRIPTION,
                        startsAt = 0L,
                    ),
                )
            },
        )

        val resolved = resolver.resolve(currentTimeMillis = 5_000L)

        assertEquals(PlanType.PRO, resolved.planType)
    }

    @Test
    fun limitChecker_blocksAtLaunchStandardLimits() {
        val checker = LimitChecker(LaunchStandardPlan.entitlements)
        val result = checker.checkCanSavePersonalUrl(
            UsageSummary(
                personalUrlCount = LaunchStandardPlan.limits.personalUrlLimit,
                normalTagCount = 0,
                sharedTagCount = 0,
                sharedTagUsages = emptyList(),
            ),
        )

        assertTrue(result is LimitResult.Blocked)
        assertEquals(LimitTarget.PERSONAL_URL, (result as LimitResult.Blocked).target)
    }

    @Test
    fun limitChecker_allowsProBeyondLaunchStandardLimits() {
        val checker = LimitChecker(ProPlan.entitlements)
        val result = checker.checkCanSavePersonalUrl(
            UsageSummary(
                personalUrlCount = LaunchStandardPlan.limits.personalUrlLimit,
                normalTagCount = 0,
                sharedTagCount = 0,
                sharedTagUsages = emptyList(),
            ),
        )

        assertEquals(LimitResult.Allowed, result)
    }

    @Test
    fun limitChecker_blocksSharedTagUrlAtPerTagLimit() {
        val checker = LimitChecker(LaunchStandardPlan.entitlements)
        val result = checker.checkCanAddUrlToSharedTag(
            usage = UsageSummary(
                personalUrlCount = 0,
                normalTagCount = 0,
                sharedTagCount = 0,
                sharedTagUsages = listOf(
                    SharedTagUsage(
                        tagId = 10L,
                        tagName = "共有",
                        urlCount = LaunchStandardPlan.limits.sharedTagUrlLimitPerTag,
                        limit = LaunchStandardPlan.limits.sharedTagUrlLimitPerTag,
                    ),
                ),
            ),
            tagId = 10L,
        )

        assertTrue(result is LimitResult.Blocked)
        assertEquals(LimitTarget.SHARED_TAG_URL, (result as LimitResult.Blocked).target)
    }

    @Test
    fun debugOverrideUsesSystemPropertyInDebugUnitTests() {
        try {
            System.setProperty(BuildVariantEntitlementOverrides.DEBUG_PLAN_PROPERTY, "pro")

            val grants = BuildVariantEntitlementOverrides.grants(currentTimeMillis = 5_000L)

            assertEquals(1, grants.size)
            assertEquals(PlanType.PRO, grants.single().planType)
            assertEquals(EntitlementSource.ADMIN_GRANT, grants.single().source)
        } finally {
            System.clearProperty(BuildVariantEntitlementOverrides.DEBUG_PLAN_PROPERTY)
        }
    }

    @Test
    fun entitlementRepository_fetchFailureUsesLastKnownGrants() = runTest {
        val grant = EntitlementGrant(
            planType = PlanType.PRO,
            source = EntitlementSource.STORE_SUBSCRIPTION,
            startsAt = 0L,
        )
        val repository = EntitlementGrantRepository(
            authSessionProvider = FakeSessionProvider(
                SharedTagAuthSession(
                    authUserId = "user-1",
                    accessToken = "access-token",
                ),
            ),
            remoteDataSource = object : EntitlementGrantRemoteDataSource {
                override suspend fun fetchGrants(session: SharedTagAuthSession): List<EntitlementGrant> {
                    throw IOException("offline")
                }
            },
            grantStore = FakeGrantStore(listOf(grant)),
            clock = FixedClock(now = 5_000L),
        )

        val loaded = repository.refreshForCurrentSession()

        assertEquals(listOf(grant), loaded)
    }

    private class FakeSessionProvider(
        session: SharedTagAuthSession?,
    ) : SharedTagAuthSessionProvider {
        private val state = MutableStateFlow(session)
        override val session: StateFlow<SharedTagAuthSession?> = state

        override fun updateSession(newSession: SharedTagAuthSession?) {
            state.value = newSession
        }
    }

    private class FakeGrantStore(
        private val grants: List<EntitlementGrant>,
    ) : EntitlementGrantStore {
        override suspend fun loadLastKnownGrants(
            authUserId: String,
            currentTimeMillis: Long,
        ): List<EntitlementGrant> = grants

        override suspend fun saveLastKnownGrants(
            authUserId: String,
            grants: List<EntitlementGrant>,
            fetchedAtMillis: Long,
        ) = Unit

        override fun cachedGrantsSnapshot(
            authUserId: String?,
            currentTimeMillis: Long,
        ): List<EntitlementGrant> = grants
    }

    private class FixedClock(
        private val now: Long,
    ) : AppClock {
        override fun nowEpochMillis(): Long = now
    }
}
