package com.manhwalens.app.memory

import com.manhwalens.app.context.EvidenceKind
import com.manhwalens.app.context.MemoryEntry
import com.manhwalens.app.context.MemoryKind
import com.manhwalens.app.context.StoryMemory
import com.manhwalens.app.context.StoryMemoryJson
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * Story memory as it behaves on disk.
 *
 * Written against a real temporary directory rather than a mock, because the properties that matter
 * here are filesystem properties: does a crash leave a readable file, does damage get kept, does one
 * work stay invisible to another, does deleting really delete. A mock would assert my intentions
 * instead of the system's behaviour.
 *
 * What is deliberately *not* claimed: nothing here says a remembered translation is correct. These
 * are durability tests, not semantic ones.
 */
class StoryMemoryFilesTest {

    private lateinit var root: File
    private lateinit var store: StoryMemoryFiles

    @Before fun setUp() {
        root = File(System.getProperty("java.io.tmpdir"), "story-memory-test-" + System.nanoTime())
        root.mkdirs()
        store = StoryMemoryFiles(root)
    }

    @After fun tearDown() {
        root.deleteRecursively()
    }

    private fun entry(
        workId: String = "work-a",
        key: String = "jin",
        value: String = "جين",
        kind: MemoryKind = MemoryKind.NAME,
        sequence: Long = 0,
    ) = MemoryEntry(workId, kind, key, value, EvidenceKind.EXPLICIT, 0.95, "chapter:1/page:2", sequence = sequence)

    // ---------------------------------------------------------------- durability

    @Test fun whatIsSavedIsWhatIsLoaded() {
        store.save("work-a", listOf(entry()), emptyList())
        val loaded = store.load("work-a")
        assertEquals(1, loaded.size)
        assertEquals("jin", loaded.single().key)
        assertEquals("جين", loaded.single().value)
    }

    @Test fun savingTwiceReplacesRatherThanDuplicates() {
        store.save("work-a", listOf(entry(key = "jin")), emptyList())
        store.save("work-a", listOf(entry(key = "jin", value = "جينجين")), emptyList())
        val loaded = store.load("work-a")
        assertEquals("a rewording must not double the glossary", 1, loaded.size)
        assertEquals("جينجين", loaded.single().value)
    }

    @Test fun aMissingWorkLoadsAsEmptyRatherThanFailing() {
        assertEquals(0, store.load("never-seen").size)
        assertEquals(-1, store.loadLines("never-seen").version)
    }

    @Test fun noTempFilesAreLeftBehindAfterASave() {
        // A leftover .tmp is harmless to correctness but would inflate the size report and confuse
        // a reader inspecting the device. The rename must complete.
        store.save("work-a", listOf(entry()), emptyList())
        val leftovers = root.listFiles()?.filter { it.name.endsWith(".tmp") }.orEmpty()
        assertTrue("temp files must not survive a finished write", leftovers.isEmpty())
    }

    // ---------------------------------------------------------------- works stay apart

    @Test fun oneWorkCannotSeeAnothersMemory() {
        // The failure this prevents: two manhwa sharing a character glossary, so "Jin" is translated
        // with the wrong title's spelling because the other title used it first.
        store.save("work-a", listOf(entry(workId = "work-a", key = "jin")), emptyList())
        store.save("work-b", listOf(entry(workId = "work-b", key = "mira")), emptyList())
        assertEquals(listOf("jin"), store.load("work-a").map { it.key })
        assertEquals(listOf("mira"), store.load("work-b").map { it.key })
    }

    @Test fun aFileContainingForeignRowsOnlyReturnsTheRequestedWork() {
        // Defence in depth: even if a save is mishandled and a file holds another work's rows, the
        // read filters them out. Returning the wrong work's names is worse than returning none.
        File(root, StoryMemoryFiles.fileName("work-a")).writeText(
            StoryMemoryJson.encode(
                listOf(entry(workId = "work-a"), entry(workId = "work-b", key = "mira")),
                emptyList(),
            ),
        )
        assertEquals(listOf("jin"), store.load("work-a").map { it.key })
    }

    @Test fun workIdsListsEveryStoredWorkAndNothingElse() {
        store.save("work-a", listOf(entry(workId = "work-a")), emptyList())
        store.save("work-b", listOf(entry(workId = "work-b", key = "mira")), emptyList())
        assertEquals(listOf("work-a", "work-b"), store.workIds())
    }

    @Test fun aWorkIdWithPathCharactersCannotEscapeTheDirectory() {
        // A work id derived from a title could contain "/" or "..". Hashing means it never becomes a
        // path component, so a save cannot write outside filesDir.
        val hostile = "../../etc/passwd"
        store.save(hostile, listOf(entry(workId = hostile)), emptyList())
        assertEquals(1, store.load(hostile).size)
        assertTrue("nothing may be written outside the root", root.listFiles()!!.all { it.isFile })
        assertFalse(File(root.parentFile, "etc").exists())
    }

