package com.openminis.app.ime

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import com.openminis.app.logging.AppLogger

/**
 * Embedded zero-UI virtual input method for Android GUI agent automation.
 *
 * When activated as the default IME during mobile automation tasks:
 * 1. Shows NO visual keyboard UI (0x0 layout), preventing window resizing (`adjustResize`)
 *    and panning (`adjustPan`) so that on-screen coordinates and layout remain 100% stable.
 * 2. Injects arbitrary Unicode strings (Chinese, Emoji, symbols, multiline) instantaneously
 *    via [InputConnection.commitText], completely bypassing IME pinyin composition.
 * 3. Can be commanded directly via in-process Kotlin companion APIs or via broadcast intents.
 */
class MinisKeyboardService : InputMethodService() {

    companion object {
        private const val TAG = "MinisKeyboard"

        const val ACTION_IME_INPUT_TEXT = "com.openminis.app.ACTION_IME_INPUT_TEXT"
        const val ACTION_IME_CLEAR_TEXT = "com.openminis.app.ACTION_IME_CLEAR_TEXT"
        const val ACTION_IME_EDITOR_ACTION = "com.openminis.app.ACTION_IME_EDITOR_ACTION"
        const val ACTION_IME_KEY_CODE = "com.openminis.app.ACTION_IME_KEY_CODE"

        const val EXTRA_TEXT = "text"
        const val EXTRA_ACTION_ID = "action_id"
        const val EXTRA_KEY_CODE = "key_code"

        @Volatile
        private var activeInstance: MinisKeyboardService? = null

        /** True if the service is currently running and has an active input connection. */
        val isConnected: Boolean
            get() = activeInstance?.currentInputConnection != null

        /** Inject text directly into the active InputConnection. */
        fun injectText(text: String): Boolean {
            val s = activeInstance ?: return false
            val ic = s.currentInputConnection ?: return false
            return try {
                ic.commitText(text, 1)
            } catch (e: Exception) {
                AppLogger.warning(TAG, "injectText failed: ${e.message}")
                false
            }
        }

        /** Clear existing text in the active input field. */
        fun clearText(): Boolean {
            val s = activeInstance ?: return false
            val ic = s.currentInputConnection ?: return false
            return try {
                ic.performContextMenuAction(android.R.id.selectAll)
                ic.commitText("", 1)
            } catch (e: Exception) {
                AppLogger.warning(TAG, "clearText failed: ${e.message}")
                false
            }
        }

        /** Trigger an IME action (e.g. EditorInfo.IME_ACTION_SEARCH, IME_ACTION_SEND, etc.). */
        fun sendEditorAction(actionId: Int): Boolean {
            val s = activeInstance ?: return false
            val ic = s.currentInputConnection ?: return false
            return try {
                ic.performEditorAction(actionId)
            } catch (e: Exception) {
                AppLogger.warning(TAG, "sendEditorAction failed: ${e.message}")
                false
            }
        }

        /** Send a key event (DOWN then UP) through the active input connection. */
        fun sendKeyCode(keyCode: Int): Boolean {
            val s = activeInstance ?: return false
            val ic = s.currentInputConnection ?: return false
            return try {
                ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
                ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
                true
            } catch (e: Exception) {
                AppLogger.warning(TAG, "sendKeyCode failed: ${e.message}")
                false
            }
        }
    }

    private val imeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent == null) return
            when (intent.action) {
                ACTION_IME_INPUT_TEXT -> {
                    val text = intent.getStringExtra(EXTRA_TEXT).orEmpty()
                    injectText(text)
                }
                ACTION_IME_CLEAR_TEXT -> {
                    clearText()
                }
                ACTION_IME_EDITOR_ACTION -> {
                    val actionId = intent.getIntExtra(EXTRA_ACTION_ID, EditorInfo.IME_ACTION_UNSPECIFIED)
                    sendEditorAction(actionId)
                }
                ACTION_IME_KEY_CODE -> {
                    val keyCode = intent.getIntExtra(EXTRA_KEY_CODE, KeyEvent.KEYCODE_ENTER)
                    sendKeyCode(keyCode)
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        activeInstance = this
        val filter = IntentFilter().apply {
            addAction(ACTION_IME_INPUT_TEXT)
            addAction(ACTION_IME_CLEAR_TEXT)
            addAction(ACTION_IME_EDITOR_ACTION)
            addAction(ACTION_IME_KEY_CODE)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(imeReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(imeReceiver, filter)
        }
        AppLogger.info(TAG, "MinisKeyboardService created")
    }

    override fun onDestroy() {
        try {
            unregisterReceiver(imeReceiver)
        } catch (_: Exception) {}
        if (activeInstance === this) {
            activeInstance = null
        }
        AppLogger.info(TAG, "MinisKeyboardService destroyed")
        super.onDestroy()
    }

    override fun onCreateInputView(): View {
        // Return a zero-dimension, invisible dummy view so the system
        // window does not expand or push up the underlying activity.
        return View(this).apply {
            layoutParams = ViewGroup.LayoutParams(0, 0)
            visibility = View.GONE
        }
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        AppLogger.debug(TAG, "onStartInputView package=${info?.packageName} imeOptions=${info?.imeOptions}")
    }
}
