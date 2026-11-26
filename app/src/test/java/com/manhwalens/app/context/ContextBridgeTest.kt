package com.manhwalens.app.context

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The bridge from settled fact to executed edit.
 *
 * Phase G.1 established that the engine could resolve a fact and the Arabic never changed. These
 * tests exist to make that failure impossible to reintroduce silently, and — more importantly — to
 * make sure the fix did not become a licence to rewrite text on thin evidence.
 *
 * The pattern each test follows is the release's own requirement: a resolved fact is not enough,
 * and a changed string is not success. So every positive case asserts *which* fact justified *which*
 * span, and every negative case asserts the specific code that blocked it.
 */
class ContextBridgeTest {

    private fun frame(
        source: String,
        referents: Map<String, Claim<String>> = emptyMap(),
        questions: List<OpenQuestion> = emptyList(),
    ) = SemanticFrame(source = source, pronounReferents = referents, openQuestions = questions)

    private fun context(
        settledAt: ContextLevel = ContextLevel.NONE,
        level: ContextLevel = ContextLevel.NONE,
        snippets: List<ContextSnippet> = emptyList(),
    ) = ContextPackage(
        frame = frame(""),
        level = level,
        snippets = snippets,
        settledAt = settledAt,
    )

    private fun genderSnippet(gender: String, confidence: Double = 0.95) = ContextSnippet(
        text = gender,
        kind = EvidenceKind.STORY_MEMORY,
        reason = "memory_gender",
        distance = 0,
        confidence = confidence,
        evidenceStrength = EvidenceKind.EXPLICIT,
    )

    // ---------------------------------------------------------------- the gap itself

    @Test fun aResolvedPronounProducesAnExecutableRewrite() {
        // Phase G.1's finding: this reference was recorded and never used. Now the recorded fact
        // reaches a rewrite, and the rewrite names the span it changed.
        val referents = mapOf("him" to Claim("Jin", EvidenceKind.NEARBY_DIALOGUE, 0.9, "adjacent_dialogue"))
        val decision = ContextBridge.decide(
            frame = frame("Mira warned him about the gate.", referents = referents),
            context = context(settledAt = ContextLevel.REGION, level = ContextLevel.REGION),
        )

        assertEquals(ContextBridge.Code.CANDIDATE_GENERATED, decision.code)
        val rewrite = decision.rewrite
        assertNotNull("the fact must reach an actual edit", rewrite)
        assertEquals("Mira warned Jin about the gate.", rewrite!!.rewritten)
        assertEquals("the original is preserved, never overwritten", "Mira warned him about the gate.", rewrite.original)
        assertEquals("him", rewrite.replaced)
        assertEquals("Jin", rewrite.replacement)
        assertTrue(decision.trail.contains(ContextBridge.Code.CONTEXT_RESOLVED))
        assertTrue(decision.trail.contains(ContextBridge.Code.CANDIDATE_GENERATED))
    }

    @Test fun theRewriteIsTheOnlyThingThatChangedAndItKeepsItsProvenance() {
        val referents = mapOf("him" to Claim("Jin", EvidenceKind.NEARBY_DIALOGUE, 0.9, "adjacent_dialogue"))
        val rewrite = ContextBridge.decide(
            frame("Mira warned him about the gate.", referents),
            context(settledAt = ContextLevel.REGION),
        ).rewrite!!

        // The fact travels with the edit, so a report can explain the change without the code.
        assertEquals(ContextBridge.FactKind.PRONOUN_REFERENT, rewrite.fact.kind)
        assertEquals("adjacent_dialogue", rewrite.fact.source)
        assertEquals(0.9, rewrite.fact.confidence, 1e-9)
        assertTrue(rewrite.reason.startsWith("pronoun_clarified:him->Jin"))
    }

    // ---------------------------------------------------------------- resolved is not actionable

    @Test fun resolvedGenderWithNoExecutableRuleReportsNoActionableFact() {
        // The audit's MAGE case. The gender is settled and nothing can use it: Arabic first person
        // does not mark gender, so "making it feminine" would be inventing a linguistic fact.
        val source = "That day, I became a mage."
        val decision = ContextBridge.decide(
            frame = frame(source),
            context = context(settledAt = ContextLevel.STORY, level = ContextLevel.STORY, snippets = listOf(genderSnippet("feminine"))),
        )

        assertEquals(ContextBridge.Code.NO_ACTIONABLE_SEMANTIC_FACT, decision.code)
        assertNull("nothing may be edited on an unusable fact", decision.rewrite)
        assertTrue(
            "the refusal must name the missing capability, not just fail: ${decision.reasons}",
            decision.reasons.any { it.startsWith("gender_unusable:") },
        )
        assertEquals("first_person_arabic_is_gender_neutral", decision.blockedFacts.single().blockedBy)
    }

