package com.openminis.app.assistant

import com.openminis.app.ProductionSources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Structural wiring tests for Android Default Digital Assistant integration.
 */
class AssistantWiringTest {

    @Test
    fun `android manifest declares VoiceInteractionService and SessionService correctly`() {
        val manifest = File("src/main/AndroidManifest.xml").readText()

        // Service declaration
        assertTrue(manifest.contains("android:name=\".assistant.MinisVoiceInteractionService\""))
        assertTrue(manifest.contains("android:permission=\"android.permission.BIND_VOICE_INTERACTION\""))
        assertTrue(manifest.contains("<action android:name=\"android.service.voice.VoiceInteractionService\" />"))
        assertTrue(manifest.contains("android:resource=\"@xml/voice_interaction_service\""))

        // Session service declaration
        assertTrue(manifest.contains("android:name=\".assistant.MinisVoiceInteractionSessionService\""))

        // ACTION_ASSIST trampoline
        assertTrue(manifest.contains("android:name=\".assistant.AssistActivity\""))
        assertTrue(manifest.contains("<action android:name=\"android.intent.action.ASSIST\" />"))
    }

    @Test
    fun `voice interaction service xml metadata defines session service and assist flags`() {
        val xml = File("src/main/res/xml/voice_interaction_service.xml").readText()

        assertTrue(xml.contains("android:sessionService=\"com.openminis.app.assistant.MinisVoiceInteractionSessionService\""))
        assertTrue(xml.contains("android:supportsAssist=\"true\""))
        assertTrue(xml.contains("android:supportsLaunchFromKeyguard=\"true\""))
    }

    @Test
    fun `AssistantManager creates valid settings intent`() {
        val intent = AssistantManager.createAssistantSettingsIntent()
        assertNotNull(intent.action)
        assertTrue(intent.flags and android.content.Intent.FLAG_ACTIVITY_NEW_TASK != 0)
    }

    @Test
    fun `background settings screen includes Default Assistant row and lifecycle check`() {
        val src = ProductionSources.read("ui/settings/BackgroundSettingsScreen.kt")
        assertTrue(src.contains("AssistantManager.isActiveAssistant"))
        assertTrue(src.contains("default_assistant_title"))
        assertTrue(src.contains("default_assistant_status_active"))
    }
}
