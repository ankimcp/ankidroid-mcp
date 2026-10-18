package ai.ankimcp.ankidroid.tools

import ai.ankimcp.ankidroid.anki.AnkiDroidRepository
import ai.ankimcp.ankidroid.anki.AnkiResult
import ai.ankimcp.ankidroid.anki.CardData
import ai.ankimcp.ankidroid.anki.CardField
import ai.ankimcp.ankidroid.anki.Deck
import ai.ankimcp.ankidroid.anki.DeckCounts
import ai.ankimcp.ankidroid.anki.NoteField
import ai.ankimcp.ankidroid.anki.RequiredVersion
import ai.ankimcp.ankidroid.mcp.ToolResult
import ai.ankimcp.ankidroid.mcp.readOnlyAnnotations
import ai.ankimcp.ankidroid.mcp.writeAnnotations
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlin.math.pow

/** Home deck name (`current_deck_id`) and, for a card in a filtered deck, that deck's name, like the add-on. */
private fun deckNames(card: CardData, decks: List<Deck>): Pair<String, String?> {
    fun nameOf(id: Long?) = decks.firstOrNull { it.id == id }?.name ?: "Unknown"
    val odid = card.originalDeckId ?: 0L
    return if (odid != 0L) nameOf(odid) to nameOf(card.deckId) else nameOf(card.deckId) to null
}

/**
 * The deck called [name], case-insensitively. AnkiDroid's deck list is rslib's deck tree, which
 * leaves out deck 1 ("Default") while it is empty and has no subdecks; the add-on still finds it
 * by name, so a missing "Default" is that empty deck rather than an unknown one (D57). A renamed
 * deck 1 that is hidden this way cannot be recognised.
 */
internal fun List<Deck>.deckNamed(name: String): Deck? =
    byName(name) { it.name }
        ?: if (name.equals(DEFAULT_DECK_NAME, ignoreCase = true) && none { it.id == DEFAULT_DECK_ID }) {
            Deck(DEFAULT_DECK_ID, DEFAULT_DECK_NAME, isFiltered = false, counts = DeckCounts(0, 0, 0))
        } else {
            null
        }

private const val DEFAULT_DECK_ID = 1L
private const val DEFAULT_DECK_NAME = "Default"

/** The add-on's deck-not-found error for tools that take a deck name. */
private fun deckNotFound(name: String): ToolResult = addonError(
    "Deck '$name' not found",
    hint = "Check spelling or use list_decks to see available decks",
    context = mapOf("deck_name" to name),
)

/**
 * `present_card`. The question is AnkiDroid's rendered question with the note type CSS in front
 * and the cloze answer stripped, as the add-on's `render_question_with_style` does; the answer
 * is styled the same way (`card.answer()`).
 */
class PresentCardTool(repository: AnkiDroidRepository) : AnkiTool(repository) {
    override val name = "present_card"
    override val title = "Present Card"
    override val description =
        "Retrieve a card's content for review. Returns the question only unless show_answer=true, so the answer " +
            "can be withheld until the user has responded. " +
            "This call does not rate the card; rating is a separate rate_card call. " +
            "Returns a card object with card_id, question (rendered HTML, always returned), answer (rendered HTML, " +
            "only when show_answer=true), deck_name, filtered_deck_name, note_type, and scheduling fields (interval, " +
            "ease_factor, due, reviews, lapses). " +
            "deck_name is the card's home deck; filtered_deck_name is always present and is null unless the card is " +
            "currently being studied from a filtered deck."
    override val inputSchema: JsonObject = inputSchema(required = listOf("card_id")) {
        property("card_id", "integer", "Card to present")
        property("show_answer", "boolean", "Include the rendered answer", default = JsonPrimitive(false))
    }
    override val annotations: JsonObject = readOnlyAnnotations(title)

