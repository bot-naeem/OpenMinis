package com.openminis.app.tools

/**
 * The agent tool surface.
 *
 * Names are short and single-word so the schema stays cheap: every one of them
 * is re-sent on every request, and the fixed cost of the tool list is paid out
 * of the context window before the user has typed anything.
 *
 * [canonical] maps the pre-rename names onto the current ones. Anything that
 * dispatches, formats, or gates on a tool name must run the raw name through it
 * first: a transcript written before the rename still carries `file_read`, and
 * a model replaying an old turn can still emit it.
 */
object ToolNames {

    // ── The surface ────────────────────────────────────────────────────────
    const val BASH = "bash"
    const val READ = "read"
    const val WRITE = "write"
    const val EDIT = "edit"
    const val BROWSER = "browser_use"
    const val ANDROID = "android"
    const val MEMORY = "memory"
    const val SUBAGENT = "subagent"

    /** Pre-rename name → current name. */
    private val ALIASES: Map<String, String> = mapOf(
        "shell_execute" to BASH,
        "file_read" to READ,
        "file_write" to WRITE,
        "file_edit" to EDIT,
        // read_image was folded into read: the handler now picks between text
        // and pixels from the path's extension, so an old call resolves to the
        // same tool and is re-routed correctly on replay.
        "read_image" to READ,
        "memory_write" to MEMORY,
        "memory_get" to MEMORY,
        // [T-sub-agents-v1] delegate_task + agent_status were folded into
        // subagent_task before this rename; both still arrive from old
        // transcripts and both mean "subagent".
        "subagent_task" to SUBAGENT,
        "delegate_task" to SUBAGENT,
        "agent_status" to SUBAGENT,
    )

    /**
     * Every tool this app has ever published, keyed by its name with case and
     * underscores squashed out.
     *
     * [MANGLED] below is the reason for the squashing rather than a plain
     * lookup: see its comment. `browser_use` and `browserUse` are the same
     * key, which is the whole point — a relay that camel-cases the name still
     * resolves.
     */
    private val CANONICAL_BY_SQUASHED: Map<String, String> =
        (listOf(BASH, READ, WRITE, EDIT, BROWSER, ANDROID, MEMORY, SUBAGENT) + ALIASES.keys)
            .associateBy { squash(it) }
            .mapKeys { (k, _) -> k }
            .mapValues { (_, canonical) -> ALIASES[canonical] ?: canonical }

    /**
     * A tool name rewritten by an OpenAI-compatible relay.
     *
     * Some gateways merge tools from several sources into one request — the
     * client's, the gateway's own built-ins, any MCP servers it fronts — and
     * namespace each one to keep them from colliding. A client tool then
     * arrives at the model as `Compat<Name><hash>`, and if the relay does not
     * map the name back on the way out, the model's tool_call comes back under
     * that mangled spelling. Without this, dispatch falls through to "Unknown
     * tool".
     *
     * The split is genuinely ambiguous and a regex cannot settle it: in
     * `CompatBrowserUse0b1309` the trailing `e` is a hex digit, so the longest
     * hex run is `e0b1309` and a naive pattern yields `BrowserUs`. Instead the
     * stem is grown a character at a time and the first reading that names one
     * of our tools wins — which is the only reading that can be right.
     */
    private val TRAILING_HEX = Regex("([0-9a-f]{4,})$")

    /** Lowercase, underscores dropped: `browser_use` and `BrowserUse` collide. */
    private fun squash(name: String): String =
        name.lowercase().replace("_", "")

    /**
     * Every spelling of [candidate] that could be ours, namespace-stripped.
     *
     * A relay's prefix length is unknown and cannot be matched greedily:
     * stripping `^[A-Za-z]+(?=[A-Z])` from `CompatBrowserUse` takes
     * `CompatBrowser`, not `Compat`, because both are followed by a capital.
     * Trying each capital in turn sidesteps the question — the first reading
     * that names a real tool is the only one that can be right.
     */
    private fun candidatesFor(candidate: String): List<String> =
        buildList {
            add(candidate)
            for (i in candidate.indices) {
                if (candidate[i].isUpperCase()) add(candidate.substring(i))
            }
        }

    /** Resolve a raw tool name to its current name. Unknown names pass through. */
    fun canonical(name: String): String {
        ALIASES[name]?.let { return it }
        CANONICAL_BY_SQUASHED[name]?.let { return it }
        val hex = TRAILING_HEX.find(name) ?: return name
        val stem = name.substring(0, hex.range.first)
        // Longest hex run first, then hand each hex character back to the stem:
        // in `CompatBrowserUse0b1309` the `e` ending "Use" is itself a hex
        // digit, so the run is `e0b1309` and the stem is `CompatBrowserUs`.
        for (keep in hex.value.indices.reversed()) {
            val head = stem + hex.value.take(keep)
            for (candidate in candidatesFor(head)) {
                val hit = CANONICAL_BY_SQUASHED[squash(candidate)]
                if (hit != null) return hit
            }
        }
        return name
    }

    /** True when [name] is a current, legacy, or relay-mangled spelling. */
    fun matches(name: String, canonicalName: String): Boolean = canonical(name) == canonicalName
}
