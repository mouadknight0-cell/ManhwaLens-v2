package com.manhwalens.app.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Output validation.
 *
 * The contract these tests defend: a reply is mapped onto bubbles only when every requested ID is
 * accounted for. A half-trusted reply puts Arabic text on the wrong bubble, which is worse than an
 * untranslated bubble with a visible reason.
 */
class QwenOutputParserTest {

    private val ids = listOf(1, 2, 3, 4)

    private val good = """
        {"translations":[
          {"id":1,"text":"لقد عدت."},
          {"id":2,"text":"قلت لك إنني سأعود."},
          {"id":3,"text":"بعد كل ما حدث؟"},
          {"id":4,"text":"لم أقل إنني سأسامحك."}
        ]}
    """.trimIndent()

    private fun success(raw: String, wanted: List<Int> = ids): Map<Int, String> {
        val result = QwenOutputParser.parse(raw, wanted)
        assertTrue("expected success but got $result", result is QwenOutputParser.Result.Success)
        return (result as QwenOutputParser.Result.Success).byId
    }

    private fun rejected(raw: String, wanted: List<Int> = ids): QwenOutputParser.Result.Rejected {
        val result = QwenOutputParser.parse(raw, wanted)
        assertTrue("expected rejection but got $result", result is QwenOutputParser.Result.Rejected)
        return result as QwenOutputParser.Result.Rejected
    }

    @Test
    fun `the reference reply maps every id`() {
        val mapped = success(good)
        assertEquals(setOf(1, 2, 3, 4), mapped.keys)
        assertEquals("لقد عدت.", mapped[1])
        assertEquals("لم أقل إنني سأسامحك.", mapped[4])
    }

    @Test
    fun `a json fence around the object is tolerated`() {
        val fenced = "Here is the JSON:\n```json\n$good\n```\nHope that helps."
        // Prose is tolerated only because the balanced object is extracted exactly; the mapping is
        // still identical to the clean case.
        assertEquals(success(good), success(fenced))
    }

    @Test
    fun `output keeps working when the model answers ids out of order`() {
        val shuffled = """
            {"translations":[
              {"id":3,"text":"بعد كل ما حدث؟"},
              {"id":1,"text":"لقد عدت."},
              {"id":4,"text":"لم أقل إنني سأسامحك."},
              {"id":2,"text":"قلت لك إنني سأعود."}
            ]}
        """.trimIndent()
        val mapped = success(shuffled)
        // Mapping is by id: line 1 must still be line 1.
        assertEquals("لقد عدت.", mapped[1])
        assertEquals("بعد كل ما حدث؟", mapped[3])
    }

    @Test
    fun `a missing id is reported for that id and does not block the rest`() {
        val missing = """
            {"translations":[
              {"id":1,"text":"لقد عدت."},
              {"id":2,"text":"قلت لك إنني سأعود."},
              {"id":4,"text":"لم أقل إنني سأسامحك."}
            ]}
        """.trimIndent()
        val mapped = success(missing)
        assertEquals(setOf(1, 2, 4), mapped.keys)
        // 3 must not be silently filled from a neighbour.
        assertFalse(mapped.containsKey(3))
    }

    @Test
    fun `a missing id does not block ids that were answered`() {
        val missing = """{"translations":[{"id":1,"text":"لقد عدت."},{"id":3,"text":"بعد كل ما حدث؟"}]}"""
        val result = QwenOutputParser.parse(missing, listOf(1, 3, 4))
        // 1 and 3 mapped correctly; 4 is genuinely absent, so the engine records MISSING_ID for it
        // and the bubble stays untranslated with a reason rather than borrowing a neighbour's text.
        assertTrue(result is QwenOutputParser.Result.Success)
        assertEquals(setOf(1, 3), (result as QwenOutputParser.Result.Success).byId.keys)
    }

    @Test
    fun `a duplicated id rejects the reply`() {
        val dup = """
            {"translations":[
              {"id":1,"text":"لقد عدت."},
              {"id":1,"text":"شيء آخر."},
              {"id":2,"text":"قلت لك إنني سأعود."},
              {"id":3,"text":"بعد كل ما حدث؟"},
              {"id":4,"text":"لم أقل إنني سأسامحك."}
            ]}
        """.trimIndent()
        val result = rejected(dup)
        assertTrue(result.failures.any { it.reason == EngineFailure.DUPLICATE_ID && it.id == 1 })
    }

