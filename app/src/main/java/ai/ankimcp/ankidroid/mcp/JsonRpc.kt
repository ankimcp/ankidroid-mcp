package ai.ankimcp.ankidroid.mcp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** JSON-RPC 2.0 error codes used by the server. */
object JsonRpcErrors {
    const val PARSE_ERROR = -32700
    const val INVALID_REQUEST = -32600
    const val METHOD_NOT_FOUND = -32601
    const val INVALID_PARAMS = -32602
    const val INTERNAL_ERROR = -32603

    /** MCP: `resources/read` for a URI the server does not have. */
    const val RESOURCE_NOT_FOUND = -32002

    /** Relay convention for request timeouts (paired with HTTP 504). */
    const val REQUEST_TIMEOUT = -32004
}

/** Thrown by method handlers to produce a JSON-RPC error response. */
class JsonRpcException(val code: Int, message: String) : Exception(message)

object JsonRpc {
    val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
    }

    fun result(id: JsonElement, result: JsonElement): JsonObject = buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", id)
        put("result", result)
    }

    fun error(id: JsonElement?, code: Int, message: String): JsonObject = buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", id ?: JsonNull)
        put(
            "error",
            buildJsonObject {
                put("code", code)
                put("message", message)
            },
        )
    }
}
