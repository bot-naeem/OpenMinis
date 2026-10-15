package com.openminis.app.tools

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import com.openminis.app.accessibility.MinisAccessibilityService
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam
import com.openminis.app.offload.ShizukuManager
import com.openminis.app.sandbox.PRootKernel
import com.openminis.app.sandbox.RootAccess
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

/**
 * Mobile GUI automation and Visual Grounding tool (aligned with Doubao Phone Assistant / UI-TARS).
 *
 * Provides:
 * 1. Visual Perception & Set-of-Marks (SoM):
 *    - `screen_view`: captures screen with optional SoM element tagging (numbered badges [1], [2]...)
 *      and returns both the visual image and a structured list of interactive elements.
 * 2. Precision Interaction:
 *    - `screen_touch`: executes actions targeting either by element ID (e.g. element=3) or
 *      normalized coordinates (x, y / point=[x, y] in 0~1000 scale).
 * 3. Complete Action Space:
 *    - click, double_click, long_press, swipe, scroll, type, press_key, wait, takeover (human-in-the-loop).
 *
 * Backends supported (in order of priority):
 * - Root (`su -c input ...` / `screencap`)
 * - Shizuku (`ShizukuManager.runProcess("input", ...)`)
 * - Accessibility (`MinisAccessibilityService.dispatchSimpleGesture` / `captureScreenshot`)
 */
object ScreenTool {

    private const val TAG = "ScreenTool"
    const val NAME = ToolNames.SCREEN

    private const val DEFAULT_MAX_EDGE = 1280
    private const val DEFAULT_JPEG_QUALITY = 80
    private const val DEFAULT_SETTLE_MS = 400L
    private const val DEFAULT_SWIPE_DURATION_MS = 300L

