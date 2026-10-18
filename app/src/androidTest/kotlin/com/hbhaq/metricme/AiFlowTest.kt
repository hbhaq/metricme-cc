package com.hbhaq.metricme

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.hbhaq.metricme.ai.*
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*
import androidx.compose.ui.graphics.asAndroidBitmap
import android.content.ContentValues
import android.provider.MediaStore
import android.graphics.Bitmap

class AiFlowTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private fun openAssist() {
        compose.waitUntil(15000) { compose.onAllNodes(hasScrollToIndexAction()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("You").performClick()
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Photo, audio & private drafts"))
        compose.onNodeWithText("Photo, audio & private drafts").performClick()
        compose.onNode(hasScrollToIndexAction()).performScrollToIndex(0)
        if(android.os.Build.VERSION.SDK_INT>=29) {
            val context=InstrumentationRegistry.getInstrumentation().targetContext
            val values=ContentValues().apply { put(MediaStore.Downloads.DISPLAY_NAME,"ai-assist.png"); put(MediaStore.Downloads.MIME_TYPE,"image/png"); put(MediaStore.Downloads.RELATIVE_PATH,"Download/metricme-ci") }
            val uri=context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,values)!!
            context.contentResolver.openOutputStream(uri)!!.use { compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG,100,it) }
        }
    }
    @Test fun offlineDraftEditsSurviveRotationWithoutUploading()=runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val graph=EntryPointAccessors.fromApplication(context,AiEntryPoint::class.java)
        val id=graph.drafts().create("FOOD",null,"image/jpeg")
        try {
            openAssist()
            compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Open draft")); compose.onNodeWithText("Open draft").performClick()
            compose.onNodeWithText("Optional context").performTextInput("Offline draft, no upload consent")
            compose.activityRule.scenario.recreate(); compose.onNodeWithText("Offline draft, no upload consent").assertExists()
            compose.onNodeWithText("Save review changes").performScrollTo().performClick()
            compose.waitUntil(10000) { compose.onAllNodesWithText("Save review changes").fetchSemanticsNodes().isEmpty() }
            assertTrue(graph.drafts().dao.draft(id)!!.content.contains("Offline draft, no upload consent"))
            assertNull(graph.drafts().dao.job(id))
            compose.onNodeWithText("Close draft").performClick()
        } finally { graph.queue().cancel(id,true) }
    }
}
