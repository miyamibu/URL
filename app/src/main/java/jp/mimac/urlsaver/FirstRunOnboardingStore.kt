package jp.mimac.urlsaver

import android.content.Context

internal const val FIRST_RUN_ONBOARDING_PREFERENCES = "rinbam_first_run_onboarding"
internal const val FIRST_RUN_ONBOARDING_SEEN_KEY = "has_seen_onboarding_v2"
private const val FIRST_RUN_ONBOARDING_MIGRATION_KEY = "onboarding_install_classified_v3"

internal fun migratedFirstRunOnboardingSeen(
    migrationAlreadyCompleted: Boolean,
    legacySeen: Boolean,
    hadExistingDatabaseBeforeStartup: Boolean,
): Boolean = if (migrationAlreadyCompleted) {
    legacySeen
} else {
    legacySeen || hadExistingDatabaseBeforeStartup
}

internal object FirstRunOnboardingStore {
    fun initialize(context: Context, hadExistingDatabaseBeforeStartup: Boolean) {
        val preferences = context.getSharedPreferences(FIRST_RUN_ONBOARDING_PREFERENCES, Context.MODE_PRIVATE)
        val migrationAlreadyCompleted = preferences.getBoolean(FIRST_RUN_ONBOARDING_MIGRATION_KEY, false)
        if (migrationAlreadyCompleted) return
        val seen = migratedFirstRunOnboardingSeen(
            migrationAlreadyCompleted = false,
            legacySeen = preferences.getBoolean(FIRST_RUN_ONBOARDING_SEEN_KEY, false),
            hadExistingDatabaseBeforeStartup = hadExistingDatabaseBeforeStartup,
        )
        preferences.edit()
            .putBoolean(FIRST_RUN_ONBOARDING_SEEN_KEY, seen)
            .putBoolean(FIRST_RUN_ONBOARDING_MIGRATION_KEY, true)
            .apply()
    }

    fun shouldShow(context: Context): Boolean {
        val preferences = context.getSharedPreferences(FIRST_RUN_ONBOARDING_PREFERENCES, Context.MODE_PRIVATE)
        return preferences.getBoolean(FIRST_RUN_ONBOARDING_MIGRATION_KEY, false) &&
            !preferences.getBoolean(FIRST_RUN_ONBOARDING_SEEN_KEY, false)
    }

    fun markSeen(context: Context) {
        context.getSharedPreferences(FIRST_RUN_ONBOARDING_PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(FIRST_RUN_ONBOARDING_SEEN_KEY, true)
            .apply()
    }
}
