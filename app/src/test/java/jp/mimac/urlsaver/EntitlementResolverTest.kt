package jp.mimac.urlsaver

import jp.mimac.urlsaver.domain.DefaultEntitlementResolver
import jp.mimac.urlsaver.domain.EntitlementGrant
import jp.mimac.urlsaver.domain.EntitlementSource
import jp.mimac.urlsaver.domain.LaunchStandardPlan
import jp.mimac.urlsaver.domain.PlanType
import org.junit.Assert.assertEquals
import org.junit.Test

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
}
