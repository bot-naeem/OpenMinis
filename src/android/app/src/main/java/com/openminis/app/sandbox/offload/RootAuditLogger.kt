package com.openminis.app.sandbox.offload

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File

/**
 * Lightweight local rolling audit logger for all superuser (Root) commands.
 *
 * Keeps a record of what commands were attempted, blocked, or executed under UID 0,
 * allowing users to inspect overnight unattended autonomous activities.
 */
object RootAuditLogger {

    private const val TAG = "RootAuditLogger"
    private const val MAX_FILE_BYTES = 512 * 1024L // 512 KB per file

    @Synchronized
    fun log(
        context: Context,
        command: String,
        sessionId: String?,
        exitCode: Int?,
        durationMs: Long,
        isBlocked: Boolean,
        blockReason: String? = null,
    ) {
        try {
            val dir = File(context.filesDir, "minis-audit").also { it.mkdirs() }
            val logFile = File(dir, "root_audit.log")

            // Rotate if oversized
            if (logFile.exists() && logFile.length() > MAX_FILE_BYTES) {
                val backup = File(dir, "root_audit.log.1")
                if (backup.exists()) backup.delete()
                logFile.renameTo(backup)
            }

            val entry = JSONObject()
                .put("ts", System.currentTimeMillis())
                .put("session", sessionId ?: "")
                .put("cmd", command)
                .put("blocked", isBlocked)
            if (isBlocked && blockReason != null) {
                entry.put("reason", blockReason)
            }
            if (exitCode != null) {
                entry.put("exitCode", exitCode)
            }
            entry.put("ms", durationMs)

            logFile.appendText(entry.toString() + "\n")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to write root audit entry", e)
        }
    }
}
