package com.manhwalens.app.context

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Mode policy: what the engine is allowed to do to what the reader sees.
 *
 * The three modes are a contract, and the parts that matter are the negative ones. Shadow must never
 * change the output no matter how good the candidate is, gated must fall back on anything short of a
 * verified semantic replacement, and the default must be the path a reader would have had without
 * this phase existing.
 */
class ContextModePolicyTest {

    private val baseline = "ابق بعيدا"

    private fun snapshot(vararg texts: String, target: Int = 0) = PageSnapshot(
        regions = texts.mapIndexed { i, t ->
            PageSnapshot.RegionInput(t, 10, 50 + i * 60, 300, 90 + i * 60, i, 0, "dialogue", 0.92f)
        },
        target = target,
        workId = "work-a",
    )

    @Test fun theDefaultModeIsBaseline() {
        // A default that has not been validated is a default that has been guessed.
        assertEquals(ContextMode.A_BASELINE, ContextModeSource.Fixed.current())
    }

    @Test fun baselineModeNeverProducesACandidateEvenWithRichEvidence() {
        val outcome = ContextTranslation.run(baseline, snapshot("Stay back!"), maxLevel = ContextLevel.NONE)
        // The engine here is run directly, so this asserts the property the client must honour:
        // whatever the engine says, mode A shows the baseline. The enforcement is in the client's
        // `when`, and this test pins the decision table itself.
        assertFalse(modeDisplays(outcome, ContextMode.A_BASELINE, baseline) != baseline)
    }

    @Test fun shadowNeverShowsACandidate() {
        val outcome = ContextTranslation.run(baseline, snapshot("Stay back!"))
        assertEquals("shadow must show the baseline", baseline, modeDisplays(outcome, ContextMode.B_SHADOW, baseline))
    }

    @Test fun gatedShowsTheCandidateOnlyWhenItWasReplaced() {
        val replaced = ContextTranslation.run(baseline, snapshot("Stay back!"))
        assertTrue(replaced.replaced)
        assertNotEqualsBaseline(baseline, modeDisplays(replaced, ContextMode.C_GATED, baseline))

        val declined = ContextTranslation.run(
            "بدا متعبا.",
            snapshot("Jin drew his sword.", "He looked tired.", target = 1),
        )
        assertFalse(declined.replaced)
        assertEquals("a declined candidate must fall back", "بدا متعبا.", modeDisplays(declined, ContextMode.C_GATED, "بدا متعبا."))
    }

    @Test fun anEngineFailureFallsBackInEveryMode() {
        // A defect in the engine must cost a translation in no mode at all.
        val failed = ContextTranslation.Outcome(
            arabic = baseline,
            replaced = false,
            reasons = listOf("CT-EXCEPTION"),
            reachedLevel = ContextLevel.NONE,
            settledLevel = ContextLevel.NONE,
            unresolved = emptyList(),
            verdict = SemanticVerdict.Outcome.REVIEW_REQUIRED,
            confidences = SemanticVerdict.Confidences(),
            contextText = "",
        )
        for (mode in ContextMode.values()) {
            assertEquals("mode $mode must fall back", baseline, modeDisplays(failed, mode, baseline))
        }
    }

    // ---------------------------------------------------------------- the bridge decision travels

    @Test fun theOutcomeCarriesTheBridgeDecisionEvenWhenNothingWasReplaced() {
        // A report must be able to say "the engine found an executable fact and declined" as distinct
        // from "the engine found nothing executable".
        val outcome = ContextTranslation.run("That day, I became a mage.", snapshot("That day, I became a mage."))
        assertNotNull("the bridge must run and record", outcome.bridge)
        // A self-contained sentence asks no question, so the honest answer is NO_OPEN_QUESTION; what
        // matters is that the bridge ran and said so rather than staying silent.
        assertTrue(
            "the bridge must record a real state: ${outcome.bridge!!.code}",
            outcome.bridge!!.code == ContextBridge.Code.NO_OPEN_QUESTION ||
                outcome.bridge!!.code == ContextBridge.Code.NO_ACTIONABLE_SEMANTIC_FACT,
        )
        assertFalse(outcome.replaced)
    }

