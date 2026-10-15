package com.openminis.app.notification

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [HeadsUpNotificationManager] pure logic.
 */
class HeadsUpNotificationManagerTest {

    @Test
    fun `shouldSaveOriginal saves on first occurrence and refuses overwrite`() {
        // First session encountering heads-up setting -> must save
        assertTrue(HeadsUpNotificationManager.shouldSaveOriginal(currentSetting = 1, alreadySaved = false))
        assertTrue(HeadsUpNotificationManager.shouldSaveOriginal(currentSetting = 0, alreadySaved = false))
        assertTrue(HeadsUpNotificationManager.shouldSaveOriginal(currentSetting = null, alreadySaved = false))

        // Subsequent session while already saved -> do not overwrite
        assertFalse(HeadsUpNotificationManager.shouldSaveOriginal(currentSetting = 1, alreadySaved = true))
        assertFalse(HeadsUpNotificationManager.shouldSaveOriginal(currentSetting = 0, alreadySaved = true))
    }

    @Test
    fun `constants adhere to contract`() {
        assertEquals("minis_interference_prefs", HeadsUpNotificationManager.PREF_NAME)
        assertEquals("saved_heads_up_enabled", HeadsUpNotificationManager.KEY_SAVED_HEADS_UP)
    }
}
