package com.openminis.app.ui.chat

import com.openminis.app.tools.ToolNames

import com.openminis.app.R

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.NoteAdd
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.ui.graphics.Color

// [T-android-split-chat] Pure tool-label / duration / timestamp formatting
// helpers extracted verbatim from ChatScreen.kt. `internal` so the rest of the
// chat package (still in ChatScreen.kt) can call them across the file boundary.
// No logic change — code moved as-is.

internal val stepTimestampFormatter: java.text.SimpleDateFormat =
    java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)

internal fun formatStepTimestamp(epochMs: Long): String =
    stepTimestampFormatter.format(java.util.Date(epochMs))

// [T-step-timestamp v2 aa8b1128] Short "elapsed-or-final duration"
// label for the tool detail header. Cross-platform format contract:
//   < 60s   → "3s"
//   < 1h    → "2m30s" (drops the seconds suffix when seconds == 0)
//   ≥ 1h    → "1h12m"
// When `stillRunning` is true the result is suffixed "…" so the
// header reads "12s…" while the tool is in flight.
// Negative / non-positive values clamp to 0.
internal fun formatStepDuration(seconds: Long, stillRunning: Boolean): String {
    val safe = seconds.coerceAtLeast(0L)
    val base = when {
        safe < 60L -> "${safe}s"
        safe < 3600L -> {
            val m = safe / 60L
            val s = safe % 60L
            if (s == 0L) "${m}m" else "${m}m${s}s"
        }
        else -> {
            val h = safe / 3600L
            val m = (safe % 3600L) / 60L
            if (m == 0L) "${h}h" else "${h}h${m}m"
        }
    }
    return if (stillRunning) "$base…" else base
}

// Helper: tool accent color
internal fun toolAccentColor(toolName: String): Color = when (ToolNames.canonical(toolName)) {
    ToolNames.BASH -> Color(0xFF34C759)
    ToolNames.READ -> Color(0xFF32ADE6)
    ToolNames.WRITE -> Color(0xFF007AFF)
    ToolNames.EDIT -> Color(0xFFFF9500)
    ToolNames.BROWSER -> Color(0xFF007AFF)
    ToolNames.MEMORY -> Color(0xFFFF2D55)
    "web_search" -> Color(0xFF32ADE6)    // iOS: .cyan for search
    // [T-sub-agents-v1] Current name + the pre-rename one still in shipped transcripts.
    ToolNames.SUBAGENT -> HelperAccentStatic  // iOS: HelperAccent.color (electric violet)
    else -> Color(0xFF8E8E93)
}

// Helper: tool icon (iOS: distinct SF Symbols per tool type)
internal fun toolIconFor(toolName: String) = when (ToolNames.canonical(toolName)) {
    ToolNames.BASH -> Icons.Default.Terminal
    ToolNames.READ -> Icons.Default.Description         // iOS: doc.text
    ToolNames.WRITE -> Icons.AutoMirrored.Filled.NoteAdd   // iOS: doc.text.fill (filled variant)
    ToolNames.EDIT -> Icons.Default.EditNote             // iOS: square.and.pencil
    ToolNames.BROWSER -> Icons.Default.Language            // iOS: globe
    ToolNames.MEMORY -> Icons.Default.Psychology // iOS: brain.head.profile
    "web_search" -> Icons.Default.Search               // iOS: magnifyingglass
    ToolNames.SUBAGENT -> Icons.Default.Groups  // iOS: person.2.wave.2
    else -> Icons.Default.Build
}

// [T-android-overlay-two-row] View-layer twin of [toolIconFor], for callers
// that build a classic View hierarchy instead of Compose (the floating-window
// capsule in ToolOverlayController). An ImageVector cannot be handed to an
// ImageView, so the same vocabulary has to exist a second time as drawable
// resources — but it lives HERE, beside the Compose map, so the two are edited
// together and cannot silently diverge. Adding a tool means adding it to both.
@androidx.annotation.DrawableRes
internal fun toolIconResFor(toolName: String?): Int = when (ToolNames.canonical(toolName ?: "")) {
    ToolNames.BASH -> R.drawable.ic_tool_terminal
    ToolNames.READ -> R.drawable.ic_tool_description
    ToolNames.WRITE -> R.drawable.ic_tool_note_add
    ToolNames.EDIT -> R.drawable.ic_tool_edit_note
    ToolNames.BROWSER -> R.drawable.ic_tool_globe
    ToolNames.MEMORY -> R.drawable.ic_tool_psychology
    "web_search" -> R.drawable.ic_tool_search
    ToolNames.SUBAGENT -> R.drawable.ic_tool_groups
    else -> R.drawable.ic_tool_build
}

