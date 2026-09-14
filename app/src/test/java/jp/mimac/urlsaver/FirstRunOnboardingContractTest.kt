package jp.mimac.urlsaver

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FirstRunOnboardingContractTest {
    @Test
    fun freshInstallRemainsEligibleAndExistingInstallIsMigratedAsSeen() {
        assertFalse(
            migratedFirstRunOnboardingSeen(
                migrationAlreadyCompleted = false,
                legacySeen = false,
                hadExistingDatabaseBeforeStartup = false,
            ),
        )
        assertTrue(
            migratedFirstRunOnboardingSeen(
                migrationAlreadyCompleted = false,
                legacySeen = false,
                hadExistingDatabaseBeforeStartup = true,
            ),
        )
        assertTrue(
            migratedFirstRunOnboardingSeen(
                migrationAlreadyCompleted = false,
                legacySeen = true,
                hadExistingDatabaseBeforeStartup = false,
            ),
        )
        assertFalse(
            migratedFirstRunOnboardingSeen(
                migrationAlreadyCompleted = true,
                legacySeen = false,
                hadExistingDatabaseBeforeStartup = true,
            ),
        )
    }

    @Test
    fun onboardingExplainsPrimaryFlowAndStaysSeparateFromManualGuide() {
        val source = File("src/main/java/jp/mimac/urlsaver/ui/UrlSaverRoot.kt").readText()

        assertTrue(source.contains("あとで見たいものを保存"))
        assertTrue(source.contains("タグと検索ですぐ見つける"))
        assertTrue(source.contains("詳しい操作は「使い方」へ"))
        assertTrue(source.contains("showUsageGuide = true"))
        assertFalse(source.contains("あまり怒らないでね"))
    }
}
