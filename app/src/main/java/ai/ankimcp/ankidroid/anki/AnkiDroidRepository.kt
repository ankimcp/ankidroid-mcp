package ai.ankimcp.ankidroid.anki

import ai.ankimcp.ankidroid.log.TunnelLog
import ai.ankimcp.ankidroid.tunnel.ElapsedClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

data class DeckCounts(val new: Long, val learn: Long, val review: Long)

data class Deck(
    val id: Long,
    val name: String,
    val isFiltered: Boolean,
    /** Null if AnkiDroid did not return DECK_COUNTS for this deck. */
    val counts: DeckCounts?,
)

/** A deck with its options group (preset) as AnkiDroid's legacy deck config JSON. */
data class DeckOptions(val id: Long, val name: String, val isFiltered: Boolean, val optionsJson: String?)

/** A note type as the `models` URI returns it. */
data class NoteType(
    val id: Long,
    val name: String,
    val fieldNames: List<String>,
    val css: String,
    val latexPre: String,
    val latexPost: String,
    /** The provider's `type` column: 1 for a cloze note type. */
    val isCloze: Boolean = false,
)

data class CardTemplate(val ord: Int, val name: String, val front: String, val back: String)

enum class NoteField(internal val column: String) {
    MODEL_ID(FlashCardsContract.Note.MID),
    FIELDS(FlashCardsContract.Note.FLDS),
    TAGS(FlashCardsContract.Note.TAGS),
    MOD(FlashCardsContract.Note.MOD),
}

/** A note; properties not requested with [NoteField] are null. */
data class NoteData(
    val id: Long,
    val modelId: Long? = null,
    /** Field values in note type order. */
    val fields: List<String>? = null,
    val tags: List<String>? = null,
    /** Last modification, epoch seconds. */
    val mod: Long? = null,
)

enum class CardField(internal val column: String) {
    NOTE_ID(FlashCardsContract.Card.NOTE_ID),
    ORD(FlashCardsContract.Card.CARD_ORD),
    DECK_ID(FlashCardsContract.Card.DECK_ID),
    ORIGINAL_DECK_ID(FlashCardsContract.Card.ORIGINAL_DECK_ID),

    /** Rendered question without the note type CSS (`question_simple`, same text as desktop's `question_text`). */
    QUESTION(FlashCardsContract.Card.QUESTION_SIMPLE),

    /** Rendered answer without the note type CSS (`answer_simple`). */
    ANSWER(FlashCardsContract.Card.ANSWER_SIMPLE),
    TYPE(FlashCardsContract.Card.TYPE),
    QUEUE(FlashCardsContract.Card.RAW_QUEUE),
    DUE(FlashCardsContract.Card.RAW_DUE),
    INTERVAL(FlashCardsContract.Card.INTERVAL),
    FACTOR(FlashCardsContract.Card.RAW_SM2_FACTOR),
    REPS(FlashCardsContract.Card.REPS),
    LAPSES(FlashCardsContract.Card.LAPSES),
    STABILITY(FlashCardsContract.Card.FSRS_STABILITY),
    DIFFICULTY(FlashCardsContract.Card.FSRS_DIFFICULTY),
    DECAY(FlashCardsContract.Card.FSRS_DECAY),
    LAST_REVIEW_SECS(FlashCardsContract.Card.LAST_REVIEW_TIME_SECONDS),

    /** AnkiDroid 2.25 or newer only. */
    FLAGS(FlashCardsContract.Card.FLAGS),
}

/** A note for [AnkiDroidRepository.addNotesToDeck]: [fields] in note type order. */
data class NewNote(val modelId: Long, val fields: List<String>, val tags: List<String>)

/** A card; properties not requested with [CardField] are null, and so are FSRS values AnkiDroid has none for. */
data class CardData(
    val id: Long,
    val noteId: Long? = null,
    val ord: Int? = null,
    val deckId: Long? = null,
    val originalDeckId: Long? = null,
    val question: String? = null,
    val answer: String? = null,
    val type: Int? = null,
    val queue: Int? = null,
    val due: Long? = null,
    val interval: Long? = null,
    val factor: Long? = null,
    val reps: Long? = null,
    val lapses: Long? = null,
    val stability: Double? = null,
    val difficulty: Double? = null,
    val decay: Double? = null,
    val lastReviewSecs: Long? = null,
    val flags: Int? = null,
)

/** The scheduler's next card: AnkiDroid's `schedule` URI identifies it by note and template ordinal. */
data class ScheduledCard(val noteId: Long, val ord: Int)

/**
 * One time budget shared by every repository call made in its coroutine context (D57): a tool
 * runs under one, so a chain of calls ends with [AnkiResult.TooSlow] before the tunnel's and
 * relay's own timeouts instead of each call getting the full [AnkiDroidRepository] timeout. The
 * clock starts at the first call that reads it, on the repository's clock.
 */
class CallBudget(val totalMs: Long) : AbstractCoroutineContextElement(CallBudget) {
    companion object Key : CoroutineContext.Key<CallBudget>

    private val startMs = AtomicLong(Long.MIN_VALUE)

    /** Milliseconds left at [nowMs]; the first call starts the budget. */
    fun remainingMs(nowMs: Long): Long {
        startMs.compareAndSet(Long.MIN_VALUE, nowMs)
        return totalMs - (nowMs - startMs.get())
    }
}

/** Outcome of a provider call; every failure carries a user-readable message. */
sealed interface AnkiResult<out T> {
    data class Success<T>(val value: T) : AnkiResult<T>

    sealed interface Failure : AnkiResult<Nothing> {
        val message: String
    }

    data object NotInstalled : Failure {
        override val message = "AnkiDroid is not installed on this phone. Install AnkiDroid from Google Play " +
            "and open it once, then try again."
    }

    data object PermissionDenied : Failure {
        override val message = "The AnkiMCP app on this phone does not have permission to access AnkiDroid. " +
            "Open it and grant the AnkiDroid database permission."
    }

    /** AnkiDroid is older than the tool set needs (D57). */
    data class Outdated(val installed: String, val required: String) : Failure {
        override val message = "This needs AnkiDroid $required or newer; the phone has AnkiDroid $installed. " +
            "Update AnkiDroid to $required or newer, then try again."
    }

    data class NotReady(val detail: String?) : Failure {
        override val message = "AnkiDroid is not ready: its collection storage is not configured. Open " +
            "AnkiDroid on the phone and finish its first-run setup, then try again." +
            (detail?.let { " ($it)" } ?: "")
    }

    /** AnkiDroid refused the request (an `IllegalArgumentException` from the provider). */
    data class Rejected(val detail: String) : Failure {
        override val message = "AnkiDroid rejected the request: $detail"
    }

    /** A call got no answer within the timeout. */
    data class NotResponding(val timeoutMs: Long) : Failure {
        override val message = "AnkiDroid is not responding (no answer within ${timeoutMs / 1000} seconds). " +
            "Restart AnkiDroid on the phone (close it from recent apps or force-stop it, then open it again) " +
            "and try again."
    }

