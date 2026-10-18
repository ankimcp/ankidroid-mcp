package ai.ankimcp.ankidroid.tools

import ai.ankimcp.ankidroid.anki.AnkiDroidRepository
import ai.ankimcp.ankidroid.anki.AnkiResult
import ai.ankimcp.ankidroid.anki.NoteType
import ai.ankimcp.ankidroid.mcp.ToolResult
import ai.ankimcp.ankidroid.mcp.writeAnnotations
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/** The add-on's model-not-found error, as raised by `get_model_copy_or_raise`. */
internal suspend fun AnkiDroidRepository.noteTypeOrFail(name: String): NoteType =
    noteTypeNamed(name) ?: fail(
        "Model \"$name\" not found",
        hint = "Use model_names tool to see available models",
        context = mapOf("model_name" to name),
    )

/** The add-on's FULL_SYNC_WARNING. */
internal const val FULL_SYNC_WARNING =
    "This is a schema change. Anki will require a one-way full sync on the next " +
        "sync, which overwrites the collection on AnkiWeb (and other devices) with " +
        "this one. Sync your other devices first if they hold unsynced changes."

/**
 * `update_model_styling`: CSS (full or patch) and the LaTeX header/footer. AnkiDroid exposes no
 * `latexsvg`, so there is no `latex_svg` argument; `will_force_full_sync` is not returned (D57).
 */
class UpdateModelStylingTool(repository: AnkiDroidRepository) : AnkiTool(repository) {
    override val name = "update_model_styling"
    override val title = "Update Note Type Styling"
    override val description =
        "Update the CSS styling (and optionally the LaTeX preamble) of an existing " +
            "note type (model). Changes apply to all cards using this model.\n\n" +
            "TWO MUTUALLY EXCLUSIVE CSS INPUT MODES -- use exactly one, or neither when " +
            "only writing LaTeX fields:\n" +
            "  1. FULL REPLACE: pass 'css' with the complete new stylesheet.\n" +
            "  2. PATCH: pass 'old_str' + 'new_str' to replace a snippet in place, like " +
            "a text editor's find-and-replace. 'old_str' must match the current CSS " +
            "EXACTLY ONCE (whitespace is significant); 0 matches or 2+ matches is an " +
            "error and nothing is written. On a 0-match error the response includes an " +
            "excerpt of the current CSS so you can correct 'old_str' and retry without " +
            "a separate read.\n\n" +
            "OPTIONAL LATEX PREAMBLE: 'latex_pre' and 'latex_post' set the LaTeX header/footer " +
            "used when rendering [latex] and [$]...[/$] blocks. Only the ones you pass are written, and they combine " +
            "with either CSS mode or can be sent alone. Read the current values with model_styling(include_latex=true)."
    override val inputSchema: JsonObject = inputSchema(required = listOf("model_name")) {
        property("model_name", "string", "Note type to update")
        property("css", "string", "New full CSS content (full-content mode; mutually exclusive with old_str/new_str)")
        property("old_str", "string", "Exact existing CSS text to replace (patch mode; must match exactly once)")
        property("new_str", "string", "Replacement CSS text (patch mode; paired with old_str)")
        property("latex_pre", "string", "New LaTeX preamble header (independent of CSS mode)")
        property("latex_post", "string", "New LaTeX preamble footer (independent of CSS mode)")
    }
    override val annotations: JsonObject = writeAnnotations(title)