    override suspend fun run(args: ToolArgs): ToolResult {
        val cardId = args.long("card_id")
        val showAnswer = args.bool("show_answer", false)
        val notFound = addonError(
            "Card not found with ID $cardId",
            hint = "Verify the card ID is correct using get_due_cards or cards_stats (find_notes returns note IDs, not card IDs)",
        )
        if (cardId <= 0) return notFound
        val fields = buildSet {
            addAll(listOf(CardField.NOTE_ID, CardField.DECK_ID, CardField.ORIGINAL_DECK_ID, CardField.QUESTION))
            addAll(listOf(CardField.DUE, CardField.INTERVAL, CardField.FACTOR, CardField.REPS, CardField.LAPSES))
            if (showAnswer) add(CardField.ANSWER)
        }
        val card = repository.searchCards("cid:$cardId", fields).valueOr { return it }.firstOrNull() ?: return notFound
        val note = card.noteId?.let { nid ->
            repository.searchNotes("nid:$nid", setOf(NoteField.MODEL_ID)).valueOr { return it }.firstOrNull()
        }
        val type = note?.modelId?.let { mid -> repository.listNoteTypes().valueOr { return it }.firstOrNull { it.id == mid } }
        val decks = repository.listDecks().valueOr { return it }
        val (deckName, filteredDeckName) = deckNames(card, decks)
        val css = type?.css ?: ""
        return ToolResult.structured(
            buildJsonObject {
                putJsonObject("card") {
                    put("card_id", card.id)
                    put("deck_name", deckName)
                    put("filtered_deck_name", filteredDeckName?.let(::JsonPrimitive) ?: JsonNull)
                    put("question", stripClozeAnswer(withStyle(css, card.question ?: "")))
                    put("note_type", type?.name ?: "Unknown")
                    put("due", card.due)
                    put("interval", card.interval)
                    put("ease_factor", card.factor)
                    put("reviews", card.reps)
                    put("lapses", card.lapses)
                    if (showAnswer) put("answer", withStyle(css, card.answer ?: ""))
                }
            },
        )
    }
}

/**
 * `get_due_cards`. skip_images/skip_audio bury at most [GetDueCardsTool.MAX_BURIES] cards per
 * call, each confirmed by a re-read, and the reply lists the buried card ids. The next card comes from AnkiDroid's `schedule` URI, which unlike the add-on leaves the
 * selected deck as it was. `counts` are the deck's due counts from AnkiDroid's deck list
 * (subdecks included), which is where the scheduler's counts come from too. Not read-only: the
 * `schedule` query selects the deck in AnkiDroid and restores the previous one (D57).
 */
class GetDueCardsTool(repository: AnkiDroidRepository, private val ratings: RatingGuard) : AnkiTool(repository) {
    override val name = "get_due_cards"
    override val title = "Next Due Card"
    override val description =
        "Retrieve the next single card due for review from a specified deck in true scheduler order. Reads the local " +
            "collection as-is and does not sync with AnkiWeb, so reviews made on other devices since the last sync are " +
            "not reflected. Returns one card per call to ensure correct scheduler interleaving. The deck_name parameter " +
            "is required - you must specify which deck to study. For voice-mode review, use skip_images=True and/or " +
            "skip_audio=True to filter out cards with media. Cards with media are buried (removed from the queue until " +
            "the next day); AnkiDroid's API cannot unbury them, the user can in AnkiDroid. At most $MAX_BURIES cards are " +
            "buried per call; their ids are returned in buried_card_ids. " +
            "Response includes has_images and has_audio flags for " +
            "each card. deckName is the card's home deck; filteredDeckName is always present and is null unless the " +
            "card is currently being studied from a filtered deck. " +
            "The answer is omitted by default (no 'back' key) so a review session does not receive it before the user " +
            "responds. Pass include_answer=True to also receive 'back' - for content analysis or editing, not for " +
            "reviewing. front (and back, when requested) are rendered card HTML like present_card's question/answer, " +
            "not raw note fields; front omits the note type CSS."
    override val inputSchema: JsonObject = inputSchema(required = listOf("deck_name")) {
        property("deck_name", "string", "Deck to pull the next due card from")
        property("skip_images", "boolean", "Bury and skip cards containing images", default = JsonPrimitive(false))
        property("skip_audio", "boolean", "Bury and skip cards containing audio", default = JsonPrimitive(false))
        property(
            "include_answer",
            "boolean",
            "Also return the rendered answer ('back'), not just the question",
            default = JsonPrimitive(false),
        )
    }

