package com.manhwalens.app.pipeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase B: rejoining one utterance that OCR split across detections.
 *
 * Reported RC8 case: inside a decorated circular narrative panel the recogniser produced
 * `"EVEN"` and, below it, `"THOUGH AE KNEw HOW HARD I WAS TRNINGo"`. There is no clean light
 * corridor between the two, so the visual bridge in `DialogueAssembler` refused and two
 * fragments were queued for translation as separate utterances.
 *
 * Every test here is about *not* over-joining as much as about joining: the expensive failure is
 * merging two speakers into one line.
 */
class BubbleReconstructorTest {

    private fun r(text: String, l: Int = 100, t: Int = 100, rr: Int = 400, b: Int = 140,
                  conf: Float = 0.8f, lines: Int = 1) =
        TextRegion(text, l, t, rr, b, conf, "en", null, lines)

    // ------------------------------------------------------------------ the reported case

    @Test
    fun evenAndThoughAreOneUtterance() {
        // "EVEN" sits on the same baseline as the rest of its clause, separated by a gutter.
        val even = r("EVEN", l = 100, t = 700, rr = 160, b = 736)
        val rest = r("THOUGH AE KNEw HOW HARD I WAS TRNINGo", l = 175, t = 698, rr = 600, b = 740)
        val (out, joins) = BubbleReconstructor.reconstruct(listOf(even, rest))
        assertEquals("the two halves must become one utterance", 1, out.size)
        assertEquals(1, joins.size)
        assertTrue("text must not lose either half: ${out.single().text}",
            out.single().text.contains("EVEN") && out.single().text.contains("THOUGH"))
        // The joined box must cover both fragments.
        assertEquals(100, out.single().left)
        assertEquals(600, out.single().right)
        assertEquals(698, out.single().top)
        assertEquals(740, out.single().bottom)
    }

    @Test
    fun stackedFragmentsOfOneSentenceAreJoined() {
        val first = r("EVEN", l = 120, t = 700, rr = 300, b = 730)
        val second = r("THOUGH HE KNEW HOW HARD I WAS TRYING", l = 120, t = 736, rr = 460, b = 766)
        val (out, joins) = BubbleReconstructor.reconstruct(listOf(first, second))
        assertEquals(1, out.size)
        assertTrue(joins.isNotEmpty())
    }

    // ------------------------------------------------------------------ geometry gates

    @Test fun farApartFragmentsAreNotJoined() {
        val a = r("EVEN", l = 100, t = 100, rr = 160, b = 136)
        val b = r("THOUGH HE KNEW", l = 100, t = 600, rr = 400, b = 636)
        val (out, _) = BubbleReconstructor.reconstruct(listOf(a, b))
        assertEquals("proximity must not be the evidence", 2, out.size)
    }

    @Test fun sideBySideSeparateBubblesAreNotJoined() {
        val left = r("I WILL NEVER", l = 40, t = 700, rr = 220, b = 740)
        val right = r("FORGIVE YOU", l = 600, t = 700, rr = 800, b = 740)
        val (out, _) = BubbleReconstructor.reconstruct(listOf(left, right))
        assertEquals(2, out.size)
    }

    @Test fun differentLanguagesAreNeverJoined() {
        val en = r("EVEN", l = 100, t = 700, rr = 160, b = 736)
        val ko = TextRegion("비록", 175, 698, 220, 740, 0.8f, "ko")
        assertNull(BubbleReconstructor.joinable(en, ko))
    }

    // ------------------------------------------------------------------ text gates

    @Test fun twoCompleteSentencesAreNotJoined() {
        val a = r("I AM FINE.", l = 100, t = 700, rr = 300, b = 740)
        val b = r("LEAVE ME ALONE!", l = 100, t = 746, rr = 320, b = 786)
        assertNull(BubbleReconstructor.joinable(a, b))
        val (out, _) = BubbleReconstructor.reconstruct(listOf(a, b))
        assertEquals(2, out.size)
    }

    @Test fun aCompletedSentenceIsNotContinuedByTheNextLine() {
        val a = r("THAT IS ENOUGH.", l = 100, t = 100, rr = 300, b = 140)
        val b = r("AND YOU KNOW IT", l = 100, t = 150, rr = 300, b = 190)
        // The opening "AND" would normally continue a clause; a finished statement wins.
        val (out, _) = BubbleReconstructor.reconstruct(listOf(a, b))
        assertEquals(2, out.size)
    }

