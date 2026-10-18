package ai.ankimcp.ankidroid.tools

import ai.ankimcp.ankidroid.anki.AnkiDroidVersion
import ai.ankimcp.ankidroid.anki.FakeAvailability
import ai.ankimcp.ankidroid.tools.FakeCollection.FCard
import ai.ankimcp.ankidroid.tools.FakeCollection.FDeck
import ai.ankimcp.ankidroid.tools.FakeCollection.FModel
import ai.ankimcp.ankidroid.tools.FakeCollection.FNote
import ai.ankimcp.ankidroid.tools.FakeCollection.FTemplate
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

class NoteWriteToolsTest {
    private val col = FakeCollection().apply {
        decks += FDeck(2, "Spanish")
        decks += FDeck(9, "Cram", dyn = true)
        models += FModel(10, "Basic", listOf("Front", "Back"))
        models += FModel(
            12,
            "Basic (and reversed card)",
            listOf("Front", "Back"),
            templates = listOf(FTemplate("Card 1", "{{Front}}", "{{Back}}"), FTemplate("Card 2", "{{Back}}", "{{Front}}")),
        )
        notes += FNote(101, 10, listOf("hola", "hello"), tags = listOf("verb"))
        notes += FNote(102, 10, listOf("adios", "bye"))
        notes += FNote(103, 10, listOf("gato", "cat"))
        cards += FCard(1001, 101, did = 2)
        cards += FCard(1002, 102, did = 2)
        cards += FCard(1003, 103, did = 2)
    }
    private val repo = col.repository()
    private fun addNote() = AddNoteTool(repo) { col.nowMillis }
    private fun addNotes() = AddNotesTool(repo) { col.nowMillis }

    // --- create_deck ---

    @Test
    fun `create_deck creates parent and child and reports the add-on's keys`() {
        val data = CreateDeckTool(repo).callWith("deck_name" to "Japanese::Tokyo").data
        assertTrue(data["created"]!!.jsonPrimitive.boolean)
        assertEquals("Japanese", data["parentDeck"]!!.jsonPrimitive.content)
        assertEquals("Tokyo", data["childDeck"]!!.jsonPrimitive.content)
        assertEquals("Successfully created parent deck \"Japanese\" and child deck \"Tokyo\"", data["message"]!!.jsonPrimitive.content)
        assertEquals(data["deckId"]!!.jsonPrimitive.long, col.decks.single { it.name == "Japanese::Tokyo" }.id)
    }

    @Test
    fun `create_deck on an existing deck changes nothing and says so`() {
        val data = CreateDeckTool(repo).callWith("deck_name" to "spanish").data
        assertFalse(data["created"]!!.jsonPrimitive.boolean)
        assertTrue(data["exists"]!!.jsonPrimitive.boolean)
        assertEquals(2L, data["deckId"]!!.jsonPrimitive.long)
        assertEquals("Deck \"Spanish\" already exists", data["message"]!!.jsonPrimitive.content)
        assertTrue(col.writes.isEmpty())
    }

    @Test
    fun `create_deck validates like the add-on`() {
        assertEquals(
            "Deck name can have maximum 2 levels (parent::child). Provided: 3 levels (hint: Use format like 'Parent::Child', not 'A::B::C')",
            CreateDeckTool(repo).callWith("deck_name" to "A::B::C").text,
        )
        assertEquals("Deck name parts cannot be empty", CreateDeckTool(repo).callWith("deck_name" to "A:: ").text)
    }

    // --- add_note ---

    @Test
    fun `add_note puts every card in the requested deck and recovers the note id`() {
        val data = addNote().callWith(
            "deck_name" to "Spanish",
            "model_name" to "Basic (and reversed card)",
            "fields" to mapOf("Front" to "perro", "Back" to "dog"),
            "tags" to listOf("animal", "es"),
        ).data
        val id = data["note_id"]!!.jsonPrimitive.long
        assertEquals(col.nowMillis, id)
        assertEquals(listOf("perro", "dog"), col.note(id).fields)
        assertEquals(listOf("animal", "es"), col.note(id).tags)
        assertEquals(listOf(2L, 2L), col.cards.filter { it.nid == id }.map { it.did })
        assertEquals("Successfully created note in deck \"Spanish\"", data["message"]!!.jsonPrimitive.content)
        assertEquals(2, data["details"]!!.jsonObject["fields_added"]!!.jsonPrimitive.int)
        assertEquals("notes?deckId=2", col.writes.single().path)
    }

