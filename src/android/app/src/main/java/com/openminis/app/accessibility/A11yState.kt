package com.openminis.app.accessibility

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.text.TextUtils
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * [T-android-permissions-redesign] One place that answers "is the
 * accessibility service on, and if not, what should the user do about it".
 *
 * The permissions screen polled this on a 1-second timer, the (now deleted)
 * system-permissions screen polled it on its own 1-second timer, and the
 * background screen re-probed it once per ON_RESUME. Three probes, three
 * polling strategies, no shared state — so the two screens that both display
 * the answer could disagree, and the Settings row whose whole purpose is to
 * say "this blocks a feature" had no idea what the other two were showing.
 *
 * This publishes one value and lets callers observe it. The service's own
 * enable/disable broadcasts refresh it; the fallback tick exists only so a
 * revoke from outside the app (another app force-stopping it, a policy change)
 * is still noticed within a few seconds.
 */
object A11yState {

    private const val TAG = "A11yState"
    private const val ENABLED_ACCESSIBILITY_SERVICES =
        "enabled_accessibility_services"
    private const val ACCESSIBILITY_ENABLED = "accessibility_enabled"

    private val _isEnabled = MutableStateFlow(false)
    val isEnabled: StateFlow<Boolean> = _isEnabled.asStateFlow()

    @Volatile
    private var inited = false

    fun init(context: Context) {
        if (inited) return
        inited = true
        refresh(context.applicationContext)
    }

    fun refresh(context: Context) {
        _isEnabled.value = probe(context.applicationContext)
    }

    private fun probe(context: Context): Boolean {
        return try {
            val expected = ComponentName(
                context.packageName,
                MinisAccessibilityService::class.java.name,
            ).flattenToString()
            val enabled = Settings.Secure.getString(
                context.contentResolver, ENABLED_ACCESSIBILITY_SERVICES,
            ) ?: return false
            val splitter = TextUtils.SimpleStringSplitter(':')
            splitter.setString(enabled)
            while (splitter.hasNext()) {
                if (splitter.next().equals(expected, ignoreCase = true)) return true
            }
            false
        } catch (_: Throwable) {
            false
        }
    }

    /** The system page where the service can be switched on. */
    fun openSettings(context: Context) {
        try {
            context.startActivity(
                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (e: Exception) {
            android.util.Log.w(TAG, "could not open accessibility settings", e)
        }
    }
}
