package com.openminis.app.tools

import android.content.Context
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam
import com.openminis.app.sandbox.PRootKernel
import org.json.JSONObject
import java.io.File

object FileReadTool {
    const val NAME = ToolNames.READ

    fun definition(): AgentToolDefinition = AgentToolDefinition(
        name = NAME,
        description = "Read a file. Faster than a shell command — no process, no shell overhead. " +
            "Text files come back as content. Images (jpg, png, gif, webp, bmp) come back as an " +
            "attachment the model can look at directly. Output is truncated at max_length chars, " +
            "default 15000 and hard-capped at 80000; when truncated the header ends with " +
            "next_offset=N — pass that as offset to continue. Binary files are rejected.",
        parameters = mapOf(
            "tool_title" to AgentToolParam("string", "A concise 5-10 word summary of what this tool call does, shown to the user (e.g. 'Read Python script contents', 'Check system configuration file'). Use the same language as the user."),
            "path" to AgentToolParam("string", "Absolute Linux path to read (e.g. /var/minis/workspace/data.csv)"),
            // [T-android-tool-surface] read_image folded into read: the model picks a path and
            // the handler decides whether that path is text or pixels. `prompt` only means
            // something for an image.
            "prompt" to AgentToolParam("string", "For images only: what to look for in them. Ignored for text files."),
            "offset" to AgentToolParam("integer", "1-based line number to start reading from (default: 1). Ignored when direction is 'tail'. If a previous read was truncated, its header ends with next_offset=N — pass that as offset to continue from where it stopped."),
            "lines" to AgentToolParam("integer", "Maximum number of lines to return (default: all lines up to max_length)"),
            "max_length" to AgentToolParam("integer", "Maximum character length of returned content (default: 15000)"),
            "direction" to AgentToolParam("string", "Read direction: 'head' (from start, default) or 'tail' (from end of file)"),
        ),
        required = listOf("tool_title", "path"),
        propertyOrdering = listOf("tool_title", "path", "prompt", "offset", "lines", "direction", "max_length"),
    )

    suspend fun execute(argsJson: String, sessionId: String, context: Context): ToolExecutionResult {
        return try {
            val args = JSONObject(argsJson)
            val path = args.optString("path", "")
            val toolTitle = args.optString("tool_title", NAME)
            val offset = args.optInt("offset", 1).coerceAtLeast(1)
            // T-FILEREAD-CAP: hard upper bound on returned content length.
            // Pre-cap, the agent could ask for `max_length=1_000_000` and we
            // would happily inline a 400 KB base64 image into a tool_result —
            // which then renders as a single user-message bubble and locks
            // up Compose's StaticLayout / LineBreaker for tens of seconds
            // (see HangDetector report for session
            // e84882d7-2087-47f8-9300-ff2c897fe0b4: 820 KB partsJson, 43 s
            // hang in nComputeLineBreaks). Cap at 80 KB regardless of
            // requested value; the truncation tail below tells the agent the
            // full file size so it can paginate with offset/lines if needed.
            // iOS mirrors this cap in AIChatViewModel.executeFileRead.
            val MAX_LENGTH_HARD_CAP = 80_000
            val maxLength = args.optInt("max_length", 15000).coerceAtMost(MAX_LENGTH_HARD_CAP)
            val direction = args.optString("direction", "head")

            if (path.isBlank()) {
                return ToolExecutionResult("Error: 'path' is required", false, toolTitle = toolTitle)
            }

            // T123: per-session resolver — see FileWriteTool for rationale.
            // [T-android-sandbox-only] Reads get the privileged-path check but
            // not the protected one: refusing to READ /system would break
            // ordinary diagnostics (`getprop`-style inspection the model does
            // constantly), and reading cannot brick anything. Another app's
            // private data is still a real disclosure, so it prompts.
            if (FileToolGuard.decide(path) == FileToolGuard.Decision.NEEDS_PERMISSION &&
                !FileToolGuard.authorizeRead(path, NAME, sessionId)
            ) {
                return ToolExecutionResult(
                    "Error: the user declined the read of $path. Do not retry it in this conversation.",
                    false,
                    toolTitle = toolTitle,
                )
            }

            val file = PRootKernel.resolveSessionHostPath(sessionId, path, context)
                ?: return ToolExecutionResult("Error: Cannot resolve path: $path", false, toolTitle = toolTitle)

            if (!file.exists() || !file.canRead()) {
                // Privileged fallback: if Root is granted and the file exists on the host
                // (e.g. inside another app's /data/data/ directory), read it via su.
                if (RootAccess.isRootGranted()) {
                    val escaped = path.replace("'", "'\\''")
                    val suCheck = RootAccess.runSu("test -e '$escaped'", timeoutMs = 3000)
                    if (suCheck?.exitCode == 0) {
                        val suIsDir = RootAccess.runSu("test -d '$escaped'", timeoutMs = 3000)
                        if (suIsDir?.exitCode == 0) {
                            return ToolExecutionResult("Error: Path is a directory: $path", false, toolTitle = toolTitle)
                        }
                        val suRead = RootAccess.runSu("cat '$escaped'", timeoutMs = 10_000)
                        if (suRead != null && suRead.exitCode == 0) {
                            val allLines = suRead.stdout.lines()
                            val size = suRead.stdout.toByteArray(Charsets.UTF_8).size.toLong()
                            return formatTextContent(allLines, size, path, args, offset, maxLength, direction, toolTitle)
                        }
                    }
                }
                if (!file.exists()) {
                    return ToolExecutionResult("Error: File not found: $path", false, toolTitle = toolTitle)
                }
                return ToolExecutionResult("Error: Permission denied: $path", false, toolTitle = toolTitle)
            }

            if (file.isDirectory) {
                return ToolExecutionResult("Error: Path is a directory: $path", false, toolTitle = toolTitle)
            }

            val size = file.length()

            // Binary detection: check first 8192 bytes for null bytes
            val isBinary = file.inputStream().use { input ->
                val buf = ByteArray(minOf(8192, size.toInt()))
                val read = input.read(buf)
                if (read > 0) buf.take(read).any { it == 0.toByte() } else false
            }

            if (isBinary) {
                return ToolExecutionResult(
                    "[$path | $size bytes | binary file — cannot display contents]",
                    true, toolTitle = toolTitle
                )
            }

            val allLines = file.readLines()
            return formatTextContent(allLines, size, path, args, offset, maxLength, direction, toolTitle)
        } catch (e: Exception) {
            ToolExecutionResult("Error reading file: ${e.message}", false)
        }
    }

