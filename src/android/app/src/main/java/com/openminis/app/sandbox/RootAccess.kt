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

    /**
     * Result of executing a command via superuser grant.
     */
    // Deliberately not a `data class`: that would auto-generate component1..3 in
    // declaration order and clash with the hand-written ones below. The order the
    // old `Pair<String?, Int>` return type destructured as has to be preserved.
    class SuResult(
        val stdout: String,
        val stderr: String,
        val exitCode: Int,
    ) {
        val stdoutFirstLine: String?
            get() = stdout.lineSequence().firstOrNull { it.isNotBlank() }

        // Destructuring compatibility for (firstLine, exitCode)
        operator fun component1(): String? = stdoutFirstLine
        operator fun component2(): Int = exitCode
        operator fun component3(): String = stderr

        override fun toString(): String =
            "SuResult(exitCode=$exitCode, stdout=${stdout.take(120)}, stderr=${stderr.take(120)})"
    }

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

    /**
     * Resolves the accessible su binary path from known locations or falls back to "su".
     */
    fun findSuBinary(): String {
        for (path in SU_PATHS) {
            val f = File(path)
            if (f.exists()) return path
        }
        return "su"
    }

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
        val result = runSu("id") ?: return false
        return result.exitCode == 0 && result.stdout.contains("uid=0")
    }

    /**
     * Actively probe or prompt for superuser grant with a user-friendly timeout
     * so that if Magisk / KernelSU presents an interactive authorization dialog,
     * the user has ample time (15s) to confirm it.
     */
    fun requestRoot(timeoutMs: Long = 15_000L): SuResult? {
        return runSu("id", timeoutMs = timeoutMs)
    }

    /**
     * Run a command through `su -c`. Drains stdout and stderr concurrently in background
     * reader threads so pipes never block or trigger SIGPIPE on large outputs.
     * Returns a [SuResult] containing stdout, stderr and exitCode, or null if the
     * process could not be started at all.
     */
    fun runSu(
        command: String,
        timeoutMs: Long = 10_000L,
        maxOutputChars: Int = 65_536,
    ): SuResult? {
        val suBin = findSuBinary()
        return try {
            val process = ProcessBuilder(suBin, "-c", command).start()
            val stdoutBuilder = StringBuilder()
            val stderrBuilder = StringBuilder()

            // Asynchronously drain stdout and stderr to prevent pipe buffer stalls / SIGPIPE
            val stdoutThread = Thread {
                runCatching {
                    process.inputStream.bufferedReader().use { reader ->
                        val buf = CharArray(4096)
                        var read: Int
                        while (reader.read(buf).also { read = it } != -1) {
                            synchronized(stdoutBuilder) {
                                if (stdoutBuilder.length < maxOutputChars) {
                                    val remaining = maxOutputChars - stdoutBuilder.length
                                    stdoutBuilder.appendRange(buf, 0, minOf(read, remaining))
                                    if (stdoutBuilder.length >= maxOutputChars) {
                                        stdoutBuilder.append("\n[... stdout truncated at $maxOutputChars chars ...]")
                                    }
                                }
                            }
                        }
                    }
                }
            }

            val stderrThread = Thread {
                runCatching {
                    process.errorStream.bufferedReader().use { reader ->
                        val buf = CharArray(4096)
                        var read: Int
                        while (reader.read(buf).also { read = it } != -1) {
                            synchronized(stderrBuilder) {
                                if (stderrBuilder.length < maxOutputChars) {
                                    val remaining = maxOutputChars - stderrBuilder.length
                                    stderrBuilder.appendRange(buf, 0, minOf(read, remaining))
                                    if (stderrBuilder.length >= maxOutputChars) {
                                        stderrBuilder.append("\n[... stderr truncated at $maxOutputChars chars ...]")
                                    }
                                }
                            }
                        }
                    }
                }
            }

            stdoutThread.start()
            stderrThread.start()

            val finished = process.waitFor(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
            val exitCode = if (finished) {
                process.exitValue()
            } else {
                process.destroyForcibly()
                124 // POSIX standard timeout code
            }

            stdoutThread.join(1000)
            stderrThread.join(1000)

            val outStr = synchronized(stdoutBuilder) { stdoutBuilder.toString() }
            val errStr = synchronized(stderrBuilder) { stderrBuilder.toString() }

            SuResult(stdout = outStr, stderr = errStr, exitCode = exitCode)
        } catch (e: Exception) {
            android.util.Log.w("RootAccess", "Failed to execute $suBin: ${e.message}")
            null
        }
    }
}
