package com.openminis.app.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [ScreenTool] coordinate normalization, key mappings, and schema definitions.
 */
class ScreenToolTest {

    @Test
    fun `definition exposes screen tool with proper schema`() {
        val def = ScreenTool.definition()
        assertEquals(ToolNames.SCREEN, def.name)
        assertTrue(def.required.contains("tool_title"))
        assertTrue(def.required.contains("action"))

        val actionParam = def.parameters["action"]
        assertNotNull(actionParam)
        val expectedActions = listOf("capture", "tap", "double_tap", "long_press", "swipe", "scroll", "type", "key", "wait")
        assertTrue(actionParam!!.enumValues!!.containsAll(expectedActions))
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
}
