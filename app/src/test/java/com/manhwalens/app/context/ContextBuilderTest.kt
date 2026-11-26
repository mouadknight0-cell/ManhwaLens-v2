package com.manhwalens.app.context

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The escalation contract (release §14): use the narrowest level that resolves the ambiguity, never
 * load history for a clear sentence, and say what is still unresolved rather than filling it in.
 */
class ContextBuilderTest {

    private fun frame(vararg texts: String, panel: Int = 0): List<TextObservation> =
        texts.mapIndexed { index, text ->
            TextObservation(text, 10, 50 + index * 60, 300, 90 + index * 60, readingOrder = index, panelIndex = panel)
        }

    private fun build(
        texts: List<TextObservation>,
        index: Int,
        memory: StoryMemory? = null,
        workId: String? = "work-a",
        history: List<String> = emptyList(),
        maxLevel: ContextLevel = ContextLevel.WIDEST,
    ) = ContextBuilder.build(
        ContextBuilder.Sources(workId, texts, index, history, memory, emptyMap(), maxLevel),
    )

    // ---------------------------------------------------------------- no escalation when clear

    @Test fun aClearSentenceGetsNoContextAtAll() {
        val texts = frame("Stay back!", "He opened the door.")
        val package0 = build(texts, 0)
        assertEquals(ContextLevel.NONE, package0.level)
        assertTrue("a self-contained line must not pull context", package0.snippets.isEmpty())
        assertTrue(package0.escalations.isEmpty())
    }

    @Test fun noEscalationMeansNoRetrievalCost() {
        // Guards the §14 requirement directly: the cheap path must be the *only* thing that runs.
        val package0 = build(frame("Retreat!"), 0)
        assertFalse(package0.isEscalated)
        assertEquals("", package0.contextText())
    }

    // ---------------------------------------------------------------- escalation when needed

    @Test fun aGenderedPronounGoesStraightToTheLevelThatCanAnswerIt() {
        // "He" needs an antecedent, and only memory holds one, so the walk goes to STORY. The
        // assertion that matters is the last one: with no memory the question stays *open*, even
        // though "Jin" is sitting in the preceding bubble. Settling it there was the bug -- it would
        // have bound "he" to whichever character happened to be named nearby.
        val texts = frame("Jin drew his sword.", "He looked tired.")
        val package0 = build(texts, 1)
        assertEquals("the walk must reach the level that could answer", ContextLevel.STORY, package0.level)
        assertEquals("but nothing answered it", ContextLevel.NONE, package0.settledAt)
        assertTrue(package0.unresolved.any { it.startsWith("pronoun_referent") })
    }

    @Test fun aNeighbouringNameDoesNotSettleAGenderedPronoun() {
        // The regression guard for the above: the memory-free walk must not bind "he" to "Jin" just
        // because Jin is the only name on the frame. Reaching STORY and settling nothing is the
        // honest outcome, and the record must distinguish the two.
        val texts = frame("Jin drew his sword.", "He looked tired.")
        val package0 = build(texts, 1)
        assertEquals(ContextLevel.STORY, package0.level)
        assertTrue(package0.unresolved.any { it == "pronoun_referent:he" })
        assertTrue("a fruitless walk must not look like an answer", package0.settledAt == ContextLevel.NONE)
    }

    @Test fun anEscalationThatSettlesNothingIsNotReportedAsSuccess() {
        // The defect the ablation record exposed, pinned directly: cost and benefit are separate.
        // Before this, `level` was the ceiling and a resolved-nothing walk read as a story success.
        val package0 = build(frame("I'll make him regret throwing me away!"), 0)
        assertEquals(ContextLevel.STORY, package0.level)
        assertTrue("it escalated", package0.escalations.contains("story_memory"))
        assertEquals("and settled nothing", ContextLevel.NONE, package0.settledAt)
        assertTrue(package0.unresolved.isNotEmpty())
    }

    @Test fun aPronounIsResolvedFromStoryMemoryWhenAnAntecedentExists() {
        val memory = StoryMemory()
        memory.add(MemoryEntry("work-a", MemoryKind.NAME, "he", "Jin", EvidenceKind.EXPLICIT, 0.95, "chapter:1"))
        val package0 = build(frame("He looked tired."), 0, memory = memory)
        assertEquals(ContextLevel.STORY, package0.level)
        assertTrue(package0.unresolved.isEmpty())
        assertTrue(package0.snippets.any { it.text.contains("Jin") })
    }

    @Test fun anItPronounUsesTheRegionAndReportsWhatItFound() {
        val texts = frame("The door was locked.", "It broke anyway.")
        val package0 = build(texts, 1)
        assertEquals(ContextLevel.REGION, package0.level)
        assertTrue(package0.snippets.any { it.reason == "same_panel" || it.reason == "preceding_utterance" })
    }

