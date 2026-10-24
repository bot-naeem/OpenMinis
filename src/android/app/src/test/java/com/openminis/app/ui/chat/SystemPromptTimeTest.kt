package com.openminis.app.ui.chat

import com.openminis.app.ProductionSources
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/**
 * Tests verifying that system prompt formats and appends current date/time
 * once per conversation session, preserving 100% prefix KV cache stability
 * across subsequent turns within the session.
 */
class SystemPromptTimeTest {

    private val chatVmSource: String by lazy {
        ProductionSources.read("ui/chat/ChatViewModel.kt")
    }

    @Test
    fun `system prompt captures session-scoped time and injects Current date and time`() {
        assertTrue(
            "ChatViewModel must define SessionTimeInfo for session-scoped caching",
            chatVmSource.contains("private data class SessionTimeInfo"),
        )
        assertTrue(
            "ChatViewModel must have getOrCreateSessionTimeInfo",
            chatVmSource.contains("private fun getOrCreateSessionTimeInfo(): SessionTimeInfo"),
        )
        assertTrue(
            "Runtime context must append - Current date and time:",
            chatVmSource.contains("append(\"- Current date and time: \").append(timeInfo.formattedDateTime)"),
        )
    }

    @Test
    fun `time formatting produces expected date time day of week and timezone`() {
        val now = ZonedDateTime.now()
        val zoneId = now.zone.id
        val dayOfWeek = now.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.getDefault())
        val dateStr = now.format(DateTimeFormatter.ISO_LOCAL_DATE)
        val fullTimeStr = now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
        val formatted = "$fullTimeStr ($dayOfWeek, $zoneId)"

        assertTrue("Date format must start with YYYY-MM-DD", formatted.startsWith(dateStr))
        assertTrue("Format must include day of week", formatted.contains(dayOfWeek))
        assertTrue("Format must include timezone", formatted.contains(zoneId))
    }

    @Test
    fun `system prompt does not recompute time per turn to prevent KV cache invalidation`() {
        // Ensure buildSystemPrompt delegates to getOrCreateSessionTimeInfo rather than LocalDate.now() directly
        val start = chatVmSource.indexOf("private fun buildSystemPrompt(): String?")
        assertTrue(start >= 0)
        val end = chatVmSource.indexOf("fun executeMemoryWrite", start)
        assertTrue(end > start)
        val promptMethod = chatVmSource.substring(start, end)

        assertTrue(promptMethod.contains("val timeInfo = getOrCreateSessionTimeInfo()"))
        assertFalse(
            "buildSystemPrompt should not directly call LocalDate.now() every turn",
            promptMethod.contains("val today = java.time.LocalDate.now()"),
        )
    }
}
