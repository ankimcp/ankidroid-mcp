package ai.ankimcp.ankidroid.tools

import ai.ankimcp.ankidroid.anki.AnkiDroidRepository
import ai.ankimcp.ankidroid.anki.NoteType
import ai.ankimcp.ankidroid.mcp.ToolResult
import ai.ankimcp.ankidroid.mcp.readOnlyAnnotations
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** The note type called [name] (case-insensitive, like `col.models.by_name`), or the add-on's not-found error. */
internal suspend inline fun AnkiDroidRepository.noteTypeOr(name: String, fail: (ToolResult) -> Nothing): NoteType {
    val types = listNoteTypes().valueOr(fail)
    return types.byName(name) { it.name }
        ?: fail(
            addonError(
                "Model \"$name\" not found",
                hint = "Use model_names tool to see available models",
                context = mapOf("model_name" to name),
            ),
        )
}

/** `model_names`. */
class ModelNamesTool(repository: AnkiDroidRepository) : AnkiTool(repository) {
    override val name = "model_names"
    override val title = "Note Type Names"
    override val description =
        "Get a list of all available note type (model) names in Anki. Use this to see what note types are " +
            "available before creating notes. Returns modelNames list and commonTypes shortcuts (basic, basicReversed, cloze)."
    override val inputSchema: JsonObject = inputSchema {}
    override val annotations: JsonObject = readOnlyAnnotations(title)

    override suspend fun run(args: ToolArgs): ToolResult {
        val names = repository.listNoteTypes().valueOr { return it }.map { it.name }.sortedBy { it.lowercase() }
        fun common(name: String) = if (name in names) JsonPrimitive(name) else JsonNull
        return ToolResult.structured(
            buildJsonObject {
                if (names.isEmpty()) put("message", "No note types found in Anki")
                putJsonArray("modelNames") { names.forEach { add(it) } }
                put("total", names.size)
                if (names.isNotEmpty()) put("message", "Found ${names.size} note types")
                putJsonObject("commonTypes") {
                    put("basic", common("Basic"))
                    put("basicReversed", common("Basic (and reversed card)"))
                    put("cloze", common("Cloze"))
                }
            },
        )
    }
}

/** `model_field_names`. AnkiDroid does not expose field descriptions, so `fields[]` has `name` only (D57). */
class ModelFieldNamesTool(repository: AnkiDroidRepository) : AnkiTool(repository) {
    override val name = "model_field_names"
    override val title = "Note Type Field Names"
    override val description =
        "Get the field names for a specific note type (model). " +
            "Use this to know what fields are required when creating notes of this type. " +
            "Returns both field_names (list of strings) and fields (list of objects with name; AnkiDroid does not " +
            "expose field descriptions)."
    override val inputSchema: JsonObject = inputSchema(required = listOf("model_name")) {
        property("model_name", "string", "Note type to inspect")
    }
    override val annotations: JsonObject = readOnlyAnnotations(title)

    override suspend fun run(args: ToolArgs): ToolResult {
        val modelName = args.string("model_name")
        val model = repository.noteTypeOr(modelName) { return it }
        val fieldNames = model.fieldNames
        if (fieldNames.isEmpty()) {
            return ToolResult.structured(
                buildJsonObject {
                    put("model_name", modelName)
                    putJsonArray("field_names") {}
                    putJsonArray("fields") {}
                    put("total", 0)
                    put("message", "Model \"$modelName\" has no fields")
                },
            )
        }
        val lower = modelName.lowercase()
        val example: Map<String, String>? = when {
            "basic" in lower && "reversed" !in lower -> mapOf("Front" to "Question or prompt text", "Back" to "Answer or response text")
            "basic" in lower && "reversed" in lower -> mapOf("Front" to "First side of the card", "Back" to "Second side of the card")
            "cloze" in lower -> mapOf(
                "Text" to "The {{c1::hidden}} text will be replaced with [...] on the card",
                "Extra" to "Additional information or hints",
            )
            else -> null
        }
        return ToolResult.structured(
            buildJsonObject {
                put("model_name", modelName)
                putJsonArray("field_names") { fieldNames.forEach { add(it) } }
                putJsonArray("fields") { fieldNames.forEach { addJsonObject { put("name", it) } } }
                put("total", fieldNames.size)
                put("message", "Model \"$modelName\" has ${fieldNames.size} field${if (fieldNames.size != 1) "s" else ""}")
                if (example != null) {
                    putJsonObject("example") { example.forEach { (k, v) -> put(k, v) } }
                    put("hint", "Use these field names as keys when creating notes with add_note tool")
                }
            },
        )
    }
}

