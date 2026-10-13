package com.openminis.app.tools

import android.content.Context
import com.openminis.app.offload.OffloadPermissionManager
import com.openminis.app.sandbox.PRootKernel

/**
 * [T-android-sandbox-only] Permission and protected-path rules for the file
 * tools.
 *
 * Before the execution-environment rework these tools only reached the PRoot
 * namespace, so "which files can the agent touch" was answered by the sandbox
 * itself. They now run on the host — that is what lets the model read an
 * Android path like `/data/data/…/shared_prefs` — and that makes the question
 * ours to answer.
 *
 * The rule is a denylist, not an allowlist: ordinary workspace and shared-folder
 * work stays out of the prompt and out of the user's face, and only the paths
 * that can brick a device or read another app's private data cost anything.
 * That is deliberately narrower than Claude Code's protected-path list, which
 * exists to stop a sandboxed command from editing the config that would widen
 * the sandbox — an attack that does not apply when the file tools are not
 * sandboxed in the first place.
 */
object FileToolGuard {

    /**
     * Paths that can brick the device. Writing any of these is refused
     * outright rather than confirmed: there is no legitimate agent task whose
     * success is worth an unwritable `/system`.
     */
    private val PROTECTED_PREFIXES = listOf(
        "/system", "/vendor", "/product", "/odm", "/oem",
        "/dev/block", "/dev/mem", "/dev/kmsg", "/dev/mtd",
        "/proc/sysrq-trigger", "/boot", "/recovery",
    )

    /**
     * Paths that expose another app's private data.
     *
     * Reachable, but not silently so for anyone who asked for a wall: these go
     * through OffloadPermissionManager, which is BYPASS by default (this app
     * runs unattended — see the note on the read/write/edit registry entries)
     * and ASK_ONCE if the user downgraded the tool in Settings.
     */
    private val PRIVILEGED_PREFIXES = listOf(
        "/data/data", "/data/user", "/data/user_de",
        "/data/app", "/data/local/tmp",
    )

    enum class Decision {
        ALLOW,

        /** Refused permanently — no prompt, no override. */
        PROTECTED,

        /** Needs the user to say yes first. */
        NEEDS_PERMISSION,
    }

    fun decide(path: String): Decision {
        val normalised = normalise(path)
        if (normalised.isEmpty()) return Decision.ALLOW
        if (PROTECTED_PREFIXES.any { normalised == it || normalised.startsWith("$it/") }) {
            return Decision.PROTECTED
        }
        if (PRIVILEGED_PREFIXES.any { normalised == it || normalised.startsWith("$it/") }) {
            return Decision.NEEDS_PERMISSION
        }
        // A user-mounted folder the user marked read-only stays read-only.
        if (PRootKernel.isLinuxPathUnderReadOnlyMount(normalised)) return Decision.PROTECTED
        return Decision.ALLOW
    }

    /**
     * Prompt for a privileged write, if the policy says so. Returns false when
     * the user declined, in which case the caller must not touch the file.
     *
     * Returns true immediately at the default BYPASS level, so the unattended
     * case never suspends on a dialog nobody is there to answer.
     */
    suspend fun authorizeWrite(path: String, toolName: String, sessionId: String): Boolean {
        val normalised = normalise(path)
        // `checkPermission` prompts when the tool sits at ASK_ONCE and returns
        // straight through for BYPASS and NOT_ALLOWED; the prefix test is what
        // makes a write into another app's private data cost something.
        if (!PRIVILEGED_PREFIXES.any { normalised.startsWith(it) }) return true
        return OffloadPermissionManager.checkPermission(
            toolName,
            "Write outside the sandbox ($toolName)",
            sessionId,
        )
    }

    /**
     * Read counterpart to [authorizeWrite]. Same prefixes, same prompt; a
     * separate function only so the two call sites read honestly at the top of
     * each handler.
     */
    suspend fun authorizeRead(path: String, toolName: String, sessionId: String): Boolean {
        val normalised = normalise(path)
        if (!PRIVILEGED_PREFIXES.any { normalised.startsWith(it) }) return true
        return OffloadPermissionManager.checkPermission(
            toolName,
            "Read outside the sandbox ($toolName)",
            sessionId,
        )
    }

    fun refusalMessage(path: String): String =
        "Error: refusing to write $path — it is on the device's protected-path list " +
            "(system partitions and raw block devices). Writing there can brick the phone, " +
            "so this tool will not do it. If you genuinely need to change a system file, " +
            "ask the user to do it themselves."

    /** Strip a trailing slash so `/system` and `/system/` compare equal. */
    private fun normalise(path: String): String =
        path.trim().let { if (it.length > 1 && it.endsWith('/')) it.dropLast(1) else it }
}
