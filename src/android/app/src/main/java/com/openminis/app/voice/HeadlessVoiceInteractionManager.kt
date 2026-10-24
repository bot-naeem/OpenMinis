package com.openminis.app.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.openminis.app.debug.HeadlessChatRunner
import com.openminis.app.logging.AppLogger
import com.openminis.app.speech.RecognitionError
import com.openminis.app.speech.RecognitionState
import com.openminis.app.speech.SpeechRecognitionManager
import com.openminis.app.ui.aurora.AuroraGlowManager
import com.openminis.app.ui.aurora.AuroraState
import com.openminis.app.ui.overlay.AgentInterventionOverlayManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Orchestrates zero-Activity headless voice interaction on the active screen.
 *
 * Flow:
 * 1. Ducks background audio & lights up Aurora Edge Glow in LISTENING mode.
 * 2. Captures speech using SpeechRecognitionManager + VAD auto-stop.
 * 3. On speech end: Aurora switches to THINKING mode.
 * 4. Dispatches prompt to HeadlessChatRunner.
 * 5. If GUI actions occur: Aurora switches to EXECUTING and surfaces the Intervention Overlay.
 *    If conversational query: speaks response via TTS and gently dismisses Aurora.
 */
object HeadlessVoiceInteractionManager {

    private const val TAG = "HeadlessVoice"
    private const val SESSION_LEASE = "headless_voice"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val isInteracting = AtomicBoolean(false)

    private var audioManager: AudioManager? = null
    private var focusRequest: AudioFocusRequest? = null
    private var ttsEngine: TextToSpeech? = null
    private var isTtsReady = false

    /**
     * Start headless voice interaction on the current screen.
     *
     * @param targetSessionId If provided, prompts or appends into this session;
     *                        otherwise resolves or creates a new session.
     * @param isSteering If true, the user's speech is treated as a steering instruction
     *                   injected into the active session.
     */
    fun start(
        context: Context,
        targetSessionId: String? = null,
        isSteering: Boolean = false,
    ) {
        if (isInteracting.getAndSet(true)) {
            AppLogger.info(TAG, "Voice interaction already in progress. Ignoring duplicate start.")
            return
        }

        val appContext = context.applicationContext
        initTtsIfNecessary(appContext)

        scope.launch {
            try {
                // 1. Duck background audio
                acquireAudioFocus(appContext)

                // 2. Turn on Aurora in LISTENING state
                AuroraGlowManager.acquire(appContext, SESSION_LEASE, AuroraState.LISTENING)

                // 3. Ensure SpeechRecognitionManager is ready
                SpeechRecognitionManager.init(appContext)

                val capturedText = StringBuilder()
                val recordingDone = kotlinx.coroutines.CompletableDeferred<Boolean>()

                SpeechRecognitionManager.startRecording(
                    onPartialOrFinal = { text, isFinal ->
                        capturedText.clear()
                        capturedText.append(text)
                        if (isFinal) {
                            recordingDone.complete(true)
                        }
                    },
                    onError = { error, msg ->
                        AppLogger.warning(TAG, "Speech recognition error: $error ($msg)")
                        recordingDone.complete(false)
                    },
                    onFailedAudio = {
                        recordingDone.complete(false)
                    }
                )

                // Wait for VAD silence cut or completion
                val ok = recordingDone.await()
                releaseAudioFocus()

                val promptText = capturedText.toString().trim()
                if (!ok || promptText.isBlank()) {
                    AppLogger.info(TAG, "No valid speech captured. Releasing aurora...")
                    AuroraGlowManager.release(appContext, SESSION_LEASE)
                    isInteracting.set(false)
                    return@launch
                }

                AppLogger.info(TAG, "Captured user prompt: '$promptText'")

                // 4. Switch to THINKING mode
                AuroraGlowManager.setState(AuroraState.THINKING)

                // 5. Execute prompt headlessly
                val resolvedSessionId = targetSessionId ?: HeadlessChatRunner.ensureSession(appContext)

                if (isSteering) {
                    val steerPrompt = "【用户追加指令】: $promptText"
                    HeadlessChatRunner.prompt(
                        context = appContext,
                        sessionId = resolvedSessionId,
                        text = steerPrompt,
                        wait = false,
                        timeoutMs = 60_000L
                    )
                    AuroraGlowManager.setState(AuroraState.EXECUTING)
                    AgentInterventionOverlayManager.show(appContext, resolvedSessionId)
                    isInteracting.set(false)
                } else {
                    val result = HeadlessChatRunner.prompt(
                        context = appContext,
                        sessionId = resolvedSessionId,
                        text = promptText,
                        wait = true,
                        timeoutMs = 90_000L
                    )

                    // 6. Inspect response & determine follow-up
                    handleAgentResponse(appContext, resolvedSessionId, result.responseText)
                }
            } catch (t: Throwable) {
                AppLogger.error(TAG, "Headless voice interaction failure: ${t.message}")
                releaseAudioFocus()
                AuroraGlowManager.release(appContext, SESSION_LEASE)
                isInteracting.set(false)
            }
        }
    }

