package ai.ankimcp.ankidroid.tools

import ai.ankimcp.ankidroid.anki.AnkiDroidAvailability
import ai.ankimcp.ankidroid.anki.AnkiDroidRepository
import ai.ankimcp.ankidroid.anki.FakeAvailability
import ai.ankimcp.ankidroid.anki.FlashCardsContract.Card
import ai.ankimcp.ankidroid.anki.FlashCardsContract.CardTemplate
import ai.ankimcp.ankidroid.anki.FlashCardsContract.Deck
import ai.ankimcp.ankidroid.anki.FlashCardsContract.Model
import ai.ankimcp.ankidroid.anki.FlashCardsContract.Note
import ai.ankimcp.ankidroid.anki.FlashCardsContract.ReviewInfo
import ai.ankimcp.ankidroid.anki.ProviderCall
import ai.ankimcp.ankidroid.anki.ProviderRow
import ai.ankimcp.ankidroid.anki.Query
import ai.ankimcp.ankidroid.anki.RecordingProvider
import ai.ankimcp.ankidroid.log.TunnelLog
import ai.ankimcp.ankidroid.mcp.Tool
import ai.ankimcp.ankidroid.mcp.ToolResult
import ai.ankimcp.ankidroid.tunnel.ElapsedClock
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.Executor

/**
 * An AnkiDroid collection behind a fake provider that answers like AnkiDroid's
 * CardContentProvider: `notes` returns a null cursor when nothing matches, `cards` never does
 * and rejects an invalid search with an `IllegalArgumentException`, and rows carry only the
 * projected columns. The search understands just what the tools send: "", `nid:`, `cid:`,
 * `deck:` (with subdecks), `did:N`, `-deck:filtered`, `added:N mid:M` and `Field:"value"`; a
 * search containing "(" is invalid.
 *
 * Writes follow the provider too: `decks` insert refuses an existing name, `notes?deckId=`
 * bulkInsert adds notes with ids from [nowMillis] and their cards in that deck and returns a count,
 * `notes/N` update takes all fields or the tag string, `schedule` update buries, suspends or
 * answers (and with [swallowSchedule] reports 1 while doing nothing, as AnkiDroid does when the
 * scheduler call fails), and so on. [refuse] can fail any write.
 */
class FakeCollection {
    data class FDeck(val id: Long, val name: String, val counts: String? = "[0,0,0]", val dyn: Boolean = false, val options: String? = null)

    data class FTemplate(val name: String, val front: String, val back: String)

    data class FModel(
        val id: Long,
        val name: String,
        val fields: List<String>,
        val css: String = ".card {}",
        val latexPre: String = "\\documentclass",
        val latexPost: String = "\\end{document}",
        val templates: List<FTemplate> = listOf(FTemplate("Card 1", "{{Front}}", "{{Back}}")),
        val cloze: Boolean = false,
    )

    data class FNote(val id: Long, val mid: Long, val fields: List<String>, val tags: List<String> = emptyList(), val mod: Long = 1_700_000_000)

    data class FCard(
        val id: Long,
        val nid: Long,
        val ord: Int = 0,
        val did: Long = 1,
        val odid: Long = 0,
        val question: String = "Q$id",
        val answer: String = "A$id",
        val type: Int = 2,
        val queue: Int = 2,
        val due: Long = 100,
        val ivl: Long = 10,
        val factor: Long = 2500,
        val reps: Long = 5,
        val lapses: Long = 1,
        val stability: Double? = null,
        val difficulty: Double? = null,
        val decay: Double? = null,
        val lastReview: Long? = null,
        val flags: Int = 0,
    )

    /** A provider write as AnkiDroid received it. */
    data class Write(val op: String, val path: String, val values: Map<String, Any?>)

    val decks = mutableListOf(FDeck(1, "Default"))
    val models = mutableListOf<FModel>()
    val notes = mutableListOf<FNote>()
    val cards = mutableListOf<FCard>()
    var scheduled: Pair<Long, Int>? = null
    var nowMillis = 1_800_000_000_000L
    var swallowSchedule = false
    var refuse: (Write) -> Exception? = { null }

    /** Reads that get a null cursor, e.g. to lose the read that confirms a write. */
    var nullReads: (Query) -> Boolean = { false }

    /** Runs after a write was applied, e.g. to undo it as if AnkiDroid had not kept it. */
    var afterWrite: (Write) -> Unit = {}
    val writes = mutableListOf<Write>()

    private var nextCardId = 900_000L

