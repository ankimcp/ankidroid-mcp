package ai.ankimcp.ankidroid.tools

import ai.ankimcp.ankidroid.anki.AnkiDroidRepository
import ai.ankimcp.ankidroid.anki.AnkiResult
import ai.ankimcp.ankidroid.anki.NoteField
import ai.ankimcp.ankidroid.mcp.ToolResult
import ai.ankimcp.ankidroid.mcp.writeAnnotations
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Tag writes for the tag tools. AnkiDroid can only replace a note's whole tag string, so each
 * note's tags are read, changed with [AnkiTags] and written back one note at a time (at most 20
 * notes per call), then re-read to confirm. Missing note ids are ignored, as Anki does (D57).
 */
internal class TagEditor(private val repository: AnkiDroidRepository, noteIds: Collection<Long>) {
    private val ids = noteIds.filter { it > 0 }.distinct()
    private lateinit var tags: MutableMap<Long, List<String>>
    private val written = mutableMapOf<Long, List<String>>()

    suspend fun load(): TagEditor {
        tags = if (ids.isEmpty()) {
            mutableMapOf()
        } else {
            repository.searchNotes(idSearch("nid", ids), setOf(NoteField.TAGS)).orFail()
                .associate { it.id to it.tags.orEmpty() }.toMutableMap()
        }
        return this
    }

    /**
     * Applies [change] to each of [noteIds] (existing ones only) and writes the notes it changes;
     * notes needing no change are recorded as unchanged. Returns the notes changed, or the first
     * refusal; stops the call with a progress report when AnkiDroid cannot be written to any
     * more, listing as not done the rest of these notes and [later] (notes of later operations).
     */
    suspend fun apply(
        noteIds: List<Long>,
        progress: WriteProgress,
        later: List<Long> = emptyList(),
        change: (List<String>) -> List<String>?,
    ): Pair<List<Long>, String?> {
        val targets = noteIds.distinct().filter { it in tags }
        val changed = mutableListOf<Long>()
        targets.forEachIndexed { k, id ->
            val next = change(tags.getValue(id))
            if (next == null) {
                progress.unchanged += id.toString()
                return@forEachIndexed
            }
            when (val result = repository.updateNote(id, tags = next)) {
                is AnkiResult.Success -> {
                    tags[id] = next
                    written[id] = next
                    changed += id
                    progress.done += id.toString()
                }
                is AnkiResult.Rejected -> return changed to result.message
                is AnkiResult.Failure -> progress.stop(result, id.toString(), (targets.drop(k + 1) + later).labels())
            }
        }
        return changed to null
    }

    /** Re-reads the written notes; raises if any does not hold the tags written (case-insensitively, as AnkiDroid canonicalises case). */
    suspend fun confirm() {
        if (written.isEmpty()) return
        val now = repository.searchNotes(idSearch("nid", written.keys), setOf(NoteField.TAGS))
            .confirmedOr("The tag change on note(s) ${written.keys.joinToString(", ")}").associate { it.id to it.tags.orEmpty() }
        val wrong = written.filter { (id, expected) -> now[id]?.map { it.lowercase() }?.toSet() != expected.map { it.lowercase() }.toSet() }.keys
        if (wrong.isNotEmpty()) {
            fail(
                "AnkiDroid reported the tags as changed, but re-reading shows different tags on note(s) ${wrong.joinToString(", ")}",
                hint = "Read the notes with notes_info before retrying.",
            )
        }
    }
}

private fun noteIdsOf(args: ToolArgs): List<Long> {
    val ids = args.longList("note_ids")
    if (ids.isEmpty()) fail("note_ids is required and cannot be empty", hint = "Provide at least one note ID")
    if (ids.size > MAX_WRITE_BATCH) tooMany(ids.size)
    return ids
}

private fun tagsOf(args: ToolArgs, name: String = "tags"): String {
    val tags = try {
        args.string(name)
    } catch (e: InvalidArguments) {
        if (args.raw(name) == null) throw e
        throw InvalidArguments("$name must be a space-separated string, e.g. \"tag1 tag2\"")
    }
    if (tags.isBlank()) fail("tags is required and cannot be empty", hint = "Provide space-separated tag names (e.g., 'vocab grammar')")
    return tags
}

/** `tag_add`. */
class TagAddTool(repository: AnkiDroidRepository) : AnkiTool(repository) {
    override val name = "tag_add"
    override val title = "Add Tags"
    override val description =
        "Add tags to notes by note ID (at most $MAX_WRITE_BATCH notes). tags is a space-separated list of tag names " +
            "(e.g. 'vocab grammar'). Returns added_count."
    override val inputSchema: JsonObject = inputSchema(required = listOf("note_ids", "tags")) {
        property("note_ids", "array", "Note IDs to add tags to (max $MAX_WRITE_BATCH)", items = "integer")
        property("tags", "string", "Space-separated tag names to add (e.g., 'vocab grammar')")
    }
    override val annotations: JsonObject = writeAnnotations(title, destructive = false, idempotent = true)

