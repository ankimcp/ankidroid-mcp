package ai.ankimcp.ankidroid.mcp

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class McpServerTest {
    private val echoTool = object : Tool {
        override val name = "echo"
        override val title = "Echo"
        override val description = "Echoes its input"
        override val inputSchema = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject { put("text", buildJsonObject { put("type", "string") }) })
        }
        override val outputSchema = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject { put("text", buildJsonObject { put("type", "string") }) })
        }
        override val annotations = readOnlyAnnotations(title)

        override suspend fun call(arguments: JsonObject): ToolResult {
            val text = (arguments["text"] as? JsonPrimitive)?.content ?: return ToolResult.error("text is required")
            return ToolResult.structured(buildJsonObject { put("text", text) })
        }
    }

    private val throwingTool = object : Tool {
        override val name = "boom"
        override val title = "Boom"
        override val description = "Always throws"
        override val inputSchema = buildJsonObject { put("type", "object") }
        override suspend fun call(arguments: JsonObject): ToolResult = throw IllegalStateException("kaboom")
    }

    private val server = McpServer(ToolRegistry(listOf(echoTool, throwingTool)), serverVersion = "0.1.0")

    private val v20250618 = mapOf("mcp-protocol-version" to "2025-06-18")

    private suspend fun call(body: String, headers: Map<String, String> = emptyMap()): JsonObject? =
        server.handleMessage(body, headers)

    private fun JsonObject.result() = this["result"]!!.jsonObject

    private fun JsonObject.errorCode() = this["error"]!!.jsonObject["code"]!!.jsonPrimitive.int

    @Test
    fun `initialize echoes a supported protocol version`() = runTest {
        val response = call(
            """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-03-26",
               "capabilities":{},"clientInfo":{"name":"claude-ai","version":"0.1.0"}}}""",
        )!!
        val result = response.result()
        assertEquals("2.0", response["jsonrpc"]!!.jsonPrimitive.content)
        assertEquals(1, response["id"]!!.jsonPrimitive.int)
        assertEquals("2025-03-26", result["protocolVersion"]!!.jsonPrimitive.content)
        assertEquals(JsonObject(emptyMap()), result["capabilities"]!!.jsonObject["tools"])
        assertEquals("ankidroid-mcp", result["serverInfo"]!!.jsonObject["name"]!!.jsonPrimitive.content)
        assertEquals("0.1.0", result["serverInfo"]!!.jsonObject["version"]!!.jsonPrimitive.content)
        assertTrue(result["instructions"]!!.jsonPrimitive.content.contains("AnkiDroid"))
    }

    @Test
    fun `initialize answers unknown, newer or missing versions with the latest supported one`() = runTest {
        assertEquals("2025-06-18", McpServer.LATEST_PROTOCOL_VERSION)
        for (requested in listOf("\"1999-01-01\"", "\"2025-11-25\"", "\"2099-01-01\"")) {
            val response = call("""{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":$requested}}""")!!
            assertEquals(requested, "2025-06-18", response.result()["protocolVersion"]!!.jsonPrimitive.content)
        }

        val missing = call("""{"jsonrpc":"2.0","id":2,"method":"initialize"}""")!!
        assertEquals("2025-06-18", missing.result()["protocolVersion"]!!.jsonPrimitive.content)
    }

    @Test
    fun `tools list exposes name, schema and annotations`() = runTest {
        val tools = call("""{"jsonrpc":"2.0","id":"a","method":"tools/list"}""", v20250618)!!.result()["tools"]!!.jsonArray
        assertEquals(2, tools.size)
        val echo = tools[0].jsonObject
        assertEquals("echo", echo["name"]!!.jsonPrimitive.content)
        assertEquals("Echo", echo["title"]!!.jsonPrimitive.content)
        assertEquals("object", echo["inputSchema"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("object", echo["outputSchema"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertTrue(echo["annotations"]!!.jsonObject["readOnlyHint"]!!.jsonPrimitive.boolean)
        assertNull(tools[1].jsonObject["annotations"])
        assertNull(tools[1].jsonObject["outputSchema"])
    }

    @Test
    fun `outputSchema is only listed for 2025-06-18 and later`() = runTest {
        val noHeader = call("""{"jsonrpc":"2.0","id":1,"method":"tools/list"}""")!!.result()["tools"]!!.jsonArray
        assertNull(noHeader[0].jsonObject["outputSchema"])

        val old = call(
            """{"jsonrpc":"2.0","id":1,"method":"tools/list"}""",
            mapOf("mcp-protocol-version" to "2025-03-26"),
        )!!.result()["tools"]!!.jsonArray
        assertNull(old[0].jsonObject["outputSchema"])
    }

    @Test
    fun `string ids are preserved`() = runTest {
        val response = call("""{"jsonrpc":"2.0","id":"abc-1","method":"ping"}""")!!
        assertEquals(JsonPrimitive("abc-1"), response["id"])
        assertEquals(JsonObject(emptyMap()), response["result"])
    }

    @Test
    fun `tools call success returns content and structuredContent on 2025-06-18`() = runTest {
        val result = call(
            """{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"echo","arguments":{"text":"hi"}}}""",
            mapOf("MCP-Protocol-Version" to "2025-06-18"),
        )!!.result()
        assertFalse(result["isError"]!!.jsonPrimitive.boolean)
        assertEquals("hi", result["structuredContent"]!!.jsonObject["text"]!!.jsonPrimitive.content)
        val content = result["content"]!!.jsonArray[0].jsonObject
        assertEquals("text", content["type"]!!.jsonPrimitive.content)
        assertEquals("""{"text":"hi"}""", content["text"]!!.jsonPrimitive.content)
    }

    @Test
    fun `structuredContent is omitted for older or unannounced protocol versions`() = runTest {
        val body = """{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"echo","arguments":{"text":"hi"}}}"""

        val noHeader = call(body)!!.result()
        assertNull(noHeader["structuredContent"])
        assertEquals("""{"text":"hi"}""", noHeader["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content)

        val old = call(body, mapOf("mcp-protocol-version" to "2024-11-05"))!!.result()
        assertNull(old["structuredContent"])
    }

    @Test
    fun `tools call reports tool errors with isError`() = runTest {
        val result = call(
            """{"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"echo","arguments":{}}}""",
        )!!.result()
        assertTrue(result["isError"]!!.jsonPrimitive.boolean)
        assertEquals("text is required", result["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content)
    }

    @Test
    fun `tools call converts thrown exceptions to isError results`() = runTest {
        val result = call("""{"jsonrpc":"2.0","id":5,"method":"tools/call","params":{"name":"boom"}}""")!!.result()
        assertTrue(result["isError"]!!.jsonPrimitive.boolean)
        assertTrue(result["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content.contains("kaboom"))
    }

    @Test
    fun `tools call with unknown tool is invalid params`() = runTest {
        val response = call("""{"jsonrpc":"2.0","id":6,"method":"tools/call","params":{"name":"nope"}}""")!!
        assertEquals(JsonRpcErrors.INVALID_PARAMS, response.errorCode())
        assertEquals(6, response["id"]!!.jsonPrimitive.int)
    }

    @Test
    fun `unknown method is method not found`() = runTest {
        val response = call("""{"jsonrpc":"2.0","id":7,"method":"sampling/createMessage"}""")!!
        assertEquals(JsonRpcErrors.METHOD_NOT_FOUND, response.errorCode())
        assertEquals(7, response["id"]!!.jsonPrimitive.int)
    }

    @Test
    fun `resources and prompts lists are empty`() = runTest {
        assertEquals(
            JsonArray(emptyList()),
            call("""{"jsonrpc":"2.0","id":8,"method":"resources/list"}""")!!.result()["resources"],
        )
        assertEquals(
            JsonArray(emptyList()),
            call("""{"jsonrpc":"2.0","id":9,"method":"prompts/list"}""")!!.result()["prompts"],
        )
    }

    @Test
    fun `batch requests are rejected`() = runTest {
        val response = call("""[{"jsonrpc":"2.0","id":1,"method":"ping"},{"jsonrpc":"2.0","id":2,"method":"ping"}]""")!!
        assertEquals(JsonRpcErrors.INVALID_REQUEST, response.errorCode())
        assertEquals(JsonNull, response["id"])
    }

    @Test
    fun `invalid json is a parse error`() = runTest {
        val response = call("""{"jsonrpc":"2.0",""")!!
        assertEquals(JsonRpcErrors.PARSE_ERROR, response.errorCode())
    }

    @Test
    fun `notifications produce no response`() = runTest {
        assertNull(call("""{"jsonrpc":"2.0","method":"notifications/initialized"}"""))
        assertNull(server.handle("""{"jsonrpc":"2.0","method":"notifications/cancelled","params":{"requestId":1}}""", emptyMap()))
    }

    @Test
    fun `handle serialises the response`() = runTest {
        val raw = server.handle("""{"jsonrpc":"2.0","id":1,"method":"ping"}""", emptyMap())
        assertNotNull(raw)
        assertEquals("""{"jsonrpc":"2.0","id":1,"result":{}}""", raw)
    }
}
