package com.openminis.app.provider.autoglm

import com.openminis.app.tools.ToolNames
import org.json.JSONObject
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.regex.Pattern

/**
 * Result of parsing AutoGLM-Phone output.
 */
sealed class AutoGLMActionResult {
    /**
     * An executable mobile action (e.g. Launch, Tap, Type, Swipe, Back, Home, Wait, Take_over).
     */
    data class Execute(
        val action: String,
        val params: JSONObject,
        val rawCommand: String,
        val reasoning: String,
    ) : AutoGLMActionResult() {
        /**
         * Convert to arguments for OpenMinis `screen_touch` tool.
         */
        fun toScreenTouchArgs(): JSONObject {
            val json = JSONObject(params.toString())
            json.put("action", action)
            if (!json.has("capture_after")) {
                json.put("capture_after", true)
            }
            if (!json.has("tool_title")) {
                val title = when (action) {
                    "launch" -> "打开 " + json.optString("app", json.optString("package", "应用"))
                    "click", "tap" -> "点击屏幕 (${json.optInt("x", 0)}, ${json.optInt("y", 0)})"
                    "double_click" -> "双击屏幕 (${json.optInt("x", 0)}, ${json.optInt("y", 0)})"
                    "long_press" -> "长按屏幕 (${json.optInt("x", 0)}, ${json.optInt("y", 0)})"
                    "type" -> "输入: " + json.optString("text", "")
                    "swipe" -> "滑动屏幕 (${json.optInt("x", 0)}, ${json.optInt("y", 0)}) -> (${json.optInt("to_x", 0)}, ${json.optInt("to_y", 0)})"
                    "press_key" -> when (json.optString("key", "")) {
                        "back" -> "返回"
                        "home" -> "返回桌面"
                        else -> "按键 " + json.optString("key", "")
                    }
                    "wait" -> "等待页面加载"
                    "takeover" -> "请求用户接管"
                    else -> "屏幕操作: $action"
                }
                json.put("tool_title", title)
            }
            return json
        }
    }

    /**
     * Task completion signal from AutoGLM (`finish(message="...")`).
     */
    data class Finish(
        val message: String,
        val reasoning: String,
    ) : AutoGLMActionResult()

    /**
     * Regular conversational text without any action.
     */
    data class None(
        val text: String,
    ) : AutoGLMActionResult()
}

/**
 * Adapter and action parser for Zhipu AutoGLM-Phone (Open-AutoGLM).
 *
 * Bridges AutoGLM's domain-specific VLM action syntax (`do(action=...)`, `finish(...)`,
 * normalized [0, 999] coordinates) with OpenMinis native `screen_touch` execution loop.
 */
object AutoGLMAdapter {

    fun isAutoGlm(modelId: String?): Boolean =
        modelId?.contains("autoglm", ignoreCase = true) == true

