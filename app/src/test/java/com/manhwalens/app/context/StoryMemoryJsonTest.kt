package com.manhwalens.app.context

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Persistence of story memory.
 *
 * The release's §13 asks for memory that is local, per-work, sourced, timestamped, migratable, and
 * installable over RC9 without data loss. Those are properties of the *file*, not of the engine, so
 * they are tested here against the encoder/decoder rather than against a device.
 *
 * Nothing here asserts that a remembered fact is *correct* — only that a fact that was written can
 * be read back unchanged, that a damaged file costs the least possible, and that two works cannot
 * see each other.
 */
class StoryMemoryJsonTest {

    private fun entry(
        key: String = "jin",
        value: String = "جين",
        kind: MemoryKind = MemoryKind.NAME,
        evidence: EvidenceKind = EvidenceKind.EXPLICIT,
        workId: String = "work-a",
        confidence: Double = 0.95,
        sequence: Long = 0,
        also: String = "",
        source: String = "chapter:1/page:3",
    ) = MemoryEntry(workId, kind, key, value, evidence, confidence, source, sequence = sequence, also = also)

    private fun roundTrip(entries: List<MemoryEntry>): StoryMemoryJson.Decoded =
        StoryMemoryJson.decode(StoryMemoryJson.encode(entries, emptyList()))

    // ---------------------------------------------------------------- round trip

    @Test fun aNameSurvivesWriteAndReadUnchanged() {
        val decoded = roundTrip(listOf(entry()))
        assertEquals(1, decoded.entries.size)
        assertEquals("jin", decoded.entries[0].key)
        assertEquals("جين", decoded.entries[0].value)
        assertEquals(MemoryKind.NAME, decoded.entries[0].kind)
        assertEquals(EvidenceKind.EXPLICIT, decoded.entries[0].kindOfEvidence)
    }

    @Test fun arabicAndPunctuationComeBackByteIdentical() {
        // A term whose Arabic gains or loses a character is announced as new every chapter, which
        // is precisely the §9 consistency failure this file has to prevent.
        val awkward = listOf(
            entry(key = "northern gate", value = "البوابة الشمالية \"الكبرى\"", kind = MemoryKind.TERM),
            entry(key = "line1", value = "سطر\nسطر\tتاب", kind = MemoryKind.TERM),
            entry(key = "back\\slash", value = "ع\\ك", kind = MemoryKind.TERM),
            entry(key = "quote\"in", value = "«اقتباس»", kind = MemoryKind.TERM),
        )
        val decoded = roundTrip(awkward)
        assertEquals(awkward.size, decoded.entries.size)
        awkward.forEachIndexed { index, original ->
            assertEquals(original.value, decoded.entries[index].value)
            assertEquals(original.key, decoded.entries[index].key)
        }
    }

    @Test fun everyOptionalFieldSurvives() {
        val original = MemoryEntry(
            workId = "work-a", kind = MemoryKind.RELATION, key = "jin", value = "brother of",
            kindOfEvidence = EvidenceKind.NEARBY_DIALOGUE, confidence = 0.66,
            source = "chapter:12/page:44", chapter = 12, page = 44, sequence = 7, also = "mira",
        )
        val decoded = roundTrip(listOf(original))
        assertEquals(original, decoded.entries.single())
    }

    @Test fun sequenceOrderIsPreservedSoReplayReproducesState() {
        // StoryMemory replays in sequence order; a lost order would let an older fact win.
        val entries = listOf(
            entry(key = "c", value = "3", sequence = 3),
            entry(key = "a", value = "1", sequence = 1),
            entry(key = "b", value = "2", sequence = 2),
        )
        val decoded = roundTrip(entries)
        assertEquals(listOf("a", "b", "c"), decoded.entries.map { it.key })
        assertEquals(listOf(1L, 2L, 3L), decoded.entries.map { it.sequence })
    }

    @Test fun confidenceKeepsItsPrecision() {
        // A confidence that rounds is a confidence that changes its mind about which fact wins.
        val decoded = roundTrip(listOf(entry(confidence = 0.9123456789)))
        assertEquals(0.9123456789, decoded.entries.single().confidence, 0.0)
    }

    // ---------------------------------------------------------------- versioning

