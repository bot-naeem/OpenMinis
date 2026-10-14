package com.openminis.app.tools

import android.content.Context
import android.util.Log
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam
import com.openminis.app.sandbox.NativeOffloadHandler
import com.openminis.app.sandbox.NativeOffloadRequest
import com.openminis.app.sandbox.NativeOffloadResult
import com.openminis.app.sandbox.NativeOffloadServer
import org.json.JSONObject

/**
 * [T-android-tool-surface] The `android` tool.
 *
 * One schema, many actions. The agent picks an action and gets a handler; the
 * detail of each action's arguments lives in the `android-api` skill, which is
 * read on demand rather than re-sent on every request.
 *
 * Every action resolves to a handler already registered with
 * [NativeOffloadServer] — the same ones the sandbox's `android-*` binaries
 * reach. Going through the registry (rather than holding references here) is
 * what keeps the tool and the shell in agreement: a handler registered for the
 * CLI is by construction registered for the tool, so the two surfaces cannot
 * drift.
 */
object AndroidTool {

    private const val TAG = "AndroidTool"

    /** action -> the offload handler name it forwards to. */
    private val ACTION_HANDLERS: Map<String, String> = mapOf(
        "device" to "android-device",
        "package" to "android-shizuku-cli",
        "permission" to "android-shizuku-cli",
        "activity" to "android-shizuku-cli",
        "display" to "android-shizuku-cli",
        "settings" to "android-shizuku-cli",
        "user" to "android-shizuku-cli",
        "network" to "android-shizuku-cli",
        "input" to "android-shizuku-cli",
        "notification" to "android-notification",
        "alarm" to "android-alarm",
        "calendar" to "android-calendar",
        "clipboard" to "android-clipboard",
        "contacts" to "android-contacts",
        "photos" to "android-photos",
        "location" to "android-location",
        "speak" to "android-speak",
        "speech" to "android-speech",
        "weather" to "android-weather",
        "player" to "android-player",
        "open" to "android-open",
        "shizuku" to "android-shizuku-cli",
        "root" to "android-root-cli",
        "screen" to "android-screen",
        "screen_capture" to "android-screen",
    )

    /**
     * Actions whose handler needs a backend the device may not have. Their
     * help is still reachable (so the model can learn the arguments before
     * deciding), but invoking them returns a typed error rather than a
     * confusing handler-specific one.
     */
    private val PRIVILEGED_ACTIONS = setOf("shizuku", "root")

    fun definition(): AgentToolDefinition = AgentToolDefinition(
        name = ToolNames.ANDROID,
        description = "Control the Android device from outside the sandbox. The sandbox has no " +
            "host access, so packages, settings, notifications, alarms, input, media, screen, and any " +
            "privileged shell all go through here. " +
            "Actions: device, package, settings, activity, notification, alarm, calendar, " +
            "clipboard, contacts, photos, location, speak, speech, weather, player, open, " +
            "shizuku, root, screen. Run `android --help <action>` for the arguments of one action. " +
            "Privileged actions (shizuku, root) return permission_denied until enabled in " +
            "[Settings -> Permissions]; relay that instead of retrying.",
        parameters = mapOf(
            "tool_title" to AgentToolParam("string", "A concise 5-10 word summary of what this tool call does, shown to the user. Use the same language as the user."),
            "action" to AgentToolParam("string", "What to do: device | package | settings | activity | notification | alarm | calendar | clipboard | contacts | photos | location | speak | speech | weather | player | open | shizuku | root | screen.", enumValues = ACTION_HANDLERS.keys.toList()),
            "args" to AgentToolParam("string", "Arguments for the action, as a single string (e.g. \"info battery storage\" or \"list --max 20\"). Run `android --help <action>` for the full set."),
        ),
        required = listOf("tool_title", "action"),
        propertyOrdering = listOf("tool_title", "action", "args"),
    )