    @Test
    fun `add_note refuses a duplicate of the sort field unless allowed`() {
        val result = addNote().callWith("deck_name" to "Spanish", "model_name" to "Basic", "fields" to mapOf("Front" to "Hola", "Back" to "x"))
        assertEquals(
            "Failed to create note - it may be a duplicate (hint: The note appears to be a duplicate. Set allow_duplicate to true " +
                "if you want to add it anyway.) (context: {'deck_name': 'Spanish', 'model_name': 'Basic'})",
            result.text,
        )
        assertTrue(col.writes.isEmpty())
        val ok = addNote().callWith(
            "deck_name" to "Spanish", "model_name" to "Basic", "fields" to mapOf("Front" to "Hola", "Back" to "x"), "allow_duplicate" to true,
        )
        assertFalse(ok.text, ok.isError)
    }

    @Test
    fun `add_note validates deck, model and fields in the add-on's order`() {
        assertTrue(addNote().callWith("deck_name" to "Nope", "model_name" to "Basic", "fields" to mapOf("Front" to "a")).text.startsWith("Deck not found: Nope"))
        assertTrue(addNote().callWith("deck_name" to "Spanish", "model_name" to "Nope", "fields" to mapOf("Front" to "a")).text.startsWith("Model not found: Nope"))
        assertTrue(
            addNote().callWith("deck_name" to "Spanish", "model_name" to "Basic", "fields" to mapOf("Front" to " ", "Back" to "b"))
                .text.startsWith("Sort field 'Front' cannot be empty"),
        )
        assertTrue(
            addNote().callWith("deck_name" to "Spanish", "model_name" to "Basic", "fields" to mapOf("Front" to "a"))
                .text.startsWith("Missing required fields: Back"),
        )
        assertTrue(
            addNote().callWith("deck_name" to "Spanish", "model_name" to "Basic", "fields" to mapOf("Front" to "a", "Back" to "b", "X" to "c"))
                .text.startsWith("Unknown fields for this model: X"),
        )
        assertTrue(col.writes.isEmpty())
    }

    @Test
    fun `add_note to a filtered deck is AnkiDroid's refusal`() {
        val result = addNote().callWith("deck_name" to "Cram", "model_name" to "Basic", "fields" to mapOf("Front" to "x", "Back" to "y"))
        assertTrue(result.text, result.text.contains("A filtered deck cannot be specified"))
    }

    @Test
    fun `add_note whose id cannot be recovered says it was added`() {
        val tool = AddNoteTool(repo) { col.nowMillis + 1_000 }
        val result = tool.callWith("deck_name" to "Spanish", "model_name" to "Basic", "fields" to mapOf("Front" to "x", "Back" to "y"))
        assertTrue(result.isError)
        assertTrue(result.text, result.text.startsWith("AnkiDroid added the note to \"Spanish\" but it could not be found again"))
    }

    @Test
    fun `add_note whose confirming read fails says it was accepted, not that it failed`() {
        col.afterWrite = { col.nullReads = { q -> q.path == "notes" } }
        val result = addNote().callWith("deck_name" to "Spanish", "model_name" to "Basic", "fields" to mapOf("Front" to "x", "Back" to "y"))
        assertTrue(result.isError)
        assertTrue(result.text, result.text.startsWith("The note for deck \"Spanish\" was sent to AnkiDroid and accepted, but could not be confirmed: "))
        assertTrue(result.text, result.text.endsWith("Check before repeating it."))
    }

    /** rslib stores field text NFC-normalised. */
    private fun storeNfc() {
        col.afterWrite = { col.notes.replaceAll { note -> note.copy(fields = note.fields.map { nfc(it) }) } }
    }

    @Test
    fun `add_note finds a note whose fields AnkiDroid normalised to NFC`() {
        storeNfc()
        val data = addNote().callWith("deck_name" to "Spanish", "model_name" to "Basic", "fields" to mapOf("Front" to "café", "Back" to "y")).data
        assertEquals(listOf("café", "y"), col.note(data["note_id"]!!.jsonPrimitive.long).fields)
    }

    @Test
    fun `field values and names containing U+001F are rejected before anything is written`() {
        val add = addNote().callWith("deck_name" to "Spanish", "model_name" to "Basic", "fields" to mapOf("Front" to "a\u001fb", "Back" to "y"))
        assertTrue(add.text, add.text.startsWith("[validation_error] Field values cannot contain the character U+001F"))
        val update = UpdateNoteFieldsTool(repo).callWith("id" to 101, "fields" to mapOf("Back" to "x\u001fy"))
        assertTrue(update.text, update.text.startsWith("[validation_error] Field values cannot contain the character U+001F"))
        val many = addNotes().callWith("deck_name" to "Spanish", "model_name" to "Basic", "notes" to listOf(mapOf("fields" to mapOf("Front" to "\u001f", "Back" to "b"))))
        assertTrue(many.text, many.text.startsWith("[validation_error] Field values cannot contain the character U+001F"))
        assertTrue(col.writes.isEmpty())
    }

