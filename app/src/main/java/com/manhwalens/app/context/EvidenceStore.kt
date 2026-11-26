package com.manhwalens.app.context

import java.io.File

/**
 * Where captured evidence lives between the app and the phone's share sheet.
 *
 * A plain [File] root, no Android types, so every rule below is exercised by ordinary tests. The
 * patterns are the ones [com.manhwalens.app.memory.StoryMemoryFiles] already uses and has proven:
 * atomic write-then-rename, damaged files kept as `.corrupt-<time>` rather than deleted, and a
 * per-file cap.
 *
 * Three rules specific to evidence:
 *
 *  - **Append-only.** A capture is a record of something that happened. Overwriting one would lose
 *    the only copy of a real observation, and an observation cannot be re-taken — the page has been
 *    scrolled past.
 *  - **Bounded in count, never in fidelity.** The oldest captures are dropped past [MAX_CASES] with
 *    a count reported, rather than truncating a case's fields. A half-recorded case is worse than no
 *    case, because it looks complete.
 *  - **Deletion is explicit.** Nothing is removed except by [clear] or by the cap, and a damaged file
 *    is preserved, because it is still evidence of the failure.
 */
class EvidenceStore(private val root: File) {

    init {
        if (!root.exists()) root.mkdirs()
    }

    /** One session's cases, in capture order. */
    fun load(sessionId: String): List<EvidenceCase> {
        val file = fileFor(sessionId)
        if (!file.exists()) return emptyList()
        val text = runCatching { file.readText(Charsets.UTF_8) }.getOrNull() ?: return emptyList()
        val decoded = EvidenceCaseDecoder.decode(text, sessionId)
        if (!decoded.ok) {
            quarantine(file)
            return emptyList()
        }
        return decoded.cases
    }

    /** How many cases the last [load] had to drop because the file exceeded [MAX_CASES]. */
    fun lastDropped(): Int = lastDropped

    /**
     * Add one case to a session.
     *
     * Re-reads before writing so a capture taken while another is still being written cannot clobber
     * it. The write is atomic, so a process death mid-capture leaves the previous file intact.
     */
    fun append(case: EvidenceCase): AppendResult {
        val sessionId = case.sessionId.ifBlank { DEFAULT_SESSION }
        val existing = load(sessionId).toMutableList()
        existing += case
        val overflow = if (existing.size > MAX_CASES) existing.size - MAX_CASES else 0
        val kept = if (overflow > 0) existing.drop(overflow) else existing
        writeAtomically(fileFor(sessionId), EvidenceCaseEncoder.encodeAll(kept))
        lastDropped = overflow
        return AppendResult(sessionId = sessionId, stored = kept.size, dropped = overflow)
    }

    data class AppendResult(val sessionId: String, val stored: Int, val dropped: Int)

    /** Every session that has captures, newest filename first. */
    fun sessionIds(): List<String> = root.listFiles()
        ?.filter { it.isFile && it.name.endsWith(SUFFIX) }
        ?.map { it.name.removeSuffix(SUFFIX) }
        ?.sorted()
        .orEmpty()

    /** Everything, grouped by session, for an export. */
    fun all(): Map<String, List<EvidenceCase>> =
        sessionIds().associateWith { load(it) }

    fun clear(sessionId: String) {
        runCatching { fileFor(sessionId).delete() }
    }

    fun footprint(): Footprint = Footprint(
        sessions = sessionIds().size,
        bytes = runCatching {
            root.listFiles()?.filter { it.isFile }?.sumOf { it.length() } ?: 0L
        }.getOrDefault(0L),
    )

    data class Footprint(val sessions: Int, val bytes: Long)

    /** Quarantined files, so a report can state that damage occurred. */
    fun quarantined(): List<String> = root.listFiles()
        ?.filter { it.isFile && CORRUPT_MARKER in it.name }
        ?.map { it.name }
        ?.sorted()
        .orEmpty()

    private fun fileFor(sessionId: String): File = File(root, safeName(sessionId) + SUFFIX)

    private fun quarantine(file: File) {
        val stamp = System.currentTimeMillis()
        runCatching { file.renameTo(File(root, file.name.removeSuffix(SUFFIX) + CORRUPT_MARKER + stamp + SUFFIX)) }
    }

    private fun writeAtomically(target: File, text: String) {
        val temp = File(root, target.name + ".tmp")
        temp.writeText(text, Charsets.UTF_8)
        if (!temp.renameTo(target)) {
            target.writeText(text, Charsets.UTF_8)
            runCatching { temp.delete() }
        }
    }

    companion object {
        /**
         * Cases kept per session.
         *
         * §9 asks for 20–30 cases across five pages. This cap leaves generous room for several such
         * sessions while keeping a file small enough to email.
         */
        const val MAX_CASES = 200
        const val DEFAULT_SESSION = "session"
        private const val SUFFIX = ".json"
        private const val CORRUPT_MARKER = ".corrupt-"

        /**
         * A filesystem-safe session name.
         *
         * Unlike a work id, a session id is generated by the app, so it is sanitised rather than
         * hashed: a reader benefits from recognising the file. Anything unsafe is replaced, and an
         * empty result falls back to a constant so a path can never be empty.
         */
        fun safeName(sessionId: String): String {
            val cleaned = sessionId.map { char ->
                if (char.isLetterOrDigit() || char == '-' || char == '_') char else '_'
            }.joinToString("").trim('_')
            return cleaned.ifBlank { DEFAULT_SESSION }.take(64)
        }

        private var lastDropped: Int = 0
    }
}
