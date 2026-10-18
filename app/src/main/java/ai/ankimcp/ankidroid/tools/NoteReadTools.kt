package ai.ankimcp.ankidroid.tools

import ai.ankimcp.ankidroid.anki.AnkiDroidRepository
import ai.ankimcp.ankidroid.anki.AnkiResult
import ai.ankimcp.ankidroid.anki.CardField
import ai.ankimcp.ankidroid.anki.NoteField
import ai.ankimcp.ankidroid.mcp.ToolResult
import ai.ankimcp.ankidroid.mcp.readOnlyAnnotations
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** `nid:1,2,3` (a comma-separated id list is valid rslib syntax for `nid:` and `cid:`). */
internal fun idSearch(prefix: String, ids: Collection<Long>): String = "$prefix:${ids.joinToString(",")}"

/** `find_notes`. Same paging, limits and texts as the add-on; ids ascending. */
class FindNotesTool(repository: AnkiDroidRepository) : AnkiTool(repository) {
    override val name = "find_notes"
    override val title = "Find Notes"
    override val description =
        "Search for notes using Anki query syntax. Returns an array of note IDs matching the query. " +
            "Supports pagination with limit/offset parameters. Maximum limit is $MAX_LIMIT per request. " +
            "Examples: \"deck:Spanish\", \"tag:verb\", \"is:due\", \"front:hello\", \"added:1\" (cards added today), " +
            "\"prop:due<=2\" (cards due within 2 days), \"flag:1\" (red flag), \"is:suspended\". " +
            "Set include_first_field=true to also get a 'noteLabels' array of " +
            "{noteId, firstField} entries " +
            "(first field excerpted at $FIRST_FIELD_EXCERPT_CHARS chars, with truncated/fullLength markers) - " +
            "one call to find AND label results instead of find_notes followed by notes_info. " +
            "The labels identify notes; read full content with notes_info before editing. " +
            "Pass 'noteIds' (not 'noteLabels') to any tool that wants note IDs."
    override val inputSchema: JsonObject = inputSchema(required = listOf("query")) {
        property("query", "string", "Anki search query, e.g. \"deck:Spanish\" or \"is:due\"")
        property("limit", "integer", "Maximum note IDs to return (max $MAX_LIMIT)", default = JsonPrimitive(100))
        property("offset", "integer", "Results to skip, for pagination", default = JsonPrimitive(0))
        property(
            "include_first_field",
            "boolean",
            "Also return each note's excerpted first field",
            default = JsonPrimitive(false),
        )
    }
    override val annotations: JsonObject = readOnlyAnnotations(title)

    override suspend fun run(args: ToolArgs): ToolResult {
        val query = args.string("query")
        val limit = args.int("limit", 100)
        val offset = args.int("offset", 0)
        val includeFirstField = args.bool("include_first_field", false)
        pagingError(limit, offset, MAX_LIMIT)?.let { return it }

        val notes = when (val result = repository.searchNotes(query)) {
            is AnkiResult.Success -> result.value
            is AnkiResult.Rejected -> return addonError(
                "Search query failed: ${result.detail}",
                hint = "Check Anki documentation for valid search syntax",
                context = mapOf(
                    "examples" to listOf(
                        "\"deck:DeckName\" - all notes in a deck",
                        "\"tag:important\" - notes with specific tag",
                        "\"is:due\" - cards that are due for review",
                        "\"added:7\" - notes added in last 7 days",
                    ),
                ),
            )
            is AnkiResult.Failure -> return ToolResult.error(result.message)
        }
        val total = notes.size
        if (total == 0) {
            return ToolResult.structured(
                buildJsonObject {
                    putJsonArray("noteIds") {}
                    put("count", 0)
                    put("total", 0)
                    put("hasMore", false)
                    put("offset", offset)
                    put("limit", limit)
                    put("query", query)
                    put("message", "No notes found matching the search criteria")
                    put("hint", "Try a broader search query or check your deck/tag names")
                    if (includeFirstField) putJsonArray("noteLabels") {}
                },
            )
        }
        val page = notes.drop(offset).take(limit)
        val hasMore = offset.toLong() + limit < total
        // Fields only for the page: the search itself asked for ids alone.
        val firstFields = if (includeFirstField && page.isNotEmpty()) {
            repository.searchNotes(idSearch("nid", page.map { it.id }), setOf(NoteField.FIELDS)).valueOr { return it }
                .associate { it.id to it.fields?.firstOrNull() }
        } else {
            emptyMap()
        }
        return ToolResult.structured(
            buildJsonObject {
                putJsonArray("noteIds") { page.forEach { add(it.id) } }
                put("count", page.size)
                put("total", total)
                put("hasMore", hasMore)
                put("offset", offset)
                put("limit", limit)
                put("query", query)
                put("message", "Found $total note${if (total != 1) "s" else ""} matching the query, returning ${page.size}")
                put(
                    "hint",
                    if (hasMore) {
                        "Use notes_info tool to get detailed information about these notes. " +
                            "Use offset parameter to fetch more results."
                    } else {
                        "Use notes_info tool to get detailed information about these notes"
                    },
                )
                if (includeFirstField) {
                    putJsonArray("noteLabels") {
                        // A note deleted between the two searches is skipped, as the add-on does.
                        page.filter { it.id in firstFields }.forEach { note ->
                            val value = firstFields[note.id] ?: ""
                            val fullLength = pyLen(value)
                            val truncated = fullLength > FIRST_FIELD_EXCERPT_CHARS
                            addJsonObject {
                                put("noteId", note.id)
                                put("firstField", if (truncated) pyPrefix(value, FIRST_FIELD_EXCERPT_CHARS) else value)
                                put("truncated", truncated)
                                put("fullLength", fullLength)
                            }
                        }
                    }
                }
            },
        )
    }

