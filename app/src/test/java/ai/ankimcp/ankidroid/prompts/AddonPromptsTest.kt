package ai.ankimcp.ankidroid.prompts

import ai.ankimcp.ankidroid.mcp.JsonRpcErrors
import ai.ankimcp.ankidroid.mcp.McpServer
import ai.ankimcp.ankidroid.mcp.PromptRegistry
import ai.ankimcp.ankidroid.mcp.ResourceRegistry
import ai.ankimcp.ankidroid.mcp.ToolRegistry
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The add-on's prompts and query-syntax resource over the `prompts` and `resources` methods. The
 * review_session golden files are the phone's adapted text (D57), pinned byte for byte.
 */
class AddonPromptsTest {
    private val server = McpServer(
        ToolRegistry(emptyList()),
        serverVersion = "test",
        prompts = PromptRegistry(AddonPrompts.prompts),
        resources = ResourceRegistry(AddonPrompts.resources),
    )

    private suspend fun call(method: String, params: String = "{}"): JsonObject =
        server.handleMessage("""{"jsonrpc":"2.0","id":1,"method":"$method","params":$params}""")!!

    private fun fixture(name: String): String =
        checkNotNull(javaClass.getResourceAsStream("/review_session/$name.txt")) { name }.use { it.readBytes().toString(Charsets.UTF_8) }

    private fun JsonObject.promptText(): String =
        this["result"]!!.jsonObject["messages"]!!.jsonArray.single().jsonObject["content"]!!.jsonObject["text"]!!.jsonPrimitive.content

    @Test
    fun `initialize advertises prompts and resources`() = runTest {
        val capabilities = call("initialize", """{"protocolVersion":"2025-06-18"}""")["result"]!!.jsonObject["capabilities"]!!.jsonObject
        assertEquals(setOf("tools", "prompts", "resources"), capabilities.keys)
    }

    @Test
    fun `prompts list has the add-on's names and optional arguments`() = runTest {
        val prompts = call("prompts/list")["result"]!!.jsonObject["prompts"]!!.jsonArray.map { it.jsonObject }

        assertEquals(listOf("review_session", "twenty_rules"), prompts.map { it["name"]!!.jsonPrimitive.content })
        val review = prompts[0]
        assertEquals(listOf("name", "description", "arguments"), review.keys.toList())
        val args = review["arguments"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("deck_name", "card_limit", "review_style"), args.map { it["name"]!!.jsonPrimitive.content })
        assertTrue(args.none { it["required"]!!.jsonPrimitive.boolean })
        assertEquals(0, prompts[1]["arguments"]!!.jsonArray.size)
    }

    @Test
    fun `review_session renders the phone's text for every style, gui and unknown styles as interactive`() = runTest {
        val cases = mapOf(
            "default" to "{}",
            "interactive_spanish" to """{"deck_name":"Spanish \"Verbs\"","card_limit":"5"}""",
            "quick" to """{"deck_name":"Lang::Fr","card_limit":"7","review_style":"quick"}""",
            "voice" to """{"deck_name":"Audio Deck","card_limit":"3","review_style":"voice"}""",
            "gui" to """{"deck_name":"Kanji","card_limit":"12","review_style":"gui"}""",
            "custom_style" to """{"review_style":"relaxed"}""",
        )
        cases.forEach { (fixture, arguments) ->
            val response = call("prompts/get", """{"name":"review_session","arguments":$arguments}""")
            val text = response.promptText()
            assertEquals(fixture, fixture(fixture), text)
            assertTrue(fixture, text.contains("use rate_card to record their performance"))
            listOf("card_unbury", "skip_images", "skip_audio", "gui_", "Offer to sync").forEach {
                assertFalse("$fixture mentions $it", text.contains(it))
            }
            val result = response["result"]!!.jsonObject
            assertEquals(ReviewSessionPrompt.description, result["description"]!!.jsonPrimitive.content)
            assertEquals("user", result["messages"]!!.jsonArray.single().jsonObject["role"]!!.jsonPrimitive.content)
        }
    }

