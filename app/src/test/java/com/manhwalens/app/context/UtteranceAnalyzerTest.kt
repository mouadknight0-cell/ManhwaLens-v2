package com.manhwalens.app.context

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Group A (language understanding) and Group B (multiple senses / multi-word verbs).
 *
 * These assert the *analysis*, not a translation. A test that checks an Arabic string here would be
 * checking the model's output, which is unverifiable without a device and is explicitly out of
 * scope for this layer.
 */
class UtteranceAnalyzerTest {

    private fun analyze(text: String, hints: List<String> = emptyList()) =
        UtteranceAnalyzer.analyze(text, hints)

    // ---------------------------------------------------------------- Group A: polarity

    @Test fun negationIsDetectedFromTheSurface() {
        val frame = analyze("I will not forgive you.")
        assertEquals(Polarity.NEGATIVE, frame.polarity.value)
        assertEquals(EvidenceKind.EXPLICIT, frame.polarity.kind)
    }

    @Test fun contractionNegationCounts() {
        assertEquals(Polarity.NEGATIVE, analyze("I can't leave them behind.").polarity.value)
        assertEquals(Polarity.NEGATIVE, analyze("Don't look at me like that.").polarity.value)
    }

    @Test fun affirmationIsDetected() {
        assertEquals(Polarity.AFFIRMATIVE, analyze("I will forgive you.").polarity.value)
    }

    @Test fun negationIsNotCountedAsArithmetic() {
        // Two negators must still read as NEGATIVE, not cancel out.
        assertEquals(Polarity.NEGATIVE, analyze("Nobody said nothing.").polarity.value)
        assertEquals(Polarity.NEGATIVE, analyze("No one can not know.").polarity.value)
    }

    @Test fun negationInsideALongerWordIsNotANegator() {
        // "nothing" is a negator; "not" inside "notion" is not a token and must not match.
        assertEquals(Polarity.AFFIRMATIVE, analyze("That notion is wrong.").polarity.value)
    }

    // ---------------------------------------------------------------- Group A: mood

    @Test fun questionMarkMakesAQuestion() {
        assertEquals(Mood.QUESTION, analyze("Who are you?").mood.value)
    }

    @Test fun invertedAuxiliaryMakesAQuestionWithoutAQuestionMark() {
        assertEquals(Mood.QUESTION, analyze("Can you hear me").mood.value)
    }

    @Test fun exclamationIsDetected() {
        assertEquals(Mood.EXCLAMATION, analyze("Stay back!").mood.value)
    }

    @Test fun plainStatementIsAStatement() {
        assertEquals(Mood.STATEMENT, analyze("He opened the door.").mood.value)
    }

    // ---------------------------------------------------------------- Group A: tense

    @Test fun futureModalIsFuture() {
        assertEquals(Tense.FUTURE, analyze("I will handle this.").tense.value)
        assertEquals(Tense.FUTURE, analyze("I am going to handle this.").tense.value)
    }

    @Test fun irregularPastIsPast() {
        assertEquals(Tense.PAST, analyze("I became a mage.").tense.value)
        assertEquals(Tense.PAST, analyze("She said nothing.").tense.value)
    }

    @Test fun noTenseMarkerStaysUnmarkedRatherThanGuessed() {
        val frame = analyze("Mage.")
        assertEquals("a single noun must not acquire a tense", Tense.UNMARKED, frame.tense.value)
    }

    // ---------------------------------------------------------------- Group D: copular / gender

    @Test fun copularSelfDescriptionRaisesAGenderQuestion() {
        val frame = analyze("That day, I became a mage.")
        assertTrue(
            "a 'I became a X' sentence cannot settle the speaker's gender",
            frame.openQuestions.any { it.kind == OpenQuestion.Kind.GENDER },
        )
    }

    @Test fun genderQuestionAsksForStoryMemoryNotTheNextBubble() {
        // The narrowest useful level is asserted, because escalation cost depends on it.
        val question = analyze("I became a mage.").openQuestions.first { it.kind == OpenQuestion.Kind.GENDER }
        assertEquals(ContextLevel.STORY, question.smallestUsefulLevel)
    }