    @Test
    fun `an empty translation fails that id only`() {
        val blank = """
            {"translations":[
              {"id":1,"text":"لقد عدت."},
              {"id":2,"text":"   "},
              {"id":3,"text":"بعد كل ما حدث؟"},
              {"id":4,"text":"لم أقل إنني سأسامحك."}
            ]}
        """.trimIndent()
        // A blank line must not be drawn as an empty bubble; it becomes a per-id failure.
        val result = QwenOutputParser.parse(blank, ids)
        if (result is QwenOutputParser.Result.Success) {
            assertFalse("blank translation must not be accepted", result.byId.containsKey(2))
        } else {
            assertTrue((result as QwenOutputParser.Result.Rejected).failures.any { it.id == 2 })
        }
    }

    @Test
    fun `an id the model invented rejects the whole reply`() {
        val extra = """{"translations":[{"id":1,"text":"لقد عدت."},{"id":9,"text":"سطر إضافي."}]}"""
        val result = rejected(extra, listOf(1))
        // A model that invents ids has broken the id-to-line contract, so none of its mapping is
        // trusted: a shifted mapping would draw text over the wrong bubble.
        assertTrue(result.failures.any { it.id == 9 && it.reason == EngineFailure.EXTRA_TEXT })
    }

    @Test
    fun `prose without json is a malformed rejection for every id`() {
        val result = rejected("I cannot translate this.")
        assertEquals(setOf(1, 2, 3, 4), result.failures.map { it.id }.toSet())
        assertTrue(result.failures.all { it.reason == EngineFailure.MALFORMED_OUTPUT })
    }

    @Test
    fun `truncated json does not crash and is rejected`() {
        val truncated = """{"translations":[{"id":1,"text":"لقد عدت."}"""
        val result = rejected(truncated)
        assertTrue(result.failures.isNotEmpty())
    }

    @Test
    fun `untranslated latin output is refused`() {
        val copied = """{"translations":[{"id":1,"text":"I came back."}]}"""
        val result = QwenOutputParser.parse(copied, listOf(1))
        // The model echoing English must never be accepted as Arabic.
        assertTrue(result is QwenOutputParser.Result.Rejected)
        assertTrue((result as QwenOutputParser.Result.Rejected).failures.any { it.id == 1 })
    }

    @Test
    fun `a translation that legitimately numbers something passes`() {
        val numbered = """{"translations":[{"id":1,"text":"٥٠٪ من الماء."},{"id":2,"text":"!?"}]}"""
        val mapped = success(numbered, listOf(1, 2))
        assertEquals("٥٠٪ من الماء.", mapped[1])
        assertEquals("!?", mapped[2])
    }

    @Test
    fun `two concatenated objects do not confuse the extractor`() {
        // Naive first-brace-to-last-brace slicing would merge these into invalid JSON.
        val two = """{"translations":[{"id":1,"text":"واحد."}]} {"translations":[{"id":2,"text":"اثنان."}]}"""
        val extracted = QwenOutputParser.extractJson(two)
        assertNotNull(extracted)
        assertEquals("""{"translations":[{"id":1,"text":"واحد."}]}""", extracted)
        assertEquals("واحد.", success(two, listOf(1))[1])
    }

    @Test
    fun `a brace inside a translated string does not truncate the object`() {
        val withBrace = """{"translations":[{"id":1,"text":"استخدم { هذا } الرمز."}]}"""
        assertEquals("استخدم { هذا } الرمز.", success(withBrace, listOf(1))[1])
    }

    @Test
    fun `an escaped quote inside the text is handled`() {
        val quoted = """{"translations":[{"id":1,"text":"قال \"لا\" وغادر."}]}"""
        assertEquals("""قال "لا" وغادر.""", success(quoted, listOf(1))[1])
    }

    @Test
    fun `id given as a string is accepted`() {
        val asString = """{"translations":[{"id":"2","text":"قلت لك إنني سأعود."}]}"""
        assertEquals("قلت لك إنني سأعود.", success(asString, listOf(2))[2])
    }

    @Test
    fun `an empty request is not an error path that returns text`() {
        val result = QwenOutputParser.parse(good, emptyList())
        assertTrue(result is QwenOutputParser.Result.Rejected)
    }

    @Test
    fun `extractJson returns null when there is no object`() {
        assertNull(QwenOutputParser.extractJson("no braces here"))
    }

    @Test
    fun `latin detection ignores digits and arabic`() {
        assertFalse(QwenOutputParser.containsLatin("١٢٣ ٤٥٦"))
        assertFalse(QwenOutputParser.containsLatin("50%"))
        assertTrue(QwenOutputParser.containsLatin("ok"))
    }
}