    // --- add_notes ---

    @Test
    fun `add_notes adds the valid notes in one call and reports each`() {
        val data = addNotes().callWith(
            "deck_name" to "Spanish",
            "model_name" to "Basic",
            "tags" to listOf("batch"),
            "notes" to listOf(
                mapOf("fields" to mapOf("Front" to "uno", "Back" to "one"), "tags" to listOf("num")),
                mapOf("fields" to mapOf("Front" to "hola", "Back" to "dup")),
                mapOf("fields" to mapOf("Front" to "dos")),
                mapOf("fields" to mapOf("Front" to "tres", "Back" to "three")),
            ),
        ).data
        assertEquals(2, data["created"]!!.jsonPrimitive.int)
        assertEquals(1, data["skipped"]!!.jsonPrimitive.int)
        assertEquals(1, data["failed"]!!.jsonPrimitive.int)
        assertEquals(20, data["max_notes_per_batch"]!!.jsonPrimitive.int)
        val results = data["results"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("created", "skipped", "failed", "created"), results.map { it["status"]!!.jsonPrimitive.content })
        assertEquals("Missing required fields: Back", results[2]["error"]!!.jsonPrimitive.content)
        val first = results[0]["note_id"]!!.jsonPrimitive.long
        assertEquals(listOf("batch", "num"), col.note(first).tags)
        assertEquals(listOf("tres", "three"), col.note(results[3]["note_id"]!!.jsonPrimitive.long).fields)
        assertEquals("Created 2 of 4 notes in deck \"Spanish\" (1 skipped, 1 failed)", data["message"]!!.jsonPrimitive.content)
        assertEquals(1, col.writes.size)
    }

    @Test
    fun `add_notes whose ids cannot be found again reports them created with no id`() {
        val data = AddNotesTool(repo) { col.nowMillis + 1_000 }.callWith(
            "deck_name" to "Spanish",
            "model_name" to "Basic",
            "notes" to listOf(mapOf("fields" to mapOf("Front" to "uno", "Back" to "one"))),
        ).data
        assertEquals(1, data["created"]!!.jsonPrimitive.int)
        val result = data["results"]!!.jsonArray.single().jsonObject
        assertEquals("created", result["status"]!!.jsonPrimitive.content)
        assertEquals(kotlinx.serialization.json.JsonNull, result["note_id"])
        assertTrue(result["note"]!!.jsonPrimitive.content.startsWith("Added, but its ID could not be found again"))
    }

    @Test
    fun `add_notes that fails part-way reads back which notes were added`() {
        // AnkiDroid adds row by row: the second row fails after the first was added.
        col.afterWrite = { w ->
            if (w.op == "bulkInsert") {
                col.notes.removeAll { it.fields[0] == "dos" }
                throw IllegalArgumentException("Incorrect flds argument")
            }
        }
        val data = addNotes().callWith(
            "deck_name" to "Spanish",
            "model_name" to "Basic",
            "notes" to listOf(
                mapOf("fields" to mapOf("Front" to "uno", "Back" to "one")),
                mapOf("fields" to mapOf("Front" to "dos", "Back" to "two")),
            ),
        ).data
        val results = data["results"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("created", "failed"), results.map { it["status"]!!.jsonPrimitive.content })
        assertEquals(listOf("uno", "one"), col.note(results[0]["note_id"]!!.jsonPrimitive.long).fields)
        assertTrue(results[1]["error"]!!.jsonPrimitive.content.contains("Incorrect flds argument"))
    }

    @Test
    fun `add_notes with no answer from AnkiDroid reports unfound notes as unknown, not failed`() {
        // AnkiDroid added the first note, then the call broke off without an answer.
        col.afterWrite = { w ->
            if (w.op == "bulkInsert") {
                col.notes.removeAll { it.fields[0] == "dos" }
                throw RuntimeException("Unknown transaction code")
            }
        }
        val data = addNotes().callWith(
            "deck_name" to "Spanish",
            "model_name" to "Basic",
            "notes" to listOf(
                mapOf("fields" to mapOf("Front" to "uno", "Back" to "one")),
                mapOf("fields" to mapOf("Front" to "dos", "Back" to "two")),
            ),
        ).data
        val results = data["results"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("created", "unknown"), results.map { it["status"]!!.jsonPrimitive.content })
        assertTrue(results[1]["note"]!!.jsonPrimitive.content.contains("may still have been added"))
        assertEquals(0, data["failed"]!!.jsonPrimitive.int)
        assertEquals(1, data["unknown"]!!.jsonPrimitive.int)
        assertEquals("Created 1 of 2 notes in deck \"Spanish\" (1 unknown)", data["message"]!!.jsonPrimitive.content)
    }

