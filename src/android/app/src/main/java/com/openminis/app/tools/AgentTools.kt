package com.openminis.app.tools

import com.openminis.app.browser.BrowserAction
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam
import com.openminis.app.data.model.SubAgentDefinition

/**
 * Central registry of all agent tool definitions.
 * Returns provider-agnostic AgentToolDefinition list used by the agent loop.
 * Tool definitions aligned with iOS AIChatViewModel.makeAgentTools().
 */
object AgentTools {

    fun makeAgentTools(
        supportsImageInput: Boolean = true,
        // [T-android-vision-group / GH#182] When the main model can't natively
        // see images but the user has bound a Vision Group, still expose
        // read_image: ReadImageTool routes the image through a vision-capable
        // group member and returns a text description. Mirrors iOS makeAgentTools
        // visionGroupConfigured. Neither native vision nor a Vision Group → tool
        // stays absent (current behaviour).
        visionGroupConfigured: Boolean = false,
        // [T-memory-toggle-gates-injection-and-tools-android] When the
        // user has turned memory off (via /memory or
        // Settings/SessionMemorySheet), drop both memory_write and
        // memory_get from the schema entirely so the model can't even
        // attempt those calls. Mirrors the iOS gate at
        // AIChatViewModel.makeAgentTools(memoryEnabled:).
        memoryEnabled: Boolean = true,
        // [T-p1-delegate-task] True for a helper (child) vm. Depth = 1: a
        // helper never sees delegate_task; it also loses memory_write (design
        // §4.3 — helpers have no long-term memory). memory_get stays so a
        // helper can still LOOK things up.
        isHelper: Boolean = false,
        // [T-p2-agent-settings] Settings › Agents can remove delegation globally.
        delegateEnabled: Boolean = true,
        // [T-tools-granular-switches] Settings › Agent Tools can remove
        // browser_use globally. Separate from delegateEnabled because the two
        // are independent user choices; mirrors iOS AgentToolSwitch.browser.
        browserEnabled: Boolean = true,
        // Screen automation tool — capture and touch on physical phone display.
        screenEnabled: Boolean = true,
        // [T-sub-agents-v1] The names of the enabled sub agents, in roster
        // order. These become `subagent_task.agent`'s enum, and the caller
        // rebuilds them every turn: the schema is not cached, so a rename takes
        // effect on the next request and the model cannot emit a name that
        // would fail to resolve. Empty falls back to the built-in's name so the
        // enum is never an empty list (which some providers reject).
        rosterNames: List<String> = listOf(SubAgentDefinition.BUILT_IN_NAME),
    ): List<AgentToolDefinition> = buildList {
        // [T-android-sandbox-only] read now covers images too, so
        // ReadImageTool.definition() is folded in and its own schema is gone.
        // Visibility of `read` is ungated: an image the model cannot see is
        // better surfaced as a tool error than hidden behind a schema it never
        // receives.
        add(shellExecuteDefinition())
        add(FileReadTool.definition())
        add(FileWriteTool.definition())
        add(FileEditTool.definition())
        if (browserEnabled) add(browserUseDefinition())
        if (screenEnabled) add(ScreenTool.definition())
        add(AndroidTool.definition())
        if (memoryEnabled) add(memoryDefinition())
        if (!isHelper && delegateEnabled) {
            add(subAgentTaskDefinition(rosterNames))
        }
    }