    @Test fun noWordsAreInventedOrDropped() {
        val a = r("EVEN", l = 100, t = 700, rr = 160, b = 736)
        val b = r("THOUGH HE KNEW HOW HARD I WAS TRYING", l = 175, t = 698, rr = 600, b = 740)
        val (out, _) = BubbleReconstructor.reconstruct(listOf(a, b))
        val joined = out.single().text
        listOf("EVEN", "THOUGH", "HE", "KNEW", "HOW", "HARD", "I", "WAS", "TRYING").forEach {
            assertTrue("joined text lost '$it': $joined", joined.contains(it, ignoreCase = true))
        }
    }

    @Test fun joiningNeverDropsAWordWhenTheSeamIsAmbiguous() {
        // Both halves carry words; the joined form must contain every one of them.
        val a = r("THOUGH HE KNEW", l = 100, t = 700, rr = 300, b = 736)
        val b = r("AND SHE DID NOT CARE", l = 100, t = 742, rr = 380, b = 778)
        val (out, joins) = BubbleReconstructor.reconstruct(listOf(a, b))
        for (join in joins) {
            for (part in join.parts) {
                assertFalse("a join lost words from '${part.text}'",
                    com.manhwalens.app.ocr.CandidateRecovery.losesText(part, join.joined))
            }
        }
        assertTrue(out.isNotEmpty())
    }

    // ------------------------------------------------------------------ determinism

    @Test fun resultIsIndependentOfInputOrder() {
        val a = r("EVEN", l = 100, t = 700, rr = 160, b = 736)
        val b = r("THOUGH HE KNEW HOW HARD I WAS TRYING", l = 175, t = 698, rr = 600, b = 740)
        val c = r("I WILL NEVER FORGIVE", l = 100, t = 900, rr = 400, b = 940)
        val forward = BubbleReconstructor.reconstruct(listOf(a, b, c))
        val reverse = BubbleReconstructor.reconstruct(listOf(c, b, a))
        assertEquals("same number of utterances",
            forward.first.size, reverse.first.size)
        assertEquals("same joined texts",
            forward.first.map { it.text }.sorted(), reverse.first.map { it.text }.sorted())
        assertEquals(forward.second.size, reverse.second.size)
    }

    @Test fun singleRegionIsReturnedUntouched() {
        val only = r("STAY BACK!", 10, 20, 200, 60)
        val (out, joins) = BubbleReconstructor.reconstruct(listOf(only))
        assertEquals(listOf(only), out)
        assertTrue(joins.isEmpty())
    }

    @Test fun emptyInputIsSafe() {
        val (out, joins) = BubbleReconstructor.reconstruct(emptyList())
        assertTrue(out.isEmpty())
        assertTrue(joins.isEmpty())
    }

    @Test fun joinedConfidenceIsTheAverageOfFiniteParts() {
        val a = r("THOUGH HE KNEW", l = 100, t = 700, rr = 320, b = 752, conf = 0.6f)
        val b = r("HOW HARD I WAS TRYING", l = 100, t = 760, rr = 400, b = 812, conf = 0.8f)
        val (out, _) = BubbleReconstructor.reconstruct(listOf(a, b))
        assertEquals(0.7f, out.single().confidence, 0.001f)
    }

    @Test fun unknownConfidenceDoesNotPoisonTheJoin() {
        val a = TextRegion("THOUGH HE KNEW", 100, 700, 320, 752, Float.NaN, "en")
        val b = TextRegion("HOW HARD I WAS TRYING", 100, 760, 400, 812, 0.8f, "en")
        val (out, _) = BubbleReconstructor.reconstruct(listOf(a, b))
        assertTrue("NaN must not become the joined confidence", out.single().confidence.isFinite())
    }

    // ------------------------------------------------------------------ grammar table

    @Test fun openEndingWordsAreRecognised() {
        listOf("EVEN", "THOUGH", "BECAUSE", "THE", "OF", "AND", "BEFORE").forEach {
            assertTrue("'$it' cannot end an utterance", ContinuationGrammar.continues(it, "HE KNEW HOW HARD I WAS TRYING"))
        }
    }

    @Test fun aCompleteSentenceFollowedByANewCapitalIsNotAContinuation() {
        assertFalse(ContinuationGrammar.continues("I AM FINE.", "LEAVE ME ALONE"))
        assertFalse(ContinuationGrammar.continues("STAY BACK!", "NOBODY MOVE"))
    }

