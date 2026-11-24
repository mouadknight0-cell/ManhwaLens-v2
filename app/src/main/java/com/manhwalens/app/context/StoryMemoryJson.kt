package com.manhwalens.app.context

/**
 * JSON (de)serialization for persisted story memory.
 *
 * **Pure Kotlin, no `org.json`.** `org.json` is an Android stub on the unit-test classpath and
 * cannot be exercised on a plain JVM, which would leave the one thing most worth testing untested:
 * whether a fact survives a round trip, whether a corrupt file loses a whole work, and whether one
 * work's memory can leak into another's. The encoding is therefore written by hand here, and
 * [memory.StoryMemoryStoreFile] only moves bytes.
 *
 * Format, deliberately boring so it is migratable by hand if it ever has to be:
 *
 * ```
 * {"version":1,"entries":[{...}],"corrections":[{...}]}
 * ```
 *
 * Rules that are load-bearing:
 *
 *  - **Version is always written and always checked on read.** A file from a future version is
 *    refused rather than half-understood, because a mis-read fact is worse than a missing one.
 *  - **Decoding is tolerant per entry.** One malformed row is skipped and counted; it does not
 *    discard the file. A corrupt byte may not cost a reader their whole glossary.
 *  - **Unknown keys are ignored, missing optional fields take their declared default.** This is what
 *    makes a field added later readable by this version, and this file readable by a later one.
 *  - **Every string is escaped on write and unescaped on read**, including the Arabic in [MemoryEntry.value]:
 *    a term's Arabic must come back byte-identical or the engine will announce a "new" term every
 *    chapter, and the §9 consistency requirement quietly fails.
 */
object StoryMemoryJson {

    /** Bumped only for a change this decoder cannot tolerate. Current readers accept `< 100`. */
    const val VERSION = 1

    private const val MAX_VERSION = 100

    /** What a decoded file contained, including what had to be discarded to read it. */
    data class Decoded(
        val entries: List<MemoryEntry> = emptyList(),
        val corrections: List<StoryMemory.Correction> = emptyList(),
        /** Rows that were present but unusable. Surfaced rather than swallowed. */
        val skipped: Int = 0,
        /** The version that was read, or -1 when the text was not a memory file at all. */
        val version: Int = -1,
    ) {
        val isEmpty: Boolean get() = entries.isEmpty() && corrections.isEmpty()
    }

    fun encode(entries: List<MemoryEntry>, corrections: List<StoryMemory.Correction>): String {
        val rows = entries.sortedBy { it.sequence }.joinToString(",") { entry ->
            buildString {
                append('{')
                append("\"workId\":").append(quote(entry.workId)).append(',')
                append("\"kind\":").append(quote(entry.kind.name)).append(',')
                append("\"key\":").append(quote(entry.key)).append(',')
                append("\"value\":").append(quote(entry.value)).append(',')
                append("\"evidence\":").append(quote(entry.kindOfEvidence.name)).append(',')
                append("\"confidence\":").append(entry.confidence).append(',')
                append("\"source\":").append(quote(entry.source)).append(',')
                append("\"chapter\":").append(entry.chapter).append(',')
                append("\"page\":").append(entry.page).append(',')
                append("\"sequence\":").append(entry.sequence).append(',')
                append("\"also\":").append(quote(entry.also))
                append('}')
            }
        }
        val history = corrections.joinToString(",") { correction ->
            buildString {
                append('{')
                append("\"workId\":").append(quote(correction.workId)).append(',')
                append("\"kind\":").append(quote(correction.kind.name)).append(',')
                append("\"key\":").append(quote(correction.key)).append(',')
                append("\"previous\":").append(quote(correction.previous)).append(',')
                append("\"replacement\":").append(quote(correction.replacement)).append(',')
                append("\"reason\":").append(quote(correction.reason)).append(',')
                append("\"source\":").append(quote(correction.source))
                append('}')
            }
        }
        return "{\"version\":$VERSION,\"entries\":[$rows],\"corrections\":[$history]}"
    }

    /** Decode one work's file. Never throws: a broken file yields an empty, reported result. */
    fun decode(text: String?): Decoded {
        if (text.isNullOrBlank()) return Decoded()
        val root = runCatching { Json(text).parse() }.getOrNull() as? Map<*, *> ?: return Decoded()
        val version = (root["version"] as? Double)?.toInt() ?: return Decoded()
        if (version <= 0 || version > MAX_VERSION) return Decoded(version = version)

        var skipped = 0
        val entries = mutableListOf<MemoryEntry>()
        ((root["entries"] as? List<*>) ?: emptyList<Any?>()).forEach { row ->
            val entry = (row as? Map<*, *>)?.let { readEntry(it) }
            if (entry == null) skipped++ else entries += entry
        }
        val corrections = mutableListOf<StoryMemory.Correction>()
        ((root["corrections"] as? List<*>) ?: emptyList<Any?>()).forEach { row ->
            val correction = (row as? Map<*, *>)?.let { readCorrection(it) }
            if (correction == null) skipped++ else corrections += correction
        }
        return Decoded(entries, corrections, skipped, version)
    }

