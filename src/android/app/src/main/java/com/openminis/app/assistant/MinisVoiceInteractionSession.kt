package com.openminis.app.assistant

import android.app.assist.AssistContent
import android.app.assist.AssistStructure
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.service.voice.VoiceInteractionSession
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.openminis.app.R
import com.openminis.app.debug.HeadlessChatRunner
import com.openminis.app.tools.ScreenTool
import com.openminis.app.ui.aurora.AuroraGlowView
import com.openminis.app.ui.aurora.AuroraState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Overlay VoiceInteractionSession for system-level assistant invocation.
 * Aligned with Doubao Phone Assistant / Siri:
 * - Full-screen Aurora Edge Glow perimeter
 * - Floating glassmorphic voice capsule at the bottom
 * - Real-time speech transcription & quick action chips
 * - Direct execution via HeadlessChatRunner & Native Fast-Path
 */
class MinisVoiceInteractionSession(context: Context) : VoiceInteractionSession(context) {

    private val sessionScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val mainHandler = Handler(Looper.getMainLooper())

    private lateinit var rootView: FrameLayout
    private lateinit var auroraGlowView: AuroraGlowView
    private lateinit var statusTextView: TextView
    private lateinit var transcriptTextView: TextView
    private lateinit var micIcon: TextView

    private var speechRecognizer: SpeechRecognizer? = null
    private var isExecuting: Boolean = false

    override fun onCreateContentView(): View {
        val density = context.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        rootView = FrameLayout(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        // 1. Fullscreen Aurora Edge Glow layer
        auroraGlowView = AuroraGlowView(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        rootView.addView(auroraGlowView)

        // 2. Scrim touch listener: tap outside dismisses
        val scrimView = View(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setOnClickListener {
                if (!isExecuting) hide()
            }
        }
        rootView.addView(scrimView)

        // 3. Floating Bottom Capsule Card
        val capsuleCard = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val bgDrawable = GradientDrawable().apply {
                setColor(Color.parseColor("#EE161A22")) // Deep glassmorphism
                cornerRadius = dp(24).toFloat()
                setStroke(dp(1), Color.parseColor("#33FFFFFF"))
            }
            background = bgDrawable
            setPadding(dp(20), dp(16), dp(20), dp(16))
            elevation = dp(16).toFloat()

            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            ).apply {
                setMargins(dp(16), 0, dp(16), dp(28))
            }

            // Prevent touch on capsule from closing scrim
            isClickable = true
            isFocusable = true
        }

        // Header Row: [Mic indicator] [Status Text] [Close Button]
        val headerRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        micIcon = TextView(context).apply {
            text = "🎙️"
            textSize = 18f
            setPadding(0, 0, dp(8), 0)
        }
        headerRow.addView(micIcon)

        statusTextView = TextView(context).apply {
            text = context.getString(R.string.default_assistant_listening)
            setTextColor(Color.parseColor("#9CA3AF"))
            textSize = 13f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        headerRow.addView(statusTextView)

        val closeButton = TextView(context).apply {
            text = "✕"
            setTextColor(Color.parseColor("#6B7280"))
            textSize = 16f
            setPadding(dp(8), 0, dp(4), 0)
            setOnClickListener { hide() }
        }
        headerRow.addView(closeButton)
        capsuleCard.addView(headerRow)

        // Transcript / Goal Display Text
        transcriptTextView = TextView(context).apply {
            text = context.getString(R.string.default_assistant_hint)
            setTextColor(Color.WHITE)
            textSize = 17f
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, dp(10), 0, dp(14))
            }
        }
        capsuleCard.addView(transcriptTextView)

        // Quick Action Chips Row
        val chipScroll = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        val chipContainer = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        val chips = listOf(
            "⏰ 定7:30闹钟" to "帮我定一个明天早上7:30的闹钟",
            "⏱ 5分钟倒计时" to "设置一个5分钟的倒计时",
            "📅 今天日程" to "查看我今天的日历日程",
            "📷 微信扫一扫" to "打开微信扫一扫",
            "🌤 今天天气" to "查询今天的天气",
        )