    @Test fun aSentenceWithoutASelfDescriptionRaisesNoGenderQuestion() {
        assertTrue(
            analyze("The knight arrived.").openQuestions.none { it.kind == OpenQuestion.Kind.GENDER },
        )
    }

    // ---------------------------------------------------------------- Group D: pronouns

    @Test fun thirdPersonPronounRaisesAReferentQuestion() {
        val frame = analyze("He opened the door.")
        assertTrue(frame.openQuestions.any { it.kind == OpenQuestion.Kind.PRONOUN_REFERENT })
    }

    @Test fun firstAndSecondPersonNeedNoReferent() {
        val frame = analyze("I told you the truth.")
        assertTrue(
            "first/second person is not a referent problem",
            frame.openQuestions.none { it.kind == OpenQuestion.Kind.PRONOUN_REFERENT },
        )
    }

    @Test fun pronounQuestionAsksForTheLevelThatCanActuallyAnswerIt() {
        // Corrected after the ablation record showed the engine escalating through every level and
        // resolving nothing: a gendered pronoun needs a *named antecedent*, which is story memory,
        // not the neighbouring bubble. Asking the cheap level first was not thrift, it was futility.
        val he = analyze("He ran.").openQuestions.first { it.kind == OpenQuestion.Kind.PRONOUN_REFERENT }
        assertEquals(ContextLevel.STORY, he.smallestUsefulLevel)
        // `it/they` are usually recoverable from the surrounding region.
        val itQ = analyze("It broke.").openQuestions.first { it.kind == OpenQuestion.Kind.PRONOUN_REFERENT }
        assertEquals(ContextLevel.REGION, itQ.smallestUsefulLevel)
    }

    @Test fun unnamedObjectNeedsStoryMemory() {
        val frame = analyze("I'll make him regret it.")
        assertTrue(
            "an unnamed `him` cannot be resolved from this sentence",
            frame.openQuestions.any { it.kind == OpenQuestion.Kind.ENTITY_IDENTITY },
        )
    }

    @Test fun ungenderedPronounsDoNotClaimAGender() {
        assertNull(UtteranceAnalyzer.genderOfPronoun("it"))
        assertNull(UtteranceAnalyzer.genderOfPronoun("they"))
        assertNull(UtteranceAnalyzer.genderOfPronoun("you"))
        assertEquals(GrammaticalGender.MASCULINE, UtteranceAnalyzer.genderOfPronoun("him"))
        assertEquals(GrammaticalGender.FEMININE, UtteranceAnalyzer.genderOfPronoun("her"))
    }

    // ---------------------------------------------------------------- Group B: phrasal verbs

    @Test fun phrasalVerbSenseIsRecorded() {
        val frame = analyze("I will never give up.")
        assertEquals("surrender/stop trying", frame.idioms["give up"]?.value)
    }

    @Test fun phrasalVerbRequiresTheWholePhrase() {
        // "give" alone is not "give up".
        assertTrue(analyze("Give me the sword.").idioms.isEmpty())
    }

    @Test fun throwingAwayIsUnderstoodAsAbandonment() {
        val frame = analyze("I'll make him regret throwing me away!")
        assertEquals("discarding/abandoning", frame.idioms["throwing away"]?.value)
    }

    @Test fun phrasalVerbTableHasNoDeadEntries() {
        assertTrue(UtteranceAnalyzer.knownPhrasalVerbs.isNotEmpty())
        UtteranceAnalyzer.knownPhrasalVerbs.forEach { phrase ->
            assertEquals("phrases are matched lowercase: $phrase", phrase.lowercase(), phrase)
            assertFalse("a phrase must be multi-word: $phrase", !phrase.contains(' '))
        }
    }

    // ---------------------------------------------------------------- Group A: subject / entities

    @Test fun pronounSubjectIsRecognised() {
        assertEquals("i", analyze("I opened it.").subject?.value)
    }

    @Test fun knownEntityIsTaggedAsStoryMemoryNotAsEvidenceFromThisSentence() {
        val frame = analyze("Jin drew his sword.", listOf("Jin"))
        val jin = frame.entities.firstOrNull { it.value == "Jin" }
        assertNotNull(jin)
        assertEquals(EvidenceKind.STORY_MEMORY, jin!!.kind)
    }

