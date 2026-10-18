package ai.ankimcp.ankidroid.tools

import ai.ankimcp.ankidroid.anki.AnkiDroidVersion
import ai.ankimcp.ankidroid.anki.FakeAvailability
import ai.ankimcp.ankidroid.tools.FakeCollection.FModel
import ai.ankimcp.ankidroid.tools.FakeCollection.FTemplate
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelToolsTest {
    private val col = FakeCollection().apply {
        models += FModel(10, "Basic", listOf("Front", "Back"), css = ".card { color: red } .cloze {}")
        models += FModel(
            11,
            "Cloze",
            listOf("Text", "Extra"),
            css = "",
            templates = listOf(FTemplate("Cloze", "{{cloze:Text}}", "{{cloze:Text}}<br>{{Extra}}")),
        )
        models += FModel(
            12,
            "Basic (and reversed card)",
            listOf("Front", "Back"),
            templates = listOf(FTemplate("Card 1", "{{Front}}", "{{Back}}"), FTemplate("Card 2", "{{Back}}", "{{Front}}")),
        )
    }
    private val repo = col.repository()

    @Test
    fun `model_names lists names case-insensitively sorted with common types`() {
        col.models += FModel(13, "apple", listOf("A"))
        val data = ModelNamesTool(repo).callWith().data

        assertEquals(
            listOf("apple", "Basic", "Basic (and reversed card)", "Cloze"),
            data["modelNames"]!!.jsonArray.map { it.jsonPrimitive.content },
        )
        assertEquals(4, data["total"]!!.jsonPrimitive.int)
        assertEquals("Found 4 note types", data["message"]!!.jsonPrimitive.content)
        val common = data["commonTypes"]!!.jsonObject
        assertEquals("Basic", common["basic"]!!.jsonPrimitive.content)
        assertEquals("Basic (and reversed card)", common["basicReversed"]!!.jsonPrimitive.content)
        assertEquals("Cloze", common["cloze"]!!.jsonPrimitive.content)
    }

    @Test
    fun `model_names with no note types has null common types`() {
        col.models.clear()
        val data = ModelNamesTool(repo).callWith().data
        assertEquals(0, data["total"]!!.jsonPrimitive.int)
        assertEquals("No note types found in Anki", data["message"]!!.jsonPrimitive.content)
        assertEquals(JsonNull, data["commonTypes"]!!.jsonObject["basic"])
    }

    @Test
    fun `model_field_names returns names only, with the add-on's example`() {
        val data = ModelFieldNamesTool(repo).callWith("model_name" to "cloze").data

        assertEquals("cloze", data["model_name"]!!.jsonPrimitive.content)
        assertEquals(listOf("Text", "Extra"), data["field_names"]!!.jsonArray.map { it.jsonPrimitive.content })
        val fields = data["fields"]!!.jsonArray.map { it.jsonObject }
        assertEquals(setOf("name"), fields[0].keys)
        assertEquals("Model \"cloze\" has 2 fields", data["message"]!!.jsonPrimitive.content)
        assertTrue(data["example"]!!.jsonObject.containsKey("Text"))
        assertEquals("Use these field names as keys when creating notes with add_note tool", data["hint"]!!.jsonPrimitive.content)
    }

    @Test
    fun `an unknown model is the add-on's not-found error`() {
        val result = ModelFieldNamesTool(repo).callWith("model_name" to "Nope")
        assertTrue(result.isError)
        assertEquals(
            "Model \"Nope\" not found (hint: Use model_names tool to see available models) (context: {'model_name': 'Nope'})",
            result.text,
        )
    }

    @Test
    fun `model_field_names needs model_name`() {
        val result = ModelFieldNamesTool(repo).callWith()
        assertTrue(result.isError)
        assertEquals("Missing required argument: model_name", result.text)
    }

    @Test
    fun `model_templates maps templates by name in template order`() {
        val data = ModelTemplatesTool(repo).callWith("model_name" to "Basic (and reversed card)").data

        val templates = data["templates"]!!.jsonObject
        assertEquals(listOf("Card 1", "Card 2"), templates.keys.toList())
        assertEquals("{{Back}}", templates["Card 2"]!!.jsonObject["Front"]!!.jsonPrimitive.content)
        assertEquals("{{Front}}", templates["Card 2"]!!.jsonObject["Back"]!!.jsonPrimitive.content)
        assertEquals(2, data["template_count"]!!.jsonPrimitive.int)
        assertEquals(
            "Retrieved 2 card template(s) for model \"Basic (and reversed card)\"",
            data["message"]!!.jsonPrimitive.content,
        )
        assertEquals("models/12/templates", col.queriesTo("models/12/templates").single().path)
    }

    @Test
    fun `model_templates errors when the model has no templates`() {
        col.models += FModel(14, "Empty", listOf("A"), templates = emptyList())
        val result = ModelTemplatesTool(repo).callWith("model_name" to "Empty")
        assertTrue(result.isError)
        assertTrue(result.text, result.text.startsWith("Model \"Empty\" has no card templates (hint:"))
    }

    @Test
    fun `model_styling returns css info and no latex by default`() {
        val data = ModelStylingTool(repo).callWith("model_name" to "Basic").data

        assertEquals(".card { color: red } .cloze {}", data["css"]!!.jsonPrimitive.content)
        val info = data["css_info"]!!.jsonObject
        assertEquals(30, info["length"]!!.jsonPrimitive.int)
        assertTrue(info["has_card_styling"]!!.jsonPrimitive.boolean)
        assertFalse(info["has_front_styling"]!!.jsonPrimitive.boolean)
        assertTrue(info["has_cloze_styling"]!!.jsonPrimitive.boolean)
        assertNull(data["latex_pre"])
        assertNull(data["latex_svg"])
    }

    @Test
    fun `model_styling with include_latex returns pre and post but never latex_svg`() {
        val data = ModelStylingTool(repo).callWith("model_name" to "Basic", "include_latex" to true).data
        assertEquals("\\documentclass", data["latex_pre"]!!.jsonPrimitive.content)
        assertEquals("\\end{document}", data["latex_post"]!!.jsonPrimitive.content)
        assertNull(data["latex_svg"])
    }

    @Test
    fun `model_styling without css errors unless latex is asked for`() {
        val result = ModelStylingTool(repo).callWith("model_name" to "Cloze")
        assertTrue(result.isError)
        assertTrue(result.text, result.text.startsWith("Model \"Cloze\" has no styling"))

        val data = ModelStylingTool(repo).callWith("model_name" to "Cloze", "include_latex" to true).data
        assertEquals(
            "Model \"Cloze\" has no CSS styling; returning its LaTeX preamble only",
            data["message"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun `latex_svg is not an argument on AnkiDroid`() {
        val result = ModelStylingTool(repo).callWith("model_name" to "Basic", "latex_svg" to true)
        assertTrue(result.isError)
        assertTrue(result.text, result.text.startsWith("Unknown argument(s) for model_styling: latex_svg."))
        assertFalse(ModelStylingTool(repo).inputSchema["properties"]!!.jsonObject.containsKey("latex_svg"))
    }

    @Test
    fun `model tools need AnkiDroid 2_24`() {
        val old = col.repository(FakeAvailability(version = AnkiDroidVersion(22303300, "2.23.3")))
        listOf(ModelNamesTool(old).callWith(), ModelStylingTool(old).callWith("model_name" to "Basic")).forEach { result ->
            assertTrue(result.isError)
            assertEquals(
                "This needs AnkiDroid 2.24 or newer; the phone has AnkiDroid 2.23.3. " +
                    "Update AnkiDroid to 2.24 or newer, then try again.",
                result.text,
            )
        }
        assertTrue(col.provider.queries.isEmpty())
    }
}