    private fun formatTextContent(
        allLines: List<String>,
        size: Long,
        path: String,
        args: JSONObject,
        offset: Int,
        maxLength: Int,
        direction: String,
        toolTitle: String,
    ): ToolExecutionResult {
        val totalLines = allLines.size
        val requestedLines = if (args.has("lines")) args.optInt("lines") else null

        val selectedLines = if (direction == "tail") {
            val count = requestedLines ?: totalLines
            val start = (totalLines - count).coerceAtLeast(0)
            allLines.subList(start, totalLines)
        } else {
            val start = (offset - 1).coerceIn(0, totalLines)
            val end = if (requestedLines != null) {
                (start + requestedLines).coerceAtMost(totalLines)
            } else {
                totalLines
            }
            allLines.subList(start, end)
        }

        val showStart = if (direction == "tail") {
            (totalLines - selectedLines.size) + 1
        } else {
            offset
        }
        val showEnd = showStart + selectedLines.size - 1

        var content = selectedLines.joinToString("\n")

        var effectiveStart = showStart
        var effectiveEnd = showEnd
        var nextOffset: Int? = null
        var wasTruncated = false
        if (content.length > maxLength) {
            wasTruncated = true
            if (direction == "tail") {
                content = content.takeLast(maxLength)
                val firstNewline = content.indexOf('\n')
                if (firstNewline in 0 until content.length - 1) {
                    content = content.substring(firstNewline + 1)
                }
                effectiveStart = effectiveEnd - content.count { it == '\n' }
            } else {
                content = content.take(maxLength)
                val lastNewline = content.lastIndexOf('\n')
                if (lastNewline > 0) content = content.substring(0, lastNewline)
                effectiveEnd = showStart + content.count { it == '\n' }
                if (effectiveEnd < totalLines) nextOffset = effectiveEnd + 1
            }
        }

        var header = "[$path | $size bytes | $totalLines lines | " +
            "showing $effectiveStart-$effectiveEnd of $totalLines"
        if (wasTruncated) {
            header += " | truncated at $maxLength chars"
            header += if (nextOffset != null) ", next_offset=$nextOffset"
                      else ", retry with a smaller lines value"
        }
        header += "]"
        return ToolExecutionResult("$header\n$content", true, toolTitle = toolTitle)
    }
}
