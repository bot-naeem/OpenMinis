package com.openminis.app.tools

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [ScreenTool] coordinate normalization, key mappings, point parsing,
 * and Doubao Phone Assistant / UI-TARS visual grounding schema definitions.
 */
class ScreenToolTest {

    @Test
    fun `viewDefinition exposes screen_view tool with Set-of-Marks schema`() {
        val def = ScreenTool.viewDefinition()
        assertEquals(ToolNames.SCREEN_VIEW, def.name)
        assertTrue(def.required.contains("tool_title"))
        assertTrue(def.parameters.containsKey("som"))
        assertTrue(def.parameters.containsKey("delay_ms"))
        assertTrue(def.parameters.containsKey("max_edge"))
        assertTrue(def.parameters.containsKey("quality"))
        assertTrue(def.parameters.containsKey("backend"))

        val somParam = def.parameters["som"]
        assertNotNull(somParam)
        assertEquals("boolean", somParam!!.type)
    }

    @Test
    fun `touchDefinition exposes screen_touch tool with Doubao and UI-TARS action space`() {
        val def = ScreenTool.touchDefinition()
        assertEquals(ToolNames.SCREEN_TOUCH, def.name)
        assertTrue(def.required.contains("tool_title"))
        assertTrue(def.required.contains("action"))

        val actionParam = def.parameters["action"]
        assertNotNull(actionParam)
        val expectedActions = listOf(
            "launch",
            "click", "tap",
            "double_click", "double_tap",
            "long_press",
            "swipe", "scroll",
            "type",
            "press_key", "key",
            "wait",
            "takeover"
        )
        assertTrue(actionParam!!.enumValues!!.containsAll(expectedActions))

        // Targets and options
        assertTrue(def.parameters.containsKey("app"))
        assertTrue(def.parameters.containsKey("element"))
        assertTrue(def.parameters.containsKey("point"))
        assertTrue(def.parameters.containsKey("x"))
        assertTrue(def.parameters.containsKey("y"))
        assertTrue(def.parameters.containsKey("to_x"))
        assertTrue(def.parameters.containsKey("to_y"))
        assertTrue(def.parameters.containsKey("direction"))
        assertTrue(def.parameters.containsKey("text"))
        assertTrue(def.parameters.containsKey("key"))
        assertTrue(def.parameters.containsKey("duration_ms"))
        assertTrue(def.parameters.containsKey("settle_ms"))
        assertTrue(def.parameters.containsKey("capture_after"))
        assertTrue(def.parameters.containsKey("message"))
        assertTrue(def.parameters.containsKey("backend"))
    }

    @Test
    fun `definition delegates to touchDefinition`() {
        val def = ScreenTool.definition()
        assertEquals(ToolNames.SCREEN_TOUCH, def.name)
    }

    @Test
    fun `parsePoint parses UI-TARS point formats and json array`() {
        // UI-TARS standard string representation: [x, y]
        val p1 = ScreenTool.parsePoint("[523, 814]")
        assertNotNull(p1)
        assertEquals(523, p1!!.first)
        assertEquals(814, p1.second)

        // Comma separated
        val p2 = ScreenTool.parsePoint("100, 200")
        assertNotNull(p2)
        assertEquals(100, p2!!.first)
        assertEquals(200, p2.second)

        // Space separated
        val p3 = ScreenTool.parsePoint("350 720")
        assertNotNull(p3)
        assertEquals(350, p3!!.first)
        assertEquals(720, p3.second)

        // JSONArray
        val jsonArr = JSONArray().put(450).put(900)
        val p4 = ScreenTool.parsePoint(jsonArr)
        assertNotNull(p4)
        assertEquals(450, p4!!.first)
        assertEquals(900, p4.second)

        // Null and invalid inputs
        assertNull(ScreenTool.parsePoint(null))
        assertNull(ScreenTool.parsePoint("invalid"))
        assertNull(ScreenTool.parsePoint(""))
    }

    @Test
    fun `normalizeCoords maps 0 to 1000 scale to physical screen pixels`() {
        val screenW = 1080
        val screenH = 2400

        // Origin (top-left)
        val (x0, y0) = ScreenTool.normalizeCoords(0, 0, screenW, screenH)
        assertEquals(0, x0)
        assertEquals(0, y0)

        // Center
        val (xCenter, yCenter) = ScreenTool.normalizeCoords(500, 500, screenW, screenH)
        assertEquals(540, xCenter)
        assertEquals(1200, yCenter)

        // Maximum normalized (bottom-right)
        val (xMax, yMax) = ScreenTool.normalizeCoords(1000, 1000, screenW, screenH)
        assertEquals(1080, xMax)
        assertEquals(2400, yMax)

        // Fractional point (e.g. 250, 750)
        val (xQuarter, yThreeQuarter) = ScreenTool.normalizeCoords(250, 750, screenW, screenH)
        assertEquals(270, xQuarter)
        assertEquals(1800, yThreeQuarter)
    }

