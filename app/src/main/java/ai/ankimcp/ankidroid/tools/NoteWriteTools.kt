package ai.ankimcp.ankidroid.tools

import ai.ankimcp.ankidroid.anki.AnkiDroidRepository
import ai.ankimcp.ankidroid.anki.AnkiResult
import ai.ankimcp.ankidroid.anki.NewNote
import ai.ankimcp.ankidroid.anki.NoteData
import ai.ankimcp.ankidroid.anki.NoteField
import ai.ankimcp.ankidroid.anki.NoteType
import ai.ankimcp.ankidroid.mcp.ToolResult
import ai.ankimcp.ankidroid.mcp.writeAnnotations
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** `{name: string}` from a JSON object argument, or [InvalidArguments]. */
internal fun stringMap(element: JsonElement?, name: String): Map<String, String>? = when (element) {
    null, JsonNull -> null
    is JsonObject -> element.mapValues { (key, value) ->
        (value as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: throw InvalidArguments("$name.$key must be a string")
    }
    else -> throw InvalidArguments("$name must be an object of strings")
}

/** [prefix] + this, or "" when this is empty. */
private fun String.prefixIfAny(prefix: String) = if (isEmpty()) "" else prefix + this

internal fun JsonObjectBuilder.putStrings(key: String, values: List<String>) = putJsonArray(key) { values.forEach { add(it) } }

/**
 * The notes of type [modelId] added at or after [sinceMillis], ascending by id. AnkiDroid's
 * `bulkInsert` returns only a count, but a note id is its creation time in milliseconds on the
 * phone's clock, which is ours too, and rslib gives notes added in one call increasing ids. So
 * the new notes are the recent notes of that type with an id not below the time taken just
 * before the call (`added:2` covers a day rollover in between) (D57).
 */
internal suspend fun AnkiDroidRepository.notesAddedSince(modelId: Long, sinceMillis: Long): AnkiResult<List<NoteData>> =
    when (val found = searchNotes("added:2 mid:$modelId", setOf(NoteField.FIELDS, NoteField.TAGS))) {
        is AnkiResult.Success -> AnkiResult.Success(found.value.filter { it.id >= sinceMillis })
        is AnkiResult.Failure -> found
    }

/**
 * Matches the [requested] new notes to the [found] ones by their field values (NFC, as rslib
 * normalises them), taking found notes in id (insertion) order, so a note someone else added
 * at the same moment is never mistaken for one of ours. Unmatched requests get null.
 */
internal fun matchAdded(requested: List<List<String>>, found: List<NoteData>): List<Long?> {
    val pool = found.sortedBy { it.id }.toMutableList()
    return requested.map { fields ->
        val i = pool.indexOfFirst { nfc(it.fields.orEmpty()) == nfc(fields) }
        if (i < 0) null else pool.removeAt(i).id
    }
}

/** `create_deck`. AnkiDroid refuses an existing name, so the existing deck is looked up first, as the add-on reports it. */
class CreateDeckTool(repository: AnkiDroidRepository) : AnkiTool(repository) {
    override val name = "create_deck"
    override val title = "Create Deck"
    override val description =
        "Create a new empty Anki deck. Supports parent::child structure " +
            "(e.g., \"Japanese::Tokyo\" creates parent deck \"Japanese\" and child deck \"Tokyo\"). " +
            "Maximum 2 levels of nesting allowed. Will not overwrite existing decks. " +
            "Creates the deck only; it adds no notes or cards. " +
            "Returns deckId and created flag (false if deck already existed)."
    override val inputSchema: JsonObject = inputSchema(required = listOf("deck_name")) {
        property("deck_name", "string", "Deck name, optionally \"Parent::Child\" (max 2 levels)")
    }
    override val annotations: JsonObject = writeAnnotations(title, destructive = false, idempotent = true)

    override suspend fun run(args: ToolArgs): ToolResult {
        val deckName = args.string("deck_name")
        val parts = deckName.split("::")
        if (parts.size > 2) {
            fail(
                "Deck name can have maximum 2 levels (parent::child). Provided: ${parts.size} levels",
                hint = "Use format like 'Parent::Child', not 'A::B::C'",
            )
        }
        if (parts.any { it.isBlank() }) fail("Deck name parts cannot be empty")
        val decks = repository.listDecks().orFail()
        val existing = decks.deckNamed(deckName)
        val parentExists = parts.size == 2 && decks.deckNamed(parts[0]) != null
        val deckId = existing?.id ?: repository.createDeck(deckName).orFail()
        val stored = existing?.name
            ?: repository.listDecks().confirmedOr("Creating deck \"$deckName\" (id $deckId)").firstOrNull { it.id == deckId }?.name
            ?: fail("AnkiDroid reported deck $deckId as created, but it is not in the deck list", hint = "Check the decks in AnkiDroid")
        return ToolResult.structured(
            buildJsonObject {
                put("deckId", deckId)
                put("deckName", stored)
                put("created", existing == null)
                if (existing != null) {
                    put("exists", true)
                    put("message", "Deck \"$stored\" already exists")
                } else {
                    val storedParts = stored.split("::")
                    if (storedParts.size == 2) {
                        val (parent, child) = storedParts
                        put("parentDeck", parent)
                        put("childDeck", child)
                        put(
                            "message",
                            if (parentExists) {
                                "Successfully created child deck \"$child\" under existing parent deck \"$parent\""
                            } else {
                                "Successfully created parent deck \"$parent\" and child deck \"$child\""
                            },
                        )
                    } else {
                        put("message", "Successfully created deck \"$stored\"")
                    }
                }
            },
        )
    }
}

/** Shared by add_note and add_notes: the add-on's per-note field checks, null when valid. */
internal fun validateNoteFields(type: NoteType, fields: Map<String, String>): String? {
    val modelFields = type.fieldNames
    val missing = modelFields.filter { it !in fields }
    if (missing.isNotEmpty()) return "Missing required fields: ${missing.sorted().joinToString(", ")}"
    val extra = fields.keys.filter { it !in modelFields }
    if (extra.isNotEmpty()) return "Unknown fields for this model: ${extra.sorted().joinToString(", ")}"
    val sortField = modelFields.first()
    if (fields[sortField].isNullOrBlank()) return "Sort field '$sortField' cannot be empty"
    return null
}

/** The add-on's duplicate check: any note whose first field is the value, via `Field:"value"`. A failing search is ignored, as there. */
internal suspend fun AnkiDroidRepository.isDuplicate(type: NoteType, fields: Map<String, String>): Boolean {
    val first = type.fieldNames.first()
    return when (val found = searchNotes("$first:\"${fields[first].orEmpty()}\"")) {
        is AnkiResult.Success -> found.value.isNotEmpty()
        is AnkiResult.Rejected -> false
        is AnkiResult.Failure -> throw ToolFailure(ToolResult.error(found.message))
    }
}

/**
 * `add_note`. AnkiDroid's single-note insert ignores the requested deck (it uses the note type's
 * default deck), so the note goes in through `bulkInsert` with the deck id, which places every
 * card in that deck, and its id is recovered with [notesAddedSince] (D57).
 */
class AddNoteTool(
    repository: AnkiDroidRepository,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) : AnkiTool(repository) {
    override val name = "add_note"
    override val title = "Add Note"
    override val description =
        "Add a new note to Anki. Use model_names to see available note types and " +
            "model_field_names to see required fields. Returns the note ID on success. " +
            "Intended for notes the user has asked to create."
    override val inputSchema: JsonObject = inputSchema(required = listOf("deck_name", "model_name", "fields")) {
        property("deck_name", "string", "Deck to add the note to")
        property("model_name", "string", "Note type to use")
        property("fields", "object", "Field values by name, e.g. {\"Front\": \"question\", \"Back\": \"answer\"}") {
            putJsonObject("additionalProperties") { put("type", "string") }
        }
        property("tags", "array", "Tags to apply to the note (JSON array)", items = "string")
        property("allow_duplicate", "boolean", "Skip the duplicate check against the sort field", default = JsonPrimitive(false))
    }
    override val annotations: JsonObject = writeAnnotations(title, destructive = false)

    override suspend fun run(args: ToolArgs): ToolResult {
        val deckName = args.string("deck_name")
        val modelName = args.string("model_name")
        val fields = stringMap(args.raw("fields"), "fields") ?: throw InvalidArguments("Missing required argument: fields")
        val tags = args.optStringList("tags").orEmpty()
        val allowDuplicate = args.bool("allow_duplicate", false)
        val context = mapOf("deck_name" to deckName, "model_name" to modelName)

        val deck = repository.listDecks().orFail().deckNamed(deckName)
            ?: fail(
                "Deck not found: $deckName",
                hint = "Use list_decks tool to see available decks or create_deck to create a new one.",
                context = context,
            )
        val type = repository.noteTypeNamed(modelName)
            ?: fail("Model not found: $modelName", hint = "Use model_names tool to see available models.", context = context)
        val sortField = type.fieldNames.first()
        if (fields[sortField].isNullOrBlank()) {
            fail(
                "Sort field '$sortField' cannot be empty",
                hint = "The first field (sort field) must have content. Other fields can be empty.",
                context = context + ("sort_field" to sortField),
            )
        }
        val missing = type.fieldNames.filter { it !in fields }
        if (missing.isNotEmpty()) {
            fail(
                "Missing required fields: ${missing.joinToString(", ")}",
                hint = "Use model_field_names tool to see required fields for this model.",
                context = context + mapOf("provided_fields" to fields.keys.toList(), "required_fields" to type.fieldNames),
            )
        }
        val extra = fields.keys.filter { it !in type.fieldNames }
        if (extra.isNotEmpty()) {
            fail(
                "Unknown fields for this model: ${extra.joinToString(", ")}",
                hint = "Use model_field_names tool to see valid fields for this model.",
                context = context + mapOf("provided_fields" to fields.keys.toList(), "required_fields" to type.fieldNames),
            )
        }
        if (!allowDuplicate && repository.isDuplicate(type, fields)) {
            fail(
                "Failed to create note - it may be a duplicate",
                hint = "The note appears to be a duplicate. Set allow_duplicate to true if you want to add it anyway.",
                context = context,
            )
        }

        val values = type.fieldNames.map { fields.getValue(it) }
        rejectFieldSeparator(values)
        val since = nowMillis()
        val added = repository.addNotesToDeck(deck.id, listOf(NewNote(type.id, values, tags))).orFail()
        if (added != 1) fail("Failed to create note", hint = "Check if the model and deck names are correct.", context = context)
        val found = repository.notesAddedSince(type.id, since).confirmedOr("The note for deck \"$deckName\"")
        val noteId = matchAdded(listOf(values), found).single()
            ?: return ToolResult.error(
                "AnkiDroid added the note to \"$deckName\" but it could not be found again, so its ID is unknown. " +
                    "Use find_notes to locate it before adding it again.",
            )
        return ToolResult.structured(
            buildJsonObject {
                put("note_id", noteId)
                put("deck_name", deckName)
                put("model_name", modelName)
                put("message", "Successfully created note in deck \"$deckName\"")
                putJsonObject("details") {
                    put("fields_added", fields.size)
                    put("tags_added", tags.size)
                }
            },
        )
    }
}

/** `add_notes`: at most 20 notes, one `bulkInsert` for the valid ones, ids recovered as for add_note. */
class AddNotesTool(
    repository: AnkiDroidRepository,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) : AnkiTool(repository) {
    override val name = "add_notes"
    override val title = "Add Notes"
    override val description =
        "Add multiple notes to Anki in a single batch sharing the same deck and model. " +
            "At most $MAX_WRITE_BATCH notes per call. Supports partial success - " +
            "individual failures don't affect others. " +
            "Intended for notes the user has asked to create. " +
            "Returns summary counts (created, skipped, failed) and a per-note results array with status and note_id. " +
            "Each note is {\"fields\": {...}, \"tags\": [...]}; tags is a JSON array."
    override val inputSchema: JsonObject = inputSchema(required = listOf("deck_name", "model_name", "notes")) {
        property("deck_name", "string", "Deck to add all notes to")
        property("model_name", "string", "Note type shared by all notes in this batch")
        property("notes", "array", "Notes to add; each {\"fields\": {...}, \"tags\": [...]} (max $MAX_WRITE_BATCH)") {
            putJsonObject("items") {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("fields") {
                        put("type", "object")
                        putJsonObject("additionalProperties") { put("type", "string") }
                    }
                    putJsonObject("tags") {
                        put("type", "array")
                        putJsonObject("items") { put("type", "string") }
                    }
                }
                putJsonArray("required") { add("fields") }
            }
        }
        property("tags", "array", "Tags for every note (JSON array)", items = "string")
        property("allow_duplicate", "boolean", "Skip the duplicate check against the sort field", default = JsonPrimitive(false))
    }
    override val annotations: JsonObject = writeAnnotations(title, destructive = false)

    private class Entry(val index: Int, val fields: Map<String, String>?, val tags: List<String>, val error: String?)

    override suspend fun run(args: ToolArgs): ToolResult {
        val deckName = args.string("deck_name")
        val modelName = args.string("model_name")
        val sharedTags = args.optStringList("tags").orEmpty()
        val allowDuplicate = args.bool("allow_duplicate", false)
        val notes = args.raw("notes") as? JsonArray ?: throw InvalidArguments("notes must be an array")
        if (notes.isEmpty()) fail("Notes array is empty", hint = "Provide at least one note in the notes array.", code = "validation_error")
        if (notes.size > MAX_WRITE_BATCH) tooMany(notes.size)
        val entries = notes.mapIndexed { i, element ->
            val obj = element as? JsonObject ?: throw InvalidArguments("notes[$i] must be an object")
            val fields = stringMap(obj["fields"], "notes[$i].fields") ?: throw InvalidArguments("notes[$i].fields is required")
            val tags = ToolArgs(obj).optStringList("tags").orEmpty()
            Entry(i, fields, (sharedTags + tags).distinct(), null)
        }
        rejectFieldSeparator(entries.flatMap { it.fields!!.values })
        val context = mapOf("deck_name" to deckName, "model_name" to modelName)
        val deck = repository.listDecks().orFail().deckNamed(deckName)
            ?: fail(
                "Deck not found: $deckName",
                hint = "Use list_decks tool to see available decks or create_deck to create a new one.",
                code = "not_found",
                context = context,
            )
        val type = repository.noteTypeNamed(modelName)
            ?: fail("Model not found: $modelName", hint = "Use model_names tool to see available models.", code = "not_found", context = context)

        val results = sortedMapOf<Int, JsonObject>()
        val valid = mutableListOf<Entry>()
        for (entry in entries) {
            val error = validateNoteFields(type, entry.fields!!)
            when {
                error != null -> results[entry.index] = status(entry.index, "failed") { put("error", error) }
                !allowDuplicate && repository.isDuplicate(type, entry.fields) ->
                    results[entry.index] = status(entry.index, "skipped") { put("reason", "duplicate") }
                else -> valid += entry
            }
        }
        if (valid.isNotEmpty()) {
            val rows = valid.map { entry -> NewNote(type.id, type.fieldNames.map { entry.fields!!.getValue(it) }, entry.tags) }
            val since = nowMillis()
            val added = repository.addNotesToDeck(deck.id, rows)
            if (added is AnkiResult.Failure && added.notSent()) {
                throw ToolFailure(ToolResult.error("Added none of the ${valid.size} valid notes: ${added.message}"))
            }
            // Sent: AnkiDroid adds row by row, so a failure part-way may follow notes it did add. Read back which.
            val found = when (val reread = repository.notesAddedSince(type.id, since)) {
                is AnkiResult.Success -> reread.value
                is AnkiResult.Failure -> if (added is AnkiResult.Success) {
                    null
                } else {
                    throw ToolFailure(
                        ToolResult.error(
                            "The ${valid.size} notes were sent to AnkiDroid, which answered: ${(added as AnkiResult.Failure).message} " +
                                "Which of them were added could not be read back: ${reread.message} " +
                                "Use find_notes on deck \"$deckName\" before retrying.",
                        ),
                    )
                }
            }
            val ids = found?.let { matchAdded(rows.map { it.fields }, it) } ?: List(rows.size) { null }
            valid.zip(ids).forEach { (entry, id) ->
                results[entry.index] = when {
                    id != null -> status(entry.index, "created") { put("note_id", id) }
                    added is AnkiResult.Success -> status(entry.index, "created") {
                        put("note_id", JsonNull)
                        put("note", "Added, but its ID could not be found again; use find_notes to locate it")
                    }
                    // No answer from AnkiDroid: a note not found yet may still have been added.
                    added is AnkiResult.OutcomeUnknown -> status(entry.index, "unknown") {
                        put("note_id", JsonNull)
                        put("note", "Not found after the insert, but AnkiDroid did not answer, so it may still have been added: ${added.message} Use find_notes before adding it again.")
                    }
                    else -> status(entry.index, "failed") { put("error", (added as AnkiResult.Failure).message) }
                }
            }
        }
        val created = results.values.count { it.status == "created" }
        val skipped = results.values.count { it.status == "skipped" }
        val failed = results.values.count { it.status == "failed" }
        val unknown = results.values.count { it.status == "unknown" }
        val detail = listOfNotNull(
            "$skipped skipped".takeIf { skipped > 0 },
            "$failed failed".takeIf { failed > 0 },
            "$unknown unknown".takeIf { unknown > 0 },
        ).joinToString(", ").let { if (it.isEmpty()) "" else " ($it)" }
        return ToolResult.structured(
            buildJsonObject {
                put("created", created)
                put("skipped", skipped)
                put("failed", failed)
                if (unknown > 0) put("unknown", unknown)
                put("total_requested", notes.size)
                put("max_notes_per_batch", MAX_WRITE_BATCH)
                put("deck_name", deckName)
                put("model_name", modelName)
                put("results", JsonArray(results.values.toList()))
                put("message", "Created $created of ${notes.size} notes in deck \"$deckName\"$detail")
            },
        )
    }

    private val JsonObject.status get() = (this["status"] as JsonPrimitive).content

    private fun status(index: Int, status: String, extra: JsonObjectBuilder.() -> Unit) = buildJsonObject {
        put("index", index)
        put("status", status)
        extra()
    }
}

