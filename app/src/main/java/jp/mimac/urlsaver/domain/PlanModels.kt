package jp.mimac.urlsaver.domain

enum class PlanType {
    FREE,
    LAUNCH_STANDARD,
    PRO,
    PROMO_PRO,
}

data class PlanLimits(
    val personalUrlLimit: Int,
    val normalTagLimit: Int,
    val sharedTagLimit: Int,
    val sharedTagUrlLimitPerTag: Int,
    val sharedTagGroupLimit: Int = 0,
    val sharedTagGroupMemberLimit: Int = 0,
)

data class FeatureEntitlements(
    val planType: PlanType,
    val limits: PlanLimits,
    val subscriptionEnabled: Boolean,
    val shouldShowAds: Boolean,
    val exportEnabled: Boolean,
    val sharedSyncEnabled: Boolean,
)

data class SharedTagUsage(
    val tagId: Long,
    val tagName: String,
    val urlCount: Int,
    val limit: Int,
)

data class UsageSummary(
    val personalUrlCount: Int,
    val normalTagCount: Int,
    val sharedTagCount: Int,
    val sharedTagGroupCount: Int = 0,
    val sharedTagUsages: List<SharedTagUsage> = emptyList(),
) {
    fun sharedTagUsageOrNull(tagId: Long): SharedTagUsage? = sharedTagUsages.firstOrNull { it.tagId == tagId }
}

sealed interface LimitResult {
    data object Allowed : LimitResult
    data class Blocked(
        val target: LimitTarget,
        val message: String,
    ) : LimitResult
}

enum class LimitTarget {
    PERSONAL_URL,
    NORMAL_TAG,
    SHARED_TAG,
    SHARED_TAG_URL,
    SHARED_TAG_GROUP,
}

class LimitChecker(
    private val entitlements: FeatureEntitlements,
) {
    private val limits = entitlements.limits
    private val planLabel = entitlements.planType.limitMessageLabel()

    fun checkCanSavePersonalUrl(usage: UsageSummary): LimitResult {
        if (usage.personalUrlCount >= limits.personalUrlLimit) {
            return LimitResult.Blocked(
                target = LimitTarget.PERSONAL_URL,
                message = "${planLabel}の保存上限に達しました。不要なURLを整理してから追加してください。",
            )
        }
        return LimitResult.Allowed
    }

    fun checkCanCreateNormalTag(usage: UsageSummary): LimitResult {
        if (usage.normalTagCount >= limits.normalTagLimit) {
            return LimitResult.Blocked(
                target = LimitTarget.NORMAL_TAG,
                message = "通常タグは${planLabel}では${limits.normalTagLimit}個まで作成できます。",
            )
        }
        return LimitResult.Allowed
    }

    fun checkCanCreateSharedTag(usage: UsageSummary): LimitResult {
        if (usage.sharedTagCount >= limits.sharedTagLimit) {
            return LimitResult.Blocked(
                target = LimitTarget.SHARED_TAG,
                message = "共有タグは${planLabel}では${limits.sharedTagLimit}個まで作成できます。",
            )
        }
        return LimitResult.Allowed
    }

    fun checkCanAddUrlToSharedTag(usage: UsageSummary, tagId: Long): LimitResult {
        val tagUsage = usage.sharedTagUsageOrNull(tagId)
        if (tagUsage != null && tagUsage.urlCount >= limits.sharedTagUrlLimitPerTag) {
            return LimitResult.Blocked(
                target = LimitTarget.SHARED_TAG_URL,
                message = "この共有タグには${limits.sharedTagUrlLimitPerTag}件までURLを追加できます。",
            )
        }
        return LimitResult.Allowed
    }

    fun checkCanCreateSharedTagGroup(usage: UsageSummary): LimitResult {
        if (usage.sharedTagGroupCount >= limits.sharedTagGroupLimit) {
            return LimitResult.Blocked(
                target = LimitTarget.SHARED_TAG_GROUP,
                message = "グループは${planLabel}では${limits.sharedTagGroupLimit}個まで作成できます。",
            )
        }
        return LimitResult.Allowed
    }
}

object LaunchStandardPlan {
    val limits: PlanLimits = PlanLimits(
        personalUrlLimit = 200,
        normalTagLimit = 10,
        sharedTagLimit = 2,
        sharedTagUrlLimitPerTag = 20,
        sharedTagGroupLimit = 2,
        sharedTagGroupMemberLimit = 10,
    )

    val entitlements: FeatureEntitlements = FeatureEntitlements(
        planType = PlanType.LAUNCH_STANDARD,
        limits = limits,
        subscriptionEnabled = false,
        shouldShowAds = false,
        exportEnabled = true,
        sharedSyncEnabled = true,
    )
}

object FreePlan {
    val limits: PlanLimits = PlanLimits(
        personalUrlLimit = 100,
        normalTagLimit = 5,
        sharedTagLimit = 1,
        sharedTagUrlLimitPerTag = 10,
        sharedTagGroupLimit = 0,
        sharedTagGroupMemberLimit = 0,
    )

    val entitlements: FeatureEntitlements = FeatureEntitlements(
        planType = PlanType.FREE,
        limits = limits,
        subscriptionEnabled = false,
        shouldShowAds = true,
        exportEnabled = false,
        sharedSyncEnabled = false,
    )
}

object ProPlan {
    val limits: PlanLimits = PlanLimits(
        personalUrlLimit = 10_000,
        normalTagLimit = 200,
        sharedTagLimit = 50,
        sharedTagUrlLimitPerTag = 10_000,
        sharedTagGroupLimit = 50,
        sharedTagGroupMemberLimit = 100,
    )

    val entitlements: FeatureEntitlements = FeatureEntitlements(
        planType = PlanType.PRO,
        limits = limits,
        subscriptionEnabled = false,
        shouldShowAds = false,
        exportEnabled = true,
        sharedSyncEnabled = true,
    )
}

object PromoProPlan {
    val entitlements: FeatureEntitlements = ProPlan.entitlements.copy(
        planType = PlanType.PROMO_PRO,
        subscriptionEnabled = false,
    )
}

object PlanEntitlements {
    fun forPlan(planType: PlanType): FeatureEntitlements {
        return when (planType) {
            PlanType.FREE -> FreePlan.entitlements
            PlanType.LAUNCH_STANDARD -> LaunchStandardPlan.entitlements
            PlanType.PRO -> ProPlan.entitlements
            PlanType.PROMO_PRO -> PromoProPlan.entitlements
        }
    }
}

object AdPolicy {
    fun shouldShowAds(entitlements: FeatureEntitlements): Boolean = entitlements.shouldShowAds
}

private fun PlanType.limitMessageLabel(): String {
    return when (this) {
        PlanType.FREE -> "無料プラン"
        PlanType.LAUNCH_STANDARD -> "ローンチ版"
        PlanType.PRO -> "Proプラン"
        PlanType.PROMO_PRO -> "優待Pro"
    }
}
