package jp.mimac.urlsaver

import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.espresso.Espresso
import jp.mimac.urlsaver.domain.ShareSaveResult
import jp.mimac.urlsaver.domain.TagWithCount
import jp.mimac.urlsaver.ui.ManualInputSheet
import jp.mimac.urlsaver.ui.theme.UrlSaverTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Pure synthetic state: no repository, account, database, or real link write. */
class ManualInputSheetDismissalTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun dragWhileSaving_failureKeepsInputTagsAndRetryVisible_thenCanDismiss() {
        exerciseFailureAfterDismissAttempt("drag") {
            composeRule.onNodeWithContentDescription("Drag handle").performTouchInput {
                swipe(center, Offset(centerX, height + 700f), 400)
            }
        }
    }

    @Test
    fun scrimWhileSaving_failureKeepsInputTagsAndRetryVisible_thenCanDismiss() {
        exerciseFailureAfterDismissAttempt("scrim") {
            val metrics = composeRule.activity.resources.displayMetrics
            val x = metrics.widthPixels / 2f
            val y = metrics.heightPixels * 0.1f
            val automation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation
            val time = android.os.SystemClock.uptimeMillis()
            val down = android.view.MotionEvent.obtain(time, time, android.view.MotionEvent.ACTION_DOWN, x, y, 0)
            val up = android.view.MotionEvent.obtain(time, time + 50, android.view.MotionEvent.ACTION_UP, x, y, 0)
            try {
                assertTrue(automation.injectInputEvent(down, true))
                assertTrue(automation.injectInputEvent(up, true))
            } finally {
                down.recycle(); up.recycle()
            }
        }
    }

    @Test
    fun backWhileSaving_failureKeepsInputTagsAndRetryVisible_thenCanDismiss() {
        exerciseFailureAfterDismissAttempt("back") { Espresso.pressBack() }
    }

    private fun exerciseFailureAfterDismissAttempt(caseName: String, dismissAttempt: () -> Unit) {
        val visible = mutableStateOf(true)
        val saving = mutableStateOf(false)
        val error = mutableStateOf<ShareSaveResult?>(null)
        var dismissCount = 0
        var saveCount = 0
        composeRule.setContent {
            UrlSaverTheme {
                ManualInputSheet(
                    visible = visible.value,
                    inputText = "https://example.invalid/synthetic-save-failure",
                    inputError = error.value,
                    localTags = listOf(TagWithCount(1L, "確認用の自作タグ", 0)),
                    selectedLocalTagIds = setOf(1L),
                    manualLocalTagError = null,
                    isManualSaving = saving.value,
                    onDismiss = { if (!saving.value) { dismissCount++; visible.value = false } },
                    onInputChange = {},
                    onPaste = {},
                    onSelectLocalTag = {},
                    onRequestCreateLocalTag = {},
                    onSave = { saveCount++; saving.value = true },
                )
            }
        }
        composeRule.onNodeWithTag("manual_input_tag_search").performTextInput("確認")
        Espresso.closeSoftKeyboard()
        composeRule.onNodeWithTag("manual_input_save").performClick()
        composeRule.onNodeWithTag("manual_input_save").assertIsNotEnabled()
        captureScreen("${caseName}-saving-before-dismiss-attempt")
        dismissAttempt()
        composeRule.waitForIdle()
        composeRule.runOnIdle {
            assertTrue(visible.value)
            assertEquals(0, dismissCount)
            assertEquals(1, saveCount)
            saving.value = false
            error.value = ShareSaveResult.SAVE_FAILED
        }
        composeRule.waitForIdle()
        captureScreen("${caseName}-failure-after-dismiss-attempt")
        composeRule.onNodeWithTag("manual_input_field")
            .assertIsDisplayed().assertTextContains("https://example.invalid/synthetic-save-failure")
        composeRule.onNodeWithTag("manual_input_selected_tags")
            .assertIsDisplayed().assertTextContains("確認用の自作タグ", substring = true)
        composeRule.onNodeWithTag("manual_input_save").assertIsDisplayed().assertIsEnabled()
        composeRule.onNodeWithText("もう一度保存").assertIsDisplayed()
        composeRule.onNodeWithTag("manual_input_tag_search").assertTextContains("確認")
        composeRule.onNodeWithTag("manual_input_save").performClick()
        composeRule.runOnIdle { assertEquals(2, saveCount); assertTrue(saving.value); saving.value = false }
        Espresso.pressBack()
        composeRule.waitForIdle()
        composeRule.runOnIdle { assertEquals(1, dismissCount); assertTrue(!visible.value) }
        composeRule.onNodeWithTag("manual_input_sheet").assertDoesNotExist()
    }
    private fun captureScreen(name: String) {
        val automation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation
        automation.executeShellCommand("mkdir -p /sdcard/Download/rinbam-sheet-qa-20261005").use {
            android.os.ParcelFileDescriptor.AutoCloseInputStream(it).use { stream -> stream.readBytes() }
        }
        automation.executeShellCommand("screencap -p /sdcard/Download/rinbam-sheet-qa-20261005/$name.png").use {
            android.os.ParcelFileDescriptor.AutoCloseInputStream(it).use { stream -> stream.readBytes() }
        }
    }

}