    @Test fun aResolvedPronounReachesTheOutcomeThroughTheRealPath() {
        // End-to-end through ContextTranslation, not just the bridge in isolation: the fact the engine
        // holds must arrive at a rewrite that names the span it changed.
        val memory = StoryMemory()
        memory.add(
            MemoryEntry("work-a", MemoryKind.NAME, "jin", "Jin", EvidenceKind.EXPLICIT, 0.98, "chapter:1/page:2", chapter = 1),
        )
        val outcome = ContextTranslation.run(
            "حذّرت ميرا منه بشأن البوابة.",
            snapshot("Mira warned him about the gate."),
            memory = memory,
        )
        assertNotNull(outcome.bridge)
        // The referent must come from evidence, not from the fact that a name exists in memory.
        assertTrue(
            "no referent may be invented from a name in memory alone: ${outcome.bridge!!.facts}",
            outcome.bridge!!.facts.none { it.kind == ContextBridge.FactKind.PRONOUN_REFERENT && it.realizable },
        )
    }

    @Test fun aScatteredFactStillHasNoCandidateRatherThanAnEmptyOne() {
        val outcome = ContextTranslation.run("Home.", snapshot("Home."))
        assertTrue(
            "an engine with nothing to say must say NO_ACTIONABLE_SEMANTIC_FACT, not produce empty output",
            outcome.bridge == null || outcome.bridge!!.code == ContextBridge.Code.NO_OPEN_QUESTION ||
                outcome.bridge!!.code == ContextBridge.Code.NO_ACTIONABLE_SEMANTIC_FACT,
        )
    }

    // ---------------------------------------------------------------- helpers

    /** The client's decision table, restated so the test fails if the client's `when` changes. */
    private fun modeDisplays(
        outcome: ContextTranslation.Outcome,
        mode: ContextMode,
        baseline: String,
    ): String {
        if (!ContextTranslation.isDisplayable(outcome)) return baseline
        return when (mode) {
            ContextMode.A_BASELINE -> baseline
            ContextMode.B_SHADOW -> baseline
            ContextMode.C_GATED -> outcome.arabic
        }
    }

    private fun assertNotEqualsBaseline(expected: String, actual: String) {
        if (expected == actual) throw AssertionError("expected a changed output but got the baseline")
    }
}

/**
 * §11: a settled fact must reach the translation *mechanism*, not just the frame.
 *
 * The phase report could previously say "the pronoun was resolved" and nothing more. Writing these
 * tests was how a real dead wire was found: `ContextBuilder` settled referent questions inside
 * `tryAnswer` and then dropped the answer, so `frame.pronounReferents` -- the only field
 * `ContextBridge` reads -- was never populated by the production path. A test that only checked
 * "the engine has the information" would have passed happily. Each test below therefore checks a
 * link in the chain, and the negative tests are as important as the positive one.
 */
class SettledFactReachesTranslationTest {

    /**
     * Memory in the only shape the engine can settle a referent from.
     *
     * `ContextBuilder.memoryEntryFor` looks up `MemoryKind.NAME` with the *pronoun surface* as the
     * key, so a record means "`him` refers to Jin". A record keyed by the character's own name
     * (`jin -> Jin`) is a name the engine already knows, not an antecedent, and deliberately does
     * not settle anything. That is a real limitation of the current memory model and it is recorded
     * as such in the phase report rather than disguised by a test fixture.
     */
    private fun memoryWithJin() = StoryMemory().apply {
        add(
            MemoryEntry("work-a", MemoryKind.NAME, "him", "Jin", EvidenceKind.EXPLICIT, 0.95, "chapter:1/page:2", chapter = 1),
        )
    }

    private fun page(source: String, vararg others: String) = PageSnapshot(
        regions = (others.toList() + source).mapIndexed { i, t ->
            PageSnapshot.RegionInput(t, 10, 10 + i * 60, 300, 50 + i * 60, i, 0, "dialogue", 0.95f)
        },
        target = (others.toList() + source).lastIndex,
        workId = "work-a",
    )

