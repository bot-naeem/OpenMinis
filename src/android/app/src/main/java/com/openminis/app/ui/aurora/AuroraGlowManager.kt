package com.openminis.app.ui.aurora

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import com.openminis.app.logging.AppLogger
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

enum class AuroraState {
    IDLE,
    THINKING,
    EXECUTING,
    PAUSED,
    COMPLETED
}

/**
 * Singleton manager for the non-intrusive full-screen Aurora Edge Glow overlay.
 *
 * Implements:
 * 1. Reference-counted session leases for concurrent tasks.
 * 2. Complete touch pass-through (FLAG_NOT_TOUCHABLE).
 * 3. Shutter Blink: instantaneous 0-alpha hide during physical screen capture to ensure
 *    the vision model perceives 100% pure, uncolored native screenshots.
 * 4. Crash recovery detection on cold boot.
 */
object AuroraGlowManager {

    private const val TAG = "AuroraGlowManager"
    const val PREF_NAME = "minis_interference_prefs"
    const val KEY_AURORA_ACTIVE = "aurora_glow_active"

    private val activeSessions = ConcurrentHashMap.newKeySet<String>()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val isAttached = AtomicBoolean(false)

    @Volatile
    private var glowView: AuroraGlowView? = null

    @Volatile
    private var windowManager: WindowManager? = null

    val activeSessionCount: Int get() = activeSessions.size
    val isShowing: Boolean get() = isAttached.get()

    /**
     * Acquire Aurora Edge Glow for [sessionId].
     * Adds session to active set and attaches the edge glow overlay if permissions allow.
     */
    @Synchronized
    fun acquire(
        context: Context,
        sessionId: String,
        state: AuroraState = AuroraState.EXECUTING
    ): Boolean {
        activeSessions.add(sessionId)
        AppLogger.info(TAG, "acquire session=$sessionId activeCount=${activeSessions.size} state=$state")

        val sp = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        if (shouldSaveActive(sp.contains(KEY_AURORA_ACTIVE))) {
            sp.edit().putBoolean(KEY_AURORA_ACTIVE, true).commit()
        }

        return showInternal(context, state)
    }

    /**
     * Update the visual animation state (THINKING, EXECUTING, PAUSED, COMPLETED).
     */
    fun setState(state: AuroraState) {
        mainHandler.post {
            glowView?.setState(state)
        }
    }

    /**
     * Release Aurora Edge Glow for [sessionId].
     * When all active sessions have finished, automatically hides the overlay.
     */
    @Synchronized
    fun release(context: Context, sessionId: String): Boolean {
        activeSessions.remove(sessionId)
        AppLogger.info(TAG, "release session=$sessionId remaining=${activeSessions.size}")

        if (activeSessions.isEmpty()) {
            val sp = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            sp.edit().remove(KEY_AURORA_ACTIVE).commit()
            return hideInternal(context)
        }
        return true
    }

    /**
     * Force-hide and detach the glow view immediately.
     */
    @Synchronized
    fun forceRestore(context: Context, reason: String = "forced"): Boolean {
        activeSessions.clear()
        val sp = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        sp.edit().remove(KEY_AURORA_ACTIVE).commit()
        AppLogger.info(TAG, "forceRestore (reason=$reason)")
        return hideInternal(context)
    }

    /**
     * Cold-start crash recovery check. Called during Application.onCreate().
     */
    fun checkCrashRecovery(context: Context) {
        val sp = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        if (sp.contains(KEY_AURORA_ACTIVE)) {
            AppLogger.warning(TAG, "Crash recovery detected lingering aurora glow state. Cleaning up...")
            forceRestore(context, reason = "crash_recovery")
        }
    }

    /**
     * Shutter Blink: instantly hide overlay before physical screen capture.
     * Blocks up to [timeoutMs] to ensure main thread has applied alpha = 0.
     */
    fun hideForShutter(timeoutMs: Long = 40L) {
        val view = glowView ?: return
        if (Looper.myLooper() == Looper.getMainLooper()) {
            view.setShutterHidden(true)
        } else {
            val latch = CountDownLatch(1)
            mainHandler.post {
                try {
                    view.setShutterHidden(true)
                } finally {
                    latch.countDown()
                }
            }
            try {
                latch.await(timeoutMs, TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {}
        }
    }

    /**
     * Shutter Blink: restore overlay alpha after physical screen capture completes.
     */
    fun restoreAfterShutter() {
        val view = glowView ?: return
        mainHandler.post {
            view.setShutterHidden(false)
        }
    }

    internal fun shouldSaveActive(alreadySaved: Boolean): Boolean {
        return !alreadySaved
    }

    private fun showInternal(context: Context, state: AuroraState): Boolean {
        if (!hasOverlayPermission(context)) {
            AppLogger.warning(TAG, "Cannot show Aurora glow: SYSTEM_ALERT_WINDOW permission not granted")
            return false
        }

        mainHandler.post {
            try {
                val wm = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return@post
                windowManager = wm

                if (!isAttached.get()) {
                    val view = AuroraGlowView(context.applicationContext)
                    view.setState(state)

                    val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                    } else {
                        @Suppress("DEPRECATION")
                        WindowManager.LayoutParams.TYPE_PHONE
                    }

                    val params = WindowManager.LayoutParams(
                        WindowManager.LayoutParams.MATCH_PARENT,
                        WindowManager.LayoutParams.MATCH_PARENT,
                        type,
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                        PixelFormat.TRANSLUCENT
                    ).apply {
                        gravity = Gravity.TOP or Gravity.START
                    }

                    wm.addView(view, params)
                    glowView = view
                    isAttached.set(true)
                    AppLogger.info(TAG, "Aurora glow attached successfully")
                } else {
                    glowView?.setState(state)
                }
            } catch (e: Exception) {
                AppLogger.warning(TAG, "Failed to attach Aurora glow: ${e.message}")
            }
        }
        return true
    }

    private fun hideInternal(context: Context): Boolean {
        mainHandler.post {
            try {
                val wm = windowManager ?: context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
                val view = glowView
                if (wm != null && view != null && isAttached.get()) {
                    wm.removeView(view)
                    AppLogger.info(TAG, "Aurora glow detached successfully")
                }
            } catch (e: Exception) {
                AppLogger.warning(TAG, "Error removing Aurora glow view: ${e.message}")
            } finally {
                glowView = null
                isAttached.set(false)
            }
        }
        return true
    }

    private fun hasOverlayPermission(context: Context): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(context)
    }
}