    override suspend fun run(args: ToolArgs): ToolResult {
        val modelName = args.string("model_name")
        val css = args.optString("css")
        val oldStr = args.optString("old_str")
        val newStr = args.optString("new_str")
        val latexPre = args.optString("latex_pre")
        val latexPost = args.optString("latex_post")
        val ctx = mapOf("model_name" to modelName)
        PatchHelpers.requirePatchPair(oldStr, newStr, ctx)
        val mode = PatchHelpers.selectMode(
            fullPresent = css != null,
            patchPresent = oldStr != null,
            fullParams = "'css'",
            patchParams = "'old_str'/'new_str'",
            allowNeither = latexPre != null || latexPost != null,
            context = ctx,
        )
        val model = repository.noteTypeOrFail(modelName)
        val oldCss = model.css
        val newCss = when (mode) {
            PatchHelpers.Mode.FULL -> css!!
            PatchHelpers.Mode.PATCH -> PatchHelpers.applyPatch(
                oldCss,
                oldStr.orEmpty(),
                newStr.orEmpty(),
                target = "CSS of model \"$modelName\"",
                readHint = "model_styling",
                context = ctx,
            )
            PatchHelpers.Mode.NONE -> oldCss
        }
        repository.updateNoteType(
            model.id,
            css = newCss.takeIf { mode != PatchHelpers.Mode.NONE },
            latexPre = latexPre,
            latexPost = latexPost,
        ).orFail()
        val after = repository.listNoteTypes().confirmedOr("The styling change of model \"$modelName\"").firstOrNull { it.id == model.id }
        if (after == null || after.css != newCss || (latexPre != null && after.latexPre != latexPre) ||
            (latexPost != null && after.latexPost != latexPost)
        ) {
            fail(
                "AnkiDroid reported the note type as updated, but re-reading it shows different styling",
                hint = "Read it with model_styling(include_latex=true) before retrying.",
                context = ctx,
            )
        }
        val updatedLatex = listOfNotNull("latex_pre".takeIf { latexPre != null }, "latex_post".takeIf { latexPost != null })
        return ToolResult.structured(
            buildJsonObject {
                put("model_name", modelName)
                put("css_length", pyLen(newCss))
                putJsonObject("css_info") {
                    put("has_rtl_support", "direction: rtl" in newCss || "direction:rtl" in newCss)
                    put("has_card_styling", ".card" in newCss)
                    put("has_front_styling", ".front" in newCss)
                    put("has_back_styling", ".back" in newCss)
                    put("has_cloze_styling", ".cloze" in newCss)
                }
                put(
                    "message",
                    when (mode) {
                        PatchHelpers.Mode.NONE -> "Successfully updated LaTeX preamble for model \"$modelName\" (CSS unchanged)"
                        PatchHelpers.Mode.PATCH -> "Successfully patched CSS styling for model \"$modelName\""
                        PatchHelpers.Mode.FULL -> "Successfully updated CSS styling for model \"$modelName\""
                    },
                )
                if (oldCss.isNotEmpty()) {
                    put("old_css_length", pyLen(oldCss))
                    put("css_length_change", pyLen(newCss) - pyLen(oldCss))
                }
                if (mode == PatchHelpers.Mode.PATCH) put("patched", true)
                if (updatedLatex.isNotEmpty()) putStrings("updated_latex_fields", updatedLatex)
            },
        )
    }
}

private val SIDE_KEYS = listOf("Back", "Front")

/** `update_model_templates`: full replace of sides of one or more templates, or the exactly-once patch of one side. One write per template. */
class UpdateModelTemplatesTool(repository: AnkiDroidRepository) : AnkiTool(repository) {
    override val name = "update_model_templates"
    override val title = "Update Note Type Templates"
    override val description =
        "Update the card template HTML (Front/Back) of a note type (model). " +
            "Changes apply to all future renders; existing cards keep their generated " +
            "content but will use the new template on next render.\n\n" +
            "TWO MUTUALLY EXCLUSIVE INPUT MODES -- use exactly one:\n" +
            "  1. FULL REPLACE: pass 'templates' as {card template name: {'Front': html, " +
            "'Back': html}}. Front/Back are optional per template but replace the whole " +
            "side. Multiple templates can be updated in one call.\n" +
            "  2. PATCH: pass 'template_name' + 'side' ('Front' or 'Back') + 'old_str' + " +
            "'new_str' to replace a snippet in place, like a text editor's " +
            "find-and-replace. 'old_str' must match that side's current HTML EXACTLY " +
            "ONCE (whitespace is significant); 0 matches or 2+ matches is an error and " +
            "nothing is written. On a 0-match error the response includes an excerpt of " +
            "the current HTML so you can correct 'old_str' and retry without a separate " +
            "read. Patch mode touches exactly one side of one template.\n\n" +
            "Use model_templates to read current template names and HTML."
    override val inputSchema: JsonObject = inputSchema(required = listOf("model_name")) {
        property("model_name", "string", "Note type to update")
        property(
            "templates",
            "object",
            "New Front/Back HTML by card template name (full-content mode; mutually exclusive with template_name/side/old_str/new_str)",
        ) {
            putJsonObject("additionalProperties") {
                put("type", "object")
                putJsonObject("additionalProperties") { put("type", "string") }
            }
        }
        property("template_name", "string", "Card template to patch (patch mode)")
        property("side", "string", "Which side to patch, 'Front' or 'Back' (patch mode)")
        property("old_str", "string", "Exact existing HTML text to replace (patch mode; must match exactly once)")
        property("new_str", "string", "Replacement HTML text (patch mode; paired with old_str)")
    }
    override val annotations: JsonObject = writeAnnotations(title)