/**
 * `update_note_fields`: full replace or the add-on's exactly-once patch. AnkiDroid takes a note's
 * fields only all at once, so the note is read, changed and written back whole, then re-read to
 * confirm; an edit made in AnkiDroid between the read and the write is overwritten (D57).
 */
class UpdateNoteFieldsTool(repository: AnkiDroidRepository) : AnkiTool(repository) {
    override val name = "update_note_fields"
    override val title = "Update Note Fields"
    override val description =
        "Update the fields of an existing note. Supports HTML content in fields and preserves CSS styling.\n\n" +
            "TWO MUTUALLY EXCLUSIVE INPUT MODES -- use exactly one:\n" +
            "  1. FULL REPLACE: pass 'fields' as {field name: new full value}. Each " +
            "listed field is overwritten entirely; fields you omit are left alone.\n" +
            "  2. PATCH: pass 'field_name' + 'old_str' + 'new_str' to replace a snippet " +
            "in place, like a text editor's find-and-replace. 'old_str' must match that " +
            "field's current value EXACTLY ONCE (whitespace and HTML markup are " +
            "significant); 0 matches or 2+ matches is an error and nothing is written. " +
            "On a 0-match error the response includes an excerpt of the current value so " +
            "you can correct 'old_str' and retry without a separate read. Patch mode " +
            "touches exactly one field. Prefer PATCH for small edits to long fields.\n\n" +
            "Note: media file attachment (audio/picture) is not supported. If the note is open in AnkiDroid's editor while " +
            "it is updated, saving there overwrites this change. Intended for notes the user has asked to modify."
    override val inputSchema: JsonObject = inputSchema(required = listOf("id")) {
        property("id", "integer", "Note ID to update")
        property("fields", "object", "New full value by field name (full-content mode; mutually exclusive with field_name/old_str/new_str)") {
            putJsonObject("additionalProperties") { put("type", "string") }
        }
        property("field_name", "string", "Field to patch (patch mode)")
        property("old_str", "string", "Exact existing field text to replace (patch mode; must match exactly once)")
        property("new_str", "string", "Replacement field text (patch mode; paired with old_str)")
    }
    override val annotations: JsonObject = writeAnnotations(title)

