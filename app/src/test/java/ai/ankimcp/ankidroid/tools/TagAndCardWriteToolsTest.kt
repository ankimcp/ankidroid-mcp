package ai.ankimcp.ankidroid.tools

import ai.ankimcp.ankidroid.anki.AnkiDroidVersion
import ai.ankimcp.ankidroid.anki.FakeAvailability
import ai.ankimcp.ankidroid.anki.SyncRequest
import ai.ankimcp.ankidroid.tools.FakeCollection.FCard
import ai.ankimcp.ankidroid.tools.FakeCollection.FDeck
import ai.ankimcp.ankidroid.tools.FakeCollection.FModel
import ai.ankimcp.ankidroid.tools.FakeCollection.FNote
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
import java.time.ZoneOffset

class TagAndCardWriteToolsTest {
    private val col = FakeCollection().apply {
        decks += FDeck(2, "Spanish")
        decks += FDeck(9, "Cram", dyn = true)
        models += FModel(10, "Basic", listOf("Front", "Back"))
        notes += FNote(101, 10, listOf("hola <img src=\"a.png\">", "hello"), tags = listOf("Vocab", "vocab::verbs", "es"))
        notes += FNote(102, 10, listOf("adios", "bye"), tags = listOf("es"))
        cards += FCard(1001, 101, did = 2, queue = 0, type = 0, reps = 0)
        cards += FCard(1002, 102, did = 2)
        cards += FCard(1003, 102, ord = 1, did = 9, odid = 2)
    }
    private val repo = col.repository()
    private val ratings = RatingGuard()

    // --- tags ---

    @Test
    fun `tag_add adds case-insensitively and counts the notes changed`() {
        val data = TagAddTool(repo).callWith("note_ids" to listOf(101, 102, 999), "tags" to "ES new").data
        assertEquals(2, data["added_count"]!!.jsonPrimitive.int)
        assertEquals("Added tags 'ES new' to 2 note(s)", data["message"]!!.jsonPrimitive.content)
        assertEquals(listOf("es", "new", "Vocab", "vocab::verbs"), col.note(101).tags)
        assertEquals(listOf("es", "new"), col.note(102).tags)
    }

    @Test
    fun `tag_remove removes children too`() {
        val data = TagRemoveTool(repo).callWith("note_ids" to listOf(101, 102), "tags" to "VOCAB").data
        assertEquals(1, data["removed_count"]!!.jsonPrimitive.int)
        assertEquals(listOf("es"), col.note(101).tags)
        assertEquals(1, col.writes.size)
    }

    @Test
    fun `tag_replace writes each note once and rejects a conflicting new tag`() {
        val data = TagReplaceTool(repo).callWith("note_ids" to listOf(101, 102), "old_tag" to "es", "new_tag" to "spanish").data
        assertEquals(2, data["added_count"]!!.jsonPrimitive.int)
        assertEquals(2, data["removed_count"]!!.jsonPrimitive.int)
        assertEquals(listOf("spanish"), col.note(102).tags)
        assertEquals(2, col.writes.size)
        assertTrue(
            TagReplaceTool(repo).callWith("note_ids" to listOf(101), "old_tag" to "vocab", "new_tag" to "Vocab::x").text
                .startsWith("new_tag is a child of old_tag"),
        )
    }

    @Test
    fun `tag_batch applies operations in order and reports each`() {
        val data = TagBatchTool(repo).callWith(
            "operations" to listOf(
                mapOf("type" to "add", "note_ids" to listOf(102), "tags" to "tmp"),
                mapOf("type" to "remove", "note_ids" to listOf(102), "tags" to "tmp"),
                mapOf("type" to "add", "note_ids" to emptyList<Long>(), "tags" to "x"),
            ),
        ).data
        assertEquals(2, data["succeeded"]!!.jsonPrimitive.int)
        assertEquals(1, data["failed"]!!.jsonPrimitive.int)
        assertEquals(listOf("es"), col.note(102).tags)
        val results = data["results"]!!.jsonArray.map { it.jsonObject }
        assertEquals(1, results[0]["affected_count"]!!.jsonPrimitive.int)
        assertEquals("note_ids is required and cannot be empty", results[2]["error"]!!.jsonPrimitive.content)
    }

