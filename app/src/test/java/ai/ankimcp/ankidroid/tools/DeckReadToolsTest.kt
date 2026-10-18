package ai.ankimcp.ankidroid.tools

import ai.ankimcp.ankidroid.anki.AnkiDroidVersion
import ai.ankimcp.ankidroid.anki.FakeAvailability
import ai.ankimcp.ankidroid.tools.FakeCollection.FCard
import ai.ankimcp.ankidroid.tools.FakeCollection.FDeck
import ai.ankimcp.ankidroid.tools.FakeCollection.FModel
import ai.ankimcp.ankidroid.tools.FakeCollection.FNote
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeckReadToolsTest {
    private val defaultPreset =
        """{"id":1,"name":"Default","fsrsWeights":[0.4,0.6],"fsrsParams5":[1.1,2.2],"fsrsParams6":[],"desiredRetention":0.9,""" +
            """"rev":{"maxIvl":3650},"weightSearch":"","ignoreRevlogsBeforeDate":"","easyDaysPercentages":[1.0,1.0]}"""
    private val examPreset =
        """{"id":7,"name":"Exam","fsrsWeights":[0.5],"desiredRetention":0.95,"rev":{"maxIvl":180},"paramSearch":"deck:Exam"}"""
    private val col = FakeCollection().apply {
        decks.clear()
        decks += FDeck(1, "Default", options = defaultPreset)
        decks += FDeck(2, "Spanish", options = defaultPreset)
        decks += FDeck(3, "Exam", options = examPreset)
        decks += FDeck(9, "Cram", dyn = true, options = """{"id":9,"name":"Cram","dyn":true}""")
        models += FModel(10, "Basic", listOf("Front", "Back"))
        notes += FNote(101, 10, listOf("a", "b"), tags = listOf("verb", "Zeta"))
        notes += FNote(102, 10, listOf("c", "d"), tags = listOf("alpha", "verb"))
        notes += FNote(103, 10, listOf("e", "f"))
        cards += FCard(1001, 101, did = 2)
        cards += FCard(1002, 102, did = 9, odid = 2)
        cards += FCard(1003, 103, did = 3)
        cards += FCard(1004, 102, ord = 1, did = 2)
    }
    private val repo = col.repository()

    @Test
    fun `tag_list without a deck lists tags on notes in Anki's tag order`() {
        val data = TagListTool(repo).callWith().data
        assertEquals(listOf("alpha", "verb", "Zeta"), data["tags"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals(3, data["count"]!!.jsonPrimitive.int)
        assertEquals("Found 3 tag(s)", data["message"]!!.jsonPrimitive.content)
        assertEquals("", col.queriesTo("notes").single().selection)
        assertEquals(listOf("_id", "tags"), col.queriesTo("notes").single().projection)
    }

    @Test
    fun `tag_list for a deck scopes by deck search, and an unknown deck is an empty list`() {
        val data = TagListTool(repo).callWith("deck" to " Exam ").data
        assertEquals(0, data["count"]!!.jsonPrimitive.int)
        assertEquals("Found 0 tag(s) in deck 'Exam'", data["message"]!!.jsonPrimitive.content)

        val spanish = TagListTool(repo).callWith("deck" to "Spanish").data
        assertEquals(listOf("Zeta", "alpha", "verb"), spanish["tags"]!!.jsonArray.map { it.jsonPrimitive.content })

        val unknown = TagListTool(repo).callWith("deck" to "Nope").data
        assertEquals(0, unknown["count"]!!.jsonPrimitive.int)
    }

    @Test
    fun `get_fsrs_params lists presets in use with their decks`() {
        val data = GetFsrsParamsTool(repo).callWith().data

        assertEquals(setOf("presets", "total_presets"), data.keys)
        assertEquals(2, data["total_presets"]!!.jsonPrimitive.int)
        val presets = data["presets"]!!.jsonArray.map { it.jsonObject }
        val default = presets[0]
        assertEquals("Default", default["preset_name"]!!.jsonPrimitive.content)
        assertEquals(1L, default["preset_id"]!!.jsonPrimitive.long)
        assertEquals(listOf(1.1, 2.2), default["fsrs_weights"]!!.jsonArray.map { it.jsonPrimitive.double })
        assertEquals(5, default["fsrs_param_version"]!!.jsonPrimitive.int)
        assertEquals(0.9, default["desired_retention"]!!.jsonPrimitive.double, 0.0)
        assertEquals(3650, default["max_interval"]!!.jsonPrimitive.int)
        assertEquals(listOf("Default", "Spanish"), default["decks"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertNull(default["param_search"])
        assertNull(default["ignore_revlogs_before_date"])
        assertEquals(2, default["easy_days_percentages"]!!.jsonArray.size)
        val exam = presets[1]
        assertEquals(JsonNull, exam["fsrs_param_version"])
        assertEquals(listOf(0.5), exam["fsrs_weights"]!!.jsonArray.map { it.jsonPrimitive.double })
        assertEquals("deck:Exam", exam["param_search"]!!.jsonPrimitive.content)
        assertEquals(180, exam["max_interval"]!!.jsonPrimitive.int)
    }

    @Test
    fun `get_fsrs_params for one deck, case-insensitively, and an unknown deck`() {
        val data = GetFsrsParamsTool(repo).callWith("deck_name" to "spanish").data
        assertEquals(setOf("deck_name", "preset"), data.keys)
        assertEquals("spanish", data["deck_name"]!!.jsonPrimitive.content)
        assertEquals("Default", data["preset"]!!.jsonObject["preset_name"]!!.jsonPrimitive.content)

        assertEquals(
            "Deck not found: Nope (hint: Check spelling or use list_decks to see available decks)",
            GetFsrsParamsTool(repo).callWith("deck_name" to "Nope").text,
        )
    }

    @Test
    fun `get_fsrs_params for a filtered deck is an error, not a preset`() {
        val result = GetFsrsParamsTool(repo).callWith("deck_name" to "Cram")
        assertTrue(result.isError)
        assertTrue(result.text, result.text.startsWith("Deck 'Cram' is a filtered deck, which has no FSRS preset (hint:"))
    }

    @Test
    fun `fsrsParams picks the highest non-empty version, then the legacy weights`() {
        val config = buildJsonObject {
            put("fsrsWeights", JsonArray(listOf(JsonPrimitive(1))))
            put("fsrsParams5", JsonArray(listOf(JsonPrimitive(5))))
            put("fsrsParams6", JsonArray(listOf(JsonPrimitive(6))))
            put("fsrsParams7", JsonArray(emptyList()))
        }
        assertEquals(6, GetFsrsParamsTool.fsrsParams(config).first)
        assertEquals(36500, GetFsrsParamsTool.maxInterval(buildJsonObject {}).jsonPrimitive.int)
    }

    @Test
    fun `filtered_deck_get_info counts each deck's own cards and skips unknown ids`() {
        val data = FilteredDeckGetInfoTool(repo).callWith("deck_ids" to listOf(9, 2, 404)).data

        val decks = data["decks"]!!.jsonArray.map { it.jsonObject }
        assertEquals(setOf("deck_id", "name", "card_count", "is_filtered"), decks[0].keys)
        assertEquals("Cram", decks[0]["name"]!!.jsonPrimitive.content)
        assertTrue(decks[0]["is_filtered"]!!.jsonPrimitive.boolean)
        assertEquals(1, decks[0]["card_count"]!!.jsonPrimitive.int)
        assertFalse(decks[1]["is_filtered"]!!.jsonPrimitive.boolean)
        assertEquals("the card in Cram whose home is Spanish is not counted", 2, decks[1]["card_count"]!!.jsonPrimitive.int)
        assertEquals(2, data["count"]!!.jsonPrimitive.int)
        assertEquals(1, data["not_found"]!!.jsonPrimitive.int)
        assertEquals("Retrieved info for 2 deck(s). 1 deck(s) not found.", data["message"]!!.jsonPrimitive.content)
        assertEquals(listOf("did:9", "did:2 -deck:filtered"), col.queriesTo("cards").map { it.selection })
    }

    @Test
    fun `filtered_deck_get_info validates the id list like the add-on`() {
        assertEquals(
            "[validation_error] deck_ids cannot be empty (hint: Provide at least one deck ID)",
            FilteredDeckGetInfoTool(repo).callWith("deck_ids" to emptyList<Long>()).text,
        )
        assertEquals(
            "[limit_exceeded] Maximum 50 deck IDs per request (requested: 51) (hint: Split your request into smaller batches)",
            FilteredDeckGetInfoTool(repo).callWith("deck_ids" to (1L..51L).toList()).text,
        )
    }

    @Test
    fun `deck tools need AnkiDroid 2_24`() {
        val old = col.repository(FakeAvailability(version = AnkiDroidVersion(22303300, "2.23.3")))
        listOf(
            TagListTool(old).callWith(),
            GetFsrsParamsTool(old).callWith(),
            FilteredDeckGetInfoTool(old).callWith("deck_ids" to listOf(1)),
            FilteredDeckGetInfoTool(old).callWith("deck_ids" to listOf(404)),
            GetDueCardsTool(old, RatingGuard()).callWith("deck_name" to "Nope"),
        ).forEach { assertTrue(it.text, it.text.contains("Update AnkiDroid to 2.24 or newer")) }
        assertTrue(col.provider.queries.isEmpty())
    }
}