    /**
     * Specialized prompt expected by AutoGLM-Phone models.
     */
    fun buildSystemPrompt(): String {
        val today = LocalDate.now()
        val weekdayNames = listOf("星期一", "星期二", "星期三", "星期四", "星期五", "星期六", "星期日")
        val weekday = weekdayNames[today.dayOfWeek.value - 1]
        val formattedDate = today.format(DateTimeFormatter.ofPattern("yyyy年MM月dd日")) + " " + weekday

        return """今天的日期是: $formattedDate
你是一个智能体分析专家，可以根据操作历史和当前状态图执行一系列操作来完成任务。
你必须严格按照要求输出以下格式：
<think>{think}</think>
<answer>{action}</answer>

其中：
- {think} 是对你为什么选择这个操作的简短推理说明。
- {action} 是本次执行的具体操作指令，必须严格遵循下方定义的指令格式。

操作指令及其作用如下：
- do(action="Launch", app="xxx")  
    Launch是启动目标app的操作，这比通过主屏幕导航更快。此操作完成后，您将自动收到结果状态的截图。
- do(action="Tap", element=[x,y])  
    Tap是点击操作，点击屏幕上的特定点。可用此操作点击按钮、选择项目、从主屏幕打开应用程序，或与任何可点击的用户界面元素进行交互。坐标系统从左上角 (0,0) 开始到右下角（999,999)结束。此操作完成后，您将自动收到结果状态的截图。
- do(action="Tap", element=[x,y], message="重要操作")  
    基本功能同Tap，点击涉及财产、支付、隐私等敏感按钮时触发。
- do(action="Type", text="xxx")  
    Type是输入操作，在当前聚焦的输入框中输入文本。使用此操作前，请确保输入框已被聚焦（先点击它）。输入的文本将像使用键盘输入一样输入。操作完成后，你将自动收到结果状态的截图。
- do(action="Type_Name", text="xxx")  
    Type_Name是输入人名的操作，基本功能同Type。
- do(action="Interact")  
    Interact是当有多个满足条件的选项时而触发的交互操作，询问用户如何选择。
- do(action="Swipe", start=[x1,y1], end=[x2,y2])  
    Swipe是滑动操作，通过从起始坐标拖动到结束坐标来执行滑动手势。坐标系统从左上角 (0,0) 开始到右下角（999,999)结束。此操作完成后，您将自动收到结果状态的截图。
- do(action="Note", message="True")  
    记录当前页面内容以便后续总结。
- do(action="Call_API", instruction="xxx")  
    总结或评论当前页面或已记录的内容。
- do(action="Long Press", element=[x,y])  
    Long Press是长按操作，在屏幕上的特定点长按指定时间。坐标系统从左上角 (0,0) 开始到右下角（999,999)结束。此操作完成后，您将自动收到结果状态的屏幕截图。
- do(action="Double Tap", element=[x,y])  
    Double Tap在屏幕上的特定点快速连续点按两次。坐标系统从左上角 (0,0) 开始到右下角（999,999)结束。此操作完成后，您将自动收到结果状态的截图。
- do(action="Take_over", message="xxx")  
    Take_over是接管操作，表示在登录和验证阶段需要用户协助。
- do(action="Back")  
    导航返回到上一个屏幕或关闭当前对话框。相当于按下 Android 的返回按钮。此操作完成后，您将自动收到结果状态的截图。
- do(action="Home") 
    Home是回到系统桌面的操作，相当于按下 Android 主屏幕按钮。此操作完成后，您将自动收到结果状态的截图。
- do(action="Wait", duration="x seconds")  
    等待页面加载，x为需要等待多少秒。
- finish(message="xxx")  
    finish是结束任务的操作，表示准确完整完成任务，message是终止信息。

必须遵循的规则：
1. 在执行任何操作前，先检查当前app是否是目标app，如果不是，先执行 Launch。
2. 如果进入到了无关页面，先执行 Back。如果执行Back后页面没有变化，请点击页面左上角的返回键进行返回，或者右上角的X号关闭。
3. 如果页面未加载出内容，最多连续 Wait 三次，否则执行 Back重新进入。
4. 如果当前页面找不到目标联系人、商品、店铺等信息，可以尝试 Swipe 滑动查找。
5. 遇到输入操作，先Tap点击输入框使其获得焦点，然后再执行 Type。
6. 在结束任务前请一定要仔细检查任务是否完整准确的完成，并在完成时调用 finish(message="...")."""
    }

    private val FINISH_REGEX = Pattern.compile(
        """finish\s*\(\s*(?:message\s*=\s*)?["'](.*?)["']\s*\)""",
        Pattern.DOTALL
    )

    private val DO_ACTION_REGEX = Pattern.compile(
        """do\s*\(\s*action\s*=\s*["']([^"']+)["']\s*(?:,\s*(.*?))?\)""",
        Pattern.DOTALL
    )

    private val LAUNCH_DIRECT_REGEX = Pattern.compile(
        """Launch\s*\(\s*(?:app\s*=\s*)?["'](.*?)["']\s*\)""",
        Pattern.DOTALL
    )

    private val TAP_DIRECT_REGEX = Pattern.compile(
        """(?:Tap|Click)\s*\(\s*(\d+)\s*,\s*(\d+)\s*\)"""
    )

    private val TYPE_DIRECT_REGEX = Pattern.compile(
        """Type\s*\(\s*(?:text\s*=\s*)?["'](.*?)["']\s*\)""",
        Pattern.DOTALL
    )

    private val SWIPE_DIRECT_REGEX = Pattern.compile(
        """Swipe\s*\(\s*(\d+)\s*,\s*(\d+)\s*,\s*(\d+)\s*,\s*(\d+)\s*\)"""
    )

    private val BACK_DIRECT_REGEX = Pattern.compile("""Back\s*\(\s*\)""")
    private val HOME_DIRECT_REGEX = Pattern.compile("""Home\s*\(\s*\)""")

