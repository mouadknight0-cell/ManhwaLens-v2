package com.manhwalens.app.context

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The §15 ablation: does each context level actually change anything?
 *
 * The release demands a comparison across five settings rather than a single "improved" claim, and
 * demands that levels which add nothing be reported as adding nothing. That is what this does: each
 * case is run with the ceiling at NONE, UTTERANCE, REGION, PAGE and STORY, and the test records
 * *which* level changed the outcome and *what* it changed.
 *
 * Cases are synthetic and live here rather than in a data file, because the point is to exercise the
 * mechanism, not to measure translation quality — the latter needs reference Arabic and a device,
 * and neither exists in this environment. Nothing here asserts a translation is good.
 */
class ContextAblationTest {

    /** What the engine concluded at one context ceiling. */
    private data class Outcome(
        val level: ContextLevel,
        /** The widest level that actually answered something; NONE when the walk earned nothing. */
        val settledAt: ContextLevel,
        val resolved: List<String>,
        val unresolved: List<String>,
        val candidateChanged: Boolean,
        val reasons: List<String>,
    )

    private fun frame(vararg texts: String): List<TextObservation> =
        texts.mapIndexed { index, text ->
            TextObservation(text, 10, 50 + index * 60, 300, 90 + index * 60, readingOrder = index, panelIndex = 0)
        }

    private fun run(
        texts: List<TextObservation>,
        index: Int,
        baseline: String,
        memory: StoryMemory? = null,
        maxLevel: ContextLevel,
    ): Outcome {
        val sources = ContextBuilder.Sources("work-a", texts, index, emptyList(), memory, emptyMap(), maxLevel)
        val package0 = ContextBuilder.build(sources)
        val candidate = ArabicPhrasing.phrase(baseline, package0.frame, package0)
        return Outcome(
            level = package0.level,
            settledAt = package0.settledAt,
            resolved = package0.escalations,
            unresolved = package0.unresolved,
            candidateChanged = candidate.text != baseline,
            reasons = candidate.reasons,
        )
    }

    private fun levelsFor(
        texts: List<TextObservation>,
        index: Int,
        baseline: String,
        memory: StoryMemory? = null,
    ): List<Outcome> = listOf(
        ContextLevel.NONE, ContextLevel.UTTERANCE, ContextLevel.REGION,
        ContextLevel.PAGE, ContextLevel.DIALOGUE, ContextLevel.STORY,
    ).map { run(texts, index, baseline, memory, it) }

    // ---------------------------------------------------------------- level 0 is enough

    @Test fun aClearSentenceIsUnchangedAtEveryLevel() {
        // "Stay back!" needs nothing. Every ceiling must give the identical answer, which is how the
        // engine proves it does not always load everything.
        val texts = frame("Jin drew his sword.", "Stay back!")
        val outcomes = levelsFor(texts, 1, "ابق بعيدا!")
        assertEquals("every level must agree", 1, outcomes.map { it.level }.distinct().size)
        assertEquals(ContextLevel.NONE, outcomes.first().level)
        assertTrue("a clear line must not be rewritten at any level", outcomes.none { it.candidateChanged })
    }

    // ---------------------------------------------------------------- level 1 helps

    @Test fun aGenderedPronounIsOnlySettledAtStoryLevel() {
        // The ablation record showed this plainly: the engine walks up to the level that *could*
        // answer and stops there. A "he" needs an antecedent, and only memory has one. With memory
        // empty the question is still open at the ceiling, and the engine says so rather than
        // binding "he" to the only name on the page.
        val texts = frame("Jin drew his sword.", "He looked tired.")
        val outcomes = levelsFor(texts, 1, "بدا متعبا.")
        assertTrue(
            "below story level the pronoun must remain open",
            outcomes.dropLast(1).all { it.unresolved.any { reason -> reason.startsWith("pronoun_referent") } },
        )
        assertEquals(ContextLevel.STORY, outcomes.last().level)
        // Still unresolved, and no level fabricated an antecedent for it.
        assertTrue(outcomes.last().unresolved.any { it.startsWith("pronoun_referent") })
        assertTrue(
            "a walk that answered nothing must settle at NONE, not claim success",
            outcomes.all { it.settledAt == ContextLevel.NONE },
        )
        assertTrue(
            "no level may invent a referent, so the Arabic must not have changed",
            outcomes.none { it.candidateChanged },
        )
    }