    private fun readEntry(row: Map<*, *>): MemoryEntry? = runCatching {
        MemoryEntry(
            workId = requireString(row, "workId"),
            kind = MemoryKind.valueOf(requireString(row, "kind")),
            key = requireString(row, "key"),
            value = requireString(row, "value"),
            kindOfEvidence = EvidenceKind.valueOf(requireString(row, "evidence")),
            confidence = (row["confidence"] as? Double) ?: 0.0,
            source = requireString(row, "source"),
            chapter = (row["chapter"] as? Double)?.toInt() ?: -1,
            page = (row["page"] as? Double)?.toInt() ?: -1,
            sequence = (row["sequence"] as? Double)?.toLong() ?: 0L,
            also = (row["also"] as? String) ?: "",
        )
    }.getOrNull()

    private fun readCorrection(row: Map<*, *>): StoryMemory.Correction? = runCatching {
        StoryMemory.Correction(
            workId = requireString(row, "workId"),
            kind = MemoryKind.valueOf(requireString(row, "kind")),
            key = requireString(row, "key"),
            previous = requireString(row, "previous"),
            replacement = requireString(row, "replacement"),
            reason = requireString(row, "reason"),
            source = requireString(row, "source"),
        )
    }.getOrNull()

    private fun requireString(row: Map<*, *>, name: String): String =
        row[name] as? String ?: throw IllegalArgumentException("missing $name")

    // ------------------------------------------------------------------ JSON primitives

    private fun quote(text: String): String = buildString {
        append('"')
        for (char in text) {
            when (char) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                // Control characters are not legal raw in JSON. Arabic is not escaped: it is emitted
                // as UTF-8 so the file stays readable and a term's Arabic round-trips exactly.
                else -> if (char < ' ') append("\\u").append(char.code.toString(16).padStart(4, '0'))
                else append(char)
            }
        }
        append('"')
    }

    /**
     * A small, strict JSON reader: objects, arrays, strings, numbers, booleans, null.
     *
     * Strict on purpose. A lenient reader would accept a truncated file and return half a glossary
     * without saying so, which is exactly the failure mode that makes a memory untrustworthy.
     */
    private class Json(private val text: String) {
        private var index = 0

        fun parse(): Any? {
            val value = readValue()
            skipWhitespace()
            if (index != text.length) throw IllegalArgumentException("trailing content at $index")
            return value
        }

        private fun readValue(): Any? {
            skipWhitespace()
            if (index >= text.length) throw IllegalArgumentException("unexpected end")
            return when (val char = text[index]) {
                '{' -> readObject()
                '[' -> readArray()
                '"' -> readString()
                't' -> readLiteral("true", true)
                'f' -> readLiteral("false", false)
                'n' -> readLiteral("null", null)
                else -> if (char == '-' || char.isDigit()) readNumber() else throw IllegalArgumentException("bad token $char")
            }
        }

        private fun readObject(): Map<String, Any?> {
            expect('{')
            val map = LinkedHashMap<String, Any?>()
            skipWhitespace()
            if (peek() == '}') { index++; return map }
            while (true) {
                skipWhitespace()
                val name = readString()
                skipWhitespace()
                expect(':')
                map[name] = readValue()
                skipWhitespace()
                when (peek()) {
                    ',' -> index++
                    '}' -> { index++; return map }
                    else -> throw IllegalArgumentException("expected , or } at $index")
                }
            }
        }

        private fun readArray(): List<Any?> {
            expect('[')
            val list = mutableListOf<Any?>()
            skipWhitespace()
            if (peek() == ']') { index++; return list }
            while (true) {
                list += readValue()
                skipWhitespace()
                when (peek()) {
                    ',' -> index++
                    ']' -> { index++; return list }
                    else -> throw IllegalArgumentException("expected , or ] at $index")
                }
            }
        }

        private fun readString(): String {
            expect('"')
            val out = StringBuilder()
            while (true) {
                if (index >= text.length) throw IllegalArgumentException("unterminated string")
                when (val char = text[index++]) {
                    '"' -> return out.toString()
                    '\\' -> {
                        if (index >= text.length) throw IllegalArgumentException("unterminated escape")
                        when (val escaped = text[index++]) {
                            '"' -> out.append('"')
                            '\\' -> out.append('\\')
                            '/' -> out.append('/')
                            'b' -> out.append('\b')
                            'f' -> out.append('\u000C')
                            'n' -> out.append('\n')
                            'r' -> out.append('\r')
                            't' -> out.append('\t')
                            'u' -> {
                                if (index + 4 > text.length) throw IllegalArgumentException("short \\u")
                                out.append(text.substring(index, index + 4).toInt(16).toChar())
                                index += 4
                            }
                            else -> throw IllegalArgumentException("bad escape $escaped")
                        }
                    }
                    else -> out.append(char)
                }
            }
        }

        private fun readNumber(): Double {
            val start = index
            if (peek() == '-') index++
            while (index < text.length && (text[index].isDigit() || text[index] in ".eE+-")) index++
            return text.substring(start, index).toDoubleOrNull()
                ?: throw IllegalArgumentException("bad number at $start")
        }

        private fun readLiteral(literal: String, value: Any?): Any? {
            if (!text.startsWith(literal, index)) throw IllegalArgumentException("bad literal at $index")
            index += literal.length
            return value
        }

        private fun peek(): Char? = if (index < text.length) text[index] else null

        private fun expect(char: Char) {
            skipWhitespace()
            if (peek() != char) throw IllegalArgumentException("expected $char at $index")
            index++
        }

        private fun skipWhitespace() {
            while (index < text.length && text[index].isWhitespace()) index++
        }
    }
}
