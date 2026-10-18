package ai.ankimcp.ankidroid.tools

import ai.ankimcp.ankidroid.anki.AnkiDroidVersion
import ai.ankimcp.ankidroid.anki.CardData
import ai.ankimcp.ankidroid.anki.FakeAvailability
import ai.ankimcp.ankidroid.tools.FakeCollection.FCard
import ai.ankimcp.ankidroid.tools.FakeCollection.FDeck
import ai.ankimcp.ankidroid.tools.FakeCollection.FModel
import ai.ankimcp.ankidroid.tools.FakeCollection.FNote
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
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

class CardReadToolsTest {
    private val clozeQuestion = """<span class="cloze" data-cloze="Madrid" data-ordinal="1">[...]</span> is the capital"""
    private val col = FakeCollection().apply {
        decks += FDeck(2, "Spanish", counts = "[1,4,6]")
        decks += FDeck(3, "Spanish::Verbs")
        decks += FDeck(9, "Cram", dyn = true)
        models += FModel(10, "Basic", listOf("Front", "Back"), css = ".card{}")
        models += FModel(11, "Cloze", listOf("Text", "Extra"), css = ".cloze{}")
        notes += FNote(101, 10, listOf("hola <img src=\"a.png\">", "hello [sound:a.mp3]"), tags = listOf("verb"))
        notes += FNote(103, 11, listOf("{{c1::Madrid}} is the capital", "geo"), tags = listOf("geo", "es"))
        cards += FCard(1001, 101, did = 3, question = "hola", answer = "hola<hr id=answer>hello", queue = 1, due = 1_700_000_000)
        cards += FCard(1003, 103, ord = 0, did = 9, odid = 2, question = clozeQuestion, answer = "<span class=\"cloze\">Madrid</span>")
        cards += FCard(1005, 103, ord = 1, did = 2, type = 0, queue = 0, ivl = 0)
    }
    private val repo = col.repository()
    private val ratings = RatingGuard()

    @Test
    fun `present_card styles the question, strips the cloze answer and names both decks`() {
        val card = PresentCardTool(repo).callWith("card_id" to 1003).data["card"]!!.jsonObject

        assertEquals(1003L, card["card_id"]!!.jsonPrimitive.long)
        assertEquals("<style>.cloze{}</style><span class=\"cloze\" data-ordinal=\"1\">[...]</span> is the capital", card["question"]!!.jsonPrimitive.content)
        assertEquals("Spanish", card["deck_name"]!!.jsonPrimitive.content)
        assertEquals("Cram", card["filtered_deck_name"]!!.jsonPrimitive.content)
        assertEquals("Cloze", card["note_type"]!!.jsonPrimitive.content)
        assertEquals(100L, card["due"]!!.jsonPrimitive.long)
        assertEquals(10L, card["interval"]!!.jsonPrimitive.long)
        assertEquals(2500L, card["ease_factor"]!!.jsonPrimitive.long)
        assertEquals(5L, card["reviews"]!!.jsonPrimitive.long)
        assertEquals(1L, card["lapses"]!!.jsonPrimitive.long)
        assertNull(card["answer"])
        assertEquals("cid:1003", col.queriesTo("cards").first().selection)
    }

    @Test
    fun `present_card with show_answer adds the styled answer, filtered_deck_name is null outside filtered decks`() {
        val card = PresentCardTool(repo).callWith("card_id" to 1001, "show_answer" to true).data["card"]!!.jsonObject
        assertEquals("<style>.card{}</style>hola<hr id=answer>hello", card["answer"]!!.jsonPrimitive.content)
        assertEquals("Spanish::Verbs", card["deck_name"]!!.jsonPrimitive.content)
        assertEquals(JsonNull, card["filtered_deck_name"])
    }

    @Test
    fun `present_card for an unknown card is the add-on's error`() {
        val result = PresentCardTool(repo).callWith("card_id" to 4242)
        assertTrue(result.isError)
        assertEquals(
            "Card not found with ID 4242 (hint: Verify the card ID is correct using get_due_cards or cards_stats " +
                "(find_notes returns note IDs, not card IDs))",
            result.text,
        )
        assertEquals("card_id must be an integer", PresentCardTool(repo).callWith("card_id" to "abc").text)
    }

