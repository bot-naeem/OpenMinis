package com.openminis.app.assistant

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log

/**
 * Transparent trampoline activity responding to Intent.ACTION_ASSIST triggers
 * from system navigation bars or third-party launchers.
 */
class AssistActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i(TAG, "AssistActivity triggered via ACTION_ASSIST")
        finish()
    }

    companion object {
        private const val TAG = "AssistActivity"
    }
}
