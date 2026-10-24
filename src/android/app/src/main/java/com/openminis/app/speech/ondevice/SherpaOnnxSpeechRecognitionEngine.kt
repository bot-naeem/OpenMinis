package com.openminis.app.speech.ondevice

import android.annotation.SuppressLint
import android.content.Context
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import com.openminis.app.logging.AppLogger
import com.openminis.app.speech.RecognitionError
import com.openminis.app.speech.SegmentEndReason
import com.openminis.app.speech.SpeechRecognitionEngine
import com.openminis.app.speech.SpeechRecognitionManager
import com.openminis.app.speech.VoiceActivityDetector
import com.openminis.app.speech.VoiceActivityListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * On-device offline automatic speech recognition engine using sherpa-onnx and
 * Alibaba SenseVoiceSmall INT8 quantized model.
 *
 * Runs 100% locally on CPU via ARM NEON, with zero network traffic, zero API cost,
 * and ultra-low latency (< 100ms inference).
 */
class SherpaOnnxSpeechRecognitionEngine(private val appContext: Context) : SpeechRecognitionEngine {

    companion object {
        private const val TAG = "SherpaASR"
        const val SAMPLE_RATE = 16_000

        init {
            // Preload ONNX Runtime before Sherpa JNI to ensure symbol resolution.
            // Sherpa-onnx JNI links against libonnxruntime.so; loading it first
            // avoids "cannot locate symbol OrtGetApiBase" when multiple ONNX
            // Runtime versions are present (e.g., from RealTimeCutVAD).
            try {
                System.loadLibrary("onnxruntime")
                AppLogger.info(TAG, "Preloaded libonnxruntime.so successfully")
            } catch (e: UnsatisfiedLinkError) {
                AppLogger.warning(TAG, "Failed to preload libonnxruntime.so: ${e.message}")
            }
        }
    }

    override val id: String = "ondevice:sherpa"
    override val displayName: String = "端侧离线语音 (SenseVoice)"
    override val supportsPartialResults: Boolean = false

    override val isAvailable: Boolean
        get() = OnDeviceSpeechModelManager.isModelReady(appContext) &&
            OnDeviceSpeechModelManager.isPreferred.value