    /** The add-on's: a write (the skip flags bury cards; the schedule query selects the deck in AnkiDroid). */
    override val annotations: JsonObject = writeAnnotations(title)

    override suspend fun run(args: ToolArgs): ToolResult {
        val deckName = args.string("deck_name")
        val skipImages = args.bool("skip_images", false)
        val skipAudio = args.bool("skip_audio", false)
        val includeAnswer = args.bool("include_answer", false)
        val decks = repository.listDecks().valueOr { return it }
        var deck = decks.deckNamed(deckName) ?: return deckNotFound(deckName)
        var skippedImages = 0
        var skippedAudio = 0
        val buried = mutableListOf<Long>()
        var found: JsonObject? = null
        val fields = buildSet {
            addAll(listOf(CardField.ORD, CardField.DECK_ID, CardField.ORIGINAL_DECK_ID, CardField.QUESTION))
            addAll(listOf(CardField.QUEUE, CardField.DUE, CardField.INTERVAL, CardField.FACTOR))
            if (includeAnswer) add(CardField.ANSWER)
        }
        // Once a card is buried, every failure in the loop reports the cards buried so far.
        fun ranOut(failure: ToolResult): Nothing = throw ToolFailure(
            if (buried.isEmpty()) {
                failure
            } else {
                ToolResult.error(
                    "Stopped while skipping cards with media: buried ${buried.size} card(s) so far " +
                        "(images: $skippedImages, audio: $skippedAudio; card ids: ${buried.joinToString(", ")}). " +
                        failure.content[0].jsonObject["text"]!!.jsonPrimitive.content,
                )
            },
        )
        fun <T> AnkiResult<T>.orRanOut(): T = valueOr { ranOut(it) }

        while (true) {
            val scheduled = repository.nextScheduledCard(deck.id).orRanOut().firstOrNull() ?: break
            val card = repository.searchCards("nid:${scheduled.noteId}", fields).orRanOut()
                .firstOrNull { it.ord == scheduled.ord }
                ?: ranOut(
                    addonError(
                        "Failed to retrieve queued cards: AnkiDroid's scheduler returned note ${scheduled.noteId} card " +
                            "${scheduled.ord + 1}, which a card search does not find",
                        hint = "Ensure a profile is open and the deck has due cards",
                    ),
                )
            val note = repository.searchNotes("nid:${scheduled.noteId}", setOf(NoteField.MODEL_ID, NoteField.FIELDS))
                .orRanOut().firstOrNull()
            val noteFields = note?.fields.orEmpty()
            val image = skipImages && hasImages(noteFields)
            val audio = skipAudio && hasAudio(noteFields)
            if (image || audio) {
                if (buried.size >= MAX_BURIES) break
                repository.buryCard(scheduled.noteId, scheduled.ord).orRanOut()
                // AnkiDroid reports success even when burying failed; without this check the loop would see the card again.
                val queue = when (val read = repository.cardsByIds(listOf(card.id), setOf(CardField.QUEUE))) {
                    is AnkiResult.Success -> read.value.firstOrNull()?.queue
                    is AnkiResult.Failure -> ranOut(
                        ToolResult.error(
                            "Burying card ${card.id} was sent to AnkiDroid and accepted, but could not be confirmed: " +
                                "${read.message} Check before repeating it.",
                        ),
                    )
                }
                if (queue != QUEUE_MANUALLY_BURIED) ranOut(ToolResult.error("AnkiDroid did not bury card ${card.id}."))
                buried += card.id
                if (image) skippedImages++
                if (audio) skippedAudio++
                continue
            }
            val type = note?.modelId?.let { mid -> repository.listNoteTypes().orRanOut().firstOrNull { it.id == mid } }
            val (home, filtered) = deckNames(card, decks)
            found = buildJsonObject {
                put("cardId", card.id)
                put("front", stripClozeAnswer(card.question ?: ""))
                put("deckName", home)
                put("filteredDeckName", filtered?.let(::JsonPrimitive) ?: JsonNull)
                put("modelName", type?.name ?: "Unknown")
                put("queueType", queueType(card.queue))
                put("due", card.due)
                put("interval", card.interval)
                put("factor", card.factor)
                put("has_images", hasImages(noteFields))
                put("has_audio", hasAudio(noteFields))
                if (includeAnswer) put("back", withStyle(type?.css ?: "", card.answer ?: ""))
            }
            break
        }

        val skippedTotal = buried.size
        if (skippedTotal > 0) {
            // The counts changed with the buried cards.
            deck = repository.listDecks().orRanOut().deckNamed(deckName) ?: deck
        }
        val capped = found == null && skippedTotal >= MAX_BURIES
        val counts = deck.counts
        val total = counts?.let { it.new + it.learn + it.review }
        val filtering = skipImages || skipAudio
        found?.let { ratings.shown(it.getValue("cardId").jsonPrimitive.content.toLong()) }
        return ToolResult.structured(
            buildJsonObject {
                putJsonArray("cards") { found?.let { add(it) } }
                if (counts != null) {
                    putJsonObject("counts") {
                        put("new", counts.new)
                        put("learning", counts.learn)
                        put("review", counts.review)
                    }
                    put("total", total)
                }
                put("returned", if (found != null) 1 else 0)
                if (filtering) {
                    putJsonObject("skipped") {
                        put("images", skippedImages)
                        put("audio", skippedAudio)
                    }
                    putJsonArray("buried_card_ids") { buried.forEach { add(it) } }
                }
                val totalText = total?.let { "total $it due cards" }
                put(
                    "message",
                    when {
                        capped ->
                            "Buried $MAX_BURIES cards with media (the most per call) and found no card without media yet. " +
                                "Call get_due_cards again to continue."
                        filtering && found == null && skippedTotal > 0 ->
                            "All cards contain media. Unbury them in AnkiDroid or try without filters."
                        found == null -> "No cards are due for review."
                        filtering -> "Next card in scheduler order (" +
                            listOfNotNull(totalText, "buried $skippedTotal cards with media").joinToString(", ") + ")"
                        totalText != null -> "Next card in scheduler order ($totalText)"
                        else -> "Next card in scheduler order"
                    },
                )
            },
        )
    }

