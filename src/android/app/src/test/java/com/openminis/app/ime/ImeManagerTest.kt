package com.openminis.app.ime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [ImeManager] and [MinisKeyboardService] pure logic.
 */
class ImeManagerTest {

    private val sampleGboard = "com.google.android.inputmethod.latin/com.android.inputmethod.latin.LatinIME"
    private val sampleWeType = "com.tencent.wetype/.WeTypeIME"
    private val sampleSogou = "com.sohu.inputmethod.sogou/.SogouIME"
    private val minisIme = ImeManager.IME_ID

    @Test
    fun `isSelfIme correctly identifies own keyboard and rejects third-party keyboards`() {
        assertTrue(ImeManager.isSelfIme(minisIme))
        assertTrue(ImeManager.isSelfIme("com.openminis.app/com.openminis.app.ime.MinisKeyboardService"))

        assertFalse(ImeManager.isSelfIme(sampleGboard))
        assertFalse(ImeManager.isSelfIme(sampleWeType))
        assertFalse(ImeManager.isSelfIme(sampleSogou))
        assertFalse(ImeManager.isSelfIme(null))
        assertFalse(ImeManager.isSelfIme(""))
        assertFalse(ImeManager.isSelfIme("   "))
    }

    @Test
    fun `shouldSaveOriginal only saves when not already saved and current is not self`() {
        // First time seeing a third-party keyboard -> save it
        assertTrue(ImeManager.shouldSaveOriginal(sampleGboard, alreadySaved = false))
        assertTrue(ImeManager.shouldSaveOriginal(sampleWeType, alreadySaved = false))

        // If already saved in SharedPreferences -> do NOT overwrite with subsequent reads
        assertFalse(ImeManager.shouldSaveOriginal(sampleGboard, alreadySaved = true))

        // If current is self -> NEVER save self as the original keyboard
        assertFalse(ImeManager.shouldSaveOriginal(minisIme, alreadySaved = false))
        assertFalse(ImeManager.shouldSaveOriginal(minisIme, alreadySaved = true))

        // If current is empty or null -> do not save
        assertFalse(ImeManager.shouldSaveOriginal(null, alreadySaved = false))
        assertFalse(ImeManager.shouldSaveOriginal("", alreadySaved = false))
    }

    @Test
    fun `shouldRestore only restores valid external keyboards`() {
        assertTrue(ImeManager.shouldRestore(sampleGboard))
        assertTrue(ImeManager.shouldRestore(sampleSogou))

        // Do not attempt to restore self as original
        assertFalse(ImeManager.shouldRestore(minisIme))
        assertFalse(ImeManager.shouldRestore(null))
        assertFalse(ImeManager.shouldRestore(""))
        assertFalse(ImeManager.shouldRestore("   "))
    }

    @Test
    fun `action intent constants match expected contracts`() {
        assertEquals("com.openminis.app.ACTION_IME_INPUT_TEXT", MinisKeyboardService.ACTION_IME_INPUT_TEXT)
        assertEquals("com.openminis.app.ACTION_IME_CLEAR_TEXT", MinisKeyboardService.ACTION_IME_CLEAR_TEXT)
        assertEquals("com.openminis.app.ACTION_IME_EDITOR_ACTION", MinisKeyboardService.ACTION_IME_EDITOR_ACTION)
        assertEquals("com.openminis.app.ACTION_IME_KEY_CODE", MinisKeyboardService.ACTION_IME_KEY_CODE)
    }
}