    /**
     * Parse raw text output from AutoGLM into a structured [AutoGLMActionResult].
     */
    fun parseAction(rawText: String): AutoGLMActionResult {
        val text = rawText.trim()
        if (text.isEmpty()) return AutoGLMActionResult.None("")

        // 1. Check for finish(message="...")
        val finishMatcher = FINISH_REGEX.matcher(text)
        if (finishMatcher.find()) {
            val finishMsg = finishMatcher.group(1) ?: ""
            val rawCmd = finishMatcher.group(0) ?: ""
            val reasoning = cleanReasoningText(text, rawCmd)
            return AutoGLMActionResult.Finish(finishMsg, reasoning)
        }

        // 2. Check for do(action="...", ...)
        val doMatcher = DO_ACTION_REGEX.matcher(text)
        if (doMatcher.find()) {
            val actionName = doMatcher.group(1)?.trim() ?: ""
            val paramsStr = doMatcher.group(2) ?: ""
            val rawCmd = doMatcher.group(0) ?: ""
            val reasoning = cleanReasoningText(text, rawCmd)

            val parsedParams = parseParamsString(paramsStr)
            val mapped = mapActionToScreenTouch(actionName, parsedParams, rawCmd, reasoning)
            if (mapped != null) return mapped
        }

        // 3. Direct function calls fallback (e.g. Launch("微信"), Tap(100, 200))
        val launchMatcher = LAUNCH_DIRECT_REGEX.matcher(text)
        if (launchMatcher.find()) {
            val app = launchMatcher.group(1) ?: ""
            val rawCmd = launchMatcher.group(0) ?: ""
            val reasoning = cleanReasoningText(text, rawCmd)
            val params = JSONObject().put("app", app)
            return AutoGLMActionResult.Execute("launch", params, rawCmd, reasoning)
        }

        val tapMatcher = TAP_DIRECT_REGEX.matcher(text)
        if (tapMatcher.find()) {
            val x = tapMatcher.group(1)?.toIntOrNull() ?: 0
            val y = tapMatcher.group(2)?.toIntOrNull() ?: 0
            val rawCmd = tapMatcher.group(0) ?: ""
            val reasoning = cleanReasoningText(text, rawCmd)
            val params = JSONObject().put("x", x).put("y", y)
            return AutoGLMActionResult.Execute("click", params, rawCmd, reasoning)
        }

        val typeMatcher = TYPE_DIRECT_REGEX.matcher(text)
        if (typeMatcher.find()) {
            val typed = typeMatcher.group(1) ?: ""
            val rawCmd = typeMatcher.group(0) ?: ""
            val reasoning = cleanReasoningText(text, rawCmd)
            val params = JSONObject().put("text", typed).put("clear", true)
            return AutoGLMActionResult.Execute("type", params, rawCmd, reasoning)
        }

        val swipeMatcher = SWIPE_DIRECT_REGEX.matcher(text)
        if (swipeMatcher.find()) {
            val x1 = swipeMatcher.group(1)?.toIntOrNull() ?: 0
            val y1 = swipeMatcher.group(2)?.toIntOrNull() ?: 0
            val x2 = swipeMatcher.group(3)?.toIntOrNull() ?: 0
            val y2 = swipeMatcher.group(4)?.toIntOrNull() ?: 0
            val rawCmd = swipeMatcher.group(0) ?: ""
            val reasoning = cleanReasoningText(text, rawCmd)
            val params = JSONObject().put("x", x1).put("y", y1).put("to_x", x2).put("to_y", y2)
            return AutoGLMActionResult.Execute("swipe", params, rawCmd, reasoning)
        }

        if (BACK_DIRECT_REGEX.matcher(text).find()) {
            val rawCmd = "Back()"
            val reasoning = cleanReasoningText(text, rawCmd)
            val params = JSONObject().put("key", "back")
            return AutoGLMActionResult.Execute("press_key", params, rawCmd, reasoning)
        }

        if (HOME_DIRECT_REGEX.matcher(text).find()) {
            val rawCmd = "Home()"
            val reasoning = cleanReasoningText(text, rawCmd)
            val params = JSONObject().put("key", "home")
            return AutoGLMActionResult.Execute("press_key", params, rawCmd, reasoning)
        }

        return AutoGLMActionResult.None(text)
    }