    @Test
    fun `get_due_cards returns the scheduler's next card without the answer`() {
        col.scheduled = 103L to 0
        val data = GetDueCardsTool(repo, ratings).callWith("deck_name" to "spanish").data

        val card = data["cards"]!!.jsonArray.single().jsonObject
        assertEquals(1003L, card["cardId"]!!.jsonPrimitive.long)
        assertEquals("<span class=\"cloze\" data-ordinal=\"1\">[...]</span> is the capital", card["front"]!!.jsonPrimitive.content)
        assertNull(card["back"])
        assertEquals("Spanish", card["deckName"]!!.jsonPrimitive.content)
        assertEquals("Cram", card["filteredDeckName"]!!.jsonPrimitive.content)
        assertEquals("Cloze", card["modelName"]!!.jsonPrimitive.content)
        assertEquals("review", card["queueType"]!!.jsonPrimitive.content)
        assertFalse(card["has_images"]!!.jsonPrimitive.boolean)
        assertFalse(card["has_audio"]!!.jsonPrimitive.boolean)
        val counts = data["counts"]!!.jsonObject
        assertEquals(6, counts["new"]!!.jsonPrimitive.int)
        assertEquals(1, counts["learning"]!!.jsonPrimitive.int)
        assertEquals(4, counts["review"]!!.jsonPrimitive.int)
        assertEquals(11, data["total"]!!.jsonPrimitive.int)
        assertEquals(1, data["returned"]!!.jsonPrimitive.int)
        assertEquals("Next card in scheduler order (total 11 due cards)", data["message"]!!.jsonPrimitive.content)
        val schedule = col.queriesTo("schedule").single()
        assertEquals("limit=?,deckID=?", schedule.selection)
        assertEquals(listOf("1", "2"), schedule.selectionArgs)
    }

