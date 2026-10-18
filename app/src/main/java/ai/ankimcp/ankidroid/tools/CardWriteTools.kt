package ai.ankimcp.ankidroid.tools

import ai.ankimcp.ankidroid.anki.AnkiDroidRepository
import ai.ankimcp.ankidroid.anki.AnkiResult
import ai.ankimcp.ankidroid.anki.CardData
import ai.ankimcp.ankidroid.anki.CardField
import ai.ankimcp.ankidroid.anki.RequiredVersion
import ai.ankimcp.ankidroid.mcp.ToolResult
import ai.ankimcp.ankidroid.mcp.writeAnnotations
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Card ids that changed, and the ones that did not with why. */
internal class CardChanges(val changed: List<Long>, val failed: List<Pair<Long, String>>)

/**
 * The shared run of the card write tools: reads the cards (missing ids are ignored, as Anki
 * does), writes each card that [needsChange] one at a time, then re-reads them and keeps only
 * those [confirmed] says took effect, because AnkiDroid's `schedule` update reports success even
 * when the scheduler call inside it failed (D57).
 */
internal suspend fun AnkiDroidRepository.changeCards(
    cardIds: List<Long>,
    fields: Set<CardField>,
    needsChange: (CardData) -> Boolean,
    change: suspend (CardData) -> AnkiResult<Int>,
    confirmed: (CardData) -> Boolean,
    precheck: (CardData) -> String? = { null },
): CardChanges {
    val wanted = fields + CardField.NOTE_ID + CardField.ORD
    val cards = cardsByIds(cardIds, wanted).orFail()
    val todo = cards.filter(needsChange)
    val progress = WriteProgress(todo.size, "cards")
    val failed = mutableListOf<Pair<Long, String>>()
    val attempted = mutableListOf<Long>()
    todo.forEachIndexed { i, card ->
        precheck(card)?.let {
            failed += card.id to it
            return@forEachIndexed
        }
        when (val result = change(card)) {
            is AnkiResult.Success -> {
                attempted += card.id
                progress.done += card.id.toString()
            }
            is AnkiResult.Rejected -> failed += card.id to result.message
            is AnkiResult.Failure -> progress.stop(result, card.id.toString(), todo.drop(i + 1).map { it.id.toString() })
        }
    }
    if (attempted.isEmpty()) return CardChanges(emptyList(), failed)
    val after = cardsByIds(attempted, wanted).confirmedOr("The change to card(s) ${attempted.joinToString(", ")}").associateBy { it.id }
    val changed = attempted.filter { id -> after[id]?.let(confirmed) == true }
    attempted.filter { it !in changed }.forEach { failed += it to "AnkiDroid reported success, but re-reading the card shows no change" }
    return CardChanges(changed, failed)
}

/** Adds `failed: [{card_id, error}]` when some cards were not changed (phone-only key, D57). */
internal fun kotlinx.serialization.json.JsonObjectBuilder.putFailed(failed: List<Pair<Long, String>>, idKey: String = "card_id") {
    if (failed.isEmpty()) return
    putJsonArray("failed") {
        failed.forEach { (id, error) ->
            addJsonObject {
                put(idKey, id)
                put("error", error)
            }
        }
    }
}

private fun cardIdsOf(args: ToolArgs): List<Long> {
    val ids = args.longList("card_ids")
    if (ids.isEmpty()) fail("card_ids is required and cannot be empty", hint = "Provide at least one card ID")
    if (ids.size > MAX_WRITE_BATCH) tooMany(ids.size, "cards")
    return ids
}

private fun cardIdsSchema(verb: String) = inputSchema(required = listOf("card_ids")) {
    property("card_ids", "array", "Card IDs to $verb (max $MAX_WRITE_BATCH)", items = "integer")
}

/** `card_bury`: manual bury through AnkiDroid's `schedule` update, confirmed by the card's queue. */
class CardBuryTool(repository: AnkiDroidRepository) : AnkiTool(repository) {
    override val name = "card_bury"
    override val title = "Bury Cards"
    override val description =
        "Manually bury cards to hide them until the next day. Works with any card type. " +
            "At most $MAX_WRITE_BATCH cards per call; AnkiDroid cannot unbury them, the user can in AnkiDroid. " +
            "Returns buried_count."
    override val inputSchema: JsonObject = cardIdsSchema("bury")
    override val annotations: JsonObject = writeAnnotations(title, destructive = false, idempotent = true)