    override suspend fun run(args: ToolArgs): ToolResult {
        val modelName = args.string("model_name")
        val templates = (args.raw("templates") as? JsonObject)?.mapValues { (name, value) ->
            stringMap(value, "templates.$name") ?: throw InvalidArguments("templates.$name must be an object")
        } ?: args.raw("templates")?.let { throw InvalidArguments("templates must be an object") }
        val templateName = args.optString("template_name")
        val side = args.optString("side")
        val oldStr = args.optString("old_str")
        val newStr = args.optString("new_str")
        val ctx = mapOf("model_name" to modelName)
        PatchHelpers.requirePatchPair(oldStr, newStr, ctx)
        val mode = PatchHelpers.selectMode(
            fullPresent = templates != null,
            patchPresent = templateName != null || side != null || oldStr != null,
            fullParams = "'templates'",
            patchParams = "'template_name'/'side'/'old_str'/'new_str'",
            context = ctx,
        )
        if (mode == PatchHelpers.Mode.FULL) {
            PatchHelpers.rejectEmptyFullInput(templates!!.isEmpty(), "'templates'", "card template name", ctx)
        }
        val model = repository.noteTypeOrFail(modelName)
        val existing = repository.listTemplates(model.id).orFail().associateBy { it.name }
        if (existing.isEmpty()) {
            fail(
                "Model \"$modelName\" has no card templates to update",
                hint = "This model exists but has no card templates defined",
                context = ctx,
            )
        }
        val available = existing.keys.sorted().joinToString(", ")
        // name -> (front, back) after the change
        val changes = LinkedHashMap<String, Pair<String, String>>()
        var patchInfo: Triple<String, Int, Int>? = null
        if (mode == PatchHelpers.Mode.PATCH) {
            if (templateName == null || side == null) {
                val missing = listOfNotNull("template_name".takeIf { templateName == null }, "side".takeIf { side == null })
                fail(
                    "Patch mode requires ${missing.joinToString(" and ")}",
                    hint = "Pass template_name (the card template to edit), side ('Front' or 'Back'), old_str and new_str.",
                    code = "validation_error",
                    context = ctx,
                )
            }
            if (side !in SIDE_KEYS) {
                fail(
                    "Invalid side \"$side\"",
                    hint = "side must be one of: ${SIDE_KEYS.joinToString(", ")} (case-sensitive).",
                    code = "validation_error",
                    context = ctx + ("valid_sides" to SIDE_KEYS),
                )
            }
            val tmpl = existing[templateName] ?: fail(
                "Card template \"$templateName\" not found in model \"$modelName\"",
                hint = "Available card templates for this model: $available. Use model_templates to see current template names.",
                code = "not_found",
                context = ctx + ("available" to existing.keys.toList()),
            )
            val current = if (side == "Front") tmpl.front else tmpl.back
            val patched = PatchHelpers.applyPatch(
                current,
                oldStr.orEmpty(),
                newStr.orEmpty(),
                target = "$side of card template \"$templateName\" in model \"$modelName\"",
                readHint = "model_templates",
                context = ctx + mapOf("template_name" to templateName, "side" to side),
            )
            changes[templateName] = if (side == "Front") patched to tmpl.back else tmpl.front to patched
            patchInfo = Triple(side, pyLen(patched), pyLen(patched) - pyLen(current))
        } else {
            val invalid = templates!!.flatMap { (tmpl, sides) -> sides.keys.filter { it !in SIDE_KEYS }.map { tmpl to it } }
            if (invalid.isNotEmpty()) {
                fail(
                    "Unrecognized template key(s): " + invalid.joinToString("; ") { (tmpl, key) -> "\"$key\" in template \"$tmpl\"" },
                    hint = "Valid template keys are: ${SIDE_KEYS.joinToString(", ")}. " +
                        "Only 'Front' and 'Back' are accepted (case-sensitive — 'front' or 'Answer' are rejected).",
                    context = ctx + mapOf(
                        "invalid_keys" to invalid.map { (tmpl, key) -> mapOf("template" to tmpl, "key" to key) },
                        "valid_keys" to SIDE_KEYS,
                    ),
                )
            }
            val notFound = templates.keys.filter { it !in existing }
            if (notFound.isNotEmpty()) {
                fail(
                    "Card template(s) not found in model \"$modelName\": ${notFound.sorted().joinToString(", ")}",
                    hint = "Available card templates for this model: $available. Use model_templates to see current template names.",
                    context = ctx + mapOf("not_found" to notFound, "available" to existing.keys.toList()),
                )
            }
            templates.forEach { (tmplName, sides) ->
                if (sides.isEmpty()) return@forEach
                val tmpl = existing.getValue(tmplName)
                changes[tmplName] = (sides["Front"] ?: tmpl.front) to (sides["Back"] ?: tmpl.back)
            }
            if (changes.isEmpty()) {
                fail(
                    "No templates were updated for model \"$modelName\"",
                    hint = "Provide at least one card template name with Front and/or Back HTML fields",
                    context = ctx,
                )
            }
        }

        // AnkiDroid saves note types without rslib's template checks, so they run here first (D57).
        val resulting = existing.values.sortedBy { it.ord }.map { t ->
            val (front, back) = changes[t.name] ?: (t.front to t.back)
            TemplateChecks.Template(t.name, front, back)
        }
        TemplateChecks.check(model.fieldNames, resulting, model.isCloze, ctx)

        val progress = WriteProgress(changes.size, "card templates")
        val names = changes.keys.toList()
        names.forEachIndexed { k, tmplName ->
            val (front, back) = changes.getValue(tmplName)
            val ord = existing.getValue(tmplName).ord
            when (val result = repository.updateTemplate(model.id, ord, front = front, back = back)) {
                is AnkiResult.Success -> progress.done += "\"$tmplName\""
                is AnkiResult.Failure -> progress.stop(result, "\"$tmplName\"", names.drop(k + 1).map { "\"$it\"" })
            }
        }
        val after = repository.listTemplates(model.id).confirmedOr("The template change of model \"$modelName\"").associateBy { it.name }
        val wrong = changes.filter { (tmplName, sides) -> after[tmplName]?.let { it.front to it.back } != sides }.keys
        if (wrong.isNotEmpty()) {
            fail(
                "AnkiDroid reported the templates as updated, but re-reading shows different HTML for: ${wrong.joinToString(", ")}",
                hint = "Read them with model_templates before retrying.",
                context = ctx,
            )
        }
        val hint = "Template changes take effect immediately for new card renders. " +
            "Existing cards will use the updated templates the next time they are displayed."
        return ToolResult.structured(
            buildJsonObject {
                put("model_name", modelName)
                if (patchInfo != null) {
                    put("template_count", 1)
                    putStrings("updated_templates", names)
                    put("patched", true)
                    put("side", patchInfo.first)
                    put("content_length", patchInfo.second)
                    put("content_length_change", patchInfo.third)
                    put("message", "Patched ${patchInfo.first} of card template \"${names.single()}\" in model \"$modelName\"")
                } else {
                    put("template_count", names.size)
                    putStrings("updated_templates", names.sorted())
                    put("message", "Updated ${names.size} card template(s) for model \"$modelName\"")
                }
                put("hint", hint)
            },
        )
    }
}