    companion object {
        const val MAX_LIMIT = 500
        const val FIRST_FIELD_EXCERPT_CHARS = 250
    }
}

/** The add-on's limit/offset validation (find_notes, cards_stats). */
internal fun pagingError(limit: Int, offset: Int, maxLimit: Int): ToolResult? = when {
    limit <= 0 -> addonError(
        "limit must be positive",
        hint = "Use a value >= 1",
        code = "validation_error",
        context = mapOf("provided_value" to limit),
    )
    limit > maxLimit -> addonError(
        "limit exceeds maximum of $maxLimit (requested: $limit)",
        hint = "Use a limit <= $maxLimit and paginate with offset",
        code = "limit_exceeded",
        context = mapOf("provided_value" to limit, "maximum" to maxLimit),
    )
    offset < 0 -> addonError(
        "offset cannot be negative",
        hint = "Use a value >= 0",
        code = "validation_error",
        context = mapOf("provided_value" to offset),
    )
    else -> null
}

/**
 * `notes_info`. AnkiDroid does not expose field descriptions, so each field has `value` and
 * `order` but no `description` (D57). The batch limit is the add-on's default
 * `max_notes_per_batch` (100); the phone has no config for it.
 */
class NotesInfoTool(repository: AnkiDroidRepository) : AnkiTool(repository) {
    override val name = "notes_info"
    override val title = "Note Details"
    override val description =
        "Get detailed information about specific notes including all fields, tags, model info, and CSS styling. " +
            "Use this after find_notes to get complete note data. Includes CSS for proper rendering awareness. " +
            "Use include_fields to return only specific fields, or exclude_fields to omit fields - " +
            "useful for reducing response size in bulk queries. If both are provided, include_fields takes priority. " +
            "Use excerpt_chars (>= 1) to cap every returned field value at that many characters; each field then also " +
            "carries truncated (bool) and fullLength (original character count). Excerpts exist to IDENTIFY notes " +
            "cheaply, NOT to search their content - content search belongs in find_notes queries, which run server-side " +
            "over full content. A truncated value is not a safe basis for an edit: writing it back stores the cut-off " +
            "value. The full value comes from a call without excerpt_chars."
    override val inputSchema: JsonObject = inputSchema(required = listOf("notes")) {
        property("notes", "array", "Note IDs to fetch (max $MAX_NOTES)", items = "integer")
        property("include_fields", "array", "Return only these fields (takes priority over exclude_fields)", items = "string")
        property("exclude_fields", "array", "Omit these fields from the response", items = "string")
        property("excerpt_chars", "integer", "Truncate each field value to this many characters")
    }
    override val annotations: JsonObject = readOnlyAnnotations(title)