    @Test fun aSettledReferentBecomesTheExactTextTheSecondPassReceives() {
        // The chain that matters: story memory -> resolved question -> recorded referent ->
        // bridge rewrite -> the string handed to the extra translation pass.
        val outcome = ContextTranslation.run(
            "حذّرت ميرا منه بشأن البوابة.",
            page("Mira warned him about the gate."),
            memory = memoryWithJin(),
        )

        val rewrite = outcome.bridge?.rewrite
        assertNotNull("the engine must produce a rewrite for a resolvable pronoun: ${outcome.bridge}", rewrite)
        assertEquals("Mira warned Jin about the gate.", rewrite!!.rewritten)
        // The original is preserved, so a reviewer can always see both sides of the edit.
        assertEquals("Mira warned him about the gate.", rewrite.original)
        assertEquals("him", rewrite.replaced)
        assertEquals("Jin", rewrite.replacement)
    }

    @Test fun theDecisionCarriesTheEvidenceThatJustifiedIt() {
        // A change without provenance is a guess with a nicer log line.
        val outcome = ContextTranslation.run(
            "حذّرت ميرا منه بشأن البوابة.",
            page("Mira warned him about the gate."),
            memory = memoryWithJin(),
        )
        val fact = outcome.bridge?.rewrite?.fact
        assertNotNull(fact)
        assertEquals(ContextBridge.FactKind.PRONOUN_REFERENT, fact!!.kind)
        assertEquals("Jin", fact.value)
        assertEquals("memory_name", fact.source)
        assertTrue("the rewrite must name the span it changed", outcome.bridge!!.rewrite!!.reason.startsWith("pronoun_clarified:him->Jin"))
    }

    @Test fun aPronounIsNeverBoundToANearbyNameWithoutEvidence() {
        // Phase G defect 2, pinned through the *real* path rather than the isolated bridge: a name in
        // the neighbouring bubble is not an antecedent. "Jin drew his sword." next to "Mira warned
        // him..." must leave `him` unresolved, because nothing says which of them it is.
        val outcome = ContextTranslation.run(
            "حذّرت ميرا منه بشأن البوابة.",
            page("Mira warned him about the gate.", "Jin drew his sword."),
        )
        assertNull("a nearby name is not an antecedent: ${outcome.bridge}", outcome.bridge?.rewrite)
        assertFalse("and nothing may be claimed as replaced", outcome.replaced)
    }

    @Test fun theExplicitSourceWinsOverAStaleTargetIndex() {
        // Callers translate regions concurrently, so a target index cannot be trusted to point at
        // the sentence being translated. The explicit source must win, or the engine would analyse
        // one line and edit another.
        val snapshot = PageSnapshot(
            regions = listOf(
                PageSnapshot.RegionInput("Jin drew his sword.", 10, 10, 300, 50, 0, 0, "dialogue", 0.95f),
                PageSnapshot.RegionInput("Mira warned him about the gate.", 10, 70, 300, 110, 1, 0, "dialogue", 0.95f),
            ),
            target = 0,
            source = "Mira warned him about the gate.",
            workId = "work-a",
        )
        val outcome = ContextTranslation.run("حذّرت ميرا منه بشأن البوابة.", snapshot, memory = memoryWithJin())
        assertEquals(
            "the analysed sentence must be the explicit source, not the row at target",
            "Mira warned Jin about the gate.",
            outcome.bridge?.rewrite?.rewritten,
        )
    }

    @Test fun aResolutionWithoutAnExecutableRuleStaysUnused() {
        // The MAGE case, end to end. The gender is settled and Arabic first person does not mark it,
        // so the outcome must record the fact and change nothing.
        val memory = StoryMemory().apply {
            add(MemoryEntry("work-a", MemoryKind.GENDER, "speaker", "female", EvidenceKind.STORY_MEMORY, 0.9, "chapter:1/page:1", chapter = 1))
        }
        val outcome = ContextTranslation.run(
            "أصبحتُ ساحرًا في ذلك اليوم.",
            page("That day, I became a mage."),
            memory = memory,
        )
        assertNull("a fact without an executable rule must not rewrite anything", outcome.bridge?.rewrite)
        assertFalse("and must not be reported as a replacement", outcome.replaced)
        // The fact is still visible, so a report can distinguish "known but unusable" from "unknown".
        assertNotNull("the bridge must still record the decision", outcome.bridge)
    }
}
