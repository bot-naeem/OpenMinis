package com.openminis.app.sandbox

import android.content.Context
import androidx.annotation.StringRes
import com.openminis.app.R
import com.openminis.app.offload.ShizukuManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * Execution environment for Agent commands (primarily `shell_execute`).
 */
enum class ExecutionEnvironment(
    val key: String,
    @StringRes val titleRes: Int,
    @StringRes val descRes: Int,
) {
    /** Isolated Alpine Linux container via PRoot (default). */
    SANDBOX(
        "sandbox",
        R.string.exec_env_sandbox_title,
        R.string.exec_env_sandbox_desc,
    ),

    /** Standard Android user-space shell (/system/bin/sh). */
    HOST_SHELL(
        "host_shell",
        R.string.exec_env_host_title,
        R.string.exec_env_host_desc,
    ),

    /** Privileged ADB Shell via Shizuku (UID 2000). */
    SHIZUKU(
        "shizuku",
        R.string.exec_env_shizuku_title,
        R.string.exec_env_shizuku_desc,
    ),

    /** Full Root / Superuser shell (su, UID 0). */
    ROOT(
        "root",
        R.string.exec_env_root_title,
        R.string.exec_env_root_desc,
    );

    companion object {
        fun fromKey(key: String?): ExecutionEnvironment {
            return entries.firstOrNull { it.key == key } ?: SANDBOX
        }
    }
}

/**
 * Repository to persist and query the agent's active execution environment.
 */
object ExecutionEnvironmentRepository {
    private const val PREFS_NAME = "execution_environment_prefs"
    private const val KEY_ENV = "active_execution_environment"

    private val _currentEnvironment = MutableStateFlow(ExecutionEnvironment.SANDBOX)
    val currentEnvironment: StateFlow<ExecutionEnvironment> = _currentEnvironment.asStateFlow()

    fun init(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val savedKey = prefs.getString(KEY_ENV, ExecutionEnvironment.SANDBOX.key)
        _currentEnvironment.value = ExecutionEnvironment.fromKey(savedKey)
    }

    fun setEnvironment(context: Context, env: ExecutionEnvironment) {
        _currentEnvironment.value = env
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_ENV, env.key)
            .apply()
    }

    /**
     * Checks if `su` binary exists and responds.
     */
    fun isRootAvailable(): Boolean {
        val suPaths = arrayOf(
            "/system/bin/su",
            "/system/xbin/su",
            "/sbin/su",
            "/system/sd/xbin/su",
            "/system/bin/failsafe/su",
            "/data/local/xbin/su",
            "/data/local/bin/su",
            "/data/local/su",
        )
        for (path in suPaths) {
            if (File(path).exists()) return true
        }
        // Also probe PATH via `which su`
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("which", "su"))
            val exit = process.waitFor()
            exit == 0
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Probe root permission grant by testing `su -c id`.
     */
    fun testRootAccess(): Boolean {
        return try {
            val process = ProcessBuilder("su", "-c", "id").start()
            val reader = process.inputStream.bufferedReader()
            val line = reader.readLine()
            val exit = process.waitFor()
            exit == 0 && (line?.contains("uid=0") == true)
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Checks if Shizuku is installed and currently running with permissions.
     */
    fun isShizukuReady(): Boolean {
        return ShizukuManager.isReady()
    }
}
