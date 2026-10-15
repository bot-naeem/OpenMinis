package com.openminis.app.notification

import android.content.Context
import android.provider.Settings
import com.openminis.app.logging.AppLogger
import com.openminis.app.offload.ShizukuManager
import com.openminis.app.sandbox.RootAccess
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages suppression of system-level Heads-Up (floating banner) notifications
 * during mobile GUI automation tasks to prevent screen occlusion and layout interference.
 *
 * Automatically records the user's original setting before disabling, and restores it
 * upon task completion, cancellation, or crash recovery.
 */
object HeadsUpNotificationManager {

    private const val TAG = "HeadsUpManager"
    private const val SETTING_KEY = "heads_up_notifications_enabled"

    const val PREF_NAME = "minis_interference_prefs"
    const val KEY_SAVED_HEADS_UP = "saved_heads_up_enabled"

    private val activeSessions = ConcurrentHashMap.newKeySet<String>()

    /** Number of sessions currently requesting notification suppression. */
    val activeSessionCount: Int get() = activeSessions.size

    /**
     * Suppress system heads-up notifications for [sessionId].
     *
     * 1. Records the session in the active set.
     * 2. Saves the original `heads_up_notifications_enabled` setting in SharedPreferences.
     * 3. Sets `heads_up_notifications_enabled 0` via Shizuku, Root, or Settings.Global.
     */
    @Synchronized
    fun acquire(context: Context, sessionId: String): Boolean {
        activeSessions.add(sessionId)

        val currentVal = queryHeadsUpEnabled(context)
        val sp = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

        if (shouldSaveOriginal(currentVal, sp.contains(KEY_SAVED_HEADS_UP))) {
            sp.edit().putInt(KEY_SAVED_HEADS_UP, currentVal ?: 1).commit()
            AppLogger.info(TAG, "Persisted original heads-up setting: $currentVal for session $sessionId")
        }

        val success = setHeadsUpSetting(context, 0)
        AppLogger.info(TAG, "acquire heads-up suppression session=$sessionId success=$success")
        return success
    }

    /**
     * Release notification suppression for [sessionId].
     *
     * When all active sessions have finished, automatically restores the original setting.
     */
    @Synchronized
    fun release(context: Context, sessionId: String): Boolean {
        activeSessions.remove(sessionId)
        AppLogger.info(TAG, "release heads-up suppression session=$sessionId remaining=${activeSessions.size}")
        if (activeSessions.isEmpty()) {
            return restoreInternal(context, reason = "all sessions released")
        }
        return true
    }

    /**
     * Force-restore the original heads-up setting immediately.
     */
    @Synchronized
    fun forceRestore(context: Context, reason: String = "forced"): Boolean {
        activeSessions.clear()
        return restoreInternal(context, reason)
    }

    /**
     * Cold-start crash recovery check. Called during Application.onCreate().
     */
    fun checkCrashRecovery(context: Context) {
        val sp = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        if (sp.contains(KEY_SAVED_HEADS_UP)) {
            val saved = sp.getInt(KEY_SAVED_HEADS_UP, 1)
            AppLogger.warning(TAG, "Crash recovery detected lingering heads-up suppression. Restoring to $saved...")
            forceRestore(context, reason = "crash_recovery")
        }
    }

    internal fun shouldSaveOriginal(currentSetting: Int?, alreadySaved: Boolean): Boolean {
        if (alreadySaved) return false
        // Only save if current is enabled (1) or non-null; if unknown, save default 1
        return true
    }

    private fun queryHeadsUpEnabled(context: Context): Int? {
        try {
            return Settings.Global.getInt(context.contentResolver, SETTING_KEY)
        } catch (_: Exception) {}

        if (ShizukuManager.isReady()) {
            val res = ShizukuManager.runProcess(arrayOf("settings", "get", "global", SETTING_KEY))
            if (res.exitCode == 0 && res.stdout.isNotBlank()) {
                return res.stdout.trim().toIntOrNull()
            }
        }
        if (RootAccess.isRootGranted()) {
            val res = RootAccess.runSu("settings get global $SETTING_KEY", 3000L)
            if (res?.exitCode == 0 && !res.stdout.isNullOrBlank()) {
                return res.stdout.trim().toIntOrNull()
            }
        }
        return null
    }

    private fun setHeadsUpSetting(context: Context, value: Int): Boolean {
        val strVal = value.toString()
        if (ShizukuManager.isReady()) {
            val res = ShizukuManager.runProcess(arrayOf("settings", "put", "global", SETTING_KEY, strVal))
            if (res.exitCode == 0) return true
        }
        if (RootAccess.isRootGranted()) {
            val res = RootAccess.runSu("settings put global $SETTING_KEY $strVal", 3000L)
            if (res?.exitCode == 0) return true
        }
        if (context.checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED) {
            try {
                return Settings.Global.putInt(context.contentResolver, SETTING_KEY, value)
            } catch (e: Exception) {
                AppLogger.warning(TAG, "Settings.Global.putInt failed: ${e.message}")
            }
        }
        return false
    }

    @Synchronized
    private fun restoreInternal(context: Context, reason: String): Boolean {
        val sp = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val targetVal = if (sp.contains(KEY_SAVED_HEADS_UP)) sp.getInt(KEY_SAVED_HEADS_UP, 1) else 1

        AppLogger.info(TAG, "Restoring heads-up notifications to $targetVal (reason=$reason)")
        val restored = setHeadsUpSetting(context, targetVal)
        sp.edit().remove(KEY_SAVED_HEADS_UP).commit()
        return restored
    }
}