    override val supportedLocales: List<Locale> = listOf(
        Locale.CHINESE,
        Locale.SIMPLIFIED_CHINESE,
        Locale.TRADITIONAL_CHINESE,
        Locale.ENGLISH,
        Locale.JAPANESE,
        Locale.KOREAN,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val recording = AtomicBoolean(false)
    private val rawAudioBuffer = ByteArrayOutputStream()
    private var vad: VoiceActivityDetector? = null
    private var activeListener: SpeechRecognitionEngine.Listener? = null

    // Cached recognizer instance to eliminate cold-start overhead
    @Volatile
    private var cachedRecognizer: OfflineRecognizer? = null
    @Volatile
    private var lastLoadedLang: String? = null
    @Volatile
    private var lastLoadedItn: Boolean = true
    @Volatile
    private var lastLoadedThreads: Int = 2

    @Synchronized
    fun getOrCreateRecognizer(): OfflineRecognizer? {
        if (!OnDeviceSpeechModelManager.isModelReady(appContext)) return null

        val currentLang = OnDeviceSpeechModelManager.language.value
        val currentItn = OnDeviceSpeechModelManager.useItn.value
        val currentThreads = OnDeviceSpeechModelManager.numThreads.value

        val existing = cachedRecognizer
        if (existing != null &&
            lastLoadedLang == currentLang &&
            lastLoadedItn == currentItn &&
            lastLoadedThreads == currentThreads
        ) {
            return existing
        }

        try {
            existing?.release()
        } catch (_: Throwable) {}

        return try {
            val modelFile = OnDeviceSpeechModelManager.getModelFile(appContext)
            val tokensFile = OnDeviceSpeechModelManager.getTokensFile(appContext)

            val senseVoiceConfig = OfflineSenseVoiceModelConfig(
                model = modelFile.absolutePath,
                language = currentLang,
                useInverseTextNormalization = currentItn,
            )
            val modelConfig = OfflineModelConfig(
                senseVoice = senseVoiceConfig,
                tokens = tokensFile.absolutePath,
                numThreads = currentThreads,
                debug = false,
            )
            val recognizerConfig = OfflineRecognizerConfig(modelConfig = modelConfig)
            val recognizer = OfflineRecognizer(null, recognizerConfig)

            cachedRecognizer = recognizer
            lastLoadedLang = currentLang
            lastLoadedItn = currentItn
            lastLoadedThreads = currentThreads
            AppLogger.info(TAG, "SenseVoice OfflineRecognizer initialized (threads=$currentThreads, lang=$currentLang, itn=$currentItn)")
            recognizer
        } catch (t: Throwable) {
            AppLogger.error(TAG, "Failed to initialize OfflineRecognizer: ${t.message}")
            null
        }
    }

    @SuppressLint("MissingPermission")
    override fun start(locale: Locale, listener: SpeechRecognitionEngine.Listener) {
        if (recording.getAndSet(true)) {
            AppLogger.warning(TAG, "Already recording in SherpaASR")
            return
        }

        activeListener = listener
        synchronized(rawAudioBuffer) {
            rawAudioBuffer.reset()
        }

        val detector = VoiceActivityDetector(
            appContext,
            object : VoiceActivityListener {
                override fun onVoiceStart() {
                    listener.onReadyForSpeech()
                }

                override fun onLevel(level: Float) {
                    listener.onRmsDb(level * 12f)
                    SpeechRecognitionManager.pushLevel(level * 12f)
                }

                override fun onVoiceEnd(wav: ByteArray, reason: SegmentEndReason, spokenSeconds: Float) {
                    if (!recording.compareAndSet(true, false)) return
                    handleSpokenWav(wav, listener)
                }

                override fun onCaptureError(message: String) {
                    recording.set(false)
                    listener.onError(RecognitionError.AUDIO_ERROR, message)
                }
            }
        )
        detector.rawAudioSink = { bytes, count ->
            synchronized(rawAudioBuffer) {
                rawAudioBuffer.write(bytes, 0, count)
            }
        }
        vad = detector

        val err = detector.start()
        if (err != null) {
            recording.set(false)
            listener.onError(RecognitionError.AUDIO_ERROR, err)
            return
        }
        AppLogger.info(TAG, "VoiceActivityDetector started for on-device ASR.")
    }

    private fun handleSpokenWav(wav: ByteArray, listener: SpeechRecognitionEngine.Listener) {
        scope.launch {
            try {
                if (wav.size <= 44) {
                    listener.onFinal("")
                    return@launch
                }
                val (transcription, latencyMs) = transcribeWav(wav)
                AppLogger.info(TAG, "On-device SenseVoice result: '$transcription' (latency=${latencyMs}ms)")
                listener.onFinal(transcription)
            } catch (t: Throwable) {
                AppLogger.error(TAG, "Error decoding on-device speech: ${t.message}")
                listener.onError(RecognitionError.TRANSCRIPTION_FAILED, t.message)
            } finally {
                vad?.stop()
                vad = null
            }
        }
    }

    private fun handleSpokenPcm(pcm: ByteArray, listener: SpeechRecognitionEngine.Listener) {
        scope.launch {
            try {
                val (transcription, latencyMs) = transcribeAudio(pcm)
                AppLogger.info(TAG, "On-device SenseVoice result: '$transcription' (latency=${latencyMs}ms)")
                listener.onFinal(transcription)
            } catch (t: Throwable) {
                AppLogger.error(TAG, "Error decoding on-device speech: ${t.message}")
                listener.onError(RecognitionError.TRANSCRIPTION_FAILED, t.message)
            }
        }
    }

    override fun stop() {
        if (recording.compareAndSet(true, false)) {
            vad?.stop()
            vad = null
            val pcm = synchronized(rawAudioBuffer) { rawAudioBuffer.toByteArray() }
            val listener = activeListener
            if (pcm.size > SAMPLE_RATE * 0.3 * 2 && listener != null) {
                handleSpokenPcm(pcm, listener)
            } else {
                listener?.onFinal("")
            }
        }
    }

    override fun cancel() {
        recording.set(false)
        vad?.cancel()
        vad = null
        activeListener = null
        synchronized(rawAudioBuffer) { rawAudioBuffer.reset() }
    }

    fun transcribeWav(wav: ByteArray): Pair<String, Long> {
        val pcm = if (wav.size > 44) wav.copyOfRange(44, wav.size) else wav
        return transcribeAudio(pcm)
    }

    fun transcribeAudio(pcmData: ByteArray): Pair<String, Long> {
        val recognizer = getOrCreateRecognizer() ?: return Pair("", 0L)
        val startTime = System.currentTimeMillis()
        val floatSamples = FloatArray(pcmData.size / 2)
        val bb = ByteBuffer.wrap(pcmData).order(ByteOrder.LITTLE_ENDIAN)
        for (i in floatSamples.indices) {
            floatSamples[i] = (bb.short / 32768.0f).coerceIn(-1.0f, 1.0f)
        }

        val stream = recognizer.createStream()
        return try {
            stream.acceptWaveform(floatSamples, SAMPLE_RATE)
            recognizer.decode(stream)
            val res = recognizer.getResult(stream)
            val latency = System.currentTimeMillis() - startTime
            Pair(res.text.trim(), latency)
        } finally {
            stream.release()
        }
    }
}