    private fun parseParamsString(paramsStr: String): JSONObject {
        val json = JSONObject()
        if (paramsStr.isBlank()) return json

        // 1. Match [x, y] coordinates: key=[123, 456]
        val coordPattern = Pattern.compile("""(\w+)\s*=\s*\[\s*(\d+)\s*,\s*(\d+)\s*\]""")
        val coordMatcher = coordPattern.matcher(paramsStr)
        while (coordMatcher.find()) {
            val key = coordMatcher.group(1) ?: ""
            val x = coordMatcher.group(2)?.toIntOrNull() ?: 0
            val y = coordMatcher.group(3)?.toIntOrNull() ?: 0
            val arr = org.json.JSONArray().put(x).put(y)
            json.put(key, arr)
        }

        // 2. Match string key="val" or key='val'
        val strPattern = Pattern.compile("""(\w+)\s*=\s*["'](.*?)["']""")
        val strMatcher = strPattern.matcher(paramsStr)
        while (strMatcher.find()) {
            val key = strMatcher.group(1) ?: ""
            val v = strMatcher.group(2) ?: ""
            if (!json.has(key)) json.put(key, v)
        }

        // 3. Match numeric key=123
        val numPattern = Pattern.compile("""(\w+)\s*=\s*(\d+)""")
        val numMatcher = numPattern.matcher(paramsStr)
        while (numMatcher.find()) {
            val key = numMatcher.group(1) ?: ""
            val num = numMatcher.group(2)?.toIntOrNull()
            if (num != null && !json.has(key)) json.put(key, num)
        }

        return json
    }

    private fun mapActionToScreenTouch(
        actionName: String,
        params: JSONObject,
        rawCmd: String,
        reasoning: String,
    ): AutoGLMActionResult.Execute? {
        val outParams = JSONObject()

        when (actionName.trim().lowercase()) {
            "launch" -> {
                val app = params.optString("app", "").ifEmpty {
                    params.optString("package", "")
                }
                outParams.put("app", app)
                return AutoGLMActionResult.Execute("launch", outParams, rawCmd, reasoning)
            }
            "tap", "click" -> {
                val element = params.optJSONArray("element")
                if (element != null && element.length() >= 2) {
                    outParams.put("x", element.getInt(0))
                    outParams.put("y", element.getInt(1))
                } else if (params.has("x") && params.has("y")) {
                    outParams.put("x", params.getInt("x"))
                    outParams.put("y", params.getInt("y"))
                }
                if (params.has("message")) {
                    outParams.put("message", params.optString("message"))
                }
                return AutoGLMActionResult.Execute("click", outParams, rawCmd, reasoning)
            }
            "double tap", "double_tap", "double_click" -> {
                val element = params.optJSONArray("element")
                if (element != null && element.length() >= 2) {
                    outParams.put("x", element.getInt(0))
                    outParams.put("y", element.getInt(1))
                } else if (params.has("x") && params.has("y")) {
                    outParams.put("x", params.getInt("x"))
                    outParams.put("y", params.getInt("y"))
                }
                return AutoGLMActionResult.Execute("double_click", outParams, rawCmd, reasoning)
            }
            "long press", "long_press" -> {
                val element = params.optJSONArray("element")
                if (element != null && element.length() >= 2) {
                    outParams.put("x", element.getInt(0))
                    outParams.put("y", element.getInt(1))
                } else if (params.has("x") && params.has("y")) {
                    outParams.put("x", params.getInt("x"))
                    outParams.put("y", params.getInt("y"))
                }
                return AutoGLMActionResult.Execute("long_press", outParams, rawCmd, reasoning)
            }
            "type", "type_name" -> {
                val text = params.optString("text", "")
                outParams.put("text", text)
                outParams.put("clear", true)
                return AutoGLMActionResult.Execute("type", outParams, rawCmd, reasoning)
            }
            "swipe", "drag" -> {
                val start = params.optJSONArray("start")
                val end = params.optJSONArray("end")
                if (start != null && start.length() >= 2) {
                    outParams.put("x", start.getInt(0))
                    outParams.put("y", start.getInt(1))
                }
                if (end != null && end.length() >= 2) {
                    outParams.put("to_x", end.getInt(0))
                    outParams.put("to_y", end.getInt(1))
                }
                return AutoGLMActionResult.Execute("swipe", outParams, rawCmd, reasoning)
            }
            "back" -> {
                outParams.put("key", "back")
                return AutoGLMActionResult.Execute("press_key", outParams, rawCmd, reasoning)
            }
            "home" -> {
                outParams.put("key", "home")
                return AutoGLMActionResult.Execute("press_key", outParams, rawCmd, reasoning)
            }
            "wait" -> {
                val durationStr = params.optString("duration", "1").trim()
                val seconds = Regex("""(\d+)""").find(durationStr)?.groupValues?.get(1)?.toLongOrNull() ?: 1L
                outParams.put("duration_ms", seconds * 1000L)
                return AutoGLMActionResult.Execute("wait", outParams, rawCmd, reasoning)
            }
            "take_over", "takeover" -> {
                val msg = params.optString("message", "请在设备上手动完成操作后继续")
                outParams.put("message", msg)
                return AutoGLMActionResult.Execute("takeover", outParams, rawCmd, reasoning)
            }
            else -> return null
        }
    }

