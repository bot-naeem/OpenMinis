package com.openminis.app.speech.ondevice

import android.content.Context
import android.content.SharedPreferences
import com.openminis.app.logging.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.concurrent.TimeUnit

/**
 * Manages the on-device speech-to-text offline model lifecycle (download, verification,
 * storage, configuration) for SenseVoiceSmall INT8 using sherpa-onnx.
 */
object OnDeviceSpeechModelManager {

    private const val TAG = "OnDeviceSpeech"
    private const val PREFS = "on_device_speech_prefs"
    private const val KEY_PREFERRED = "preferred_on_device"
    private const val KEY_LANGUAGE = "language_code"
    private const val KEY_USE_ITN = "use_itn"
    private const val KEY_NUM_THREADS = "num_threads"

    private const val MODEL_SUBDIR = "models/sensevoice_int8"
    private const val MODEL_FILE_NAME = "model.int8.onnx"
    private const val TOKENS_FILE_NAME = "tokens.txt"

    // Primary Mirror: ModelScope High-Speed Mirror (Fast & Unrestricted in CN/Global)
    private const val PRIMARY_TOKENS_URL =
        "https://modelscope.cn/models/poloniumrock/SenseVoiceSmallOnnx/resolve/master/tokens.txt"
    private const val PRIMARY_MODEL_URL =
        "https://modelscope.cn/models/poloniumrock/SenseVoiceSmallOnnx/resolve/master/model.int8.onnx"

    // Backup Mirror: HuggingFace Mirror
    private const val BACKUP_TOKENS_URL =
        "https://hf-mirror.com/csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17/resolve/main/tokens.txt"
    private const val BACKUP_MODEL_URL =
        "https://hf-mirror.com/csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17/resolve/main/model.int8.onnx"

    sealed class ModelState {
        object NotDownloaded : ModelState()
        data class Downloading(
            val progress: Float,
            val currentStage: String,
            val downloadedBytes: Long,
            val totalBytes: Long,
        ) : ModelState()
        data class Ready(val modelDir: File, val totalSizeBytes: Long) : ModelState()
        data class Error(val message: String) : ModelState()
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var downloadJob: Job? = null

    private val _modelState = MutableStateFlow<ModelState>(ModelState.NotDownloaded)
    val modelState: StateFlow<ModelState> = _modelState.asStateFlow()

    private val _isPreferred = MutableStateFlow(true)
    val isPreferred: StateFlow<Boolean> = _isPreferred.asStateFlow()

    private val _language = MutableStateFlow("auto")
    val language: StateFlow<String> = _language.asStateFlow()

    private val _useItn = MutableStateFlow(true)
    val useItn: StateFlow<Boolean> = _useItn.asStateFlow()

    private val _numThreads = MutableStateFlow(2)
    val numThreads: StateFlow<Int> = _numThreads.asStateFlow()

    private lateinit var prefs: SharedPreferences

    private val httpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    fun init(context: Context) {
        val appContext = context.applicationContext
        prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        _isPreferred.value = prefs.getBoolean(KEY_PREFERRED, true)
        _language.value = prefs.getString(KEY_LANGUAGE, "auto") ?: "auto"
        _useItn.value = prefs.getBoolean(KEY_USE_ITN, true)
        _numThreads.value = prefs.getInt(KEY_NUM_THREADS, 2).coerceIn(1, 4)

        refreshStatus(appContext)
    }