    @Test fun theFileAlwaysDeclaresItsVersion() {
        val encoded = StoryMemoryJson.encode(listOf(entry()), emptyList())
        assertTrue("a file with no version cannot be migrated", encoded.contains("\"version\":1"))
        assertEquals(StoryMemoryJson.VERSION, StoryMemoryJson.decode(encoded).version)
    }

    @Test fun aFutureVersionIsRefusedRatherThanHalfRead() {
        // Reading a format this build does not understand could silently drop a fact it depends on.
        val future = "{\"version\":999,\"entries\":[{\"workId\":\"work-a\",\"kind\":\"NAME\"," +
            "\"key\":\"jin\",\"value\":\"جين\",\"evidence\":\"EXPLICIT\",\"confidence\":0.9," +
            "\"source\":\"c\",\"chapter\":1,\"page\":1,\"sequence\":0,\"also\":\"\"}]}"
        val decoded = StoryMemoryJson.decode(future)
        assertEquals(999, decoded.version)
        assertTrue(decoded.isEmpty)
    }

    @Test fun anUnversionedFileIsNotGuessedAt() {
        val legacy = "{\"entries\":[{\"workId\":\"work-a\",\"kind\":\"NAME\",\"key\":\"jin\"," +
            "\"value\":\"جين\",\"evidence\":\"EXPLICIT\",\"confidence\":0.9,\"source\":\"c\"}]}"
        assertTrue(StoryMemoryJson.decode(legacy).isEmpty)
    }

    @Test fun anUnknownFieldIsIgnoredRatherThanFatal() {
        // Forward compatibility: a field added by a later build must not make this build refuse.
        val forward = StoryMemoryJson.encode(listOf(entry()), emptyList())
            .replace("{\"workId\":", "{\"futureField\":123,\"workId\":")
        val decoded = StoryMemoryJson.decode(forward)
        assertEquals(1, decoded.entries.size)
        assertEquals(0, decoded.skipped)
    }

    // ---------------------------------------------------------------- damage costs little

    @Test fun oneMalformedRowDoesNotDiscardTheFile() {
        // A corrupt byte must not cost a reader their whole glossary.
        val good = StoryMemoryJson.encode(listOf(entry(key = "jin"), entry(key = "mira")), emptyList())
        val damaged = good.replace("\"kind\":\"NAME\",\"key\":\"mira\"", "\"kind\":\"NOT_A_KIND\",\"key\":\"mira\"")
        val decoded = StoryMemoryJson.decode(damaged)
        assertEquals(1, decoded.entries.size)
        assertEquals("jin", decoded.entries.single().key)
        assertEquals(1, decoded.skipped)
    }

    @Test fun unreadableTextYieldsNothingAndSaysSo() {
        assertEquals(-1, StoryMemoryJson.decode("this is not json").version)
        assertEquals(-1, StoryMemoryJson.decode("{\"version\":1,\"entries\":[").version)
        assertTrue(StoryMemoryJson.decode("").isEmpty)
        assertTrue(StoryMemoryJson.decode(null).isEmpty)
    }

    @Test fun aTruncatedFileIsRefusedRatherThanPartlyTrusted() {
        // Half a JSON document must not be read as "half a glossary": the reader cannot tell which
        // half is missing, so it trusts none of it.
        val encoded = StoryMemoryJson.encode(listOf(entry()), emptyList())
        val truncated = encoded.substring(0, encoded.length / 2)
        assertEquals(-1, StoryMemoryJson.decode(truncated).version)
    }

    @Test fun trailingContentIsRejected() {
        val encoded = StoryMemoryJson.encode(listOf(entry()), emptyList())
        assertEquals(-1, StoryMemoryJson.decode(encoded + "garbage").version)
    }

    // ---------------------------------------------------------------- works stay apart

    @Test fun twoWorksAreEncodedAndDecodedIndependently() {
        val entries = listOf(entry(workId = "work-a", key = "jin"), entry(workId = "work-b", key = "mira"))
        val encoded = StoryMemoryJson.encode(entries, emptyList())
        // The encoder writes whatever it is given; separation is the store's job and is asserted in
        // the store's own test. Here the point is that the workId survives, so the filter can work.
        assertEquals(setOf("work-a", "work-b"), StoryMemoryJson.decode(encoded).entries.map { it.workId }.toSet())
    }

