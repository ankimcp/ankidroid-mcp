package ai.ankimcp.ankidroid.tools

import ai.ankimcp.ankidroid.anki.AnkiDroidVersion
import ai.ankimcp.ankidroid.anki.FakeAvailability
import ai.ankimcp.ankidroid.tools.FakeCollection.FCard
import ai.ankimcp.ankidroid.tools.FakeCollection.FDeck
import ai.ankimcp.ankidroid.tools.FakeCollection.FModel
import ai.ankimcp.ankidroid.tools.FakeCollection.FNote
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NoteReadToolsTest {
    private val col = FakeCollection().apply {
        decks += FDeck(2, "Spanish")
        models += FModel(10, "Basic", listOf("Front", "Back"))
        models += FModel(11, "Cloze", listOf("Text", "Extra"))
        notes += FNote(101, 10, listOf("hola", "hello"), tags = listOf("verb", "es::a1"), mod = 1_700_000_101)
        notes += FNote(102, 10, listOf("x".repeat(300), "long"))
        notes += FNote(103, 11, listOf("{{c1::Madrid}} is the capital", "geo"))
        cards += FCard(1001, 101, did = 2)
        cards += FCard(1003, 103, ord = 1, did = 2)
        cards += FCard(1002, 102)
        cards += FCard(1004, 103, ord = 0, did = 2)
    }
    private val repo = col.repository()

    @Test
    fun `find_notes pages ascending ids and reports hasMore`() {
        val data = FindNotesTool(repo).callWith("query" to "", "limit" to 2, "offset" to 0).data

        assertEquals(listOf(101L, 102L), data["noteIds"]!!.jsonArray.map { it.jsonPrimitive.long })
        assertEquals(2, data["count"]!!.jsonPrimitive.int)
        assertEquals(3, data["total"]!!.jsonPrimitive.int)
        assertTrue(data["hasMore"]!!.jsonPrimitive.boolean)
        assertEquals("Found 3 notes matching the query, returning 2", data["message"]!!.jsonPrimitive.content)
        assertTrue(data["hint"]!!.jsonPrimitive.content.endsWith("Use offset parameter to fetch more results."))
        assertNull(data["noteLabels"])

        val last = FindNotesTool(repo).callWith("query" to "", "limit" to 2, "offset" to 2).data
        assertEquals(listOf(103L), last["noteIds"]!!.jsonArray.map { it.jsonPrimitive.long })
        assertFalse(last["hasMore"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun `find_notes labels with the first field, excerpted at 250 code points`() {
        val data = FindNotesTool(repo).callWith("query" to "deck:Default", "include_first_field" to true).data

        val labels = data["noteLabels"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf(102L), labels.map { it["noteId"]!!.jsonPrimitive.long })
        assertEquals(250, labels[0]["firstField"]!!.jsonPrimitive.content.length)
        assertTrue(labels[0]["truncated"]!!.jsonPrimitive.boolean)
        assertEquals(300, labels[0]["fullLength"]!!.jsonPrimitive.int)
    }

    @Test
    fun `find_notes searches ids only and reads fields just for the page of a large collection`() {
        val big = FakeCollection().apply {
            models += FModel(10, "Basic", listOf("Front", "Back"))
            for (i in 1L..5_000L) {
                notes += FNote(10_000 + i, 10, listOf("front $i", "back"))
                cards += FCard(20_000 + i, 10_000 + i)
            }
        }

        val data = FindNotesTool(big.repository()).callWith("query" to "", "limit" to 3, "offset" to 10, "include_first_field" to true).data

        assertEquals(5_000, data["total"]!!.jsonPrimitive.int)
        assertEquals(listOf(10_011L, 10_012L, 10_013L), data["noteIds"]!!.jsonArray.map { it.jsonPrimitive.long })
        assertEquals(
            listOf("front 11", "front 12", "front 13"),
            data["noteLabels"]!!.jsonArray.map { it.jsonObject["firstField"]!!.jsonPrimitive.content },
        )
        val noteQueries = big.queriesTo("notes")
        assertEquals(listOf("_id"), noteQueries[0].projection)
        assertEquals("", noteQueries[0].selection)
        assertEquals(listOf("_id", "flds"), noteQueries[1].projection)
        assertEquals("nid:10011,10012,10013", noteQueries[1].selection)
        assertTrue(big.queriesTo("cards").all { it.projection == listOf("_id") })
    }

    @Test
    fun `notes_info with only impossible ids still reports an outdated AnkiDroid`() {
        val old = col.repository(FakeAvailability(version = AnkiDroidVersion(22303300, "2.23.3")))
        assertTrue(NotesInfoTool(old).callWith("notes" to listOf(-1)).text.contains("Update AnkiDroid to 2.24 or newer"))
    }

    @Test
    fun `an empty search is an empty result, answered by the card search alone`() {
        val data = FindNotesTool(repo).callWith("query" to "deck:Nothing", "include_first_field" to true).data

        assertEquals(0, data["total"]!!.jsonPrimitive.int)
        assertEquals("No notes found matching the search criteria", data["message"]!!.jsonPrimitive.content)
        assertEquals(0, data["noteLabels"]!!.jsonArray.size)
        assertEquals(1, col.queriesTo("cards").size)
        assertTrue("no note search for an empty card match", col.queriesTo("notes").isEmpty())
    }

    @Test
    fun `an invalid search is the add-on's search error, not a frozen AnkiDroid`() {
        val result = FindNotesTool(repo).callWith("query" to "deck:(")
        assertTrue(result.isError)
        assertTrue(result.text, result.text.startsWith("Search query failed: Invalid Anki search query: \"deck:(\" (hint: Check Anki"))
        assertTrue(result.text, result.text.contains("(context: {'examples': ['\"deck:DeckName\" - all notes in a deck'"))
    }

    @Test
    fun `find_notes validates limit and offset like the add-on`() {
        assertEquals(
            "[limit_exceeded] limit exceeds maximum of 500 (requested: 501) (hint: Use a limit <= 500 and paginate " +
                "with offset) (context: {'provided_value': 501, 'maximum': 500})",
            FindNotesTool(repo).callWith("query" to "", "limit" to 501).text,
        )
        assertTrue(FindNotesTool(repo).callWith("query" to "", "limit" to 0).text.startsWith("[validation_error] limit must be positive"))
        assertTrue(FindNotesTool(repo).callWith("query" to "", "offset" to -1).text.startsWith("[validation_error] offset cannot be negative"))
        assertEquals("limit must be an integer", FindNotesTool(repo).callWith("query" to "", "limit" to "ten").text)
        assertEquals(2, FindNotesTool(repo).callWith("query" to "", "limit" to "2").data["count"]!!.jsonPrimitive.int)
        assertTrue(col.provider.queries.size <= 2)
    }

    @Test
    fun `notes_info returns fields in order, tags, cards in template order from batched searches, and mod`() {
        val data = NotesInfoTool(repo).callWith("notes" to listOf(103, 101)).data

        val notes = data["notes"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf(103L, 101L), notes.map { it["noteId"]!!.jsonPrimitive.long })
        val cloze = notes[0]
        assertEquals("Cloze", cloze["modelName"]!!.jsonPrimitive.content)
        assertEquals("template order", listOf(1004L, 1003L), cloze["cards"]!!.jsonArray.map { it.jsonPrimitive.long })
        // The note search's probe, the batched id-only search, one detail call for all three cards.
        val cardQueries = col.queriesTo("cards")
        assertEquals(listOf("nid:103,101", "nid:101,103", "cid:1001,1003,1004"), cardQueries.map { it.selection })
        assertEquals(listOf("_id"), cardQueries[1].projection)
        assertEquals(listOf("_id", "note_id", "ord"), cardQueries[2].projection)
        val fields = cloze["fields"]!!.jsonObject
        assertEquals(listOf("Text", "Extra"), fields.keys.toList())
        assertEquals(setOf("value", "order"), fields["Extra"]!!.jsonObject.keys)
        assertEquals(1, fields["Extra"]!!.jsonObject["order"]!!.jsonPrimitive.int)
        val basic = notes[1]
        assertEquals(listOf("verb", "es::a1"), basic["tags"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals(1_700_000_101L, basic["mod"]!!.jsonPrimitive.long)
        assertEquals(2, data["count"]!!.jsonPrimitive.int)
        assertEquals(0, data["notFound"]!!.jsonPrimitive.int)
        assertEquals(listOf("Cloze", "Basic"), data["models"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals("Successfully retrieved information for 2 note(s)", data["message"]!!.jsonPrimitive.content)
        assertEquals("nid:103,101", col.queriesTo("notes").single().selection)
    }

    @Test
    fun `notes_info filters fields and excerpts the survivors`() {
        val data = NotesInfoTool(repo).callWith(
            "notes" to listOf(102),
            "exclude_fields" to listOf("Back"),
            "excerpt_chars" to 10,
        ).data

        val fields = data["notes"]!!.jsonArray[0].jsonObject["fields"]!!.jsonObject
        assertEquals(listOf("Front"), fields.keys.toList())
        val front = fields["Front"]!!.jsonObject
        assertEquals("xxxxxxxxxx", front["value"]!!.jsonPrimitive.content)
        assertTrue(front["truncated"]!!.jsonPrimitive.boolean)
        assertEquals(300, front["fullLength"]!!.jsonPrimitive.int)
        assertTrue(data["hint"]!!.jsonPrimitive.content.contains("Values are excerpts capped at 10 character(s)"))

        val included = NotesInfoTool(repo).callWith("notes" to listOf(101), "include_fields" to listOf("Back"), "exclude_fields" to listOf("Back")).data
        assertEquals(listOf("Back"), included["notes"]!!.jsonArray[0].jsonObject["fields"]!!.jsonObject.keys.toList())
    }

    @Test
    fun `notes_info counts missing notes without failing`() {
        val data = NotesInfoTool(repo).callWith("notes" to listOf(101, 999, -5)).data
        assertEquals(1, data["count"]!!.jsonPrimitive.int)
        assertEquals(2, data["notFound"]!!.jsonPrimitive.int)
        assertEquals(listOf(101L, 999L, -5L), data["requestedIds"]!!.jsonArray.map { it.jsonPrimitive.long })
        assertEquals("Retrieved 1 note(s). 2 note(s) not found (possibly deleted).", data["message"]!!.jsonPrimitive.content)

        val none = NotesInfoTool(repo).callWith("notes" to listOf(998, 999)).data
        assertEquals(0, none["count"]!!.jsonPrimitive.int)
        assertEquals("No valid notes found. They may have been deleted.", none["hint"]!!.jsonPrimitive.content)
    }

    @Test
    fun `notes_info validates its arguments like the add-on`() {
        assertEquals("[validation_error] notes parameter cannot be empty", NotesInfoTool(repo).callWith("notes" to emptyList<Long>()).text)
        assertTrue(NotesInfoTool(repo).callWith("notes" to listOf(1), "excerpt_chars" to 0).text.startsWith("[validation_error] excerpt_chars must be >= 1 (got 0)"))
        assertTrue(
            NotesInfoTool(repo).callWith("notes" to (1..101).toList()).text
                .startsWith("[limit_exceeded] Too many notes: 101 (maximum is 100)"),
        )
        assertEquals("notes must be an array of integers", NotesInfoTool(repo).callWith("notes" to JsonPrimitive(5)).text)
        assertTrue(col.provider.queries.isEmpty())
    }

    @Test
    fun `note tools need AnkiDroid 2_24`() {
        val old = col.repository(FakeAvailability(version = AnkiDroidVersion(22303300, "2.23.3")))
        assertTrue(FindNotesTool(old).callWith("query" to "").text.contains("Update AnkiDroid to 2.24 or newer"))
        assertTrue(NotesInfoTool(old).callWith("notes" to listOf(101)).text.contains("Update AnkiDroid to 2.24 or newer"))
    }
}
