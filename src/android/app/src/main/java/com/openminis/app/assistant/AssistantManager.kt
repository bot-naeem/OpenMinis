package com.openminis.app.assistant

import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.util.Log

/**
 * Helper utilities for Android Default Digital Assistant detection and settings navigation.
 */
object AssistantManager {

    private const val TAG = "AssistantManager"

    /**
     * Checks whether OpenMinis is currently configured as the Android system's
     * default voice interaction / digital assistant service.
     */
    fun isActiveAssistant(context: Context): Boolean {
        return try {
            val cr = context.contentResolver
            val voiceSetting = Settings.Secure.getString(cr, "voice_interaction_service")
            val assistantSetting = Settings.Secure.getString(cr, "assistant")
            val pkg = context.packageName
            (voiceSetting != null && voiceSetting.contains(pkg)) ||
                (assistantSetting != null && assistantSetting.contains(pkg))
        } catch (e: Exception) {
            Log.w(TAG, "failed to query system assistant settings: ${e.message}")
            false
        }
    }

    /**
     * Creates an Intent to navigate the user directly to the Android System Settings page
     * where the Default Digital Assistant can be selected.
     */
    fun createAssistantSettingsIntent(): Intent {
        val intents = listOf(
            Intent(Settings.ACTION_VOICE_INPUT_SETTINGS),
            Intent("android.settings.VOICE_INPUT_SETTINGS"),
            Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS),
            Intent(Settings.ACTION_SETTINGS),
        )

        for (intent in intents) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return intents.first()
    }
}