    fun getModelDir(context: Context): File {
        val dir = File(context.filesDir, MODEL_SUBDIR)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun getModelFile(context: Context): File = File(getModelDir(context), MODEL_FILE_NAME)
    fun getTokensFile(context: Context): File = File(getModelDir(context), TOKENS_FILE_NAME)

    fun isModelReady(context: Context): Boolean {
        val mFile = getModelFile(context)
        val tFile = getTokensFile(context)
        // SenseVoice INT8 is ~75MB; sanity check > 50MB and tokens > 5KB
        return mFile.exists() && mFile.length() > 50L * 1024 * 1024 &&
            tFile.exists() && tFile.length() > 5 * 1024
    }

    fun refreshStatus(context: Context) {
        val appContext = context.applicationContext
        if (isModelReady(appContext)) {
            val totalSize = getModelFile(appContext).length() + getTokensFile(appContext).length()
            _modelState.value = ModelState.Ready(getModelDir(appContext), totalSize)
        } else if (_modelState.value !is ModelState.Downloading) {
            _modelState.value = ModelState.NotDownloaded
        }
    }

    fun setPreferred(context: Context, preferred: Boolean) {
        _isPreferred.value = preferred
        if (::prefs.isInitialized) {
            prefs.edit().putBoolean(KEY_PREFERRED, preferred).apply()
        }
        refreshStatus(context)
    }

    fun setLanguage(languageCode: String) {
        _language.value = languageCode
        if (::prefs.isInitialized) {
            prefs.edit().putString(KEY_LANGUAGE, languageCode).apply()
        }
    }

    fun setUseItn(enabled: Boolean) {
        _useItn.value = enabled
        if (::prefs.isInitialized) {
            prefs.edit().putBoolean(KEY_USE_ITN, enabled).apply()
        }
    }

    fun setNumThreads(threads: Int) {
        val clamped = threads.coerceIn(1, 4)
        _numThreads.value = clamped
        if (::prefs.isInitialized) {
            prefs.edit().putInt(KEY_NUM_THREADS, clamped).apply()
        }
    }

    fun startDownload(context: Context) {
        if (_modelState.value is ModelState.Downloading) return
        val appContext = context.applicationContext

        downloadJob?.cancel()
        downloadJob = scope.launch {
            try {
                _modelState.value = ModelState.Downloading(
                    progress = 0f,
                    currentStage = "正在连接模型服务器...",
                    downloadedBytes = 0L,
                    totalBytes = 75L * 1024 * 1024,
                )

                val modelDir = getModelDir(appContext)
                val tempTokens = File(modelDir, "$TOKENS_FILE_NAME.tmp")
                val tempModel = File(modelDir, "$MODEL_FILE_NAME.tmp")

                // Step 1: Download tokens.txt
                _modelState.value = ModelState.Downloading(0.02f, "下载词表配置 (tokens.txt)...", 0L, 75L * 1024 * 1024)
                val tokensDownloaded = downloadFileWithFallback(
                    primaryUrl = PRIMARY_TOKENS_URL,
                    backupUrl = BACKUP_TOKENS_URL,
                    targetFile = tempTokens,
                    onProgress = { _, _ -> /* small file */ }
                )
                if (!tokensDownloaded || !tempTokens.exists() || tempTokens.length() < 1024) {
                    throw IllegalStateException("词表文件下载失败")
                }

                // Step 2: Download model.int8.onnx (~75MB)
                _modelState.value = ModelState.Downloading(0.05f, "下载语音识别模型 (SenseVoice INT8)...", 0L, 75L * 1024 * 1024)
                val modelDownloaded = downloadFileWithFallback(
                    primaryUrl = PRIMARY_MODEL_URL,
                    backupUrl = BACKUP_MODEL_URL,
                    targetFile = tempModel,
                    onProgress = { bytesRead, totalBytes ->
                        val total = if (totalBytes > 0) totalBytes else 75L * 1024 * 1024
                        val p = (bytesRead.toFloat() / total.toFloat()).coerceIn(0.05f, 0.98f)
                        _modelState.value = ModelState.Downloading(
                            progress = p,
                            currentStage = "正在下载端侧离线模型 (${bytesRead / (1024 * 1024)}MB / ${total / (1024 * 1024)}MB)",
                            downloadedBytes = bytesRead,
                            totalBytes = total,
                        )
                    }
                )

                if (!modelDownloaded || !tempModel.exists() || tempModel.length() < 50L * 1024 * 1024) {
                    throw IllegalStateException("模型权重文件校验失败 (文件大小异常)")
                }

                // Step 3: Atomic rename
                val targetTokens = getTokensFile(appContext)
                val targetModel = getModelFile(appContext)

                if (targetTokens.exists()) targetTokens.delete()
                if (targetModel.exists()) targetModel.delete()

                tempTokens.renameTo(targetTokens)
                tempModel.renameTo(targetModel)

                AppLogger.info(TAG, "On-device SenseVoice model downloaded successfully.")
                refreshStatus(appContext)
            } catch (t: Throwable) {
                AppLogger.error(TAG, "Failed to download on-device model: ${t.message}")
                _modelState.value = ModelState.Error(t.message ?: "下载离线模型失败")
            }
        }
    }

    private suspend fun downloadFileWithFallback(
        primaryUrl: String,
        backupUrl: String,
        targetFile: File,
        onProgress: (bytesRead: Long, totalBytes: Long) -> Unit,
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            if (performDownload(primaryUrl, targetFile, onProgress)) return@withContext true
        } catch (e: Exception) {
            AppLogger.warning(TAG, "Primary mirror download failed ($primaryUrl): ${e.message}. Trying backup...")
        }

        try {
            return@withContext performDownload(backupUrl, targetFile, onProgress)
        } catch (e: Exception) {
            AppLogger.warning(TAG, "Backup mirror download failed ($backupUrl): ${e.message}")
            return@withContext false
        }
    }

    private fun performDownload(
        url: String,
        targetFile: File,
        onProgress: (bytesRead: Long, totalBytes: Long) -> Unit,
    ): Boolean {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Android; Mobile) OpenMinis/1.0")
            .build()

        val response = httpClient.newCall(request).execute()
        if (!response.isSuccessful) {
            response.close()
            return false
        }

        val body = response.body ?: return false
        val totalBytes = body.contentLength()
        var bytesReadTotal = 0L

        body.byteStream().use { input ->
            FileOutputStream(targetFile).use { output ->
                val buffer = ByteArray(32 * 1024)
                var read: Int
                while (input.read(buffer).also { read = it } != -1) {
                    output.write(buffer, 0, read)
                    bytesReadTotal += read
                    onProgress(bytesReadTotal, totalBytes)
                }
                output.flush()
            }
        }
        return true
    }

    fun cancelDownload() {
        downloadJob?.cancel()
        downloadJob = null
        _modelState.value = ModelState.NotDownloaded
    }

    fun deleteModel(context: Context) {
        downloadJob?.cancel()
        val appContext = context.applicationContext
        getModelFile(appContext).delete()
        getTokensFile(appContext).delete()
        _modelState.value = ModelState.NotDownloaded
        AppLogger.info(TAG, "On-device model deleted.")
    }
}
