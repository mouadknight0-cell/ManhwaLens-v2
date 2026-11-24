package com.manhwalens.app.context

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Group H (memory and continuity) and Group I (ambiguity).
 *
 * The rules under test are the ones that stop memory becoming a liability: a guess never becomes a
 * fact, an old memory loses to a clear current sentence, and two works never contaminate each other.
 */
class StoryMemoryTest {

    private fun entry(
        work: String = "work-a",
        kind: MemoryKind = MemoryKind.NAME,
        key: String = "jin",
        value: String = "Jin",
        evidence: EvidenceKind = EvidenceKind.EXPLICIT,
        confidence: Double = 0.95,
        source: String = "chapter:1/page:1",
        chapter: Int = 1,
    ) = MemoryEntry(work, kind, key, value, evidence, confidence, source, chapter)

    // ---------------------------------------------------------------- isolation (Group H)

    @Test fun entriesFromDifferentWorksNeverMix() {
        val memory = StoryMemory()
        memory.add(entry(work = "work-a", key = "mage", value = "ساحر", kind = MemoryKind.TERM))
        memory.add(entry(work = "work-b", key = "mage", value = "سحرة", kind = MemoryKind.TERM))
        assertEquals("ساحر", memory.lookup("work-a", MemoryKind.TERM, "mage")?.value)
        assertEquals("سحرة", memory.lookup("work-b", MemoryKind.TERM, "mage")?.value)
        assertEquals(1, memory.size("work-a"))
    }

    @Test fun lookupInAnUnknownWorkReturnsNothing() {
        val memory = StoryMemory()
        memory.add(entry(work = "work-a"))
        assertNull(memory.lookup("work-z", MemoryKind.NAME, "jin"))
    }

    @Test fun forgettingOneWorkLeavesTheOtherIntact() {
        val memory = StoryMemory()
        memory.add(entry(work = "work-a"))
        memory.add(entry(work = "work-b"))
        assertEquals(1, memory.forgetWork("work-a"))
        assertEquals(0, memory.size("work-a"))
        assertEquals(1, memory.size("work-b"))
    }

    // ---------------------------------------------------------------- never invent a fact (Group I)

    @Test fun aHypothesisIsNotReturnedWhenAFactIsRequired() {
        val memory = StoryMemory()
        memory.add(entry(kind = MemoryKind.GENDER, key = "jin", value = "masculine", evidence = EvidenceKind.HYPOTHESIS, confidence = 0.5))
        // A hypothesis may be read back as a hypothesis...
        assertEquals("masculine", memory.lookup("work-a", MemoryKind.GENDER, "jin")?.value)
        // ...but not when the caller demands at least NEARBY_DIALOGUE evidence.
        assertNull(memory.lookup("work-a", MemoryKind.GENDER, "jin", EvidenceKind.NEARBY_DIALOGUE))
    }

    @Test fun repetitionNeverPromotesAGuessToAFact() {
        val memory = StoryMemory()
        repeat(5) {
            memory.add(entry(kind = MemoryKind.GENDER, key = "jin", value = "feminine", evidence = EvidenceKind.HYPOTHESIS, confidence = 0.5))
        }
        val stored = memory.lookup("work-a", MemoryKind.GENDER, "jin")!!
        assertEquals("repetition must not raise the evidence kind", EvidenceKind.HYPOTHESIS, stored.kindOfEvidence)
        assertFalse(stored.isSettled)
    }

    @Test fun aHypothesisNeverDisplacesASettledEntry() {
        val memory = StoryMemory()
        memory.add(entry(kind = MemoryKind.GENDER, key = "jin", value = "masculine", evidence = EvidenceKind.EXPLICIT))
        val outcome = memory.add(entry(kind = MemoryKind.GENDER, key = "jin", value = "feminine", evidence = EvidenceKind.HYPOTHESIS))
        assertEquals(StoryMemory.AddOutcome.RejectedHypothesis, outcome)
        assertEquals("masculine", memory.lookup("work-a", MemoryKind.GENDER, "jin")?.value)
    }

    @Test fun weakerEvidenceCannotOverwriteASettledEntry() {
        val memory = StoryMemory()
        memory.add(entry(kind = MemoryKind.NAME, key = "jin", value = "Jin", evidence = EvidenceKind.EXPLICIT))
        val outcome = memory.add(entry(kind = MemoryKind.NAME, key = "jin", value = "Jinn", evidence = EvidenceKind.STORY_MEMORY))
        assertEquals(StoryMemory.AddOutcome.RejectedWeaker, outcome)
        assertEquals("Jin", memory.lookup("work-a", MemoryKind.NAME, "jin")?.value)
    }

    // ---------------------------------------------------------------- current page wins (Group H)