    @Test
    fun `add_notes takes at most 20`() {
        val notes = List(21) { mapOf("fields" to mapOf("Front" to "f$it", "Back" to "b")) }
        assertTrue(
            addNotes().callWith("deck_name" to "Spanish", "model_name" to "Basic", "notes" to notes).text
                .startsWith("[limit_exceeded] Too many notes: 21 (maximum is 20)"),
        )
    }

    // --- update_note_fields ---

    @Test
    fun `update_note_fields replaces named fields and confirms`() {
        val data = UpdateNoteFieldsTool(repo).callWith("id" to 101, "fields" to mapOf("Back" to "hi")).data
        assertEquals(listOf("hola", "hi"), col.note(101).fields)
        assertEquals("Successfully updated 1 field in note", data["message"]!!.jsonPrimitive.content)
        assertNull(data["patched"])
    }

    @Test
    fun `update_note_fields confirms a value AnkiDroid normalised to NFC`() {
        storeNfc()
        val result = UpdateNoteFieldsTool(repo).callWith("id" to 101, "fields" to mapOf("Back" to "café"))
        assertFalse(result.text, result.isError)
        assertEquals("café", col.note(101).fields[1])
    }

    @Test
    fun `update_note_fields patches exactly once`() {
        val data = UpdateNoteFieldsTool(repo).callWith("id" to 101, "field_name" to "Back", "old_str" to "ell", "new_str" to "ELL").data
        assertEquals("hELLo", col.note(101).fields[1])
        assertTrue(data["patched"]!!.jsonPrimitive.boolean)
        val miss = UpdateNoteFieldsTool(repo).callWith("id" to 101, "field_name" to "Back", "old_str" to "zzz", "new_str" to "x")
        assertTrue(miss.text, miss.text.startsWith("[patch_no_match] old_str not found in field \"Back\" of note 101"))
        assertTrue(miss.text, miss.text.contains("'current_content_excerpt': 'hELLo', 'note_id': 101, 'field_name': 'Back'"))
    }

    @Test
    fun `update_note_fields input errors and a missing note`() {
        assertTrue(UpdateNoteFieldsTool(repo).callWith("id" to 101).text.startsWith("[validation_error] No input provided"))
        assertTrue(
            UpdateNoteFieldsTool(repo).callWith("id" to 101, "fields" to mapOf("Back" to "x"), "old_str" to "a", "new_str" to "b")
                .text.startsWith("[validation_error] Cannot combine 'fields'"),
        )
        assertTrue(UpdateNoteFieldsTool(repo).callWith("id" to 101, "fields" to emptyMap<String, String>()).text.contains("was provided but is empty"))
        assertEquals(
            "Note not found with ID 5. The note ID is invalid or the note has been deleted. (hint: Use find_notes to get valid note IDs.)",
            UpdateNoteFieldsTool(repo).callWith("id" to 5, "fields" to mapOf("Back" to "x")).text,
        )
        assertTrue(
            UpdateNoteFieldsTool(repo).callWith("id" to 101, "fields" to mapOf("Nope" to "x")).text
                .startsWith("Invalid fields for model \"Basic\": Nope. Valid fields are: Front, Back."),
        )
        assertTrue(col.writes.isEmpty())
    }

    // --- update_notes ---

    @Test
    fun `update_notes dry run writes nothing`() {
        val data = UpdateNotesTool(repo).callWith(
            "notes" to listOf(mapOf("id" to 101, "fields" to mapOf("Back" to "x")), mapOf("id" to 999, "fields" to mapOf("Back" to "y"))),
            "dry_run" to true,
        ).data
        assertEquals(1, data["would_update"]!!.jsonPrimitive.int)
        assertEquals(1, data["would_fail"]!!.jsonPrimitive.int)
        assertEquals("Dry run: would update 1 of 2 notes (1 would fail)", data["message"]!!.jsonPrimitive.content)
        assertTrue(col.writes.isEmpty())
    }

