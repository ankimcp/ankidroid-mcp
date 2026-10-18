package ai.ankimcp.ankidroid.mcp

import ai.ankimcp.ankidroid.log.TunnelLog
import ai.ankimcp.ankidroid.tunnel.JsonRpcHandler
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Stateless MCP server: each call to [handle] takes one JSON-RPC message and returns the
 * response body, or null for notifications and client responses.
 *
 * No session, no SSE, no batching (batches get -32600), no server-initiated messages. The
 * negotiated protocol version of later requests comes from the `mcp-protocol-version` header
 * (Streamable HTTP); without it the spec says to assume 2025-03-26.
 */
class McpServer(
    private val registry: ToolRegistry,
    private val serverVersion: String,
    private val instructions: String = SERVER_INSTRUCTIONS,
    private val log: TunnelLog? = null,
    private val prompts: PromptRegistry = PromptRegistry(emptyList()),
    private val resources: ResourceRegistry = ResourceRegistry(emptyList()),
) : JsonRpcHandler {

    override suspend fun handle(body: String, headers: Map<String, String>): String? =
        handleMessage(body, headers)?.let { JsonRpc.json.encodeToString(JsonObject.serializer(), it) }

    suspend fun handleMessage(body: String, headers: Map<String, String> = emptyMap()): JsonObject? {
        val element = try {
            JsonRpc.json.parseToJsonElement(body)
        } catch (e: SerializationException) {
            return JsonRpc.error(JsonNull, JsonRpcErrors.PARSE_ERROR, "Parse error")
        }
        if (element is JsonArray) {
            return JsonRpc.error(JsonNull, JsonRpcErrors.INVALID_REQUEST, "Batch requests are not supported")
        }
        val message = element as? JsonObject
            ?: return JsonRpc.error(JsonNull, JsonRpcErrors.INVALID_REQUEST, "Invalid Request")

        val id = message["id"]
        val method = (message["method"] as? JsonPrimitive)?.takeIf { it.isString }?.content

        if (method == null) {
            // A response to a server-initiated request (we never send any) or garbage.
            if (id != null && ("result" in message || "error" in message)) return null
            return JsonRpc.error(id.validIdOrNull(), JsonRpcErrors.INVALID_REQUEST, "Invalid Request")
        }
        if ((message["jsonrpc"] as? JsonPrimitive)?.content != "2.0") {
            return if (id == null) null else JsonRpc.error(id.validIdOrNull(), JsonRpcErrors.INVALID_REQUEST, "Invalid Request")
        }
        if (id == null) {
            // Notification (notifications/initialized, notifications/cancelled, ...): nothing to do.
            return null
        }
        if (id !is JsonPrimitive || id is JsonNull) {
            return JsonRpc.error(JsonNull, JsonRpcErrors.INVALID_REQUEST, "Invalid Request: id must be a string or number")
        }

        val params = message["params"]
        if (params != null && params !is JsonObject) {
            return JsonRpc.error(id, JsonRpcErrors.INVALID_PARAMS, "params must be an object")
        }
        val paramsObj = params as? JsonObject ?: JsonObject(emptyMap())

        return try {
            JsonRpc.result(id, dispatch(method, paramsObj, protocolVersionOf(headers)))
        } catch (e: JsonRpcException) {
            JsonRpc.error(id, e.code, e.message ?: "Error")
        }
    }

    private suspend fun dispatch(method: String, params: JsonObject, protocolVersion: String): JsonElement =
        when (method) {
            "initialize" -> initialize(params)
            "ping" -> JsonObject(emptyMap())
            "tools/list" -> listTools(supportsStructuredOutput(protocolVersion))
            "tools/call" -> callTool(params, supportsStructuredOutput(protocolVersion))
            "resources/list" -> listResources()
            "resources/read" -> readResource(params)
            "resources/templates/list" -> buildJsonObject { put("resourceTemplates", JsonArray(emptyList())) }
            "prompts/list" -> listPrompts()
            "prompts/get" -> getPrompt(params)
            else -> throw JsonRpcException(JsonRpcErrors.METHOD_NOT_FOUND, "Method not found: $method")
        }

    /** Same shape as the add-on's FastMCP: `{name, title?, description, arguments: [{name, description?, required}]}`. */
    private fun listPrompts(): JsonObject = buildJsonObject {
        put(
            "prompts",
            buildJsonArray {
                prompts.prompts.forEach { prompt ->
                    add(
                        buildJsonObject {
                            put("name", prompt.name)
                            prompt.title?.let { put("title", it) }
                            put("description", prompt.description)
                            put(
                                "arguments",
                                buildJsonArray {
                                    prompt.arguments.forEach { argument ->
                                        add(
                                            buildJsonObject {
                                                put("name", argument.name)
                                                argument.description?.let { put("description", it) }
                                                put("required", argument.required)
                                            },
                                        )
                                    }
                                },
                            )
                        },
                    )
                }
            },
        )
    }

    /** `{description, messages: [{role: "user", content: {type: "text", text}}]}`, as FastMCP renders a prompt returning a string. */
    private fun getPrompt(params: JsonObject): JsonObject {
        val name = (params["name"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: throw JsonRpcException(JsonRpcErrors.INVALID_PARAMS, "Missing prompt name")
        val prompt = prompts[name] ?: throw JsonRpcException(JsonRpcErrors.INVALID_PARAMS, "Unknown prompt: $name")
        val arguments = when (val raw = params["arguments"]) {
            null, JsonNull -> emptyMap()
            is JsonObject -> raw.mapValues { (key, value) ->
                (value as? JsonPrimitive)?.takeIf { it.isString }?.content
                    ?: throw JsonRpcException(JsonRpcErrors.INVALID_PARAMS, "Prompt argument $key must be a string")
            }
            else -> throw JsonRpcException(JsonRpcErrors.INVALID_PARAMS, "arguments must be an object")
        }
        val required = prompt.arguments.filter { it.required && it.name !in arguments }.map { it.name }
        if (required.isNotEmpty()) {
            throw JsonRpcException(JsonRpcErrors.INVALID_PARAMS, "Missing required arguments: ${required.joinToString(", ")}")
        }
        val text = try {
            prompt.render(arguments)
        } catch (e: PromptArgumentException) {
            throw JsonRpcException(JsonRpcErrors.INVALID_PARAMS, "Error rendering prompt $name: ${e.message}")
        }
        log?.info("prompts/get $name")
        return buildJsonObject {
            put("description", prompt.description)
            put(
                "messages",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("role", "user")
                            put(
                                "content",
                                buildJsonObject {
                                    put("type", "text")
                                    put("text", text)
                                },
                            )
                        },
                    )
                },
            )
        }
    }

    /** `{name, title?, uri, description, mimeType}` per resource, FastMCP's field order. */
    private fun listResources(): JsonObject = buildJsonObject {
        put(
            "resources",
            buildJsonArray {
                resources.resources.forEach { resource ->
                    add(
                        buildJsonObject {
                            put("name", resource.name)
                            resource.title?.let { put("title", it) }
                            put("uri", resource.uri)
                            put("description", resource.description)
                            put("mimeType", resource.mimeType)
                        },
                    )
                }
            },
        )
    }

    private fun readResource(params: JsonObject): JsonObject {
        val uri = (params["uri"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: throw JsonRpcException(JsonRpcErrors.INVALID_PARAMS, "Missing resource uri")
        val resource = resources[uri] ?: throw JsonRpcException(JsonRpcErrors.RESOURCE_NOT_FOUND, "Unknown resource: $uri")
        log?.info("resources/read $uri")
        return buildJsonObject {
            put(
                "contents",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("uri", resource.uri)
                            put("mimeType", resource.mimeType)
                            put("text", resource.read())
                        },
                    )
                },
            )
        }
    }

    private fun initialize(params: JsonObject): JsonObject {
        val requested = (params["protocolVersion"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        // Unknown or missing: answer with our latest; the client disconnects if it can't use it.
        val version = requested?.takeIf { it in SUPPORTED_PROTOCOL_VERSIONS } ?: LATEST_PROTOCOL_VERSION
        return buildJsonObject {
            put("protocolVersion", version)
            put(
                "capabilities",
                buildJsonObject {
                    put("tools", JsonObject(emptyMap()))
                    if (prompts.prompts.isNotEmpty()) put("prompts", JsonObject(emptyMap()))
                    if (resources.resources.isNotEmpty()) put("resources", JsonObject(emptyMap()))
                },
            )
            put(
                "serverInfo",
                buildJsonObject {
                    put("name", SERVER_NAME)
                    put("title", "AnkiMCP")
                    put("version", serverVersion)
                },
            )
            put("instructions", instructions)
        }
    }

    private fun listTools(structuredOutput: Boolean): JsonObject = buildJsonObject {
        put(
            "tools",
            buildJsonArray {
                registry.tools.forEach { tool ->
                    add(
                        buildJsonObject {
                            put("name", tool.name)
                            put("title", tool.title)
                            put("description", tool.description)
                            put("inputSchema", tool.inputSchema)
                            if (structuredOutput) tool.outputSchema?.let { put("outputSchema", it) }
                            tool.annotations?.let { put("annotations", it) }
                        },
                    )
                }
            },
        )
    }

    private suspend fun callTool(params: JsonObject, structuredOutput: Boolean): JsonObject {
        val name = (params["name"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: throw JsonRpcException(JsonRpcErrors.INVALID_PARAMS, "Missing tool name")
        val tool = registry[name]
            ?: throw JsonRpcException(JsonRpcErrors.INVALID_PARAMS, "Unknown tool: $name")
        val arguments = when (val raw = params["arguments"]) {
            null, JsonNull -> JsonObject(emptyMap())
            is JsonObject -> raw
            else -> throw JsonRpcException(JsonRpcErrors.INVALID_PARAMS, "arguments must be an object")
        }
        log?.info("tools/call $name")
        val result = try {
            tool.call(arguments)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ToolResult.error("Error executing tool $name: ${e.message ?: e.javaClass.simpleName}")
        }
        if (result.isError) log?.warn("$name failed: ${result.content.firstText()}")
        return result.toJson(includeStructured = structuredOutput)
    }

    companion object {
        const val SERVER_NAME = "ankidroid-mcp"

        /** Oldest first; the last entry is the newest version this server implements. */
        val SUPPORTED_PROTOCOL_VERSIONS = listOf("2024-11-05", "2025-03-26", "2025-06-18")
        val LATEST_PROTOCOL_VERSION = SUPPORTED_PROTOCOL_VERSIONS.last()

        /** Assumed when a request has no `mcp-protocol-version` header (MCP 2025-06-18, transports). */
        const val DEFAULT_HEADER_PROTOCOL_VERSION = "2025-03-26"

        /** `structuredContent` and `outputSchema` were added in 2025-06-18. */
        const val STRUCTURED_OUTPUT_SINCE = "2025-06-18"

        private const val PROTOCOL_VERSION_HEADER = "mcp-protocol-version"

        /** Versions are `YYYY-MM-DD`, so string order is date order. */
        internal fun supportsStructuredOutput(protocolVersion: String): Boolean =
            protocolVersion >= STRUCTURED_OUTPUT_SINCE

        internal fun protocolVersionOf(headers: Map<String, String>): String =
            headers.entries.firstOrNull { it.key.equals(PROTOCOL_VERSION_HEADER, ignoreCase = true) }?.value?.trim()
                ?: DEFAULT_HEADER_PROTOCOL_VERSION

        /** Adapted from `SERVER_INSTRUCTIONS` in the add-on's mcp_server.py. */
        const val SERVER_INSTRUCTIONS =
            "This server works on the user's local AnkiDroid collection on their Android phone, " +
                "reached through AnkiDroid's content provider while the AnkiMCP app is connected. " +
                "Results reflect the phone's collection at call time; no tool call syncs with AnkiWeb, " +
                "so if the user also studies on other devices, suggest syncing in AnkiDroid first; the sync " +
                "tool only posts a notification the user must tap, and reports no result. " +
                "AI-driven review loop: get_due_cards -> present_card (question first, then " +
                "show_answer=true once the user has answered) -> rate_card, with the user confirming the " +
                "rating after seeing the answer; never repeat a rate_card call that may have gone through. " +
                "The anki://query-syntax resource documents the search syntax find_notes accepts; the " +
                "review_session and twenty_rules prompts guide a review session and writing good cards. " +
                "Create, edit or delete only the notes, decks, note types and media the user asked for."
    }
}

private fun JsonElement?.validIdOrNull(): JsonElement =
    if (this is JsonPrimitive && this !is JsonNull) this else JsonNull

private fun JsonArray.firstText(): String =
    (firstOrNull() as? JsonObject)?.get("text")?.let { (it as? JsonPrimitive)?.content } ?: ""
