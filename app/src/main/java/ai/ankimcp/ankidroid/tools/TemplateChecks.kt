package ai.ankimcp.ankidroid.tools

/**
 * The card template checks rslib 25.09 runs when a note type is saved normally
 * (`notetype/mod.rs` `check_templates`, parsing as `template.rs`): identical fronts unless they
 * contain `{{Card}}`, a template that does not parse, a front that references nothing, a field
 * name on either side that is not a field or a special field, and for a cloze type a
 * `{{cloze:...}}` on both sides of the first template. AnkiDroid's provider saves note types with
 * the checks skipped, so create_model and update_model_templates run these before writing
 * anything (D57).
 */
internal object TemplateChecks {
    data class Template(val name: String, val front: String, val back: String)

    /** rslib's `SPECIAL_FIELDS`. */
    private val SPECIAL = setOf("FrontSide", "Card", "CardFlag", "Deck", "Subdeck", "Tags", "Type", "CardID")
    private val CARD_TAG = Regex("""\{\{\s*Card\s*\}\}""")
    private const val ALT_HANDLEBAR_DIRECTIVE = "{{=<% %>=}}"
    private val CLOZE_CONDITIONAL = Regex("""c\d+""")

    /** What one side references: every field name (conditionals included) and the cloze-filtered ones. */
    class References(val all: Set<String>, val cloze: Set<String>)

    /**
     * rslib's tokenizer and `find_field_references`: `<!-- -->` comments are skipped, a handlebar is
     * `{{` up to the next `}}`, `#`/`^` open a conditional whose key counts unless it is `cN`, `/`
     * closes one, and a replacement's field is the text after its last `:` (the rest are filters).
     * Null when a conditional is closed without being open, or left open (rslib's parse error).
     */
    fun references(text: String): References? {
        val all = mutableSetOf<String>()
        val cloze = mutableSetOf<String>()
        val open = ArrayDeque<String>()
        var i = 0
        while (i < text.length) {
            if (text.startsWith("{{", i)) {
                val end = text.indexOf("}}", i + 2)
                if (end >= 0) {
                    val handle = text.substring(i + 2, end).trimStart('{').trim()
                    i = end + 2
                    when {
                        handle.length >= 2 && handle[0] in "#^" -> {
                            val key = handle.substring(1).trimStart()
                            if (!CLOZE_CONDITIONAL.matches(key)) all += key
                            open.addLast(key)
                        }
                        handle.length >= 2 && handle[0] == '/' -> {
                            if (open.removeLastOrNull() != handle.substring(1).trimStart()) return null
                        }
                        else -> {
                            val key = handle.substringAfterLast(':')
                            all += key
                            if (handle.split(':').dropLast(1).any { it == "cloze" }) cloze += key
                        }
                    }
                    continue
                }
            }
            if (text.startsWith("<!--", i)) {
                val end = text.indexOf("-->", i + 4)
                if (end >= 0) {
                    i = end + 3
                    continue
                }
            }
            i++
        }
        return if (open.isEmpty()) References(all, cloze) else null
    }

    /** Raises rslib's first error for [templates] of a note type with [fields]. */
    fun check(fields: List<String>, templates: List<Template>, cloze: Boolean, context: Map<String, Any?>) {
        fun error(message: String, hint: String): Nothing = fail(message, hint = hint, code = "validation_error", context = context)

        val seenFronts = mutableMapOf<String, String>()
        for (t in templates) {
            val other = seenFronts.put(t.front, t.name)
            if (other != null && !CARD_TAG.containsMatchIn(t.front)) {
                error(
                    "Card templates \"$other\" and \"${t.name}\" have the same front, which Anki does not allow",
                    "Make each template's front different (or include {{Card}} in it).",
                )
            }
        }
        // rslib reads a template that starts with the legacy {{=<% %>=}} directive with <% %> delimiters; not checked here.
        fun legacy(side: String) = side.trimStart().startsWith(ALT_HANDLEBAR_DIRECTIVE)
        val parsed = templates.map { t ->
            if (legacy(t.front) || legacy(t.back)) return@map null
            val front = references(t.front)
            val back = references(t.back)
            if (front == null || back == null) {
                error(
                    "Card template \"${t.name}\" has a conditional that is not opened or not closed",
                    "Match every {{#Field}} or {{^Field}} with {{/Field}}.",
                )
            }
            if (front.all.isEmpty()) {
                error(
                    "The front of card template \"${t.name}\" references no field, so it would never create a card",
                    "Put at least one {{Field}} on the front.",
                )
            }
            val unknown = (front.all + back.all).firstOrNull { it.isNotEmpty() && it !in SPECIAL && it !in fields }
            if (unknown != null) {
                error(
                    "Card template \"${t.name}\" refers to field \"$unknown\", but the note type has no field called \"$unknown\"",
                    "Use only these fields: ${fields.joinToString(", ")} (names are case-sensitive).",
                )
            }
            front to back
        }
        val first = parsed.firstOrNull() ?: return
        if (cloze && (first.first.cloze.isEmpty() || first.second.cloze.isEmpty())) {
            error(
                "Cloze template \"${templates.first().name}\" needs {{cloze:Field}} on both its front and its back",
                "A cloze note type needs {{cloze:FieldName}} on both sides of its first template.",
            )
        }
    }
}