    @Test
    fun `tag tools take at most 20 notes, across operations for tag_batch`() {
        assertTrue(TagAddTool(repo).callWith("note_ids" to (1L..21L).toList(), "tags" to "x").text.startsWith("[limit_exceeded] Too many notes: 21"))
        val ops = List(3) { mapOf("type" to "add", "note_ids" to (1L..7L).toList(), "tags" to "x") }
        assertTrue(TagBatchTool(repo).callWith("operations" to ops).text.startsWith("[limit_exceeded] Too many note IDs across operations: 21"))
        assertTrue(TagAddTool(repo).callWith("note_ids" to listOf(101), "tags" to " ").text.startsWith("tags is required and cannot be empty"))
    }

    @Test
    fun `tag_batch that stops lists unchanged notes and the notes of later operations as not done`() {
        col.refuse = { w -> if (w.path == "notes/102") RuntimeException("Unknown transaction code") else null }
        val result = TagBatchTool(repo).callWith(
            "operations" to listOf(
                mapOf("type" to "add", "note_ids" to listOf(101), "tags" to "es"),
                mapOf("type" to "add", "note_ids" to listOf(101, 102), "tags" to "new"),
                mapOf("type" to "remove", "note_ids" to listOf(102), "tags" to "es"),
            ),
        )
        assertTrue(result.isError)
        assertTrue(
            result.text,
            result.text.startsWith(
                "Did 1 of 4 note updates before stopping. Done: 101. Unchanged (nothing to do): 101. " +
                    "Unknown whether 102 was changed. Not done: 102. Reason: ",
            ),
        )
    }

    @Test
    fun `tags given as a list get the add-on's format hint`() {
        val result = TagAddTool(repo).callWith("note_ids" to listOf(101), "tags" to listOf("a", "b"))
        assertTrue(result.text, result.text.contains("tags must be a space-separated string, e.g. \"tag1 tag2\""))
    }

    @Test
    fun `a tag write AnkiDroid did not keep is reported`() {
        col.afterWrite = { col.notes.replaceAll { if (it.id == 102L) it.copy(tags = listOf("es")) else it } }
        val result = TagAddTool(repo).callWith("note_ids" to listOf(102), "tags" to "x")
        assertTrue(result.isError)
        assertTrue(result.text, result.text.startsWith("AnkiDroid reported the tags as changed, but re-reading shows different tags on note(s) 102"))
    }

    // --- cards ---

    @Test
    fun `card_bury buries through the schedule and confirms by re-reading`() {
        val data = CardBuryTool(repo).callWith("card_ids" to listOf(1001, 1002, 4242)).data
        assertEquals(2, data["buried_count"]!!.jsonPrimitive.int)
        assertEquals(-3, col.card(1001).queue)
        assertNull(data["failed"])
        assertEquals("schedule", col.writes.first().path)
        assertEquals(1, col.writes.first().values["buried"])
    }

    @Test
    fun `card_bury leaves a suspended card alone and says why`() {
        col.cards.replaceAll { if (it.id == 1002L) it.copy(queue = -1) else it }
        val data = CardBuryTool(repo).callWith("card_ids" to listOf(1002)).data
        assertEquals(0, data["buried_count"]!!.jsonPrimitive.int)
        assertTrue(data["failed"]!!.jsonArray[0].jsonObject["error"]!!.jsonPrimitive.content.contains("card is suspended; Anki does not bury suspended cards"))
        assertTrue(col.writes.isEmpty())
    }

    @Test
    fun `a bury AnkiDroid swallowed is a failure, not a success`() {
        col.swallowSchedule = true
        val data = CardSuspendTool(repo).callWith("card_ids" to listOf(1001)).data
        assertEquals(0, data["suspended_count"]!!.jsonPrimitive.int)
        assertTrue(data["failed"]!!.jsonArray[0].jsonObject["error"]!!.jsonPrimitive.content.contains("re-reading the card shows no change"))
    }

    @Test
    fun `card_set_flag needs AnkiDroid 2_25 and a flag 0-7`() {
        val v24 = col.repository(FakeAvailability(version = AnkiDroidVersion(22401300, "2.24.1")))
        assertTrue(CardSetFlagTool(v24).callWith("card_ids" to listOf(1001), "flag" to 1).text.contains("Update AnkiDroid to 2.25 or newer"))
        assertTrue(CardSetFlagTool(repo).callWith("card_ids" to listOf(1001), "flag" to 8).text.startsWith("Invalid flag value: 8"))
        val data = CardSetFlagTool(repo).callWith("card_ids" to listOf(1001), "flag" to 3).data
        assertEquals("Set green flag on 1 card(s)", data["message"]!!.jsonPrimitive.content)
        assertEquals(3, col.card(1001).flags)
    }