    // ---------------------------------------------------------------- deletion

    @Test fun deletingAWorkRemovesItAndLeavesOthersAlone() {
        store.save("work-a", listOf(entry(workId = "work-a")), emptyList())
        store.save("work-b", listOf(entry(workId = "work-b", key = "mira")), emptyList())
        store.delete("work-a")
        assertEquals(0, store.load("work-a").size)
        assertEquals(1, store.load("work-b").size)
    }

    @Test fun savingNothingDeletesTheFileRatherThanLeavingAnEmptyOne() {
        store.save("work-a", listOf(entry()), emptyList())
        store.save("work-a", emptyList(), emptyList())
        assertEquals(0L, store.sizeBytes("work-a"))
        assertTrue(store.workIds().isEmpty())
    }

    // ---------------------------------------------------------------- damage is kept

    @Test fun aCorruptFileIsQuarantinedAndReportedRatherThanSilentlyLost() {
        // Silently returning empty would make a reader think their corrections never happened.
        val target = File(root, StoryMemoryFiles.fileName("work-a"))
        target.writeText("{not json")
        val decoded = store.loadLines("work-a")
        assertEquals(0, decoded.entries.size)
        assertTrue("the caller must be able to tell that something was wrong", decoded.skipped > 0)
        assertEquals(1, store.quarantined().size)
    }

    @Test fun aFileFromANewerBuildIsKeptForThatBuildToRead() {
        val target = File(root, StoryMemoryFiles.fileName("work-a"))
        target.writeText("{\"version\":999,\"entries\":[]}")
        store.loadLines("work-a")
        assertEquals("a future format must not be deleted by an older build", 1, store.quarantined().size)
    }

    @Test fun aTruncatedFileIsQuarantined() {
        store.save("work-a", (1..5).map { entry(key = "name$it", sequence = it.toLong()) }, emptyList())
        val target = File(root, StoryMemoryFiles.fileName("work-a"))
        val text = target.readText()
        target.writeText(text.substring(0, text.length / 2))
        store.loadLines("work-a")
        assertEquals(1, store.quarantined().size)
    }

    // ---------------------------------------------------------------- bounds

    @Test fun thePerWorkCapKeepsTheNewestFacts() {
        val many = (1..(StoryMemoryFiles.MAX_ENTRIES + 40)).map {
            entry(key = "name$it", sequence = it.toLong())
        }
        store.save("work-a", many, emptyList())
        val loaded = store.load("work-a")
        assertEquals(StoryMemoryFiles.MAX_ENTRIES, loaded.size)
        assertEquals("the newest chapter must not be evicted to keep the first", "name640", loaded.last().key)
    }

    @Test fun aWorkWithNoFactsStillSavesItsCorrectionHistory() {
        // A correction is how a wrong term stops recurring; losing it because the entry was later
        // forgotten would let the engine relearn the mistake.
        val correction = StoryMemory.Correction("work-a", MemoryKind.TERM, "jin", "جين", "جِن", "user_adopted", "settings")
        store.save("work-a", emptyList(), listOf(correction))
        val decoded = store.loadLines("work-a")
        assertEquals(1, decoded.corrections.size)
        assertEquals("جِن", decoded.corrections.single().replacement)
    }

    // ---------------------------------------------------------------- the full loop

    @Test fun memorySurvivesAProcessRestart() {
        // The scenario the release actually cares about: remember across sessions, on one device.
        val memory = StoryMemory()
        memory.add(entry(key = "jin", value = "جين"))
        memory.add(entry(key = "speaker", value = "feminine", kind = MemoryKind.GENDER, sequence = 1))
        store.save("work-a", memory.forWork("work-a"), memory.corrections("work-a"))

        val reopened = StoryMemoryFiles(root)
        val reloaded = StoryMemory(reopened.load("work-a"))
        assertEquals(memory.size("work-a"), reloaded.size("work-a"))
        assertNotNull(reloaded.lookup("work-a", MemoryKind.GENDER, "speaker"))
        assertEquals("جين", reloaded.lookup("work-a", MemoryKind.NAME, "jin")?.value)
    }

    @Test fun theFootprintIsReportedForTheSizeBudget() {
        store.save("work-a", (1..20).map { entry(key = "name$it", sequence = it.toLong()) }, emptyList())
        val footprint = store.footprint()
        assertEquals(1, footprint.works)
        assertTrue("20 facts must be small, but must be measured", footprint.bytes > 0)
        assertTrue("20 facts took ${footprint.bytes} bytes", footprint.bytes < 20_000)
    }
}
