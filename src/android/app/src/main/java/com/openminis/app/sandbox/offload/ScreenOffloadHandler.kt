package com.openminis.app.sandbox.offload

import android.content.Context
import com.openminis.app.sandbox.NativeOffloadHandler
import com.openminis.app.sandbox.NativeOffloadRequest
import com.openminis.app.sandbox.NativeOffloadResult
import com.openminis.app.tools.ScreenTool
import org.json.JSONObject

/**
 * `android-screen` — CLI bridge for device screen capture and automation.
 *
 * Provides a shell/PRoot accessible interface for:
 * - `android-screen capture [--quality Q] [--max-edge N]`
 * - `android-screen tap <x> <y> [--settle-ms MS] [--capture]`
 * - `android-screen swipe <x1> <y1> <x2> <y2> [--duration MS] [--settle-ms MS]`
 * - `android-screen scroll <direction> [--duration MS]`
 * - `android-screen type <text>`
 * - `android-screen key <key>`
 * - `android-screen wait <duration_ms>`
 */
class ScreenOffloadHandler(private val context: Context) : NativeOffloadHandler {

    override fun handle(request: NativeOffloadRequest): NativeOffloadResult {
        val args = OffloadArgs(request.argv.drop(1), booleanFlags = setOf("help", "h", "capture", "capture_after"))
        if (args.hasFlag("h", "help") || args.positional.isEmpty()) {
            val isVersion = args.positional.firstOrNull() == "--version"
            return NativeOffloadResult(
                exitCode = 0,
                output = if (isVersion) "android-screen 1.0\n" else HELP + "\n",
            )
        }

        val sub = args.positional[0].lowercase()
        val jsonArgs = JSONObject().put("action", sub).put("tool_title", "CLI Screen $sub")

        args.get("backend")?.let { jsonArgs.put("backend", it) }
        args.getInt("settle-ms")?.let { jsonArgs.put("settle_ms", it) }
        if (args.hasFlag("capture", "capture_after")) jsonArgs.put("capture_after", true)

        when (sub) {
            "capture", "screenshot" -> {
                args.getInt("max-edge")?.let { jsonArgs.put("max_edge", it) }
                args.getInt("quality")?.let { jsonArgs.put("quality", it) }
            }
            "tap", "click" -> {
                val x = args.positional.getOrNull(1)?.toIntOrNull()
                val y = args.positional.getOrNull(2)?.toIntOrNull()
                if (x == null || y == null) {
                    return NativeOffloadResult(2, "android-screen: tap requires <x> <y>\n")
                }
                jsonArgs.put("x", x).put("y", y)
            }
            "double_tap", "double-tap" -> {
                jsonArgs.put("action", "double_tap")
                val x = args.positional.getOrNull(1)?.toIntOrNull()
                val y = args.positional.getOrNull(2)?.toIntOrNull()
                if (x == null || y == null) {
                    return NativeOffloadResult(2, "android-screen: double-tap requires <x> <y>\n")
                }
                jsonArgs.put("x", x).put("y", y)
            }
            "long_press", "long-press" -> {
                jsonArgs.put("action", "long_press")
                val x = args.positional.getOrNull(1)?.toIntOrNull()
                val y = args.positional.getOrNull(2)?.toIntOrNull()
                if (x == null || y == null) {
                    return NativeOffloadResult(2, "android-screen: long-press requires <x> <y>\n")
                }
                jsonArgs.put("x", x).put("y", y)
                args.getInt("duration")?.let { jsonArgs.put("duration_ms", it) }
            }
            "swipe" -> {
                val x1 = args.positional.getOrNull(1)?.toIntOrNull()
                val y1 = args.positional.getOrNull(2)?.toIntOrNull()
                val x2 = args.positional.getOrNull(3)?.toIntOrNull()
                val y2 = args.positional.getOrNull(4)?.toIntOrNull()
                if (x1 == null || y1 == null || x2 == null || y2 == null) {
                    return NativeOffloadResult(2, "android-screen: swipe requires <x1> <y1> <x2> <y2>\n")
                }
                jsonArgs.put("x", x1).put("y", y1).put("to_x", x2).put("to_y", y2)
                args.getInt("duration")?.let { jsonArgs.put("duration_ms", it) }
            }
            "scroll" -> {
                val dir = args.positional.getOrNull(1) ?: "down"
                jsonArgs.put("direction", dir)
                args.getInt("duration")?.let { jsonArgs.put("duration_ms", it) }
            }
            "type" -> {
                val text = args.positional.drop(1).joinToString(" ")
                if (text.isEmpty()) {
                    return NativeOffloadResult(2, "android-screen: type requires <text>\n")
                }
                jsonArgs.put("text", text)
            }
            "key" -> {
                val key = args.positional.getOrNull(1)
                if (key.isNullOrEmpty()) {
                    return NativeOffloadResult(2, "android-screen: key requires <keycode_or_name>\n")
                }
                jsonArgs.put("key", key)
            }
            "wait" -> {
                val duration = args.positional.getOrNull(1)?.toIntOrNull() ?: 1000
                jsonArgs.put("duration_ms", duration)
            }
            else -> {
                return NativeOffloadResult(2, "android-screen: unknown subcommand '$sub'\n$HELP\n")
            }
        }

        val res = ScreenTool.execute(jsonArgs.toString(), request.sessionId, context)
        val formatted = OffloadOutput.formatBody(res.output, args)
        return NativeOffloadResult(
            exitCode = if (res.success) 0 else 1,
            output = formatted + "\n",
        )
    }

    companion object {
        private val HELP = """
            android-screen — screen capture and GUI automation CLI.

            Usage:
              android-screen capture [--quality Q] [--max-edge N]
              android-screen tap <x> <y> [--settle-ms MS] [--capture]
              android-screen double-tap <x> <y> [--settle-ms MS] [--capture]
              android-screen long-press <x> <y> [--duration MS] [--settle-ms MS] [--capture]
              android-screen swipe <x1> <y1> <x2> <y2> [--duration MS] [--settle-ms MS]
              android-screen scroll <up|down|left|right> [--duration MS] [--settle-ms MS]
              android-screen type <text> [--settle-ms MS]
              android-screen key <back|home|recents|enter|delete|power|KEYCODE>
              android-screen wait <duration_ms>

            Coordinates:
              Uses normalized 0~1000 scale: (0,0) is top-left, (1000,1000) is bottom-right.
              Coordinates > 1000 are interpreted as physical screen pixels.

            Backends:
              Auto-selects Root, Shizuku, or AccessibilityService based on availability.
              Pass --backend [auto|root|shizuku|a11y] to force a specific backend.
        """.trimIndent()
    }
}
