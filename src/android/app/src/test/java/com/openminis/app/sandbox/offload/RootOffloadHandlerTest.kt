package com.openminis.app.sandbox.offload

import com.openminis.app.sandbox.PRootKernel
import com.openminis.app.sandbox.RootAccess
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests verifying that RootOffloadHandler argument parsing, RootAccess
 * result structures, and PRootKernel host filesystem path resolution behave correctly.
 */
class RootOffloadHandlerTest {

    @Test
    fun `root command args are not truncated to first token`() {
        val rawTokens = listOf("ls", "-la", "/data/local/tmp")
        val command = extractRootCommand(rawTokens)
        assertEquals("ls -la /data/local/tmp", command)
    }

    @Test
    fun `root command preserves flags like timeout-ms while keeping positional command intact`() {
        val rawTokens = listOf("cat", "/etc/hosts", "--timeout-ms", "5000")
        val command = extractRootCommand(rawTokens)
        val timeoutMs = OffloadArgs(rawTokens).get("timeout-ms")?.toLongOrNull()

        assertEquals("cat /etc/hosts", command)
        assertEquals(5000L, timeoutMs)
    }

    @Test
    fun `su result destructuring keeps backwards compatibility`() {
        val res = RootAccess.SuResult(
            stdout = "first line\nsecond line\nthird line",
            stderr = "",
            exitCode = 0,
        )
        val (firstLine, code) = res
        assertEquals("first line", firstLine)
        assertEquals(0, code)
        assertEquals("first line", res.stdoutFirstLine)
    }

    @Test
    fun `host filesystem path detection distinguishes Android host paths from sandbox paths`() {
        assertTrue(PRootKernel.isHostFilesystemPath("/data/data/com.openminis.app"))
        assertTrue(PRootKernel.isHostFilesystemPath("/data/local/tmp/test.sh"))
        assertTrue(PRootKernel.isHostFilesystemPath("/sdcard/Download"))
        assertTrue(PRootKernel.isHostFilesystemPath("/storage/emulated/0"))
        assertTrue(PRootKernel.isHostFilesystemPath("/system/bin/sh"))

        assertFalse(PRootKernel.isHostFilesystemPath("/var/minis/workspace"))
        assertFalse(PRootKernel.isHostFilesystemPath("/var/minis/attachments/image.png"))
    }
}
