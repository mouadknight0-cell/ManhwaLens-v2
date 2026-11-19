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
            group.map { it.confidence }.average().toFloat(), group.first().languageHint)
        }
    }
    private fun canFollow(a: TextRegion, b: TextRegion): Boolean {
        val gap = b.top - a.bottom
        val height = maxOf(a.height, b.height)
        if (gap < 0 || gap > height * 0.9f) return false
        val overlap = minOf(a.right, b.right) - maxOf(a.left, b.left)
        if (overlap < minOf(a.width, b.width) * 0.65f) return false
        if (kotlin.math.abs(a.centerX - b.centerX) > maxOf(a.width, b.width) * 0.3f) return false
        return a.languageHint == b.languageHint
    }
}
