package com.openminis.app.sandbox

import java.io.File

/**
 * Probes for a usable superuser grant.
 *
 * The agent never receives a raw root shell: privileged execution goes through
 * the offload IPC layer (`android-root-cli`), which every call site gates behind
 * `OffloadPermissionManager`. This object only answers "is root even available on
 * this device", so the answer can be surfaced to the user and to the model
 * without ever opening a hole in the sandbox.
 */
object RootAccess {

    /** Paths a superuser binary is known to use across common root solutions. */
    private val SU_PATHS = arrayOf(
        "/system/bin/su",
        "/system/xbin/su",
        "/sbin/su",
        "/system/sd/xbin/su",
        "/system/bin/failsafe/su",
        "/data/local/xbin/su",
        "/data/local/bin/su",
        "/data/local/su",
        "/debug_ramdisk/su",
    )

    /** `su` exists somewhere. Says nothing about whether the grant is granted. */
    fun isRootInstalled(): Boolean {
        for (path in SU_PATHS) {
            if (File(path).exists()) return true
        }
        return runSu("id") != null
    }

    /**
     * `su` exists AND the grant is actually granted — verified by `id` reporting
     * uid 0. A Magisk/KernelSU prompt that has not been accepted returns false.
     */
    fun isRootGranted(): Boolean {
        val (uidLine, _) = runSu("id") ?: return false
        return uidLine?.contains("uid=0") == true
    }

    /**
     * Run a command through `su -c`. Returns (first stdout line, exit code), or
     * null when no superuser process could be started at all.
     */
    fun runSu(command: String, timeoutMs: Long = 10_000L): Pair<String?, Int>? {
        return try {
            val process = ProcessBuilder("su", "-c", command).start()
            val line = process.inputStream.bufferedReader().use { it.readLine() }
            val code = if (process.waitFor(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                process.exitValue()
            } else {
                process.destroyForcibly()
                -1
            }
            if (line == null && code != 0) null else (line to code)
        } catch (_: Exception) {
            null
        }
    }
}