        for ((label, prompt) in chips) {
            val chip = TextView(context).apply {
                text = label
                setTextColor(Color.parseColor("#E5E7EB"))
                textSize = 12f
                val chipBg = GradientDrawable().apply {
                    setColor(Color.parseColor("#26374151"))
                    cornerRadius = dp(14).toFloat()
                    setStroke(dp(1), Color.parseColor("#20FFFFFF"))
                }
                background = chipBg
                setPadding(dp(12), dp(6), dp(12), dp(6))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    setMargins(0, 0, dp(8), 0)
                }
                setOnClickListener {
                    submitGoal(prompt)
                }
            }
            chipContainer.addView(chip)
        }

        chipScroll.addView(chipContainer)
        capsuleCard.addView(chipScroll)

        rootView.addView(capsuleCard)
        return rootView
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        isExecuting = false
        triggerHaptic()

        auroraGlowView.setState(AuroraState.THINKING)
        statusTextView.text = context.getString(R.string.default_assistant_listening)
        transcriptTextView.text = context.getString(R.string.default_assistant_hint)
        micIcon.text = "🎙️"

        startListening()
    }

    override fun onHide() {
        super.onHide()
        stopListening()
        isExecuting = false
    }

    override fun onDestroy() {
        super.onDestroy()
        stopListening()
        sessionScope.cancel()
    }

    override fun onHandleScreenshot(screenshot: Bitmap?) {
        super.onHandleScreenshot(screenshot)
        if (screenshot != null) {
            Log.d(TAG, "onHandleScreenshot received OS bitmap: ${screenshot.width}x${screenshot.height}")
            ScreenTool.setLastSystemScreenshot(screenshot)
        }
    }

    override fun onHandleAssist(args: Bundle?, structure: AssistStructure?, content: AssistContent?) {
        super.onHandleAssist(args, structure, content)
        Log.d(TAG, "onHandleAssist structure: ${structure?.activityComponent}")
    }

    private fun startListening() {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            statusTextView.text = "语音识别不可用，请点击快捷指令"
            return
        }

        try {
            speechRecognizer?.destroy()
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
                setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {
                        statusTextView.text = context.getString(R.string.default_assistant_listening)
                        micIcon.text = "🟢"
                    }
                    override fun onBeginningOfSpeech() {
                        statusTextView.text = "正在倾听…"
                        auroraGlowView.setState(AuroraState.EXECUTING)
                    }
                    override fun onRmsChanged(rmsdB: Float) {}
                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onEndOfSpeech() {
                        statusTextView.text = "正在解析语音…"
                        micIcon.text = "⏳"
                    }
                    override fun onError(error: Int) {
                        Log.d(TAG, "speech recognition error: $error")
                        micIcon.text = "🎙️"
                        if (!isExecuting) {
                            statusTextView.text = "请点击上方快捷指令，或再次尝试说话"
                        }
                    }
                    override fun onResults(results: Bundle?) {
                        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        val text = matches?.firstOrNull()?.trim()
                        if (!text.isNullOrEmpty()) {
                            submitGoal(text)
                        }
                    }
                    override fun onPartialResults(partialResults: Bundle?) {
                        val partials = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        val text = partials?.firstOrNull()?.trim()
                        if (!text.isNullOrEmpty()) {
                            transcriptTextView.text = text
                        }
                    }
                    override fun onEvent(eventType: Int, params: Bundle?) {}
                })
            }

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            }
            speechRecognizer?.startListening(intent)
        } catch (e: Exception) {
            Log.w(TAG, "failed to start speech recognition: ${e.message}")
        }
    }

    private fun stopListening() {
        try {
            speechRecognizer?.stopListening()
            speechRecognizer?.destroy()
        } catch (_: Exception) {}
        speechRecognizer = null
    }

    private fun submitGoal(goal: String) {
        if (isExecuting) return
        isExecuting = true
        stopListening()

        triggerHaptic()
        transcriptTextView.text = goal
        statusTextView.text = context.getString(R.string.default_assistant_processing)
        auroraGlowView.setState(AuroraState.EXECUTING)

        sessionScope.launch(Dispatchers.IO) {
            val sessionId = "assistant_${System.currentTimeMillis()}"
            try {
                val outcome = HeadlessChatRunner.prompt(
                    context = context,
                    sessionId = sessionId,
                    prompt = goal,
                    timeoutMs = 45_000L,
                )

                withContext(Dispatchers.Main) {
                    triggerHaptic()
                    auroraGlowView.setState(AuroraState.COMPLETED)
                    val reply = outcome.response?.lines()?.firstOrNull { it.isNotBlank() }?.take(80)
                        ?: "已完成"
                    statusTextView.text = "✓ 已完成"
                    transcriptTextView.text = reply

                    mainHandler.postDelayed({
                        hide()
                    }, 2200)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Assistant goal failed", e)
                withContext(Dispatchers.Main) {
                    auroraGlowView.setState(AuroraState.PAUSED)
                    statusTextView.text = "执行遇到问题"
                    transcriptTextView.text = e.message ?: "未知异常"
                }
            }
        }
    }

    private fun triggerHaptic() {
        try {
            val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(30L)
            }
        } catch (_: Exception) {}
    }

    companion object {
        private const val TAG = "MinisVoiceSession"
    }
}