/** `model_templates`. */
class ModelTemplatesTool(repository: AnkiDroidRepository) : AnkiTool(repository) {
    override val name = "model_templates"
    override val title = "Note Type Templates"
    override val description =
        "Get the card template HTML (Front and Back) for each card type in a note type (model). " +
            "Returns the raw HTML templates used to render cards during review and editing."
    override val inputSchema: JsonObject = inputSchema(required = listOf("model_name")) {
        property("model_name", "string", "Note type to inspect")
    }
    override val annotations: JsonObject = readOnlyAnnotations(title)

    override suspend fun run(args: ToolArgs): ToolResult {
        val modelName = args.string("model_name")
        val model = repository.noteTypeOr(modelName) { return it }
        val templates = repository.listTemplates(model.id).valueOr { return it }
        if (templates.isEmpty()) {
            return addonError(
                "Model \"$modelName\" has no card templates",
                hint = "This model exists but has no card templates defined",
                context = mapOf("model_name" to modelName),
            )
        }
        val byName = LinkedHashMap<String, Pair<String, String>>()
        templates.forEach { byName[it.name] = it.front to it.back }
        return ToolResult.structured(
            buildJsonObject {
                put("model_name", modelName)
                putJsonObject("templates") {
                    byName.forEach { (name, sides) ->
                        putJsonObject(name) {
                            put("Front", sides.first)
                            put("Back", sides.second)
                        }
                    }
                }
                put("template_count", byName.size)
                put("message", "Retrieved ${byName.size} card template(s) for model \"$modelName\"")
                put(
                    "hint",
                    "Front and Back fields contain the raw HTML/CSS templates used to render cards. " +
                        "Template placeholders like {{FieldName}} are substituted with field values at render time.",
                )
            },
        )
    }
}

/** `model_styling`. AnkiDroid does not expose `latexsvg`, so `latex_svg` is never returned (D57). */
class ModelStylingTool(repository: AnkiDroidRepository) : AnkiTool(repository) {
    override val name = "model_styling"
    override val title = "Note Type Styling"
    override val description =
        "Get the CSS styling for a specific note type (model). This CSS is used when rendering cards of this type. " +
            "Set include_latex=true to additionally return the model's LaTeX preamble " +
            "('latex_pre', 'latex_post') -- the header/footer wrapped around " +
            "[latex] and [$]...[/$] blocks when rendering them. Only ask for it when " +
            "diagnosing or fixing LaTeX/TikZ rendering; it is omitted by default."
    override val inputSchema: JsonObject = inputSchema(required = listOf("model_name")) {
        property("model_name", "string", "Note type to inspect")
        property("include_latex", "boolean", "Also return latex_pre/latex_post", default = JsonPrimitive(false))
    }
    override val annotations: JsonObject = readOnlyAnnotations(title)

    override suspend fun run(args: ToolArgs): ToolResult {
        val modelName = args.string("model_name")
        val includeLatex = args.bool("include_latex", false)
        val model = repository.noteTypeOr(modelName) { return it }
        val css = model.css
        if (css.isEmpty() && !includeLatex) {
            return addonError(
                "Model \"$modelName\" has no styling",
                hint = "This model exists but has no CSS styling defined",
                context = mapOf("model_name" to modelName),
            )
        }
        return ToolResult.structured(
            buildJsonObject {
                put("model_name", modelName)
                put("css", css)
                putJsonObject("css_info") {
                    put("length", pyLen(css))
                    put("has_card_styling", ".card" in css)
                    put("has_front_styling", ".front" in css)
                    put("has_back_styling", ".back" in css)
                    put("has_cloze_styling", ".cloze" in css)
                }
                put(
                    "message",
                    if (css.isNotEmpty()) {
                        "Retrieved CSS styling for model \"$modelName\""
                    } else {
                        "Model \"$modelName\" has no CSS styling; returning its LaTeX preamble only"
                    },
                )
                put("hint", "This CSS is automatically applied when cards of this type are rendered in Anki")
                if (includeLatex) {
                    put("latex_pre", model.latexPre)
                    put("latex_post", model.latexPost)
                }
            },
        )
    }
}
