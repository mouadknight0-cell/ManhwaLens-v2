package com.manhwalens.app.pipeline

/** Reconstruct across ML Kit block boundaries only with nearby aligned lines AND
 * visual evidence of a continuous light balloon between them. No sentence templates. */
object DialogueAssembler {
    fun assemble(lines: List<TextRegion>, bridgeIsClear: (TextRegion, TextRegion) -> Boolean): List<TextRegion> {
        val groups = mutableListOf<MutableList<TextRegion>>()
        for (line in lines.sortedWith(compareBy({ it.top }, { it.left }))) {
            val group = groups.filter { canFollow(it.last(), line) && bridgeIsClear(it.last(), line) }
                .minByOrNull { line.top - it.last().bottom }
            if (group == null) groups.add(mutableListOf(line)) else group.add(line)
        }
        return groups.map { group -> TextRegion(
            SentenceReconstructor.joinLines(group.map { it.text }),
            group.minOf { it.left }, group.minOf { it.top }, group.maxOf { it.right }, group.maxOf { it.bottom },
            group.map { it.confidence }.average().toFloat(), group.first().languageHint, sourceLineCount = group.size)
        }
    }
    private fun canFollow(a: TextRegion, b: TextRegion): Boolean {
        val gap = b.top - a.bottom
        val height = maxOf(a.height, b.height)
        // Small box overlap is OCR geometry jitter, not necessarily a shared row.
        val smallerHeight = minOf(a.height, b.height)
        // ML Kit line boxes routinely overlap by 25-35% of their height (extra line
        // spacing padding and ascender/descender boxes). Requiring only 50% clearance
        // silently dropped wrapped lines of the *same sentence*. Keep a real same-row
        // veto but allow wrapped lines with realistic box overlap.
        if (smallerHeight <= 0 || b.top - a.top < smallerHeight * 0.25f) return false
        if (gap < -smallerHeight * 0.35f || gap > height * 0.9f) return false
        val overlap = minOf(a.right, b.right) - maxOf(a.left, b.left)
        if (overlap < minOf(a.width, b.width) * 0.65f) return false
        // Centred dialogue lines share a centre; a wrap only has to stand over the previous line.
        if (overlap < minOf(a.width, b.width) * 0.95f &&
            kotlin.math.abs(a.centerX - b.centerX) > maxOf(a.width, b.width) * 0.3f) return false
        // A sentence that ends on a terminal mark is complete: the next vertically separate line
        // belongs to a different utterance. Lines whose boxes overlap (a real wrap) stay together.
        val tail = a.text.trimEnd('"', '”', '’', '\'', ')', ' ')
        val terminal = tail.endsWith('.') || tail.endsWith('!') || tail.endsWith('?') || tail.endsWith('…')
        if (terminal && gap > 0) return false
        return a.languageHint == b.languageHint
    }
}
