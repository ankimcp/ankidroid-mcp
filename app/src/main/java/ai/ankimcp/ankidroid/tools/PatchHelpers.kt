package ai.ankimcp.ankidroid.tools

/**
 * The add-on's `_patch_helpers.py`: the `old_str`/`new_str` patch mode of update_note_fields,
 * update_model_styling and update_model_templates. `old_str` must occur exactly once
 * (non-overlapping, like Python's `str.count`), nothing is written otherwise, and a zero-match
 * error carries an excerpt of the current content so the caller can re-sync. Errors raise
 * [ToolFailure] with the add-on's wording. Lengths and excerpts count code points like Python.
 * The add-on's JSON-literal transport caveat is about FastMCP's argument pre-parsing and does not
 * apply here, so it is not repeated in the tool descriptions.
 */
internal object PatchHelpers {
    private const val EXCERPT_LEN = 200
    private const val EXCERPT_LEAD = 60
    private val ANCHOR_PROBE_SIZES = listOf(60, 30, 15)

    /** Non-overlapping occurrences, scanning left to right. */
    fun count(content: String, needle: String): Int {
        var n = 0
        var from = 0
        while (true) {
            val i = content.indexOf(needle, from)
            if (i < 0) return n
            n++
            from = i + needle.length
        }
    }

    /** A code-point index in [content] near where [oldStr] almost matched, or -1. */
    fun findAnchor(content: String, oldStr: String): Int {
        val oldLen = pyLen(oldStr)
        val probes = ANCHOR_PROBE_SIZES.filter { it <= oldLen }.ifEmpty { listOf(oldLen) }
        for (size in probes) {
            val idx = content.indexOf(pyPrefix(oldStr, size))
            if (idx != -1) return content.codePointCount(0, idx)
        }
        return -1
    }

    fun buildExcerpt(content: String, oldStr: String): String {
        if (content.isEmpty()) return ""
        val anchor = findAnchor(content, oldStr)
        val length = pyLen(content)
        val start = if (anchor < 0) 0 else maxOf(0, anchor - EXCERPT_LEAD)
        val end = minOf(length, start + EXCERPT_LEN)
        var excerpt = content.substring(content.offsetByCodePoints(0, start), content.offsetByCodePoints(0, end))
        if (start > 0) excerpt = "...$excerpt"
        if (end < length) excerpt = "$excerpt..."
        return excerpt
    }

    /** [content] with the single occurrence of [oldStr] replaced by [newStr]; raises otherwise. */
    fun applyPatch(
        content: String,
        oldStr: String,
        newStr: String,
        target: String,
        readHint: String,
        context: Map<String, Any?> = emptyMap(),
    ): String {
        if (oldStr.isEmpty()) {
            fail(
                "old_str must not be empty",
                hint = "Provide the exact text to replace. To set the full content " +
                    "instead, use this tool's full-content parameter.",
                code = "validation_error",
                context = context,
            )
        }
        val matches = count(content, oldStr)
        if (matches == 0) {
            fail(
                "old_str not found in $target (0 matches) -- nothing was written",
                hint = "old_str must match the current content EXACTLY (whitespace, " +
                    "newlines and HTML markup are all significant). Re-sync using " +
                    "the excerpt below, or read the full current content with " +
                    "$readHint, then retry.",
                code = "patch_no_match",
                context = linkedMapOf<String, Any?>(
                    "content_length" to pyLen(content),
                    "current_content_excerpt" to buildExcerpt(content, oldStr),
                ) + context,
            )
        }
        if (matches > 1) {
            fail(
                "old_str matches $matches times in $target -- ambiguous, nothing was written",
                hint = "Extend old_str with surrounding context until it identifies exactly one location, then retry.",
                code = "patch_multiple_matches",
                context = linkedMapOf<String, Any?>("match_count" to matches, "content_length" to pyLen(content)) + context,
            )
        }
        val at = content.indexOf(oldStr)
        return content.substring(0, at) + newStr + content.substring(at + oldStr.length)
    }

    /** Rejects a half-specified patch; `new_str` may be empty (a deletion). */
    fun requirePatchPair(oldStr: String?, newStr: String?, context: Map<String, Any?> = emptyMap()) {
        if ((oldStr == null) != (newStr == null)) {
            val missing = if (newStr == null) "new_str" else "old_str"
            fail(
                "Patch mode requires both old_str and new_str -- $missing is missing",
                hint = "Pass old_str (text to find) and new_str (replacement). " +
                    "new_str may be an empty string to delete the matched text.",
                code = "validation_error",
                context = context,
            )
        }
    }

    /** Full mode selected by presence, so an empty mapping is rejected here. */
    fun rejectEmptyFullInput(empty: Boolean, fullParams: String, what: String, context: Map<String, Any?> = emptyMap()) {
        if (empty) {
            fail(
                "$fullParams was provided but is empty -- nothing to update",
                hint = "Include at least one $what in $fullParams, or use this tool's patch parameters instead.",
                code = "validation_error",
                context = context,
            )
        }
    }

    enum class Mode { FULL, PATCH, NONE }

    fun selectMode(
        fullPresent: Boolean,
        patchPresent: Boolean,
        fullParams: String,
        patchParams: String,
        allowNeither: Boolean = false,
        context: Map<String, Any?> = emptyMap(),
    ): Mode {
        if (fullPresent && patchPresent) {
            fail(
                "Cannot combine $fullParams with $patchParams -- these are mutually exclusive input modes",
                hint = "Use $fullParams to replace the content wholesale, OR $patchParams to patch it in place. Not both.",
                code = "validation_error",
                context = context,
            )
        }
        return when {
            fullPresent -> Mode.FULL
            patchPresent -> Mode.PATCH
            allowNeither -> Mode.NONE
            else -> fail(
                "No input provided -- supply either $fullParams or $patchParams",
                hint = "Use $fullParams to replace the content wholesale, OR $patchParams to patch it in place.",
                code = "validation_error",
                context = context,
            )
        }
    }
}