    fun execute(argsJson: String, sessionId: String, context: Context): ToolExecutionResult {
        val action: String
        val rest: List<String>
        val o: JSONObject
        try {
            o = JSONObject(argsJson)
            action = o.optString("action", "").trim().lowercase()
            rest = splitArgs(o.optString("args", ""))
        } catch (_: Exception) {
            return ToolExecutionResult("Error: could not parse tool arguments", false)
        }

        if (action.isEmpty()) {
            return ToolExecutionResult(
                "Error: 'action' is required. Known actions: ${ACTION_HANDLERS.keys.joinToString(", ")}",
                false,
            )
        }

        if (action in setOf("screen", "screen_capture", "screen_touch", "screen_tap")) {
            val rawArgs = o.optString("args", "")
            return ScreenTool.executeViaAndroidTool(action, rawArgs, sessionId, context)
        }

        if (action == "--help" || action == "help") {
            return ToolExecutionResult(help(rest.firstOrNull()), true)
        }

        val handlerName = ACTION_HANDLERS[action]
            ?: return ToolExecutionResult(
                "Error: unknown action '$action'. Known actions: ${ACTION_HANDLERS.keys.joinToString(", ")}",
                false,
            )

        val handler: NativeOffloadHandler? = NativeOffloadServer.handlerFor(handlerName)
        if (handler == null) {
            // Registration happens in MinisApp.onCreate; a miss means this tool
            // ran before the app finished wiring, not that the action is gone.
            Log.w(TAG, "no handler registered for '$handlerName' (action=$action)")
            return ToolExecutionResult(
                "Error: '$action' is not available yet in this session. Try again in a moment.",
                false,
            )
        }

        // `--help` on a known action must work even when its backend is absent,
        // otherwise the model cannot learn the arguments it needs to decide
        // whether to ask for the permission at all.
        if (rest.contains("--help") || rest.contains("-h")) {
            val forwarded = runCatching {
                handler.handle(requestFor(handlerName, listOf("--help"), sessionId))
            }.getOrNull()
            return ToolExecutionResult(forwarded?.output ?: help(action), true)
        }

        // The action is a routing label, not an argument. Every handler does
        // `argv.drop(1)` and then dispatches on `positional[0]` — so passing
        // the action through would make `android-shizuku-cli` look for a
        // subcommand literally named "shizuku" and every call would fail with
        // "unknown subcommand". argv[1] has to be the handler's OWN
        // subcommand, which is what `args` carries.
        val result: NativeOffloadResult = runCatching {
            handler.handle(requestFor(handlerName, rest, sessionId))
        }.getOrElse { e ->
            Log.w(TAG, "action '$action' failed", e)
            NativeOffloadResult(exitCode = 1, output = "{\"error\":\"${e.message}\"}")
        }

        return ToolExecutionResult(
            output = result.output,
            success = result.exitCode == 0,
        )
    }

    private fun requestFor(handlerName: String, argv: List<String>, sessionId: String) =
        NativeOffloadRequest(
            pid = android.os.Process.myPid(),
            argv = listOf(handlerName) + argv,
            env = emptyMap(),
            cwd = "",
            sessionId = sessionId,
        )

    /**
     * Split the free-form `args` string. Deliberately dumb — the action's own
     * parser (OffloadArgs) handles quoting inside a value; this only has to
     * break the string into tokens, and it must not try to be clever about
     * quotes because the model may legitimately pass an unbalanced one.
     */
    private fun splitArgs(raw: String): List<String> =
        raw.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }

    private fun help(action: String?): String {
        if (action == null) {
            return buildString {
                appendLine("android — actions:")
                ACTION_HANDLERS.keys.sorted().chunked(4).forEach { row ->
                    appendLine("  " + row.joinToString(", "))
                }
                appendLine()
                append("Run `android --help <action>` for one action's arguments.")
            }
        }
        val handlerName = ACTION_HANDLERS[action] ?: return "Error: unknown action '$action'"
        return runCatching {
            NativeOffloadServer.handlerFor(handlerName)
                ?.handle(requestFor(handlerName, listOf("--help"), ""))
                ?.output
        }.getOrNull() ?: "Error: no handler for '$action'"
    }
}