    /**
     * [T-android-tool-surface] memory_write and memory_get collapsed into one
     * tool: the two schemas shared `tool_title` and nothing else, and paid for
     * two tool headers per request. Mode is inferred from [MEMORY_SCOPE_WRITE]:
     * a `content` argument writes, its absence searches.
     */
    private fun memoryDefinition(): AgentToolDefinition = AgentToolDefinition(
        name = ToolNames.MEMORY,
        description = "Read and write persistent memory that survives across sessions. " +
            "Pass `content` to save a memory entry to today's daily log (YYYY-MM-DD.md); omit it " +
            "to search memory instead, optionally narrowed by `scope` and `keywords`. " +
            "GLOBAL.md is read-only — saved entries land in the daily log. " +
            "Never store passwords, API keys or tokens.",
        parameters = mapOf(
            "tool_title" to AgentToolParam("string", "A concise 5-10 word summary of what this tool call does, shown to the user. Use the same language as the user."),
            "content" to AgentToolParam("string", "The memory entry to save. Include this to write; omit it to search. Concise Markdown with a short heading (## Topic) and context about what was done or learned."),
            "scope" to AgentToolParam("string", "Search scope when not writing: 'daily' (today's log only, default) or 'all' (daily logs plus GLOBAL.md)."),
            "keywords" to AgentToolParam("string", "Space-separated keywords for fuzzy matching during a search (e.g. 'python preference'). All keywords must appear in a line or its surrounding context. Leave empty to return the whole file."),
        ),
        required = listOf("tool_title"),
        propertyOrdering = listOf("tool_title", "content", "scope", "keywords"),
    )

    /**
     * [T-sub-agents-v1] The one sub agent tool: delegate, and inspect or stop
     * what you delegated.
     *
     * Replaces `delegate_task` + `agent_status`. The separate status tool is
     * folded in as an `action`, so the model sees ONE tool — the shape iOS
     * settled on. Description text is byte-identical to iOS
     * `AIChatViewModel+ToolDefinitions.swift`; the two clients present one
     * contract to the same models, so these strings are copied, never
     * paraphrased.
     *
     * [rosterNames] are the enabled sub agents' names, rebuilt every turn
     * (the schema is not cached), so a rename takes effect on the next request
     * and the model cannot invent a name that would fail to resolve.
     *
     * `task` is deliberately NOT in [required]: it is required for
     * action=delegate and meaningless for status/cancel, which JSON Schema
     * cannot express here. The dispatcher rejects a delegate call with no task.
     */
    private fun subAgentTaskDefinition(rosterNames: List<String>): AgentToolDefinition =
        AgentToolDefinition(
            name = SubAgentDefinition.TOOL_NAME,
            description = "Start a sub agent on a self-contained task. It runs in its own hidden " +
                "session with a fresh context and its own tool loop, concurrently with you, and you " +
                "do not see this conversation. Write `task` as a complete standalone brief for " +
                "someone who just walked in: goal, constraints, where things are, what to return. " +
                "Costs a full model run per delegation, and at most 3 run at once. " +
                "wait=false (default) returns immediately with a job_id; the result arrives later as " +
                "a new message, so do not poll for it. wait=true blocks and returns the result inline. " +
                "Use action=status/steer/cancel/resume to manage jobs you already started.",
            parameters = mapOf(
            "tool_title" to AgentToolParam("string", "A concise 5-10 word summary shown to the user on the block. Use the same language as the user."),
            "action" to AgentToolParam("string", "delegate (default): start a sub agent on `task`. status: list this conversation's sub agents and their state. steer: correct a RUNNING one. cancel: stop one by `job_id`. resume: restart an interrupted one.", enumValues = listOf("delegate", "status", "steer", "cancel", "resume")),
            "task" to AgentToolParam("string", "action=delegate only, required. The complete, self-contained brief: goal, success criteria, relevant paths/URLs, constraints, and exactly what to return."),
            "agent" to AgentToolParam("string", "action=delegate only. Which sub agent runs this task; omit to use the general one.", enumValues = rosterNames),
            "model_choice" to AgentToolParam("string", "action=delegate only. Which model runs it: same_as_me (default), default_model for the hardest tasks, sub_model for mechanical well-bounded ones. Ignored unless the sub agent is set to Auto.", enumValues = listOf("same_as_me", "default_model", "sub_model")),
            "context" to AgentToolParam("string", "action=delegate only. Raw material to hand over verbatim (file excerpts, error output, a list of paths). Appended to the task."),
            "max_minutes" to AgentToolParam("integer", "action=delegate only. Wall-clock budget in minutes (default 10, max 60). On overrun it is stopped and returns what it produced with status=timeout."),
            "wait" to AgentToolParam("boolean", "action=delegate only. false (default): return at once with status=running, result arrives later as a new message. true: block and return the result inline — only when the next step cannot proceed without it."),
            "progress_report" to AgentToolParam("string", "action=delegate only. Mid-run progress messages. none (default), frequent (15s), moderate (1min). Ignored when wait=true.", enumValues = listOf("none", "frequent", "moderate")),
            "job_id" to AgentToolParam("string", "The job_id a previous delegate returned. Required for steer and cancel; omit on status to list all."),
            "message" to AgentToolParam("string", "action=steer only, required. The correction, as an instruction to the running sub agent. Read at its next turn, so it does not interrupt the current tool call."),
            "child_session_id" to AgentToolParam("string", "action=resume only, optional. One interrupted sub agent to restart; omit to resume all of them."),
            ),
            required = listOf("tool_title"),
            propertyOrdering = listOf(
                "tool_title", "action", "task", "agent", "model_choice", "context",
                "max_minutes", "wait", "progress_report", "job_id", "message",
                "child_session_id",
            ),
        )