    @Test
    fun `a card_limit that is not a number is an invalid-params error`() = runTest {
        val response = call("prompts/get", """{"name":"review_session","arguments":{"card_limit":"many"}}""")
        assertEquals(JsonRpcErrors.INVALID_PARAMS, response["error"]!!.jsonObject["code"]!!.jsonPrimitive.int)
        assertTrue(response.toString(), response["error"]!!.jsonObject["message"]!!.jsonPrimitive.content.contains("card_limit"))
    }

    @Test
    fun `twenty_rules is the add-on's markdown, unchanged`() = runTest {
        val text = call("prompts/get", """{"name":"twenty_rules"}""").promptText()
        assertTrue(text.startsWith("*Based on \"Twenty Rules of Formulating Knowledge\" by Dr. Piotr Wozniak"))
        assertEquals(7988, text.toByteArray(Charsets.UTF_8).size)
    }

    @Test
    fun `bundled texts load by absolute path, independent of the loading class's package`() {
        assertTrue(BUNDLED_DIR.startsWith("/"))
        // R8 moves AddonPrompts to another package; a class elsewhere stands in for that here.
        listOf("twenty_rules_content.md", "query_syntax.json").forEach { file ->
            McpServer::class.java.getResourceAsStream("$BUNDLED_DIR$file").use { assertTrue(file, it != null) }
            McpServer::class.java.getResourceAsStream(file).use { assertTrue("relative $file must not resolve", it == null) }
            assertTrue(file, bundledText(file).isNotEmpty())
        }
    }

    @Test
    fun `an unknown prompt is invalid params`() = runTest {
        val response = call("prompts/get", """{"name":"nope"}""")
        assertEquals(JsonRpcErrors.INVALID_PARAMS, response["error"]!!.jsonObject["code"]!!.jsonPrimitive.int)
        assertEquals("Unknown prompt: nope", response["error"]!!.jsonObject["message"]!!.jsonPrimitive.content)
    }

    @Test
    fun `query-syntax is listed and read as FastMCP serves it`() = runTest {
        val resource = call("resources/list")["result"]!!.jsonObject["resources"]!!.jsonArray.single().jsonObject
        assertEquals(listOf("name", "title", "uri", "description", "mimeType"), resource.keys.toList())
        assertEquals("anki://query-syntax", resource["uri"]!!.jsonPrimitive.content)
        assertEquals("query_syntax", resource["name"]!!.jsonPrimitive.content)
        assertEquals("text/plain", resource["mimeType"]!!.jsonPrimitive.content)

        val contents = call("resources/read", """{"uri":"anki://query-syntax"}""")["result"]!!.jsonObject["contents"]!!.jsonArray.single().jsonObject
        assertEquals(listOf("uri", "mimeType", "text"), contents.keys.toList())
        val text = contents["text"]!!.jsonPrimitive.content
        val parsed = Json.parseToJsonElement(text).jsonObject
        assertEquals(
            listOf(
                "basic_searches", "field_searches", "deck_and_tag", "card_state", "card_properties", "date_searches",
                "note_and_card_types", "special_searches", "combining", "examples",
            ),
            parsed.keys.toList(),
        )
        // pydantic_core.to_json(indent=2): the same layout kotlinx prints with a two-space indent.
        @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
        val pretty = Json {
            prettyPrint = true
            prettyPrintIndent = "  "
        }
        assertEquals(pretty.encodeToString(JsonElement.serializer(), parsed), text)
        assertFalse(text.endsWith("\n"))
    }

    @Test
    fun `an unknown resource is resource not found`() = runTest {
        val response = call("resources/read", """{"uri":"anki://system-info"}""")
        assertEquals(JsonRpcErrors.RESOURCE_NOT_FOUND, response["error"]!!.jsonObject["code"]!!.jsonPrimitive.int)
    }
}
