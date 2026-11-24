package com.manhwalens.app.context

/**
 * Reconstructs reading order and panel grouping from region geometry plus text cues.
 *
 * The release warns that "geometric order alone is not enough" (§5B). That is right, and the
 * concrete reason is a defect RC9 already hit: OCR jitter put the same-row fragment `"EVEN"` two
 * pixels *lower* than the rest of its line, so ordering by `top` emitted `THOUGH ... EVEN`. Ordering
 * must therefore be row-aware, not a plain `top` sort.
 *
 * What this adds over a sort:
 *
 *  - **rows**, grouped by vertical overlap, ordered top-to-bottom, left-to-right within a row;
 *  - **panels**, as maximal vertical bands separated by real gaps, because a manhwa page reads
 *    panel by panel and not as one continuous column;
 *  - **kind guesses** with confidence, not as facts;
 *  - a **reading-order confidence** that reflects how ambiguous the layout actually was: two
 *    regions with an identical `top` in different columns are genuinely ambiguous, and the engine
 *    should say so rather than silently pick one.
 *
 * It does not attempt panel *detection* from pixels. No panel-boundary detector exists in the
 * project, and inventing one here would be the "large vision system" the release says not to build
 * without need. Bands from gaps are the cheap, honest approximation, and their confidence is
 * reported as such.
 */
object ReadingOrder {

    /** Vertical overlap needed for two regions to count as sharing a row. Same tolerance as RC9. */
    private const val ROW_OVERLAP_FRACTION = 0.6f

    data class Grouping(
        val ordered: List<TextObservation>,
        /** Reading-order confidence for the frame as a whole, 0..1. */
        val confidence: Double,
        val panelCount: Int,
        /** Codes describing why confidence was reduced. */
        val warnings: List<String> = emptyList(),
    )

    fun arrange(observations: List<TextObservation>): Grouping {
        if (observations.isEmpty()) return Grouping(emptyList(), 1.0, 0)
        if (observations.size == 1) {
            return Grouping(listOf(observations.single().copy(readingOrder = 0, panelIndex = 0)), 1.0, 1)
        }

        val byTop = observations.sortedWith(compareBy({ it.top }, { it.left }, { it.text }))
        val rows = buildRows(byTop)
        val inline = rows.flatMap { row -> row.sortedWith(compareBy({ it.left }, { it.top }, { it.text })) }

        // Panels: a vertical gap larger than a normal line step separates bands.
        val panels = splitPanels(inline)
        val panelOf = mutableMapOf<TextObservation, Int>()
        panels.forEachIndexed { index, members -> members.forEach { panelOf[it] = index } }

        val ordered = panels.flatMap { members ->
            members.sortedWith(compareBy({ it.centerY }, { it.left }, { it.text }))
        }.mapIndexed { index, observation ->
            observation.copy(readingOrder = index, panelIndex = panelOf[observation] ?: 0)
        }

        val warnings = mutableListOf<String>()
        var confidence = 1.0

        // Ambiguity 1: two regions starting at the same height in clearly different columns, within
        // one panel. Right-to-left manga ordering exists, but this app targets left-to-right manhwa;
        // either way the layout is ambiguous and the engine should say so.
        // Two regions whose vertical spans genuinely overlap but which sit in separated columns.
        val sameTop = inline.firstOrNull { first ->
            inline.any { second ->
                first !== second &&
                    minOf(first.bottom, second.bottom) - maxOf(first.top, second.top) > 0 &&
                    (second.left - first.right) > first.width
            }
        }
        if (sameTop != null) {
            confidence -= 0.15
            warnings += "ambiguous_columns"
        }

        // Ambiguity 2: a region whose vertical band overlaps two panels, i.e. a narration box that
        // spans a boundary.
        val spanning = inline.count { observation ->
            val band = panels.count { members ->
                members.any { it.top <= observation.centerY && observation.centerY <= it.bottom }
            }
            band > 1
        }
        if (spanning > 0) {
            confidence -= 0.10
            warnings += "spanning_region"
        }

        // Ambiguity 3: no usable gap at all -- everything is one band, which may be correct or may
        // mean the gap was too small to detect.
        if (panels.size == 1 && inline.size > 3 && sameTop == null) {
            confidence -= 0.10
            warnings += "single_band"
        }

        // An unknown kind is *reported* but must not lower reading-order confidence: the label is a
        // separate axis from position, and mixing them made a clean two-line layout report 0.95.
        // A caller that cares about the label reads `warnings` and the per-region kind instead.
        if (inline.any { it.kind == RegionKindGuess.UNKNOWN }) {
            warnings += "unclassified_text"
        }

        return Grouping(ordered, confidence.coerceIn(0.0, 1.0), panels.size, warnings)
    }