    @Test
    fun `update_notes updates, reports retryable field errors and confirms`() {
        val data = UpdateNotesTool(repo).callWith(
            "notes" to listOf(
                mapOf("id" to 101, "fields" to mapOf("Back" to "x")),
                mapOf("id" to 102, "fields" to mapOf("Nope" to "y")),
                mapOf("id" to 103, "fields" to mapOf("Front" to "GATO")),
            ),
        ).data
        assertEquals(2, data["updated"]!!.jsonPrimitive.int)
        assertEquals(1, data["retryable_failed"]!!.jsonPrimitive.int)
        assertEquals("Updated 2 of 3 notes (1 failed, 1 retryable)", data["message"]!!.jsonPrimitive.content)
        assertEquals("GATO", col.note(103).fields[0])
    }

    @Test
    fun `a batch that runs out of budget says what it did`() {
        // Each write takes 7 s of the tool's 12 s budget: under 3 s are left for the third, so it is not sent.
        col.refuse = {
            col.elapsedMs += 7_000
            null
        }
        val result = UpdateNotesTool(repo).callWith(
            "notes" to listOf(101, 102, 103).map { mapOf("id" to it, "fields" to mapOf("Back" to "new")) },
        )
        assertTrue(result.isError)
        assertTrue(result.text, result.text.startsWith("Did 2 of 3 notes before stopping. Done: 101, 102. Not done: 103. Reason: Did not send updating note 103 to AnkiDroid: this request had less than 3 seconds left. Nothing was changed; ask again."))
        assertEquals("new", col.note(102).fields[1])
        assertEquals("cat", col.note(103).fields[1])
        assertEquals(2, col.writes.size)
    }

    @Test
    fun `an unknown outcome mid-batch is named`() {
        col.refuse = { w -> if (w.path == "notes/102") RuntimeException("Unknown transaction code") else null }
        val result = UpdateNotesTool(repo).callWith(
            "notes" to listOf(101, 102, 103).map { mapOf("id" to it, "fields" to mapOf("Back" to "new")) },
        )
        assertTrue(result.text, result.text.startsWith("Did 1 of 3 notes before stopping. Done: 101. Unknown whether 102 was changed. Not done: 103."))
    }

    // --- delete_notes ---

    @Test
    fun `delete_notes needs confirmation, previews and deletes with confirmation`() {
        assertTrue(DeleteNotesTool(repo).callWith("notes" to listOf(101)).text.startsWith("[validation_error] Deletion not confirmed."))
        assertTrue(
            DeleteNotesTool(repo).callWith("notes" to listOf(101), "confirmDeletion" to false).text
                .startsWith("[validation_error] Deletion not confirmed."),
        )
        val preview = DeleteNotesTool(repo).callWith("notes" to listOf(101, 777), "confirmDeletion" to false, "dry_run" to true).data
        assertEquals(1, preview["deletedCount"]!!.jsonPrimitive.int)
        assertEquals(1, preview["cardsDeleted"]!!.jsonPrimitive.int)
        assertEquals("Dry run: would delete 1 note(s) and 1 card(s). 1 note(s) were not found.", preview["message"]!!.jsonPrimitive.content)
        assertTrue(col.writes.isEmpty())

        val done = DeleteNotesTool(repo).callWith("notes" to listOf(101, 102), "confirmDeletion" to true).data
        assertEquals(2, done["deletedCount"]!!.jsonPrimitive.int)
        assertEquals("Successfully deleted 2 note(s) and 2 card(s)", done["message"]!!.jsonPrimitive.content)
        assertTrue(col.notes.none { it.id == 101L || it.id == 102L })
    }

    @Test
    fun `delete_notes limits and missing notes`() {
        assertTrue(
            DeleteNotesTool(repo).callWith("notes" to (1L..21L).toList(), "confirmDeletion" to true).text
                .startsWith("[limit_exceeded] Cannot delete more than 20 notes at once. Requested: 21 notes"),
        )
        val none = DeleteNotesTool(repo).callWith("notes" to listOf(5), "confirmDeletion" to true).data
        assertEquals("No notes were deleted (none of the provided IDs were valid)", none["message"]!!.jsonPrimitive.content)
    }

    @Test
    fun `write tools need AnkiDroid 2_24`() {
        val old = col.repository(FakeAvailability(version = AnkiDroidVersion(22303300, "2.23.3")))
        assertTrue(DeleteNotesTool(old).callWith("notes" to listOf(101), "confirmDeletion" to true).text.contains("Update AnkiDroid to 2.24"))
        assertTrue(CreateDeckTool(old).callWith("deck_name" to "X").text.contains("Update AnkiDroid to 2.24"))
        assertTrue(col.writes.isEmpty())
    }
}
