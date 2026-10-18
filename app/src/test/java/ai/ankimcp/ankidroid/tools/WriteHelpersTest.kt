package ai.ankimcp.ankidroid.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** The ports of the add-on's `_patch_helpers.py` and rslib's tag rules. */
class WriteHelpersTest {
    private fun failure(block: () -> Unit): String {
        try {
            block()
        } catch (e: ToolFailure) {
            return e.result.text
        }
        fail("expected ToolFailure")
        error("unreachable")
    }

    @Test
    fun `a patch replaces the single occurrence`() {
        assertEquals("a X c", PatchHelpers.applyPatch("a b c", "b", "X", "t", "r"))
        assertEquals("a  c", PatchHelpers.applyPatch("a b c", "b", "", "t", "r"))
        // Non-overlapping count, like str.count: "aa" occurs once in "aaa".
        assertEquals("Xa", PatchHelpers.applyPatch("aaa", "aa", "X", "t", "r"))
    }

    @Test
    fun `no match carries an excerpt anchored near the closest partial match`() {
        val content = "x".repeat(100) + "<div class=\"front\">hello</div>" + "y".repeat(300)
        val text = failure {
            PatchHelpers.applyPatch(content, "<div class=\"front\">bye</div>", "z", "CSS of model \"Basic\"", "model_styling", mapOf("model_name" to "Basic"))
        }
        assertTrue(text, text.startsWith("[patch_no_match] old_str not found in CSS of model \"Basic\" (0 matches) -- nothing was written (hint: "))
        assertTrue(text, text.contains("read the full current content with model_styling, then retry."))
        assertTrue(text, text.contains("(context: {'content_length': 430, 'current_content_excerpt': '..." + "x".repeat(60) + "<div class=\"front\">hello"))
        assertTrue(text, text.endsWith("...', 'model_name': 'Basic'})"))
        assertEquals("abc", PatchHelpers.buildExcerpt("abc", "zzz"))
        assertEquals("", PatchHelpers.buildExcerpt("", "a"))
    }

    @Test
    fun `several matches, empty old_str, half pairs and mixed modes are rejected`() {
        assertTrue(failure { PatchHelpers.applyPatch("b b", "b", "x", "t", "r") }.startsWith("[patch_multiple_matches] old_str matches 2 times in t"))
        assertTrue(failure { PatchHelpers.applyPatch("b", "", "x", "t", "r") }.startsWith("[validation_error] old_str must not be empty"))
        assertTrue(failure { PatchHelpers.requirePatchPair("a", null) }.startsWith("[validation_error] Patch mode requires both old_str and new_str -- new_str is missing"))
        PatchHelpers.requirePatchPair("a", "")
        assertTrue(
            failure { PatchHelpers.selectMode(true, true, "'css'", "'old_str'/'new_str'") }
                .startsWith("[validation_error] Cannot combine 'css' with 'old_str'/'new_str'"),
        )
        assertTrue(failure { PatchHelpers.selectMode(false, false, "'css'", "'p'") }.startsWith("[validation_error] No input provided"))
        assertEquals(PatchHelpers.Mode.NONE, PatchHelpers.selectMode(false, false, "'css'", "'p'", allowNeither = true))
        assertTrue(failure { PatchHelpers.rejectEmptyFullInput(true, "'fields'", "field name") }.startsWith("[validation_error] 'fields' was provided but is empty"))
    }

    @Test
    fun `tags are added case-insensitively and sorted like rslib`() {
        assertEquals(listOf("alpha", "Beta", "zeta"), AnkiTags.add(listOf("zeta", "alpha"), listOf("Beta", "ALPHA")))
        assertNull(AnkiTags.add(listOf("Vocab"), listOf("vocab")))
    }

    @Test
    fun `removal matches the tag or its children, ignoring case, with no wildcards`() {
        assertEquals(listOf("vocabulary", "other"), AnkiTags.remove(listOf("Vocab", "vocab::verbs", "vocabulary", "other"), listOf("VOCAB")))
        assertNull(AnkiTags.remove(listOf("a*b"), listOf("a*")))
        assertEquals(emptyList<String>(), AnkiTags.remove(listOf("a*b"), listOf("A*B")))
        assertEquals(listOf("a", "b"), AnkiTags.split("  a \t b\n"))
    }

    @Test
    fun `replace conflicts are the add-on's`() {
        assertEquals("same_tag", AnkiTags.replaceConflict("Vocab", "vocab"))
        assertEquals("child_tag", AnkiTags.replaceConflict("vocab", "Vocab::verbs"))
        assertNull(AnkiTags.replaceConflict("vocab", "vocabulary"))
    }
}