    companion object {
        /**
         * Cards buried per call while skipping media, instead of the add-on's 100 checked: each
         * bury is a write plus a confirming read, and the call has a 12 s budget (D57).
         */
        const val MAX_BURIES = 5

        /** The add-on names the scheduler queue: new, learning (intraday, day learning, preview) or review. */
        internal fun queueType(queue: Int?): String = when (queue) {
            0 -> "new"
            1, 3, 4 -> "learning"
            2 -> "review"
            else -> "unknown"
        }

        internal fun hasImages(fields: List<String>): Boolean =
            fields.any { "<img " in it.lowercase() || "<img>" in it.lowercase() }

        internal fun hasAudio(fields: List<String>): Boolean = fields.any { "[sound:" in it.lowercase() }
    }
}

/** `cards_stats` without `dueToday`: AnkiDroid does not expose the scheduler's day number or day cutoff (D57). */
class CardsStatsTool(repository: AnkiDroidRepository) : AnkiTool(repository) {
    override val name = "cards_stats"
    override val title = "Card Statistics"
    override val description =
        "Bulk per-card scheduling stats for a deck (subdecks included), FSRS-independent. " +
            "For every matching card returns a compact record: cid, nid, the note's tags, and " +
            "raw Anki ints for type (0 new, 1 learning, 2 review, 3 relearning) and queue " +
            "(-3/-2 buried, -1 suspended, 0 new, 1 lrn, 2 rev, 3 day-lrn), plus interval (days), and on " +
            "AnkiDroid 2.25+ the card flag (0 none, 1-7 red, orange, green, blue, pink, turquoise, purple). " +
            "No due-today flag on AnkiDroid (it does not expose the scheduler's day). " +
            "No note fields, no HTML, no human-readable names -- " +
            "just the scheduling metrics, for bulk analytics. Paginated with limit (default " +
            "$MAX_LIMIT, max $MAX_LIMIT) and offset; cards are ordered by card id for stable paging. " +
            "Prefer this over find_notes + notes_info + get_card_memory_state when you only need " +
            "scheduling metrics: it is one compact read and does not require FSRS."
    override val inputSchema: JsonObject = inputSchema(required = listOf("deck")) {
        property("deck", "string", "Deck name, including subdecks")
        property("limit", "integer", "Max cards to return, default $MAX_LIMIT, max $MAX_LIMIT", default = JsonPrimitive(MAX_LIMIT))
        property("offset", "integer", "Cards to skip for pagination", default = JsonPrimitive(0))
    }
    override val annotations: JsonObject = readOnlyAnnotations(title)

