package com.manhwalens.app.pipeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase C: restore a dropped apostrophe only when the contraction is the only reading.
 *
 * The reported failure was `"Ill make him regret throwing me away."` being handed to the translator
 * unchanged, producing "سوء جعله يندم رمي لي بعيدا!". Protection of `ILL` as a real word was
 * correct; what was missing was the evidence-based restoration.
 */
class ContractionRepairTest {

    private fun apply(text: String) = ContractionRepair.repair(text)

    // ------------------------------------------------------------------ required positives

    @Test fun illBeforeBareVerbBecomesContraction() {
        val (out, ledger) = apply("ILL MAKE HIM REGRET IT.")
        assertEquals("I'LL MAKE HIM REGRET IT.", out)
        assertEquals(1, ledger.size)
        assertEquals(RepairLedger.Decision.ACCEPTED, ledger.single().decision)
        assertEquals(RepairLedger.Kind.MISSING_APOSTROPHE, ledger.single().kind)
    }

    @Test fun theReportedSentenceIsCorrected() {
        val (out, _) = apply("Ill make him regret throwing me away.")
        assertEquals("I'll make him regret throwing me away.", out)
    }

    // ------------------------------------------------------------------ required negatives

    @Test fun attributiveIllBeforeANounIsLeftAlone() {
        val (out, ledger) = apply("THE ILL KNIGHT ARRIVED.")
        assertEquals("the ill knight arrives".uppercase().let { "THE ILL KNIGHT ARRIVED." }, out)
        assertTrue("no correction may be applied: $ledger",
            ledger.none { it.decision == RepairLedger.Decision.ACCEPTED })
    }

    @Test fun predicateIllIsLeftAlone() {
        val (out, ledger) = apply("I AM ILL.")
        assertEquals("I AM ILL.", out)
        assertNotNull(ledger.singleOrNull())
        assertEquals(RepairLedger.Decision.REFUSED, ledger.single().decision)
        assertEquals("predicate_adjective", ledger.single().evidence)
    }

    @Test fun hyphenatedCompoundIsNeverTouched() {
        val (out, ledger) = apply("ILL-FATED MAN.")
        assertEquals("ILL-FATED MAN.", out)
        assertTrue(ledger.isEmpty())
    }

    // ------------------------------------------------------------------ more negatives

    @Test fun illFollowedByPunctuationIsNotAContraction() {
        val (out, _) = apply("HE IS ILL.")
        assertEquals("HE IS ILL.", out)
    }

    @Test fun illAtEndOfSentenceIsNotAContraction() {
        val (out, _) = apply("SHE FELT ILL")
        assertEquals("SHE FELT ILL", out)
    }

    @Test fun existingApostropheIsNotDuplicated() {
        val (out, ledger) = apply("I'LL MAKE HIM REGRET IT.")
        assertEquals("I'LL MAKE HIM REGRET IT.", out)
        assertTrue(ledger.isEmpty())
    }

    @Test fun curlyApostropheIsAlsoRespected() {
        val (out, ledger) = apply("I\u2019LL MAKE HIM REGRET IT.")
        assertEquals("I\u2019LL MAKE HIM REGRET IT.", out)
        assertTrue(ledger.isEmpty())
    }

    @Test fun adverbAfterIllStillReadsAsAContraction() {
        val (out, _) = apply("ILL NEVER FORGIVE YOU.")
        assertEquals("I'LL NEVER FORGIVE YOU.", out)
    }

    @Test fun otherWordsAreUntouched() {
        val source = "HE WILL MAKE HIM REGRET IT."
        val (out, ledger) = apply(source)
        assertEquals(source, out)
        assertTrue(ledger.isEmpty())
    }

    // ------------------------------------------------------------------ casing

    @Test fun casingOfTheFirstLetterIsKept() {
        assertEquals("I'LL", ContractionRepair.repair("ILL MAKE").first.substringBefore(' '))
        assertEquals("I'll", ContractionRepair.repair("Ill make").first.substringBefore(' '))
        assertEquals("i'll", ContractionRepair.repair("ill make").first.substringBefore(' '))
    }

    // ------------------------------------------------------------------ ledger contract

    @Test fun refusalsAreRecordedNotSilent() {
        val (_, ledger) = apply("I AM ILL.")
        assertEquals(1, ledger.size)
        assertEquals(RepairLedger.Decision.REFUSED, ledger.single().decision)
        assertEquals("I AM ILL.".substringAfter("AM ").trimEnd('.'), ledger.single().raw)
    }

    @Test fun rawSourceIsPreservedInTheLedger() {
        val (_, ledger) = apply("Ill make him regret it.")
        assertEquals("Ill", ledger.single().raw)
        assertEquals("I'll", ledger.single().proposed)
        assertFalse("the ledger must keep the raw spelling", ledger.single().raw == ledger.single().proposed)
    }

    @Test fun entriesCarryEvidenceAndConfidence() {
        val (_, ledger) = apply("ILL MAKE HIM REGRET IT.")
        val entry = ledger.single()
        assertTrue(entry.evidence.isNotBlank())
        assertTrue("accepted proposals must clear the threshold", entry.confidence >= RepairLedger.MIN_ACCEPT)
    }

    @Test fun lowConfidenceIsNeverPromotedToAFact() {
        // The threshold is the only thing standing between a proposal and a rewrite.
        assertTrue(RepairLedger.accepts(RepairLedger.MIN_ACCEPT))
        assertFalse(RepairLedger.accepts(RepairLedger.MIN_ACCEPT - 0.01))
        assertFalse(RepairLedger.accepts(0.0))
    }

    @Test fun summaryIsTokenSizedAndCarriesCounts() {
        // One refusal ("I AM ILL") and one acceptance ("ILL MAKE ...").
        val (_, ledger) = apply("I AM ILL. ILL MAKE HIM REGRET IT.")
        val summary = RepairLedger.summary(ledger)
        assertEquals("1", summary["repairs_accepted"])
        assertEquals("1", summary["repairs_refused"])
        assertTrue(summary.keys.containsAll(listOf("repairs_accepted", "repairs_refused", "repair_kinds")))
        assertTrue("a summary must not contain a whole sentence",
            summary.getValue("repair_log").length < 200)
    }

    @Test fun emptyAndUnrelatedTextIsUntouched() {
        assertEquals("", apply("").first)
        assertEquals("STAY BACK!", apply("STAY BACK!").first)
        assertNull(ContractionRepair.propose("MAKE", null, "HIM", false))
    }

    @Test fun multipleIllTokensAreEachJudged() {
        val (out, ledger) = apply("I AM ILL. ILL MAKE HIM REGRET IT.")
        assertEquals("I AM ILL. I'LL MAKE HIM REGRET IT.", out)
        assertEquals(2, ledger.size)
        assertEquals(1, ledger.count { it.decision == RepairLedger.Decision.ACCEPTED })
        assertEquals(1, ledger.count { it.decision == RepairLedger.Decision.REFUSED })
    }

    // ------------------------------------------------------------------ interaction with the corrector

    @Test fun contractionRepairRunsOnTextTheCorrectorAlreadyLeftAlone() {
        // Phase 1 guarantees ILL is not rewritten to another word; this stage adds the apostrophe.
        assertEquals("ILL", ReadingEnglish.repair("ILL"))
        val (out, _) = apply(ReadingEnglish.repair("ILL MAKE HIM REGRET THROWING ME AWAY!"))
        assertTrue("expected the apostrophe to be restored: $out", out.startsWith("I'LL"))
    }
}
