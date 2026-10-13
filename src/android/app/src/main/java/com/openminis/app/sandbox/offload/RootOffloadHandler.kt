package com.openminis.app.sandbox.offload

import android.content.Context
import android.util.Log
import com.openminis.app.sandbox.NativeOffloadHandler
import com.openminis.app.sandbox.NativeOffloadRequest
import com.openminis.app.sandbox.NativeOffloadResult
import com.openminis.app.sandbox.RootAccess
import org.json.JSONObject

/**
 * `android-root-cli` — privileged Android control via a superuser grant (UID 0).
 *
 * [T-android-sandbox-only] This exists because the execution environment no
 * longer has a ROOT branch. `su -c <anything>` used to be reachable by setting
 * one pref; now it is reachable only by (a) the model naming the `root` action
 * on the `android` tool, (b) the user having enabled the `root_cli` permission,
 * and (c) a confirmation prompt when the grant is not yet active. That is the
 * whole point: on Android, `su` inside a mount namespace keeps its full
 * capability set, so there is no kernel boundary to hide behind — confinement
 * has to happen here, at the API surface.
 *
 * A rooted device gets the deepest reach (full /data, /system remount, block
 * devices). A non-rooted one gets a typed `permission_denied` naming the fix,
 * and Shizuku via `android-shizuku-cli` remains the unprivileged tier.
 */
class RootOffloadHandler(private val context: Context) : NativeOffloadHandler {

    override fun handle(request: NativeOffloadRequest): NativeOffloadResult {
        val argv = request.argv.drop(1)
        val args = OffloadArgs(argv)
        val command = args.positional.joinToString(" ").trim()

        if (command.isEmpty() || command == "--help" || command == "-h" ||
            command == "help" || command == "--version"
        ) {
            return NativeOffloadResult(
                exitCode = 0,
                output = if (command == "--version") "android-root-cli 1.0" else HELP,
            )
        }

        // Permission gate first — same shape as ShizukuOffloadHandler, so a
        // revoked grant is reported identically no matter which backend asked.
        OffloadGate.enforce("root_cli", "android-root-cli", args, request)?.let { return it }

        // Hard safety boundary: prevent brick-risk operations, mass deletions,
        // and partition corruption regardless of user permissions.
        val guardDecision = RootCommandGuard.check(command)
        if (guardDecision is RootCommandGuard.Decision.Blocked) {
            Log.w(TAG, "Root command blocked by guard: $command (${guardDecision.code})")
            RootAuditLogger.log(
                context = context,
                command = command,
                sessionId = request.sessionId,
                exitCode = 126,
                durationMs = 0L,
                isBlocked = true,
                blockReason = guardDecision.reason,
            )
            val errObj = JSONObject()
                .put("ok", false)
                .put("error", JSONObject()
                    .put("code", guardDecision.code)
                    .put("message", guardDecision.reason)
                )
                .put("data", JSONObject()
                    .put("command", command)
                    .put("exitCode", 126)
                    .put("stdout", "")
                    .put("stderr", guardDecision.reason)
                )
            val body = OffloadOutput.formatBody(errObj.toString(2), args)
            return NativeOffloadResult(exitCode = 126, output = body + "\n")
        }

        val granted = runCatching { RootAccess.isRootGranted() }.getOrDefault(false)
        if (!granted) {
            return NativeOffloadResult(
                exitCode = 126,
                output = errEnvelope(
                    "NO_ROOT",
                    "No superuser grant on this device, or it has not been accepted yet. " +
                        "Install Magisk or KernelSU and grant the request, then retry. " +
                        "Without root, android-shizuku-cli (Shizuku) is the equivalent surface.",
                ),
            )
        }

        val timeoutMs = args.get("timeout-ms")?.toLongOrNull() ?: DEFAULT_TIMEOUT_MS
        val started = System.currentTimeMillis()
        val result = RootAccess.runSu(command, timeoutMs)
            ?: return NativeOffloadResult(
                exitCode = 127,
                output = errEnvelope("SPAWN_FAILED", "Could not start a superuser process for this command."),
            )

        val durationMs = System.currentTimeMillis() - started
        Log.i(TAG, "root exec exit=${result.exitCode} in ${durationMs}ms")

        RootAuditLogger.log(
            context = context,
            command = command,
            sessionId = request.sessionId,
            exitCode = result.exitCode,
            durationMs = durationMs,
            isBlocked = false,
        )

        val data = JSONObject()
            .put("command", command)
            .put("exitCode", result.exitCode)
            .put("stdout", result.stdout)
            .put("stderr", result.stderr)

        return if (result.exitCode == 0) {
            okEnvelope(data, args)
        } else {
            val errObj = JSONObject()
                .put("ok", false)
                .put("error", JSONObject()
                    .put("code", if (result.exitCode == 124) "TIMEOUT" else "COMMAND_FAILED")
                    .put("message", if (result.exitCode == 124) {
                        "Command timed out after ${timeoutMs}ms"
                    } else {
                        result.stderr.ifBlank { "Command exited with code ${result.exitCode}" }
                    })
                )
                .put("data", data)
            val body = OffloadOutput.formatBody(errObj.toString(2), args)
            NativeOffloadResult(exitCode = result.exitCode, output = body + "\n")
        }
    }

    private fun okEnvelope(payload: Any, args: OffloadArgs): NativeOffloadResult {
        val obj = JSONObject().put("ok", true).put("data", payload)
        val body = OffloadOutput.formatBody(obj.toString(2), args)
        return NativeOffloadResult(0, body + "\n")
    }

    private fun errEnvelope(code: String, message: String): String =
        "{\"error\":{\"code\":\"$code\",\"message\":${jsonString(message)}}}"

    private fun jsonString(s: String): String {
        val sb = StringBuilder(s.length + 2)
        sb.append('"')
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (c < ' ') sb.append("\\u%04x".format(c.code)) else sb.append(c)
            }
        }
        sb.append('"')
        return sb.toString()
    }

    companion object {
        private const val TAG = "RootOffload"
        private const val DEFAULT_TIMEOUT_MS = 30_000L

        private val HELP = """
            android-root-cli — run a command with a superuser grant (UID 0).

            Usage:
              android-root-cli "<shell command>" [--timeout-ms N]

            This is the root counterpart of android-shizuku-cli and reaches
            strictly further: full /data, /system inspection, device diagnosis.
            Both stdout and stderr are returned directly in the output JSON.

            Safety: RootCommandGuard unconditionally blocks raw block device writes,
            mass partition wipes, and destructive system mounts to protect the device.

            Requires a granted superuser prompt, and the root_cli permission in
            [Settings -> Permissions] (off by default).
        """.trimIndent()
    }
}
