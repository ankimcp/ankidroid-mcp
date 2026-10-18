package ai.ankimcp.ankidroid.tools

import ai.ankimcp.ankidroid.mcp.ToolResult
import ai.ankimcp.ankidroid.tools.FakeCollection.FModel
import ai.ankimcp.ankidroid.tools.FakeCollection.FTemplate
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelWriteToolsTest {
    private val col = FakeCollection().apply {
        models += FModel(
            10,
            "Basic",
            listOf("Front", "Back"),
            css = ".card { color: red }",
            templates = listOf(FTemplate("Card 1", "<b>{{Front}}</b>", "{{FrontSide}}<hr id=answer>{{Back}}")),
        )
    }
    private val repo = col.repository()

    @Test
    fun `update_model_styling replaces or patches the CSS and confirms`() {
        val patched = UpdateModelStylingTool(repo).callWith("model_name" to "basic", "old_str" to "red", "new_str" to "blue").data
        assertEquals(".card { color: blue }", col.models.single().css)
        assertTrue(patched["patched"]!!.jsonPrimitive.boolean)
        assertEquals(1, patched["css_length_change"]!!.jsonPrimitive.int)
        UpdateModelStylingTool(repo).callWith("model_name" to "Basic", "css" to ".card { direction: rtl }")
        assertEquals(".card { direction: rtl }", col.models.single().css)
    }

    @Test
    fun `update_model_styling can write only the LaTeX header and footer`() {
        val data = UpdateModelStylingTool(repo).callWith("model_name" to "Basic", "latex_pre" to "\\usepackage{tikz}").data
        assertEquals("\\usepackage{tikz}", col.models.single().latexPre)
        assertEquals(".card { color: red }", col.models.single().css)
        assertEquals(listOf("latex_pre"), data["updated_latex_fields"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals("Successfully updated LaTeX preamble for model \"Basic\" (CSS unchanged)", data["message"]!!.jsonPrimitive.content)
        assertNull(col.writes.single().values["css"])
        assertTrue(UpdateModelStylingTool(repo).callWith("model_name" to "Basic").text.startsWith("[validation_error] No input provided"))
        assertTrue(UpdateModelStylingTool(repo).callWith("model_name" to "Basic", "latex_svg" to true).text.startsWith("Unknown argument(s)"))
    }

    @Test
    fun `update_model_templates patches one side and replaces whole sides`() {
        val patched = UpdateModelTemplatesTool(repo).callWith(
            "model_name" to "Basic", "template_name" to "Card 1", "side" to "Front", "old_str" to "<b>", "new_str" to "<i>",
        ).data
        assertEquals("<i>{{Front}}</b>", col.models.single().templates[0].front)
        assertEquals("Patched Front of card template \"Card 1\" in model \"Basic\"", patched["message"]!!.jsonPrimitive.content)
        UpdateModelTemplatesTool(repo).callWith("model_name" to "Basic", "templates" to mapOf("Card 1" to mapOf("Back" to "{{Back}}")))
        assertEquals("{{Back}}", col.models.single().templates[0].back)
        assertEquals("<i>{{Front}}</b>", col.models.single().templates[0].front)
    }

    @Test
    fun `update_model_templates rejects bad sides, names and keys before writing`() {
        assertTrue(
            UpdateModelTemplatesTool(repo).callWith(
                "model_name" to "Basic", "template_name" to "Card 1", "side" to "front", "old_str" to "a", "new_str" to "b",
            ).text.startsWith("[validation_error] Invalid side \"front\" (hint: side must be one of: Back, Front (case-sensitive).)"),
        )
        assertTrue(
            UpdateModelTemplatesTool(repo).callWith("model_name" to "Basic", "templates" to mapOf("Card 9" to mapOf("Front" to "x")))
                .text.startsWith("Card template(s) not found in model \"Basic\": Card 9"),
        )
        assertTrue(
            UpdateModelTemplatesTool(repo).callWith("model_name" to "Basic", "templates" to mapOf("Card 1" to mapOf("Answer" to "x")))
                .text.startsWith("Unrecognized template key(s): \"Answer\" in template \"Card 1\""),
        )
        assertTrue(col.writes.isEmpty())
    }

    @Test
    fun `create_model creates the type, writes its templates and switches cloze last`() {
        val data = CreateModelTool(repo).callWith(
            "model_name" to "MyCloze",
            "in_order_fields" to listOf("Text", "Extra"),
            "card_templates" to listOf(mapOf("Name" to "Cloze", "Front" to "{{cloze:Text}}", "Back" to "{{cloze:Text}}<br>{{Extra}}")),
            "css" to ".cloze { color: blue }",
            "is_cloze" to true,
        ).data
        val model = col.models.single { it.name == "MyCloze" }
        assertEquals(model.id, data["model_id"]!!.jsonPrimitive.long)
        assertEquals(FTemplate("Cloze", "{{cloze:Text}}", "{{cloze:Text}}<br>{{Extra}}"), model.templates.single())
        assertTrue(model.cloze)
        assertEquals(listOf("insert", "update", "update"), col.writes.map { it.op })
        assertEquals("models/${model.id}", col.writes.last().path)
        assertNull(data["warnings"])
        assertNull(data["will_force_full_sync"])
    }

    @Test
    fun `create_model validates and keeps the add-on's warnings for references it cannot read`() {
        assertTrue(
            CreateModelTool(repo).callWith(
                "model_name" to "basic", "in_order_fields" to listOf("A"), "card_templates" to listOf(mapOf("Name" to "C", "Front" to "x", "Back" to "y")),
            ).text.startsWith("Model \"basic\" already exists"),
        )
        assertEquals(
            "Template 0 ('C') missing required 'Back' field",
            CreateModelTool(repo).callWith("model_name" to "N", "in_order_fields" to listOf("A"), "card_templates" to listOf(mapOf("Name" to "C", "Front" to "x"))).text,
        )
        val data = CreateModelTool(repo).callWith(
            "model_name" to "N", "in_order_fields" to listOf("A"), "card_templates" to listOf(mapOf("Name" to "C", "Front" to "{{A}}", "Back" to "{{text:A}}")),
        ).data
        // rslib reads {{text:A}} as field A with a filter; the add-on's check does not, and warns.
        assertEquals(
            listOf("Template \"C\" references field \"{{text:A}}\" which is not in in_order_fields"),
            data["warnings"]!!.jsonArray.map { it.jsonPrimitive.content },
        )
    }

    /** Templates as (front, back); every call uses a new model name so accepted ones do not collide. */
    private var models = 0
    private fun createModel(fields: List<String>, vararg sides: Pair<String, String>, cloze: Boolean = false) = CreateModelTool(repo).callWith(
        "model_name" to "N${models++}",
        "in_order_fields" to fields,
        "card_templates" to sides.mapIndexed { i, (front, back) -> mapOf("Name" to "C$i", "Front" to front, "Back" to back) },
        "is_cloze" to cloze,
    )

    private fun rejected(result: ToolResult, message: String) {
        assertTrue(result.text, result.isError && result.text.startsWith("[validation_error] $message"))
    }

    private fun accepted(result: ToolResult) = assertFalse(result.text, result.isError)

    @Test
    fun `template checks - unknown fields on either side`() {
        rejected(createModel(listOf("A"), "{{Bee}}" to "{{A}}"), "Card template \"C0\" refers to field \"Bee\", but the note type has no field called \"Bee\"")
        rejected(createModel(listOf("A"), "{{A}}" to "{{Bee}}"), "Card template \"C0\" refers to field \"Bee\"")
        rejected(createModel(listOf("A"), "{{#Bee}}{{A}}{{/Bee}}" to "x"), "Card template \"C0\" refers to field \"Bee\"")
        accepted(createModel(listOf("A", "B"), "{{#B}}{{text:B}}{{/B}}{{^A}}{{Deck}}{{/A}}{{A}}" to "{{FrontSide}}{{tts en_US:A}}"))
        // Empty field names are allowed (add-ons use them); cloze conditionals {{#c1}} are not fields.
        accepted(createModel(listOf("A"), "{{A}}{{text:}}{{tts-voices:}}{{#c1}}x{{/c1}}" to "x"))
        assertEquals(2, col.models.size - 1)
    }

    @Test
    fun `template checks - comments are skipped`() {
        rejected(createModel(listOf("A"), "<!-- {{A}} -->" to "{{A}}"), "The front of card template \"C0\" references no field")
        accepted(createModel(listOf("A"), "{{A}}<!-- {{Nope}} -->" to "x"))
    }

    @Test
    fun `template checks - a front of special fields only is accepted, an empty one is not`() {
        accepted(createModel(listOf("A"), "{{Tags}} {{Deck}}" to "{{A}}"))
        rejected(createModel(listOf("A"), "plain text" to "{{A}}"), "The front of card template \"C0\" references no field")
    }

    @Test
    fun `template checks - identical fronts only with {{Card}}`() {
        rejected(createModel(listOf("A"), "{{A}}" to "1", "{{A}}" to "2"), "Card templates \"C0\" and \"C1\" have the same front")
        accepted(createModel(listOf("A"), "{{A}} {{ Card }}" to "1", "{{A}} {{ Card }}" to "2"))
    }

    @Test
    fun `template checks - cloze needs a cloze filter on both sides of the first template`() {
        rejected(createModel(listOf("Text"), "{{cloze:Text}}" to "{{Text}}", cloze = true), "Cloze template \"C0\" needs {{cloze:Field}} on both")
        rejected(createModel(listOf("Text"), "{{Text}}" to "{{cloze:Text}}", cloze = true), "Cloze template \"C0\" needs {{cloze:Field}} on both")
        accepted(createModel(listOf("Text"), "{{cloze:Text}}" to "{{cloze:Text}}", cloze = true))
        accepted(createModel(listOf("Text"), "{{Text}}" to "{{Text}}"))
    }

    @Test
    fun `template checks - unbalanced conditionals and the legacy delimiter directive`() {
        rejected(createModel(listOf("A"), "{{#A}}{{A}}" to "x"), "Card template \"C0\" has a conditional that is not opened or not closed")
        rejected(createModel(listOf("A"), "{{A}}{{/A}}" to "x"), "Card template \"C0\" has a conditional")
        accepted(createModel(listOf("A"), "{{=<% %>=}}<%Nope%>" to "x"))
    }

    @Test
    fun `field names containing U+001F are rejected`() {
        rejected(createModel(listOf("A\u001fB"), "{{A}}" to "x"), "Field names cannot contain the character U+001F")
        assertTrue(col.writes.isEmpty())
    }

    @Test
    fun `update_model_templates checks the resulting templates`() {
        val result = UpdateModelTemplatesTool(repo).callWith("model_name" to "Basic", "templates" to mapOf("Card 1" to mapOf("Front" to "{{Nope}}")))
        assertTrue(result.text, result.text.startsWith("[validation_error] Card template \"Card 1\" refers to field \"Nope\""))
        val back = UpdateModelTemplatesTool(repo).callWith("model_name" to "Basic", "templates" to mapOf("Card 1" to mapOf("Back" to "{{Nope}}")))
        assertTrue(back.text, back.text.startsWith("[validation_error] Card template \"Card 1\" refers to field \"Nope\""))
        assertTrue(col.writes.isEmpty())
    }

    @Test
    fun `model_field_add reports the name AnkiDroid stored`() {
        col.afterWrite = { w ->
            if (w.path.endsWith("/fields")) {
                col.models.replaceAll { m -> m.copy(fields = m.fields.map { if (it == "Ex:tra") "Extra" else it }) }
            }
        }
        val data = ModelFieldAddTool(repo).callWith("model_name" to "Basic", "field_name" to "Ex:tra").data
        assertEquals("Extra", data["field_name"]!!.jsonPrimitive.content)
        assertEquals("Added field \"Extra\" to model \"Basic\" (Anki stored \"Ex:tra\" as \"Extra\")", data["message"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a template write that fails leaves a clear message about the half-made type`() {
        col.refuse = { w -> if (w.path.contains("/templates/")) IllegalArgumentException("Note type is malformed") else null }
        val result = CreateModelTool(repo).callWith(
            "model_name" to "N", "in_order_fields" to listOf("A"), "card_templates" to listOf(mapOf("Name" to "C", "Front" to "{{A}}", "Back" to "{{A}}")),
        )
        assertTrue(result.text, result.text.startsWith("Note type \"N\" was created (id "))
        assertTrue(result.text, result.text.contains("may still have AnkiDroid's default templates"))
    }

    @Test
    fun `model_field_add appends and refuses duplicates`() {
        val data = ModelFieldAddTool(repo).callWith("model_name" to "Basic", "field_name" to " Extra ").data
        assertEquals(listOf("Front", "Back", "Extra"), data["fields"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertTrue(data["warning"]!!.jsonPrimitive.content.startsWith("This is a schema change."))
        assertTrue(ModelFieldAddTool(repo).callWith("model_name" to "Basic", "field_name" to "back").text.contains("collides with an existing field"))
        assertTrue(ModelFieldAddTool(repo).callWith("model_name" to "Basic", "field_name" to "x", "index" to 0).text.startsWith("Unknown argument(s)"))
        assertFalse(col.models.single().fields.contains("back"))
    }
}