    override suspend fun run(args: ToolArgs): ToolResult {
        val ids = cardIdsOf(args)
        val result = repository.changeCards(
            ids,
            setOf(CardField.QUEUE),
            needsChange = { it.queue != QUEUE_MANUALLY_BURIED },
            change = { repository.buryCard(it.noteId!!, it.ord!!) },
            confirmed = { it.queue == QUEUE_MANUALLY_BURIED },
            precheck = { if (it.queue == QUEUE_SUSPENDED) "card is suspended; Anki does not bury suspended cards" else null },
        )
        return ToolResult.structured(
            buildJsonObject {
                put("buried_count", result.changed.size)
                putJsonArray("card_ids") { ids.forEach { add(it) } }
                put("message", "Buried ${result.changed.size} cards")
                putFailed(result.failed)
            },
        )
    }
}

/** `card_suspend`, confirmed by the card's queue. */
class CardSuspendTool(repository: AnkiDroidRepository) : AnkiTool(repository) {
    override val name = "card_suspend"
    override val title = "Suspend Cards"
    override val description =
        "Suspend cards, hiding them from review until they are unsuspended. Works with any card type. " +
            "At most $MAX_WRITE_BATCH cards per call; AnkiDroid cannot unsuspend them, the user can in AnkiDroid. " +
            "Returns suspended_count."
    override val inputSchema: JsonObject = cardIdsSchema("suspend")
    override val annotations: JsonObject = writeAnnotations(title, destructive = false, idempotent = true)

    override suspend fun run(args: ToolArgs): ToolResult {
        val ids = cardIdsOf(args)
        val result = repository.changeCards(
            ids,
            setOf(CardField.QUEUE),
            needsChange = { it.queue != QUEUE_SUSPENDED },
            change = { repository.suspendCard(it.noteId!!, it.ord!!) },
            confirmed = { it.queue == QUEUE_SUSPENDED },
        )
        return ToolResult.structured(
            buildJsonObject {
                put("suspended_count", result.changed.size)
                putJsonArray("card_ids") { ids.forEach { add(it) } }
                put("message", "Suspended ${result.changed.size} cards")
                putFailed(result.failed)
            },
        )
    }
}

/** `card_set_flag`: needs AnkiDroid 2.25 (the card `flags` column and its update). */
class CardSetFlagTool(repository: AnkiDroidRepository) : AnkiTool(repository) {
    override val name = "card_set_flag"
    override val title = "Set Card Flag"
    override val description =
        "Set or remove a colored flag on cards. " +
            "Flag values: 0=none/remove, 1=red, 2=orange, 3=green, 4=blue, 5-7=custom. " +
            "At most $MAX_WRITE_BATCH cards per call; needs AnkiDroid 2.25 or newer. Returns flagged_count."
    override val inputSchema: JsonObject = inputSchema(required = listOf("card_ids", "flag")) {
        property("card_ids", "array", "Card IDs to flag (max $MAX_WRITE_BATCH)", items = "integer")
        property("flag", "integer", "Flag value: 0=none/remove, 1=red, 2=orange, 3=green, 4=blue, 5-7=custom user flags")
    }
    override val annotations: JsonObject = writeAnnotations(title, destructive = false, idempotent = true)

    override suspend fun run(args: ToolArgs): ToolResult {
        val ids = cardIdsOf(args)
        val flag = args.long("flag").coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt()
        if (flag < 0 || flag > 7) {
            fail(
                "Invalid flag value: $flag",
                hint = "Flag must be 0 (none/remove), 1 (red), 2 (orange), 3 (green), 4 (blue), or 5-7 (custom)",
                context = mapOf("flag" to flag),
            )
        }
        repository.requireVersion(RequiredVersion.CARD_FLAGS)
        val result = repository.changeCards(
            ids,
            setOf(CardField.FLAGS),
            needsChange = { it.flags != flag },
            change = { repository.setCardFlag(it.noteId!!, it.ord!!, flag) },
            confirmed = { it.flags == flag },
        )
        val label = mapOf(0 to "none", 1 to "red", 2 to "orange", 3 to "green", 4 to "blue")[flag] ?: "custom ($flag)"
        val action = if (flag == 0) "Removed flag from" else "Set $label flag on"
        return ToolResult.structured(
            buildJsonObject {
                put("flagged_count", result.changed.size)
                putJsonArray("card_ids") { ids.forEach { add(it) } }
                put("flag", flag)
                put("message", "$action ${result.changed.size} card(s)")
                putFailed(result.failed)
            },
        )
    }
}

/**
 * `card_change_deck`. AnkiDroid sets a card's deck directly and refuses a filtered target; a
 * card that sits in a filtered deck is not moved (the add-on takes it out of the filtered deck
 * first, which the provider cannot do) and is reported in `failed` (D57).
 */