    /**
     * Rows: maximal runs whose boxes overlap vertically by at least [ROW_OVERLAP_FRACTION] of the
     * shorter box. A plain `top` sort is not a total order for same-row text because OCR jitter
     * moves one fragment up or down by a few pixels.
     */
    private fun buildRows(sortedByTop: List<TextObservation>): List<List<TextObservation>> {
        val rows = mutableListOf<MutableList<TextObservation>>()
        for (observation in sortedByTop) {
            // Test against *every* open row, not just the last one: a tall narration box can share a
            // baseline with two shorter rows that were started after it. Assigning to the first row
            // that matches keeps the result deterministic.
            val target = rows.lastOrNull { row ->
                row.any { existing ->
                    val overlap = minOf(existing.bottom, observation.bottom) - maxOf(existing.top, observation.top)
                    overlap >= minOf(existing.height, observation.height) * ROW_OVERLAP_FRACTION
                }
            }
            if (target != null) target.add(observation) else rows.add(mutableListOf(observation))
        }
        return rows
    }

    /**
     * Split into vertical bands. The threshold is relative to the text height, so it adapts to a
     * phone screen instead of hard-coding pixels: a gap larger than one line step is a band break.
     */
    private fun splitPanels(ordered: List<TextObservation>): List<List<TextObservation>> {
        if (ordered.isEmpty()) return emptyList()
        val lineHeight = ordered.map { it.height }.filter { it > 0 }.sorted().let {
            if (it.isEmpty()) 0 else it[it.size / 2]
        }
        val threshold = maxOf(lineHeight, 24)
        val panels = mutableListOf<MutableList<TextObservation>>()
        var current = mutableListOf(ordered.first())
        var bottom = ordered.first().bottom
        for (observation in ordered.drop(1)) {
            if (observation.top - bottom > threshold) {
                panels.add(current)
                current = mutableListOf(observation)
            } else {
                current.add(observation)
            }
            bottom = maxOf(bottom, observation.bottom)
        }
        panels.add(current)
        return panels
    }

    /**
     * Guess what kind of text a region is, with the reason recorded.
     *
     * Reuses the project's existing classifier thresholds rather than inventing new ones, but keeps
     * its own enum so `RegionKind` and the translation plan stay untouched.
     */
    fun classify(text: String): Pair<RegionKindGuess, String> {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return RegionKindGuess.UNKNOWN to "empty"
        val letters = trimmed.filter { it.isLetter() }
        val mostlyUpper = letters.isNotEmpty() && letters.count { it.isUpperCase() }.toDouble() / letters.length >= 0.85
        if (trimmed.length in 1..28 && mostlyUpper && looksLikeSoundEffect(trimmed)) {
            return RegionKindGuess.SOUND_EFFECT to "short_uppercase_sound"
        }
        if (trimmed.any { it == '"' || it == '\u201C' || it == '\u201D' || it == '\u00AB' }) {
            return RegionKindGuess.DIALOGUE to "quoted"
        }
        if (trimmed.endsWith("!") || trimmed.endsWith("?")) return RegionKindGuess.DIALOGUE to "emotional_punctuation"
        // A sign or document is typically a short all-caps or title-case label without sentence
        // punctuation; distinguished from SFX by containing real words.
        if (trimmed.length <= 40 && mostlyUpper && trimmed.split(Regex("\\s+")).size >= 2) {
            return RegionKindGuess.SIGN_OR_MESSAGE to "short_uppercase_words"
        }
        if (trimmed.length >= 60) return RegionKindGuess.NARRATION to "long_sentence"
        return RegionKindGuess.DIALOGUE to "default"
    }

    private val SOUND_WORDS = listOf(
        "GYU", "BOOM", "BANG", "CRASH", "SMASH", "GRRR", "BAM", "POW", "WHAM", "SLAM",
        "RUMBLE", "SWOOSH", "HISS", "ROAR", "ZZZT", "THUD", "KAPOW", "SPLAT", "CRACK",
        "KRAK", "THWUMP", "SHOOM", "VOOM", "CLANG", "CRUNCH", "WHOOSH", "THUMP", "SWISH",
    )

    private fun looksLikeSoundEffect(text: String): Boolean {
        val core = text.trim { !it.isLetter() }.uppercase()
        if (core.isEmpty()) return false
        if (SOUND_WORDS.any { it == core }) return true
        // Reduplicated noise: "GYUAAAAA", "NOOOOOO" -- one dominant letter repeated.
        val letters = core.filter { it.isLetter() }
        if (letters.length >= 4) {
            val most = letters.groupingBy { it }.eachCount().values.max()
            if (most.toDouble() / letters.length >= 0.5) return true
        }
        return false
    }
}
