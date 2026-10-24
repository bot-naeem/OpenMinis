package com.openminis.app.ui.overlay

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.openminis.app.debug.HeadlessChatRunner
import com.openminis.app.logging.AppLogger
import com.openminis.app.service.SessionActivityTracker
import com.openminis.app.ui.aurora.AuroraGlowManager
import com.openminis.app.ui.aurora.AuroraState
import com.openminis.app.ui.aurora.ShutterBlinkListener
import com.openminis.app.voice.HeadlessVoiceInteractionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

/**
 * Floating intervention bar ("人在回路"悬浮控制组件) providing "接管任务" (Take Over)
 * and "追加指令" (Append Instruction) actions during agent automation.
 *
 * Implements [ShutterBlinkListener] to seamlessly disappear during physical screen
 * capture so that the vision model perceives 100% clean, unblocked native screenshots.
 */
object AgentInterventionOverlayManager : ShutterBlinkListener {

    private const val TAG = "InterventionOverlay"

    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val isAttached = AtomicBoolean(false)

    @Volatile
    private var overlayContainer: LinearLayout? = null
    private var windowManager: WindowManager? = null

    @Volatile
    private var currentSessionId: String? = null

    private var isPausedByUser = false

    init {
        // Register for screenshot shutter sync
        AuroraGlowManager.registerShutterListener(this)
    }

    /**
     * Show the floating intervention bar for the active [sessionId].
     */
    fun show(context: Context, sessionId: String) {
        currentSessionId = sessionId
        isPausedByUser = false

        if (!AuroraGlowManager.hasOverlayPermission(context)) {
            AuroraGlowManager.tryAutoGrantOverlayWithRoot(context)
        }
        if (!AuroraGlowManager.hasOverlayPermission(context)) {
            AppLogger.warning(TAG, "Cannot show intervention overlay: missing SYSTEM_ALERT_WINDOW")
            return
        }

        mainHandler.post {
            if (isAttached.get()) {
                updateUiState(isPaused = false)
                return@post
            }

            try {
                val wm = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return@post
                windowManager = wm

                val container = createOverlayView(context)
                val params = createLayoutParams(context)

                wm.addView(container, params)
                overlayContainer = container
                isAttached.set(true)
                updateUiState(isPaused = false)
                AppLogger.info(TAG, "Intervention overlay attached for session=$sessionId")
            } catch (t: Throwable) {
                AppLogger.warning(TAG, "Failed to attach intervention overlay: ${t.message}")
            }
        }
    }

    /**
     * Hide and dismiss the intervention overlay.
     */
    fun hide() {
        mainHandler.post {
            try {
                val container = overlayContainer
                val wm = windowManager
                if (container != null && wm != null && isAttached.get()) {
                    wm.removeView(container)
                    AppLogger.info(TAG, "Intervention overlay detached")
                }
            } catch (t: Throwable) {
                AppLogger.warning(TAG, "Failed to remove overlay: ${t.message}")
            } finally {
                overlayContainer = null
                isAttached.set(false)
                isPausedByUser = false
            }
        }
    }

    // ── ShutterBlinkListener (Zero-Interference Screenshot) ─────────────────

    override fun onShutterHide() {
        // Must be fast and synchronous on main thread
        overlayContainer?.let { view ->
            view.alpha = 0f
            view.visibility = View.INVISIBLE
        }
    }

    override fun onShutterRestore() {
        overlayContainer?.let { view ->
            view.alpha = 1f
            view.visibility = View.VISIBLE
        }
    }

    // ── UI Construction & Layout ───────────────────────────────────────────

    private fun createOverlayView(context: Context): LinearLayout {
        val density = context.resources.displayMetrics.density

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(
                (14 * density).toInt(),
                (7 * density).toInt(),
                (14 * density).toInt(),
                (9 * density).toInt()
            )
            elevation = 10 * density
        }

