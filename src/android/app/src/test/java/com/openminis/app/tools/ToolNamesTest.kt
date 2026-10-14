package com.openminis.app.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-tool-surface] Tool-name resolution.
 *
 * Three things can arrive at the dispatcher under a name the current schema
 * never publishes: a pre-rename name replayed from an old transcript, and a
 * name a relay rewrote when it merged several tool sources into one request.
 * Both have to land on the same arm, and neither may swallow a name that
 * belongs to some other tool source.
 */
class ToolNamesTest {

    // ── current + legacy names ────────────────────────────────────────────

    @Test
    fun `current names resolve to themselves`() {
        assertEquals(ToolNames.BASH, ToolNames.canonical("bash"))
        assertEquals(ToolNames.READ, ToolNames.canonical("read"))
        assertEquals(ToolNames.BROWSER, ToolNames.canonical("browser_use"))
        assertEquals(ToolNames.SCREEN, ToolNames.canonical("screen"))
        assertEquals(ToolNames.SCREEN_VIEW, ToolNames.canonical("screen_view"))
        assertEquals(ToolNames.SCREEN_TOUCH, ToolNames.canonical("screen_touch"))
        assertEquals(ToolNames.ANDROID, ToolNames.canonical("android"))
        assertEquals(ToolNames.MEMORY, ToolNames.canonical("memory"))
        assertEquals(ToolNames.SUBAGENT, ToolNames.canonical("subagent"))
    }

    @Test
    fun `pre-rename names resolve to the tool that replaced them`() {
        assertEquals(ToolNames.BASH, ToolNames.canonical("shell_execute"))
        assertEquals(ToolNames.READ, ToolNames.canonical("file_read"))
        assertEquals(ToolNames.WRITE, ToolNames.canonical("file_write"))
        assertEquals(ToolNames.EDIT, ToolNames.canonical("file_edit"))
        assertEquals(ToolNames.READ, ToolNames.canonical("read_image"))
        assertEquals(ToolNames.SCREEN_VIEW, ToolNames.canonical("screen_capture"))
        assertEquals(ToolNames.SCREEN_VIEW, ToolNames.canonical("screenshot"))
        assertEquals(ToolNames.SCREEN_VIEW, ToolNames.canonical("capture"))
        assertEquals(ToolNames.SCREEN_TOUCH, ToolNames.canonical("screen_touch"))
        assertEquals(ToolNames.SCREEN_TOUCH, ToolNames.canonical("screen_tap"))
        assertEquals(ToolNames.SCREEN_TOUCH, ToolNames.canonical("screen_action"))
        assertEquals(ToolNames.SCREEN_TOUCH, ToolNames.canonical("screen_use"))
        assertEquals(ToolNames.SCREEN_TOUCH, ToolNames.canonical("touch"))
        assertEquals(ToolNames.SCREEN_TOUCH, ToolNames.canonical("tap"))
        assertEquals(ToolNames.MEMORY, ToolNames.canonical("memory_write"))
        assertEquals(ToolNames.MEMORY, ToolNames.canonical("memory_get"))
        assertEquals(ToolNames.SUBAGENT, ToolNames.canonical("subagent_task"))
        assertEquals(ToolNames.SUBAGENT, ToolNames.canonical("delegate_task"))
        assertEquals(ToolNames.SUBAGENT, ToolNames.canonical("agent_status"))
    }

    // ── relay-mangled names ───────────────────────────────────────────────
    // The exact shapes seen from an OpenAI-compatible relay that namespaces
    // client tools as `Compat<Name><hash>` and camel-cases the name on the way
    // through.

    @Test
    fun `a compat-prefixed name resolves`() {
        assertEquals(ToolNames.BASH, ToolNames.canonical("CompatBash96ee1a"))
        assertEquals(ToolNames.READ, ToolNames.canonical("CompatRead9359c7"))
        assertEquals(ToolNames.WRITE, ToolNames.canonical("CompatWrite4ab9fd"))
        assertEquals(ToolNames.EDIT, ToolNames.canonical("CompatEdit5b8814"))
        assertEquals(ToolNames.SCREEN, ToolNames.canonical("CompatScreen1a2b3c"))
        assertEquals(ToolNames.SCREEN_VIEW, ToolNames.canonical("CompatScreenView1a2b3c"))
        assertEquals(ToolNames.SCREEN_TOUCH, ToolNames.canonical("CompatScreenTouch4d5e6f"))
        assertEquals(ToolNames.ANDROID, ToolNames.canonical("CompatAndroid3e2fbc"))
        assertEquals(ToolNames.MEMORY, ToolNames.canonical("CompatMemory2826fa"))
        assertEquals(ToolNames.SUBAGENT, ToolNames.canonical("CompatSubagent04e77f"))
    }

    @Test
    fun `a mangled underscore name resolves - the relay camel-cases`() {
        // browser_use -> CompatBrowserUse0b1309: the underscore is gone and the
        // words are capitalised, so this only works because the lookup squashes
        // case and underscores together.
        assertEquals(ToolNames.BROWSER, ToolNames.canonical("CompatBrowserUse0b1309"))
        assertEquals(ToolNames.BASH, ToolNames.canonical("CompatShellExecute1234abcd"))
        assertEquals(ToolNames.READ, ToolNames.canonical("CompatFileReaddeadbeef"))
        // The trailing `e` of "Use" is a hex digit, so the longest hex run in
        // this name is `e0b1309` and a naive pattern reads it as `BrowserUs`.
        assertEquals(ToolNames.BROWSER, ToolNames.canonical("CompatBrowserUsee0b1309"))
    }

    @Test
    fun `a mangled legacy name still lands on the current tool`() {
        // A relay rewriting a call that was already replayed from an old
        // transcript has to survive both rewrites.
        assertEquals(ToolNames.MEMORY, ToolNames.canonical("CompatMemoryWriteabcdef"))
        assertEquals(ToolNames.SUBAGENT, ToolNames.canonical("CompatDelegateTask0123abcd"))
    }

    // ── it must not over-match ────────────────────────────────────────────

    @Test
    fun `an unrelated tool from another source passes through untouched`() {
        assertEquals("web_search", ToolNames.canonical("web_search"))
        assertEquals("mcp__filesystem__read", ToolNames.canonical("mcp__filesystem__read"))
        // A relay's own built-in tools are namespaced too; they must survive.
        assertEquals("CompatWebSearcha1b2c3d", ToolNames.canonical("CompatWebSearcha1b2c3d"))
    }

    @Test
    fun `a name that merely looks hex-ish is not stripped`() {
        // No trailing hash => not a relay rewrite.
        assertEquals("bashy", ToolNames.canonical("bashy"))
        assertEquals("readall", ToolNames.canonical("readall"))
    }

    @Test
    fun `matches agrees with canonical`() {
        assertTrue(ToolNames.matches("CompatBash96ee1a", ToolNames.BASH))
        assertTrue(ToolNames.matches("shell_execute", ToolNames.BASH))
        assertTrue(!ToolNames.matches("CompatWebSearcha1b2c3d", ToolNames.BASH))
    }
}