// [T-android-overlay-two-row] View-layer twin of [toolAccentColor]. Same
// literals, as a packed ARGB Int rather than a Compose Color.
@androidx.annotation.ColorInt
internal fun toolAccentColorInt(toolName: String?): Int = when (ToolNames.canonical(toolName ?: "")) {
    ToolNames.BASH -> 0xFF34C759.toInt()
    ToolNames.READ -> 0xFF32ADE6.toInt()
    ToolNames.WRITE -> 0xFF007AFF.toInt()
    ToolNames.EDIT -> 0xFFFF9500.toInt()
    ToolNames.BROWSER -> 0xFF007AFF.toInt()
    ToolNames.MEMORY -> 0xFFFF2D55.toInt()
    "web_search" -> 0xFF32ADE6.toInt()
    ToolNames.SUBAGENT -> 0xFFAF52DE.toInt()
    else -> 0xFF8E8E93.toInt()
}

/**
 * [T-android-overlay-two-row] Humanized fallback title for a tool call whose
 * model-supplied `tool_title` has not arrived (or was never sent).
 *
 * This mirrors ChatViewModel.friendlyToolTitle, which is what the chat stream's
 * tool pill shows in the same situation. Kept as a separate copy rather than
 * hoisting that private member, because ChatViewModel is a very large file that
 * several sessions edit concurrently; the vocabulary here is small and stable.
 */
internal fun friendlyToolTitleFor(toolName: String?): String = when (ToolNames.canonical(toolName ?: "")) {
    null, "" -> "Minis"
    ToolNames.BASH -> "Execute Shell"
    ToolNames.READ -> "Read File"
    ToolNames.WRITE -> "Write File"
    ToolNames.EDIT -> "Edit File"
    ToolNames.BROWSER -> "Browse Web"
    ToolNames.MEMORY -> "Write Memory"
    ToolNames.MEMORY -> "Read Memory"
    "web_search" -> "Search Web"
    else -> toolName.orEmpty()
        .split('_')
        .filter { it.isNotEmpty() }
        .joinToString(" ") { it.replaceFirstChar { ch -> ch.uppercase() } }
}

// Helper: tool display name for "Minis is using X"
internal fun toolDisplayName(toolName: String): String = when (ToolNames.canonical(toolName)) {
    ToolNames.BASH -> "terminal"
    ToolNames.READ -> "file reader"
    ToolNames.WRITE -> "file writer"
    ToolNames.EDIT -> "file editor"
    ToolNames.BROWSER -> "browser"
    ToolNames.MEMORY -> "memory"
    ToolNames.MEMORY -> "memory"
    "web_search" -> "search"
    ToolNames.SUBAGENT -> "agent"
    else -> toolName
}

/**
 * Full "Minis is …" label shown in the tool detail sheet's bottom bar.
 * Mirrors iOS ToolLiveSheet.toolTitle so the wording matches per tool.
 */
internal fun toolTitleLabel(toolName: String): String = when (ToolNames.canonical(toolName)) {
    ToolNames.BASH -> "Minis is using Shell"
    ToolNames.READ -> "Minis is reading File"
    ToolNames.WRITE -> "Minis is using Editor"
    ToolNames.EDIT -> "Minis is editing File"
    ToolNames.BROWSER -> "Minis is using Browser"
    ToolNames.MEMORY -> "Minis is using Memory"
    "web_search" -> "Minis is using Search"
    ToolNames.SUBAGENT -> "Minis is using an Agent"
    else -> "Minis is using ${toolDisplayName(toolName)}"
}

// Helper: format duration (iOS: < 1s → "0.1s", < 60s → "45s", >= 60s → "2m 10s")
internal fun formatToolDuration(ms: Long): String {
    val seconds = ms / 1000.0
    return when {
        seconds < 1 -> String.format("%.1fs", seconds)
        seconds < 60 -> String.format("%.0fs", seconds)
        else -> {
            val m = (seconds / 60).toInt()
            val s = (seconds % 60).toInt()
            "${m}m ${s}s"
        }
    }
}