        setupDraggable(container)
        return container
    }

    private enum class OverlayMode {
        RUNNING,
        PAUSED,
        INPUT
    }

    private var currentMode = OverlayMode.RUNNING

    private fun renderCurrentMode(context: Context) {
        val container = overlayContainer ?: return
        val density = context.resources.displayMetrics.density

        container.removeAllViews()

        // Dynamic frosted glass border & background
        val (bgHex, strokeHex, strokeWidthDp) = when (currentMode) {
            OverlayMode.RUNNING -> Triple("#F00C0F17", "#4400F5D4", 1.2f)
            OverlayMode.PAUSED -> Triple("#F2120F16", "#80FFB703", 1.5f)
            OverlayMode.INPUT -> Triple("#F50D111A", "#8000F5D4", 1.5f)
        }

        container.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 24 * density
            setColor(Color.parseColor(bgHex))
            setStroke((strokeWidthDp * density).toInt(), Color.parseColor(strokeHex))
        }

        when (currentMode) {
            OverlayMode.RUNNING -> {
                // Top drag handle
                container.addView(createDragHandle(context))

                val row = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }

                // Breathing cyan status dot
                row.addView(createStatusDot(context, "#00F5D4"))

                // Take Over button (Ruby Sunset glow)
                val btnTakeOver = createPillButton(
                    context = context,
                    iconText = "✋",
                    labelText = "接管任务",
                    strokeHex = "#80FF453A",
                    bgStartHex = "#33FF453A",
                    bgEndHex = "#44FF3B30",
                    textColorHex = "#FFF5F5"
                ) {
                    handleTakeOver(context)
                }
                row.addView(btnTakeOver)

                // High-tech divider
                val divider = View(context).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        (1 * density).toInt(),
                        (18 * density).toInt()
                    ).apply {
                        marginStart = (8 * density).toInt()
                        marginEnd = (8 * density).toInt()
                    }
                    setBackgroundColor(Color.parseColor("#26FFFFFF"))
                }
                row.addView(divider)

                // Append Instruction button (Cyberpunk Cyan glow)
                val btnAppend = createPillButton(
                    context = context,
                    iconText = "💬",
                    labelText = "追加指令",
                    strokeHex = "#8000F5D4",
                    bgStartHex = "#2600F5D4",
                    bgEndHex = "#3300B4D8",
                    textColorHex = "#E6FFFF"
                ) {
                    switchToInputMode(context)
                }
                row.addView(btnAppend)

                container.addView(row)
            }
            OverlayMode.PAUSED -> {
                // Top drag handle
                container.addView(createDragHandle(context))

                val row = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }

                // Paused status badge
                val badge = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(
                        (8 * density).toInt(),
                        (4 * density).toInt(),
                        (10 * density).toInt(),
                        (4 * density).toInt()
                    )
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.RECTANGLE
                        cornerRadius = 14 * density
                        setColor(Color.parseColor("#26FFB703"))
                        setStroke((1 * density).toInt(), Color.parseColor("#66FFB703"))
                    }
                    addView(createStatusDot(context, "#FFB703"))
                    val t = TextView(context).apply {
                        text = "手动接管中"
                        setTextColor(Color.parseColor("#FFD000"))
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
                        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                    }
                    addView(t)
                }
                row.addView(badge)

                val s1 = View(context).apply {
                    layoutParams = LinearLayout.LayoutParams((8 * density).toInt(), 1)
                }
                row.addView(s1)

                // Resume button (Emerald neon glow)
                val btnResume = createPillButton(
                    context = context,
                    iconText = "▶",
                    labelText = "继续让 Minis 执行",
                    strokeHex = "#B306D6A0",
                    bgStartHex = "#4006D6A0",
                    bgEndHex = "#6605B888",
                    textColorHex = "#FFFFFF"
                ) {
                    handleResume(context)
                }
                row.addView(btnResume)

                val s2 = View(context).apply {
                    layoutParams = LinearLayout.LayoutParams((6 * density).toInt(), 1)
                }
                row.addView(s2)

                // Cancel button
                val btnCancel = createPillButton(
                    context = context,
                    iconText = "✕",
                    labelText = "放弃",
                    strokeHex = "#33FFFFFF",
                    bgStartHex = "#1F2430",
                    bgEndHex = "#1F2430",
                    textColorHex = "#94A3B8"
                ) {
                    hide()
                }
                row.addView(btnCancel)

                container.addView(row)
            }
            OverlayMode.INPUT -> {
                // Header bar
                val headerRow = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        bottomMargin = (8 * density).toInt()
                    }

                    val titleText = TextView(context).apply {
                        text = "💬 追加指令给智能体"
                        setTextColor(Color.parseColor("#94A3B8"))
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
                        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    }
                    addView(titleText)

                    // Close circle button
                    val closeBtn = TextView(context).apply {
                        text = "✕"
                        setTextColor(Color.parseColor("#CBD5E1"))
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                        gravity = Gravity.CENTER
                        layoutParams = LinearLayout.LayoutParams(
                            (22 * density).toInt(),
                            (22 * density).toInt()
                        )
                        background = GradientDrawable().apply {
                            shape = GradientDrawable.OVAL
                            setColor(Color.parseColor("#26FFFFFF"))
                        }
                        setOnClickListener {
                            performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                            switchBackFromInput(context)
                        }
                    }
                    addView(closeBtn)
                }
                container.addView(headerRow)

                // Input + actions row
                val inputRow = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                }

                // Styled EditText
                val editText = EditText(context).apply {
                    tag = "append_input_edit_text"
                    hint = "想让 Minis 做什么？(如: 点跳过/输入密码)"
                    setHintTextColor(Color.parseColor("#64748B"))
                    setTextColor(Color.WHITE)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.RECTANGLE
                        cornerRadius = 16 * density
                        setColor(Color.parseColor("#181B26"))
                        setStroke((1 * density).toInt(), Color.parseColor("#4400F5D4"))
                    }
                    setPadding(
                        (12 * density).toInt(),
                        (8 * density).toInt(),
                        (12 * density).toInt(),
                        (8 * density).toInt()
                    )
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                        minWidth = (200 * density).toInt()
                    }
                    isSingleLine = true
                    imeOptions = EditorInfo.IME_ACTION_SEND
                    setOnEditorActionListener { _, actionId, _ ->
                        if (actionId == EditorInfo.IME_ACTION_SEND || actionId == EditorInfo.IME_ACTION_DONE) {
                            val content = text?.toString()?.trim().orEmpty()
                            if (content.isNotEmpty()) {
                                sendSteeringInstruction(context, content)
                            }
                            true
                        } else false
                    }
                }
                inputRow.addView(editText)

                val s1 = View(context).apply {
                    layoutParams = LinearLayout.LayoutParams((6 * density).toInt(), 1)
                }
                inputRow.addView(s1)

                // Voice dictation button (Violet circle)
                val btnVoice = TextView(context).apply {
                    text = "🎙️"
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                    gravity = Gravity.CENTER
                    layoutParams = LinearLayout.LayoutParams(
                        (34 * density).toInt(),
                        (34 * density).toInt()
                    )
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(Color.parseColor("#26A06CD5"))
                        setStroke((1 * density).toInt(), Color.parseColor("#80A06CD5"))
                    }
                    setOnClickListener {
                        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        val sId = currentSessionId
                        switchBackFromInput(context)
                        if (sId != null) {
                            HeadlessVoiceInteractionManager.start(context, targetSessionId = sId, isSteering = true)
                        }
                    }
                }
                inputRow.addView(btnVoice)

                val s2 = View(context).apply {
                    layoutParams = LinearLayout.LayoutParams((6 * density).toInt(), 1)
                }
                inputRow.addView(s2)

                // Send button (Cyan solid pill)
                val btnSend = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER
                    isClickable = true
                    isFocusable = true
                    setPadding(
                        (12 * density).toInt(),
                        (8 * density).toInt(),
                        (14 * density).toInt(),
                        (8 * density).toInt()
                    )
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.RECTANGLE
                        cornerRadius = 16 * density
                        colors = intArrayOf(Color.parseColor("#00F5D4"), Color.parseColor("#00BBF9"))
                        gradientType = GradientDrawable.LINEAR_GRADIENT
                        orientation = GradientDrawable.Orientation.LEFT_RIGHT
                    }
                    val tv = TextView(context).apply {
                        text = "发送 ➔"
                        setTextColor(Color.parseColor("#0A0E17"))
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                    }
                    addView(tv)
                    setOnClickListener {
                        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        val content = editText.text?.toString()?.trim().orEmpty()
                        if (content.isNotEmpty()) {
                            sendSteeringInstruction(context, content)
                        } else {
                            Toast.makeText(context, "请输入指令内容", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
                inputRow.addView(btnSend)

                container.addView(inputRow)
            }
        }
    }

    private fun createDragHandle(context: Context): View {
        val density = context.resources.displayMetrics.density
        return View(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                (32 * density).toInt(),
                (3.5 * density).toInt()
            ).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = (6 * density).toInt()
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 2 * density
                setColor(Color.parseColor("#33FFFFFF"))
            }
        }
    }

    private fun createStatusDot(context: Context, colorHex: String): View {
        val density = context.resources.displayMetrics.density
        return View(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                (7 * density).toInt(),
                (7 * density).toInt()
            ).apply {
                gravity = Gravity.CENTER_VERTICAL
                marginEnd = (6 * density).toInt()
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor(colorHex))
            }
        }
    }

    private fun createPillButton(
        context: Context,
        iconText: String?,
        labelText: String,
        strokeHex: String,
        bgStartHex: String,
        bgEndHex: String,
        textColorHex: String = "#FFFFFF",
        onClick: () -> Unit
    ): LinearLayout {
        val density = context.resources.displayMetrics.density

        val normalBg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 18 * density
            colors = intArrayOf(Color.parseColor(bgStartHex), Color.parseColor(bgEndHex))
            gradientType = GradientDrawable.LINEAR_GRADIENT
            orientation = GradientDrawable.Orientation.LEFT_RIGHT
            setStroke((1.2 * density).toInt(), Color.parseColor(strokeHex))
        }

        val mask = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 18 * density
            setColor(Color.WHITE)
        }

        val rippleBg = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            RippleDrawable(ColorStateList.valueOf(Color.parseColor("#33FFFFFF")), normalBg, mask)
        } else {
            normalBg
        }

        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            background = rippleBg
            setPadding(
                (12 * density).toInt(),
                (7 * density).toInt(),
                (14 * density).toInt(),
                (7 * density).toInt()
            )

            if (!iconText.isNullOrEmpty()) {
                val iconView = TextView(context).apply {
                    text = iconText
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                    setPadding(0, 0, (4 * density).toInt(), 0)
                }
                addView(iconView)
            }

            val textView = TextView(context).apply {
                text = labelText
                setTextColor(Color.parseColor(textColorHex))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            }
            addView(textView)

            setOnClickListener {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                } else {
                    performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                }
                onClick()
            }
        }
    }

    private fun updateUiState(isPaused: Boolean) {
        val container = overlayContainer ?: return
        currentMode = if (isPaused) OverlayMode.PAUSED else OverlayMode.RUNNING
        renderCurrentMode(container.context)
    }

    private fun switchToInputMode(context: Context) {
        val container = overlayContainer ?: return
        val wm = windowManager ?: return
        val params = container.layoutParams as? WindowManager.LayoutParams ?: return

        currentMode = OverlayMode.INPUT
        params.flags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        params.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE or
            WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN
        try {
            wm.updateViewLayout(container, params)
        } catch (_: Throwable) {}

        renderCurrentMode(context)

        val et = container.findViewWithTag<EditText>("append_input_edit_text")
        et?.requestFocus()
        mainHandler.postDelayed({
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.showSoftInput(et, InputMethodManager.SHOW_IMPLICIT)
        }, 150)
    }

    private fun switchBackFromInput(context: Context) {
        val container = overlayContainer ?: return
        val wm = windowManager ?: return
        val params = container.layoutParams as? WindowManager.LayoutParams ?: return

        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(container.windowToken, 0)

        currentMode = if (isPausedByUser) OverlayMode.PAUSED else OverlayMode.RUNNING
        params.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        try {
            wm.updateViewLayout(container, params)
        } catch (_: Throwable) {}

        renderCurrentMode(context)
    }

    private fun sendSteeringInstruction(context: Context, text: String) {
        val sId = currentSessionId ?: return
        switchBackFromInput(context)
        Toast.makeText(context, "已追加指令: $text", Toast.LENGTH_SHORT).show()

        scope.launch(Dispatchers.IO) {
            try {
                HeadlessChatRunner.prompt(
                    context = context,
                    sessionId = sId,
                    text = "【用户追加指令】: $text",
                    wait = false,
                    timeoutMs = 60_000L,
                )
            } catch (t: Throwable) {
                AppLogger.warning(TAG, "Failed to send steering instruction: ${t.message}")
            }
        }
        AuroraGlowManager.setState(AuroraState.EXECUTING)
    }

    private fun createLayoutParams(context: Context): WindowManager.LayoutParams {
        val density = context.resources.displayMetrics.density
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = (48 * density).toInt() // Positioned neatly above system gesture bar
        }
    }

    private fun setupDraggable(view: View) {
        var startX = 0f
        var startY = 0f
        var initialX = 0
        var initialY = 0
        var isDragging = false

        view.setOnTouchListener { v, event ->
            val wm = windowManager ?: return@setOnTouchListener false
            val params = v.layoutParams as? WindowManager.LayoutParams ?: return@setOnTouchListener false

            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = event.rawX
                    startY = event.rawY
                    initialX = params.x
                    initialY = params.y
                    isDragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - startX
                    val dy = event.rawY - startY
                    if (abs(dx) > 10 || abs(dy) > 10) {
                        isDragging = true
                    }
                    if (isDragging) {
                        params.x = initialX + dx.toInt()
                        params.y = initialY - dy.toInt()
                        try {
                            wm.updateViewLayout(v, params)
                        } catch (_: Throwable) {}
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!isDragging) {
                        v.performClick()
                    }
                    true
                }
                else -> false
            }
        }
    }

    // ── Interaction Handlers ───────────────────────────────────────────────

    private fun handleTakeOver(context: Context) {
        isPausedByUser = true
        AppLogger.info(TAG, "User triggered TAKE OVER. Halting active agent loop...")

        // 1. Halt tool execution
        SessionActivityTracker.cancelAllActiveStreams()

        // 2. Set Aurora to calm amber PAUSED state
        AuroraGlowManager.setState(AuroraState.PAUSED)

        // 3. Update overlay UI to Option A (show Resume button)
        updateUiState(isPaused = true)
    }

    private fun handleResume(context: Context) {
        isPausedByUser = false
        val sId = currentSessionId ?: return
        AppLogger.info(TAG, "User tapped RESUME task for session=$sId")

        // Switch Aurora back to EXECUTING
        AuroraGlowManager.setState(AuroraState.EXECUTING)
        updateUiState(isPaused = false)

        // Send resume steering prompt to the agent
        scope.launch(Dispatchers.IO) {
            try {
                val resumePrompt = "我已完成屏幕上的手动接管操作，请重新通过 screen_view 观察当前屏幕状态，并继续完成剩余任务。"
                HeadlessChatRunner.prompt(
                    context = context,
                    sessionId = sId,
                    text = resumePrompt,
                    wait = false,
                    timeoutMs = 60_000L,
                )
            } catch (t: Throwable) {
                AppLogger.warning(TAG, "Failed to resume agent task: ${t.message}")
            }
        }
    }

    private fun handleAppendInstruction(context: Context) {
        val sId = currentSessionId ?: return
        AppLogger.info(TAG, "User tapped APPEND INSTRUCTION for session=$sId")

        // Trigger headless voice prompt to collect user instruction
        HeadlessVoiceInteractionManager.start(context, targetSessionId = sId, isSteering = true)
    }
}

