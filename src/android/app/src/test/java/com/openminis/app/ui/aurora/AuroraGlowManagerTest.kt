package com.openminis.app.ui.aurora

import com.openminis.app.service.ToolOverlayController
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [AuroraGlowManager] pure logic and decommission contract.
 */
class AuroraGlowManagerTest {

    @Test
    fun `shouldSaveActive saves on first occurrence and refuses overwrite`() {
        // First session encountering aurora glow -> must save
        assertTrue(AuroraGlowManager.shouldSaveActive(alreadySaved = false))

        // Subsequent session while already active -> do not overwrite
        assertFalse(AuroraGlowManager.shouldSaveActive(alreadySaved = true))
    }

    @Test
    fun `constants adhere to contract`() {
        assertEquals("minis_interference_prefs", AuroraGlowManager.PREF_NAME)
        assertEquals("aurora_glow_active", AuroraGlowManager.KEY_AURORA_ACTIVE)
    }

    @Test
    fun `aurora states contain all required states`() {
        val states = AuroraState.values().map { it.name }.toSet()
        assertTrue(states.contains("IDLE"))
        assertTrue(states.contains("THINKING"))
        assertTrue(states.contains("EXECUTING"))
        assertTrue(states.contains("PAUSED"))
        assertTrue(states.contains("COMPLETED"))
        assertTrue(states.contains("LISTENING"))
        assertTrue(states.contains("INTERVENTION"))
    }

    @Test
    fun `old floating tool overlay capsule is explicitly decommissioned`() {
        assertTrue(ToolOverlayController.DECOMMISSIONED)
    }

    @Test
    fun `shutter blink listener receives hide and restore dispatches`() {
        var hideCount = 0
        var restoreCount = 0

        val listener = object : ShutterBlinkListener {
            override fun onShutterHide() {
                hideCount++
            }

            override fun onShutterRestore() {
                restoreCount++
            }
        }

        AuroraGlowManager.registerShutterListener(listener)
        AuroraGlowManager.hideForShutter(10L)
        assertEquals(1, hideCount)

        AuroraGlowManager.restoreAfterShutter()
        // Wait briefly for main handler dispatch if not on main thread
        Thread.sleep(50)
        assertEquals(1, restoreCount)

        AuroraGlowManager.unregisterShutterListener(listener)
        AuroraGlowManager.hideForShutter(10L)
        assertEquals(1, hideCount) // Should remain 1 after unregister
    }
}

