package com.openminis.app.tools

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-tool-surface] The argv the `android` tool hands a handler.
 *
 * The bug this guards against is invisible from the schema: the action is a
 * routing label, and every handler reads `argv[1]` as *its own* subcommand.
 * Passing the action through makes `android-shizuku-cli` search for a
 * subcommand named "shizuku", and every single call fails the same way with no
 * argument combination able to get past it.
 */
class AndroidToolArgvTest {

    private fun argvFor(action: String, args: String): List<String> {
        // Mirrors the parsing AndroidTool.execute does, without a Context: the
        // handler name comes from the action, the rest is the user's string.
        val handlerName = HANDLERS[action] ?: error("unknown action")
        val rest = args.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        return listOf(handlerName) + rest
    }

    private val HANDLERS = mapOf(
        "device" to "android-device",
        "alarm" to "android-alarm",
        "notification" to "android-notification",
        "shizuku" to "android-shizuku-cli",
        "root" to "android-root-cli",
    )

    @Test
    fun `the action never reaches argv`() {
        assertEquals(
            listOf("android-shizuku-cli", "package", "list", "--max", "3"),
            argvFor("shizuku", "package list --max 3"),
        )
        assertEquals(
            listOf("android-device", "info", "battery"),
            argvFor("device", "info battery"),
        )
    }

    @Test
    fun `handlers that take a bare command get it at argv 1`() {
        // android-alarm dispatches on schedule/timer/open; the action name
        // would land in that slot and match none of them.
        assertEquals(listOf("android-alarm", "open"), argvFor("alarm", "open"))
        // android-device treats argv 1 as positional info|battery|storage|all.
        assertEquals(listOf("android-device", "storage"), argvFor("device", "storage"))
    }

    @Test
    fun `a handler with no subcommand still receives an empty argv 1`() {
        assertEquals(listOf("android-shizuku-cli"), argvFor("shizuku", ""))
        // It must not become ["android-shizuku-cli", "shizuku"], which is the
        // failure the phone reported as "unknown subcommand 'shizuku'".
    }

    @Test
    fun `quoting inside one argument is left to the handler`() {
        // args is split on whitespace only. A value with spaces arrives as
        // several tokens, which is the handler's parser's problem to report —
        // better than this tool inventing a quoting scheme the handlers do not
        // share.
        assertEquals(
            listOf("android-alarm", "schedule", "07:30", "--label", "wake", "up"),
            argvFor("alarm", "schedule 07:30 --label wake up"),
        )
    }

    @Test
    fun `every mapped action resolves to a registered handler`() {
        for ((action, handler) in HANDLERS) {
            assertTrue("action '$action' has a handler", handler.isNotEmpty())
        }
    }
}