class CardChangeDeckTool(repository: AnkiDroidRepository) : AnkiTool(repository) {
    override val name = "card_change_deck"
    override val title = "Move Cards to Deck"
    override val description =
        "Move cards to a different deck, creating the deck if needed. Works with any card type. " +
            "Cards currently in a filtered deck are not moved on AnkiDroid (empty the filtered deck first); " +
            "they are listed in 'failed'. At most $MAX_WRITE_BATCH cards per call. " +
            "Returns moved count and deck_id."
    override val inputSchema: JsonObject = inputSchema(required = listOf("card_ids", "deck")) {
        property("card_ids", "array", "Card IDs to move (max $MAX_WRITE_BATCH)", items = "integer")
        property("deck", "string", "Target deck name (use '::' for nested, e.g., 'Spanish::Verbs')")
    }
    override val annotations: JsonObject = writeAnnotations(title, destructive = false, idempotent = true)

    override suspend fun run(args: ToolArgs): ToolResult {
        val ids = cardIdsOf(args)
        val deckName = args.string("deck")
        if (deckName.isBlank()) {
            fail("deck name cannot be empty", hint = "Provide a valid deck name (e.g., 'Spanish' or 'Spanish::Verbs')")
        }
        val decks = repository.listDecks().orFail()
        val existing = decks.deckNamed(deckName)
        if (existing?.isFiltered == true) {
            fail("Cards cannot be moved to a filtered deck", hint = "Choose a normal deck", context = mapOf("deck" to deckName))
        }
        val deckId = existing?.id ?: repository.createDeck(deckName).orFail()
        fun nameOf(id: Long?) = decks.firstOrNull { it.id == id }?.name ?: "a filtered deck"
        val result = repository.changeCards(
            ids,
            setOf(CardField.DECK_ID, CardField.ORIGINAL_DECK_ID),
            needsChange = { it.deckId != deckId },
            change = { repository.moveCard(it.noteId!!, it.ord!!, deckId) },
            confirmed = { it.deckId == deckId },
            precheck = { card ->
                if ((card.originalDeckId ?: 0L) != 0L) {
                    "card is in filtered deck ${nameOf(card.deckId)}; empty it in AnkiDroid first"
                } else {
                    null
                }
            },
        )
        return ToolResult.structured(
            buildJsonObject {
                put("moved", result.changed.size)
                put("deck_id", deckId)
                put("message", "Moved ${result.changed.size} cards to deck '$deckName'")
                putFailed(result.failed)
            },
        )
    }
}

/**
 * `rate_card`: answers the card through AnkiDroid's `schedule` update and re-reads it to confirm
 * the review was recorded (AnkiDroid reports success even when answering failed). Not
 * idempotent: a repeated call records a second review; the app's request dedupe (D52) only stops
 * a relay re-forward of the same request, not a new call, so a card rated again before
 * get_due_cards returned it again is refused ([RatingGuard]). The answer time sent
 * is always 0 ms (AnkiDroid's -1
 * would use a timer that was never started) (D57). The next review date of a review card needs
 * the collection's creation day, which AnkiDroid does not expose, so review cards get
 * `new_interval` only; intraday learning and preview cards get `next_review` from their due time;
 * day-learning cards get neither.
 */