    val provider = object : RecordingProvider(::answer) {
        override fun insert(path: String, values: Map<String, Any?>) = write("insert", path, values) { insertRow(path, values) }

        override fun update(path: String, values: Map<String, Any?>, selection: String?, selectionArgs: List<String>?) =
            write("update", path, values) { updateRow(path, values) }

        override fun delete(path: String, selection: String?, selectionArgs: List<String>?) =
            write("delete", path, emptyMap()) { deleteRow(path) }

        override fun bulkInsert(path: String, rows: List<Map<String, Any?>>) =
            write("bulkInsert", path, mapOf("rows" to rows)) { bulkInsertRows(path, rows) }
    }

    val availability = FakeAvailability()

    /** Elapsed time the repository sees; tests advance it (e.g. in [refuse]) to run out a tool's budget. */
    var elapsedMs = 0L

    fun repository(availability: AnkiDroidAvailability = this.availability) = AnkiDroidRepository(
        provider,
        availability,
        Executor { it.run() },
        TunnelLog(mirrorToLogcat = false),
        ElapsedClock { elapsedMs },
        nullRetryDelaysMs = listOf(1L),
    )

    private fun <T> write(op: String, path: String, values: Map<String, Any?>, block: () -> T): ProviderCall<T> {
        val w = Write(op, path, values)
        writes += w
        refuse(w)?.let { throw it }
        val value = block()
        afterWrite(w)
        return ProviderCall(value, 0, viaClient = true, clientDied = false)
    }

    private fun insertRow(path: String, values: Map<String, Any?>): String {
        val fieldsPath = Regex("""models/(\d+)/fields""").matchEntire(path)
        return when {
            path == "decks" -> {
                val name = values[Deck.DECK_NAME] as String
                if (decks.any { it.name.equals(name, true) }) throw IllegalArgumentException("Deck name already exists: $name")
                val id = (decks.maxOf { it.id }) + 1
                decks += FDeck(id, name)
                "content://com.ichi2.anki.flashcards/decks/$id"
            }
            path == "models" -> {
                val id = (models.maxOfOrNull { it.id } ?: 100) + 1
                val fields = (values[Model.FIELD_NAMES] as String).split("\u001f")
                val n = (values[Model.NUM_CARDS] as Number).toInt()
                models += FModel(
                    id,
                    values[Model.NAME] as String,
                    fields,
                    css = values[Model.CSS] as String? ?: ".card {}",
                    templates = (1..n).map { FTemplate("Card $it", "{{${fields[0]}}}", "{{FrontSide}}") },
                )
                "content://com.ichi2.anki.flashcards/models/$id"
            }
            fieldsPath != null -> {
                val mid = fieldsPath.groupValues[1].toLong()
                val model = models.first { it.id == mid }
                replaceModel(model.copy(fields = model.fields + (values[Model.FIELD_NAME] as String)))
                "content://com.ichi2.anki.flashcards/models/$mid/fields/${model.fields.size}"
            }
            else -> throw IllegalArgumentException("uri $path is not supported")
        }
    }

    private fun bulkInsertRows(path: String, rows: List<Map<String, Any?>>): Int {
        val deckId = path.substringAfter("deckId=").toLong()
        if (decks.first { it.id == deckId }.dyn) {
            throw IllegalArgumentException("A filtered deck cannot be specified as the deck in bulkInsertNotes")
        }
        rows.forEachIndexed { i, row ->
            val mid = (row[Note.MID] as Number).toLong()
            val model = models.first { it.id == mid }
            val fields = (row[Note.FLDS] as String).split("\u001f")
            if (fields.size != model.fields.size) throw IllegalArgumentException("Incorrect flds argument : ${row[Note.FLDS]}")
            val id = nowMillis + i
            notes += FNote(id, mid, fields, (row[Note.TAGS] as String? ?: "").split(" ").filter { it.isNotEmpty() })
            model.templates.indices.forEach { ord -> cards += FCard(nextCardId++, id, ord = ord, did = deckId, type = 0, queue = 0, reps = 0) }
        }
        return rows.size
    }