    override suspend fun run(args: ToolArgs): ToolResult {
        val ids = args.longList("notes")
        val includeFields = args.optStringList("include_fields")?.toSet()
        val excludeFields = args.optStringList("exclude_fields")?.toSet()
        val excerptChars = args.optLong("excerpt_chars")
        if (ids.isEmpty()) return addonError("notes parameter cannot be empty", code = "validation_error")
        if (excerptChars != null && excerptChars < 1) {
            return addonError(
                "excerpt_chars must be >= 1 (got $excerptChars)",
                hint = "Omit excerpt_chars to get full field values, or pass a positive character budget such as 120",
                code = "validation_error",
                context = mapOf("provided_value" to excerptChars),
            )
        }
        if (ids.size > MAX_NOTES) {
            return addonError(
                "Too many notes: ${ids.size} (maximum is $MAX_NOTES)",
                hint = "Split your request into batches of $MAX_NOTES or fewer.",
                code = "limit_exceeded",
            )
        }

        // Anki ids are positive; anything else cannot exist and is not valid `nid:` syntax.
        val searchable = ids.filter { it > 0 }.distinct()
        val notes = if (searchable.isEmpty()) {
            emptyMap()
        } else {
            repository.searchNotes(
                idSearch("nid", searchable),
                setOf(NoteField.MODEL_ID, NoteField.FIELDS, NoteField.TAGS, NoteField.MOD),
            ).valueOr { return it }.associateBy { it.id }
        }
        val types = if (notes.isEmpty()) emptyMap() else repository.listNoteTypes().valueOr { return it }.associateBy { it.id }
        // The card ids of all requested notes in one id-only `nid:a,b,c` search (no rendering), then
        // note_id and ord for those cards in calls of at most 100 cards, which do render: an id-only
        // answer cannot say which note a card belongs to. Cards are in template order like the add-on (D57).
        val cardIds = notes.keys.chunked(MAX_NOTES).flatMap { chunk ->
            repository.searchCardIds(idSearch("nid", chunk)).valueOr { return it }
        }
        val cardsByNote = repository.cardsByIds(cardIds, setOf(CardField.NOTE_ID, CardField.ORD)).valueOr { return it }
            .groupBy { it.noteId }
            .mapValues { (_, cards) -> cards.sortedWith(compareBy({ it.ord }, { it.id })).map { it.id } }

        val found = ids.mapNotNull { id -> notes[id]?.let { note -> types[note.modelId]?.let { note to it } } }
        val modelNames = LinkedHashSet<String>()
        val notesJson = buildJsonArray {
            found.forEach { (note, type) ->
                modelNames += type.name
                val values = note.fields.orEmpty()
                val entries = type.fieldNames.zip(values).withIndex()
                    .filter { (_, pair) ->
                        val fieldName = pair.first
                        when {
                            includeFields != null -> fieldName in includeFields
                            excludeFields != null -> fieldName !in excludeFields
                            else -> true
                        }
                    }
                addJsonObject {
                    put("noteId", note.id)
                    put("modelName", type.name)
                    putJsonArray("tags") { note.tags.orEmpty().forEach { add(it) } }
                    putJsonObject("fields") {
                        entries.forEach { (order, pair) ->
                            val (fieldName, value) = pair
                            putJsonObject(fieldName) {
                                val fullLength = pyLen(value)
                                if (excerptChars != null && fullLength > excerptChars) {
                                    put("value", pyPrefix(value, excerptChars.toInt()))
                                } else {
                                    put("value", value)
                                }
                                put("order", order)
                                if (excerptChars != null) {
                                    put("truncated", fullLength > excerptChars)
                                    put("fullLength", fullLength)
                                }
                            }
                        }
                    }
                    putJsonArray("cards") { cardsByNote[note.id].orEmpty().forEach { add(it) } }
                    put("mod", note.mod ?: 0)
                }
            }
        }

        val validCount = found.size
        val notFound = ids.size - validCount
        var hint = if (validCount > 0) {
            "Fields may contain HTML. Use update_note_fields to modify content. " +
                "Do not view notes in Anki browser while updating."
        } else {
            "No valid notes found. They may have been deleted."
        }
        if (validCount > 0 && excerptChars != null) {
            hint += " Values are excerpts capped at $excerptChars character(s); " +
                "any field with truncated=true is incomplete -- re-read it without " +
                "excerpt_chars before editing, and search content via find_notes."
        }
        return ToolResult.structured(
            buildJsonObject {
                put("notes", notesJson)
                put("count", validCount)
                put("notFound", notFound)
                putJsonArray("requestedIds") { ids.forEach { add(it) } }
                put(
                    "message",
                    if (notFound > 0) {
                        "Retrieved $validCount note(s). $notFound note(s) not found (possibly deleted)."
                    } else {
                        "Successfully retrieved information for $validCount note(s)"
                    },
                )
                putJsonArray("models") { modelNames.forEach { add(it) } }
                put(
                    "cssNote",
                    "Each note model has its own CSS styling. Use model_styling tool to get CSS for specific models.",
                )
                put("hint", hint)
            },
        )
    }

    companion object {
        /** The add-on's default `max_notes_per_batch`. */
        const val MAX_NOTES = 100
    }
}
