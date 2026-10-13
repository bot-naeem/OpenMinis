package com.openminis.app.offload

import com.openminis.app.offload.OffloadPermissionManager.PermissionLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Verifies session-level root authorization and confirmation flow wiring.
 */
class SessionRootPermissionTest {

    @Test
    fun `root_cli defaults to ASK_ONCE in tool registry`() {
        val rootInfo = OffloadPermissionManager.toolRegistry.find { it.toolName == "root_cli" }
        assertNotNull("root_cli must be registered in toolRegistry", rootInfo)
        assertEquals(
            "root_cli default permission must be ASK_ONCE so un-authorized calls prompt the user",
            PermissionLevel.ASK_ONCE,
            rootInfo!!.defaultLevel,
        )
    }

    @Test
    fun `session root grant and revoke lifecycle updates state and version`() {
        val sessionId = "test-session-root-123"
        val startVersion = OffloadPermissionManager.sessionGrantsVersion.value

        assertFalse(
            "session root should not be granted by default",
            OffloadPermissionManager.isSessionGranted("root_cli", sessionId),
        )

        // Grant session
        OffloadPermissionManager.grantSession("root_cli", sessionId)
        assertTrue(
            "session root should be granted after grantSession",
            OffloadPermissionManager.isSessionGranted("root_cli", sessionId),
        )
        assertTrue(
            "version should increment on grantSession",
            OffloadPermissionManager.sessionGrantsVersion.value > startVersion,
        )

        val grantedVersion = OffloadPermissionManager.sessionGrantsVersion.value

        // Revoke session
        OffloadPermissionManager.revokeSession("root_cli", sessionId)
        assertFalse(
            "session root should not be granted after revokeSession",
            OffloadPermissionManager.isSessionGranted("root_cli", sessionId),
        )
        assertTrue(
            "version should increment on revokeSession",
            OffloadPermissionManager.sessionGrantsVersion.value > grantedVersion,
        )

        // Clean up
        OffloadPermissionManager.clearSessionGrants(sessionId)
    }

    // ── Source Wiring Assertions ──────────────────────────────────────────────

    private fun readSrc(relPath: String): String {
        val candidates = listOf(
            File(relPath),
            File("src/android/app", relPath),
        )
        val f = candidates.firstOrNull { it.exists() }
        assertNotNull("missing source file: $relPath across $candidates", f)
        return f!!.readText()
    }

    @Test
    fun `OffloadGate passes command detail to OffloadPermissionManager`() {
        val gateSrc = readSrc("src/main/java/com/openminis/app/sandbox/offload/OffloadGate.kt")
        assertTrue(
            "OffloadGate.allow must accept detail parameter",
            gateSrc.contains("detail: String? = null"),
        )
        assertTrue(
            "OffloadGate must forward detail to checkPermission",
            gateSrc.contains("detail = detail"),
        )
    }

    @Test
    fun `RootOffloadHandler forwards reconstructed command as detail to OffloadGate`() {
        val handlerSrc = readSrc("src/main/java/com/openminis/app/sandbox/offload/RootOffloadHandler.kt")
        assertTrue(
            "RootOffloadHandler must pass detail = command to OffloadGate.enforce",
            handlerSrc.contains("OffloadGate.enforce(\"root_cli\", \"android-root-cli\", args, request, detail = command)"),
        )
    }

    @Test
    fun `ChatViewModel registers and dispatches root slash command and exposes sessionRootEnabled`() {
        val vmSrc = readSrc("src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt")
        assertTrue(
            "availableSlashCommands must include root slash command",
            vmSrc.contains("id = \"root\""),
        )
        assertTrue(
            "executeSlashCommand must dispatch root to toggleSessionRootEnabled",
            vmSrc.contains("\"root\" -> toggleSessionRootEnabled()"),
        )
        assertTrue(
            "ChatViewModel must expose sessionRootEnabled StateFlow",
            vmSrc.contains("val sessionRootEnabled: StateFlow<Boolean>"),
        )
        assertTrue(
            "ChatViewModel must provide toggleSessionRootEnabled function",
            vmSrc.contains("fun toggleSessionRootEnabled()"),
        )
    }

    @Test
    fun `ChatViewModelSlashExt formats root slash command subtitle dynamically`() {
        val slashSrc = readSrc("src/main/java/com/openminis/app/ui/chat/ChatViewModelSlashExt.kt")
        assertTrue(
            "filteredSlashCommands must handle root command subtitle",
            slashSrc.contains("\"root\" -> cmd.copy("),
        )
        assertTrue(
            "filteredSlashCommands must check sessionRootEnabled.value",
            slashSrc.contains("sessionRootEnabled.value"),
        )
    }

    @Test
    fun `ChatScreen includes session root toggle in MinisMenu`() {
        val screenSrc = readSrc("src/main/java/com/openminis/app/ui/chat/ChatScreen.kt")
        assertTrue(
            "ChatScreen must bind sessionRootEnabled in MinisMenu",
            screenSrc.contains("viewModel.sessionRootEnabled.collectAsState()"),
        )
        assertTrue(
            "ChatScreen must provide session root toggle item in MinisMenu",
            screenSrc.contains("R.string.chat_menu_session_root"),
        )
    }

    @Test
    fun `OffloadPermissionDialog renders command detail and includes tri-action buttons`() {
        val dialogSrc = readSrc("src/main/java/com/openminis/app/ui/chat/OffloadPermissionDialog.kt")
        assertTrue(
            "dialog must render req.detail when present",
            dialogSrc.contains("!req.detail.isNullOrBlank()"),
        )
        assertTrue(
            "dialog must display command execution request title",
            dialogSrc.contains("R.string.offload_perm_dialog_cmd_request"),
        )
        assertTrue(
            "dialog must have allow once response",
            dialogSrc.contains("OffloadPermissionManager.Response.ALLOW_ONCE"),
        )
        assertTrue(
            "dialog must have allow session response",
            dialogSrc.contains("OffloadPermissionManager.Response.ALLOW_SESSION"),
        )
        assertTrue(
            "dialog must have deny session response",
            dialogSrc.contains("OffloadPermissionManager.Response.DENY_SESSION"),
        )
    }
}