    data class DetectedElement(
        val id: Int,
        val text: String,
        val contentDesc: String,
        val className: String,
        val bounds: Rect,
        val centerNormalized: Pair<Int, Int>,
        val clickable: Boolean,
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("id", id)
            if (text.isNotEmpty()) put("text", text)
            if (contentDesc.isNotEmpty()) put("desc", contentDesc)
            put("center", JSONArray().put(centerNormalized.first).put(centerNormalized.second))
            put("bounds", JSONArray().put(bounds.left).put(bounds.top).put(bounds.right).put(bounds.bottom))
        }
    }

    private val lastPerceivedElements = ConcurrentHashMap<Int, DetectedElement>()

    fun viewDefinition(): AgentToolDefinition = AgentToolDefinition(
        name = ToolNames.SCREEN_VIEW,
        description = "Capture the physical Android screen for visual perception and element grounding (aligned with Doubao Phone Assistant / UI-TARS). " +
            "Returns the visual image directly to the multimodal model. " +
            "Supports Set-of-Marks (SoM): when `som: true` (default), interactive UI elements (buttons, inputs, tabs) are visually tagged with numbered badges [1], [2]... on the image, and a list of elements with their text and center coordinates is returned in JSON. " +
            "Set `som: false` to receive a clean, untagged screenshot for pure visual models like UI-TARS. " +
            "Supports Root, Shizuku, and AccessibilityService backends automatically.",
        parameters = mapOf(
            "tool_title" to AgentToolParam("string", "A concise 5-10 word summary of what this tool call does, shown to the user. Use the same language as the user."),
            "som" to AgentToolParam("boolean", "Whether to overlay Set-of-Marks (SoM) numbered badges on interactive elements. Default is true. Set false for pure coordinate models like UI-TARS."),
            "delay_ms" to AgentToolParam("integer", "Optional delay in milliseconds before taking screenshot to let animations or page loading settle (default: 0)."),
            "max_edge" to AgentToolParam("integer", "Maximum dimension in pixels for image scaling (default: 1280). Keeps visual detail within model context limits."),
            "quality" to AgentToolParam("integer", "JPEG compression quality from 10 to 100 (default: 80)."),
            "backend" to AgentToolParam("string", "Explicit backend to use ('auto', 'root', 'shizuku', 'a11y'). Default is 'auto'.", enumValues = listOf("auto", "root", "shizuku", "a11y")),
        ),
        required = listOf("tool_title"),
        propertyOrdering = listOf("tool_title", "som", "delay_ms", "max_edge", "quality", "backend"),
    )

    fun touchDefinition(): AgentToolDefinition = AgentToolDefinition(
        name = ToolNames.SCREEN_TOUCH,
        description = "Perform physical touch, swipe, keyboard typing, application launching, system navigation, or user takeover on the Android device (aligned with Doubao Phone Assistant / UI-TARS action space). " +
            "Targets can be specified by `app` (name or package to launch), `element` (ID from screen_view SoM, e.g. element=3) OR by normalized coordinates `x, y` (0~1000 scale) / `point=[x, y]`. " +
            "Supported actions: launch, click/tap, double_click, long_press, swipe, scroll, type, press_key, wait, takeover. " +
            "Set `capture_after: true` on any action to immediately capture and return the updated screen in the same turn for instant visual verification. " +
            "Action `takeover`: pauses execution and requests the user to manually handle sensitive operations (CAPTCHA / payment / biometric auth).",
        parameters = mapOf(
            "tool_title" to AgentToolParam("string", "A concise 5-10 word summary of what this tool call does, shown to the user. Use the same language as the user."),
            "action" to AgentToolParam("string", "What action to perform on the screen",
                enumValues = listOf("launch", "click", "tap", "double_click", "double_tap", "long_press", "swipe", "scroll", "type", "press_key", "key", "wait", "takeover")),
            "app" to AgentToolParam("string", "Application display name (e.g. '美团', '小红书', '微信') or Android package name (e.g. 'com.xingin.xhs') to launch for launch action."),
            "element" to AgentToolParam("integer", "Target element ID from screen_view SoM (e.g. 1, 2, 3). If provided, automatically taps the exact center of that element."),
            "x" to AgentToolParam("integer", "X coordinate (0~1000 normalized, where 0 is left edge and 1000 is right edge) for click, double_click, long_press, or swipe start."),
            "y" to AgentToolParam("integer", "Y coordinate (0~1000 normalized, where 0 is top edge and 1000 is bottom edge) for click, double_click, long_press, or swipe start."),
            "point" to AgentToolParam("string", "Alternative [x, y] coordinate string or array (e.g. '[500, 300]') output by UI-TARS / Doubao VLM."),
            "to_x" to AgentToolParam("integer", "Target X coordinate (0~1000 normalized) for swipe."),
            "to_y" to AgentToolParam("integer", "Target Y coordinate (0~1000 normalized) for swipe."),
            "direction" to AgentToolParam("string", "Scroll direction for scroll action", enumValues = listOf("up", "down", "left", "right")),
            "text" to AgentToolParam("string", "Text to type into field for type action. Can be used together with point=[x, y] or element to tap and focus before typing."),
            "clear" to AgentToolParam("boolean", "Optional. If true, clears existing text in the input field before typing."),
            "key" to AgentToolParam("string", "Key name for press_key action: 'back', 'home', 'recents', 'enter', 'delete', 'volume_up', 'volume_down', 'power' (or Android keycode integer)."),
            "duration_ms" to AgentToolParam("integer", "Duration in milliseconds for swipe, long_press, or wait action (default: 300 for swipe, 800 for long_press, 1000 for wait)."),
            "settle_ms" to AgentToolParam("integer", "Delay in milliseconds after touch action to let Android UI finish transition animations before returning (default: 400ms)."),
            "capture_after" to AgentToolParam("boolean", "If true, automatically captures the screen after executing the action (after settle delay) and returns the screenshot image in this turn."),
            "message" to AgentToolParam("string", "Prompt message for takeover action explaining why user assistance is needed (e.g. '请完成滑块验证码后在聊天中回复继续')."),
            "backend" to AgentToolParam("string", "Explicit backend to use ('auto', 'root', 'shizuku', 'a11y'). Default is 'auto'.", enumValues = listOf("auto", "root", "shizuku", "a11y")),
        ),
        required = listOf("tool_title", "action"),
        propertyOrdering = listOf("tool_title", "action", "app", "element", "x", "y", "point", "to_x", "to_y", "direction", "text", "clear", "key", "duration_ms", "settle_ms", "capture_after", "message", "backend"),
    )

    fun definition(): AgentToolDefinition = touchDefinition()

    fun executeView(argsJson: String, sessionId: String?, context: Context): ToolExecutionResult {
        val o = try {
            JSONObject(argsJson)
        } catch (e: Exception) {
            return ToolExecutionResult("Error: invalid JSON in screen_view arguments: ${e.message}", false)
        }

        val toolTitle = o.optString("tool_title", "Screen View")
        val delayMs = o.optLong("delay_ms", 0L).coerceIn(0L, 10_000L)
        val maxEdge = o.optInt("max_edge", DEFAULT_MAX_EDGE)
        val quality = o.optInt("quality", DEFAULT_JPEG_QUALITY)
        val backendPref = o.optString("backend", "auto").trim().lowercase()
        val som = o.optBoolean("som", true)

        if (delayMs > 0) sleepQuietly(delayMs)
        return executeCapture(context, sessionId, maxEdge, quality, backendPref, toolTitle, enableSom = som)
    }

    fun executeTouch(argsJson: String, sessionId: String?, context: Context): ToolExecutionResult {
        val o = try {
            JSONObject(argsJson)
        } catch (e: Exception) {
            return ToolExecutionResult("Error: invalid JSON in screen_touch arguments: ${e.message}", false)
        }

        val toolTitle = o.optString("tool_title", "Screen Touch")
        val action = o.optString("action", "").trim().lowercase()
        val backendPref = o.optString("backend", "auto").trim().lowercase()
        val settleMs = o.optLong("settle_ms", DEFAULT_SETTLE_MS).coerceIn(0L, 5000L)
        val captureAfter = o.optBoolean("capture_after", false)

        if (action.isEmpty()) {
            return ToolExecutionResult(
                output = JSONObject().put("ok", false).put("error", "action is required").toString(2),
                success = false,
                toolTitle = toolTitle,
            )
        }

        if (action == "capture" || action == "screenshot") {
            val maxEdge = o.optInt("max_edge", DEFAULT_MAX_EDGE)
            val quality = o.optInt("quality", DEFAULT_JPEG_QUALITY)
            val som = o.optBoolean("som", true)
            return executeCapture(context, sessionId, maxEdge, quality, backendPref, toolTitle, enableSom = som)
        }

        return dispatchTouchAction(o, action, toolTitle, backendPref, settleMs, captureAfter, sessionId, context)
    }

    fun execute(argsJson: String, sessionId: String?, context: Context): ToolExecutionResult {
        val o = try {
            JSONObject(argsJson)
        } catch (e: Exception) {
            return ToolExecutionResult("Error: invalid JSON in screen arguments: ${e.message}", false)
        }

        val action = o.optString("action", "").trim().lowercase()
        return if (action == "capture" || action == "screenshot" || (!o.has("action") && o.has("delay_ms"))) {
            executeView(argsJson, sessionId, context)
        } else {
            executeTouch(argsJson, sessionId, context)
        }
    }

    private fun dispatchTouchAction(
        o: JSONObject,
        action: String,
        toolTitle: String,
        backendPref: String,
        settleMs: Long,
        captureAfter: Boolean,
        sessionId: String?,
        context: Context,
    ): ToolExecutionResult {
        val (screenW, screenH) = getDisplayBounds(context)
        sessionId?.let { sid ->
            com.openminis.app.ime.ImeManager.acquire(context, sid)
            com.openminis.app.notification.HeadsUpNotificationManager.acquire(context, sid)
        }

        return when (action) {
            "launch", "open_app" -> {
                val appTarget = o.optString("app", "").ifEmpty {
                    o.optString("package", "")
                }.ifEmpty {
                    o.optString("text", "")
                }.trim()

                if (appTarget.isEmpty()) {
                    return ToolExecutionResult("Error: 'app' (application name or package) is required for launch action", false, toolTitle = toolTitle)
                }

                val actRes = performLaunch(appTarget, context, backendPref)
                if (!actRes.success) {
                    return ToolExecutionResult(actRes.toErrorJson().toString(2), false, toolTitle = toolTitle)
                }

                val waitSettle = settleMs.coerceAtLeast(1000L)
                if (waitSettle > 0) sleepQuietly(waitSettle)

                val details = JSONObject().apply {
                    put("app", appTarget)
                    put("resolved_package", actRes.resolvedPackage)
                    put("backend", actRes.backend)
                    put("settle_ms", waitSettle)
                }

                if (captureAfter) {
                    executeCapture(context, sessionId, DEFAULT_MAX_EDGE, DEFAULT_JPEG_QUALITY, backendPref, toolTitle, extraData = details)
                } else {
                    val out = JSONObject().put("ok", true).put("action", "launch").put("data", details)
                    ToolExecutionResult(out.toString(2), true, toolTitle = toolTitle)
                }
            }

            "click", "tap" -> {
                val (realX, realY, rawX, rawY) = resolveCoordsFromInput(o, screenW, screenH)
                    ?: return ToolExecutionResult("Error: target required (specify 'element', 'point', or 'x' and 'y')", false, toolTitle = toolTitle)

                val actRes = performTap(realX, realY, context, backendPref)
                if (!actRes.success) {
                    return ToolExecutionResult(actRes.toErrorJson().toString(2), false, toolTitle = toolTitle)
                }

                if (settleMs > 0) sleepQuietly(settleMs)

                if (captureAfter) {
                    executeCapture(context, sessionId, DEFAULT_MAX_EDGE, DEFAULT_JPEG_QUALITY, backendPref, toolTitle,
                        extraData = actRes.toSuccessJson(rawX, rawY, realX, realY, settleMs))
                } else {
                    val out = actRes.toSuccessJson(rawX, rawY, realX, realY, settleMs)
                    ToolExecutionResult(out.toString(2), true, toolTitle = toolTitle)
                }
            }

            "double_click", "double_tap" -> {
                val (realX, realY, rawX, rawY) = resolveCoordsFromInput(o, screenW, screenH)
                    ?: return ToolExecutionResult("Error: target required for double_click", false, toolTitle = toolTitle)

                val actRes = performDoubleTap(realX, realY, context, backendPref)
                if (!actRes.success) {
                    return ToolExecutionResult(actRes.toErrorJson().toString(2), false, toolTitle = toolTitle)
                }

                if (settleMs > 0) sleepQuietly(settleMs)

                if (captureAfter) {
                    executeCapture(context, sessionId, DEFAULT_MAX_EDGE, DEFAULT_JPEG_QUALITY, backendPref, toolTitle,
                        extraData = actRes.toSuccessJson(rawX, rawY, realX, realY, settleMs))
                } else {
                    val out = actRes.toSuccessJson(rawX, rawY, realX, realY, settleMs)
                    ToolExecutionResult(out.toString(2), true, toolTitle = toolTitle)
                }
            }

            "long_press" -> {
                val (realX, realY, rawX, rawY) = resolveCoordsFromInput(o, screenW, screenH)
                    ?: return ToolExecutionResult("Error: target required for long_press", false, toolTitle = toolTitle)

                val durationMs = o.optLong("duration_ms", 800L).coerceIn(200L, 5000L)
                val actRes = performLongPress(realX, realY, durationMs, context, backendPref)
                if (!actRes.success) {
                    return ToolExecutionResult(actRes.toErrorJson().toString(2), false, toolTitle = toolTitle)
                }

                if (settleMs > 0) sleepQuietly(settleMs)

                if (captureAfter) {
                    executeCapture(context, sessionId, DEFAULT_MAX_EDGE, DEFAULT_JPEG_QUALITY, backendPref, toolTitle,
                        extraData = actRes.toSuccessJson(rawX, rawY, realX, realY, settleMs))
                } else {
                    val out = actRes.toSuccessJson(rawX, rawY, realX, realY, settleMs)
                    ToolExecutionResult(out.toString(2), true, toolTitle = toolTitle)
                }
            }

            "swipe", "drag" -> {
                val startCoords = resolveCoordsFromInput(o, screenW, screenH)
                val rawToX = if (o.has("to_x")) o.getInt("to_x") else null
                val rawToY = if (o.has("to_y")) o.getInt("to_y") else null

                val targetCoords = if (rawToX != null && rawToY != null) {
                    val (rx, ry) = normalizeCoords(rawToX, rawToY, screenW, screenH)
                    QuadCoords(rx, ry, rawToX, rawToY)
                } else null

                if (startCoords == null || targetCoords == null) {
                    return ToolExecutionResult("Error: swipe requires start coords ('x', 'y' or 'point') and target ('to_x', 'to_y')", false, toolTitle = toolTitle)
                }

                val durationMs = o.optLong("duration_ms", DEFAULT_SWIPE_DURATION_MS).coerceIn(50L, 5000L)
                val actRes = performSwipe(startCoords.realX, startCoords.realY, targetCoords.realX, targetCoords.realY, durationMs, context, backendPref)
                if (!actRes.success) {
                    return ToolExecutionResult(actRes.toErrorJson().toString(2), false, toolTitle = toolTitle)
                }

                if (settleMs > 0) sleepQuietly(settleMs)

                val details = JSONObject().apply {
                    put("from", JSONObject().put("x", startCoords.normX).put("y", startCoords.normY).put("real_x", startCoords.realX).put("real_y", startCoords.realY))
                    put("to", JSONObject().put("x", targetCoords.normX).put("y", targetCoords.normY).put("real_x", targetCoords.realX).put("real_y", targetCoords.realY))
                    put("duration_ms", durationMs)
                    put("backend", actRes.backend)
                    put("settle_ms", settleMs)
                }

                if (captureAfter) {
                    executeCapture(context, sessionId, DEFAULT_MAX_EDGE, DEFAULT_JPEG_QUALITY, backendPref, toolTitle, extraData = details)
                } else {
                    val out = JSONObject().put("ok", true).put("action", "swipe").put("data", details)
                    ToolExecutionResult(out.toString(2), true, toolTitle = toolTitle)
                }
            }

            "scroll" -> {
                val direction = o.optString("direction", "down").lowercase()
                val durationMs = o.optLong("duration_ms", DEFAULT_SWIPE_DURATION_MS).coerceIn(50L, 5000L)
                val (coords, actRes) = performScroll(direction, durationMs, screenW, screenH, context, backendPref)
                if (!actRes.success) {
                    return ToolExecutionResult(actRes.toErrorJson().toString(2), false, toolTitle = toolTitle)
                }

                if (settleMs > 0) sleepQuietly(settleMs)

                val details = JSONObject().apply {
                    put("direction", direction)
                    put("coords", coords)
                    put("duration_ms", durationMs)
                    put("backend", actRes.backend)
                    put("settle_ms", settleMs)
                }

                if (captureAfter) {
                    executeCapture(context, sessionId, DEFAULT_MAX_EDGE, DEFAULT_JPEG_QUALITY, backendPref, toolTitle, extraData = details)
                } else {
                    val out = JSONObject().put("ok", true).put("action", "scroll").put("data", details)
                    ToolExecutionResult(out.toString(2), true, toolTitle = toolTitle)
                }
            }

            "type" -> {
                val text = o.optString("text", "")
                if (text.isEmpty()) {
                    return ToolExecutionResult("Error: 'text' is required for type action", false, toolTitle = toolTitle)
                }

                val clear = o.optBoolean("clear", false)

                // If element or coordinates were passed, tap it first to ensure focus
                val targetCoords = resolveCoordsFromInput(o, screenW, screenH)
                if (targetCoords != null) {
                    performTap(targetCoords.realX, targetCoords.realY, context, backendPref)
                    sleepQuietly(400) // 400ms to allow view focus & soft keyboard initialization
                }

                val actRes = performType(text, context, backendPref, targetCoords, clearFirst = clear)
                if (!actRes.success) {
                    return ToolExecutionResult(actRes.toErrorJson().toString(2), false, toolTitle = toolTitle)
                }

                if (settleMs > 0) sleepQuietly(settleMs)

                val details = JSONObject().apply {
                    put("text", text)
                    put("backend", actRes.backend)
                    put("settle_ms", settleMs)
                    if (targetCoords != null) {
                        put("tapped_point", JSONArray().put(targetCoords.normX).put(targetCoords.normY))
                    } else {
                        put("hint", "Typed into currently focused field. If target field was not focused, provide point=[x, y] to focus before typing.")
                    }
                }

                if (captureAfter) {
                    executeCapture(context, sessionId, DEFAULT_MAX_EDGE, DEFAULT_JPEG_QUALITY, backendPref, toolTitle, extraData = details)
                } else {
                    val out = JSONObject().put("ok", true).put("action", "type").put("data", details)
                    ToolExecutionResult(out.toString(2), true, toolTitle = toolTitle)
                }
            }

            "press_key", "key" -> {
                val key = o.optString("key", "")
                if (key.isEmpty()) {
                    return ToolExecutionResult("Error: 'key' is required for key action", false, toolTitle = toolTitle)
                }
                val actRes = performKey(key, context, backendPref)
                if (!actRes.success) {
                    return ToolExecutionResult(actRes.toErrorJson().toString(2), false, toolTitle = toolTitle)
                }

                if (settleMs > 0) sleepQuietly(settleMs)

                val details = JSONObject().apply {
                    put("key", key)
                    put("backend", actRes.backend)
                    put("settle_ms", settleMs)
                }

                if (captureAfter) {
                    executeCapture(context, sessionId, DEFAULT_MAX_EDGE, DEFAULT_JPEG_QUALITY, backendPref, toolTitle, extraData = details)
                } else {
                    val out = JSONObject().put("ok", true).put("action", "key").put("data", details)
                    ToolExecutionResult(out.toString(2), true, toolTitle = toolTitle)
                }
            }

            "wait" -> {
                val durationMs = o.optLong("duration_ms", 1000L).coerceIn(50L, 10_000L)
                sleepQuietly(durationMs)
                val out = JSONObject().put("ok", true).put("action", "wait").put("duration_ms", durationMs)
                if (captureAfter) {
                    executeCapture(context, sessionId, DEFAULT_MAX_EDGE, DEFAULT_JPEG_QUALITY, backendPref, toolTitle, extraData = out)
                } else {
                    ToolExecutionResult(out.toString(2), true, toolTitle = toolTitle)
                }
            }

            "takeover", "call_user" -> {
                val message = o.optString("message", "请在手机上完成敏感操作（如滑块验证码、指纹或支付密码），完成后回复继续。")
                val out = JSONObject().apply {
                    put("ok", true)
                    put("action", "takeover")
                    put("status", "waiting_user_takeover")
                    put("message", message)
                }
                ToolExecutionResult(out.toString(2), true, toolTitle = toolTitle)
            }

            else -> {
                ToolExecutionResult(
                    output = "Error: unknown action '$action'. Known actions: launch, click, double_click, long_press, swipe, scroll, type, press_key, wait, takeover",
                    success = false,
                    toolTitle = toolTitle,
                )
            }
        }
    }

    private data class QuadCoords(val realX: Int, val realY: Int, val normX: Int, val normY: Int)

    private fun resolveCoordsFromInput(o: JSONObject, screenW: Int, screenH: Int): QuadCoords? {
        if (o.has("element")) {
            val elemId = o.getInt("element")
            val elem = lastPerceivedElements[elemId] ?: return null
            val rx = elem.bounds.centerX().coerceIn(0, screenW)
            val ry = elem.bounds.centerY().coerceIn(0, screenH)
            return QuadCoords(rx, ry, elem.centerNormalized.first, elem.centerNormalized.second)
        }

        if (o.has("point")) {
            val pt = parsePoint(o.opt("point"))
            if (pt != null) {
                val (rx, ry) = normalizeCoords(pt.first, pt.second, screenW, screenH)
                return QuadCoords(rx, ry, pt.first, pt.second)
            }
        }

        val rawX = if (o.has("x")) o.getInt("x") else null
        val rawY = if (o.has("y")) o.getInt("y") else null
        if (rawX != null && rawY != null) {
            val (rx, ry) = normalizeCoords(rawX, rawY, screenW, screenH)
            return QuadCoords(rx, ry, rawX, rawY)
        }

        return null
    }

    fun parsePoint(pointVal: Any?): Pair<Int, Int>? {
        if (pointVal == null) return null
        if (pointVal is JSONArray && pointVal.length() >= 2) {
            return Pair(pointVal.getInt(0), pointVal.getInt(1))
        }
        val str = pointVal.toString().trim()
        val match = Regex("(\\d+)\\s*[,\\s]\\s*(\\d+)").find(str) ?: return null
        val x = match.groupValues[1].toIntOrNull() ?: return null
        val y = match.groupValues[2].toIntOrNull() ?: return null
        return Pair(x, y)
    }

    // ── Screen Dimension & Coordinates ────────────────────────────────────

    fun getDisplayBounds(context: Context): Pair<Int, Int> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val wm = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
            if (wm != null) {
                val bounds = wm.currentWindowMetrics.bounds
                return Pair(bounds.width(), bounds.height())
            }
        }
        val dm = context.resources.displayMetrics
        return Pair(dm.widthPixels, dm.heightPixels)
    }

    fun normalizeCoords(x: Int, y: Int, screenW: Int, screenH: Int): Pair<Int, Int> {
        val realX = if (x in 0..1000) {
            (x.toFloat() / 1000f * screenW).roundToInt().coerceIn(0, screenW)
        } else {
            x.coerceIn(0, screenW)
        }
        val realY = if (y in 0..1000) {
            (y.toFloat() / 1000f * screenH).roundToInt().coerceIn(0, screenH)
        } else {
            y.coerceIn(0, screenH)
        }
        return Pair(realX, realY)
    }

    // ── Visual Grounding & SoM (Set-of-Marks) ─────────────────────────────

    fun detectInteractiveElements(context: Context, screenW: Int, screenH: Int): List<DetectedElement> {
        val a11y = MinisAccessibilityService.getInstance() ?: return emptyList()
        val roots = a11y.rootNodes()
        if (roots.isEmpty()) return emptyList()

        val rawElements = mutableListOf<DetectedElement>()
        val seenRects = mutableSetOf<String>()

        fun walk(node: AccessibilityNodeInfo?, depth: Int) {
            if (node == null || depth > 20 || rawElements.size >= 60) return
            try {
                if (!node.isVisibleToUser) return
                val rect = Rect()
                node.getBoundsInScreen(rect)

                if (rect.width() > 10 && rect.height() > 10 &&
                    rect.left < screenW && rect.top < screenH && rect.right > 0 && rect.bottom > 0 &&
                    !(rect.width() >= screenW && rect.height() >= screenH)
                ) {
                    val isInteractive = node.isClickable || node.isLongClickable || node.isCheckable || node.isEditable
                    val text = node.text?.toString()?.trim() ?: ""
                    val desc = node.contentDescription?.toString()?.trim() ?: ""

                    if (isInteractive || text.isNotEmpty() || desc.isNotEmpty()) {
                        val key = "${rect.left},${rect.top},${rect.right},${rect.bottom}"
                        if (seenRects.add(key)) {
                            val normX = (rect.centerX().toFloat() / screenW * 1000f).roundToInt().coerceIn(0, 1000)
                            val normY = (rect.centerY().toFloat() / screenH * 1000f).roundToInt().coerceIn(0, 1000)
                            rawElements.add(
                                DetectedElement(
                                    id = 0,
                                    text = text,
                                    contentDesc = desc,
                                    className = node.className?.toString() ?: "",
                                    bounds = rect,
                                    centerNormalized = Pair(normX, normY),
                                    clickable = isInteractive,
                                )
                            )
                        }
                    }
                }

                for (i in 0 until node.childCount) {
                    walk(node.getChild(i), depth + 1)
                }
            } catch (e: Exception) {
                Log.w(TAG, "error walking a11y node", e)
            }
        }

        for (root in roots) {
            walk(root, 0)
        }

        rawElements.sortWith(compareBy({ it.bounds.top / 40 }, { it.bounds.left }))

        return rawElements.mapIndexed { idx, elem ->
            elem.copy(id = idx + 1)
        }
    }

    fun overlaySetOfMarks(
        bitmap: Bitmap,
        elements: List<DetectedElement>,
        screenW: Int,
        screenH: Int,
    ): Bitmap {
        if (elements.isEmpty()) return bitmap

        val scaleX = bitmap.width.toFloat() / screenW
        val scaleY = bitmap.height.toFloat() / screenH

        val mutableBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, true) ?: return bitmap
        val canvas = Canvas(mutableBitmap)

        val boxPaint = Paint().apply {
            style = Paint.Style.STROKE
            strokeWidth = 3f
            color = Color.argb(220, 255, 45, 85)
        }

        val badgeBgPaint = Paint().apply {
            style = Paint.Style.FILL
            color = Color.argb(230, 20, 20, 20)
        }

        val textPaint = Paint().apply {
            style = Paint.Style.FILL
            color = Color.WHITE
            textSize = 22f
            isFakeBoldText = true
            textAlign = Paint.Align.CENTER
        }

        for (elem in elements) {
            val left = elem.bounds.left * scaleX
            val top = elem.bounds.top * scaleY
            val right = elem.bounds.right * scaleX
            val bottom = elem.bounds.bottom * scaleY

            canvas.drawRect(left, top, right, bottom, boxPaint)

            val idText = elem.id.toString()
            val badgeWidth = (idText.length * 14f + 16f).coerceAtLeast(32f)
            val badgeHeight = 24f

            val badgeLeft = left.coerceIn(0f, (bitmap.width - badgeWidth))
            val badgeTop = (top - badgeHeight).coerceAtLeast(0f)
            val badgeRect = RectF(badgeLeft, badgeTop, badgeLeft + badgeWidth, badgeTop + badgeHeight)

            canvas.drawRoundRect(badgeRect, 6f, 6f, badgeBgPaint)
            canvas.drawText(idText, badgeRect.centerX(), badgeRect.centerY() + 7f, textPaint)
        }

        return mutableBitmap
    }

    // ── Screenshot Capture ────────────────────────────────────────────────

    data class ScreenCaptureResult(
        val bitmap: Bitmap?,
        val backend: String,
        val errorMessage: String? = null,
    )

    private fun executeCapture(
        context: Context,
        sessionId: String?,
        maxEdge: Int,
        quality: Int,
        backendPref: String,
        toolTitle: String,
        extraData: JSONObject? = null,
        enableSom: Boolean = true,
    ): ToolExecutionResult {
        val capResult = captureRawBitmap(context, backendPref)
        val rawBitmap = capResult.bitmap
            ?: return ToolExecutionResult(
                output = JSONObject().apply {
                    put("ok", false)
                    put("error", JSONObject().apply {
                        put("code", "CAPTURE_FAILED")
                        put("message", capResult.errorMessage ?: "Failed to capture screen with any backend")
                    })
                }.toString(2),
                success = false,
                toolTitle = toolTitle,
            )

        val origW = rawBitmap.width
        val origH = rawBitmap.height
        val (screenW, screenH) = getDisplayBounds(context)
        val baseW = if (screenW > 0) screenW else origW
        val baseH = if (screenH > 0) screenH else origH

        // Visual Grounding: detect interactive elements via Accessibility
        val detected = detectInteractiveElements(context, baseW, baseH)
        lastPerceivedElements.clear()
        detected.forEach { lastPerceivedElements[it.id] = it }

        // Overlay Set-of-Marks if requested and elements found
        val markedBitmap = if (enableSom && detected.isNotEmpty()) {
            val overlaid = overlaySetOfMarks(rawBitmap, detected, baseW, baseH)
            if (overlaid !== rawBitmap) rawBitmap.recycle()
            overlaid
        } else {
            rawBitmap
        }

        // Scale to token budget
        val scaledBitmap = if (markedBitmap.width > maxEdge || markedBitmap.height > maxEdge) {
            val scale = maxEdge.toFloat() / maxOf(markedBitmap.width, markedBitmap.height)
            val sw = (markedBitmap.width * scale).roundToInt().coerceAtLeast(1)
            val sh = (markedBitmap.height * scale).roundToInt().coerceAtLeast(1)
            Bitmap.createScaledBitmap(markedBitmap, sw, sh, true).also {
                if (it !== markedBitmap) markedBitmap.recycle()
            }
        } else {
            markedBitmap
        }

        val baos = ByteArrayOutputStream()
        scaledBitmap.compress(Bitmap.CompressFormat.JPEG, quality.coerceIn(10, 100), baos)
        val jpegBytes = baos.toByteArray()
        val scaledW = scaledBitmap.width
        val scaledH = scaledBitmap.height
        scaledBitmap.recycle()

        // Persist to session attachment directory
        val attachmentsHostDir: File? = sessionId?.let {
            PRootKernel.resolveSessionHostPath(it, "/var/minis/attachments", context)
        } ?: PRootKernel.resolveHostPath("/var/minis/attachments")
          ?: File(context.filesDir, "attachments")

        val shotDir = File(attachmentsHostDir, "screenshots").apply { mkdirs() }
        val filename = "screen_${System.currentTimeMillis()}.jpg"
        val hostFile = File(shotDir, filename)
        try {
            hostFile.writeBytes(jpegBytes)
        } catch (e: Exception) {
            Log.w(TAG, "failed to persist screenshot to ${hostFile.absolutePath}", e)
        }

        val linuxPath = "/var/minis/attachments/screenshots/$filename"
        val minisUrl = "minis://attachments/screenshots/$filename"

        val elementsJson = JSONArray()
        detected.forEach { elementsJson.put(it.toJson()) }

        val json = JSONObject().apply {
            put("ok", true)
            put("action", "capture")
            put("som_enabled", enableSom && detected.isNotEmpty())
            put("elements_count", detected.size)
            if (detected.isNotEmpty()) put("elements", elementsJson)
            put("display", JSONObject().apply {
                put("width", origW)
                put("height", origH)
                put("scaled_width", scaledW)
                put("scaled_height", scaledH)
            })
            put("backend", capResult.backend)
            put("path", linuxPath)
            put("minis_url", minisUrl)
            put("size_bytes", jpegBytes.size)
            if (extraData != null) put("action_result", extraData)
        }

        return ToolExecutionResult(
            output = json.toString(2),
            success = true,
            imageData = jpegBytes,
            imageMimeType = "image/jpeg",
            toolTitle = toolTitle,
            imageFilePath = hostFile.absolutePath,
            imageLinuxPath = linuxPath,
        )
    }

    private fun captureRawBitmap(context: Context, backendPref: String): ScreenCaptureResult {
        val backendsToTry = when (backendPref) {
            "a11y" -> listOf("a11y")
            "root" -> listOf("root")
            "shizuku" -> listOf("shizuku")
            else -> listOf("a11y", "root", "shizuku")
        }

        val errors = mutableListOf<String>()

        for (backend in backendsToTry) {
            when (backend) {
                "a11y" -> {
                    val a11y = MinisAccessibilityService.getInstance()
                    if (a11y != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        val shot = a11y.captureScreenshot()
                        if (shot.bitmap != null) {
                            return ScreenCaptureResult(shot.bitmap, "a11y")
                        } else {
                            errors.add("a11y: ${shot.errorMessage ?: "takeScreenshot failed"}")
                        }
                    } else {
                        errors.add("a11y not running or API < 30")
                    }
                }
                "root" -> {
                    if (RootAccess.isRootGranted()) {
                        val tempFile = File(context.cacheDir, "screencap_${System.currentTimeMillis()}.png")
                        val suRes = RootAccess.runSu("screencap -p '${tempFile.absolutePath}' && chmod 666 '${tempFile.absolutePath}'", 8000L)
                        if (tempFile.exists() && tempFile.length() > 0) {
                            val bmp = BitmapFactory.decodeFile(tempFile.absolutePath)
                            tempFile.delete()
                            if (bmp != null) return ScreenCaptureResult(bmp, "root")
                            errors.add("root: decode screencap failed")
                        } else {
                            tempFile.delete()
                            errors.add("root: screencap exited with ${suRes?.exitCode}: ${suRes?.stderr}")
                        }
                    } else {
                        errors.add("root not granted")
                    }
                }
                "shizuku" -> {
                    if (ShizukuManager.isReady()) {
                        val extDir = context.getExternalFilesDir("screenshots") ?: context.cacheDir
                        val tempFile = File(extDir, "screencap_shizuku_${System.currentTimeMillis()}.png")
                        val pRes = ShizukuManager.runProcess(arrayOf("screencap", "-p", tempFile.absolutePath), timeoutMs = 8000L)
                        if (tempFile.exists() && tempFile.length() > 0) {
                            val bmp = BitmapFactory.decodeFile(tempFile.absolutePath)
                            tempFile.delete()
                            if (bmp != null) return ScreenCaptureResult(bmp, "shizuku")
                            errors.add("shizuku: decode screencap failed")
                        } else {
                            tempFile.delete()
                            errors.add("shizuku: screencap exited with ${pRes.exitCode}: ${pRes.stderr}")
                        }
                    } else {
                        errors.add("shizuku not ready")
                    }
                }
            }
        }

        return ScreenCaptureResult(
            bitmap = null,
            backend = "none",
            errorMessage = "No capture backend available (${errors.joinToString("; ")}). " +
                "Enable Minis in Settings → Accessibility, grant Shizuku, or grant Root.",
        )
    }

    // ── Input Actions ─────────────────────────────────────────────────────

    data class ScreenActionResult(
        val success: Boolean,
        val backend: String,
        val errorMessage: String? = null,
        // `performLaunch` resolves an app name to a package before dispatching.
        // That package is a result, not an error, so it gets its own slot rather
        // than riding in errorMessage where toErrorJson would report it as a failure.
        val resolvedPackage: String? = null,
    ) {
        fun toErrorJson(): JSONObject = JSONObject().apply {
            put("ok", false)
            put("error", JSONObject().put("code", "ACTION_FAILED").put("message", errorMessage ?: "Action failed"))
            put("backend", backend)
        }

        fun toSuccessJson(rawX: Int, rawY: Int, realX: Int, realY: Int, settleMs: Long): JSONObject = JSONObject().apply {
            put("ok", true)
            put("coords", JSONObject().put("x", rawX).put("y", rawY).put("real_x", realX).put("real_y", realY))
            put("backend", backend)
            put("settle_ms", settleMs)
        }
    }

    private fun performTap(realX: Int, realY: Int, context: Context, backendPref: String): ScreenActionResult {
        val backends = resolveActionBackends(backendPref)
        for (b in backends) {
            when (b) {
                "root" -> if (RootAccess.isRootGranted()) {
                    val res = RootAccess.runSu("input tap $realX $realY", 5000L)
                    if (res?.exitCode == 0) return ScreenActionResult(true, "root")
                }
                "shizuku" -> if (ShizukuManager.isReady()) {
                    val res = ShizukuManager.runProcess(arrayOf("input", "tap", realX.toString(), realY.toString()))
                    if (res.exitCode == 0) return ScreenActionResult(true, "shizuku")
                }
                "a11y" -> {
                    val a11y = MinisAccessibilityService.getInstance()
                    if (a11y != null) {
                        val path = Path().apply { moveTo(realX.toFloat(), realY.toFloat()) }
                        if (a11y.dispatchSimpleGesture(path, 0, 80)) return ScreenActionResult(true, "a11y")
                    }
                }
            }
        }
        return ScreenActionResult(false, "none", "No touch backend available or execution failed")
    }

    private fun performDoubleTap(realX: Int, realY: Int, context: Context, backendPref: String): ScreenActionResult {
        val backends = resolveActionBackends(backendPref)
        for (b in backends) {
            when (b) {
                "root" -> if (RootAccess.isRootGranted()) {
                    val res = RootAccess.runSu("input tap $realX $realY && sleep 0.1 && input tap $realX $realY", 5000L)
                    if (res?.exitCode == 0) return ScreenActionResult(true, "root")
                }
                "shizuku" -> if (ShizukuManager.isReady()) {
                    val r1 = ShizukuManager.runProcess(arrayOf("input", "tap", realX.toString(), realY.toString()))
                    sleepQuietly(100)
                    val r2 = ShizukuManager.runProcess(arrayOf("input", "tap", realX.toString(), realY.toString()))
                    if (r1.exitCode == 0 && r2.exitCode == 0) return ScreenActionResult(true, "shizuku")
                }
                "a11y" -> {
                    val a11y = MinisAccessibilityService.getInstance()
                    if (a11y != null) {
                        val path = Path().apply { moveTo(realX.toFloat(), realY.toFloat()) }
                        val r1 = a11y.dispatchSimpleGesture(path, 0, 60)
                        sleepQuietly(80)
                        val r2 = a11y.dispatchSimpleGesture(path, 0, 60)
                        if (r1 && r2) return ScreenActionResult(true, "a11y")
                    }
                }
            }
        }
        return ScreenActionResult(false, "none", "No touch backend available for double tap")
    }

    private fun performLongPress(realX: Int, realY: Int, durationMs: Long, context: Context, backendPref: String): ScreenActionResult {
        val backends = resolveActionBackends(backendPref)
        for (b in backends) {
            when (b) {
                "root" -> if (RootAccess.isRootGranted()) {
                    val res = RootAccess.runSu("input swipe $realX $realY $realX $realY $durationMs", durationMs + 4000L)
                    if (res?.exitCode == 0) return ScreenActionResult(true, "root")
                }
                "shizuku" -> if (ShizukuManager.isReady()) {
                    val res = ShizukuManager.runProcess(arrayOf("input", "swipe", realX.toString(), realY.toString(), realX.toString(), realY.toString(), durationMs.toString()))
                    if (res.exitCode == 0) return ScreenActionResult(true, "shizuku")
                }
                "a11y" -> {
                    val a11y = MinisAccessibilityService.getInstance()
                    if (a11y != null) {
                        val path = Path().apply { moveTo(realX.toFloat(), realY.toFloat()) }
                        if (a11y.dispatchSimpleGesture(path, 0, durationMs)) return ScreenActionResult(true, "a11y")
                    }
                }
            }
        }
        return ScreenActionResult(false, "none", "No touch backend available for long press")
    }

    private fun performSwipe(startX: Int, startY: Int, endX: Int, endY: Int, durationMs: Long, context: Context, backendPref: String): ScreenActionResult {
        val backends = resolveActionBackends(backendPref)
        for (b in backends) {
            when (b) {
                "root" -> if (RootAccess.isRootGranted()) {
                    val res = RootAccess.runSu("input swipe $startX $startY $endX $endY $durationMs", durationMs + 4000L)
                    if (res?.exitCode == 0) return ScreenActionResult(true, "root")
                }
                "shizuku" -> if (ShizukuManager.isReady()) {
                    val res = ShizukuManager.runProcess(arrayOf("input", "swipe", startX.toString(), startY.toString(), endX.toString(), endY.toString(), durationMs.toString()))
                    if (res.exitCode == 0) return ScreenActionResult(true, "shizuku")
                }
                "a11y" -> {
                    val a11y = MinisAccessibilityService.getInstance()
                    if (a11y != null) {
                        val path = Path().apply { moveTo(startX.toFloat(), startY.toFloat()); lineTo(endX.toFloat(), endY.toFloat()) }
                        if (a11y.dispatchSimpleGesture(path, 0, durationMs)) return ScreenActionResult(true, "a11y")
                    }
                }
            }
        }
        return ScreenActionResult(false, "none", "No touch backend available for swipe")
    }

    private fun performScroll(
        direction: String,
        durationMs: Long,
        screenW: Int,
        screenH: Int,
        context: Context,
        backendPref: String,
    ): Pair<JSONObject, ScreenActionResult> {
        val (startX, startY, endX, endY) = when (direction) {
            "up" -> arrayOf(
                (screenW * 0.5f).roundToInt(),
                (screenH * 0.3f).roundToInt(),
                (screenW * 0.5f).roundToInt(),
                (screenH * 0.75f).roundToInt(),
            )
            "left" -> arrayOf(
                (screenW * 0.8f).roundToInt(),
                (screenH * 0.5f).roundToInt(),
                (screenW * 0.2f).roundToInt(),
                (screenH * 0.5f).roundToInt(),
            )
            "right" -> arrayOf(
                (screenW * 0.2f).roundToInt(),
                (screenH * 0.5f).roundToInt(),
                (screenW * 0.8f).roundToInt(),
                (screenH * 0.5f).roundToInt(),
            )
            else -> arrayOf(
                (screenW * 0.5f).roundToInt(),
                (screenH * 0.75f).roundToInt(),
                (screenW * 0.5f).roundToInt(),
                (screenH * 0.3f).roundToInt(),
            )
        }

        val coordsJson = JSONObject().apply {
            put("from_x", startX); put("from_y", startY)
            put("to_x", endX); put("to_y", endY)
        }

        val res = performSwipe(startX, startY, endX, endY, durationMs, context, backendPref)
        return Pair(coordsJson, res)
    }

    fun setSystemClipboard(context: Context, text: String): Boolean {
        return try {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                ?: return false
            val latch = CountDownLatch(1)
            var success = false
            Handler(Looper.getMainLooper()).post {
                try {
                    val clip = ClipData.newPlainText("screen_touch", text)
                    cm.setPrimaryClip(clip)
                    success = true
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to setPrimaryClip: ${e.message}", e)
                } finally {
                    latch.countDown()
                }
            }
            latch.await(1500, TimeUnit.MILLISECONDS)
            success
        } catch (e: Exception) {
            Log.w(TAG, "setSystemClipboard error", e)
            false
        }
    }

    fun isPureAscii(text: String): Boolean {
        for (ch in text) {
            if (ch.code > 127) return false
        }
        return true
    }

    private fun findEditableNode(a11y: MinisAccessibilityService, targetCoords: QuadCoords?): AccessibilityNodeInfo? {
        val roots = a11y.rootNodes()
        if (roots.isEmpty()) return null

        // 1. If target coordinates are provided, find node under coordinates
        if (targetCoords != null) {
            for (root in roots) {
                val nodeAtPoint = findNodeAtPoint(root, targetCoords.realX, targetCoords.realY)
                if (nodeAtPoint != null) {
                    val editable = findNearestEditable(nodeAtPoint)
                    if (editable != null) return editable
                }
            }
        }

        // 2. Check input focus
        for (root in roots) {
            val focusedInput = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            if (focusedInput != null && focusedInput.isEditable) return focusedInput
            val focusedA11y = root.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)
            if (focusedA11y != null && focusedA11y.isEditable) return focusedA11y
        }

        // 3. Search tree for isFocused == true && isEditable == true
        for (root in roots) {
            val focused = findNodeMatching(root) { it.isFocused && it.isEditable }
            if (focused != null) return focused
        }

        // 4. Search tree for any isEditable == true node
        for (root in roots) {
            val editable = findNodeMatching(root) { it.isEditable }
            if (editable != null) return editable
        }

        return null
    }

    private fun findNodeAtPoint(root: AccessibilityNodeInfo, x: Int, y: Int): AccessibilityNodeInfo? {
        val rect = Rect()
        root.getBoundsInScreen(rect)
        if (!rect.contains(x, y)) return null
        for (i in 0 until root.childCount) {
            val child = root.getChild(i) ?: continue
            val found = findNodeAtPoint(child, x, y)
            if (found != null) return found
        }
        return root
    }

    private fun findNearestEditable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var curr: AccessibilityNodeInfo? = node
        while (curr != null) {
            if (curr.isEditable) return curr
            curr = curr.parent
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            if (child.isEditable) return child
        }
        return null
    }

    private fun findNodeMatching(root: AccessibilityNodeInfo, predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        if (predicate(root)) return root
        for (i in 0 until root.childCount) {
            val child = root.getChild(i) ?: continue
            val found = findNodeMatching(child, predicate)
            if (found != null) return found
        }
        return null
    }

    private fun performType(
        text: String,
        context: Context,
        backendPref: String,
        targetCoords: QuadCoords? = null,
        clearFirst: Boolean = false,
    ): ScreenActionResult {
        // Strategy 0: If MinisKeyboardService is active and connected, inject directly via InputConnection
        if (com.openminis.app.ime.MinisKeyboardService.isConnected) {
            if (clearFirst) {
                com.openminis.app.ime.MinisKeyboardService.clearText()
                sleepQuietly(50)
            }
            if (com.openminis.app.ime.MinisKeyboardService.injectText(text)) {
                return ScreenActionResult(true, "minis_ime")
            }
        }

        val backends = resolveActionBackends(backendPref)

        // 1. Preload clipboard with exact text (supports UTF-8 Chinese, emojis, symbols, newlines)
        val clipSet = setSystemClipboard(context, text)

        for (b in backends) {
            when (b) {
                "root" -> if (RootAccess.isRootGranted()) {
                    if (clearFirst) {
                        RootAccess.runSu("input keyevent 29 31", 3000L) // Ctrl+A
                        RootAccess.runSu("input keyevent 67", 3000L)    // DEL
                        sleepQuietly(100)
                    }

                    // Primary Strategy: KEYCODE_PASTE (279) inserts directly from clipboard, bypassing IME pinyin composition
                    if (clipSet) {
                        val pasteRes = RootAccess.runSu("input keyevent 279", 5000L)
                        if (pasteRes?.exitCode == 0) return ScreenActionResult(true, "root")
                    }

                    // Secondary Strategy: Ctrl+V (29 + 50)
                    if (clipSet) {
                        val ctrlV = RootAccess.runSu("input keyevent --longpress 29 50", 5000L)
                        if (ctrlV?.exitCode == 0) return ScreenActionResult(true, "root")
                    }

                    // Fallback Strategy: input text for pure-ASCII strings only if clipboard paste failed
                    if (isPureAscii(text)) {
                        val escaped = escapeForInputText(text)
                        val res = RootAccess.runSu("input text $escaped", 5000L)
                        if (res?.exitCode == 0) return ScreenActionResult(true, "root")
                    }
                }
                "shizuku" -> if (ShizukuManager.isReady()) {
                    if (clearFirst) {
                        ShizukuManager.runProcess(arrayOf("input", "keyevent", "29", "31"))
                        ShizukuManager.runProcess(arrayOf("input", "keyevent", "67"))
                        sleepQuietly(100)
                    }

                    if (clipSet) {
                        val pasteRes = ShizukuManager.runProcess(arrayOf("input", "keyevent", "279"))
                        if (pasteRes.exitCode == 0) return ScreenActionResult(true, "shizuku")
                    }

                    if (isPureAscii(text)) {
                        val escaped = escapeForInputText(text)
                        val res = ShizukuManager.runProcess(arrayOf("input", "text", escaped))
                        if (res.exitCode == 0) return ScreenActionResult(true, "shizuku")
                    }
                }
                "a11y" -> {
                    val a11y = MinisAccessibilityService.getInstance()
                    if (a11y != null) {
                        val node = findEditableNode(a11y, targetCoords)
                        if (node != null) {
                            if (clearFirst) {
                                a11y.setNodeText(node, "")
                                sleepQuietly(50)
                            }
                            if (a11y.setNodeText(node, text)) {
                                return ScreenActionResult(true, "a11y")
                            }
                            if (clipSet && node.performAction(AccessibilityNodeInfo.ACTION_PASTE)) {
                                return ScreenActionResult(true, "a11y")
                            }
                        }
                    }
                }
            }
        }
        return ScreenActionResult(
            false,
            "none",
            "Failed to type text '$text'. Neither clipboard paste (root/shizuku keyevent 279) nor accessibility input could be applied."
        )
    }

    private fun performKey(keyStr: String, context: Context, backendPref: String): ScreenActionResult {
        val normalizedKey = keyStr.trim().lowercase()
        if (com.openminis.app.ime.MinisKeyboardService.isConnected) {
            when (normalizedKey) {
                "enter" -> if (com.openminis.app.ime.MinisKeyboardService.sendKeyCode(android.view.KeyEvent.KEYCODE_ENTER)) return ScreenActionResult(true, "minis_ime")
                "search" -> if (com.openminis.app.ime.MinisKeyboardService.sendEditorAction(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH)) return ScreenActionResult(true, "minis_ime")
                "send" -> if (com.openminis.app.ime.MinisKeyboardService.sendEditorAction(android.view.inputmethod.EditorInfo.IME_ACTION_SEND)) return ScreenActionResult(true, "minis_ime")
                "done" -> if (com.openminis.app.ime.MinisKeyboardService.sendEditorAction(android.view.inputmethod.EditorInfo.IME_ACTION_DONE)) return ScreenActionResult(true, "minis_ime")
            }
        }

        val keyCode = resolveKeyCode(keyStr)
        val backends = resolveActionBackends(backendPref)

        for (b in backends) {
            when (b) {
                "root" -> if (RootAccess.isRootGranted() && keyCode != null) {
                    val res = RootAccess.runSu("input keyevent $keyCode", 5000L)
                    if (res?.exitCode == 0) return ScreenActionResult(true, "root")
                }
                "shizuku" -> if (ShizukuManager.isReady() && keyCode != null) {
                    val res = ShizukuManager.runProcess(arrayOf("input", "keyevent", keyCode.toString()))
                    if (res.exitCode == 0) return ScreenActionResult(true, "shizuku")
                }
                "a11y" -> {
                    val a11y = MinisAccessibilityService.getInstance()
                    if (a11y != null) {
                        val handled = when (keyStr.trim().lowercase()) {
                            "back" -> a11y.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                            "home" -> a11y.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
                            "recents", "app_switch" -> a11y.performGlobalAction(AccessibilityService.GLOBAL_ACTION_RECENTS)
                            "notifications" -> a11y.performGlobalAction(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS)
                            else -> false
                        }
                        if (handled) return ScreenActionResult(true, "a11y")
                    }
                }
            }
        }
        return ScreenActionResult(false, "none", "Could not send key '$keyStr' with available backends")
    }

    private fun resolveActionBackends(pref: String): List<String> = when (pref) {
        "root" -> listOf("root")
        "shizuku" -> listOf("shizuku")
        "a11y" -> listOf("a11y")
        else -> listOf("root", "shizuku", "a11y")
    }

    fun resolveKeyCode(keyStr: String): Int? {
        keyStr.toIntOrNull()?.let { return it }
        return when (keyStr.trim().lowercase()) {
            "back" -> 4 // KEYCODE_BACK
            "home" -> 3 // KEYCODE_HOME
            "recents", "app_switch", "menu" -> 187 // KEYCODE_APP_SWITCH
            "enter", "return" -> 66 // KEYCODE_ENTER
            "del", "delete", "backspace" -> 67 // KEYCODE_DEL
            "tab" -> 61 // KEYCODE_TAB
            "space" -> 62 // KEYCODE_SPACE
            "escape", "esc" -> 111 // KEYCODE_ESCAPE
            "power" -> 26 // KEYCODE_POWER
            "volume_up", "volup" -> 24 // KEYCODE_VOLUME_UP
            "volume_down", "voldown" -> 25 // KEYCODE_VOLUME_DOWN
            "search" -> 84 // KEYCODE_SEARCH
            "dpad_up", "up" -> 19 // KEYCODE_DPAD_UP
            "dpad_down", "down" -> 20 // KEYCODE_DPAD_DOWN
            "dpad_left", "left" -> 21 // KEYCODE_DPAD_LEFT
            "dpad_right", "right" -> 22 // KEYCODE_DPAD_RIGHT
            "dpad_center" -> 23 // KEYCODE_DPAD_CENTER
            else -> null
        }
    }

    fun escapeForInputText(text: String): String {
        val sb = StringBuilder()
        for (ch in text) {
            when (ch) {
                ' ' -> sb.append("%s")
                '\\' -> sb.append("\\\\")
                '"' -> sb.append("\\\"")
                '\'' -> sb.append("\\'")
                '&' -> sb.append("\\&")
                ';' -> sb.append("\\;")
                '(' -> sb.append("\\(")
                ')' -> sb.append("\\)")
                '<' -> sb.append("\\<")
                '>' -> sb.append("\\>")
                '|' -> sb.append("\\|")
                '$' -> sb.append("\\$")
                '`' -> sb.append("\\`")
                else -> sb.append(ch)
            }
        }
        return sb.toString()
    }

    fun performLaunch(appTarget: String, context: Context, backendPref: String): ScreenActionResult {
        val pm = context.packageManager
        val pkg = resolvePackageName(appTarget, context)
            ?: return ScreenActionResult(false, "none", "Could not resolve application package for '$appTarget'")

        val backends = resolveActionBackends(backendPref)
        for (b in backends) {
            when (b) {
                "root" -> if (RootAccess.isRootGranted()) {
                    val res = RootAccess.runSu("monkey -p $pkg -c android.intent.category.LAUNCHER 1", 5000L)
                    if (res?.exitCode == 0) return ScreenActionResult(true, "root", resolvedPackage = pkg)
                }
                "shizuku" -> if (ShizukuManager.isReady()) {
                    val res = ShizukuManager.runProcess(arrayOf("monkey", "-p", pkg, "-c", "android.intent.category.LAUNCHER", "1"))
                    if (res.exitCode == 0) return ScreenActionResult(true, "shizuku", resolvedPackage = pkg)
                }
                "a11y" -> {
                    try {
                        val intent = pm.getLaunchIntentForPackage(pkg)
                        if (intent != null) {
                            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                            context.startActivity(intent)
                            return ScreenActionResult(true, "a11y", resolvedPackage = pkg)
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "failed to launch intent for $pkg via a11y context", e)
                    }
                }
            }
        }

        // Framework fallback: attempt context startActivity directly
        try {
            val intent = pm.getLaunchIntentForPackage(pkg)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                context.startActivity(intent)
                return ScreenActionResult(true, "framework", resolvedPackage = pkg)
            }
        } catch (e: Exception) {
            return ScreenActionResult(false, "none", "Failed to launch package '$pkg': ${e.message}")
        }

        return ScreenActionResult(false, "none", "No launch intent found for package '$pkg'")
    }

    fun resolvePackageName(target: String, context: Context? = null): String? {
        val trimmed = target.trim()
        if (trimmed.isEmpty()) return null
        if (trimmed.contains(".")) return trimmed

        val knownAliases = mapOf(
            "微信" to "com.tencent.mm",
            "wechat" to "com.tencent.mm",
            "qq" to "com.tencent.mobileqq",
            "小红书" to "com.xingin.xhs",
            "xhs" to "com.xingin.xhs",
            "美团" to "com.sankuai.meituan",
            "大众点评" to "com.dianping.v1",
            "淘宝" to "com.taobao.taobao",
            "京东" to "com.jingdong.app.mall",
            "拼多多" to "com.xunmeng.pinduoduo",
            "pdd" to "com.xunmeng.pinduoduo",
            "高德地图" to "com.autonavi.minimap",
            "高德" to "com.autonavi.minimap",
            "百度地图" to "com.baidu.BaiduMap",
            "抖音" to "com.ss.android.ugc.aweme",
            "快手" to "com.kuaishou.nebula",
            "哔哩哔哩" to "tv.danmaku.bili",
            "bilibili" to "tv.danmaku.bili",
            "b站" to "tv.danmaku.bili",
            "知乎" to "com.zhihu.android",
            "微博" to "com.sina.weibo",
            "网易云音乐" to "com.netease.cloudmusic",
            "网易云" to "com.netease.cloudmusic",
            "qq音乐" to "com.tencent.qqmusic",
            "饿了么" to "me.ele",
            "携程" to "ctrip.android.view",
            "12306" to "com.MobileTicket",
            "铁路12306" to "com.MobileTicket",
            "滴滴" to "com.sdu.didi.psnger",
            "滴滴出行" to "com.sdu.didi.psnger",
            "设置" to "com.android.settings",
            "相册" to "com.android.gallery3d",
            "相机" to "com.android.camera",
            "电话" to "com.android.dialer",
            "短信" to "com.android.mms",
            "浏览器" to "com.android.browser",
        )
        knownAliases[trimmed.lowercase()]?.let { return it }

        if (context == null) return null
        try {
            val pm = context.packageManager
            val launchIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val activities = pm.queryIntentActivities(launchIntent, 0)
            for (resolveInfo in activities) {
                val label = resolveInfo.loadLabel(pm).toString().trim()
                if (label.equals(trimmed, ignoreCase = true)) {
                    return resolveInfo.activityInfo.packageName
                }
            }
            for (resolveInfo in activities) {
                val label = resolveInfo.loadLabel(pm).toString().trim()
                if (label.contains(trimmed, ignoreCase = true) || trimmed.contains(label, ignoreCase = true)) {
                    return resolveInfo.activityInfo.packageName
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "error resolving package name for '$trimmed'", e)
        }

        return null
    }

    private fun sleepQuietly(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }
}