    @Test fun resolvedIsNeverReportedAsAccepted() {
        // The exact conflation §8 forbids: a settled fact must not read as an accepted change.
        val decision = ContextBridge.decide(
            frame = frame("That day, I became a mage."),
            context = context(settledAt = ContextLevel.STORY, snippets = listOf(genderSnippet("feminine"))),
        )
        assertFalse(decision.code == ContextBridge.Code.CANDIDATE_ACCEPTED)
        assertTrue("the trail must show resolution without acceptance", decision.trail.contains(ContextBridge.Code.CONTEXT_RESOLVED))
        assertFalse(decision.trail.contains(ContextBridge.Code.CANDIDATE_ACCEPTED))
    }

    @Test fun aClearSentenceNeverPaysForContextItDoesNotNeed() {
        val decision = ContextBridge.decide(frame("Stay back!"), context())
        assertEquals(ContextBridge.Code.NO_OPEN_QUESTION, decision.code)
        assertTrue(decision.facts.isEmpty())
        assertNull(decision.rewrite)
    }

    @Test fun glossaryKnowledgeIsRecordedAsUnusableRatherThanSwappedBlindly() {
        // There is no alignment between the English term and the Arabic token, so a swap would be a
        // guess about which Arabic word to touch.
        val decision = ContextBridge.decide(
            frame = frame("The Awakening has begun."),
            context = context(),
            glossary = mapOf("Awakening" to "الصحوة"),
        )
        val term = decision.facts.single { it.kind == ContextBridge.FactKind.TERM }
        assertFalse(term.realizable)
        assertEquals("no_arabic_anchor", term.blockedBy)
        assertNull(decision.rewrite)
    }

    // ---------------------------------------------------------------- evidence gates

    @Test fun aHypothesisNeverBecomesAnEdit() {
        val referents = mapOf("him" to Claim("Jin", EvidenceKind.HYPOTHESIS, 0.95, "guessed"))
        val decision = ContextBridge.decide(frame("Mira warned him about the gate.", referents), context())
        assertNull("a guess must never change the text", decision.rewrite)
        assertEquals("hypothesis_only", decision.blockedFacts.single().blockedBy)
    }

    @Test fun storyMemoryAloneIsTooWeakToChangeTheSentence() {
        val referents = mapOf("him" to Claim("Jin", EvidenceKind.STORY_MEMORY, 0.95, "memory"))
        val decision = ContextBridge.decide(frame("Mira warned him about the gate.", referents), context())
        assertNull(decision.rewrite)
        assertEquals("evidence_too_weak", decision.blockedFacts.single().blockedBy)
    }

    @Test fun aLowConfidenceClaimDoesNotQualify() {
        val referents = mapOf("him" to Claim("Jin", EvidenceKind.NEARBY_DIALOGUE, 0.45, "weak"))
        val decision = ContextBridge.decide(frame("Mira warned him about the gate.", referents), context())
        assertNull(decision.rewrite)
        assertEquals("low_confidence", decision.blockedFacts.single().blockedBy)
    }

    @Test fun aReferentThatIsNotANameNeverReplacesAPronoun() {
        // "the man" restricts differently from "him"; the difference is meaning, not style.
        for (bad in listOf("the man", "someone", "a stranger", "they")) {
            val referents = mapOf("him" to Claim(bad, EvidenceKind.NEARBY_DIALOGUE, 0.9, "nearby"))
            val decision = ContextBridge.decide(frame("Mira warned him about the gate.", referents), context())
            assertNull("'$bad' must not be substituted for a pronoun", decision.rewrite)
            assertEquals("referent_not_a_name", decision.blockedFacts.single().blockedBy)
        }
    }

    @Test fun anAmbiguousPronounPositionIsRefused() {
        // With two occurrences, "which one did we clarify?" is a question the report cannot answer.
        val referents = mapOf("him" to Claim("Jin", EvidenceKind.NEARBY_DIALOGUE, 0.9, "nearby"))
        val decision = ContextBridge.decide(frame("She warned him, then left him there.", referents), context())
        assertNull(decision.rewrite)
        assertEquals("pronoun_ambiguous_position", decision.blockedFacts.single().blockedBy)
    }

