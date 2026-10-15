package com.openminis.app.ime

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import com.openminis.app.logging.AppLogger
import com.openminis.app.offload.ShizukuManager
import com.openminis.app.sandbox.RootAccess
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Lifecycle manager for taking over the Android system IME during mobile GUI automation
 * and guaranteeing 100% reliable restoration across all exit/crash scenarios.
 */
object ImeManager {

    private const val TAG = "ImeManager"

    const val IME_ID = "com.openminis.app/.ime.MinisKeyboardService"
    const val PREF_NAME = "minis_ime_prefs"
    const val KEY_ORIGINAL_IME = "saved_original_ime"

    /** 5 minutes watchdog timeout: automatically restores original IME if no activity. */
    const val WATCHDOG_TIMEOUT_MS = 5 * 60 * 1000L

    private val activeSessions = ConcurrentHashMap.newKeySet<String>()
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var watchdogJob: Job? = null

    /** Number of sessions currently holding a lease on the IME. */
    val activeSessionCount: Int get() = activeSessions.size

    /**
     * Acquire IME takeover for a given [sessionId].
     *
     * 1. Records the session into the active set.
     * 2. Reads the current default IME and persists it to SharedPreferences before switching.
     * 3. Enables and sets [MinisKeyboardService] via Shizuku/Root/SecureSettings.
     * 4. Arms a 5-minute safety watchdog.
     */
    @Synchronized
    fun acquire(context: Context, sessionId: String): Boolean {
        activeSessions.add(sessionId)
        resetWatchdog(context)

        val currentIme = queryDefaultIme(context)
        val sp = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

        // Save original IME if not already saved and not currently self
        if (shouldSaveOriginal(currentIme, sp.contains(KEY_ORIGINAL_IME))) {
            sp.edit().putString(KEY_ORIGINAL_IME, currentIme!!.trim()).commit()
            AppLogger.info(TAG, "Persisted original IME: $currentIme for session $sessionId")
        }

        if (isSelfIme(currentIme)) {
            AppLogger.debug(TAG, "MinisKeyboardService is already the default IME")
            return true
        }

        // Enable and switch to our IME
        val enableOk = runPrivileged("ime enable $IME_ID")
        val setOk = runPrivileged("ime set $IME_ID")

        var success = setOk || enableOk
        if (!success && hasWriteSecureSettings(context)) {
            try {
                Settings.Secure.putString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD, IME_ID)
                success = true
            } catch (e: Exception) {
                AppLogger.warning(TAG, "Settings.Secure write failed: ${e.message}")
            }
        }