    override suspend fun run(args: ToolArgs): ToolResult {
        val deckName = args.string("deck")
        val limit = args.int("limit", MAX_LIMIT)
        val offset = args.int("offset", 0)
        pagingError(limit, offset, MAX_LIMIT)?.let { return it }

        val decks = repository.listDecks().valueOr { return it }
        if (decks.deckNamed(deckName) == null) return deckNotFound(deckName)
        val allIds = repository.searchCardIds(deckSearch(deckName)).valueOr { return it }
        val total = allIds.size
        val page = allIds.drop(offset).take(limit)
        // AnkiDroid 2.25 reads card flags; older versions leave the key out.
        val withFlags = repository.checkVersion(RequiredVersion.CARD_FLAGS) == null
        val cards = if (page.isEmpty()) {
            emptyList()
        } else {
            val cardFields = buildSet {
                addAll(listOf(CardField.NOTE_ID, CardField.TYPE, CardField.QUEUE, CardField.INTERVAL))
                if (withFlags) add(CardField.FLAGS)
            }
            val byId = repository.cardsByIds(page, cardFields).valueOr { return it }.associateBy { it.id }
            val noteIds = byId.values.mapNotNull { it.noteId }.distinct()
            val tags = if (noteIds.isEmpty()) {
                emptyMap()
            } else {
                repository.searchNotes(idSearch("nid", noteIds), setOf(NoteField.TAGS)).valueOr { return it }
                    .associate { it.id to it.tags.orEmpty() }
            }
            // A card that vanished between the two searches is skipped, as the add-on does.
            page.mapNotNull { id -> byId[id]?.let { it to tags[it.noteId].orEmpty() } }
        }
        return ToolResult.structured(
            buildJsonObject {
                put("deck", deckName)
                put("total", total)
                put("offset", offset)
                put("count", cards.size)
                put("hasMore", total != 0 && offset.toLong() + limit < total)
                putJsonArray("cards") {
                    cards.forEach { (card, noteTags) ->
                        addJsonObject {
                            put("cid", card.id)
                            put("nid", card.noteId)
                            putJsonArray("tags") { noteTags.forEach { add(it) } }
                            put("type", card.type)
                            put("queue", card.queue)
                            put("ivl", card.interval)
                            if (withFlags) card.flags?.let { put("flag", it) }
                        }
                    }
                }
            },
        )
    }

    companion object {
        /** Below the add-on's 1000: AnkiDroid renders every card whose details are read (D57). */
        const val MAX_LIMIT = 200
    }
}

/**
 * `get_card_memory_state` with the cached FSRS state only: AnkiDroid cannot recompute it or
 * report whether FSRS is enabled, so there is no `recompute` argument and no FSRS-disabled
 * error (a card without memory state has null stability/difficulty). `retrievability` is
 * computed the way Anki's card info does (rslib `card_stats`: seconds since the last review,
 * the card's decay, `fsrs::current_retrievability`), not with the add-on's day-based FSRS-4
 * curve; `elapsed_days` needs the scheduler's day number and is not returned (D57).
 */