    @Test
    fun `get_due_cards with include_answer adds a styled back and media flags`() {
        col.scheduled = 101L to 0
        val card = GetDueCardsTool(repo, ratings).callWith("deck_name" to "Spanish::Verbs", "include_answer" to true)
            .data["cards"]!!.jsonArray.single().jsonObject
        assertEquals("<style>.card{}</style>hola<hr id=answer>hello", card["back"]!!.jsonPrimitive.content)
        assertEquals("learning", card["queueType"]!!.jsonPrimitive.content)
        assertTrue(card["has_images"]!!.jsonPrimitive.boolean)
        assertTrue(card["has_audio"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun `get_due_cards with nothing due and an unknown deck`() {
        val empty = GetDueCardsTool(repo, ratings).callWith("deck_name" to "Spanish").data
        assertEquals(0, empty["cards"]!!.jsonArray.size)
        assertEquals(0, empty["returned"]!!.jsonPrimitive.int)
        assertEquals("No cards are due for review.", empty["message"]!!.jsonPrimitive.content)

        assertEquals(
            "Deck 'Nope' not found (hint: Check spelling or use list_decks to see available decks) (context: {'deck_name': 'Nope'})",
            GetDueCardsTool(repo, ratings).callWith("deck_name" to "Nope").text,
        )
        assertNull(empty["skipped"])
    }

    @Test
    fun `cards_stats pages a deck with subdecks by card id, without dueToday`() {
        val data = CardsStatsTool(repo).callWith("deck" to "Spanish", "limit" to 2).data

        assertEquals("Spanish", data["deck"]!!.jsonPrimitive.content)
        assertEquals(3, data["total"]!!.jsonPrimitive.int)
        assertEquals(2, data["count"]!!.jsonPrimitive.int)
        assertTrue(data["hasMore"]!!.jsonPrimitive.boolean)
        val cards = data["cards"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf(1001L, 1003L), cards.map { it["cid"]!!.jsonPrimitive.long })
        assertEquals(setOf("cid", "nid", "tags", "type", "queue", "ivl", "flag"), cards[0].keys)
        assertEquals(listOf("geo", "es"), cards[1]["tags"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals("deck:Spanish", col.queriesTo("cards").first().selection)
        assertEquals(listOf("_id"), col.queriesTo("cards").first().projection)
        assertEquals("cid:1001,1003", col.queriesTo("cards")[1].selection)

        val rest = CardsStatsTool(repo).callWith("deck" to "Spanish", "limit" to 2, "offset" to 2).data
        assertEquals(listOf(1005L), rest["cards"]!!.jsonArray.map { it.jsonObject["cid"]!!.jsonPrimitive.long })
        assertFalse(rest["hasMore"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun `cards_stats gives the card flag on AnkiDroid 2_25 only`() {
        col.cards.replaceAll { if (it.id == 1001L) it.copy(flags = 4) else it }
        val flagged = CardsStatsTool(repo).callWith("deck" to "Spanish", "limit" to 1).data
        assertEquals(4, flagged["cards"]!!.jsonArray[0].jsonObject["flag"]!!.jsonPrimitive.int)

        val v24 = col.repository(FakeAvailability(version = AnkiDroidVersion(22401300, "2.24.1")))
        val old = CardsStatsTool(v24).callWith("deck" to "Spanish", "limit" to 1).data
        assertNull(old["cards"]!!.jsonArray[0].jsonObject["flag"])
        assertFalse(col.queriesTo("cards").last().projection!!.contains("flags"))
    }

    @Test
    fun `cards_stats for an empty deck, an unknown deck and bad paging`() {
        col.decks += FakeCollection.FDeck(4, "Empty Deck")
        val empty = CardsStatsTool(repo).callWith("deck" to "Empty Deck").data
        assertEquals(0, empty["total"]!!.jsonPrimitive.int)
        assertEquals(0, empty["cards"]!!.jsonArray.size)
        assertFalse(empty["hasMore"]!!.jsonPrimitive.boolean)
        assertEquals("\"deck:Empty Deck\"", col.queriesTo("cards").single().selection)

        assertTrue(CardsStatsTool(repo).callWith("deck" to "Nope").text.startsWith("Deck 'Nope' not found"))
        assertTrue(CardsStatsTool(repo).callWith("deck" to "Spanish", "limit" to 201).text.startsWith("[limit_exceeded] limit exceeds maximum of 200"))
    }

    @Test
    fun `get_card_memory_state returns cached FSRS state and Anki's retrievability`() {
        val now = 1_700_000_000L
        col.cards += FCard(2001, 101, stability = 10.0, difficulty = 5.5, decay = 0.1542, lastReview = now - 10 * 86_400)
        val data = GetCardMemoryStateTool(repo) { now }.callWith("card_ids" to listOf(2001, 1005, 77)).data

        val cards = data["cards"]!!.jsonArray.map { it.jsonObject }
        assertEquals(2, data["total"]!!.jsonPrimitive.int)
        assertEquals(listOf(77L), data["not_found"]!!.jsonArray.map { it.jsonPrimitive.long })
        val fsrs = cards[0]
        assertEquals("review", fsrs["queue"]!!.jsonPrimitive.content)
        assertEquals("review", fsrs["type"]!!.jsonPrimitive.content)
        assertEquals(10.0, fsrs["stability"]!!.jsonPrimitive.double, 0.0)
        assertEquals(5.5, fsrs["difficulty"]!!.jsonPrimitive.double, 0.0)
        assertEquals(0.9, fsrs["retrievability"]!!.jsonPrimitive.double, 0.0)
        assertNull(fsrs["elapsed_days"])
        val sm2 = cards[1]
        assertEquals(JsonNull, sm2["stability"])
        assertEquals(JsonNull, sm2["difficulty"])
        assertNull(sm2["retrievability"])
        assertEquals("new", sm2["queue"]!!.jsonPrimitive.content)
    }

    @Test
    fun `retrievability follows the FSRS forgetting curve and the add-on's conditions`() {
        val now = 1_000_000L
        val card = CardData(1, type = 2, interval = 5, stability = 4.0, decay = 0.5, lastReviewSecs = now - 4 * 86_400)
        // FSRS-5: factor 19/81, R(t = S) = 0.9.
        assertEquals(0.9, GetCardMemoryStateTool.retrievability(card, now)!!, 0.0)
        assertEquals(1.0, GetCardMemoryStateTool.retrievability(card.copy(lastReviewSecs = now), now)!!, 0.0)
        assertEquals(0.9, GetCardMemoryStateTool.retrievability(card.copy(decay = null), now)!!, 0.0)
        assertNull(GetCardMemoryStateTool.retrievability(card.copy(type = 1), now))
        assertNull(GetCardMemoryStateTool.retrievability(card.copy(interval = 0), now))
        assertNull(GetCardMemoryStateTool.retrievability(card.copy(lastReviewSecs = null), now))
    }

    @Test
    fun `get_card_memory_state validates and has no recompute argument`() {
        assertEquals(
            "No card IDs provided (hint: Provide at least one card ID. Use find_notes then notes_info to find card IDs.)",
            GetCardMemoryStateTool(repo).callWith("card_ids" to emptyList<Long>()).text,
        )
        assertTrue(GetCardMemoryStateTool(repo).callWith("card_ids" to listOf(1), "recompute" to true).text.startsWith("Unknown argument(s)"))
    }

    @Test
    fun `cards_stats defaults to 200 cards and reads their details 100 at a time`() {
        for (i in 1L..250L) col.cards += FCard(5_000 + i, 101, did = 2)
        val data = CardsStatsTool(repo).callWith("deck" to "Spanish").data

        assertEquals(253, data["total"]!!.jsonPrimitive.int)
        assertEquals(200, data["count"]!!.jsonPrimitive.int)
        assertTrue(data["hasMore"]!!.jsonPrimitive.boolean)
        val detailCalls = col.queriesTo("cards").filter { it.selection!!.startsWith("cid:") }
        assertEquals(listOf(100, 100), detailCalls.map { it.selection!!.removePrefix("cid:").split(",").size })
    }

    @Test
    fun `an empty Default deck hidden from AnkiDroid's deck list is empty, not unknown`() {
        col.decks.removeAll { it.id == 1L }

        val stats = CardsStatsTool(repo).callWith("deck" to "Default").data
        assertEquals(0, stats["total"]!!.jsonPrimitive.int)
        assertEquals(0, stats["cards"]!!.jsonArray.size)

        val due = GetDueCardsTool(repo, ratings).callWith("deck_name" to "default").data
        assertEquals(0, due["cards"]!!.jsonArray.size)
        assertEquals(0, due["total"]!!.jsonPrimitive.int)
        assertEquals(listOf("1"), col.queriesTo("schedule").single().selectionArgs!!.drop(1))

        assertEquals(0, FindNotesTool(repo).callWith("query" to "deck:Default").data["total"]!!.jsonPrimitive.int)
        assertTrue(CardsStatsTool(repo).callWith("deck" to "Nope").text.startsWith("Deck 'Nope' not found"))
    }

    @Test
    fun `get_card_memory_state takes at most 100 cards`() {
        assertTrue(
            GetCardMemoryStateTool(repo).callWith("card_ids" to (1L..101L).toList()).text
                .startsWith("[limit_exceeded] Too many cards: 101 (maximum is 100)"),
        )
        assertTrue(col.provider.queries.isEmpty())
    }

    @Test
    fun `the version gate runs before argument-only answers`() {
        val old = col.repository(FakeAvailability(version = AnkiDroidVersion(22303300, "2.23.3")))
        assertTrue(PresentCardTool(old).callWith("card_id" to -1).text.contains("Update AnkiDroid to 2.24 or newer"))
        assertTrue(GetCardMemoryStateTool(old).callWith("card_ids" to listOf(-1)).text.contains("Update AnkiDroid to 2.24 or newer"))
    }

    @Test
    fun `card tools need AnkiDroid 2_24`() {
        val old = col.repository(FakeAvailability(version = AnkiDroidVersion(22311300, "2.23.11")))
        listOf(
            PresentCardTool(old).callWith("card_id" to 1003),
            GetDueCardsTool(old, ratings).callWith("deck_name" to "Spanish"),
            CardsStatsTool(old).callWith("deck" to "Spanish"),
            GetCardMemoryStateTool(old).callWith("card_ids" to listOf(1003)),
        ).forEach { assertTrue(it.text, it.text.contains("Update AnkiDroid to 2.24 or newer")) }
    }
}