    // [T-android-sandbox-only] There is no longer an environment to branch on:
    // the agent always runs in the PRoot Alpine sandbox. Privileged host work
    // goes through the `android` tool, which reaches the offload IPC layer.
    private fun shellExecuteDefinition(): AgentToolDefinition =
        AgentToolDefinition(
            name = ToolNames.BASH,
            description = "Execute a shell command in the Alpine Linux sandbox. " +
                "Runs via /bin/sh -c with stdout and stderr merged. Each invocation is a fresh " +
                "process — there is no shared terminal session. Output is truncated; the full text " +
                "is saved to a file whose path is returned when that happens. " +
                "The sandbox has no access to the host Android system: for am/pm/settings, " +
                "notifications, and privileged work use the `android` tool instead.",
            parameters = mapOf(
                "tool_title" to AgentToolParam("string", "A concise 5-10 word summary of what this tool call does, shown to the user (e.g. 'Install Python data analysis packages', 'List files in home directory'). Use the same language as the user."),
                "command" to AgentToolParam("string", "The shell command to execute. Supports multi-line commands directly — no special escaping needed. Keep under 1000 chars; for longer scripts, write to a file with file_write first, then run it."),
                // [T-android-parity-fixes] State the ceiling. It used to say only "use
                // a larger value" while values above 900 were silently cut to 900.
                "timeout" to AgentToolParam("integer", "Timeout in seconds (default: 900, maximum: 3600 — larger values are capped at 3600). Use a larger value for long-running commands like package installs."),
                "delay" to AgentToolParam("integer", "Delay in seconds before execution begins. The tool blocks the agent flow during this wait WITHOUT occupying the shell, so other concurrent tasks can use it. Use this instead of sleep commands to avoid resource contention."),
            ),
            required = listOf("tool_title", "command"),
            propertyOrdering = listOf("tool_title", "command", "timeout", "delay"),
        )