    override suspend fun run(args: ToolArgs): ToolResult {
        val ids = noteIdsOf(args)
        val tags = tagsOf(args)
        val editor = TagEditor(repository, ids).load()
        val (changed, refused) = editor.apply(ids, WriteProgress(ids.size, "notes")) { AnkiTags.add(it, AnkiTags.split(tags)) }
        refused?.let { fail("AnkiDroid refused a tag change after ${changed.size} note(s): $it") }
        editor.confirm()
        return ToolResult.structured(
            buildJsonObject {
                put("added_count", changed.size)
                put("tags", tags)
                put("message", "Added tags '$tags' to ${changed.size} note(s)")
            },
        )
    }
}

/** `tag_remove`: case-insensitive, children (`tag::...`) too, no wildcards, like rslib's matcher. */
class TagRemoveTool(repository: AnkiDroidRepository) : AnkiTool(repository) {
    override val name = "tag_remove"
    override val title = "Remove Tags"
    override val description =
        "Remove tags from notes by note ID (at most $MAX_WRITE_BATCH notes). tags is a space-separated list of tag names; " +
            "removal is case-insensitive and also removes child tags (tag::...). Returns removed_count."
    override val inputSchema: JsonObject = inputSchema(required = listOf("note_ids", "tags")) {
        property("note_ids", "array", "Note IDs to remove tags from (max $MAX_WRITE_BATCH)", items = "integer")
        property("tags", "string", "Space-separated tag names to remove (e.g., 'vocab grammar')")
    }
    override val annotations: JsonObject = writeAnnotations(title, idempotent = true)

    override suspend fun run(args: ToolArgs): ToolResult {
        val ids = noteIdsOf(args)
        val tags = tagsOf(args)
        val editor = TagEditor(repository, ids).load()
        val (changed, refused) = editor.apply(ids, WriteProgress(ids.size, "notes")) { AnkiTags.remove(it, AnkiTags.split(tags)) }
        refused?.let { fail("AnkiDroid refused a tag change after ${changed.size} note(s): $it") }
        editor.confirm()
        return ToolResult.structured(
            buildJsonObject {
                put("removed_count", changed.size)
                put("tags", tags)
                put("message", "Removed tags '$tags' from ${changed.size} note(s)")
            },
        )
    }
}

/** `tag_replace`: add and remove in one write per note, so there is no window with neither tag (D57). */
class TagReplaceTool(repository: AnkiDroidRepository) : AnkiTool(repository) {
    override val name = "tag_replace"
    override val title = "Replace Tag"
    override val description =
        "Replace a tag with another on specific notes (at most $MAX_WRITE_BATCH). " +
            "Adds new_tag then removes old_tag on the given notes. " +
            "The removal matches case-insensitively and also strips old_tag's child tags " +
            "(old_tag::...), so a new_tag equal to old_tag ignoring case, or a child of it, " +
            "is rejected before anything is written. " +
            "Returns added_count and removed_count."
    override val inputSchema: JsonObject = inputSchema(required = listOf("note_ids", "old_tag", "new_tag")) {
        property("note_ids", "array", "Note IDs to replace tags on (max $MAX_WRITE_BATCH)", items = "integer")
        property("old_tag", "string", "Tag to remove")
        property("new_tag", "string", "Tag to add in place of old_tag")
    }
    override val annotations: JsonObject = writeAnnotations(title, idempotent = true)

    override suspend fun run(args: ToolArgs): ToolResult {
        val ids = noteIdsOf(args)
        val oldTag = args.string("old_tag")
        val newTag = args.string("new_tag")
        if (oldTag.isBlank()) fail("old_tag is required and cannot be empty", hint = "Provide the tag to replace")
        if (newTag.isBlank()) fail("new_tag is required and cannot be empty", hint = "Provide the replacement tag")
        when (AnkiTags.replaceConflict(oldTag, newTag)) {
            "same_tag" -> fail(
                "old_tag and new_tag must be different (Anki compares tags case-insensitively)",
                hint = "To change only the capitalisation, replace old_tag with a temporary tag, then replace the temporary tag with new_tag.",
                context = mapOf("old_tag" to oldTag, "new_tag" to newTag),
            )
            "child_tag" -> fail(
                "new_tag is a child of old_tag, and removing old_tag also removes its child tags, so new_tag would be removed too",
                hint = "Replace old_tag with a temporary tag first, then replace the temporary tag with new_tag.",
                context = mapOf("old_tag" to oldTag, "new_tag" to newTag),
            )
        }
        val newTags = AnkiTags.split(newTag)
        val oldTags = AnkiTags.split(oldTag)
        var added = 0
        var removed = 0
        val editor = TagEditor(repository, ids).load()
        val (changed, refused) = editor.apply(ids, WriteProgress(ids.size, "notes")) { current ->
            val withNew = AnkiTags.add(current, newTags)
            val withoutOld = AnkiTags.remove(withNew ?: current, oldTags)
            if (withNew != null) added++
            if (withoutOld != null) removed++
            withoutOld ?: withNew
        }
        refused?.let { fail("AnkiDroid refused a tag change after ${changed.size} note(s): $it") }
        editor.confirm()
        return ToolResult.structured(
            buildJsonObject {
                putJsonArray("note_ids") { ids.forEach { add(it) } }
                put("old_tag", oldTag)
                put("new_tag", newTag)
                put("added_count", added)
                put("removed_count", removed)
                put("message", "Replaced tag '$oldTag' with '$newTag' on $removed note(s)")
            },
        )
    }
}

