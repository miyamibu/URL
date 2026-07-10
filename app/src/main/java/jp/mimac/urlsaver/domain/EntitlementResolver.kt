package jp.mimac.urlsaver.domain

enum class EntitlementSource {
    STORE_SUBSCRIPTION,
    STORE_PROMO_CODE,
    ADMIN_GRANT,
    REFERRAL_GRANT,
}

enum class EntitlementGrantStatus {
    ACTIVE,
    REVOKED,
    PENDING,
}

data class EntitlementGrant(
    val planType: PlanType,
    val source: EntitlementSource,
    val status: EntitlementGrantStatus = EntitlementGrantStatus.ACTIVE,
    val startsAt: Long,
    val endsAt: Long? = null,
    val sourceId: String? = null,
    val note: String? = null,
) {
    fun isActiveAt(currentTimeMillis: Long): Boolean {
        if (status != EntitlementGrantStatus.ACTIVE) {
            return false
        }
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
            .sortedWith(
                compareBy<EntitlementGrant> { it.planType.priority }
                    .thenBy { it.source.priority }
                    .thenByDescending { it.startsAt },
            )
            .firstOrNull()

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
    }

private val PlanType.priority: Int
    get() = when (this) {
        PlanType.PROMO_PRO -> 0
        PlanType.PRO -> 1
        PlanType.STANDARD -> 2
        PlanType.LAUNCH_STANDARD -> 3
        PlanType.FREE -> 4
    }