    @Test fun theRetrievedSnippetIsSourceTextNotATranslation() {
        val texts = frame("Jin drew his sword.", "He looked tired.")
        val package0 = build(texts, 1)
        // No Arabic anywhere: feeding a translation back in would make the engine depend on itself.
        assertTrue(package0.snippets.all { snippet -> snippet.text.none { it in '\u0600'..'\u06FF' } })
    }

    @Test fun anUnresolvablePronounFallsBackToOneUtteranceAndStops() {
        val texts = frame("He looked tired.")
        val package0 = build(texts, 0)
        // Nothing precedes it, so the question stays open and no fabricated context appears.
        assertTrue(package0.unresolved.any { it.startsWith("pronoun_referent") })
        assertTrue(package0.snippets.none { it.reason == "same_panel" })
    }

    @Test fun storyLevelIsUsedForTheSpeakersGender() {
        val memory = StoryMemory()
        memory.add(
            MemoryEntry("work-a", MemoryKind.GENDER, "speaker_of_copular_self_description", "feminine",
                EvidenceKind.EXPLICIT, 0.95, "chapter:3/page:8"),
        )
        val package0 = build(frame("That day, I became a mage."), 0, memory = memory)
        assertEquals(ContextLevel.STORY, package0.level)
        assertTrue(package0.escalations.contains("story_memory"))
        assertTrue(package0.snippets.any { it.text.contains("feminine") })
    }

    @Test fun storyLevelStaysUnresolvedWhenMemoryHasNothing() {
        val package0 = build(frame("That day, I became a mage."), 0, memory = StoryMemory())
        assertTrue("no memory means the gender stays open",
            package0.unresolved.any { it.startsWith("gender") })
        assertTrue(package0.snippets.none { it.kind == EvidenceKind.STORY_MEMORY })
    }

    @Test fun noMemoryObjectMeansNoStoryClaim() {
        val package0 = build(frame("That day, I became a mage."), 0, memory = null)
        assertTrue(package0.unresolved.any { it.startsWith("gender") })
    }

    @Test fun noWorkIdMeansMemoryIsNotConsulted() {
        val memory = StoryMemory()
        memory.add(
            MemoryEntry("work-a", MemoryKind.GENDER, "speaker_of_copular_self_description", "feminine",
                EvidenceKind.EXPLICIT, 0.95, "chapter:3/page:8"),
        )
        val package0 = build(frame("That day, I became a mage."), 0, memory = memory, workId = null)
        assertTrue(package0.unresolved.any { it.startsWith("gender") })
    }

    // ---------------------------------------------------------------- policy ceiling

    @Test fun aCeilingPreventsExpensiveLevels() {
        val memory = StoryMemory()
        memory.add(
            MemoryEntry("work-a", MemoryKind.GENDER, "speaker_of_copular_self_description", "feminine",
                EvidenceKind.EXPLICIT, 0.95, "c"),
        )
        val package0 = build(frame("That day, I became a mage."), 0, memory = memory, maxLevel = ContextLevel.UTTERANCE)
        assertFalse("story memory must not be consulted past the ceiling",
            package0.escalations.contains("story_memory"))
        assertTrue(package0.unresolved.any { it.startsWith("gender") })
    }

    // ---------------------------------------------------------------- bounded context

    @Test fun contextTextIsBounded() {
        val texts = frame(*(1..40).map { "Line number $it with some text." }.toTypedArray())
        val package0 = build(texts, 30)
        assertTrue(package0.contextText().length <= ContextPackage.MAX_SNIPPET_CHARS)
        assertTrue(package0.snippets.size <= ContextPackage.MAX_SNIPPETS)
    }

    @Test fun duplicateSnippetsAreNotRepeated() {
        val texts = frame("Same line.", "He ran.")
        val package0 = build(texts, 1)
        assertEquals(package0.snippets.size, package0.snippets.map { it.text }.distinct().size)
    }

    @Test fun snippetsAreOrderedByDistance() {
        val texts = frame("First.", "Second.", "Third.", "He ran.")
        val package0 = build(texts, 3)
        val distances = package0.snippets.map { it.distance }
        assertEquals(distances.sorted(), distances)
    }

    // ---------------------------------------------------------------- determinism

    @Test fun buildingTwiceGivesTheSamePackage() {
        val texts = frame("Jin drew his sword.", "He looked tired.")
        val first = build(texts, 1)
        val second = build(texts, 1)
        assertEquals(first.level, second.level)
        assertEquals(first.snippets.map { it.text }, second.snippets.map { it.text })
        assertEquals(first.unresolved, second.unresolved)
    }

    @Test fun escalationOrderIsDeterministic() {
        val texts = frame("Jin drew his sword.", "He looked tired.")
        assertEquals(build(texts, 1).escalations, build(texts, 1).escalations)
    }
}