    @Test
    fun `card_change_deck creates the deck, moves cards and leaves cards in filtered decks alone`() {
        val data = CardChangeDeckTool(repo).callWith("card_ids" to listOf(1001, 1003), "deck" to "Spanish::Verbs").data
        val newDeck = col.decks.single { it.name == "Spanish::Verbs" }.id
        assertEquals(newDeck, data["deck_id"]!!.jsonPrimitive.long)
        assertEquals(1, data["moved"]!!.jsonPrimitive.int)
        assertEquals(newDeck, col.card(1001).did)
        assertEquals(9L, col.card(1003).did)
        assertTrue(data["failed"]!!.jsonArray[0].jsonObject["error"]!!.jsonPrimitive.content.contains("is in filtered deck Cram"))
        assertTrue(CardChangeDeckTool(repo).callWith("card_ids" to listOf(1001), "deck" to "Cram").text.startsWith("Cards cannot be moved to a filtered deck"))
    }

    @Test
    fun `card tools take at most 20 cards`() {
        assertTrue(CardBuryTool(repo).callWith("card_ids" to (1L..21L).toList()).text.startsWith("[limit_exceeded] Too many cards: 21"))
        assertEquals("card_ids is required and cannot be empty (hint: Provide at least one card ID)", CardBuryTool(repo).callWith("card_ids" to emptyList<Long>()).text)
    }

    // --- rate_card ---

    @Test
    fun `rate_card answers through the schedule and confirms the review count`() {
        val data = RateCardTool(repo, ratings, ZoneOffset.UTC).callWith("card_id" to 1002, "rating" to 3).data
        assertEquals("review", data["card_type"]!!.jsonPrimitive.content)
        assertEquals(12, data["new_interval"]!!.jsonPrimitive.int)
        assertEquals("Card rated as 'Good'. Next review in 12 days", data["message"]!!.jsonPrimitive.content)
        assertNull(data["next_review"])
        val write = col.writes.single()
        assertEquals(3, write.values["answer_ease"])
        assertEquals(6L, col.card(1002).reps)
        assertTrue(RateCardTool(repo, ratings).callWith("card_id" to 1002, "rating" to 3, "time_taken" to 4200).text.startsWith("Unknown argument(s) for rate_card: time_taken"))
    }