class RateCardTool(
    repository: AnkiDroidRepository,
    private val ratings: RatingGuard,
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val nowSeconds: () -> Long = { System.currentTimeMillis() / 1000 },
) : AnkiTool(repository) {
    override val name = "rate_card"
    override val title = "Rate Card"
    override val description =
        "Submit a rating for a card to update Anki's spaced repetition scheduling. " +
            "Precondition: the answer must already have been revealed to the user via present_card(show_answer=true), " +
            "and the user must have confirmed or modified your suggested rating. " +
            "The rating is applied to the schedule immediately, so it is meant for a rating the user has confirmed after seeing the answer. " +
            "Calling it twice records two reviews: only an automatic re-send of the same request is protected, so never retry " +
            "a rating that may have gone through; check with present_card first. Rating a card again before get_due_cards has " +
            "returned it again is refused. Returns new_interval (days, for review cards), next_review (learning cards) and card_type."
    override val inputSchema: JsonObject = inputSchema(required = listOf("card_id", "rating")) {
        property("card_id", "integer", "Card to rate")
        property("rating", "integer", "Rating 1-4 (1=Again, 2=Hard, 3=Good, 4=Easy)")
    }
    override val annotations: JsonObject = writeAnnotations(title)

    override suspend fun run(args: ToolArgs): ToolResult {
        val cardId = args.long("card_id")
        val rating = args.long("rating")
        if (rating < 1 || rating > 4) {
            fail(
                "Invalid rating: $rating. Must be 1-4 (1=Again, 2=Hard, 3=Good, 4=Easy)",
                hint = "Rating must be an integer between 1 and 4",
            )
        }
        if (cardId <= 0) {
            fail(
                "card_id must be a positive integer, got: $cardId",
                hint = "Use get_due_cards or cards_stats to get valid card IDs (find_notes returns note IDs, not card IDs)",
            )
        }
        val fields = setOf(
            CardField.NOTE_ID, CardField.ORD, CardField.REPS, CardField.TYPE, CardField.QUEUE, CardField.INTERVAL,
            CardField.DUE, CardField.DECK_ID, CardField.LAST_REVIEW_SECS,
        )
        val before = repository.cardsByIds(listOf(cardId), fields).orFail().firstOrNull()
            ?: fail("Card not found: $cardId", hint = "Verify the card ID is correct using get_due_cards or other card operations")
        val now = nowSeconds()
        ratings.repeatedAfter(cardId, before.lastReviewSecs, now)?.let { ago ->
            fail(
                "Card $cardId was rated $ago seconds ago and has not been shown since; rating it again now would record " +
                    "a second review",
                hint = "If this rating was already sent, it went through: check the card with present_card. " +
                    "To rate the card again on purpose, get it again with get_due_cards first.",
                context = mapOf("card_id" to cardId),
            )
        }
        when (val answered = repository.answerCard(before.noteId!!, before.ord!!, rating.toInt(), TIME_TAKEN_MS)) {
            is AnkiResult.Success -> ratings.rated(cardId, now)
            is AnkiResult.Failure -> {
                // An answer that may have gone through counts as a rating.
                if (answered is AnkiResult.OutcomeUnknown) ratings.rated(cardId, now)
                throw ToolFailure(ToolResult.error(answered.message))
            }
        }
        val unconfirmed = "The rating of card $cardId was sent to AnkiDroid and accepted, but could not be confirmed"
        val after = when (val reread = repository.cardsByIds(listOf(cardId), fields)) {
            is AnkiResult.Success -> reread.value.firstOrNull()
            is AnkiResult.Failure -> throw ToolFailure(
                ToolResult.error("$unconfirmed: ${reread.message} Do not rate this card again; check it with present_card."),
            )
        } ?: throw ToolFailure(ToolResult.error("$unconfirmed: the card is gone. Do not rate it again."))
        // A preview in a filtered deck does not count as a review, so any scheduling change confirms it.
        val changed = after.reps != before.reps || after.queue != before.queue || after.due != before.due ||
            after.type != before.type || after.deckId != before.deckId
        if (!changed) {
            ratings.shown(cardId)
            throw ToolFailure(
                ToolResult.error(
                    "AnkiDroid did not record the review of card $cardId (re-reading it shows no change). " +
                        "Check the card in AnkiDroid before rating it again.",
                ),
            )
        }
        val ratingName = EASE_NAMES.getValue(rating.toInt())
        val type = after.type ?: -1
        return ToolResult.structured(
            buildJsonObject {
                put("card_id", cardId)
                put("rating", rating)
                put("card_type", listOf("new", "learning", "review", "relearning").getOrElse(type) { "unknown" })
                val due = after.due ?: 0
                when (after.queue) {
                    2 -> {
                        val days = after.interval ?: 0
                        put("new_interval", days)
                        put("message", "Card rated as '$ratingName'. Next review in $days days")
                    }
                    1, 4 -> {
                        // Intraday learning and preview: due is a time (epoch seconds).
                        val next = LEARNING_FORMAT.format(Instant.ofEpochSecond(due).atZone(zone))
                        put("next_review", next)
                        put("message", "Card rated as '$ratingName'. Next review: $next (in ${untilText(due - now)})")
                    }
                    else -> put("message", "Card rated as '$ratingName'")
                }
            },
        )
    }

    companion object {
        private val EASE_NAMES = mapOf(1 to "Again", 2 to "Hard", 3 to "Good", 4 to "Easy")
        private val LEARNING_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

        /** Always 0: AnkiDroid treats -1 as "time it with the card's timer", which was never started (D57). */
        private const val TIME_TAKEN_MS = 0L

        internal fun untilText(seconds: Long): String {
            val s = seconds.coerceAtLeast(0)
            return when {
                s < 60 -> "$s seconds"
                s < 3600 -> "${s / 60} minutes"
                s < 86_400 -> "${s / 3600} hours"
                else -> "${s / 86_400} days"
            }
        }
    }
}

internal const val QUEUE_SUSPENDED = -1
internal const val QUEUE_MANUALLY_BURIED = -3