    @Test fun aClearCurrentReadingBeatsAnOldMemory() {
        // The release: "a clear sentence in the current page must not be overridden by old memory".
        val memory = StoryMemory()
        memory.add(entry(kind = MemoryKind.NAME, key = "jin", value = "Jin", evidence = EvidenceKind.STORY_MEMORY, confidence = 0.6))
        val outcome = memory.add(entry(kind = MemoryKind.NAME, key = "jin", value = "Jin Ha", evidence = EvidenceKind.EXPLICIT, source = "chapter:9/page:3"))
        assertEquals(StoryMemory.AddOutcome.Replaced, outcome)
        assertEquals("Jin Ha", memory.lookup("work-a", MemoryKind.NAME, "jin")?.value)
    }

    @Test fun aReplacementIsRecordedSoTheOldValueIsRecoverable() {
        val memory = StoryMemory()
        memory.add(entry(kind = MemoryKind.NAME, key = "jin", value = "Jin", evidence = EvidenceKind.STORY_MEMORY, confidence = 0.6))
        memory.add(entry(kind = MemoryKind.NAME, key = "jin", value = "Jin Ha", evidence = EvidenceKind.EXPLICIT))
        val correction = memory.corrections("work-a").single()
        assertEquals("Jin", correction.previous)
        assertEquals("Jin Ha", correction.replacement)
    }

    @Test fun reinforcingTheSameValueRaisesConfidenceWithoutChangingKind() {
        val memory = StoryMemory()
        memory.add(entry(confidence = 0.7))
        val outcome = memory.add(entry(confidence = 0.9))
        assertEquals(StoryMemory.AddOutcome.Reinforced, outcome)
        assertEquals(0.9, memory.lookup("work-a", MemoryKind.NAME, "jin")!!.confidence, 0.0001)
        assertEquals(1, memory.size("work-a"))
    }

    // ---------------------------------------------------------------- correction (Group H)

    @Test fun aUserCorrectionIsRecordedAndApplied() {
        val memory = StoryMemory()
        memory.add(entry(kind = MemoryKind.TERM, key = "mage", value = "ماجاة"))
        assertTrue(memory.correct("work-a", MemoryKind.TERM, "mage", "ساحر", reason = "reviewer: wrong term", source = "review"))
        assertEquals("ساحر", memory.lookup("work-a", MemoryKind.TERM, "mage")?.value)
        assertEquals("ماجاة", memory.corrections("work-a").single().previous)
    }

    @Test fun aCorrectionWithoutAReasonIsRefused() {
        val memory = StoryMemory()
        memory.add(entry(kind = MemoryKind.TERM, key = "mage", value = "ماجاة"))
        assertFalse(memory.correct("work-a", MemoryKind.TERM, "mage", "ساحر", reason = "  ", source = "review"))
        assertEquals("ماجاة", memory.lookup("work-a", MemoryKind.TERM, "mage")?.value)
    }

    @Test fun forgettingAnEntryRemovesIt() {
        val memory = StoryMemory()
        memory.add(entry())
        assertEquals(1, memory.forget("work-a", MemoryKind.NAME, "jin"))
        assertNull(memory.lookup("work-a", MemoryKind.NAME, "jin"))
    }

    // ---------------------------------------------------------------- retrieval

    @Test fun matchingFindsKeysInsideAText() {
        val memory = StoryMemory()
        memory.add(entry(kind = MemoryKind.NAME, key = "jin", value = "Jin"))
        memory.add(entry(kind = MemoryKind.ENTITY, key = "northern gate", value = "Northern Gate"))
        val found = memory.matching("work-a", "Jin walked to the northern gate.")
        assertEquals(2, found.size)
    }

    @Test fun matchingDoesNotRetrieveFromAnotherWork() {
        val memory = StoryMemory()
        memory.add(entry(work = "work-b", kind = MemoryKind.NAME, key = "jin", value = "Jin"))
        assertTrue(memory.matching("work-a", "Jin walked in.").isEmpty())
    }

    @Test fun matchingIsBounded() {
        val memory = StoryMemory()
        (1..20).forEach { memory.add(entry(key = "name$it", value = "Name$it")) }
        val text = (1..20).joinToString(" ") { "name$it" }
        assertTrue(memory.matching("work-a", text, limit = 4).size <= 4)
    }

    @Test fun aBlankKeyIsRejected() {
        var rejected = false
        try { MemoryEntry("w", MemoryKind.NAME, " ", "x", EvidenceKind.EXPLICIT, 0.9, "s") }
        catch (e: IllegalArgumentException) { rejected = true }
        assertTrue(rejected)
    }

    // ---------------------------------------------------------------- persistence shape

    @Test fun replayingAPersistedLogReproducesTheSameState() {
        val memory = StoryMemory()
        memory.add(entry(key = "jin", value = "Jin"))
        memory.add(entry(kind = MemoryKind.TERM, key = "mage", value = "ساحر"))
        val snapshot = memory.forWork("work-a")
        val replayed = StoryMemory(snapshot)
        assertEquals(snapshot.map { it.key to it.value }, replayed.forWork("work-a").map { it.key to it.value })
    }
}