/**
 * `create_model`. AnkiDroid creates a note type with default "Card N" templates, so each template
 * is then written with its name and HTML, and a cloze type is switched to cloze last. AnkiDroid
 * saves note types without rslib's template checks, so the cheap ones run here before anything is
 * written ([TemplateChecks]) (D57). Several writes: a call cut short leaves the note type with
 * default templates, and says so.
 */
class CreateModelTool(repository: AnkiDroidRepository) : AnkiTool(repository) {
    override val name = "create_model"
    override val title = "Create Note Type"
    override val description =
        "Create a new note type (model) in Anki with custom fields, card templates, and styling. Useful for creating " +
            "specialized models like RTL (Right-to-Left) language models for Hebrew, Arabic, etc. Each model defines the " +
            "structure of notes and how cards are generated from them. " +
            "Returns model_id, fields list, template_count, and any template warnings."
    override val inputSchema: JsonObject = inputSchema(required = listOf("model_name", "in_order_fields", "card_templates")) {
        property("model_name", "string", "Name for the new note type; must not already exist")
        property("in_order_fields", "array", "Field names in order", items = "string")
        property("card_templates", "array", "Card templates, each a dict with 'Name', 'Front', and 'Back' keys") {
            putJsonObject("items") {
                put("type", "object")
                putJsonObject("additionalProperties") { put("type", "string") }
            }
        }
        property("css", "string", "Stylesheet applied to cards of this model")
        property("is_cloze", "boolean", "Whether this is a cloze-deletion note type", default = JsonPrimitive(false))
    }
    override val annotations: JsonObject = writeAnnotations(title, destructive = false)

