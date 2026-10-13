package com.openminis.app.sandbox.offload

import com.openminis.app.offload.OffloadPermissionManager
import com.openminis.app.sandbox.NativeOffloadRequest
import com.openminis.app.sandbox.NativeOffloadResult
import kotlinx.coroutines.runBlocking
import org.json.JSONObject

/**
 * Tri-state agent gate for `android-*` offload CLIs. Sits at the
 * NativeOffloadHandler entry point so every shell invocation of a CLI —
 * regardless of how the LLM constructed the command — passes through
 * the same BYPASS / ASK_ONCE / NOT_ALLOWED policy stored in
 * [OffloadPermissionManager].
 *
 * Why a helper:
 *   * `NativeOffloadHandler.handle` is synchronous (the offload IPC
 *     server invokes it from a worker thread and writes the result
 *     into the guest's tmp file before replying). [checkPermission]
 *     is suspend (ASK_ONCE awaits user input on a coroutine), so we
 *     bridge with `runBlocking`. The block is fine on this thread —
 *     the IPC reply already gates further guest progress on its
 *     return.
 *   * Every handler shares the same prompt-and-cache flow, so the
 *     boilerplate lives here rather than 5+ copy-pastes.
 *
 * Session scope: T340 — prefer the chat session id forwarded by the
 * agent shell via the `MINIS_CHAT_SESSION_ID` env var (surfaced as
 * [NativeOffloadRequest.sessionId]). Falls back to
 * `OFFLOAD_GLOBAL_SESSION_ID` when the offload runs outside a chat
 * (e.g. interactive terminal). This keeps "Allow in this session"
 * grants from leaking across chat sessions.
 */
internal object OffloadGate {
    /**
     * @return true if the CLI may proceed; false → handler should
     * short-circuit with a PERMISSION_DENIED envelope pointing the
     * user to Settings → Permissions.
     */
    fun allow(toolName: String, displayName: String, sessionId: String? = null): Boolean = runBlocking {
        OffloadPermissionManager.checkPermission(
            toolName,
            displayName,
            sessionId ?: OffloadPermissionManager.OFFLOAD_GLOBAL_SESSION_ID,
        )
    }

    /** Overload that pulls the chat session id straight off the request. */
    fun allow(toolName: String, displayName: String, request: NativeOffloadRequest): Boolean =
        allow(toolName, displayName, request.sessionId)

    /**
     * Convenience: gate the call and return null when allowed, or a
     * pre-formatted PERMISSION_DENIED envelope when not. Lets a handler
     * write `enforce(...)?.let { return it }` at the top of `handle`.
     */
    fun enforce(
        toolName: String,
        displayName: String,
        args: OffloadArgs,
        request: NativeOffloadRequest? = null,
    ): NativeOffloadResult? {
        if (allow(toolName, displayName, request?.sessionId)) return null
        val body = JSONObject()
            .put("error", "permission_denied")
            .put("tool", toolName)
            // Q4: the old message was one sentence for all eighteen
            // capabilities, which sent the model to a page with sixteen rows
            // and told it nothing about which one was at fault — or, for root,
            // that there was a second thing to do afterwards (accept the su
            // prompt). Each of the three privileged backends now says what is
            // missing and where to fix it.
            .put("message", denialMessage(toolName, displayName))
            .toString()
        return NativeOffloadResult(126, OffloadOutput.formatBody(body, args) + "\n")
    }

    /**
     * The message the model relays when a capability is switched off.
     *
     * Not localised here: this string crosses the offload IPC into the guest
     * shell, where it is read by a model rather than by a person, and the
     * three privileged cases carry instructions the model must follow rather
     * than translate. The user-facing copy of the same three facts lives in
     * the permissions screen itself.
     */
    private fun denialMessage(toolName: String, displayName: String): String =
        when (toolName) {
            "root_cli" ->
                "Root is switched off. Tell the user: Minis Settings -> Permissions, " +
                    "switch on Root, and accept the superuser prompt (Magisk or KernelSU). " +
                    "Do not retry until they confirm."
            "shizuku_cli" ->
                "Shizuku is switched off. Tell the user: Minis Settings -> Permissions, " +
                    "switch on Shizuku, and make sure the Shizuku service is running. " +
                    "Do not retry until they confirm."
            "a11y_cli" ->
                "Accessibility control is switched off. Tell the user: Minis Settings -> " +
                    "Permissions, switch it on, then enable the Minis service in system " +
                    "Accessibility settings. Do not retry until they confirm."
            else ->
                "Agent is not allowed to use $displayName. " +
                    "Tell the user: Minis Settings -> Permissions, find \"$displayName\", " +
                    "and switch it on. Do not retry until they confirm."
        }
}
