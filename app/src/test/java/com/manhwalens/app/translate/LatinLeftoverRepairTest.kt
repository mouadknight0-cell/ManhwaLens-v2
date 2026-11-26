package com.manhwalens.app.translate

import org.junit.Assert.*
import org.junit.Test

/**
 * Regression coverage for the reported TR-UNTRANSLATED loss:
 *   OCR  "I'M THE ONLY ONE OUT HERE ALL BY MYSELE, AFTER ALL,"
 *   MT   "أنا الوحيد هنا جميعًا من قبل Mysele، بعد كل شيء،"
 * A single one-glyph OCR slip must be repaired once and retranslated, while real names,
 * genuine foreign words and ambiguous tokens must never be rewritten.
 */
class LatinLeftoverRepairTest {
    private val source = "I'M THE ONLY ONE OUT HERE ALL BY MYSELE, AFTER ALL,"
    private val raw = "أنا الوحيد هنا جميعًا من قبل Mysele، بعد كل شيء،"

    @Test fun singleGlyphSlipIsIdentifiedAndCorrectedInTheSource() {
        val fix = LatinLeftoverRepair.correctedSource(source, raw, emptyList(), emptyMap())
        assertNotNull("MYSELE -> MYSELF must be proposed", fix)
        val (corrected, how) = fix!!
        assertEquals("I'M THE ONLY ONE OUT HERE ALL BY MYSELF, AFTER ALL,", corrected)
        assertTrue(how, how.contains("MYSELE") && how.contains("MYSELF"))
    }

    @Test fun correctionKeepsTheOriginalOcrCase() {
        assertEquals("MYSELF", LatinLeftoverRepair.correctedSource("ALL BY MYSELE", "من قبل Mysele", emptyList(), emptyMap())!!.first.removePrefix("ALL BY "))
        assertEquals("myself", LatinLeftoverRepair.correctedSource("all by mysele", "من قبل mysele", emptyList(), emptyMap())!!.first.removePrefix("all by "))
    }

    @Test fun protectedCharacterNameIsNotAnOcrError() {
        val leftovers = LatinLeftoverRepair.unexplained("قال Namgung", listOf("Namgung"), emptyMap())
        assertTrue("a shielded name must never be reported as a leftover: $leftovers", leftovers.isEmpty())
        assertNull(LatinLeftoverRepair.correctedSource("SAID NAMGUNG", "قال Namgung", listOf("Namgung"), emptyMap()))
    }

    @Test fun glossaryTermIsNotAnOcrError() {
        val terms = mapOf("Murim" to "الموريم")
        assertTrue(LatinLeftoverRepair.unexplained("داخل Murim", emptyList(), terms).isEmpty())
    }

    @Test fun ordinaryEnglishWordIsNotAnOcrError() {
        // MT sometimes legitimately keeps a foreign/loan word; not a decoding failure.
        assertTrue(LatinLeftoverRepair.unexplained("هذا computer", emptyList(), emptyMap()).isEmpty())
    }

    @Test fun unconfidentOrMultipleLeftoversStayUncorrected() {
        // Not a dictionary word and no unique single-edit neighbour: never guess.
        assertNull(LatinLeftoverRepair.correctedSource("SAID QZXY", "قال Qzxy", emptyList(), emptyMap()))
        // Two independent leftovers: never pick one and retranslate (bounded, deterministic).
        assertNull(LatinLeftoverRepair.correctedSource("BY MYSELE AND MYSEF", "من قبل Mysele و Mysef", emptyList(), emptyMap()))
    }

    @Test fun tokenThatIsNotUniqueInTheSourceIsNotRewritten() {
        assertNull(LatinLeftoverRepair.correctedSource("MYSELE? MYSELE!", "Mysele Mysele", emptyList(), emptyMap()))
    }

    @Test fun correctionDoesNotTouchPartOfALongerWord() {
        // "MYSELE" inside "MYSELEPHONE" must not be replaced by a substring edit.
        assertNull(LatinLeftoverRepair.correctedSource("MYSELEPHONE", "Myselephone", emptyList(), emptyMap()))
    }

    @Test fun strongLegalLeftoverReportsTheTokenForDiagnostics() {
        val leftovers = LatinLeftoverRepair.unexplained(raw, emptyList(), emptyMap())
        assertEquals(1, leftovers.size)
        assertEquals("Mysele", leftovers.single().token)
        assertEquals("MYSELF", leftovers.single().suggestion)
    }
}