    override suspend fun run(args: ToolArgs): ToolResult {
        val id = args.long("id")
        val fields = stringMap(args.raw("fields"), "fields")
        val fieldName = args.optString("field_name")
        val oldStr = args.optString("old_str")
        val newStr = args.optString("new_str")
        val ctx = mapOf("note_id" to id)
        PatchHelpers.requirePatchPair(oldStr, newStr, ctx)
        val mode = PatchHelpers.selectMode(
            fullPresent = fields != null,
            patchPresent = fieldName != null || oldStr != null,
            fullParams = "'fields'",
            patchParams = "'field_name'/'old_str'/'new_str'",
            context = ctx,
        )
        if (mode == PatchHelpers.Mode.FULL) PatchHelpers.rejectEmptyFullInput(fields!!.isEmpty(), "'fields'", "field name", ctx)

        val note = (if (id > 0) repository.searchNotes("nid:$id", setOf(NoteField.MODEL_ID, NoteField.FIELDS)).orFail().firstOrNull() else null)
            ?: fail(
                "Note not found with ID $id. The note ID is invalid or the note has been deleted.",
                hint = "Use find_notes to get valid note IDs.",
            )
        val type = repository.listNoteTypes().orFail().firstOrNull { it.id == note.modelId }
            ?: fail("Failed to retrieve note $id: its note type is missing")
        val existing = type.fieldNames
        val values = existing.zip(note.fields.orEmpty()).toMap().toMutableMap()
        val updated: List<String> = if (mode == PatchHelpers.Mode.PATCH) {
            if (fieldName == null) {
                fail("Patch mode requires field_name", hint = "Pass field_name (the field to edit), old_str and new_str.", code = "validation_error", context = ctx)
            }
            if (fieldName !in existing) {
                fail(
                    "Field \"$fieldName\" does not exist on model \"${type.name}\". Valid fields are: ${existing.joinToString(", ")}.",
                    hint = "Use model_field_names to see valid fields for this model.",
                    code = "not_found",
                    context = ctx,
                )
            }
            values[fieldName] = PatchHelpers.applyPatch(
                values[fieldName].orEmpty(),
                oldStr.orEmpty(),
                newStr.orEmpty(),
                target = "field \"$fieldName\" of note $id",
                readHint = "notes_info",
                context = ctx + ("field_name" to fieldName),
            )
            listOf(fieldName)
        } else {
            val invalid = fields!!.keys.filter { it !in existing }
            if (invalid.isNotEmpty()) {
                fail(
                    "Invalid fields for model \"${type.name}\": ${invalid.joinToString(", ")}. Valid fields are: ${existing.joinToString(", ")}.",
                    hint = "Use model_field_names to see valid fields for this model.",
                )
            }
            values.putAll(fields)
            fields.keys.toList()
        }
        val newFields = existing.map { values[it].orEmpty() }
        rejectFieldSeparator(newFields)
        repository.updateNote(id, fields = newFields).orFail()
        confirmFields(id, newFields)
        val count = updated.size
        return ToolResult.structured(
            buildJsonObject {
                put("noteId", id)
                putStrings("updatedFields", updated)
                put("fieldCount", count)
                put("modelName", type.name)
                put("message", "Successfully updated $count field${if (count != 1) "s" else ""} in note")
                put("cssNote", "HTML content is preserved. Model CSS styling remains unchanged.")
                put("warning", "If changes don't appear, check that the note was not being edited in AnkiDroid at the same time.")
                put("hint", "Use notes_info to verify the changes or find_notes to locate other notes to update.")
                if (mode == PatchHelpers.Mode.PATCH) put("patched", true)
            },
        )
    }