    @Test fun anUnfinishedClauseContinues() {
        assertTrue(ContinuationGrammar.continues("EVEN", "THOUGH HE KNEW"))
        assertTrue(ContinuationGrammar.continues("I KNEW IT WAS", "BECAUSE HE TOLD ME"))
    }

    @Test fun grammarNeverJoinsAcrossATerminalIntoANoun() {
        assertFalse(ContinuationGrammar.continues("HE LEFT.", "SWORD IN HAND"))
    }

    // ------------------------------------------------------------------ record

    @Test fun everyJoinCarriesItsEvidence() {
        val a = r("EVEN", l = 100, t = 700, rr = 160, b = 736)
        val b = r("THOUGH HE KNEW HOW HARD I WAS TRYING", l = 175, t = 698, rr = 600, b = 740)
        val (_, joins) = BubbleReconstructor.reconstruct(listOf(a, b))
        assertNotNull(joins.singleOrNull()?.evidence)
        assertEquals(2, joins.single().parts.size)
    }

    // ------------------------------------------------------------------ duplicate collapse
    //
    // Phase A made the overlay elect the complete reading for *display*. The translator still
    // receives every reading, so the same A/B/C case produced three translated blocks for one
    // line, two of them partial. Collapsing before joining closes that gap.

    @Test fun partialReadsOfOneRegionCollapseToTheCompleteReading() {
        val a = r("IL MAKE HIM REGRET", l = 60, t = 640, rr = 380, b = 686)
        val b = r("ILL MAKE HIM REGRET THROWING ME AWAY!", l = 60, t = 640, rr = 620, b = 690)
        val c = r("THROWING ME AWAY!", l = 300, t = 640, rr = 620, b = 690)
        val out = BubbleReconstructor.collapseDuplicates(listOf(a, b, c))
        assertEquals(1, out.size)
        assertEquals("ILL MAKE HIM REGRET THROWING ME AWAY!", out.single().text)
    }

    @Test fun duplicateCollapseIsOrderIndependent() {
        val a = r("IL MAKE HIM REGRET", l = 60, t = 640, rr = 380, b = 686)
        val b = r("ILL MAKE HIM REGRET THROWING ME AWAY!", l = 60, t = 640, rr = 620, b = 690)
        val c = r("THROWING ME AWAY!", l = 300, t = 640, rr = 620, b = 690)
        val expected = BubbleReconstructor.collapseDuplicates(listOf(a, b, c)).map { it.text }
        listOf(
            listOf(a, c, b), listOf(b, a, c), listOf(b, c, a),
            listOf(c, a, b), listOf(c, b, a),
        ).forEach { perm ->
            assertEquals("permutation $perm", expected, BubbleReconstructor.collapseDuplicates(perm).map { it.text })
        }
    }

    @Test fun separateBubblesAreNeverCollapsed() {
        val first = r("STAY BACK!", l = 60, t = 100, rr = 300, b = 150)
        val second = r("I WILL HANDLE THIS.", l = 60, t = 600, rr = 400, b = 650)
        assertEquals(2, BubbleReconstructor.collapseDuplicates(listOf(first, second)).size)
    }

    @Test fun collapsingNeverInventsOrDropsText() {
        val a = r("WARSHIP", l = 60, t = 640, rr = 200, b = 690)
        val b = r("WARSHIP APPROACHING", l = 60, t = 640, rr = 420, b = 690)
        val out = BubbleReconstructor.collapseDuplicates(listOf(a, b))
        assertEquals(1, out.size)
        // The winner must be one of the inputs verbatim, never a merge of the two.
        assertTrue(out.single().text == a.text || out.single().text == b.text)
        assertEquals("WARSHIP APPROACHING", out.single().text)
    }

    @Test fun singleRegionAndEmptyInputsAreSafe() {
        val one = r("HELLO.", l = 10, t = 10, rr = 100, b = 40)
        assertEquals(listOf(one), BubbleReconstructor.collapseDuplicates(listOf(one)))
        assertEquals(emptyList<TextRegion>(), BubbleReconstructor.collapseDuplicates(emptyList()))
    }

    @Test fun completenessPrefersCapturedTextOverConfidence() {
        val fragment = TextRegion("IL MAKE HIM REGRET", 60, 640, 380, 686, 0.99f, "en")
        val complete = TextRegion("ILL MAKE HIM REGRET THROWING ME AWAY!", 60, 640, 620, 690, 0.40f, "en")
        assertTrue(
            "a confident fragment must not outrank a complete line",
            BubbleReconstructor.completeness(complete) > BubbleReconstructor.completeness(fragment),
        )
    }
}
