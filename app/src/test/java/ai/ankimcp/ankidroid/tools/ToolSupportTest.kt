package ai.ankimcp.ankidroid.tools

import ai.ankimcp.ankidroid.ankiTools
import ai.ankimcp.ankidroid.mcp.writeAnnotations
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ToolSupportTest {
    @Test
    fun `deck searches escape like build_search_string`() {
        assertEquals("deck:Spanish", deckSearch("Spanish"))
        assertEquals("deck:Lang::Fr", deckSearch("Lang::Fr"))
        assertEquals("\"deck:My Deck\"", deckSearch("My Deck"))
        assertEquals("deck:A\\_B\\*C\\\\D", deckSearch("A_B*C\\D"))
        assertEquals("\"deck:Q (1)\"", deckSearch("Q (1)"))
        assertEquals("deck:say\\\"hi\\\"", deckSearch("say\"hi\""))
        assertEquals("deck:_*", deckSearch("_*"))
    }

    @Test
    fun `errors read like the add-on's HandlerError`() {
        val result = addonError(
            "limit exceeds maximum of 500 (requested: 501)",
            hint = "Use a limit <= 500",
            code = "limit_exceeded",
            context = mapOf("provided_value" to 501, "names" to listOf("a", "it's"), "none" to null, "flag" to true),
        )
        assertTrue(result.isError)
        assertEquals(
            "[limit_exceeded] limit exceeds maximum of 500 (requested: 501) (hint: Use a limit <= 500) " +
                "(context: {'provided_value': 501, 'names': ['a', \"it's\"], 'none': None, 'flag': True})",
            result.text,
        )
        assertEquals("plain", addonError("plain").text)
    }

    @Test
    fun `python helpers count code points and round half-even on the binary value`() {
        val emoji = "a😀b"
        assertEquals(3, pyLen(emoji))
        assertEquals("a😀", pyPrefix(emoji, 2))
        assertEquals(emoji, pyPrefix(emoji, 5))
        assertEquals(0.1235, pyRound(0.12345678, 4), 0.0)
        assertEquals(2.675.let { pyRound(it, 2) }, 2.67, 0.0)
    }

    @Test
    fun `cloze answers are stripped and style is prepended`() {
        assertEquals(
            "<span class=\"cloze\" data-ordinal=\"1\">[...]</span>",
            stripClozeAnswer("<span class=\"cloze\" data-cloze=\"Paris &amp; co\" data-ordinal=\"1\">[...]</span>"),
        )
        assertEquals("<style>.a{}</style>x", withStyle(".a{}", "x"))
    }

    @Test
    fun `arguments are coerced like pydantic lax mode`() {
        val args = ToolArgs(
            JsonObject(
                mapOf(
                    "i" to JsonPrimitive(5.0),
                    "s" to JsonPrimitive("7"),
                    "b" to JsonPrimitive("true"),
                    "n" to JsonNull,
                    "list" to JsonArray(listOf(JsonPrimitive(1), JsonPrimitive("2"))),
                ),
            ),
        )
        assertEquals(5L, args.long("i"))
        assertEquals(7, args.int("s", 0))
        assertTrue(args.bool("b", false))
        assertNull(args.optLong("n"))
        assertEquals(9, args.int("missing", 9))
        assertEquals(listOf(1L, 2L), args.longList("list"))
        listOf<() -> Unit>(
            { ToolArgs(JsonObject(mapOf("x" to JsonPrimitive(1.5)))).long("x") },
            { ToolArgs(JsonObject(mapOf("x" to JsonPrimitive(true)))).long("x") },
            { ToolArgs(JsonObject(mapOf("x" to JsonPrimitive(1)))).string("x") },
            { ToolArgs(JsonObject(emptyMap())).string("x") },
        ).forEach { block ->
            try {
                block()
                fail("expected InvalidArguments")
            } catch (_: InvalidArguments) {
            }
        }
    }

    @Test
    fun `booleans take pydantic's lax spellings`() {
        fun parse(value: kotlinx.serialization.json.JsonElement) = ToolArgs(JsonObject(mapOf("b" to value))).bool("b", default = false)
        listOf("true", "True", "yes", "YES", "on", "1", "t", "y").forEach { assertTrue(it, parse(JsonPrimitive(it))) }
        listOf("false", "no", "Off", "0", "f", "n").forEach { assertFalse(it, parse(JsonPrimitive(it))) }
        assertTrue(parse(JsonPrimitive(1)))
        assertFalse(parse(JsonPrimitive(0)))
        assertTrue(parse(JsonPrimitive(true)))
        listOf(JsonPrimitive(2), JsonPrimitive("maybe"), JsonPrimitive(1.5)).forEach { value ->
            try {
                parse(value)
                fail("expected InvalidArguments for $value")
            } catch (_: InvalidArguments) {
            }
        }
    }

    @Test
    fun `write annotations mirror the add-on's`() {
        val additive = writeAnnotations("Add Note", destructive = false)
        assertEquals(listOf("title", "readOnlyHint", "destructiveHint", "openWorldHint"), additive.keys.toList())
        assertFalse(additive["readOnlyHint"]!!.jsonPrimitive.boolean)
        assertFalse(additive["destructiveHint"]!!.jsonPrimitive.boolean)
        val idempotent = writeAnnotations("Delete Notes", idempotent = true)
        assertEquals(listOf("title", "readOnlyHint", "destructiveHint", "idempotentHint", "openWorldHint"), idempotent.keys.toList())
        assertTrue(idempotent["destructiveHint"]!!.jsonPrimitive.boolean)
        assertTrue(idempotent["idempotentHint"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun `the registered tool set and its annotations follow the add-on`() {
        val tools = ankiTools(FakeCollection().repository()) { ai.ankimcp.ankidroid.anki.SyncRequest.NotificationPosted }
        // name -> (destructiveHint, idempotentHint) for the write tools, as the add-on declares them.
        val writes = mapOf(
            "create_deck" to (false to true), "add_note" to (false to null), "add_notes" to (false to null),
            "update_note_fields" to (true to null), "update_notes" to (true to null), "delete_notes" to (true to true),
            "create_model" to (false to null), "update_model_styling" to (true to null), "update_model_templates" to (true to null),
            "model_field_add" to (true to true), "tag_add" to (false to true), "tag_remove" to (true to true),
            "tag_replace" to (true to true), "tag_batch" to (true to true), "card_bury" to (false to true),
            "card_suspend" to (false to true), "card_set_flag" to (false to true), "card_change_deck" to (false to true),
            "get_due_cards" to (true to null), "rate_card" to (true to null), "sync" to (true to null),
        )
        val reads = setOf(
            "list_decks", "find_notes", "notes_info", "model_names", "model_field_names", "model_templates", "model_styling",
            "tag_list", "filtered_deck_get_info", "present_card", "cards_stats", "get_card_memory_state", "get_fsrs_params",
        )
        assertEquals(34, tools.size)
        assertEquals(reads + writes.keys, tools.map { it.name }.toSet())
        tools.forEach { tool ->
            val annotations = tool.annotations!!
            val write = writes[tool.name]
            assertEquals(tool.name, write == null, annotations["readOnlyHint"]!!.jsonPrimitive.boolean)
            assertEquals(tool.name, write?.first ?: false, annotations["destructiveHint"]!!.jsonPrimitive.boolean)
            assertEquals(tool.name, write?.second, annotations["idempotentHint"]?.jsonPrimitive?.boolean)
            assertEquals(tool.name, tool.name == "sync", annotations["openWorldHint"]!!.jsonPrimitive.boolean)
            assertEquals(tool.name, tool.title, annotations["title"]!!.jsonPrimitive.content)
            assertEquals(tool.name, JsonPrimitive(false), tool.inputSchema["additionalProperties"])
            assertTrue(tool.name, tool.inputSchema["properties"]!!.jsonObject.keys.none { it in UNSUPPORTED_ARGUMENTS })
        }
        assertTrue(tools.none { it.name in NOT_ON_ANKIDROID })
    }

    private companion object {
        /** Add-on arguments AnkiDroid cannot honour; they must not be in any schema (D57). */
        val UNSUPPORTED_ARGUMENTS = setOf("recompute", "latex_svg", "index", "job_id", "resolve")

        /** Add-on tools AnkiDroid cannot serve; never registered (D57). */
        val NOT_ON_ANKIDROID = setOf(
            "model_field_remove", "model_field_rename", "model_field_reposition", "change_note_type", "tag_clear_unused",
            "card_unbury", "card_unsuspend", "card_forget", "card_reposition", "card_set_due_date", "filtered_deck_upsert",
            "filtered_deck_delete", "filtered_deck_empty", "filtered_deck_rebuild", "set_fsrs_params", "optimize_fsrs_params",
            "store_media_file", "get_media_files_names", "delete_media_file", "gui_add_cards", "gui_answer_card", "gui_browse",
            "gui_current_card", "gui_deck_browser", "gui_deck_review", "gui_edit_note", "gui_select_card", "gui_show_answer",
            "gui_show_question", "gui_undo",
        )
    }
}
