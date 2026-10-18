package ai.ankimcp.ankidroid.tools

/**
 * Anki's tag rules for the tag tools, ported because AnkiDroid's provider can only replace a
 * note's whole tag string (D57). From rslib: tags are whitespace-separated
 * (`tags::split_tags`); adding compares case-insensitively and leaves a note untouched when it
 * already has every tag (`bulkadd.rs`, `add_missing_tags`); removing matches a tag exactly or as
 * a `tag::` parent, case-insensitively, with no wildcards (`matcher.rs`, `TagMatcher`, which
 * regex-escapes the input). AnkiDroid canonicalises the case of a tag to an existing one when it
 * saves the note, as Anki does.
 */
internal object AnkiTags {
    fun split(tags: String): List<String> = tags.split(Regex("\\s+")).filter { it.isNotEmpty() }

    /** [noteTags] with [toAdd] added, sorted case-insensitively like rslib, or null if nothing changes. */
    fun add(noteTags: List<String>, toAdd: List<String>): List<String>? {
        val result = noteTags.toMutableList()
        var modified = false
        for (tag in toAdd) {
            if (result.none { it.equals(tag, ignoreCase = true) }) {
                result += tag
                modified = true
            }
        }
        return if (modified) result.sortedWith(String.CASE_INSENSITIVE_ORDER) else null
    }

    /** True if [tag] is [pattern] or a child of it (`pattern::...`), ignoring case. */
    fun matches(tag: String, pattern: String): Boolean =
        tag.equals(pattern, ignoreCase = true) || tag.lowercase().startsWith(pattern.lowercase() + "::")

    /** [noteTags] without the tags matching any of [toRemove], or null if nothing changes. */
    fun remove(noteTags: List<String>, toRemove: List<String>): List<String>? {
        val kept = noteTags.filter { tag -> toRemove.none { matches(tag, it) } }
        return if (kept.size == noteTags.size) null else kept
    }

    /** The add-on's `find_replace_conflict`: "same_tag", "child_tag" or null. */
    fun replaceConflict(oldTag: String, newTag: String): String? {
        val olds = split(oldTag).map { it.lowercase() }
        for (new in split(newTag).map { it.lowercase() }) {
            for (old in olds) {
                if (new == old) return "same_tag"
                if (new.startsWith("$old::")) return "child_tag"
            }
        }
        return null
    }

    /** The provider's tag string. */
    fun join(tags: List<String>): String = tags.joinToString(" ")
}
