package com.openminis.app.assistant

import android.service.voice.VoiceInteractionService
import android.util.Log

/**
 * Android system VoiceInteractionService entry point.
 * Enables OpenMinis to be selected as the official "Default digital assistant app"
 * in Android System Settings, waking via power button long-press or corner gesture.
 */
class MinisVoiceInteractionService : VoiceInteractionService() {

    override fun onReady() {
        super.onReady()
        Log.i(TAG, "MinisVoiceInteractionService is ready and active as system assistant")
    }

    override fun onShutdown() {
        super.onShutdown()
        Log.i(TAG, "MinisVoiceInteractionService shutdown")
    }

    companion object {
        private const val TAG = "MinisVoiceIntService"
    }
}
