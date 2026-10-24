package com.openminis.app.speech.ondevice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class OnDeviceSpeechModelManagerTest {

    @Test
    fun `test state transitions and definitions`() {
        val notDownloaded = OnDeviceSpeechModelManager.ModelState.NotDownloaded
        assertTrue(notDownloaded is OnDeviceSpeechModelManager.ModelState.NotDownloaded)

        val error = OnDeviceSpeechModelManager.ModelState.Error("Network failure")
        assertEquals("Network failure", error.message)

        val ready = OnDeviceSpeechModelManager.ModelState.Ready(File("/tmp/models"), 78000000L)
        assertEquals(78000000L, ready.totalSizeBytes)
    }

    @Test
    fun `test thread clamping logic`() {
        OnDeviceSpeechModelManager.setNumThreads(8)
        assertEquals(4, OnDeviceSpeechModelManager.numThreads.value)

        OnDeviceSpeechModelManager.setNumThreads(0)
        assertEquals(1, OnDeviceSpeechModelManager.numThreads.value)

        OnDeviceSpeechModelManager.setNumThreads(2)
        assertEquals(2, OnDeviceSpeechModelManager.numThreads.value)
    }

    @Test
    fun `test language and itn options`() {
        OnDeviceSpeechModelManager.setLanguage("zh")
        assertEquals("zh", OnDeviceSpeechModelManager.language.value)

        OnDeviceSpeechModelManager.setUseItn(false)
        assertFalse(OnDeviceSpeechModelManager.useItn.value)

        OnDeviceSpeechModelManager.setUseItn(true)
        assertTrue(OnDeviceSpeechModelManager.useItn.value)
    }
}