    // Aligned with iOS AIChatViewModel.swift browser_use definition
    private fun browserUseDefinition(): AgentToolDefinition = AgentToolDefinition(
        name = "browser_use",
        // [T-android-browser-tab-ownership] "up to 3" stopped being true once
        // the ceiling became dynamic (3 alone, +2 per active agent, capped at
        // 6), and an agent sees only its own tabs anyway — so the honest thing
        // to tell it is which tabs it may use, not a number that is now wrong.
        description = "Control a web browser with a few tabs (list_tabs shows the ones you may use). " +
            "Do NOT use this tool for minis:// action URLs (open_terminal, views, settings) — those are app deep links, use Markdown links in chat instead. " +
            "The browser supports both web URLs and minis:// resource URLs. Use minis:// URLs to preview session files (e.g. navigate to minis://workspace/index.html). " +
            "Sub-resources (JS, CSS, images, fonts) referenced via minis:// absolute paths or relative paths within HTML pages resolve correctly. " +
            "Use navigate to open URLs, screenshot to see the page (returns an image), " +
            "click/type to interact with elements, get_text/get_readable to extract content, " +
            "scroll to navigate long pages, scroll_and_collect to scroll through infinite-scroll/virtual-rendered pages (like Twitter/X timelines) and accumulate unique content items across scroll positions in a single call, " +
            "find_elements to discover interactive elements, " +
            "get_page_info for page metadata, get_backbone to get a structural overview of the page DOM as a simplified tree, " +
            "fetch to download files/resources using the page's session (returns metadata and a minis:// URL), " +
            "new_tab to open an additional tab, close_tab to close a tab, and list_tabs to see all open tabs. " +
            "Use set_viewport with viewport_width + viewport_height to override the viewport for the current session (e.g. before screenshotting a 1920×1080 HTML composition that would otherwise be cropped to the phone viewport); pass reset=true to drop the session override and fall back to the global browser setting. " +
            "Use get_cookies to retrieve cookies for the current page URL / current site root domain only (including HttpOnly cookies). get_cookies supports optional 'keywords' (filter by cookie name) and 'fuzzy' (true=contains match, false=exact match, default true). It returns only a summary and an offload env file path — raw cookie values are NOT included in the tool response. To reuse cookies in shell commands: `. /var/minis/offloads/env_cookies_xxx.sh && command`. You may define alias variables when needed. " +
            "Use set_cookies to write cookies into the current page's cookie store via the native cookie store (so even HttpOnly cookies, which JS cannot set, land). Pass a 'cookies' array of objects, each with name + value (required) and optional domain (defaults to the current page host), path (defaults to '/'), secure, http_only, and expires (Unix timestamp in seconds; omit for a session cookie). " +
            "Use wait_for_dom_stable to wait until the page DOM stops changing (useful after navigation or interactions that trigger async data loading — polls every 0.5s, resolves when mutation rate gradient is stable for 3+ intervals, default timeout 10s). " +
            "Use tab_id to target a specific tab (defaults to the most recently used tab).",
        parameters = mapOf(
            "tool_title" to AgentToolParam("string", "A concise 5-10 word summary of what this tool call does, shown to the user (e.g. 'Open Wikipedia homepage', 'Take screenshot of current page'). Use the same language as the user."),
            "action" to AgentToolParam("string", "The browser action to perform",
                enumValues = BrowserAction.allValues),
            "url" to AgentToolParam("string", "URL to navigate to (for navigate action) or resource to download (for fetch action)"),
            "selector" to AgentToolParam("string", "CSS selector for targeting elements (click, type, get_text, scroll, hover, find_elements). For scroll: specify a scrollable container to scroll (e.g. 'div.timeline'); if omitted, auto-detects the best scrollable element."),
            "text" to AgentToolParam("string", "Text to type (for type action)"),
            "coordinate_x" to AgentToolParam("integer", "X coordinate for click (alternative to selector)"),
            "coordinate_y" to AgentToolParam("integer", "Y coordinate for click (alternative to selector)"),
            "direction" to AgentToolParam("string", "Scroll direction", enumValues = listOf("up", "down")),
            "amount" to AgentToolParam("integer", "Scroll amount in pixels (default: 500)"),
            "script" to AgentToolParam("string", "JavaScript code to execute (for execute_js action). The script runs inside an async function wrapper — `await` and top-level `return` are both supported (e.g. `var r = await fetch(url); return await r.json()`)."),
            "user_agent" to AgentToolParam("string", "User agent profile to switch to", enumValues = listOf("desktop_chrome", "mobile_chrome")),
            "max_depth" to AgentToolParam("integer", "Maximum tree depth for get_backbone (default: 5)"),
            "scroll_count" to AgentToolParam("integer", "Number of scroll steps for scroll_and_collect (default: 10, max: 20). Each step scrolls by 'amount' pixels and waits for new content."),
            "item_selector" to AgentToolParam("string", "CSS selector for individual content items in scroll_and_collect (e.g. 'article', '[data-testid=\"tweet\"]'). If omitted, auto-detects repeated elements."),
            "tab_id" to AgentToolParam("integer", "Target tab ID (optional, defaults to your most recently used tab). Use list_tabs to see the tabs you may use; ids you did not receive from list_tabs/new_tab are rejected."),
            "keywords" to AgentToolParam("string", "Filter cookies by name (for get_cookies). A space-separated string or array of strings. With fuzzy=true (default), ALL keywords must appear in the cookie name (case-insensitive). With fuzzy=false, cookie name must exactly equal any one of the provided keywords (case-insensitive). Omit to return all cookies for the current site."),
            "fuzzy" to AgentToolParam("boolean", "Whether keyword matching is fuzzy (contains-all) or exact-any (for get_cookies, default: true)."),
            "cookies" to AgentToolParam("string", "For set_cookies: a JSON array of cookie objects to write. Pass it as a JSON array (a JSON-encoded string of the array is also accepted). Each object: {\"name\": str (required), \"value\": str (required), \"domain\": str (optional, defaults to current page host), \"path\": str (optional, defaults to \"/\"), \"secure\": bool (optional), \"http_only\": bool (optional — sets an HttpOnly cookie that JS cannot read/set), \"expires\": int (optional, Unix timestamp in seconds; omit for a session cookie)}. Field-name variants from common cookie exports are accepted: httpOnly (=http_only), expirationDate (=expires), sameSite, and case/camel variants — so you can paste cookies verbatim from browser extensions (EditThisCookie / Cookie-Editor) or Playwright/Puppeteer storage."),
            "timeout" to AgentToolParam("integer", "Timeout in seconds for wait_for_dom_stable (default: 10). The action polls every 0.5s and resolves when DOM mutation rate stabilizes."),
            "viewport_width" to AgentToolParam("integer", "Viewport width in CSS pixels for set_viewport (e.g. 1920). Required together with viewport_height unless reset=true."),
            "viewport_height" to AgentToolParam("integer", "Viewport height in CSS pixels for set_viewport (e.g. 1080). Required together with viewport_width unless reset=true."),
            "reset" to AgentToolParam("boolean", "For set_viewport: when true, clear the session-level viewport override and fall back to the global browser setting."),
            // [T-android-browser-full-page-schema] The capability was already
            // implemented end to end (BrowserActionInput parses `full_page`,
            // BrowserUseManager.screenshot stretches the WebView and caps at
            // MAX_FULL_PAGE_HEIGHT_PX) — it was simply never declared, so the
            // model had no way to ask for it. Wording adapted from iOS: the
            // mechanism differs (Android stretches the WebView's viewport
            // rather than resizing a WKWebView), the cap and the truncation
            // reporting are identical.
            "full_page" to AgentToolParam("boolean", "For screenshot: capture the entire scrollable page by temporarily stretching the browser viewport to document.documentElement.scrollHeight. Default false captures the visible viewport only. Capped at 32768px tall; when capped, the result text includes 'Truncated: true' and the original height, so you can scroll and capture the remainder separately."),
        ),
        required = listOf("tool_title", "action"),
        propertyOrdering = listOf("tool_title", "action", "tab_id", "url", "selector", "text", "coordinate_x", "coordinate_y", "direction", "amount", "scroll_count", "item_selector", "script", "user_agent", "max_depth", "keywords", "fuzzy", "cookies", "timeout", "viewport_width", "viewport_height", "reset", "full_page"),
    )
}