    override suspend fun run(args: ToolArgs): ToolResult {
        val modelName = args.string("model_name")
        val fields = args.optStringList("in_order_fields") ?: throw InvalidArguments("Missing required argument: in_order_fields")
        val rawTemplates = args.raw("card_templates") as? JsonArray ?: throw InvalidArguments("Missing required argument: card_templates")
        val templates = rawTemplates.mapIndexed { i, t -> stringMap(t, "card_templates[$i]") ?: emptyMap() }
        val css = args.optString("css")
        val isCloze = args.bool("is_cloze", false)

        if (modelName.isBlank()) fail("Model name cannot be empty")
        if (fields.isEmpty()) fail("At least one field is required")
        if (templates.isEmpty()) fail("At least one card template is required")
        if (repository.noteTypeNamed(modelName) != null) {
            fail(
                "Model \"$modelName\" already exists",
                hint = "A model with this name already exists. Use a different name or use model_names tool to see existing models.",
                context = mapOf("model_name" to modelName),
            )
        }
        if (fields.any { it.isBlank() }) fail("Field names cannot be empty")
        templates.forEachIndexed { i, t ->
            if (t["Name"].isNullOrEmpty()) fail("Template $i missing required 'Name' field")
            if (t["Front"].isNullOrEmpty()) fail("Template $i ('${t["Name"] ?: "unnamed"}') missing required 'Front' field")
            if (t["Back"].isNullOrEmpty()) fail("Template $i ('${t["Name"] ?: "unnamed"}') missing required 'Back' field")
        }
        rejectFieldSeparator(fields, "Field names")
        val warnings = templateWarnings(fields, templates)
        // AnkiDroid saves note types without rslib's template checks, so they run here first (D57).
        TemplateChecks.check(
            fields,
            templates.map { TemplateChecks.Template(it.getValue("Name"), it.getValue("Front"), it.getValue("Back")) },
            isCloze,
            mapOf("model_name" to modelName),
        )

        val modelId = repository.createNoteType(modelName, fields, templates.size, css?.takeIf { it.isNotEmpty() }).orFail()
        templates.forEachIndexed { ord, t ->
            val result = repository.updateTemplate(modelId, ord, name = t.getValue("Name"), front = t.getValue("Front"), back = t.getValue("Back"))
            if (result is AnkiResult.Failure) {
                fail(
                    "Note type \"$modelName\" was created (id $modelId), but writing card template ${ord + 1} of ${templates.size} " +
                        "failed, so it may still have AnkiDroid's default templates: ${result.message}",
                    hint = "Fix the templates with update_model_templates (see model_templates for their current names).",
                    context = mapOf("model_name" to modelName),
                )
            }
        }
        if (isCloze) {
            val result = repository.updateNoteType(modelId, cloze = true)
            if (result is AnkiResult.Failure) {
                fail(
                    "Note type \"$modelName\" was created (id $modelId) as a standard type; switching it to cloze failed: ${result.message}",
                    context = mapOf("model_name" to modelName),
                )
            }
        }
        val what = "Note type \"$modelName\" (id $modelId)"
        val created = repository.listNoteTypes().confirmedOr(what).firstOrNull { it.id == modelId }
        val createdTemplates = repository.listTemplates(modelId).confirmedOr(what)
        if (created == null || created.fieldNames != fields ||
            createdTemplates.map { Triple(it.name, it.front, it.back) } != templates.map { Triple(it["Name"], it["Front"], it["Back"]) }
        ) {
            fail(
                "AnkiDroid reported note type \"$modelName\" as created, but re-reading it shows different fields or templates",
                hint = "Check it with model_field_names and model_templates.",
                context = mapOf("model_name" to modelName, "model_id" to modelId),
            )
        }
        return ToolResult.structured(
            buildJsonObject {
                put("model_name", modelName)
                put("model_id", modelId)
                putStrings("fields", fields)
                put("template_count", templates.size)
                put("has_css", !css.isNullOrEmpty())
                put("is_cloze", isCloze)
                var message = "Successfully created model \"$modelName\" with ${fields.size} fields and ${templates.size} template(s)"
                if (warnings.isNotEmpty()) {
                    putStrings("warnings", warnings)
                    message += ". Note: Some warnings were detected (see warnings field)."
                }
                put("message", message)
            },
        )
    }

