package com.openminis.app.tools

import com.openminis.app.data.model.AgentToolDefinition
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test

/**
 * Dumps the actual wire payload for the current tool surface.
 *
 * Not an assertion suite — this exists so the thing the model really receives
 * can be inspected with `./gradlew :app:testDebugUnitTest --tests '*ToolSurfaceDumpTest*' -i`
 * rather than reconstructed by hand from the source, which is how a schema
 * drifts unnoticed. Everything the schema costs is also printed per tool, so a
 * regression that doubles a description is visible in the test log.
 */
class ToolSurfaceDumpTest {

    private val defaults: List<AgentToolDefinition> = AgentTools.makeAgentTools()

    private fun anthropicPayload(): JSONArray = JSONArray().apply {
        defaults.forEach { put(it.toAnthropicJson()) }
    }

    private fun openAiPayload(): JSONArray = JSONArray().apply {
        defaults.forEach { put(it.toOpenAIJson()) }
    }

    @Test
    fun `dump the tool surface`() {
        println("\n================ TOOL SURFACE ================")
        println("count = ${defaults.size}")
        println()
        println("%-14s %-10s %7s %8s  %s".format("name", "params", "desc", "total", "required"))
        println("-".repeat(88))
        var descTotal = 0
        var wireTotal = 0
        for (t in defaults.sortedBy { it.name }) {
            val json = t.toAnthropicJson()
            val desc = t.description.length
            val total = json.toString().length
            descTotal += desc
            wireTotal += total
            println(
                "%-14s %-10d %7d %8d  %s".format(
                    t.name, t.parameters.size, desc, total, t.required.joinToString(",")
                )
            )
        }
        println("-".repeat(88))
        println("%-14s %-10s %7d %8d".format("TOTAL", "", descTotal, wireTotal))
        println("estimated tokens (chars/4) ~ ${wireTotal / 4}")
        println()

        println("---- payload keys per provider ----")
        println("anthropic : name, description, input_schema{type,properties,required}")
        println("openai    : type:function, function{name,description,parameters{type,properties,required}}")
        println("gemini    : name, description, parameters{type,properties,required,propertyOrdering}")
        println()

        println("---- smallest tool, verbatim (anthropic) ----")
        val smallest = defaults.minBy { it.toAnthropicJson().toString().length }
        println(JSONObject(smallest.toAnthropicJson().toString()).toString(2))
        println()

        println("---- largest tool: description head + param list ----")
        val largest = defaults.maxBy { it.toAnthropicJson().toString().length }
        println("name: ${largest.name}")
        println("desc (${largest.description.length} chars), first 300:")
        println(largest.description.take(300))
        println("params (${largest.parameters.size}): ${largest.parameters.keys.joinToString(", ")}")
        println("required: ${largest.required}")
        println()
        println("============================================\n")
    }

    @Test
    fun `dump the raw json to a file next to the build dir`() {
        val out = java.io.File("build/tool-surface.json")
        out.parentFile?.mkdirs()
        val payload = JSONObject().apply {
            put("count", defaults.size)
            put("anthropic", anthropicPayload())
            put("openai", openAiPayload())
        }
        out.writeText(payload.toString(2))
        println("wrote ${out.absolutePath} (${out.length()} bytes)")
    }
}