    @Test
    fun `normalizeCoords allows absolute pixel coordinates when greater than 1000`() {
        val screenW = 1080
        val screenH = 2400

        // Absolute physical coordinate within bounds
        val (xAbs, yAbs) = ScreenTool.normalizeCoords(1050, 2300, screenW, screenH)
        assertEquals(1050, xAbs)
        assertEquals(2300, yAbs)

        // Out-of-bounds coordinates get clamped
        val (xOver, yOver) = ScreenTool.normalizeCoords(1500, 3000, screenW, screenH)
        assertEquals(1080, xOver)
        assertEquals(2400, yOver)
    }

    @Test
    fun `resolveKeyCode resolves standard Android keys`() {
        assertEquals(4, ScreenTool.resolveKeyCode("back"))
        assertEquals(4, ScreenTool.resolveKeyCode("BACK"))
        assertEquals(3, ScreenTool.resolveKeyCode("home"))
        assertEquals(187, ScreenTool.resolveKeyCode("recents"))
        assertEquals(187, ScreenTool.resolveKeyCode("app_switch"))
        assertEquals(66, ScreenTool.resolveKeyCode("enter"))
        assertEquals(66, ScreenTool.resolveKeyCode("return"))
        assertEquals(67, ScreenTool.resolveKeyCode("del"))
        assertEquals(67, ScreenTool.resolveKeyCode("delete"))
        assertEquals(67, ScreenTool.resolveKeyCode("backspace"))
        assertEquals(26, ScreenTool.resolveKeyCode("power"))
        assertEquals(24, ScreenTool.resolveKeyCode("volume_up"))
        assertEquals(25, ScreenTool.resolveKeyCode("volume_down"))
        assertEquals(61, ScreenTool.resolveKeyCode("tab"))
        assertEquals(62, ScreenTool.resolveKeyCode("space"))

        // Numeric string passes through as keycode
        assertEquals(82, ScreenTool.resolveKeyCode("82"))

        // Unknown key name returns null
        assertNull(ScreenTool.resolveKeyCode("nonexistent_key_xyz"))
    }

    @Test
    fun `escapeForInputText escapes spaces and shell characters`() {
        assertEquals("hello%sworld", ScreenTool.escapeForInputText("hello world"))
        assertEquals("test%smessage%swith%sspaces", ScreenTool.escapeForInputText("test message with spaces"))
        assertEquals("foo\\\"bar", ScreenTool.escapeForInputText("foo\"bar"))
        assertEquals("foo\\\$bar", ScreenTool.escapeForInputText("foo\$bar"))
        assertEquals("foo\\&bar", ScreenTool.escapeForInputText("foo&bar"))
    }

    @Test
    fun `resolvePackageName resolves direct package names and known Chinese app shortcuts`() {
        // Direct package name
        assertEquals("com.tencent.mm", ScreenTool.resolvePackageName("com.tencent.mm"))
        assertEquals("com.xingin.xhs", ScreenTool.resolvePackageName("com.xingin.xhs"))

        // Known aliases
        assertEquals("com.tencent.mm", ScreenTool.resolvePackageName("微信"))
        assertEquals("com.tencent.mm", ScreenTool.resolvePackageName("WeChat"))
        assertEquals("com.xingin.xhs", ScreenTool.resolvePackageName("小红书"))
        assertEquals("com.xingin.xhs", ScreenTool.resolvePackageName("xhs"))
        assertEquals("com.sankuai.meituan", ScreenTool.resolvePackageName("美团"))
        assertEquals("com.taobao.taobao", ScreenTool.resolvePackageName("淘宝"))
        assertEquals("com.autonavi.minimap", ScreenTool.resolvePackageName("高德地图"))
        assertEquals("com.autonavi.minimap", ScreenTool.resolvePackageName("高德"))
        assertEquals("tv.danmaku.bili", ScreenTool.resolvePackageName("哔哩哔哩"))
        assertEquals("tv.danmaku.bili", ScreenTool.resolvePackageName("b站"))
        assertEquals("com.MobileTicket", ScreenTool.resolvePackageName("12306"))

        // Blank returns null
        assertNull(ScreenTool.resolvePackageName(""))
        assertNull(ScreenTool.resolvePackageName("   "))
    }
}
