package com.hbhaq.metricme

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import android.graphics.Bitmap

class NavigationTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private fun ready() { compose.waitUntil(15000) { compose.onAllNodesWithText("Message MetricMe").fetchSemanticsNodes().isNotEmpty() } }
    private fun screenshot(name:String) {
        if(android.os.Build.VERSION.SDK_INT<29) return
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val values=android.content.ContentValues().apply { put(android.provider.MediaStore.Downloads.DISPLAY_NAME,name+".png"); put(android.provider.MediaStore.Downloads.MIME_TYPE,"image/png"); put(android.provider.MediaStore.Downloads.RELATIVE_PATH,"Download/metricme-ci") }
        val uri=context.contentResolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI,values)!!
        context.contentResolver.openOutputStream(uri)!!.use { compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG,100,it) }
    }
    @Test fun profileDraftSurvivesRotationAndCancelDoesNotSave() {
        ready()
        compose.onNodeWithTag("nav-you").performClick()
        val edit=hasText("Add details") or hasText("Edit details")
        compose.onNode(edit).performClick()
        compose.onNodeWithText("Name").performTextInput("Unsaved draft")
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText("Unsaved draft").assertExists()
        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithText("Unsaved draft").assertDoesNotExist()
        screenshot("you")
    }
    @Test fun allFourTabsAreReachableAndInsightsDraftSurvivesSwitchingAndRotation() {
        ready()
        repeat(3) {
            compose.onNodeWithTag("nav-insights").performClick()
            compose.onNodeWithText("Ask your history").assertIsDisplayed()
            compose.onNodeWithTag("nav-you").performClick()
            compose.onNodeWithText("Measurement history").assertExists()
            compose.onNodeWithTag("nav-setup").performClick()
            compose.onNodeWithText("AI connection").assertExists()
            compose.onNodeWithTag("nav-journal").performClick()
            compose.onNodeWithText("Message MetricMe").assertExists()
        }
        compose.onNodeWithTag("nav-insights").performClick()
        compose.onNodeWithText("Ask about your history").performTextInput("When did I last have a burger?")
        compose.onNodeWithTag("nav-journal").performClick()
        compose.onNodeWithTag("nav-insights").performClick()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText("When did I last have a burger?").assertExists()
        screenshot("insights")
    }
    @Test fun setupContainsAccountAndKeepsTechnicalFieldsCollapsed() {
        ready()
        compose.onNodeWithTag("nav-setup").performClick()
        compose.onNodeWithText("AI connection").assertExists()
        compose.onNodeWithText("Sign in with a code").assertExists()
        compose.onNodeWithText("Backend HTTPS URL").assertDoesNotExist()
        compose.onNodeWithText("Older AI drafts").assertDoesNotExist()
        screenshot("setup")
        compose.onNodeWithTag("nav-journal").performClick()
        compose.onNodeWithText("Message MetricMe").performTextInput("I slept seven hours")
        compose.onNodeWithContentDescription("Send message").performClick()
        compose.onNodeWithText("AI connection").assertExists()
    }
    @Test fun loggingIsChatWithAttachmentsAndNoCategoryPicker() {
        ready()
        compose.onNodeWithContentDescription("Record a voice note").assertExists()
        compose.onNodeWithText("All").assertDoesNotExist()
        compose.onNodeWithText("Your entries").assertDoesNotExist()
        compose.onNodeWithText("Offline manual records").assertDoesNotExist()
        screenshot("log")
        compose.onNodeWithContentDescription("Add attachment").performClick()
        compose.onNodeWithText("Photo library").assertIsDisplayed()
        compose.onNodeWithText("Camera").assertIsDisplayed()
        compose.onNodeWithText("Audio file").assertIsDisplayed()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText("Message MetricMe").performTextInput("I slept seven hours and had eggs")
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText("I slept seven hours and had eggs").assertExists()
    }
}