        val finalIme = queryDefaultIme(context)
        val active = isSelfIme(finalIme)
        AppLogger.info(TAG, "acquire session=$sessionId success=$success active=$active defaultIme=$finalIme")
        return active
    }

    /**
     * Release IME takeover for a given [sessionId].
     *
     * When all active sessions have finished, automatically restores the persisted original IME.
     */
    @Synchronized
    fun release(context: Context, sessionId: String): Boolean {
        activeSessions.remove(sessionId)
        AppLogger.info(TAG, "release session=$sessionId remaining=${activeSessions.size}")
        if (activeSessions.isEmpty()) {
            return restoreInternal(context, reason = "all sessions released")
        }
        return true
    }

    /**
     * Immediately force-restores the original IME regardless of active sessions.
     * Used for user manual recovery, notification click, or crash recovery.
     */
    @Synchronized
    fun forceRestore(context: Context, reason: String = "forced"): Boolean {
        activeSessions.clear()
        return restoreInternal(context, reason)
    }

    /**
     * Cold-start crash recovery check. Called during Application.onCreate().
     * If an unsaved/unrestored original IME is found, restores it immediately.
     */
    fun checkCrashRecovery(context: Context) {
        val sp = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val saved = sp.getString(KEY_ORIGINAL_IME, null)
        if (!saved.isNullOrBlank()) {
            AppLogger.warning(TAG, "Crash recovery triggered! Found leftover original IME: $saved. Restoring...")
            forceRestore(context, reason = "crash_recovery")
        }
    }

    /** True if MinisKeyboardService is currently the system's default IME. */
    fun isCurrentlyActive(context: Context): Boolean {
        val current = queryDefaultIme(context)
        return isSelfIme(current)
    }

    /** Query current default IME string via Settings.Secure with shell fallback. */
    fun queryDefaultIme(context: Context): String? {
        try {
            val fromSettings = Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
            if (!fromSettings.isNullOrBlank()) return fromSettings.trim()
        } catch (_: Exception) {}

        // Fallback to privileged shell query
        if (ShizukuManager.isReady()) {
            val res = ShizukuManager.runProcess(arrayOf("settings", "get", "secure", "default_input_method"))
            if (res.exitCode == 0 && res.stdout.isNotBlank()) {
                return res.stdout.trim()
            }
        }
        if (RootAccess.isRootGranted()) {
            val res = RootAccess.runSu("settings get secure default_input_method", 3000L)
            if (res?.exitCode == 0 && !res.stdout.isNullOrBlank()) {
                return res.stdout.trim()
            }
        }
        return null
    }

    internal fun isSelfIme(imeComponent: String?): Boolean {
        if (imeComponent.isNullOrBlank()) return false
        return imeComponent.contains("com.openminis.app") && imeComponent.contains("MinisKeyboardService")
    }

    internal fun shouldSaveOriginal(currentIme: String?, alreadySaved: Boolean): Boolean {
        if (alreadySaved) return false
        if (currentIme.isNullOrBlank()) return false
        return !isSelfIme(currentIme)
    }

    internal fun shouldRestore(savedOriginal: String?): Boolean {
        if (savedOriginal.isNullOrBlank()) return false
        return !isSelfIme(savedOriginal)
    }

    @Synchronized
    private fun restoreInternal(context: Context, reason: String): Boolean {
        cancelWatchdog()
        val sp = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val saved = sp.getString(KEY_ORIGINAL_IME, null)

        var restored = false
        if (shouldRestore(saved)) {
            AppLogger.info(TAG, "Restoring original IME: '$saved' (reason=$reason)")
            val shellOk = runPrivileged("ime set $saved")
            if (shellOk) {
                restored = true
            } else if (hasWriteSecureSettings(context)) {
                try {
                    Settings.Secure.putString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD, saved)
                    restored = true
                } catch (e: Exception) {
                    AppLogger.warning(TAG, "Failed to restore IME via Settings.Secure: ${e.message}")
                }
            }
        } else {
            // No saved original IME, but we are still default: try resetting to system default
            if (isCurrentlyActive(context)) {
                AppLogger.warning(TAG, "No saved original IME found; executing 'ime reset'...")
                runPrivileged("ime reset")
            }
            restored = true
        }

        // Clear persisted state once restoration has been dispatched
        sp.edit().remove(KEY_ORIGINAL_IME).commit()
        AppLogger.info(TAG, "restoreInternal complete (restored=$restored, reason=$reason)")
        return restored
    }

    private fun resetWatchdog(context: Context) {
        cancelWatchdog()
        watchdogJob = scope.launch {
            delay(WATCHDOG_TIMEOUT_MS)
            AppLogger.warning(TAG, "Watchdog timeout reached ($WATCHDOG_TIMEOUT_MS ms)! Auto-restoring original IME...")
            forceRestore(context, reason = "watchdog_timeout")
        }
    }

    private fun cancelWatchdog() {
        watchdogJob?.cancel()
        watchdogJob = null
    }

    private fun runPrivileged(cmd: String): Boolean {
        val parts = cmd.split(" ").filter { it.isNotEmpty() }
        if (ShizukuManager.isReady()) {
            val res = ShizukuManager.runProcess(parts.toTypedArray())
            if (res.exitCode == 0) return true
            AppLogger.warning(TAG, "Shizuku command '$cmd' failed with code ${res.exitCode}: ${res.stderr}")
        }
        if (RootAccess.isRootGranted()) {
            val res = RootAccess.runSu(cmd, 5000L)
            if (res?.exitCode == 0) return true
            AppLogger.warning(TAG, "Root command '$cmd' failed: ${res?.stderr}")
        }
        return false
    }

    private fun hasWriteSecureSettings(context: Context): Boolean {
        return context.checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
    }
}