    @Test fun aReflexiveOrUnsupportedPronounIsRefused() {
        for (bad in listOf("himself", "his own", "whom")) {
            val referents = mapOf(bad to Claim("Jin", EvidenceKind.NEARBY_DIALOGUE, 0.9, "nearby"))
            val decision = ContextBridge.decide(frame("He hurt $bad badly.", referents), context())
            assertEquals("unsupported_pronoun", decision.blockedFacts.single().blockedBy)
        }
    }

    // ---------------------------------------------------------------- verification

    @Test fun theVerifierAcceptsonlyASingleTokenSwap() {
        val rewrite = ContextBridge.SourceRewrite(
            original = "Mira warned him about the gate.",
            rewritten = "Mira warned Jin about the gate.",
            spanStart = 12, spanEnd = 15,
            replaced = "him", replacement = "Jin",
            fact = ContextBridge.ActionableFact(
                ContextBridge.FactKind.PRONOUN_REFERENT, "him", "Jin",
                EvidenceKind.NEARBY_DIALOGUE, 0.9, "nearby", true,
            ),
            reason = "pronoun_clarified",
        )
        val result = RewriteVerification.verify(rewrite)
        assertTrue(result.reasons.joinToString(), result.ok)
        assertTrue(result.reasons.contains("token_count_preserved"))
    }

    @Test fun theVerifierRejectsAnExtraWordSmuggledIn() {
        // The verifier's job is to catch an edit that "improves" the sentence by adding meaning.
        val rewrite = ContextBridge.SourceRewrite(
            original = "Mira warned him about the gate.",
            rewritten = "Mira warned the young Jin about the gate.",
            spanStart = 12, spanEnd = 15,
            replaced = "him", replacement = "Jin",
            fact = ContextBridge.ActionableFact(
                ContextBridge.FactKind.PRONOUN_REFERENT, "him", "Jin",
                EvidenceKind.NEARBY_DIALOGUE, 0.9, "nearby", true,
            ),
            reason = "pronoun_clarified",
        )
        val result = RewriteVerification.verify(rewrite)
        assertFalse("added content must be rejected", result.ok)
    }

    @Test fun theVerifierRejectsAReplacementThatIsNotTheResolvedEntity() {
        val rewrite = ContextBridge.SourceRewrite(
            original = "Mira warned him about the gate.",
            rewritten = "Mira warned Jae about the gate.",
            spanStart = 12, spanEnd = 15,
            replaced = "him", replacement = "Jae",
            fact = ContextBridge.ActionableFact(
                ContextBridge.FactKind.PRONOUN_REFERENT, "him", "Jin",
                EvidenceKind.NEARBY_DIALOGUE, 0.9, "nearby", true,
            ),
            reason = "pronoun_clarified",
        )
        assertEquals("replacement_not_the_resolved_entity", RewriteVerification.verify(rewrite).reason)
    }

    @Test fun aRewriteThatChangesNothingIsNotACandidate() {
        val rewrite = ContextBridge.SourceRewrite(
            original = "Mira warned him.",
            rewritten = "Mira warned him.",
            spanStart = 12, spanEnd = 15,
            replaced = "him", replacement = "him",
            fact = ContextBridge.ActionableFact(
                ContextBridge.FactKind.PRONOUN_REFERENT, "him", "him",
                EvidenceKind.NEARBY_DIALOGUE, 0.9, "nearby", true,
            ),
            reason = "nothing",
        )
        assertEquals("no_change", RewriteVerification.verify(rewrite).reason)
    }

    // ---------------------------------------------------------------- possessive and case

    @Test fun aPossessiveIsClarifiedAndTheNameKeepsItsCapital() {
        val referents = mapOf("his" to Claim("jin", EvidenceKind.NEARBY_DIALOGUE, 0.9, "nearby"))
        val decision = ContextBridge.decide(frame("Mira took his sword.", referents), context())
        // A lowercase referent value is still a name once capitalised; "jin" alone would be a typo
        // in the output, not a clarification.
        assertEquals(ContextBridge.Code.CANDIDATE_GENERATED, decision.code)
        assertEquals("Mira took Jin sword.", decision.rewrite!!.rewritten)
    }

    @Test fun theNameIsNotCapitalisedMidSentenceByAccident() {
        // Guards the capitalisation path: the replacement must be a name, and names are capitalised.
        assertTrue(ReferentClarification.isName("Jin"))
        assertTrue(ReferentClarification.isName("Mira"))
        assertFalse(ReferentClarification.isName("jin"))
        assertFalse(ReferentClarification.isName("the"))
    }
}
