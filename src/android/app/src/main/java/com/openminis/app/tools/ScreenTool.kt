package com.openminis.app.tools

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Path
import android.os.Build
import android.util.Log
import android.view.Display
import android.view.WindowManager
import com.openminis.app.accessibility.MinisAccessibilityService
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam
import com.openminis.app.offload.ShizukuManager
import com.openminis.app.sandbox.PRootKernel
import com.openminis.app.sandbox.RootAccess
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.roundToInt

/**
 * Screen automation tool for visual GUI agents.
 *
 * Provides phone-level visual perception and physical interaction:
 * - `capture`: captures the screen and returns image data (JPEG compressed) directly into the agent context
 * - `tap`: taps at normalized (0~1000) or physical coordinates
 * - `double_tap`: double taps in rapid succession
 * - `long_press`: presses and holds at a location
 * - `swipe`: swipes from (x, y) to (to_x, to_y) with specified duration
 * - `scroll`: scrolls up, down, left, or right
 * - `type`: inputs text into focused field
 * - `key`: presses system or navigation keys (back, home, recents, enter, etc.)
 * - `wait`: pauses for animations or loading
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

    fun definition(): AgentToolDefinition = AgentToolDefinition(
        name = NAME,
        description = "Control and observe the physical Android device screen. " +
            "Take screenshots to see the current UI (returns visual image data to the model). " +
            "Perform touch actions (tap, double_tap, long_press, swipe, scroll), type text, " +
            "press system keys (back, home, recents, enter), or wait for UI rendering. " +
            "Coordinates x, y, to_x, to_y use normalized 0~1000 scale (0,0 is top-left, 1000,1000 is bottom-right). " +
            "Supports Root, Shizuku, and AccessibilityService backends automatically. " +
            "Set `capture_after: true` on any touch action to immediately capture and return the updated screen in the same turn.",
        parameters = mapOf(
            "tool_title" to AgentToolParam("string", "A concise 5-10 word summary of what this tool call does, shown to the user. Use the same language as the user."),
            "action" to AgentToolParam("string", "What to do on the screen",
                enumValues = listOf("capture", "tap", "double_tap", "long_press", "swipe", "scroll", "type", "key", "wait")),
            "x" to AgentToolParam("integer", "X coordinate (0~1000 normalized, where 0 is left edge and 1000 is right edge) for tap, double_tap, long_press, or swipe start."),
            "y" to AgentToolParam("integer", "Y coordinate (0~1000 normalized, where 0 is top edge and 1000 is bottom edge) for tap, double_tap, long_press, or swipe start."),
            "to_x" to AgentToolParam("integer", "Target X coordinate (0~1000 normalized) for swipe."),
            "to_y" to AgentToolParam("integer", "Target Y coordinate (0~1000 normalized) for swipe."),
            "direction" to AgentToolParam("string", "Scroll direction for scroll action", enumValues = listOf("up", "down", "left", "right")),
            "text" to AgentToolParam("string", "Text to type for type action."),
            "key" to AgentToolParam("string", "Key name for key action: 'back', 'home', 'recents', 'enter', 'delete', 'volume_up', 'volume_down', 'power' (or Android keycode integer)."),
            "duration_ms" to AgentToolParam("integer", "Duration in milliseconds for swipe or wait action (default: 300ms for swipe, 1000ms for wait)."),
            "settle_ms" to AgentToolParam("integer", "Delay in milliseconds after touch action to let Android UI finish transition animations before returning (default: 400ms)."),
            "capture_after" to AgentToolParam("boolean", "If true, automatically captures the screen after executing the action (after settle delay) and returns the screenshot image in this turn."),
            "backend" to AgentToolParam("string", "Explicit backend to use ('auto', 'root', 'shizuku', 'a11y'). Default is 'auto' (picks best available).", enumValues = listOf("auto", "root", "shizuku", "a11y")),
        ),
        required = listOf("tool_title", "action"),
        propertyOrdering = listOf("tool_title", "action", "x", "y", "to_x", "to_y", "direction", "text", "key", "duration_ms", "settle_ms", "capture_after", "backend"),
    )

    fun execute(argsJson: String, sessionId: String?, context: Context): ToolExecutionResult {
        val o = try {
            JSONObject(argsJson)
        } catch (e: Exception) {
            return ToolExecutionResult("Error: invalid JSON in screen arguments: ${e.message}", false)
        }

        val toolTitle = o.optString("tool_title", "Screen Action")
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

        val (screenW, screenH) = getDisplayBounds(context)

        return when (action) {
            "capture", "screenshot" -> {
                val maxEdge = o.optInt("max_edge", DEFAULT_MAX_EDGE)
                val quality = o.optInt("quality", DEFAULT_JPEG_QUALITY)
                executeCapture(context, sessionId, maxEdge, quality, backendPref, toolTitle)
            }

            "tap", "click" -> {
                val rawX = if (o.has("x")) o.getInt("x") else null
                val rawY = if (o.has("y")) o.getInt("y") else null
                if (rawX == null || rawY == null) {
                    return ToolExecutionResult("Error: 'x' and 'y' are required for tap", false, toolTitle = toolTitle)
                }
                val (realX, realY) = normalizeCoords(rawX, rawY, screenW, screenH)
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

            "double_tap" -> {
                val rawX = if (o.has("x")) o.getInt("x") else null
                val rawY = if (o.has("y")) o.getInt("y") else null
                if (rawX == null || rawY == null) {
                    return ToolExecutionResult("Error: 'x' and 'y' are required for double_tap", false, toolTitle = toolTitle)
                }
                val (realX, realY) = normalizeCoords(rawX, rawY, screenW, screenH)
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
                val rawX = if (o.has("x")) o.getInt("x") else null
                val rawY = if (o.has("y")) o.getInt("y") else null
                if (rawX == null || rawY == null) {
                    return ToolExecutionResult("Error: 'x' and 'y' are required for long_press", false, toolTitle = toolTitle)
                }
                val durationMs = o.optLong("duration_ms", 800L).coerceIn(200L, 5000L)
                val (realX, realY) = normalizeCoords(rawX, rawY, screenW, screenH)
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

            "swipe" -> {
                val rawX = if (o.has("x")) o.getInt("x") else null
                val rawY = if (o.has("y")) o.getInt("y") else null
                val rawToX = if (o.has("to_x")) o.getInt("to_x") else null
                val rawToY = if (o.has("to_y")) o.getInt("to_y") else null

                if (rawX == null || rawY == null || rawToX == null || rawToY == null) {
                    return ToolExecutionResult("Error: 'x', 'y', 'to_x', 'to_y' are required for swipe", false, toolTitle = toolTitle)
                }

                val durationMs = o.optLong("duration_ms", DEFAULT_SWIPE_DURATION_MS).coerceIn(50L, 5000L)
                val (realStartX, realStartY) = normalizeCoords(rawX, rawY, screenW, screenH)
                val (realEndX, realEndY) = normalizeCoords(rawToX, rawToY, screenW, screenH)

                val actRes = performSwipe(realStartX, realStartY, realEndX, realEndY, durationMs, context, backendPref)
                if (!actRes.success) {
                    return ToolExecutionResult(actRes.toErrorJson().toString(2), false, toolTitle = toolTitle)
                }

                if (settleMs > 0) sleepQuietly(settleMs)

                val details = JSONObject().apply {
                    put("from", JSONObject().put("x", rawX).put("y", rawY).put("real_x", realStartX).put("real_y", realStartY))
                    put("to", JSONObject().put("x", rawToX).put("y", rawToY).put("real_x", realEndX).put("real_y", realEndY))
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
                val actRes = performType(text, context, backendPref)
                if (!actRes.success) {
                    return ToolExecutionResult(actRes.toErrorJson().toString(2), false, toolTitle = toolTitle)
                }

                if (settleMs > 0) sleepQuietly(settleMs)

                val details = JSONObject().apply {
                    put("text", text)
                    put("backend", actRes.backend)
                    put("settle_ms", settleMs)
                }

                if (captureAfter) {
                    executeCapture(context, sessionId, DEFAULT_MAX_EDGE, DEFAULT_JPEG_QUALITY, backendPref, toolTitle, extraData = details)
                } else {
                    val out = JSONObject().put("ok", true).put("action", "type").put("data", details)
                    ToolExecutionResult(out.toString(2), true, toolTitle = toolTitle)
                }
            }

            "key" -> {
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

            else -> {
                ToolExecutionResult(
                    output = "Error: unknown action '$action'. Known actions: capture, tap, double_tap, long_press, swipe, scroll, type, key, wait",
                    success = false,
                    toolTitle = toolTitle,
                )
            }
        }
    }

    /**
     * Adaptor for callers forwarding from AndroidTool:
     * `android(action="screen", args="capture")` or `android(action="screen", args="tap 500 500")`
     */
    fun executeViaAndroidTool(action: String, argsStr: String, sessionId: String?, context: Context): ToolExecutionResult {
        val tokens = argsStr.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        val effectiveAction = if (action in setOf("screen_capture", "screen_touch", "screen_tap")) {
            action.removePrefix("screen_")
        } else {
            tokens.firstOrNull()?.lowercase() ?: "capture"
        }

        val rest = if (action == "screen" && tokens.isNotEmpty()) tokens.drop(1) else tokens
        val obj = JSONObject().put("action", effectiveAction).put("tool_title", "Screen $effectiveAction")

        when (effectiveAction) {
            "tap", "click", "double_tap" -> {
                if (rest.size >= 2) {
                    obj.put("x", rest[0].toIntOrNull() ?: 500)
                    obj.put("y", rest[1].toIntOrNull() ?: 500)
                }
            }
            "swipe" -> {
                if (rest.size >= 4) {
                    obj.put("x", rest[0].toIntOrNull() ?: 500)
                    obj.put("y", rest[1].toIntOrNull() ?: 700)
                    obj.put("to_x", rest[2].toIntOrNull() ?: 500)
                    obj.put("to_y", rest[3].toIntOrNull() ?: 300)
                }
            }
            "scroll" -> {
                if (rest.isNotEmpty()) obj.put("direction", rest[0])
            }
            "type" -> {
                obj.put("text", rest.joinToString(" "))
            }
            "key" -> {
                if (rest.isNotEmpty()) obj.put("key", rest[0])
            }
            "wait" -> {
                if (rest.isNotEmpty()) obj.put("duration_ms", rest[0].toLongOrNull() ?: 1000L)
            }
        }

        return execute(obj.toString(), sessionId, context)
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

    /**
     * Translates normalized coordinates (0..1000) or passes through absolute pixel coordinates.
     */
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

        // Scale to token budget
        val scaledBitmap = if (origW > maxEdge || origH > maxEdge) {
            val scale = maxEdge.toFloat() / maxOf(origW, origH)
            val sw = (origW * scale).roundToInt().coerceAtLeast(1)
            val sh = (origH * scale).roundToInt().coerceAtLeast(1)
            Bitmap.createScaledBitmap(rawBitmap, sw, sh, true).also {
                if (it !== rawBitmap) rawBitmap.recycle()
            }
        } else {
            rawBitmap
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

        val json = JSONObject().apply {
            put("ok", true)
            put("action", "capture")
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
        // Priority order based on backendPref
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
            else -> arrayOf( // "down" (default): scroll down content (drag finger up)
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

    private fun performType(text: String, context: Context, backendPref: String): ScreenActionResult {
        val backends = resolveActionBackends(backendPref)
        for (b in backends) {
            when (b) {
                "root" -> if (RootAccess.isRootGranted()) {
                    val escaped = escapeForInputText(text)
                    val res = RootAccess.runSu("input text $escaped", 5000L)
                    if (res?.exitCode == 0) return ScreenActionResult(true, "root")
                }
                "shizuku" -> if (ShizukuManager.isReady()) {
                    val escaped = escapeForInputText(text)
                    val res = ShizukuManager.runProcess(arrayOf("input", "text", escaped))
                    if (res.exitCode == 0) return ScreenActionResult(true, "shizuku")
                }
                "a11y" -> {
                    val a11y = MinisAccessibilityService.getInstance()
                    if (a11y != null) {
                        val focused = a11y.rootInActiveWindow?.findFocus(android.view.accessibility.AccessibilityNodeInfo.FOCUS_INPUT)
                        if (focused != null && focused.isEditable) {
                            if (a11y.setNodeText(focused, text)) return ScreenActionResult(true, "a11y")
                        }
                    }
                }
            }
        }
        return ScreenActionResult(false, "none", "No input backend available for type action")
    }

    private fun performKey(keyStr: String, context: Context, backendPref: String): ScreenActionResult {
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

    private fun sleepQuietly(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }
}