    companion object {
        private val SPECIAL_FIELDS = setOf(
            "FrontSide", "Tags", "Type", "Deck", "Subdeck", "Card",
            "CardFlag", "c1", "c2", "c3", "c4", "c5", "c6", "c7", "c8", "c9",
        )
        private val FIELD_REF = Regex("""\{\{([^}]+)\}\}""")

        /** The add-on's warning for `{{Field}}` references that are not in the field list. */
        internal fun templateWarnings(fields: List<String>, templates: List<Map<String, String>>): List<String> =
            templates.flatMap { t ->
                FIELD_REF.findAll("${t["Front"]} ${t["Back"]}").map { it.groupValues[1].trim() }
                    .filter { ref -> ref !in SPECIAL_FIELDS && !ref.startsWith("cloze:") && !ref.startsWith("c") && ref !in fields }
                    .map { ref -> "Template \"${t["Name"]}\" references field \"{{$ref}}\" which is not in in_order_fields" }
                    .toList()
            }
    }
}

/**
 * `model_field_add`: appends a field. AnkiDroid's provider cannot place it elsewhere, so there is
 * no `index` argument; adding a field changes the schema (one-way full sync) (D57).
 */
class ModelFieldAddTool(repository: AnkiDroidRepository) : AnkiTool(repository) {
    override val name = "model_field_add"
    override val title = "Add Note Type Field"
    override val description =
        "Add a new field at the end of a note type's field list. " +
            "This changes the note type schema, which forces a one-way full sync on the " +
            "next sync -- it overwrites the collection on AnkiWeb and your other devices."
    override val inputSchema: JsonObject = inputSchema(required = listOf("model_name", "field_name")) {
        property("model_name", "string", "Name of the note type (model) to modify")
        property("field_name", "string", "Name of the new field to add")
    }
    override val annotations: JsonObject = writeAnnotations(title, idempotent = true)

    override suspend fun run(args: ToolArgs): ToolResult {
        val modelName = args.string("model_name")
        val name = args.string("field_name").trim()
        val model = repository.noteTypeOrFail(modelName)
        val existing = model.fieldNames
        if (name.isEmpty()) fail("Field name must not be empty", hint = "Provide a non-empty field name", context = mapOf("model_name" to modelName))
        if (name in existing) {
            fail(
                "Field \"$name\" already exists in model \"$modelName\"",
                hint = "Choose a different field name, or use model_field_rename to change an existing field",
                context = mapOf("model_name" to modelName, "field_name" to name, "available" to existing),
            )
        }
        if (existing.any { it.equals(name, ignoreCase = true) }) {
            fail(
                "Field name \"$name\" collides with an existing field (case-insensitive) in model \"$modelName\"",
                hint = "Anki treats field names case-insensitively for uniqueness. Choose a distinct name.",
                context = mapOf("model_name" to modelName, "field_name" to name, "available" to existing),
            )
        }
        rejectFieldSeparator(listOf(name), "Field names")
        repository.addField(model.id, name).orFail()
        val fields = repository.listNoteTypes().confirmedOr("Adding field \"$name\" to model \"$modelName\"")
            .firstOrNull { it.id == model.id }?.fieldNames.orEmpty()
        // rslib may adjust the name it stores (e.g. characters it does not allow); report what it kept.
        val stored = fields.drop(existing.size).singleOrNull()
            ?: fail(
                "AnkiDroid reported field \"$name\" as added, but re-reading the note type does not show a new field",
                hint = "Check the note type with model_field_names.",
                context = mapOf("model_name" to modelName),
            )
        return ToolResult.structured(
            buildJsonObject {
                put("model_name", modelName)
                put("field_name", stored)
                putStrings("fields", fields)
                put(
                    "message",
                    if (stored == name) {
                        "Added field \"$stored\" to model \"$modelName\""
                    } else {
                        "Added field \"$stored\" to model \"$modelName\" (Anki stored \"$name\" as \"$stored\")"
                    },
                )
                put("warning", FULL_SYNC_WARNING)
            },
        )
    }
}
