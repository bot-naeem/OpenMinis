package com.openminis.app.sandbox

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets

/**
 * Executes shell commands directly on the Android host OS (or via `su` for root).
 */
object HostShellExecutor {
    private const val TAG = "HostShellExecutor"
    private const val CANCELLED_EXIT = 130

    @Volatile
    private var liveProcess: Process? = null

    @Volatile
    private var isCancelled = false

    fun cancel() {
        isCancelled = true
        liveProcess?.let {
            runCatching { it.destroyForcibly() }
        }
        liveProcess = null
    }

    suspend fun execute(
        context: Context,
        command: String,
        timeoutMs: Long,
        isRoot: Boolean,
        lineCallback: ((String) -> Unit)? = null,
    ): Pair<String, Int> = withContext(Dispatchers.IO) {
        isCancelled = false
        val startTime = System.currentTimeMillis()

        val processBuilder = if (isRoot) {
            ProcessBuilder("su", "-c", command)
        } else {
            ProcessBuilder("/system/bin/sh", "-c", command)
        }

        // Set safe working directory to app internal files dir or /data/local/tmp
        val workDir = if (isRoot) File("/data/local/tmp").takeIf { it.exists() } ?: context.filesDir else context.filesDir
        processBuilder.directory(workDir)
        processBuilder.redirectErrorStream(true)

        val process = try {
            processBuilder.start()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start host shell process (isRoot=$isRoot): ${e.message}")
            return@withContext Pair("[Process spawn failed: ${e.message}]", -1)
        }

        liveProcess = process

        if (isCancelled) {
            runCatching { process.destroyForcibly() }
            liveProcess = null
            return@withContext Pair("[Cancelled]", CANCELLED_EXIT)
        }

        val output = StringBuilder()
        val readerThread = Thread {
            try {
                BufferedReader(InputStreamReader(process.inputStream, StandardCharsets.UTF_8)).use { reader ->
                    var line = reader.readLine()
                    while (line != null) {
                        output.append(line).append("\n")
                        lineCallback?.invoke(line)
                        line = reader.readLine()
                    }
                }
            } catch (_: Exception) {}
        }
        readerThread.start()

        val checkIntervalMs = 100L
        var timedOut = false

        while (readerThread.isAlive) {
            if (isCancelled) {
                runCatching { process.destroyForcibly() }
                readerThread.interrupt()
                return@withContext Pair("[Cancelled]", CANCELLED_EXIT)
            }
            if (System.currentTimeMillis() - startTime > timeoutMs) {
                timedOut = true
                runCatching { process.destroyForcibly() }
                readerThread.interrupt()
                break
            }
            Thread.sleep(checkIntervalMs)
        }

        readerThread.join(500)
        liveProcess = null

        if (timedOut) {
            return@withContext Pair(output.toString() + "\n[Command timed out after ${timeoutMs / 1000}s]", 124)
        }

        val exitCode = try {
            process.exitValue()
        } catch (_: Exception) {
            -1
        }

        Pair(output.toString().trimEnd(), exitCode)
    }
}