    /**
     * Strips `<think>...</think>`, `<answer>...</answer>`, and raw `do(...)` commands,
     * leaving only the human-readable thoughts/reasoning.
     */
    fun cleanReasoningText(fullText: String, rawCmd: String = ""): String {
        var t = fullText
        // Strip think tags if present
        t = t.replace(Regex("""<think>[\s\S]*?</think>"""), "")
        // Strip answer tags
        t = t.replace("<answer>", "").replace("</answer>", "")
        // Strip the raw command if provided
        if (rawCmd.isNotEmpty()) {
            t = t.replace(rawCmd, "")
        }
        // Also strip any residual do(action=...) or finish(...)
        t = t.replace(Regex("""do\s*\(\s*action\s*=[\s\S]*?\)\s*"""), "")
        t = t.replace(Regex("""finish\s*\([\s\S]*?\)\s*"""), "")
        t = t.replace(Regex("""Launch\s*\([\s\S]*?\)\s*"""), "")
        t = t.replace(Regex("""(?:Tap|Click)\s*\([\s\S]*?\)\s*"""), "")
        t = t.replace(Regex("""Type\s*\([\s\S]*?\)\s*"""), "")
        t = t.replace(Regex("""Swipe\s*\([\s\S]*?\)\s*"""), "")
        t = t.replace(Regex("""Back\s*\(\s*\)\s*"""), "")
        t = t.replace(Regex("""Home\s*\(\s*\)\s*"""), "")
        return t.trim()
    }

    /**
     * Filter partial text during streaming so raw action commands like `do(...)`
     * or `<answer>` are not streamed to the user's visible text bubble.
     */
    fun filterStreamVisibleText(visibleText: String): String {
        // If the chunk contains answer tags or do(action, strip them
        return visibleText
            .replace("<answer>", "")
            .replace("</answer>", "")
            .replace(Regex("""do\s*\(\s*action\s*=[\s\S]*?\)\s*"""), "")
            .replace(Regex("""finish\s*\([\s\S]*?\)\s*"""), "")
    }

    /**
     * Formats an OpenMinis `screen_touch` tool call argument back into the
     * native AutoGLM DSL string (`do(action=...)`) for assistant history replay.
     */
    fun formatToAutoGLM(input: JSONObject): String {
        val action = input.optString("action", "").trim().lowercase()
        return when (action) {
            "launch" -> {
                val app = input.optString("app", input.optString("package", ""))
                """do(action="Launch", app="$app")"""
            }
            "click", "tap" -> {
                val x = input.optInt("x", 0)
                val y = input.optInt("y", 0)
                """do(action="Tap", element=[$x, $y])"""
            }
            "double_click" -> {
                val x = input.optInt("x", 0)
                val y = input.optInt("y", 0)
                """do(action="Double Tap", element=[$x, $y])"""
            }
            "long_press" -> {
                val x = input.optInt("x", 0)
                val y = input.optInt("y", 0)
                """do(action="Long Press", element=[$x, $y])"""
            }
            "type" -> {
                val text = input.optString("text", "")
                """do(action="Type", text="$text")"""
            }
            "swipe" -> {
                val x1 = input.optInt("x", 0)
                val y1 = input.optInt("y", 0)
                val x2 = input.optInt("to_x", 0)
                val y2 = input.optInt("to_y", 0)
                """do(action="Swipe", start=[$x1, $y1], end=[$x2, $y2])"""
            }
            "press_key" -> {
                when (input.optString("key", "").lowercase()) {
                    "back" -> """do(action="Back")"""
                    "home" -> """do(action="Home")"""
                    else -> """do(action="Back")"""
                }
            }
            "wait" -> {
                val ms = input.optLong("duration_ms", 1000L)
                val sec = (ms / 1000L).coerceAtLeast(1L)
                """do(action="Wait", duration="$sec seconds")"""
            }
            "takeover" -> {
                val msg = input.optString("message", "请在设备上手动完成操作后继续")
                """do(action="Take_over", message="$msg")"""
            }
            else -> ""
        }
    }
}