    @Test
    fun `rate_card gives a learning card the real time until it is due, and a day-learning card no time`() {
        val now = col.nowMillis / 1000
        col.afterWrite = { col.cards.replaceAll { if (it.id == 1001L) it.copy(type = 1, queue = 1, due = now + 600, ivl = 0) else it } }
        val learning = RateCardTool(repo, ratings, ZoneOffset.UTC) { now }.callWith("card_id" to 1001, "rating" to 1).data
        assertEquals("learning", learning["card_type"]!!.jsonPrimitive.content)
        assertEquals("2027-01-15 08:10", learning["next_review"]!!.jsonPrimitive.content)
        assertEquals("Card rated as 'Again'. Next review: 2027-01-15 08:10 (in 10 minutes)", learning["message"]!!.jsonPrimitive.content)
        assertNull(learning["new_interval"])

        col.afterWrite = { col.cards.replaceAll { if (it.id == 1002L) it.copy(type = 3, queue = 3, due = 20_000, reps = 9) else it } }
        val dayLearning = RateCardTool(repo, ratings, ZoneOffset.UTC) { now }.callWith("card_id" to 1002, "rating" to 1).data
        assertEquals("relearning", dayLearning["card_type"]!!.jsonPrimitive.content)
        assertEquals(setOf("card_id", "rating", "card_type", "message"), dayLearning.keys)
        assertEquals("Card rated as 'Again'", dayLearning["message"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a second rating of a card not shown in between is refused`() {
        val now = col.nowMillis / 1000
        RateCardTool(repo, ratings) { now }.callWith("card_id" to 1002, "rating" to 3).data
        // Other cards are not affected, and a minute later is no different.
        assertFalse(RateCardTool(repo, ratings) { now }.callWith("card_id" to 1001, "rating" to 3).isError)
        val result = RateCardTool(repo, ratings) { now + 300 }.callWith("card_id" to 1001, "rating" to 3)
        assertTrue(
            result.text,
            result.text.startsWith("Card 1001 was rated 300 seconds ago and has not been shown since; rating it again now would record a second review"),
        )
        assertEquals(2, col.writes.size)
    }

    @Test
    fun `only get_due_cards returning the card again allows a new rating, present_card does not`() {
        val now = col.nowMillis / 1000
        RateCardTool(repo, ratings) { now }.callWith("card_id" to 1002, "rating" to 1).data
        // present_card is where an unconfirmed rating sends Claude to check; it must not clear the guard.
        PresentCardTool(repo).callWith("card_id" to 1002).data
        assertTrue(RateCardTool(repo, ratings) { now + 5 }.callWith("card_id" to 1002, "rating" to 3).isError)

        col.scheduled = 102L to 0
        GetDueCardsTool(repo, ratings).callWith("deck_name" to "Spanish").data
        assertFalse(RateCardTool(repo, ratings) { now + 6 }.callWith("card_id" to 1002, "rating" to 3).isError)
        assertEquals(2, col.writes.size)
    }

    @Test
    fun `without memory of earlier ratings, a review under 10 s old is refused`() {
        val now = col.nowMillis / 1000
        col.cards.replaceAll { if (it.id == 1002L) it.copy(lastReview = now - 4) else it }
        val fresh = RatingGuard()
        val result = RateCardTool(repo, fresh) { now }.callWith("card_id" to 1002, "rating" to 3)
        assertTrue(result.text, result.text.startsWith("Card 1002 was rated 4 seconds ago"))
        assertTrue(col.writes.isEmpty())
        assertFalse(RateCardTool(repo, RatingGuard()) { now + 6 }.callWith("card_id" to 1002, "rating" to 3).isError)
    }

    @Test
    fun `a rating whose confirming read fails says it was accepted and must not be repeated`() {
        col.afterWrite = { col.nullReads = { q -> q.path == "cards" } }
        val result = RateCardTool(repo, ratings).callWith("card_id" to 1002, "rating" to 3)
        assertTrue(result.isError)
        assertTrue(result.text, result.text.startsWith("The rating of card 1002 was sent to AnkiDroid and accepted, but could not be confirmed: "))
        assertTrue(result.text, result.text.endsWith("Do not rate this card again; check it with present_card."))
    }

    @Test
    fun `a rating with under 3 s of budget left is not sent`() {
        // The card read uses 10 s of the 12 s budget.
        col.nullReads = {
            col.elapsedMs = 10_000
            false
        }
        val result = RateCardTool(repo, ratings).callWith("card_id" to 1002, "rating" to 3)
        assertEquals(
            "Did not send recording the review to AnkiDroid: this request had less than 3 seconds left. Nothing was changed; ask again.",
            result.text,
        )
        assertTrue(col.writes.isEmpty())
    }

    @Test
    fun `a review AnkiDroid swallowed is an error`() {
        col.swallowSchedule = true
        val result = RateCardTool(repo, ratings).callWith("card_id" to 1002, "rating" to 1)
        assertTrue(result.text, result.text.startsWith("AnkiDroid did not record the review of card 1002"))
    }

    @Test
    fun `rate_card validates rating then card, and always sends time_taken 0`() {
        assertTrue(RateCardTool(repo, ratings).callWith("card_id" to 1002, "rating" to 5).text.startsWith("Invalid rating: 5. Must be 1-4"))
        assertTrue(RateCardTool(repo, ratings).callWith("card_id" to -1, "rating" to 3).text.startsWith("card_id must be a positive integer, got: -1"))
        assertTrue(RateCardTool(repo, ratings).callWith("card_id" to 77, "rating" to 3).text.startsWith("Card not found: 77"))
        RateCardTool(repo, ratings).callWith("card_id" to 1002, "rating" to 2)
        assertEquals(0L, col.writes.single().values["time_taken"])
    }

    // --- get_due_cards skip flags ---

    @Test
    fun `get_due_cards with skip_images buries cards with images and returns the next one`() {
        var queue = mutableListOf(101L to 0, 102L to 0)
        col.scheduled = queue.first()
        col.refuse = { w ->
            if (w.path == "schedule") {
                queue.removeAt(0)
                col.scheduled = queue.firstOrNull()
            }
            null
        }
        val data = GetDueCardsTool(repo, ratings).callWith("deck_name" to "Spanish", "skip_images" to true).data
        assertEquals(1002L, data["cards"]!!.jsonArray.single().jsonObject["cardId"]!!.jsonPrimitive.long)
        assertEquals(1, data["skipped"]!!.jsonObject["images"]!!.jsonPrimitive.int)
        assertEquals(0, data["skipped"]!!.jsonObject["audio"]!!.jsonPrimitive.int)
        assertEquals(-3, col.card(1001).queue)
        assertEquals(listOf(1001L), data["buried_card_ids"]!!.jsonArray.map { it.jsonPrimitive.long })
        assertTrue(data["message"]!!.jsonPrimitive.content.endsWith("buried 1 cards with media)"))
    }

    @Test
    fun `get_due_cards stops if AnkiDroid does not bury, without counting the card`() {
        col.scheduled = 101L to 0
        col.swallowSchedule = true
        val result = GetDueCardsTool(repo, ratings).callWith("deck_name" to "Spanish", "skip_images" to true)
        assertEquals("AnkiDroid did not bury card 1001.", result.text)
    }

    /** Notes 201.. with an image each, their cards 2001.. due in that order, then note 102 (no media). */
    private fun mediaQueue(count: Int): MutableList<Pair<Long, Int>> {
        (1..count).forEach { i ->
            col.notes += FNote(200L + i, 10, listOf("<img src=\"$i.png\">", "x"))
            col.cards += FCard(2000L + i, 200L + i, did = 2)
        }
        val queue = ((1..count).map { 200L + it to 0 } + (102L to 0)).toMutableList()
        col.scheduled = queue.first()
        col.refuse = { w ->
            if (w.path == "schedule") {
                queue.removeAt(0)
                col.scheduled = queue.firstOrNull()
            }
            null
        }
        return queue
    }

    @Test
    fun `get_due_cards buries at most 5 cards per call and lists them`() {
        mediaQueue(6)
        val data = GetDueCardsTool(repo, ratings).callWith("deck_name" to "Spanish", "skip_images" to true).data
        assertEquals(0, data["returned"]!!.jsonPrimitive.int)
        assertEquals((2001L..2005L).toList(), data["buried_card_ids"]!!.jsonArray.map { it.jsonPrimitive.long })
        assertEquals(5, data["skipped"]!!.jsonObject["images"]!!.jsonPrimitive.int)
        assertTrue(data["message"]!!.jsonPrimitive.content.contains("Call get_due_cards again to continue"))
        assertEquals(2, col.card(2006).queue)

        val next = GetDueCardsTool(repo, ratings).callWith("deck_name" to "Spanish", "skip_images" to true).data
        assertEquals(listOf(2006L), next["buried_card_ids"]!!.jsonArray.map { it.jsonPrimitive.long })
        assertEquals(1002L, next["cards"]!!.jsonArray.single().jsonObject["cardId"]!!.jsonPrimitive.long)
    }

    @Test
    fun `a failed read after a bury lists the cards buried so far`() {
        mediaQueue(2)
        var notesReads = 0
        col.nullReads = { q -> q.path == "notes" && ++notesReads >= 2 }
        val result = GetDueCardsTool(repo, ratings).callWith("deck_name" to "Spanish", "skip_images" to true)
        assertTrue(result.isError)
        assertTrue(
            result.text,
            result.text.startsWith("Stopped while skipping cards with media: buried 1 card(s) so far (images: 1, audio: 0; card ids: 2001). "),
        )
    }

    // --- sync ---

    @Test
    fun `sync posts the notification and says what happens next`() {
        val data = SyncTool(repo) { SyncRequest.NotificationPosted }.callWith().data
        assertEquals("requested", data["status"]!!.jsonPrimitive.content)
        assertTrue(data["message"]!!.jsonPrimitive.content.contains("must tap the \"Tap to sync AnkiDroid\" notification"))
        assertTrue(data["message"]!!.jsonPrimitive.content.contains("signed in to AnkiWeb"))
    }

    @Test
    fun `sync says why it could not ask`() {
        val blocked = SyncTool(repo) { SyncRequest.NotificationBlocked("Notification permission is off") }.callWith()
        assertTrue(blocked.text, blocked.isError && blocked.text.startsWith("Could not ask for a sync: Notification permission is off"))
        val missing = SyncTool(repo) { SyncRequest.AnkiDroidMissing }.callWith()
        assertTrue(missing.text.contains("AnkiDroid does not accept sync requests"))
        assertTrue(SyncTool(repo) { SyncRequest.NotificationPosted }.callWith("job_id" to "x").text.startsWith("Unknown argument(s) for sync"))
    }
}