/** `tag_batch`: up to 50 operations in order, at most 20 note ids across all of them. */
class TagBatchTool(repository: AnkiDroidRepository) : AnkiTool(repository) {
    override val name = "tag_batch"
    override val title = "Batch Tag Operations"
    override val description =
        "Apply multiple add/remove tag operations in a single call. " +
            "Each operation specifies type ('add' or 'remove'), note_ids, and tags. " +
            "Operations execute in order with partial success support. Max $MAX_OPERATIONS operations " +
            "and at most $MAX_WRITE_BATCH note IDs across all operations. " +
            "Returns per-operation results with affected_count, plus succeeded/failed totals."
    override val inputSchema: JsonObject = inputSchema(required = listOf("operations")) {
        property(
            "operations",
            "array",
            "List of tag operations. Each has: type ('add'/'remove'), note_ids (list of ints), " +
                "tags (space-separated string). Executed in order.",
        ) {
            putJsonObject("items") {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("type") {
                        put("type", "string")
                        putJsonArray("enum") {
                            add("add")
                            add("remove")
                        }
                    }
                    putJsonObject("note_ids") {
                        put("type", "array")
                        putJsonObject("items") { put("type", "integer") }
                    }
                    putJsonObject("tags") { put("type", "string") }
                }
                putJsonArray("required") {
                    add("type")
                    add("note_ids")
                    add("tags")
                }
            }
        }
    }
    override val annotations: JsonObject = writeAnnotations(title, idempotent = true)

    private class Op(val type: String, val noteIds: List<Long>, val tags: String)

    override suspend fun run(args: ToolArgs): ToolResult {
        val raw = args.raw("operations") as? JsonArray ?: throw InvalidArguments("Missing required argument: operations")
        if (raw.isEmpty()) {
            fail("operations is required and cannot be empty", hint = "Provide at least one operation with type, note_ids, and tags")
        }
        if (raw.size > MAX_OPERATIONS) {
            fail(
                "Too many operations: ${raw.size} (maximum is $MAX_OPERATIONS)",
                hint = "Split into batches of $MAX_OPERATIONS or fewer.",
                context = mapOf("requested" to raw.size, "maximum" to MAX_OPERATIONS),
            )
        }
        val ops = raw.mapIndexed { i, element ->
            val opArgs = ToolArgs(element as? JsonObject ?: throw InvalidArguments("operations[$i] must be an object"))
            val type = opArgs.string("type")
            if (type != "add" && type != "remove") throw InvalidArguments("operations[$i].type must be 'add' or 'remove'")
            Op(type, opArgs.longList("note_ids"), opArgs.string("tags"))
        }
        val totalIds = ops.sumOf { it.noteIds.size }
        if (totalIds > MAX_WRITE_BATCH) tooMany(totalIds, "note IDs across operations")

        val editor = TagEditor(repository, ops.flatMap { it.noteIds }).load()
        val progress = WriteProgress(totalIds, "note updates")
        val results = ops.mapIndexed { i, op ->
            when {
                op.noteIds.isEmpty() -> opFailed(i, op.type, "note_ids is required and cannot be empty")
                op.tags.isBlank() -> opFailed(i, op.type, "tags is required and cannot be empty")
                else -> {
                    val tags = AnkiTags.split(op.tags)
                    val later = ops.drop(i + 1).flatMap { it.noteIds }
                    val (changed, refused) = editor.apply(op.noteIds, progress, later) { current ->
                        if (op.type == "add") AnkiTags.add(current, tags) else AnkiTags.remove(current, tags)
                    }
                    if (refused != null) {
                        opFailed(i, op.type, "after ${changed.size} note(s): $refused")
                    } else {
                        buildJsonObject {
                            put("index", i)
                            put("type", op.type)
                            put("status", "ok")
                            put("affected_count", changed.size)
                            put("tags", op.tags)
                        }
                    }
                }
            }
        }
        editor.confirm()
        val succeeded = results.count { (it["status"] as kotlinx.serialization.json.JsonPrimitive).content == "ok" }
        val failed = results.size - succeeded
        return ToolResult.structured(
            buildJsonObject {
                put("succeeded", succeeded)
                put("failed", failed)
                put("total_operations", ops.size)
                put("results", JsonArray(results))
                put("message", "Completed $succeeded of ${ops.size} tag operation(s)" + if (failed > 0) " ($failed failed)" else "")
            },
        )
    }

    private fun opFailed(index: Int, type: String, error: String) = buildJsonObject {
        put("index", index)
        put("type", type)
        put("status", "failed")
        put("error", error)
    }

    companion object {
        const val MAX_OPERATIONS = 50
    }
}
