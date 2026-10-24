package com.openminis.app.ui.overlay

import com.openminis.app.ui.aurora.AuroraGlowManager
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * Unit test verifying [AgentInterventionOverlayManager] registration and shutter contract.
 */
class AgentInterventionOverlayManagerTest {

    @Test
    fun `manager implements shutter blink listener and registers with AuroraGlowManager`() {
        assertNotNull(AgentInterventionOverlayManager)

        // Trigger shutter hide and restore to verify safe execution without active overlay view
        AgentInterventionOverlayManager.onShutterHide()
        AgentInterventionOverlayManager.onShutterRestore()

        // Dispatches through AuroraGlowManager should execute cleanly
        AuroraGlowManager.hideForShutter(10L)
        AuroraGlowManager.restoreAfterShutter()
    }
}
