package jp.mimac.urlsaver.domain

enum class EntitlementSource {
    LOCAL_DEFAULT,
    STORE_SUBSCRIPTION,
    STORE_PROMO_CODE,
    ADMIN_GRANT,
    REFERRAL_GRANT,
}

data class EntitlementGrant(
    val planType: PlanType,
    val source: EntitlementSource,
    val startsAt: Long,
    val endsAt: Long? = null,
    val sourceId: String? = null,
    val note: String? = null,
) {
    fun isActiveAt(currentTimeMillis: Long): Boolean {
        val started = currentTimeMillis >= startsAt
        val notExpired = endsAt == null || currentTimeMillis < endsAt
        return started && notExpired
    }
}

interface EntitlementResolver {
    fun resolve(currentTimeMillis: Long = System.currentTimeMillis()): FeatureEntitlements
}

class DefaultEntitlementResolver(
    private val defaultEntitlements: FeatureEntitlements = LaunchStandardPlan.entitlements,
    private val grantsProvider: () -> List<EntitlementGrant> = { emptyList() },
) : EntitlementResolver {

    override fun resolve(currentTimeMillis: Long): FeatureEntitlements {
        val activeGrant = grantsProvider()
            .asSequence()
            .filter { it.isActiveAt(currentTimeMillis) }
            .minByOrNull { it.source.priority }

        return if (activeGrant == null) {
            defaultEntitlements
        } else {
            PlanEntitlements.forPlan(activeGrant.planType)
        }
    }
}

private val EntitlementSource.priority: Int
    get() = when (this) {
        EntitlementSource.ADMIN_GRANT -> 0
        EntitlementSource.STORE_SUBSCRIPTION -> 1
        EntitlementSource.STORE_PROMO_CODE -> 2
        EntitlementSource.REFERRAL_GRANT -> 3
        EntitlementSource.LOCAL_DEFAULT -> 4
    }
