package jp.mimac.urlsaver.domain

import jp.mimac.urlsaver.BuildConfig

object BuildVariantEntitlementOverrides {
    @Suppress("UNUSED_PARAMETER")
    fun grants(currentTimeMillis: Long): List<EntitlementGrant> {
        if (!BuildConfig.DEBUG) {
            return emptyList()
        }
        val planType = System.getProperty(DEBUG_PLAN_PROPERTY)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.toPlanTypeOrNull()
            ?: return emptyList()
        return listOf(
            EntitlementGrant(
                planType = planType,
                source = EntitlementSource.ADMIN_GRANT,
                status = EntitlementGrantStatus.ACTIVE,
                startsAt = 0L,
                sourceId = "debug_override",
            ),
        )
    }

    const val DEBUG_PLAN_PROPERTY = "urlsaver.debug.entitlement.plan"

    private fun String.toPlanTypeOrNull(): PlanType? {
        return when (lowercase()) {
            "free" -> PlanType.FREE
            "launch_standard" -> PlanType.LAUNCH_STANDARD
            "pro" -> PlanType.PRO
            "promo_pro" -> PlanType.PROMO_PRO
            else -> null
        }
    }
}