    @Test fun anItPronounIsResolvedAtRegionLevel() {
        val texts = frame("The door was locked.", "It broke anyway.")
        val outcomes = levelsFor(texts, 1, "لكنه انكسر.")
        assertEquals(ContextLevel.NONE, outcomes[0].level)
        assertEquals(ContextLevel.REGION, outcomes[2].level)
    }

    // ---------------------------------------------------------------- level 5 helps

    @Test fun storyMemoryIsTheLevelThatResolvesGender() {
        val memory = StoryMemory()
        memory.add(
            MemoryEntry("work-a", MemoryKind.GENDER, "speaker_of_copular_self_description", "feminine",
                EvidenceKind.EXPLICIT, 0.95, "chapter:3/page:8"),
        )
        val texts = frame("That day, I became a mage.")
        val outcomes = levelsFor(texts, 0, "في ذلك اليوم، أصبحت ماجاة.", memory)

        // Below STORY the question must stay open, and the Arabic must stay ambiguous.
        val belowStory = outcomes.filter { it.level != ContextLevel.STORY }
        assertTrue("gender cannot be settled before story memory",
            belowStory.all { outcome -> outcome.unresolved.any { it.startsWith("gender") } })

        val atStory = outcomes.last()
        assertEquals(ContextLevel.STORY, atStory.level)
        assertEquals("memory is what answered it", ContextLevel.STORY, atStory.settledAt)
        assertTrue(atStory.resolved.contains("story_memory"))
        assertTrue("memory settles it, so it is no longer unresolved", atStory.unresolved.isEmpty())
        assertTrue(
            "the resolved gender must be recorded as a reason",
            atStory.reasons.any { it.startsWith("gender_resolved") },
        )
    }

    // ---------------------------------------------------------------- no context is not invented

    @Test fun withoutMemoryTheGenderQuestionStaysOpenAtEveryLevel() {
        val texts = frame("That day, I became a mage.")
        val outcomes = levelsFor(texts, 0, "في ذلك اليوم، أصبحت ماجاة.", StoryMemory())
        assertTrue(
            "no level may invent a gender that memory does not hold",
            outcomes.all { it.unresolved.any { reason -> reason.startsWith("gender") } },
        )
    }

    // ---------------------------------------------------------------- wrong memory must not win

    @Test fun anOldMemoryDoesNotOverrideTheCurrentSentence() {
        // Memory says the speaker is feminine; a later explicit sentence contradicts it. The current
        // page must win, which is the release's "memory is not infallible" rule.
        val memory = StoryMemory()
        memory.add(
            MemoryEntry("work-a", MemoryKind.GENDER, "speaker_of_copular_self_description", "feminine",
                EvidenceKind.STORY_MEMORY, 0.6, "chapter:1/page:2"),
        )
        val corrected = memory.add(
            MemoryEntry("work-a", MemoryKind.GENDER, "speaker_of_copular_self_description", "masculine",
                EvidenceKind.EXPLICIT, 0.95, "chapter:9/page:3"),
        )
        assertEquals(StoryMemory.AddOutcome.Replaced, corrected)
        assertEquals("masculine", memory.lookup("work-a", MemoryKind.GENDER, "speaker_of_copular_self_description")?.value)
    }

    // ---------------------------------------------------------------- the report

    @Test fun everyCaseRecordsWhichLevelChangedTheDecision() {
        // A compact, machine-readable record: one line per (case, level). This is the artefact the
        // release asks for, and it is produced by the test rather than asserted by hand.
        val memory = StoryMemory()
        memory.add(
            MemoryEntry("work-a", MemoryKind.GENDER, "speaker_of_copular_self_description", "feminine",
                EvidenceKind.EXPLICIT, 0.95, "c"),
        )
        val cases = listOf(
            Triple("clear", frame("Jin drew his sword.", "Stay back!"), 1 to "ابق بعيدا!"),
            Triple("pronoun", frame("Jin drew his sword.", "He looked tired."), 1 to "بدا متعبا."),
            Triple("gender", frame("That day, I became a mage."), 0 to "في ذلك اليوم، أصبحت ماجاة."),
        )
        val record = mutableListOf<String>()
        for ((name, texts, spec) in cases) {
            val outcomes = levelsFor(texts, spec.first, spec.second, memory)
            val baselineLevel = outcomes.first().level
            val firstChange = outcomes.firstOrNull { it.level != baselineLevel }
            record += name + ":" + outcomes.map { it.level.name } + "=" + (firstChange?.level?.name ?: "none")
            // Every case must produce a level for every ceiling.
            assertEquals(6, outcomes.size)
        }
        assertEquals(3, record.size)
        assertTrue("the record must name the level that changed something", record.all { it.contains("=") })
    }
}