    private fun handleAgentResponse(context: Context, sessionId: String, responseText: String?) {
        scope.launch {
            val reply = responseText?.trim() ?: ""

            // Check if automation tools (screen / click / swipe) are actively running
            val isToolActive = com.openminis.app.service.SessionActivityTracker.isToolRunning.value

            if (isToolActive) {
                // Agent started screen actions! Show Intervention Overlay and maintain EXECUTING glow
                AuroraGlowManager.setState(AuroraState.EXECUTING)
                AgentInterventionOverlayManager.show(context, sessionId)
                isInteracting.set(false)
            } else {
                // Conversational response: speak response aloud via TTS
                if (reply.isNotEmpty()) {
                    speakResponse(reply) {
                        AuroraGlowManager.setState(AuroraState.COMPLETED)
                        scope.launch {
                            delay(1200L)
                            AuroraGlowManager.release(context, SESSION_LEASE)
                            isInteracting.set(false)
                        }
                    }
                } else {
                    AuroraGlowManager.release(context, SESSION_LEASE)
                    isInteracting.set(false)
                }
            }
        }
    }

    private fun acquireAudioFocus(context: Context) {
        try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
            audioManager = am

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    .build()
                focusRequest = req
                am.requestAudioFocus(req)
            } else {
                @Suppress("DEPRECATION")
                am.requestAudioFocus(
                    null,
                    AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
                )
            }
        } catch (t: Throwable) {
            AppLogger.warning(TAG, "Audio focus request failed: ${t.message}")
        }
    }

    private fun releaseAudioFocus() {
        try {
            val am = audioManager ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                focusRequest?.let { am.abandonAudioFocusRequest(it) }
            } else {
                @Suppress("DEPRECATION")
                am.abandonAudioFocus(null)
            }
        } catch (_: Throwable) {}
        focusRequest = null
        audioManager = null
    }

    private fun initTtsIfNecessary(context: Context) {
        if (ttsEngine != null) return
        try {
            ttsEngine = TextToSpeech(context) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    ttsEngine?.language = Locale.getDefault()
                    isTtsReady = true
                }
            }
        } catch (t: Throwable) {
            AppLogger.warning(TAG, "Failed to initialize TTS engine: ${t.message}")
        }
    }

    private fun speakResponse(text: String, onFinished: () -> Unit) {
        val tts = ttsEngine
        if (tts == null || !isTtsReady) {
            onFinished()
            return
        }

        // Clean markdown formatting before speaking
        val cleanText = text
            .replace(Regex("```[\\s\\S]*?```"), "")
            .replace(Regex("`[^`]*`"), "")
            .replace(Regex("[#*_>~]"), "")
            .take(300) // Keep spoken summary concise

        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) { onFinished() }
            override fun onError(utteranceId: String?) { onFinished() }
        })

        val params = android.os.Bundle()
        params.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f)
        tts.speak(cleanText, TextToSpeech.QUEUE_FLUSH, params, "headless_reply")
    }
}