class GetCardMemoryStateTool(
    repository: AnkiDroidRepository,
    private val nowSeconds: () -> Long = { System.currentTimeMillis() / 1000 },
) : AnkiTool(repository) {
    override val name = "get_card_memory_state"
    override val title = "Card Memory State"
    override val description =
        "Get FSRS memory state (stability, difficulty, retrievability) for one or more cards. " +
            "Returns per-card memory state along with scheduling info. Cards without an FSRS memory state " +
            "(FSRS disabled, or never reviewed with FSRS) have null stability and difficulty. " +
            "On AnkiDroid the stored state is returned; it cannot be recalculated from the review log."
    override val inputSchema: JsonObject = inputSchema(required = listOf("card_ids")) {
        property("card_ids", "array", "Card IDs to look up (max $MAX_CARDS)", items = "integer")
    }
    override val annotations: JsonObject = readOnlyAnnotations(title)

    override suspend fun run(args: ToolArgs): ToolResult {
        val ids = args.longList("card_ids")
        if (ids.isEmpty()) {
            return addonError(
                "No card IDs provided",
                hint = "Provide at least one card ID. Use find_notes then notes_info to find card IDs.",
            )
        }
        if (ids.size > MAX_CARDS) {
            return addonError(
                "Too many cards: ${ids.size} (maximum is $MAX_CARDS)",
                hint = "Split your request into batches of $MAX_CARDS or fewer.",
                code = "limit_exceeded",
            )
        }
        val byId = repository.cardsByIds(
            ids,
            setOf(
                CardField.INTERVAL, CardField.DUE, CardField.QUEUE, CardField.TYPE, CardField.REPS, CardField.LAPSES,
                CardField.STABILITY, CardField.DIFFICULTY, CardField.DECAY, CardField.LAST_REVIEW_SECS,
            ),
        ).valueOr { return it }.associateBy { it.id }
        val now = nowSeconds()
        val notFound = ids.filter { it !in byId }
        val cards = buildJsonArray {
            ids.forEach { id ->
                val card = byId[id] ?: return@forEach
                addJsonObject {
                    put("card_id", card.id)
                    put("interval", card.interval)
                    put("due", card.due)
                    put("queue", QUEUE_NAMES[card.queue] ?: "unknown(${card.queue})")
                    put("type", TYPE_NAMES[card.type] ?: "unknown(${card.type})")
                    put("reps", card.reps)
                    put("lapses", card.lapses)
                    put("stability", card.stability)
                    put("difficulty", card.difficulty)
                    retrievability(card, now)?.let { put("retrievability", it) }
                }
            }
        }
        return ToolResult.structured(
            buildJsonObject {
                put("cards", cards)
                put("total", cards.size)
                if (notFound.isNotEmpty()) putJsonArray("not_found") { notFound.forEach { add(it) } }
            },
        )
    }

    companion object {
        private val QUEUE_NAMES = mapOf(
            -1 to "suspended",
            -2 to "sibling_buried",
            -3 to "manually_buried",
            0 to "new",
            1 to "learning",
            2 to "review",
            3 to "day_learning",
            4 to "preview",
        )
        private val TYPE_NAMES = mapOf(0 to "new", 1 to "learning", 2 to "review", 3 to "relearning")

        /** Like notes_info's batch limit; the add-on has none here (D57). */
        const val MAX_CARDS = 100

        /** `FSRS5_DEFAULT_DECAY`, which rslib uses for a card without a stored decay. */
        private const val DEFAULT_DECAY = 0.5

        /**
         * Same cases as the add-on (stability > 0, interval > 0, review type), plus a known last
         * review time. R = (1 + factor * days / S)^-decay with factor = 0.9^(-1/decay) - 1,
         * rounded to 4 places like the add-on.
         */
        internal fun retrievability(card: CardData, nowSeconds: Long): Double? {
            val stability = card.stability ?: return null
            val last = card.lastReviewSecs?.takeIf { it > 0 } ?: return null
            if (stability <= 0 || (card.interval ?: 0) <= 0 || card.type != 2) return null
            val decay = card.decay?.takeIf { it > 0 } ?: DEFAULT_DECAY
            val days = (nowSeconds - last).coerceAtLeast(0) / 86_400.0
            val factor = 0.9.pow(-1.0 / decay) - 1.0
            return pyRound((days / stability * factor + 1.0).pow(-decay), 4)
        }
    }
}