    private fun updateRow(path: String, values: Map<String, Any?>): Int {
        val notePath = Regex("""notes/(\d+)""").matchEntire(path)
        val cardPath = Regex("""notes/(\d+)/cards/(\d+)""").matchEntire(path)
        val modelPath = Regex("""models/(\d+)""").matchEntire(path)
        val templatePath = Regex("""models/(\d+)/templates/(\d+)""").matchEntire(path)
        return when {
            notePath != null -> {
                val id = notePath.groupValues[1].toLong()
                var note = notes.firstOrNull { it.id == id } ?: throw RuntimeException("BackendNotFoundException")
                values[Note.FLDS]?.let { flds ->
                    val fields = (flds as String).split("\u001f")
                    if (fields.size != note.fields.size) throw IllegalArgumentException("Incorrect flds argument : $flds")
                    note = note.copy(fields = fields)
                }
                values[Note.TAGS]?.let { note = note.copy(tags = (it as String).split(" ").filter { t -> t.isNotEmpty() }) }
                notes[notes.indexOfFirst { it.id == id }] = note
                values.size
            }
            cardPath != null -> {
                val (nid, ord) = cardPath.destructured
                val i = cards.indexOfFirst { it.nid == nid.toLong() && it.ord == ord.toInt() }
                if (i < 0) throw IllegalArgumentException("Card with ord $ord does not exist for note $nid")
                values[Card.DECK_ID]?.let { cards[i] = cards[i].copy(did = (it as Number).toLong()) }
                values[Card.FLAGS]?.let { cards[i] = cards[i].copy(flags = (it as Number).toInt()) }
                1
            }
            path == "schedule" -> {
                val nid = (values[ReviewInfo.NOTE_ID] as Number).toLong()
                val ord = (values[ReviewInfo.CARD_ORD] as Number).toInt()
                val i = cards.indexOfFirst { it.nid == nid && it.ord == ord }
                if (i < 0) throw RuntimeException("BackendNotFoundException")
                if (!swallowSchedule) {
                    val c = cards[i]
                    cards[i] = when {
                        values[ReviewInfo.BURY] == 1 -> c.copy(queue = -3)
                        values[ReviewInfo.SUSPEND] == 1 -> c.copy(queue = -1)
                        else -> c.copy(
                            reps = c.reps + 1,
                            type = 2,
                            queue = 2,
                            ivl = 4 * (values[ReviewInfo.EASE] as Number).toLong(),
                            due = 200,
                            lastReview = nowMillis / 1000,
                        )
                    }
                }
                1
            }
            templatePath != null -> {
                val (mid, ord) = templatePath.destructured
                val model = models.first { it.id == mid.toLong() }
                val t = model.templates[ord.toInt()]
                val next = FTemplate(
                    values[CardTemplate.NAME] as String? ?: t.name,
                    values[CardTemplate.QUESTION_FORMAT] as String? ?: t.front,
                    values[CardTemplate.ANSWER_FORMAT] as String? ?: t.back,
                )
                replaceModel(model.copy(templates = model.templates.toMutableList().also { it[ord.toInt()] = next }))
                values.size
            }
            modelPath != null -> {
                val model = models.first { it.id == modelPath.groupValues[1].toLong() }
                replaceModel(
                    model.copy(
                        css = values[Model.CSS] as String? ?: model.css,
                        latexPre = values[Model.LATEX_PRE] as String? ?: model.latexPre,
                        latexPost = values[Model.LATEX_POST] as String? ?: model.latexPost,
                        cloze = values[Model.TYPE]?.let { (it as Number).toInt() == 1 } ?: model.cloze,
                    ),
                )
                values.size
            }
            else -> throw IllegalArgumentException("uri $path is not supported")
        }
    }

    private fun deleteRow(path: String): Int {
        val id = path.removePrefix("notes/").toLong()
        notes.removeAll { it.id == id }
        cards.removeAll { it.nid == id }
        return 1
    }

    private fun replaceModel(model: FModel) {
        models[models.indexOfFirst { it.id == model.id }] = model
    }

    private fun answer(q: Query): List<ProviderRow>? {
        if (nullReads(q)) return null
        val templates = Regex("""models/(\d+)/templates""").matchEntire(q.path)
        return when {
            q.path == "decks" -> decks.map { d ->
                mapOf(
                    Deck.DECK_ID to d.id,
                    Deck.DECK_NAME to d.name,
                    Deck.DECK_COUNTS to d.counts,
                    Deck.DECK_DYN to d.dyn.toString(),
                    Deck.OPTIONS to d.options,
                )
            }
            q.path == "models" -> models.map { m ->
                mapOf(
                    Model._ID to m.id,
                    Model.NAME to m.name,
                    Model.FIELD_NAMES to m.fields.joinToString("\u001f"),
                    Model.CSS to m.css,
                    Model.LATEX_PRE to m.latexPre,
                    Model.LATEX_POST to m.latexPost,
                    Model.TYPE to if (m.cloze) 1L else 0L,
                )
            }
            templates != null -> models.first { it.id == templates.groupValues[1].toLong() }.templates.mapIndexed { ord, t ->
                mapOf(
                    CardTemplate.ORD to ord.toLong(),
                    CardTemplate.NAME to t.name,
                    CardTemplate.QUESTION_FORMAT to t.front,
                    CardTemplate.ANSWER_FORMAT to t.back,
                )
            }
            q.path == "cards" -> search(q.selection ?: "").map(::cardRow)
            q.path == "notes" -> {
                val ids = search(q.selection ?: "").map { it.nid }.toSet()
                notes.filter { it.id in ids }.map(::noteRow).ifEmpty { null }
            }
            q.path == "schedule" -> listOfNotNull(scheduled?.let { (nid, ord) -> mapOf(ReviewInfo.NOTE_ID to nid, ReviewInfo.CARD_ORD to ord.toLong()) })
            else -> throw IllegalArgumentException("uri ${q.path} is not supported")
        }
    }

