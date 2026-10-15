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
    }

    @Test
    fun `old floating tool overlay capsule is explicitly decommissioned`() {
        assertTrue(ToolOverlayController.DECOMMISSIONED)
    }
}
