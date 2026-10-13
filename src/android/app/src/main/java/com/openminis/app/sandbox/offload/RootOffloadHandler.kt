package com.openminis.app.sandbox.offload

import android.content.Context
import android.util.Log
import com.openminis.app.sandbox.NativeOffloadHandler
import com.openminis.app.sandbox.NativeOffloadRequest
import com.openminis.app.sandbox.NativeOffloadResult
import com.openminis.app.sandbox.RootAccess

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
        val command = argv.firstOrNull { it.isNotBlank() }?.trim()

        if (command.isNullOrEmpty() || command == "--help" || command == "-h" ||
            command == "help" || command == "--version"
        ) {
            return NativeOffloadResult(
                exitCode = 0,
                output = if (command == "--version") "android-root-cli 1.0" else HELP,
            )
        }

        // Permission gate first — same shape as ShizukuOffloadHandler, so a
        // revoked grant is reported identically no matter which backend asked.
        val args = OffloadArgs(argv)
        OffloadGate.enforce("root_cli", "android-root-cli", args, request)?.let { return it }

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
        val (firstLine, exitCode) = result

        Log.i(TAG, "root exec exit=$exitCode in ${System.currentTimeMillis() - started}ms")

        // RootAccess.runSu only surfaces the first stdout line — this handler
        // exists for interactive confirmation and diagnostics, not as a
        // general-purpose output channel. Say so rather than returning a
        // half-result the model would misread as complete.
        val body = firstLine ?: ""
        val output = if (exitCode == 124) {
            "[timeout after ${timeoutMs}ms] $body"
        } else {
            buildString {
                append("{")
                append("\"command\":").append(jsonString(command)).append(",")
                append("\"exitCode\":").append(exitCode).append(",")
                append("\"stdout_first_line\":").append(jsonString(body)).append(",")
                append("\"note\":").append(
                    jsonString(
                        "Only the first stdout line is returned. For full output, or for " +
                            "anything interactive, write to a file under /var/minis/shared " +
                            "and read it back with the read tool."
                    )
                ).append("}")
            }
        }
        return NativeOffloadResult(exitCode = exitCode, output = output)
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
            strictly further: full /data, /system remount, block devices.
            Only the FIRST line of stdout is returned — for full output, or for
            anything interactive, redirect to a file under /var/minis/shared and
            read it back with the read tool.

            Requires a granted superuser prompt, and the root_cli permission in
            [Settings -> Permissions] (off by default).
        """.trimIndent()
    }
}