    /**
     * A write reached AnkiDroid but did not come back with a result, so it may or may not have
     * been applied (D57). [operation] reads as the object of "whether ... happened".
     */
    data class OutcomeUnknown(val operation: String, val reason: String) : Failure {
        override val message = "$reason, so it is unknown whether $operation happened. Check AnkiDroid on the " +
            "phone before repeating it."
    }

    /** A write that was not sent because the tool's budget was nearly used up; nothing changed (D57). */
    data class NotSent(val operation: String) : Failure {
        override val message = "Did not send $operation to AnkiDroid: this request had less than " +
            "${AnkiDroidRepository.MIN_WRITE_BUDGET_MS / 1000} seconds left. Nothing was changed; ask again."
    }

    /** The calls of one tool together ran past its [CallBudget] (D57). */
    data class TooSlow(val budgetMs: Long) : Failure {
        override val message = "AnkiDroid is taking too long: this request did not finish within ${budgetMs / 1000} " +
            "seconds. Try a smaller request (for example a lower limit), or open AnkiDroid on the phone and ask again."
    }

    /** Every provider thread is busy and the wait queue is full. */
    data object Busy : Failure {
        override val message = "AnkiDroid is busy with other requests from AnkiMCP. Try again in a moment."
    }

    /**
     * Every attempt came back as a null cursor within [AnkiDroidRepository.FAST_NULL_MS], or
     * the last one hung: the phone refused the provider lookup or is holding the binder call
     * because it froze AnkiDroid (D51). [makerNames] is the phone's `FreezingOem.displayName`.
     */
    data class Frozen(val makerNames: String) : Failure {
        override val message = "AnkiDroid is running, but the phone has paused it in the background ($makerNames " +
            "phones do this to apps you aren't using), so it can't answer right now. Open " +
            "AnkiDroid on the phone, then ask again. No setting reliably stops this on these phones."
    }

    data class Unavailable(override val message: String) : Failure
}

/**
 * Reads from and writes to AnkiDroid's ContentProvider. Each provider call runs on
 * [providerExecutor] (a small bounded pool with a short queue, see [newProviderExecutor]). A
 * full queue fails fast with [AnkiResult.Busy].
 *
 * Every method needs AnkiDroid [RequiredVersion.TOOL_SET] or newer and returns
 * [AnkiResult.Outdated] below it (D57), except [listDecks] when called without a version.
 *
 * Reads: a null cursor usually means the OS had frozen AnkiDroid's process, so the query is
 * retried after each of [nullRetryDelaysMs] while the next retry still starts within
 * [nullRetryBudgetMs] of the first attempt (D44, D51). The retry is short because the freezer
 * also freezes our own process a few seconds after a thaw. One summary line per call records
 * when each attempt started, how long its query took and by which path, and how the call
 * ended. On a freezing-OEM phone ([freezingOemName] set), nulls that all came back fast are the
 * freezer's refusal and end as [AnkiResult.Frozen]; elsewhere every null ends as
 * [AnkiResult.Unavailable]. A note search that matches nothing also returns a null cursor;
 * [searchNotes] tells the two apart (D57).
 *
 * On a freezing-OEM phone a read attempt through the held client that has not answered after
 * [attemptTimeoutMs] (a binder call into a frozen AnkiDroid can be held by the freezer) is
 * checked with a cheap probe ([awaitUnlessFrozen]): no answer to the probe within
 * [probeTimeoutMs] ends the call as a hang, otherwise the attempt keeps going (D57). An attempt through the plain
 * resolver has no such limit, since it may cold-start AnkiDroid with a big collection. The path
 * is checked right before each attempt is submitted, so a client acquired during an attempt
 * does not bound that attempt. All attempts share the overall [timeoutMs], counted from the
 * first submission, whose expiry reports [AnkiResult.NotResponding].
 *
 * Writes ([write]) run exactly once: no null retry, no per-attempt limit, no dead-client retry.
 * One that started and got no answer within [timeoutMs], or whose AnkiDroid died under it, is
 * [AnkiResult.OutcomeUnknown] (D57).
 *
 * The provider keeps one client of AnkiDroid's provider for the process lifetime (D51);
 * [releaseProviderClient] drops it.
 */
