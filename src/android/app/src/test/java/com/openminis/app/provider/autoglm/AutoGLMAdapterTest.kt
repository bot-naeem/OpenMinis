package com.openminis.app.provider.autoglm

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoGLMAdapterTest {

    @Test
    fun testIsAutoGlm() {
        assertTrue(AutoGLMAdapter.isAutoGlm("autoglm-phone"))
        assertTrue(AutoGLMAdapter.isAutoGlm("AutoGLM-Phone-9B"))
        assertTrue(AutoGLMAdapter.isAutoGlm("zai-org/AutoGLM-Phone-9B"))
        assertEquals(false, AutoGLMAdapter.isAutoGlm("gpt-4o"))
        assertEquals(false, AutoGLMAdapter.isAutoGlm("deepseek-chat"))
        assertEquals(false, AutoGLMAdapter.isAutoGlm(null))
    }

    @Test
    fun testParseLaunch() {
        val input = "用户要求打开微信。\ndo(action=\"Launch\", app=\"微信\")"
        val res = AutoGLMAdapter.parseAction(input)
        assertTrue(res is AutoGLMActionResult.Execute)
        val exec = res as AutoGLMActionResult.Execute
        assertEquals("launch", exec.action)
        assertEquals("微信", exec.params.optString("app"))
        val touchArgs = exec.toScreenTouchArgs()
        assertEquals("launch", touchArgs.optString("action"))
        assertEquals("微信", touchArgs.optString("app"))
        assertTrue(touchArgs.optBoolean("capture_after"))
        assertEquals("打开 微信", touchArgs.optString("tool_title"))
    }

    @Test
    fun testParseTap() {
        val input = "<think>点击搜索框</think>\n<answer>do(action=\"Tap\", element=[350, 720])</answer>"
        val res = AutoGLMAdapter.parseAction(input)
        assertTrue(res is AutoGLMActionResult.Execute)
        val exec = res as AutoGLMActionResult.Execute
        assertEquals("click", exec.action)
        assertEquals(350, exec.params.optInt("x"))
        assertEquals(720, exec.params.optInt("y"))
        val touchArgs = exec.toScreenTouchArgs()
        assertEquals("click", touchArgs.optString("action"))
        assertEquals(350, touchArgs.optInt("x"))
        assertEquals(720, touchArgs.optInt("y"))
        assertTrue(touchArgs.optBoolean("capture_after"))
    }

    @Test
    fun testParseType() {
        val input = "do(action=\"Type\", text=\"明天上午十点开会\")"
        val res = AutoGLMAdapter.parseAction(input)
        assertTrue(res is AutoGLMActionResult.Execute)
        val exec = res as AutoGLMActionResult.Execute
        assertEquals("type", exec.action)
        assertEquals("明天上午十点开会", exec.params.optString("text"))
        assertTrue(exec.params.optBoolean("clear"))
    }

    @Test
    fun testParseSwipe() {
        val input = "do(action=\"Swipe\", start=[500, 800], end=[500, 200])"
        val res = AutoGLMAdapter.parseAction(input)
        assertTrue(res is AutoGLMActionResult.Execute)
        val exec = res as AutoGLMActionResult.Execute
        assertEquals("swipe", exec.action)
        assertEquals(500, exec.params.optInt("x"))
        assertEquals(800, exec.params.optInt("y"))
        assertEquals(500, exec.params.optInt("to_x"))
        assertEquals(200, exec.params.optInt("to_y"))
    }

    @Test
    fun testParseKeys() {
        val backRes = AutoGLMAdapter.parseAction("do(action=\"Back\")")
        assertTrue(backRes is AutoGLMActionResult.Execute)
        assertEquals("back", (backRes as AutoGLMActionResult.Execute).params.optString("key"))

        val homeRes = AutoGLMAdapter.parseAction("do(action=\"Home\")")
        assertTrue(homeRes is AutoGLMActionResult.Execute)
        assertEquals("home", (homeRes as AutoGLMActionResult.Execute).params.optString("key"))
    }

    @Test
    fun testParseWaitAndTakeover() {
        val waitRes = AutoGLMAdapter.parseAction("do(action=\"Wait\", duration=\"3 seconds\")")
        assertTrue(waitRes is AutoGLMActionResult.Execute)
        assertEquals(3000L, (waitRes as AutoGLMActionResult.Execute).params.optLong("duration_ms"))

        val takeRes = AutoGLMAdapter.parseAction("do(action=\"Take_over\", message=\"请完成滑块验证\")")
        assertTrue(takeRes is AutoGLMActionResult.Execute)
        assertEquals("takeover", (takeRes as AutoGLMActionResult.Execute).action)
        assertEquals("请完成滑块验证", takeRes.params.optString("message"))
    }

    @Test
    fun testParseFinish() {
        val input = "<think>任务已经全部完成</think>\n<answer>finish(message=\"微信已发送成功给张三！\")</answer>"
        val res = AutoGLMAdapter.parseAction(input)
        assertTrue(res is AutoGLMActionResult.Finish)
        val fin = res as AutoGLMActionResult.Finish
        assertEquals("微信已发送成功给张三！", fin.message)
    }

    @Test
    fun testFormatToAutoGLM() {
        val launchObj = JSONObject().put("action", "launch").put("app", "微信")
        assertEquals("""do(action="Launch", app="微信")""", AutoGLMAdapter.formatToAutoGLM(launchObj))

        val clickObj = JSONObject().put("action", "click").put("x", 200).put("y", 300)
        assertEquals("""do(action="Tap", element=[200, 300])""", AutoGLMAdapter.formatToAutoGLM(clickObj))

        val typeObj = JSONObject().put("action", "type").put("text", "测试文字")
        assertEquals("""do(action="Type", text="测试文字")""", AutoGLMAdapter.formatToAutoGLM(typeObj))

        val swipeObj = JSONObject().put("action", "swipe").put("x", 100).put("y", 200).put("to_x", 300).put("to_y", 400)
        assertEquals("""do(action="Swipe", start=[100, 200], end=[300, 400])""", AutoGLMAdapter.formatToAutoGLM(swipeObj))

        val backObj = JSONObject().put("action", "press_key").put("key", "back")
        assertEquals("""do(action="Back")""", AutoGLMAdapter.formatToAutoGLM(backObj))
    }

    @Test
    fun testCleanReasoningText() {
        val input = "用户要求打开微信。\n让我先启动微信应用。\ndo(action=\"Launch\", app=\"微信\")"
        val cleaned = AutoGLMAdapter.cleanReasoningText(input)
        assertEquals("用户要求打开微信。\n让我先启动微信应用。", cleaned)

        val inputWithThink = "<think>点击搜索框输入文字</think>\n<answer>do(action=\"Tap\", element=[500, 500])</answer>"
        val cleanedThink = AutoGLMAdapter.cleanReasoningText(inputWithThink)
        assertEquals("", cleanedThink)

        val inputFinish = "finish(message=\"任务完成！\")"
        assertEquals("", AutoGLMAdapter.cleanReasoningText(inputFinish))
    }

    @Test
    fun testRealWorldOutputs() {
        val liveOutput1 = "用户要求打开微信给Joe发一条消息：\"你好\"。当前屏幕显示的是一个蓝色的纯色屏幕，这可能是手机的主屏幕或者某个应用界面。\n\n我需要：\n1. 首先打开微信应用\n2. 然后找到Joe这个联系人\n3. 给他发送消息\"你好\"\n\n让我先启动微信应用。\ndo(action=\"Launch\", app=\"微信\")"
        val parsed1 = AutoGLMAdapter.parseAction(liveOutput1)
        assertTrue(parsed1 is AutoGLMActionResult.Execute)
        val exec1 = parsed1 as AutoGLMActionResult.Execute
        assertEquals("launch", exec1.action)
        assertEquals("微信", exec1.params.optString("app"))
        assertTrue(exec1.reasoning.contains("让我先启动微信应用"))
        assertTrue(!exec1.reasoning.contains("do(action"))

        val liveOutputFinish = "finish(message=\"任务完成！我已经成功给Joe发送了消息\\\"你好\\\"。消息已经发送出去，现在正在等待对方接收。\")"
        val parsedFinish = AutoGLMAdapter.parseAction(liveOutputFinish)
        assertTrue(parsedFinish is AutoGLMActionResult.Finish)
        val fin = parsedFinish as AutoGLMActionResult.Finish
        assertTrue(fin.message.contains("我已经成功给Joe发送了消息"))
    }
}
