package com.manhwalens.app.memory

import com.manhwalens.app.context.MemoryEntry
import com.manhwalens.app.context.StoryMemory
import com.manhwalens.app.context.StoryMemoryJson
import com.manhwalens.app.context.StoryMemoryStore
import java.io.File

/**
 * On-device persistence for story memory: one JSON file per work, under a caller-supplied root.
 *
 * **No Android imports.** The root directory is a plain [File], so all of this — atomic writes,
 * quarantine of damaged files, the per-work cap, deletion, isolation — is exercised by ordinary
 * tests instead of being taken on trust. [StoryMemoryStoreFile] supplies `context.filesDir` and does
 * nothing else.
 *
 * The release's §13 rules, and where each is enforced:
 *
 *  - **Per work, isolated.** A work's id is hashed to a filename by [fileName], and [load] filters by
 *    `workId` again after decode. Isolation is asserted twice because a filename collision would
 *    otherwise silently mix two titles' character names, which is the worst possible failure here.
 *  - **Local only.** Files live under [root]; nothing is uploaded and no path leaves the device.
 *  - **Atomic.** A write goes to a sibling temp file and is then renamed over the target, so a
 *    process death mid-write cannot produce a half-written glossary.
 *  - **Damage is kept, not deleted.** A file that fails to decode is renamed to `.corrupt-<time>`.
 *    A wrong memory has to remain findable to be correctable; silently discarding it teaches the
 *    engine nothing and hides the bug.
 *  - **Sourced.** Every entry carries its own `source`/`chapter`/`page`, so the engine never has to
 *    guess when or where a fact was learned.
 *  - **Migratable.** [StoryMemoryJson] writes a version and refuses a future one.
 *  - **Bounded.** A work keeps at most [MAX_ENTRIES] long-term facts. Full page text is never stored
 *    here, so this is a growth guard, not a text budget.
 */
class StoryMemoryFiles(private val root: File) : StoryMemoryStore {

    init {
        if (!root.exists()) root.mkdirs()
    }

    private fun fileFor(workId: String): File = File(root, fileName(workId))

    override fun load(workId: String): List<MemoryEntry> = loadLines(workId).entries

    /**
     * Read one work, with the diagnostics a caller needs to notice trouble.
     *
     * Returns an empty result with `skipped > 0` when rows were unusable, and `version = -1` when
     * the file was not readable at all. A caller that wants to tell the reader "some remembered
     * names could not be read" has what it needs; a caller that just wants the facts gets an empty
     * list instead of an exception.
     */
    fun loadLines(workId: String): StoryMemoryJson.Decoded {
        val file = fileFor(workId)
        if (!file.exists()) return StoryMemoryJson.Decoded()
        val text = runCatching { file.readText(Charsets.UTF_8) }.getOrNull()
        val decoded = StoryMemoryJson.decode(text)
        if (decoded.version != StoryMemoryJson.VERSION) {
            // Unreadable, truncated, or from a newer build. Keep it: it is the only copy of whatever
            // this reader had learned, and a later build may understand it.
            quarantine(file)
            return StoryMemoryJson.Decoded(version = decoded.version, skipped = maxOf(decoded.skipped, 1))
        }
        // Defence in depth: a file must never hand back another work's rows.
        return decoded.copy(entries = decoded.entries.filter { it.workId == workId })
    }

    override fun save(
        workId: String,
        entries: List<MemoryEntry>,
        corrections: List<StoryMemory.Correction>,
    ) {
        if (workId.isBlank()) return
        val mine = entries.filter { it.workId == workId }
        // Keep the newest facts when a work exceeds the cap: the chapter a reader is on now matters
        // more than the one they finished long ago.
        val kept = if (mine.size <= MAX_ENTRIES) mine else mine.sortedBy { it.sequence }.takeLast(MAX_ENTRIES)
        val keptCorrections = corrections.filter { it.workId == workId }.takeLast(MAX_CORRECTIONS)
        // Forgetting everything about a work should leave no file: an empty file would keep the work
        // listed in [workIds] and keep its bytes in the size report.
        if (kept.isEmpty() && keptCorrections.isEmpty()) {
            delete(workId)
            return
        }
        writeAtomically(fileFor(workId), StoryMemoryJson.encode(kept, keptCorrections))
    }

    /**
     * Every work that has stored facts.
     *
     * Reads each file, because the filename is a hash. For a settings or diagnostics screen; it is
     * not on the translation path, which loads exactly one work.
     */
    override fun workIds(): List<String> = root.listFiles()
        ?.filter { it.isFile && it.name.endsWith(SUFFIX) }
        ?.mapNotNull { file ->
            StoryMemoryJson.decode(runCatching { file.readText(Charsets.UTF_8) }.getOrNull())
                .entries.firstOrNull()?.workId
        }
        ?.distinct()
        ?.sorted()
        .orEmpty()

    override fun delete(workId: String) {
        runCatching { fileFor(workId).delete() }
    }

    /** Bytes on disk for one work, or 0. Part of the §14 size measurement. */
    fun sizeBytes(workId: String): Long = runCatching { fileFor(workId).length() }.getOrDefault(0L)

    /** Total bytes and work count, for the §14 report. */
    fun footprint(): Footprint = Footprint(
        works = workIds().size,
        bytes = runCatching {
            root.listFiles()?.filter { it.isFile }?.sumOf { it.length() } ?: 0L
        }.getOrDefault(0L),
    )

    data class Footprint(val works: Int, val bytes: Long)

    /** Any quarantined files, so a report can say that damage happened. */
    fun quarantined(): List<String> = root.listFiles()
        ?.filter { it.isFile && CORRUPT_MARKER in it.name }
        ?.map { it.name }
        ?.sorted()
        .orEmpty()

    private fun quarantine(file: File) {
        val stamp = System.currentTimeMillis()
        runCatching { file.renameTo(File(root, file.name.removeSuffix(SUFFIX) + CORRUPT_MARKER + stamp)) }
    }

    private fun writeAtomically(target: File, text: String) {
        val temp = File(root, target.name + ".tmp")
        // Write, then rename over the target: a rename within one directory is atomic, so a reader
        // either sees the previous complete file or the new complete one, never a partial write.
        temp.writeText(text, Charsets.UTF_8)
        if (!temp.renameTo(target)) {
            // Filesystems differ; fall back to copy-then-delete rather than losing the write.
            target.writeText(text, Charsets.UTF_8)
            runCatching { temp.delete() }
        }
    }

    companion object {
        /** Long-term facts per work. Small records, so this is a growth guard, not a text budget. */
        const val MAX_ENTRIES = 600
        const val MAX_CORRECTIONS = 200
        private const val SUFFIX = ".json"
        private const val CORRUPT_MARKER = ".corrupt-"

        /**
         * A filesystem-safe name for a work id.
         *
         * A work id can come from a title string, so it may contain `/`, `..`, Arabic, or anything
         * else. Rather than sanitise — two different titles can sanitise to the same name and then
         * share a glossary — the id is hashed, and the true id is stored inside the file. A collision
         * is caught by the `workId` filter on read, which returns nothing rather than the wrong work.
         */
        fun fileName(workId: String): String {
            var hash = 1125899906842597L // FNV offset basis
            for (byte in workId.toByteArray(Charsets.UTF_8)) {
                hash = 31 * hash + byte
            }
            return "work-" + java.lang.Long.toHexString(hash) + SUFFIX
        }
    }
}