    private suspend fun confirmFields(id: Long, expected: List<String>) {
        val now = repository.searchNotes("nid:$id", setOf(NoteField.FIELDS)).confirmedOr("The update of note $id").firstOrNull()?.fields
        if (nfc(now) != nfc(expected)) {
            fail(
                "AnkiDroid reported note $id as updated, but re-reading it shows different field values",
                hint = "Read the note with notes_info and check it in AnkiDroid before retrying.",
            )
        }
    }
}

/** `update_notes`: at most 20 entries, one write per note, stopping with a progress report if the call runs out of time. */
class UpdateNotesTool(repository: AnkiDroidRepository) : AnkiTool(repository) {
    override val name = "update_notes"
    override val title = "Update Notes"
    override val description =
        "Update the fields of multiple existing notes in a single batch (at most $MAX_WRITE_BATCH). " +
            "Each entry must include the note ID and the fields to update (partial updates are fine - " +
            "only specified fields are changed). Failures for individual notes do not affect others. " +
            "Use dry_run=true to validate all entries and preview which notes would be updated " +
            "without writing any changes. Intended for notes the user has asked to modify. " +
            "Returns summary counts (updated, failed) and a per-note results array with " +
            "retry hints for recoverable failures."
    override val inputSchema: JsonObject = inputSchema(required = listOf("notes")) {
        property("notes", "array", "Notes to update; each {id, fields} (max $MAX_WRITE_BATCH)") {
            putJsonObject("items") {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("id") { put("type", "integer") }
                    putJsonObject("fields") {
                        put("type", "object")
                        putJsonObject("additionalProperties") { put("type", "string") }
                    }
                }
                putJsonArray("required") {
                    add("id")
                    add("fields")
                }
            }
        }
        property("dry_run", "boolean", "Validate and preview without writing any changes", default = JsonPrimitive(false))
    }
    override val annotations: JsonObject = writeAnnotations(title)

    override suspend fun run(args: ToolArgs): ToolResult {
        val raw = args.raw("notes") as? JsonArray ?: throw InvalidArguments("notes must be an array")
        val dryRun = args.bool("dry_run", false)
        if (raw.isEmpty()) {
            fail("Notes array is empty", hint = "Provide at least one note entry with id and fields.", code = "validation_error")
        }
        if (raw.size > MAX_WRITE_BATCH) tooMany(raw.size)
        val entries = raw.mapIndexed { i, element ->
            val obj = element as? JsonObject ?: throw InvalidArguments("notes[$i] must be an object")
            val entryArgs = ToolArgs(obj)
            entryArgs.long("id") to (stringMap(obj["fields"], "notes[$i].fields") ?: emptyMap())
        }
        val ids = entries.map { it.first }.filter { it > 0 }.distinct()
        val notes = if (ids.isEmpty()) {
            emptyMap()
        } else {
            repository.searchNotes(idSearch("nid", ids), setOf(NoteField.MODEL_ID, NoteField.FIELDS)).orFail().associateBy { it.id }
        }
        val types = repository.listNoteTypes().orFail().associateBy { it.id }
        val current = notes.mapValues { (_, note) -> note.fields.orEmpty().toMutableList() }

        val results = sortedMapOf<Int, JsonObject>()
        val planned = mutableListOf<Triple<Int, Long, List<String>>>()
        entries.forEachIndexed { i, (id, fields) ->
            fun failed(error: String, retryable: Boolean, hint: String) {
                results[i] = buildJsonObject {
                    put("index", i)
                    put("note_id", id)
                    put("status", "failed")
                    put("error", error)
                    put("retryable", retryable)
                    put("retry_hint", hint)
                }
            }
            val note = notes[id]
            val type = note?.let { types[it.modelId] }
            when {
                id <= 0 -> failed("Invalid note ID: $id", false, "Provide a valid positive integer note ID.")
                fields.isEmpty() -> failed(
                    "Empty fields dict - provide at least one field to update",
                    false,
                    "Include a non-empty fields dict with field name/value pairs.",
                )
                note == null || type == null -> failed(
                    "Note not found with ID $id. The note ID is invalid or the note has been deleted.",
                    false,
                    "Use find_notes to locate the correct note ID, or skip this note.",
                )
                fields.keys.any { it !in type.fieldNames } -> {
                    val invalid = fields.keys.filter { it !in type.fieldNames }
                    failed(
                        "Invalid fields for model \"${type.name}\": ${invalid.joinToString(", ")}.",
                        true,
                        "Model \"${type.name}\" valid fields are: ${type.fieldNames.joinToString(", ")}. " +
                            "Replace ${invalid.joinToString(", ")} with valid field names and retry.",
                    )
                }
                else -> {
                    val values = current.getValue(id)
                    fields.forEach { (name, value) -> values[type.fieldNames.indexOf(name)] = value }
                    planned += Triple(i, id, fields.keys.toList())
                }
            }
        }

        if (dryRun) {
            planned.forEach { (i, id, names) -> results[i] = updated(i, id, names, "would_update") }
            val would = results.values.count { it.status == "would_update" }
            val wouldFail = results.values.count { it.status == "failed" }
            return ToolResult.structured(
                buildJsonObject {
                    put("dry_run", true)
                    put("would_update", would)
                    put("would_fail", wouldFail)
                    put("total_requested", raw.size)
                    put("max_notes_per_batch", MAX_WRITE_BATCH)
                    put("results", JsonArray(results.values.toList()))
                    put("message", "Dry run: would update $would of ${raw.size} notes" + if (wouldFail > 0) " ($wouldFail would fail)" else "")
                    put("hint", "Set dry_run=false to perform the actual update.")
                },
            )
        }

        // One write per note (its last state), in the order the notes first appear.
        val writes = planned.map { it.second }.distinct()
        rejectFieldSeparator(writes.flatMap { current.getValue(it) })
        val progress = WriteProgress(writes.size, "notes")
        val written = mutableSetOf<Long>()
        val refused = mutableMapOf<Long, String>()
        writes.forEachIndexed { k, id ->
            when (val result = repository.updateNote(id, fields = current.getValue(id))) {
                is AnkiResult.Success -> {
                    written += id
                    progress.done += id.toString()
                }
                is AnkiResult.Rejected -> refused[id] = result.message
                is AnkiResult.Failure -> progress.stop(result, id.toString(), writes.drop(k + 1).labels())
            }
        }
        val after = if (written.isEmpty()) {
            emptyMap()
        } else {
            repository.searchNotes(idSearch("nid", written), setOf(NoteField.FIELDS))
                .confirmedOr("The update of note(s) ${written.joinToString(", ")}").associate { it.id to it.fields }
        }
        planned.forEach { (i, id, names) ->
            results[i] = when {
                id in refused -> failedEntry(i, id, refused.getValue(id), "AnkiDroid refused the update. Check the note and retry.")
                nfc(after[id]) == nfc(current[id]) -> updated(i, id, names, "updated")
                else -> failedEntry(
                    i,
                    id,
                    "AnkiDroid reported the update, but re-reading the note shows different field values",
                    "Read the note with notes_info before retrying.",
                )
            }
        }
        val updatedCount = results.values.count { it.status == "updated" }
        val failedCount = results.values.count { it.status == "failed" }
        val retryable = results.values.count { it.status == "failed" && (it["retryable"] as? JsonPrimitive)?.content == "true" }
        val detail = if (failedCount > 0) {
            " (" + listOfNotNull("$failedCount failed", "$retryable retryable".takeIf { retryable > 0 }).joinToString(", ") + ")"
        } else {
            ""
        }
        return ToolResult.structured(
            buildJsonObject {
                put("dry_run", false)
                put("updated", updatedCount)
                put("failed", failedCount)
                put("retryable_failed", retryable)
                put("total_requested", raw.size)
                put("max_notes_per_batch", MAX_WRITE_BATCH)
                put("results", JsonArray(results.values.toList()))
                put("message", "Updated $updatedCount of ${raw.size} notes$detail")
                put(
                    "hint",
                    "Batch limit is $MAX_WRITE_BATCH notes per call. Retry retryable failures by calling update_notes again " +
                        "with just those note IDs, fixing field names first. Use notes_info to verify successful updates.",
                )
            },
        )
    }

    private val JsonObject.status get() = (this["status"] as JsonPrimitive).content

    private fun updated(index: Int, id: Long, names: List<String>, status: String) = buildJsonObject {
        put("index", index)
        put("note_id", id)
        put("status", status)
        putStrings("updated_fields", names)
    }

    private fun failedEntry(index: Int, id: Long, error: String, hint: String) = buildJsonObject {
        put("index", index)
        put("note_id", id)
        put("status", "failed")
        put("error", error)
        put("retryable", true)
        put("retry_hint", hint)
    }
}