    // ---------------------------------------------------------------- corrections

    @Test fun aCorrectionKeepsBothTheOldAndTheNewValue() {
        // §9: correcting a fact must not erase the fact that it was corrected.
        val memory = StoryMemory()
        memory.add(entry(key = "jin", value = "جين", kind = MemoryKind.TERM))
        memory.correct("work-a", MemoryKind.TERM, "jin", "جِن", reason = "user_adopted", source = "settings")
        val corrections = memory.corrections("work-a")
        val encoded = StoryMemoryJson.encode(memory.forWork("work-a"), corrections)
        val decoded = StoryMemoryJson.decode(encoded)
        assertEquals("جِن", decoded.entries.single().value)
        assertEquals("جين", decoded.corrections.single().previous)
        assertEquals("جِن", decoded.corrections.single().replacement)
        assertEquals("user_adopted", decoded.corrections.single().reason)
    }

    // ---------------------------------------------------------------- replay rebuilds the same memory

    @Test fun replayingPersistedEntriesRebuildsTheSameState() {
        // The real persistence contract: a saved memory, loaded into a fresh StoryMemory, must
        // answer queries identically. If it does not, the app remembers something different after
        // a restart than it did before one.
        val memory = StoryMemory()
        memory.add(entry(key = "jin", value = "جين", confidence = 0.95, sequence = 0))
        memory.add(entry(key = "speaker", value = "feminine", kind = MemoryKind.GENDER, sequence = 1))
        memory.add(entry(key = "he", value = "jin", kind = MemoryKind.NAME, source = "chapter:2/page:9", sequence = 2))

        val encoded = StoryMemoryJson.encode(memory.forWork("work-a"), memory.corrections("work-a"))
        val reloaded = StoryMemory(StoryMemoryJson.decode(encoded).entries)

        assertEquals(memory.size("work-a"), reloaded.size("work-a"))
        assertEquals(
            memory.lookup("work-a", MemoryKind.NAME, "he")?.value,
            reloaded.lookup("work-a", MemoryKind.NAME, "he")?.value,
        )
        assertEquals(
            memory.lookup("work-a", MemoryKind.GENDER, "speaker")?.value,
            reloaded.lookup("work-a", MemoryKind.GENDER, "speaker")?.value,
        )
    }

    @Test fun aReloadedMemoryStillRefusesToLetAHypothesisAnswerAFact() {
        // Persistence must not launder a hypothesis into a fact by dropping its evidence kind.
        val hypothesis = entry(key = "speaker", value = "feminine", kind = MemoryKind.GENDER,
            evidence = EvidenceKind.HYPOTHESIS, confidence = 0.99)
        val encoded = StoryMemoryJson.encode(listOf(hypothesis), emptyList())
        val reloaded = StoryMemory(StoryMemoryJson.decode(encoded).entries)
        assertNull(
            "a stored hypothesis may never answer a gender question",
            reloaded.lookup("work-a", MemoryKind.GENDER, "speaker", minimumKind = EvidenceKind.SYNTACTIC),
        )
        assertNotNull(reloaded.lookup("work-a", MemoryKind.GENDER, "speaker", minimumKind = EvidenceKind.HYPOTHESIS))
    }

    // ---------------------------------------------------------------- sizes

    @Test fun aRealisticGlossaryStaysSmall() {
        // §13 forbids storing every page's text without studying the size. This bounds what the
        // design actually writes: short records, not dialogue.
        val entries = (1..600).map { index ->
            entry(key = "character $index", value = "شخصية $index", sequence = index.toLong())
        }
        val bytes = StoryMemoryJson.encode(entries, emptyList()).toByteArray(Charsets.UTF_8).size
        assertTrue("600 facts took $bytes bytes; the cap assumes well under 200 KB", bytes < 200_000)
    }

    @Test fun anEmptyWorkEncodesToAValidEmptyFile() {
        val decoded = StoryMemoryJson.decode(StoryMemoryJson.encode(emptyList(), emptyList()))
        assertEquals(StoryMemoryJson.VERSION, decoded.version)
        assertTrue(decoded.entries.isEmpty())
    }
}
