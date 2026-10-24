package com.openminis.app.daemon

import android.content.Context
import android.net.LocalServerSocket
import android.net.LocalSocket
import android.os.Build
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.openminis.app.logging.AppLogger
import com.openminis.app.sandbox.RootAccess
import com.openminis.app.voice.HeadlessVoiceInteractionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.io.InputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Manages the native power key daemon (minis_power_daemon) lifecycle and
 * listens for hardware long-press wakeup signals via UNIX abstract domain sockets.
 */
object PowerKeyDaemonManager {

    private const val TAG = "PowerKeyDaemon"
    private const val SOCKET_NAME = "minis_power_ipc"
    private const val DAEMON_BIN_NAME = "minis_power_daemon"
    private const val TARGET_TMP_BIN = "/data/local/tmp/minis_power_daemon"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val isRunning = AtomicBoolean(false)
    private var serverSocket: LocalServerSocket? = null

    private val _isDaemonActive = MutableStateFlow(false)
    val isDaemonActive: StateFlow<Boolean> = _isDaemonActive.asStateFlow()

    private val _runningPid = MutableStateFlow<String?>(null)
    val runningPid: StateFlow<String?> = _runningPid.asStateFlow()

    private var longPressMs: Int = 600

    /**
     * Set long-press threshold in milliseconds (default: 600ms).
     */
    fun setThresholdMs(thresholdMs: Int) {
        longPressMs = thresholdMs.coerceIn(200, 3000)
    }

    /**
     * Starts the socket listener and starts the root daemon if root access is granted.
     */
    @Synchronized
    fun start(context: Context) {
        startSocketServer(context)

        scope.launch {
            if (RootAccess.isRootGranted()) {
                deployAndLaunchDaemon(context)
            }
        }
    }

    /**
     * Restarts the daemon and returns (success, detail message).
     */
    suspend fun restart(context: Context): Pair<Boolean, String> {
        startSocketServer(context)
        return deployAndLaunchDaemon(context)
    }

    /**
     * Manually trigger simulated hardware wakeup for testing.
     */
    fun testWakeup(context: Context) {
        AppLogger.info(TAG, "Test hardware wakeup triggered manually.")
        onHardwareWakeup(context)
    }

    /**
     * Stops the daemon and socket server.
     */
    @Synchronized
    fun stop() {
        isRunning.set(false)
        _isDaemonActive.value = false
        _runningPid.value = null

        try {
            serverSocket?.close()
        } catch (_: Throwable) {}
        serverSocket = null

        // Terminate native daemon via root
        scope.launch {
            if (RootAccess.isRootGranted()) {
                val killCmd = "pkill -9 \"$DAEMON_BIN_NAME\" 2>/dev/null || true"
                RootAccess.runSu(killCmd)
            }
        }
    }

    /**
     * Check if daemon is currently alive in process list.
     */
    suspend fun refreshStatus(): Boolean {
        if (!RootAccess.isRootGranted()) {
            _isDaemonActive.value = false
            _runningPid.value = null
            return false
        }
        val res = RootAccess.runSu("pidof $DAEMON_BIN_NAME", timeoutMs = 3000L)
        val pid = res?.stdout?.trim()?.split("\\s+".toRegex())?.firstOrNull()?.takeIf { it.isNotEmpty() }
        val active = !pid.isNullOrEmpty()
        _isDaemonActive.value = active
        _runningPid.value = pid
        return active
    }

    private fun startSocketServer(context: Context) {
        if (serverSocket != null) return
        isRunning.set(true)
        scope.launch {
            try {
                serverSocket = LocalServerSocket(SOCKET_NAME)
                AppLogger.info(TAG, "Local abstract stream socket '$SOCKET_NAME' listening...")

                while (isRunning.get()) {
                    val socket: LocalSocket = serverSocket?.accept() ?: break
                    handleSocketConnection(context, socket)
                }
            } catch (t: Throwable) {
                if (isRunning.get()) {
                    AppLogger.warning(TAG, "Socket server encountered error: ${t.message}")
                }
            }
        }
    }

    private fun handleSocketConnection(context: Context, socket: LocalSocket) {
        scope.launch {
            try {
                val input: InputStream = socket.inputStream
                val buffer = ByteArray(64)
                val read = input.read(buffer)
                if (read > 0) {
                    val message = String(buffer, 0, read).trim()
                    if (message.contains("WAKEUP")) {
                        AppLogger.info(TAG, "Hardware power button long-press detected! Initiating wakeup...")
                        onHardwareWakeup(context)
                    }
                }
                socket.close()
            } catch (t: Throwable) {
                AppLogger.warning(TAG, "Error handling socket client: ${t.message}")
            }
        }
    }