class AnkiDroidRepository(
    private val provider: AnkiProvider,
    private val availability: AnkiDroidAvailability,
    private val providerExecutor: Executor,
    private val log: TunnelLog,
    private val clock: ElapsedClock,
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    private val nullRetryDelaysMs: List<Long> = NULL_RETRY_DELAYS_MS,
    private val nullRetryBudgetMs: Long = NULL_RETRY_BUDGET_MS,
    private val attemptTimeoutMs: Long = ATTEMPT_TIMEOUT_MS,
    private val probeTimeoutMs: Long = PROBE_TIMEOUT_MS,
    /** Threads in [providerExecutor]; when calls abandoned as frozen hold all of them, nothing else can start. */
    private val poolSize: Int = MAX_PROVIDER_THREADS,
    /**
     * The maker names (`FreezingOem.displayName`) when the phone's skin freezes background apps,
     * null otherwise. Only then is a held-client attempt capped at [attemptTimeoutMs] and a
     * failure diagnosed as [AnkiResult.Frozen] (D51).
     */
    private val freezingOemName: String? = null,
) {
    /** A null-cursor attempt, [startMs] counted from the first attempt's start. */
    private class NullAttempt(val startMs: Long, val call: ProviderCall<*>) {
        /** The frozen signature (D51); a dead client's fallback counts by its own call's time. */
        val fast get() = call.tookMs < FAST_NULL_MS
    }

    private val scheduleTurn = Mutex()
    private val scheduleLock = ReentrantLock()

    private class ScheduleBusyException : Exception()

    /**
     * The install, permission and 2.24 checks every tool but `list_decks` runs before anything
     * else, so even a call that would not reach AnkiDroid reports an outdated AnkiDroid (D57).
     */
    fun checkToolSet(): AnkiResult.Failure? = precheck(RequiredVersion.TOOL_SET)

    /** The same checks against [required], e.g. [RequiredVersion.CARD_FLAGS]. */
    fun checkVersion(required: RequiredVersion): AnkiResult.Failure? = precheck(required)

    /** Drops the held provider client, e.g. on sign-out. Never waits for AnkiDroid. */
    fun releaseProviderClient(reason: String) {
        provider.releaseClient(reason)
    }

    /**
     * All decks with today's due counts. The `decks` URI works on every AnkiDroid version the app
     * supports, so `list_decks` passes a null [minVersion]; tools that go on to need 2.24 keep
     * the gate, so they fail the same way whatever the deck lookup finds.
     */
    suspend fun listDecks(minVersion: RequiredVersion? = RequiredVersion.TOOL_SET): AnkiResult<List<Deck>> {
        val noAnswer = "AnkiDroid did not answer the deck query. Open AnkiDroid on the phone once, then ask again."
        return read(noAnswer, minVersion) {
            provider.query("decks", DECK_PROJECTION).map { rows ->
                rows.mapNotNull { row ->
                    val id = row.long(FlashCardsContract.Deck.DECK_ID) ?: return@mapNotNull null
                    val name = row.string(FlashCardsContract.Deck.DECK_NAME) ?: ""
                    if (id == 0L || name.isEmpty()) return@mapNotNull null
                    Deck(
                        id = id,
                        name = name,
                        isFiltered = parseBoolean(row.string(FlashCardsContract.Deck.DECK_DYN)),
                        counts = row.string(FlashCardsContract.Deck.DECK_COUNTS)?.let(::parseCounts),
                    )
                }
            }
        }
    }

    /** All decks with their options group JSON (`configDictForDeckId`). */
    suspend fun listDeckOptions(): AnkiResult<List<DeckOptions>> =
        read("AnkiDroid did not answer the deck options query. Open AnkiDroid on the phone once, then ask again.") {
            provider.query("decks", DECK_OPTIONS_PROJECTION).map { rows ->
                rows.mapNotNull { row ->
                    val id = row.long(FlashCardsContract.Deck.DECK_ID) ?: return@mapNotNull null
                    val name = row.string(FlashCardsContract.Deck.DECK_NAME) ?: ""
                    if (id == 0L || name.isEmpty()) return@mapNotNull null
                    DeckOptions(
                        id = id,
                        name = name,
                        isFiltered = parseBoolean(row.string(FlashCardsContract.Deck.DECK_DYN)),
                        optionsJson = row.string(FlashCardsContract.Deck.OPTIONS),
                    )
                }
            }
        }

    suspend fun listNoteTypes(): AnkiResult<List<NoteType>> =
        read("AnkiDroid did not answer the note type query. Open AnkiDroid on the phone once, then ask again.") {
            provider.query("models", NOTE_TYPE_PROJECTION).map { rows ->
                rows.mapNotNull { row ->
                    NoteType(
                        id = row.long(FlashCardsContract.Model._ID) ?: return@mapNotNull null,
                        name = row.string(FlashCardsContract.Model.NAME) ?: "",
                        fieldNames = row.string(FlashCardsContract.Model.FIELD_NAMES)?.let(::splitFields) ?: emptyList(),
                        css = row.string(FlashCardsContract.Model.CSS) ?: "",
                        latexPre = row.string(FlashCardsContract.Model.LATEX_PRE) ?: "",
                        latexPost = row.string(FlashCardsContract.Model.LATEX_POST) ?: "",
                        isCloze = row.long(FlashCardsContract.Model.TYPE) == 1L,
                    )
                }
            }
        }

    /** Card templates of note type [noteTypeId], in template order. */
    suspend fun listTemplates(noteTypeId: Long): AnkiResult<List<CardTemplate>> =
        read("AnkiDroid did not answer the template query. Open AnkiDroid on the phone once, then ask again.") {
            provider.query("models/$noteTypeId/templates", TEMPLATE_PROJECTION).map { rows ->
                rows.mapNotNull { row ->
                    CardTemplate(
                        ord = row.long(FlashCardsContract.CardTemplate.ORD)?.toInt() ?: return@mapNotNull null,
                        name = row.string(FlashCardsContract.CardTemplate.NAME) ?: "",
                        front = row.string(FlashCardsContract.CardTemplate.QUESTION_FORMAT) ?: "",
                        back = row.string(FlashCardsContract.CardTemplate.ANSWER_FORMAT) ?: "",
                    )
                }.sortedBy { it.ord }
            }
        }

    /**
     * Notes matching the Anki search [query], ordered by id. AnkiDroid's `notes` search returns a
     * null cursor both when nothing matches and when AnkiDroid is frozen, and an invalid search
     * there throws an exception that does not cross the binder cleanly. So the same search runs
     * on `cards` first, asking only for ids: that cursor is never null while AnkiDroid answers,
     * an invalid search comes back as [AnkiResult.Rejected], and no matching card means no
     * matching note. Only a non-empty card match goes on to the `notes` search, where a null
     * cursor is then retried as frozen (D57). The two are separate reads, so each provider call
     * is its own attempt under the per-attempt cap.
     */
    suspend fun searchNotes(query: String, fields: Set<NoteField> = emptySet()): AnkiResult<List<NoteData>> {
        val noAnswer = "AnkiDroid did not answer the note search. Open AnkiDroid on the phone once, then ask again."
        val cards = read(noAnswer) { provider.query("cards", ID_ONLY, query) }
        when (cards) {
            is AnkiResult.Failure -> return cards
            is AnkiResult.Success -> if (cards.value.isEmpty()) return AnkiResult.Success(emptyList())
        }
        val projection = listOf(FlashCardsContract.Note._ID) + NoteField.entries.filter { it in fields }.map { it.column }
        return read(noAnswer) {
            provider.query("notes", projection, query).map { rows -> rows.mapNotNull(::noteOf).sortedBy { it.id } }
        }
    }

    /** Ids of the cards matching [query], ascending. Only asks AnkiDroid for ids, which it answers without rendering cards. */
    suspend fun searchCardIds(query: String): AnkiResult<List<Long>> =
        read("AnkiDroid did not answer the card search. Open AnkiDroid on the phone once, then ask again.") {
            provider.query("cards", ID_ONLY, query).map { rows ->
                rows.mapNotNull { it.long(FlashCardsContract.Card._ID) }.sorted()
            }
        }

    /**
     * Cards matching [query] with [fields], ascending by id. AnkiDroid 2.24 and 2.25 render every
     * card's question and answer for any projection beyond the id, so keep the match small.
     */
    suspend fun searchCards(query: String, fields: Set<CardField>): AnkiResult<List<CardData>> {
        val projection = listOf(FlashCardsContract.Card._ID) + CardField.entries.filter { it in fields }.map { it.column }
        return read("AnkiDroid did not answer the card search. Open AnkiDroid on the phone once, then ask again.") {
            provider.query("cards", projection, query).map { rows -> rows.mapNotNull(::cardOf).sortedBy { it.id } }
        }
    }

    /**
     * Cards [ids] with [fields], ascending by id; ids that do not exist are left out. Read in
     * `cid:` searches of at most [MAX_CARDS_PER_CALL] cards each, so no single call renders more.
     */
    suspend fun cardsByIds(ids: Collection<Long>, fields: Set<CardField>): AnkiResult<List<CardData>> {
        val cards = mutableListOf<CardData>()
        ids.filter { it > 0 }.distinct().chunked(MAX_CARDS_PER_CALL).forEach { chunk ->
            when (val result = searchCards("cid:${chunk.joinToString(",")}", fields)) {
                is AnkiResult.Success -> cards += result.value
                is AnkiResult.Failure -> return result
            }
        }
        return AnkiResult.Success(cards.sortedBy { it.id })
    }

    /**
     * The scheduler's next card in deck [deckId] (subdecks included): one entry, or none if
     * nothing is due. AnkiDroid selects the deck for the query and restores the previous
     * selection afterwards, which is not atomic: two of our schedule queries at once could leave
     * the wrong deck selected or read the other's queue. So they are serialised in two layers
     * (D57). Callers queue for [scheduleTurn] for up to [SCHEDULE_LOCK_WAIT_MS], outside the
     * read and so outside its freeze watch: a caller that does not get its turn reports
     * [AnkiResult.Busy], never Frozen. The provider thread then holds [scheduleLock] for the
     * whole binder call, which also covers a call whose caller already gave up; if such a call
     * is still running, the next one's `tryLock` fails at once and also reports Busy.
     */
    suspend fun nextScheduledCard(deckId: Long): AnkiResult<List<ScheduledCard>> {
        precheck(RequiredVersion.TOOL_SET)?.let { return it }
        if (withTimeoutOrNull(SCHEDULE_LOCK_WAIT_MS) { scheduleTurn.lock() } == null) return AnkiResult.Busy
        try {
            return readSchedule(deckId)
        } finally {
            scheduleTurn.unlock()
        }
    }

    private suspend fun readSchedule(deckId: Long): AnkiResult<List<ScheduledCard>> =
        read("AnkiDroid did not answer the schedule query. Open AnkiDroid on the phone once, then ask again.") {
            if (!scheduleLock.tryLock()) throw ScheduleBusyException()
            try {
                provider.query(
                    "schedule",
                    listOf(FlashCardsContract.ReviewInfo.NOTE_ID, FlashCardsContract.ReviewInfo.CARD_ORD),
                    "limit=?,deckID=?",
                    listOf("1", deckId.toString()),
                )
            } finally {
                scheduleLock.unlock()
            }.map { rows ->
                rows.mapNotNull { row ->
                    val noteId = row.long(FlashCardsContract.ReviewInfo.NOTE_ID) ?: return@mapNotNull null
                    val ord = row.long(FlashCardsContract.ReviewInfo.CARD_ORD) ?: return@mapNotNull null
                    ScheduledCard(noteId, ord.toInt())
                }.take(1)
            }
        }

    // --- Writes (D57). Each is one provider call through [write]; tools re-read to confirm. ---

    /** Creates deck [name] (AnkiDroid refuses an existing or invalid name); its id. */
    suspend fun createDeck(name: String): AnkiResult<Long> =
        write("creating the deck") {
            provider.insert("decks", mapOf(FlashCardsContract.Deck.DECK_NAME to name))
        }.idFromUri("creating the deck")

    /**
     * Adds [notes] with all their cards in deck [deckId] (`bulkInsert` on `notes?deckId=`, the
     * only provider call that honours the deck; a plain insert uses the note type's default
     * deck). AnkiDroid returns only how many it added, no ids.
     */
    suspend fun addNotesToDeck(deckId: Long, notes: List<NewNote>): AnkiResult<Int> =
        write("adding the notes") {
            provider.bulkInsert(
                "notes?${FlashCardsContract.Note.DECK_ID_QUERY_PARAM}=$deckId",
                notes.map { note ->
                    mapOf(
                        FlashCardsContract.Note.MID to note.modelId,
                        FlashCardsContract.Note.FLDS to note.fields.joinToString(FIELD_SEPARATOR),
                        FlashCardsContract.Note.TAGS to note.tags.joinToString(" "),
                    )
                },
            )
        }

    /** Replaces note [noteId]'s fields (all of them, in order) and/or its whole tag string. */
    suspend fun updateNote(noteId: Long, fields: List<String>? = null, tags: List<String>? = null): AnkiResult<Int> =
        write("updating note $noteId") {
            provider.update(
                "notes/$noteId",
                buildMap {
                    fields?.let { put(FlashCardsContract.Note.FLDS, it.joinToString(FIELD_SEPARATOR)) }
                    tags?.let { put(FlashCardsContract.Note.TAGS, it.joinToString(" ")) }
                },
            )
        }

    /** Deletes note [noteId] and its cards. AnkiDroid 2.24 always returns 1; 2.25 a real count. */
    suspend fun deleteNote(noteId: Long): AnkiResult<Int> =
        write("deleting note $noteId") { provider.delete("notes/$noteId") }

    suspend fun moveCard(noteId: Long, ord: Int, deckId: Long): AnkiResult<Int> =
        write("moving the card") {
            provider.update("notes/$noteId/cards/$ord", mapOf(FlashCardsContract.Card.DECK_ID to deckId))
        }

    suspend fun setCardFlag(noteId: Long, ord: Int, flag: Int): AnkiResult<Int> =
        write("flagging the card", RequiredVersion.CARD_FLAGS) {
            provider.update("notes/$noteId/cards/$ord", mapOf(FlashCardsContract.Card.FLAGS to flag))
        }

    /**
     * Manually buries the card. AnkiDroid's `schedule` update catches errors from bury, suspend
     * and answer and still reports 1, so callers confirm by re-reading the card.
     */
    suspend fun buryCard(noteId: Long, ord: Int): AnkiResult<Int> =
        write("burying the card") { schedule(noteId, ord, FlashCardsContract.ReviewInfo.BURY to 1) }

    suspend fun suspendCard(noteId: Long, ord: Int): AnkiResult<Int> =
        write("suspending the card") { schedule(noteId, ord, FlashCardsContract.ReviewInfo.SUSPEND to 1) }

    /** Answers the card with [ease] 1-4; [timeTakenMs] is recorded in the review log. */
    suspend fun answerCard(noteId: Long, ord: Int, ease: Int, timeTakenMs: Long): AnkiResult<Int> =
        write("recording the review") {
            schedule(
                noteId,
                ord,
                FlashCardsContract.ReviewInfo.EASE to ease,
                FlashCardsContract.ReviewInfo.TIME_TAKEN to timeTakenMs,
            )
        }

    private fun schedule(noteId: Long, ord: Int, vararg values: Pair<String, Any>): ProviderCall<Int> =
        provider.update(
            "schedule",
            mapOf(FlashCardsContract.ReviewInfo.NOTE_ID to noteId, FlashCardsContract.ReviewInfo.CARD_ORD to ord) + values,
        )

    /** A new note type with [fieldNames] and [templateCount] default templates ("Card N"); its id. */
    suspend fun createNoteType(name: String, fieldNames: List<String>, templateCount: Int, css: String?): AnkiResult<Long> =
        write("creating the note type") {
            provider.insert(
                "models",
                buildMap {
                    put(FlashCardsContract.Model.NAME, name)
                    put(FlashCardsContract.Model.FIELD_NAMES, fieldNames.joinToString(FIELD_SEPARATOR))
                    put(FlashCardsContract.Model.NUM_CARDS, templateCount)
                    css?.let { put(FlashCardsContract.Model.CSS, it) }
                },
            )
        }.idFromUri("creating the note type")

    /** Changes the given note type properties; [cloze] sets the kind (1 = cloze, 0 = standard). */
    suspend fun updateNoteType(
        noteTypeId: Long,
        css: String? = null,
        latexPre: String? = null,
        latexPost: String? = null,
        cloze: Boolean? = null,
    ): AnkiResult<Int> =
        write("updating the note type") {
            provider.update(
                "models/$noteTypeId",
                buildMap {
                    css?.let { put(FlashCardsContract.Model.CSS, it) }
                    latexPre?.let { put(FlashCardsContract.Model.LATEX_PRE, it) }
                    latexPost?.let { put(FlashCardsContract.Model.LATEX_POST, it) }
                    cloze?.let { put(FlashCardsContract.Model.TYPE, if (it) 1 else 0) }
                },
            )
        }

    suspend fun updateTemplate(noteTypeId: Long, ord: Int, name: String? = null, front: String? = null, back: String? = null): AnkiResult<Int> =
        write("updating the card template") {
            provider.update(
                "models/$noteTypeId/templates/$ord",
                buildMap {
                    name?.let { put(FlashCardsContract.CardTemplate.NAME, it) }
                    front?.let { put(FlashCardsContract.CardTemplate.QUESTION_FORMAT, it) }
                    back?.let { put(FlashCardsContract.CardTemplate.ANSWER_FORMAT, it) }
                },
            )
        }

    /** Appends field [name] to the note type (AnkiDroid cannot insert it elsewhere). */
    suspend fun addField(noteTypeId: Long, name: String): AnkiResult<String> =
        write("adding the field") {
            provider.insert("models/$noteTypeId/fields", mapOf(FlashCardsContract.Model.FIELD_NAME to name))
        }

    private fun AnkiResult<String>.idFromUri(operation: String): AnkiResult<Long> = when (this) {
        is AnkiResult.Failure -> this
        is AnkiResult.Success -> value.substringAfterLast('/').toLongOrNull()?.let { AnkiResult.Success(it) }
            ?: AnkiResult.OutcomeUnknown(operation, "AnkiDroid answered with an unexpected result ($value)")
    }

    /**
     * Runs one provider write [call] exactly once (D57). [operation] names the change for an
     * [AnkiResult.OutcomeUnknown] message ("adding the note"). A write still queued when the
     * timeout expires is never sent and reports [AnkiResult.NotResponding]; one that started
     * and is still unanswered, or whose AnkiDroid died under it (a dead-object error, or a null
     * from the resolver path, which swallows that error), reports [AnkiResult.OutcomeUnknown].
     * An `IllegalArgumentException` or `UnsupportedOperationException` from AnkiDroid is its
     * validation before writing and reports [AnkiResult.Rejected]; any other exception is
     * [AnkiResult.OutcomeUnknown]. A resolver write that found no provider to send to
     * ([ProviderUnreachableException]) changed nothing and reports Frozen or Unavailable.
     * Cancelling the caller while the write is still queued means it is never sent either.
     */
    internal suspend fun <T : Any> write(
        operation: String,
        minVersion: RequiredVersion = RequiredVersion.TOOL_SET,
        call: () -> ProviderCall<T>,
    ): AnkiResult<T> {
        precheck(minVersion)?.let { return it }
        val limit = writeTimeout() ?: return AnkiResult.NotSent(operation)
        val state = AtomicInteger(QUEUED)
        val result = CompletableDeferred<AnkiResult<T>>()
        try {
            providerExecutor.execute {
                if (state.compareAndSet(QUEUED, RUNNING)) result.complete(runWriteCall(operation, call))
            }
        } catch (_: RejectedExecutionException) {
            return AnkiResult.Busy
        }
        val startMs = clock.elapsedMs()
        val outcome = try {
            withTimeoutOrNull(limit.ms) { result.await() }
        } catch (e: CancellationException) {
            if (state.compareAndSet(QUEUED, ABANDONED)) log.debug("AnkiDroid write ($operation) cancelled while queued; not sent")
            throw e
        }
        if (outcome != null) return outcome
        val elapsedMs = clock.elapsedMs() - startMs
        if (state.compareAndSet(QUEUED, ABANDONED)) {
            log.warn("AnkiDroid write ($operation) still queued after $elapsedMs ms; not sent")
            return if (limit.byBudget) tooSlow() else AnkiResult.NotResponding(timeoutMs)
        }
        // It finished between the timeout and the check: report what really happened.
        if (result.isCompleted) return result.await()
        log.warn("AnkiDroid write ($operation) unanswered after $elapsedMs ms; outcome unknown")
        val waitedS = (elapsedMs + 999) / 1000
        val reason = if (limit.byBudget) {
            val budgetS = (currentCoroutineContext()[CallBudget]?.totalMs ?: timeoutMs) / 1000
            "AnkiDroid did not answer within $waitedS seconds, when this request's $budgetS-second limit was down to " +
                "the ${CONFIRM_RESERVE_MS / 1000} seconds kept for checking the result"
        } else {
            "AnkiDroid did not answer within $waitedS seconds"
        }
        return AnkiResult.OutcomeUnknown(operation, reason)
    }

    private fun <T : Any> runWriteCall(operation: String, call: () -> ProviderCall<T>): AnkiResult<T> = try {
        val done = call()
        when {
            done.value != null -> AnkiResult.Success(done.value)
            done.viaClient -> AnkiResult.Unavailable("AnkiDroid returned no result for $operation.")
            else -> AnkiResult.OutcomeUnknown(operation, "AnkiDroid returned no result")
        }
    } catch (e: ProviderDiedException) {
        AnkiResult.OutcomeUnknown(operation, "AnkiDroid stopped while handling it")
    } catch (e: ProviderUnreachableException) {
        freezingOemName?.let { AnkiResult.Frozen(it) }
            ?: AnkiResult.Unavailable("AnkiDroid could not be reached, so nothing was changed. Open AnkiDroid on the phone, then try again.")
    } catch (e: SecurityException) {
        AnkiResult.PermissionDenied
    } catch (e: IllegalStateException) {
        if (isStorageNotConfigured(e)) {
            AnkiResult.NotReady(e.message)
        } else {
            AnkiResult.OutcomeUnknown(operation, providerError(e))
        }
    } catch (e: IllegalArgumentException) {
        AnkiResult.Rejected(e.message ?: e.javaClass.simpleName)
    } catch (e: UnsupportedOperationException) {
        AnkiResult.Rejected(e.message ?: e.javaClass.simpleName)
    } catch (e: Exception) {
        AnkiResult.OutcomeUnknown(operation, providerError(e))
    }

    /** Install, permission and (unless [minVersion] is null) version checks shared by reads and writes. */
    private fun precheck(minVersion: RequiredVersion?): AnkiResult.Failure? {
        if (!availability.isInstalled()) return AnkiResult.NotInstalled
        if (!availability.hasPermission()) return AnkiResult.PermissionDenied
        if (minVersion != null) {
            val version = availability.version() ?: return AnkiResult.NotInstalled
            if (!version.atLeast(minVersion)) return AnkiResult.Outdated(version.name, minVersion.label)
        }
        return null
    }

    private suspend fun <T : Any> read(
        noAnswerMessage: String,
        minVersion: RequiredVersion? = RequiredVersion.TOOL_SET,
        query: () -> ProviderCall<T>,
    ): AnkiResult<T> {
        precheck(minVersion)?.let { return it }
        val limit = callTimeout() ?: return tooSlow()
        return queryRetryingNull(noAnswerMessage, limit, query)
    }

    /** The timeout for the next call: [timeoutMs], or less if the caller's [CallBudget] has less left. */
    private class Limit(val ms: Long, val byBudget: Boolean)

    /** Null when the caller's [CallBudget] is used up. */
    private suspend fun callTimeout(): Limit? {
        val budget = currentCoroutineContext()[CallBudget] ?: return Limit(timeoutMs, byBudget = false)
        val remaining = budget.remainingMs(clock.elapsedMs())
        return when {
            remaining <= 0 -> null
            remaining < timeoutMs -> Limit(remaining, byBudget = true)
            else -> Limit(timeoutMs, byBudget = false)
        }
    }

    /**
     * The timeout for a write under the caller's [CallBudget] (D57): none is started with less
     * than [MIN_WRITE_BUDGET_MS] left (null: not sent), and [CONFIRM_RESERVE_MS] stays for the
     * read that confirms it, so a write that went through can still be confirmed.
     */
    private suspend fun writeTimeout(): Limit? {
        val budget = currentCoroutineContext()[CallBudget] ?: return Limit(timeoutMs, byBudget = false)
        val remaining = budget.remainingMs(clock.elapsedMs())
        if (remaining < MIN_WRITE_BUDGET_MS) return null
        val allowed = remaining - CONFIRM_RESERVE_MS
        return if (allowed < timeoutMs) Limit(allowed, byBudget = true) else Limit(timeoutMs, byBudget = false)
    }

    private suspend fun tooSlow(): AnkiResult.TooSlow =
        AnkiResult.TooSlow(currentCoroutineContext()[CallBudget]?.totalMs ?: timeoutMs).also {
            log.warn("AnkiDroid calls ran past the ${it.budgetMs} ms budget")
        }

    /**
     * Runs [query] until it returns non-null or the retries run out. Still null after that, or
     * an attempt that hangs, is [AnkiResult.Frozen] on a freezing-OEM phone when every null
     * came back within [FAST_NULL_MS], otherwise [AnkiResult.Unavailable] with [noAnswerMessage].
     */
    private suspend fun <T : Any> queryRetryingNull(noAnswerMessage: String, limit: Limit, query: () -> ProviderCall<T>): AnkiResult<T> {
        val startMs = clock.elapsedMs()
        val nulls = mutableListOf<NullAttempt>()
        fun nullsSummary(): String {
            val took = nulls.map { "${it.call.tookMs}${if (it.call.viaClient) "c" else "r"}" }
            val died = nulls.withIndex().filter { it.value.call.clientDied }.map { it.index + 1 }
            val diedText = if (died.isEmpty()) "" else ", client died on attempts $died"
            return "AnkiDroid null cursor x${nulls.size} at ${nulls.map { it.startMs }} ms (took $took ms)$diedText"
        }
        fun frozenOrUnavailable(): AnkiResult<T> =
            if (freezingOemName != null && nulls.all { it.fast }) {
                AnkiResult.Frozen(freezingOemName)
            } else {
                AnkiResult.Unavailable(noAnswerMessage)
            }
        val outcome = withTimeoutOrNull(limit.ms) {
            var result: AnkiResult<ProviderCall<T>>
            while (true) {
                val attemptStartMs = clock.elapsedMs() - startMs
                val watched = freezingOemName != null && provider.isClientHeld()
                var frozenWhy = ""
                val attemptResult = if (watched) {
                    awaitUnlessFrozen(query) { frozenWhy = it }
                } else {
                    submit { AnkiResult.Success(query()) }
                }
                result = attemptResult ?: run {
                    val prefix = if (nulls.isEmpty()) "AnkiDroid query" else "${nullsSummary()}, then attempt ${nulls.size + 1}"
                    log.warn("$prefix $frozenWhy")
                    return@withTimeoutOrNull frozenOrUnavailable()
                }
                val call = (result as? AnkiResult.Success)?.value
                if (call == null || call.value != null) break
                val endMs = clock.elapsedMs() - startMs
                nulls += NullAttempt(attemptStartMs, call)
                val delayMs = nullRetryDelaysMs.getOrNull(nulls.size - 1)
                if (delayMs == null || endMs + delayMs > nullRetryBudgetMs) {
                    log.warn("${nullsSummary()}, gave up")
                    return@withTimeoutOrNull frozenOrUnavailable()
                }
                delay(delayMs)
            }
            if (nulls.isNotEmpty()) {
                val attempt = nulls.size + 1
                val elapsedMs = clock.elapsedMs() - startMs
                if (result is AnkiResult.Success) {
                    log.info("${nullsSummary()}, recovered on attempt $attempt after $elapsedMs ms")
                } else {
                    log.warn("${nullsSummary()}, then attempt $attempt failed after $elapsedMs ms")
                }
            }
            when (result) {
                is AnkiResult.Success -> AnkiResult.Success(checkNotNull(result.value.value))
                is AnkiResult.Failure -> result
            }
        }
        if (outcome != null) return outcome
        val elapsedMs = clock.elapsedMs() - startMs
        val prefix = if (nulls.isEmpty()) "AnkiDroid query" else "${nullsSummary()}, then"
        log.warn("$prefix timed out after $elapsedMs ms")
        return if (limit.byBudget) tooSlow() else AnkiResult.NotResponding(timeoutMs)
    }

    /**
     * Waits for a held-client [attempt] on a freezing-OEM phone without mistaking a slow
     * AnkiDroid for a frozen one (D57). If it has not answered [attemptTimeoutMs] after it started
     * on a thread (time queued for a thread does not count), a cheap
     * probe (the deck list, ids only; no schedule lock, no cap of its own beyond
     * [probeTimeoutMs]) runs alongside it. No answer to the probe either: AnkiDroid is frozen,
     * the attempt is abandoned and null is returned. A binder call cannot be cancelled
     * (AnkiDroid's provider ignores CancellationSignal, and creating one is itself a binder
     * call), so the abandoned call keeps its thread until AnkiDroid answers and its result is
     * discarded; such threads are counted as stuck until they finish. The probe's second counts
     * from when it actually starts on a thread, not from when it is queued: a probe waiting for a
     * free thread says nothing about AnkiDroid (a busy pool). The probe answered: the attempt
     * keeps going until the caller's overall limit, which reports TooSlow or NotResponding
     * rather than Frozen. An attempt (or probe) that cannot start because every pool thread is
     * held by a call already abandoned as frozen will never start: Frozen at once.
     * [frozen] receives the log wording when null is returned.
     */
    private suspend fun <R : Any> awaitUnlessFrozen(
        query: () -> ProviderCall<R>,
        frozen: (String) -> Unit,
    ): AnkiResult<ProviderCall<R>>? = coroutineScope {
        val call = Tracked()
        val running = async(start = CoroutineStart.UNDISPATCHED) { submit { call.run { AnkiResult.Success(query()) } } }
        // Time queued for a free thread says nothing about AnkiDroid: the attempt's clock starts when it does.
        while (!call.isStarted) {
            if (running.isCompleted) return@coroutineScope running.await()
            if (pinned()) {
                running.cancel()
                frozen("could not start: every provider thread is held by a call AnkiDroid never answered")
                return@coroutineScope null
            }
            delay(START_POLL_MS)
        }
        withTimeoutOrNull(attemptTimeoutMs) { running.await() }?.let { return@coroutineScope it }
        val probeCall = Tracked()
        val probe = async(start = CoroutineStart.UNDISPATCHED) {
            submit { probeCall.run { AnkiResult.Success(provider.query("decks", PROBE_PROJECTION)) } }
        }
        var waitedForThread = false
        while (!probeCall.isStarted) {
            if (call.isFinished || running.isCompleted) {
                probe.cancel()
                return@coroutineScope running.await()
            }
            if (pinned()) {
                call.abandonAsFrozen()
                running.cancel()
                probe.cancel()
                frozen("hung for $attemptTimeoutMs ms and every other provider thread is held by a call AnkiDroid never answered")
                return@coroutineScope null
            }
            if (!waitedForThread) {
                log.info("AnkiDroid query still running after $attemptTimeoutMs ms; a probe is still waiting for a free thread")
            }
            waitedForThread = true
            delay(START_POLL_MS)
        }
        val answer = withTimeoutOrNull(probeTimeoutMs) { probe.await() }
        val alive = answer != null && !(answer is AnkiResult.Success && answer.value.value == null)
        if (!alive) {
            call.abandonAsFrozen()
            probeCall.abandonAsFrozen()
            running.cancel()
            probe.cancel()
            frozen("hung for $attemptTimeoutMs ms and a probe got no answer within $probeTimeoutMs ms")
            return@coroutineScope null
        }
        val late = if (waitedForThread) " (the probe first waited for a free thread)" else ""
        log.info("AnkiDroid query still running after $attemptTimeoutMs ms; AnkiDroid answered a probe$late, so waiting")
        running.await()
    }

    /** Provider threads held by calls abandoned as frozen that have not returned yet. */
    private val stuckFrozen = AtomicInteger()

    private fun pinned() = stuckFrozen.get() >= poolSize

    /** A provider call's lifecycle, so a frozen call's thread is counted as stuck until it returns. */
    private inner class Tracked {
        private var started = false
        private var finished = false
        private var abandoned = false

        val isStarted: Boolean get() = synchronized(this) { started }
        val isFinished: Boolean get() = synchronized(this) { finished }

        fun <T> run(block: () -> T): T {
            synchronized(this) { started = true }
            try {
                return block()
            } finally {
                synchronized(this) {
                    finished = true
                    if (abandoned) stuckFrozen.decrementAndGet()
                }
            }
        }

        fun abandonAsFrozen() = synchronized(this) {
            if (started && !finished && !abandoned) {
                abandoned = true
                stuckFrozen.incrementAndGet()
            }
        }
    }

    private suspend fun <T> submit(block: () -> AnkiResult<T>): AnkiResult<T> {
        val result = CompletableDeferred<AnkiResult<T>>()
        try {
            providerExecutor.execute {
                // Still queued when the caller gave up: don't send AnkiDroid a query nobody waits for.
                if (result.isActive) result.complete(runProviderCall(block))
            }
        } catch (_: RejectedExecutionException) {
            return AnkiResult.Busy
        }
        try {
            return result.await()
        } finally {
            result.cancel()
        }
    }

    private fun <T> runProviderCall(block: () -> AnkiResult<T>): AnkiResult<T> = try {
        block()
    } catch (e: SecurityException) {
        AnkiResult.PermissionDenied
    } catch (e: IllegalStateException) {
        if (isStorageNotConfigured(e)) {
            AnkiResult.NotReady(e.message)
        } else {
            AnkiResult.Unavailable(providerError(e))
        }
    } catch (e: ScheduleBusyException) {
        AnkiResult.Busy
    } catch (e: ProviderDiedException) {
        // Only through the resolver: the pin handles a dead held client itself (D51).
        AnkiResult.Unavailable("AnkiDroid stopped answering while sending the results. Ask again.")
    } catch (e: Exception) {
        // IllegalArgumentException (an invalid search, with AnkiDroid's explanation as far as it
        // crosses the binder), and the RuntimeException the binder layer raises for an exception
        // AnkiDroid cannot send back (e.g. a bad `re:` regex failing inside the database). Only a
        // RemoteException means a dead client, and the pin has handled that before here (D57).
        AnkiResult.Rejected(e.message ?: e.javaClass.simpleName)
    }

    companion object {
        /** Most cards one provider call may render; AnkiDroid 2.24/2.25 render every card a non-id query returns (D57). */
        const val MAX_CARDS_PER_CALL = 100

        /** How long a schedule query waits for its turn behind another one before reporting busy. */
        const val SCHEDULE_LOCK_WAIT_MS = 3_000L

        /**
         * Below the 25 s tunnel request timeout so the user gets this readable tool error
         * rather than a generic 504.
         */
        const val DEFAULT_TIMEOUT_MS = 20_000L

        /** Four attempts over about 2 s (D51). */
        val NULL_RETRY_DELAYS_MS = listOf(250L, 750L, 1_000L)

        /**
         * No retry starts later than this after the first attempt. Above the 2 s the delays add
         * up to, so the attempts' own time does not cut off the last one.
         */
        const val NULL_RETRY_BUDGET_MS = 2_500L

        /** A null cursor faster than this is the freezer refusing the provider lookup (D51). */
        const val FAST_NULL_MS = 100L

        /**
         * An attempt still unanswered after this is held by the freezer (D51). Short because the
         * freezer refreezes our own process 5-8 s after a thaw.
         */
        const val ATTEMPT_TIMEOUT_MS = 2_500L

        /** A write is not started with less of the tool's budget left than this (D57). */
        const val MIN_WRITE_BUDGET_MS = 3_000L

        /** Budget kept back from a write for the read that confirms it (D57). */
        const val CONFIRM_RESERVE_MS = 2_000L

        /** How long the freeze probe may take; the deck list normally answers in about 10 ms (D57). */
        const val PROBE_TIMEOUT_MS = 1_000L

        /** How often the freeze watch checks whether a queued call has started. */
        private const val START_POLL_MS = 50L

        /** The freeze probe's projection: deck ids only. */
        private val PROBE_PROJECTION = listOf(FlashCardsContract.Deck.DECK_ID)

        /** Upper bound on threads that a hung AnkiDroid can pin. */
        const val MAX_PROVIDER_THREADS = 4

        /** Calls that may wait for a free provider thread before new ones are rejected as busy. */
        const val PROVIDER_QUEUE_CAPACITY = 8

        private const val QUEUED = 0
        private const val RUNNING = 1
        private const val ABANDONED = 2

        private val DECK_PROJECTION = listOf(
            FlashCardsContract.Deck.DECK_ID,
            FlashCardsContract.Deck.DECK_NAME,
            FlashCardsContract.Deck.DECK_COUNTS,
            FlashCardsContract.Deck.DECK_DYN,
        )

        private val DECK_OPTIONS_PROJECTION = listOf(
            FlashCardsContract.Deck.DECK_ID,
            FlashCardsContract.Deck.DECK_NAME,
            FlashCardsContract.Deck.DECK_DYN,
            FlashCardsContract.Deck.OPTIONS,
        )

        private val NOTE_TYPE_PROJECTION = listOf(
            FlashCardsContract.Model._ID,
            FlashCardsContract.Model.NAME,
            FlashCardsContract.Model.FIELD_NAMES,
            FlashCardsContract.Model.CSS,
            FlashCardsContract.Model.LATEX_PRE,
            FlashCardsContract.Model.LATEX_POST,
            FlashCardsContract.Model.TYPE,
        )

        private val TEMPLATE_PROJECTION = listOf(
            FlashCardsContract.CardTemplate.ORD,
            FlashCardsContract.CardTemplate.NAME,
            FlashCardsContract.CardTemplate.QUESTION_FORMAT,
            FlashCardsContract.CardTemplate.ANSWER_FORMAT,
        )

        /** The card search's fast path: AnkiDroid builds no card objects when only `_id` is asked for. */
        private val ID_ONLY = listOf(FlashCardsContract.Card._ID)

        /**
         * Up to [maxThreads] threads (started per call until the limit, idle ones exit after 60 s),
         * then a FIFO queue of [queueCapacity]. When that is full too, `execute` throws
         * [RejectedExecutionException], which [AnkiDroidRepository] reports as [AnkiResult.Busy].
         */
        fun newProviderExecutor(
            maxThreads: Int = MAX_PROVIDER_THREADS,
            queueCapacity: Int = PROVIDER_QUEUE_CAPACITY,
        ): ThreadPoolExecutor {
            val count = AtomicInteger()
            // core == max: with a bounded queue, threads above the core size would only start once
            // the queue is full, so calls would wait behind a stuck one while threads were free.
            return ThreadPoolExecutor(
                maxThreads,
                maxThreads,
                60L,
                TimeUnit.SECONDS,
                ArrayBlockingQueue(queueCapacity),
                { r -> Thread(r, "anki-provider-${count.incrementAndGet()}").apply { isDaemon = true } },
                ThreadPoolExecutor.AbortPolicy(),
            ).apply { allowCoreThreadTimeOut(true) }
        }

        private val json = Json { ignoreUnknownKeys = true }

        private fun isStorageNotConfigured(e: IllegalStateException): Boolean {
            val message = e.message ?: return false
            return message.contains("storage", ignoreCase = true) || message.contains("not configured", ignoreCase = true)
        }

        private fun providerError(e: Exception): String =
            "AnkiDroid returned an error: ${e.message ?: e.javaClass.simpleName}"

        /** DECK_COUNTS is `[learn, review, new]`. */
        internal fun parseCounts(raw: String): DeckCounts? = try {
            val array = json.parseToJsonElement(raw) as? JsonArray
            val values = array?.map { (it as? JsonPrimitive)?.longOrNull }
            if (values == null || values.size < 3 || values.any { it == null }) {
                null
            } else {
                DeckCounts(learn = values[0]!!, review = values[1]!!, new = values[2]!!)
            }
        } catch (_: Exception) {
            null
        }

        internal fun parseBoolean(raw: String?): Boolean =
            raw != null && (raw.equals("true", ignoreCase = true) || raw == "1")

        /** Anki joins field values (and the provider joins field names) with the unit separator. */
        internal fun splitFields(raw: String): List<String> = raw.split(FIELD_SEPARATOR)

        /** The raw tags column is space separated with sentinel spaces (`" a b "`). */
        internal fun splitTags(raw: String): List<String> = raw.split(' ', '\t', '\n', '\r', '\u000c').filter { it.isNotEmpty() }

        private const val FIELD_SEPARATOR = "\u001f"

        private fun noteOf(row: ProviderRow): NoteData? = row.long(FlashCardsContract.Note._ID)?.let { id -> noteOf(id, row) }

        private fun noteOf(id: Long, row: ProviderRow) = NoteData(
            id = id,
            modelId = row.long(FlashCardsContract.Note.MID),
            fields = row.string(FlashCardsContract.Note.FLDS)?.let(::splitFields),
            tags = row.string(FlashCardsContract.Note.TAGS)?.let(::splitTags),
            mod = row.long(FlashCardsContract.Note.MOD),
        )

        private fun cardOf(row: ProviderRow): CardData? = row.long(FlashCardsContract.Card._ID)?.let { id -> cardOf(id, row) }

        private fun cardOf(id: Long, row: ProviderRow) = CardData(
            id = id,
            noteId = row.long(CardField.NOTE_ID.column),
            ord = row.long(CardField.ORD.column)?.toInt(),
            deckId = row.long(CardField.DECK_ID.column),
            originalDeckId = row.long(CardField.ORIGINAL_DECK_ID.column),
            question = row.string(CardField.QUESTION.column),
            answer = row.string(CardField.ANSWER.column),
            type = row.long(CardField.TYPE.column)?.toInt(),
            queue = row.long(CardField.QUEUE.column)?.toInt(),
            due = row.long(CardField.DUE.column),
            interval = row.long(CardField.INTERVAL.column),
            factor = row.long(CardField.FACTOR.column),
            reps = row.long(CardField.REPS.column),
            lapses = row.long(CardField.LAPSES.column),
            stability = row.double(CardField.STABILITY.column),
            difficulty = row.double(CardField.DIFFICULTY.column),
            decay = row.double(CardField.DECAY.column),
            lastReviewSecs = row.long(CardField.LAST_REVIEW_SECS.column),
            flags = row.long(CardField.FLAGS.column)?.toInt(),
        )

        internal fun ProviderRow.long(column: String): Long? = when (val v = this[column]) {
            is Long -> v
            is Int -> v.toLong()
            is Double -> v.toLong()
            is String -> v.trim().toLongOrNull()
            else -> null
        }

        internal fun ProviderRow.double(column: String): Double? = when (val v = this[column]) {
            is Double -> v
            is Float -> v.toDouble()
            is Long -> v.toDouble()
            is Int -> v.toDouble()
            is String -> v.trim().toDoubleOrNull()
            else -> null
        }

        internal fun ProviderRow.string(column: String): String? = when (val v = this[column]) {
            null -> null
            is String -> v
            is ByteArray -> String(v, Charsets.UTF_8)
            else -> v.toString()
        }

        private inline fun <T, R> ProviderCall<T>.map(transform: (T) -> R): ProviderCall<R> =
            ProviderCall(value?.let(transform), tookMs, viaClient, clientDied)
    }
}