    // ---------------------------------------------------------------- honesty of the frame

    @Test fun emptyInputProducesAnEmptyFrame() {
        // An empty frame still has its three default axes (unmarked tense/mood/polarity); what it
        // must not have is any *entity, speaker or idiom* claim, which would be invention.
        val frame = analyze("")
        assertEquals("", frame.source)
        assertEquals(0, frame.entities.size + frame.idioms.size)
        assertEquals(null, frame.speaker)
        assertEquals(null, frame.subject)
        assertTrue(frame.openQuestions.isEmpty())
    }

    @Test fun aClearSentenceNeedsNoWiderContext() {
        // This is the §14 requirement: do not load history for something self-evident.
        val frame = analyze("Stay back!")
        assertTrue("'Stay back!' is self-contained", frame.openQuestions.isEmpty())
    }

    @Test fun hypothesisClaimsNeverOutrankExplicitOnes() {
        val explicit = Claim("Jin", EvidenceKind.EXPLICIT, 0.6, "text")
        val hypothesis = Claim("Jin", EvidenceKind.HYPOTHESIS, 0.99, "guess")
        assertTrue(explicit.outranks(hypothesis))
        assertFalse(hypothesis.outranks(explicit))
    }

    @Test fun storyMemoryIsOverridableButExplicitIsNot() {
        assertTrue(EvidenceKind.STORY_MEMORY.isOverridable)
        assertTrue(EvidenceKind.HYPOTHESIS.isOverridable)
        assertFalse(EvidenceKind.EXPLICIT.isOverridable)
        assertFalse(EvidenceKind.NEARBY_DIALOGUE.isOverridable)
    }

    @Test fun confidenceOutOfRangeIsRejected() {
        var rejected = false
        try { Claim("x", EvidenceKind.EXPLICIT, 1.2, "bad") } catch (e: IllegalArgumentException) { rejected = true }
        assertTrue("a confidence above 1.0 must not be constructible", rejected)
    }

    @Test fun frameConfidenceIgnoresHypotheses() {
        val frame = SemanticFrame(
            source = "x",
            entities = listOf(
                Claim("Jin", EvidenceKind.EXPLICIT, 0.9, "a"),
                Claim("Ghost", EvidenceKind.HYPOTHESIS, 0.1, "b"),
            ),
        )
        // The default axes (unmarked tense/polarity/mood, all 0.4) are decisive-but-default, so
        // the mean is (0.9 + 0.4*3) / 4. The point of the assertion is the *exclusion* of the 0.1
        // hypothesis: including it would give 0.42, and a guess must never move the score.
        assertEquals((0.9 + 0.4 + 0.4 + 0.4) / 4, frame.decisiveConfidence(), 0.0001)
    }

    // ---------------------------------------------------------------- RC9/RC8 regression inputs

    @Test fun rc8AndRc9AcceptanceSentencesStillAnalyse() {
        val cases = listOf(
            "ILL MAKE HIM REGRET THROWING ME AWAY!",
            "EVEN THOUGH HE KNEW HOW HARD I WAS TRYING",
            "THAT DAY, I BECAME A MAGE.",
            "YOU FILTHY BLUE-EYED BASTARD.",
            "I WANTED TO BE ACCEPTED AS FAMILY.",
        )
        cases.forEach { case ->
            val frame = analyze(case)
            assertEquals("analysis must not alter the source: $case", case, frame.source)
            assertTrue("every claim must carry a source: $case", frame.claims().all { it.source.isNotBlank() })
            assertTrue("every confidence in range: $case", frame.claims().all { it.confidence in 0.0..1.0 })
        }
    }

    @Test fun theInsultKeepsItsNegativityAsAnAttributeNotANegation() {
        // "filthy blue-eyed bastard" is an insult, but it is not *negated*: polarity must stay
        // affirmative or the Arabic would come out wrong.
        val frame = analyze("YOU FILTHY BLUE-EYED BASTARD.")
        assertEquals(Polarity.AFFIRMATIVE, frame.polarity.value)
    }
}