/** `delete_notes`: at most 20 notes, one provider delete per note, confirmed by searching for them again. */
class DeleteNotesTool(repository: AnkiDroidRepository) : AnkiTool(repository) {
    override val name = "delete_notes"
    override val title = "Delete Notes"
    override val description =
        "Delete notes by their IDs. This will permanently remove the notes and ALL associated cards. " +
            "Requires confirmDeletion=true as a safeguard — the call will fail without it. " +
            "Use dry_run=true to preview what would be deleted without actually deleting anything " +
            "(confirmDeletion is ignored during dry runs). At most $MAX_WRITE_BATCH notes per call. " +
            "Returns deletedCount, cardsDeleted, and notFoundCount."
    override val inputSchema: JsonObject = inputSchema(required = listOf("notes", "confirmDeletion")) {
        property("notes", "array", "Note IDs to delete (max $MAX_WRITE_BATCH)", items = "integer")
        property("confirmDeletion", "boolean", "Required safeguard; must be true to actually delete")
        property("dry_run", "boolean", "Preview the deletion without removing anything", default = JsonPrimitive(false))
    }
    override val annotations: JsonObject = writeAnnotations(title, destructive = true, idempotent = true)

    override suspend fun run(args: ToolArgs): ToolResult {
        val ids = args.longList("notes")
        val confirm = args.bool("confirmDeletion", false)
        val dryRun = args.bool("dry_run", false)
        if (!dryRun && !confirm) {
            fail(
                "Deletion not confirmed. Set confirmDeletion to true to permanently " +
                    "delete these notes and all their cards. This action cannot be undone!",
                hint = "Set confirmDeletion to true, or use dry_run=true to preview the deletion first",
                code = "validation_error",
            )
        }
        if (ids.size > MAX_WRITE_BATCH) {
            fail(
                "Cannot delete more than $MAX_WRITE_BATCH notes at once. Requested: ${ids.size} notes",
                hint = "Delete notes in smaller batches of $MAX_WRITE_BATCH or fewer.",
                code = "limit_exceeded",
            )
        }
        if (ids.isEmpty()) fail("No note IDs provided", code = "validation_error")

        val searchable = ids.filter { it > 0 }.distinct()
        val existing = if (searchable.isEmpty()) {
            emptySet()
        } else {
            repository.searchNotes(idSearch("nid", searchable)).orFail().map { it.id }.toSet()
        }
        val valid = ids.filter { it in existing }.distinct()
        val notFound = ids.size - valid.size
        if (valid.isEmpty()) {
            return ToolResult.structured(
                buildJsonObject {
                    put("dry_run", dryRun)
                    put("deletedCount", 0)
                    putJsonArray("deletedNoteIds") {}
                    put("cardsDeleted", 0)
                    put("notFoundCount", ids.size)
                    putJsonArray("requestedIds") { ids.forEach { add(it) } }
                    put("message", "No notes were deleted (none of the provided IDs were valid)")
                    put("hint", "The notes may have already been deleted or the IDs are invalid")
                },
            )
        }
        val cards = repository.searchCardIds(idSearch("nid", valid)).orFail().size
        val notFoundText = if (notFound > 0) " $notFound note(s) were not found." else ""
        if (dryRun) {
            return ToolResult.structured(
                buildJsonObject {
                    put("dry_run", true)
                    put("deletedCount", valid.size)
                    putJsonArray("deletedNoteIds") { valid.forEach { add(it) } }
                    put("cardsDeleted", cards)
                    put("notFoundCount", notFound)
                    putJsonArray("requestedIds") { ids.forEach { add(it) } }
                    put("message", "Dry run: would delete ${valid.size} note(s) and $cards card(s)" + notFoundText.prefixIfAny("."))
                    put("hint", "Set dry_run=false and confirmDeletion=true to perform the actual deletion")
                },
            )
        }

        val progress = WriteProgress(valid.size, "notes")
        valid.forEachIndexed { k, id ->
            when (val result = repository.deleteNote(id)) {
                is AnkiResult.Success -> progress.done += id.toString()
                is AnkiResult.Failure -> progress.stop(result, id.toString(), valid.drop(k + 1).labels())
            }
        }
        val remaining = repository.searchNotes(idSearch("nid", valid))
            .confirmedOr("The deletion of note(s) ${valid.joinToString(", ")}").map { it.id }.toSet()
        if (remaining.isNotEmpty()) {
            val deleted = valid.filter { it !in remaining }
            return ToolResult.error(
                "AnkiDroid reported ${valid.size} note(s) deleted, but ${remaining.size} still exist: ${remaining.joinToString(", ")}. " +
                    "Deleted: ${if (deleted.isEmpty()) "none" else deleted.joinToString(", ")}.",
            )
        }
        return ToolResult.structured(
            buildJsonObject {
                put("dry_run", false)
                put("deletedCount", valid.size)
                putJsonArray("deletedNoteIds") { valid.forEach { add(it) } }
                put("cardsDeleted", cards)
                put("notFoundCount", notFound)
                putJsonArray("requestedIds") { ids.forEach { add(it) } }
                put("message", "Successfully deleted ${valid.size} note(s) and $cards card(s)" + notFoundText.prefixIfAny("."))
                put("warning", "These notes and cards have been permanently deleted")
                put("hint", "Consider syncing with AnkiWeb to propagate deletions to other devices")
            },
        )
    }
}