    private fun onHardwareWakeup(context: Context) {
        // 1. Trigger haptic feedback (crisp vibration)
        performHapticFeedback(context)

        // 2. Wake up screen if off
        wakeScreenIfNecessary(context)

        // 3. Initiate headless voice interaction on main thread
        scope.launch(Dispatchers.Main) {
            HeadlessVoiceInteractionManager.start(context)
        }
    }

    private fun performHapticFeedback(context: Context) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vm?.defaultVibrator?.vibrate(
                    VibrationEffect.createOneShot(60, VibrationEffect.DEFAULT_AMPLITUDE)
                )
            } else {
                @Suppress("DEPRECATION")
                val v = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    v?.vibrate(VibrationEffect.createOneShot(60, VibrationEffect.DEFAULT_AMPLITUDE))
                } else {
                    @Suppress("DEPRECATION")
                    v?.vibrate(60)
                }
            }
        } catch (t: Throwable) {
            AppLogger.warning(TAG, "Failed to vibrate: ${t.message}")
        }
    }

    private fun wakeScreenIfNecessary(context: Context) {
        try {
            val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            if (pm != null && !pm.isInteractive) {
                @Suppress("DEPRECATION")
                val wl = pm.newWakeLock(
                    PowerManager.SCREEN_BRIGHT_WAKE_LOCK or
                        PowerManager.ACQUIRE_CAUSES_WAKEUP or
                        PowerManager.ON_AFTER_RELEASE,
                    "minis:power_key_wakeup"
                )
                wl.acquire(3000L) // 3s hold
            }
        } catch (t: Throwable) {
            AppLogger.warning(TAG, "Failed to wake screen: ${t.message}")
        }
    }

    private suspend fun deployAndLaunchDaemon(context: Context): Pair<Boolean, String> {
        if (!RootAccess.isRootGranted()) {
            val msg = "Root 权限未授予，无法启动电源键守护进程"
            AppLogger.warning(TAG, msg)
            _isDaemonActive.value = false
            _runningPid.value = null
            return Pair(false, msg)
        }

        val nativeLibDir = context.applicationInfo.nativeLibraryDir
        val candidates = listOf(
            File(nativeLibDir, "lib$DAEMON_BIN_NAME.so"),
            File(nativeLibDir, DAEMON_BIN_NAME),
            File(context.filesDir, DAEMON_BIN_NAME)
        )
        val sourceFile = candidates.firstOrNull { it.exists() }
        val sourcePath = sourceFile?.absolutePath ?: File(nativeLibDir, "lib$DAEMON_BIN_NAME.so").absolutePath

        // Highly compatible deployment script: works across all Toybox/Busybox ROMs
        // Use POSIX sh syntax only, avoid bashisms, keep newlines for readability.
        // Build command with explicit quoting to avoid shell parsing issues.
        val cmd = """
            mkdir -p /data/local/tmp
            [ -f "$sourcePath" ] && cp -f "$sourcePath" "$TARGET_TMP_BIN" || true
            chmod 755 "$TARGET_TMP_BIN"
            chcon u:object_r:system_file:s0 "$TARGET_TMP_BIN" 2>/dev/null || true
            pkill -9 "$DAEMON_BIN_NAME" 2>/dev/null || true
            "$TARGET_TMP_BIN" "$longPressMs" </dev/null >/dev/null 2>&1 &
            sleep 0.4
            pidof "$DAEMON_BIN_NAME"
        """.trimIndent()

        val res = RootAccess.runSu(cmd, timeoutMs = 8000L)
        val pid = res?.stdout?.trim()?.split("\\s+".toRegex())?.firstOrNull()?.takeIf { it.isNotEmpty() }
        if (!pid.isNullOrEmpty()) {
            _isDaemonActive.value = true
            _runningPid.value = pid
            val successMsg = "守护进程已成功运行 (PID: $pid, 阈值: ${longPressMs}ms)"
            AppLogger.info(TAG, successMsg)
            return Pair(true, successMsg)
        } else {
            _isDaemonActive.value = false
            _runningPid.value = null
            val errDetail = res?.stderr?.ifBlank { res.stdout } ?: "未知原因"
            val failMsg = "拉起守护进程失败: $errDetail"
            AppLogger.warning(TAG, failMsg)
            AppLogger.warning(TAG, "PowerKeyDaemon launch script: $cmd")
            AppLogger.warning(TAG, "PowerKeyDaemon runSu stdout: ${res?.stdout}")
            AppLogger.warning(TAG, "PowerKeyDaemon runSu stderr: ${res?.stderr}")
            return Pair(false, failMsg)
        }
    }
}
