package ai.ankimcp.ankidroid.mcp

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** An MCP tool. [inputSchema] is a JSON Schema object (`{"type":"object",...}`). */
interface Tool {
    val name: String
    val title: String
    val description: String
    val inputSchema: JsonObject

    /**
     * JSON Schema of [ToolResult.structuredContent], or null if the tool returns none. Only
     * advertised to clients on protocol 2025-06-18 or later.
     */
    val outputSchema: JsonObject? get() = null

    /** MCP tool annotations (readOnlyHint etc.), or null to omit. */
    val annotations: JsonObject? get() = null

    /**
     * Runs the tool. Expected failures should be returned as [ToolResult.error]; thrown
     * exceptions are converted to `isError` results by [McpServer].
     */
    suspend fun call(arguments: JsonObject): ToolResult
}

/** Result of `tools/call`. */
data class ToolResult(
    val content: JsonArray,
    val structuredContent: JsonObject? = null,
    val isError: Boolean = false,
) {
    /** [includeStructured] is false for clients older than 2025-06-18, which predate `structuredContent`. */
    fun toJson(includeStructured: Boolean = true): JsonObject = buildJsonObject {
        put("content", content)
        if (includeStructured) structuredContent?.let { put("structuredContent", it) }
        put("isError", isError)
    }

    companion object {
        fun text(text: String): ToolResult = ToolResult(textContent(text))

        /** Structured result; also serialised as text for clients that ignore structuredContent. */
        fun structured(value: JsonObject): ToolResult =
            ToolResult(textContent(JsonRpc.json.encodeToString(JsonObject.serializer(), value)), value)

        fun error(message: String): ToolResult = ToolResult(textContent(message), isError = true)

        private fun textContent(text: String) = buildJsonArray {
            add(
                buildJsonObject {
                    put("type", "text")
                    put("text", text)
                },
            )
        }
    }
}

/** Read-only tool annotations for tools that only read the local collection. */
fun readOnlyAnnotations(title: String): JsonObject = buildJsonObject {
    put("title", title)
    put("readOnlyHint", true)
    put("destructiveHint", false)
    put("openWorldHint", false)
}

class ToolRegistry(tools: List<Tool>) {
    private val byName: Map<String, Tool> = tools.associateBy { it.name }.also {
        require(it.size == tools.size) { "Duplicate tool names" }
    }

    val tools: List<Tool> = tools.toList()

    operator fun get(name: String): Tool? = byName[name]
}
