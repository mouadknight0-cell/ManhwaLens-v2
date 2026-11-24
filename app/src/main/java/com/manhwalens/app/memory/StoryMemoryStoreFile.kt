package com.manhwalens.app.memory

import android.content.Context
import com.manhwalens.app.context.MemoryEntry
import com.manhwalens.app.context.StoryMemory
import com.manhwalens.app.context.StoryMemoryStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The Android binding for story memory: it supplies `filesDir` and moves work off the main thread.
 *
 * Everything that decides *what* is remembered lives in `context/` as pure Kotlin, everything that
 * decides *how a row is written* lives in `StoryMemoryJson`, and everything that touches the
 * filesystem lives in [StoryMemoryFiles]. This class exists only so the translation path can have a
 * store without the interface knowing about `Context`.
 *
 * §14: no memory operation may freeze the translation UI. Every disk call here is `suspend` on
 * [Dispatchers.IO]; the translation path loads one work once and then queries the in-memory
 * [StoryMemory], whose lookups are list scans over at most [StoryMemoryFiles.MAX_ENTRIES] records.
 */
class StoryMemoryStoreFile(context: Context) : StoryMemoryStore {

    private val files = StoryMemoryFiles(File(context.filesDir, DIR))

    override fun load(workId: String): List<MemoryEntry> = files.load(workId)

    override fun save(workId: String, entries: List<MemoryEntry>, corrections: List<StoryMemory.Correction>) =
        files.save(workId, entries, corrections)

    override fun workIds(): List<String> = files.workIds()

    override fun delete(workId: String) = files.delete(workId)

    suspend fun loadInto(memory: StoryMemory, workId: String): Int = withContext(Dispatchers.IO) {
        val decoded = files.loadLines(workId)
        decoded.entries.forEach { memory.add(it) }
        decoded.entries.size
    }

    suspend fun saveFrom(memory: StoryMemory, workId: String) = withContext(Dispatchers.IO) {
        files.save(workId, memory.forWork(workId), memory.corrections(workId))
    }

    suspend fun footprint(): StoryMemoryFiles.Footprint = withContext(Dispatchers.IO) { files.footprint() }

    companion object {
        private const val DIR = "story-memory"
    }
}