    private fun search(query: String): List<FCard> {
        if ("(" in query) throw IllegalArgumentException("Invalid Anki search query: \"$query\"")
        var result: List<FCard> = cards.toList()
        var rest = query.trim()
        if (rest.endsWith(" -deck:filtered")) {
            result = result.filter { it.odid == 0L }
            rest = rest.removeSuffix(" -deck:filtered")
        }
        val fieldSearch = Regex("""^(\w+):"(.*)"$""").matchEntire(rest)
        when {
            rest.isEmpty() -> {}
            rest.startsWith("added:") -> {
                val mid = rest.substringAfter("mid:").toLong()
                val nids = notes.filter { it.mid == mid }.map { it.id }.toSet()
                result = result.filter { it.nid in nids }
            }
            rest.startsWith("nid:") -> rest.removePrefix("nid:").split(",").map { it.toLong() }.toSet().let { ids -> result = result.filter { it.nid in ids } }
            rest.startsWith("cid:") -> rest.removePrefix("cid:").split(",").map { it.toLong() }.toSet().let { ids -> result = result.filter { it.id in ids } }
            rest.startsWith("did:") -> rest.removePrefix("did:").toLong().let { did -> result = result.filter { it.did == did || it.odid == did } }
            fieldSearch != null && !rest.startsWith("deck:") -> {
                val (field, value) = fieldSearch.destructured
                val nids = notes.filter { note ->
                    val model = models.first { it.id == note.mid }
                    model.fields.indexOf(field).let { i -> i >= 0 && note.fields[i].equals(value, ignoreCase = true) }
                }.map { it.id }.toSet()
                result = result.filter { it.nid in nids }
            }
            else -> {
                val name = rest.removeSurrounding("\"").removePrefix("deck:").replace(Regex("""\\(.)"""), "$1")
                val dids = decks.filter { it.name.equals(name, true) || it.name.startsWith("$name::", true) }.map { it.id }.toSet()
                result = result.filter { it.did in dids || it.odid in dids }
            }
        }
        return result
    }

    private fun cardRow(c: FCard): ProviderRow = mapOf(
        Card._ID to c.id,
        Card.NOTE_ID to c.nid,
        Card.CARD_ORD to c.ord.toLong(),
        Card.DECK_ID to c.did,
        Card.ORIGINAL_DECK_ID to c.odid,
        Card.QUESTION_SIMPLE to c.question,
        Card.ANSWER_SIMPLE to c.answer,
        Card.TYPE to c.type.toLong(),
        Card.RAW_QUEUE to c.queue.toLong(),
        Card.RAW_DUE to c.due,
        Card.INTERVAL to c.ivl,
        Card.RAW_SM2_FACTOR to c.factor,
        Card.REPS to c.reps,
        Card.LAPSES to c.lapses,
        Card.FSRS_STABILITY to c.stability,
        Card.FSRS_DIFFICULTY to c.difficulty,
        Card.FSRS_DECAY to c.decay,
        Card.LAST_REVIEW_TIME_SECONDS to c.lastReview,
        Card.FLAGS to c.flags.toLong(),
    )

    private fun noteRow(n: FNote): ProviderRow = mapOf(
        Note._ID to n.id,
        Note.MID to n.mid,
        Note.FLDS to n.fields.joinToString("\u001f"),
        Note.TAGS to n.tags.joinToString(" ", " ", " "),
        Note.MOD to n.mod,
    )

    /** Queries sent to [path] so far. */
    fun queriesTo(path: String) = provider.queries.filter { it.path == path }

    fun note(id: Long) = notes.single { it.id == id }

    fun card(id: Long) = cards.single { it.id == id }
}

/** JSON for a Kotlin test value: strings, numbers, booleans, lists and maps of those, or JSON elements. */
fun toJson(v: Any?): JsonElement = when (v) {
    null -> JsonNull
    is JsonElement -> v
    is String -> JsonPrimitive(v)
    is Number -> JsonPrimitive(v)
    is Boolean -> JsonPrimitive(v)
    is List<*> -> JsonArray(v.map(::toJson))
    is Map<*, *> -> JsonObject(v.entries.associate { (k, value) -> k.toString() to toJson(value) })
    else -> error("unsupported $v")
}

fun Tool.callWith(vararg args: Pair<String, Any?>): ToolResult = runBlocking {
    call(JsonObject(args.associate { (k, v) -> k to toJson(v) }))
}

val ToolResult.text: String get() = content[0].jsonObject["text"]!!.jsonPrimitive.content

val ToolResult.data: JsonObject get() {
    check(!isError) { "tool error: $text" }
    return structuredContent!!
}
