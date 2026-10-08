package jp.mimac.urlsaver

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.view.KeyEvent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso
import androidx.test.platform.app.InstrumentationRegistry
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import jp.mimac.urlsaver.data.UrlEntryEntity
import jp.mimac.urlsaver.domain.ContentContext
import jp.mimac.urlsaver.domain.CreateTagResult
import jp.mimac.urlsaver.domain.MetadataState
import jp.mimac.urlsaver.domain.ServiceType
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import java.io.File

class AiHandoffFlowTest {
    private val captureRun = "run-${System.currentTimeMillis()}"
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun providerSelectionAndSingleSend_keepNativeShareAndHomeRoutes() {
        // The fixture must never be written to a person's physical device.
        check(Build.HARDWARE == "ranchu" || Build.HARDWARE == "goldfish")
        val app = ApplicationProvider.getApplicationContext<Context>() as UrlSaverApp
        val fixtureTag = "AI動作確認"
        runBlocking {
            val tagId = when (val result = app.container.tagRepository.createLocalTagWithResult(fixtureTag)) {
                is CreateTagResult.Success -> result.tagId
                CreateTagResult.Duplicate -> requireNotNull(app.container.tagRepository.findLocalTagIdByName(fixtureTag))
                else -> error("Could not create the local fixture tag: $result")
            }
            val now = System.currentTimeMillis()
            val url = "https://example.invalid/ai-handoff-$now"
            val entryId = app.container.database.urlEntryDao().insert(
                UrlEntryEntity(
                    originalUrl = url,
                    normalizedUrl = url,
                    displayUrl = url,
                    openUrl = url,
                    normalizedHost = "example.invalid",
                    rawSourceHost = "example.invalid",
                    serviceType = ServiceType.WEB,
                    contentContext = ContentContext.STANDARD,
                    userTitle = "AI動作確認リンク",
                    metadataState = MetadataState.READY,
                    createdAt = now,
                    updatedAt = now,
                ),
            )
            app.container.tagRepository.assignTag(tagId, entryId)
        }
        composeRule.waitForIdle()
        if (composeRule.onAllNodesWithText("スキップ").fetchSemanticsNodes().isNotEmpty()) {
            composeRule.onNodeWithText("スキップ").performClick()
        }
        waitForText("グループ")
        listOf("グループ", "エクスポート", "タグ", "アーカイブ").forEach {
            composeRule.onNodeWithText(it).assertIsDisplayed()
        }
        composeRule.onNodeWithContentDescription("追加").assertIsDisplayed()
        capture("01-home.png")

        listOf("ChatGPT", "Gemini", "Claude", "DeepSeek").forEachIndexed { index, provider ->
            composeRule.onNodeWithContentDescription("AI").performClick()
            waitForText("AIを選ぶ")
            listOf("ChatGPT", "Gemini", "Claude", "DeepSeek").forEach {
                composeRule.onNodeWithText(it).assertIsDisplayed()
            }
            if (index == 0) capture("02-provider-chooser.png")
            composeRule.onNodeWithText(provider).performClick()
            waitForText("${provider}に送る")
            composeRule.onNodeWithText("${provider}に送る").assertIsNotEnabled()
            capture("0${index + 3}-${provider.lowercase()}-unselected.png")
            Espresso.pressBack()
            waitForText("グループ")
        }

        composeRule.onNodeWithContentDescription("AI").performClick()
        waitForText("AIを選ぶ")
        composeRule.onNodeWithText("Gemini").performClick()
        waitForText(fixtureTag)
        composeRule.onNode(
            hasText(fixtureTag) and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Checkbox),
        ).performClick()
        composeRule.waitUntil(30_000) {
            composeRule.onAllNodesWithText("対象").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Geminiに送る").assertIsEnabled()
        capture("07-gemini-ready.png")
        composeRule.onNodeWithText("Geminiに送る").performClick()
        composeRule.waitUntil(30_000) {
            InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow
                ?.packageName?.toString() == "com.android.intentresolver"
        }
        capture("08-native-share.png")
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        composeRule.waitForIdle()
        Espresso.pressBack()
        waitForText("グループ")

        composeRule.onNodeWithContentDescription("メニュー").performClick()
        composeRule.onNodeWithText("使い方").performClick()
        waitForText("まず覚える")
        composeRule.onNodeWithText("便利な操作").assertIsDisplayed()
        capture("09-usage-guide.png")
        composeRule.onNodeWithText("戻る").performClick()
        waitForText("グループ")
        composeRule.onNodeWithText("エクスポート").performClick()
        waitForText("ZIP")
        composeRule.onNodeWithText("JSON").assertIsDisplayed()
        capture("10-normal-export.png")
    }

    private fun waitForText(text: String) {
        composeRule.waitUntil(30_000) {
            composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.waitForIdle()
    }

    private fun capture(name: String) {
        composeRule.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        instrumentation.uiAutomation.waitForIdle(500, 5_000)
        val app = ApplicationProvider.getApplicationContext<Context>()
        val directory = File(app.getExternalFilesDir(null), "ai-handoff-ui-review/$captureRun").apply { mkdirs() }
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        File(directory, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
