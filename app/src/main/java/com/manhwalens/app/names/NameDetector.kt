package com.manhwalens.app.names

import com.manhwalens.app.pipeline.TextRegion

/** A detected proper-name occurrence inside a text. */
data class NameHit(
    val name: String,
    val start: Int,
    val end: Int,
    val source: Source,
) {
    enum class Source { GLOSSARY, KOREAN_SURNAME, CHINESE_SURNAME, CAPITALIZED }
}

/**
 * Detects Chinese- and Korean-origin character names written in Latin
 * transliteration inside English dialogue.
 *
 * Strategies (ordered, longest match wins):
 *  1. Known names from the series glossary (exact / hyphen-insensitive).
 *  2. Romanized Korean family names + given token(s): "Kim Jiwoo", "Park Seo-yeon".
 *  3. Romanized Chinese family names + given token(s): "Wang Wei", "Zhang Sanfeng".
 *
 * Sentence-initial common words may be flagged; the user can prune the
 * glossary, and [NameShield] keeps detection conservative by requiring a
 * second-token shape for families.
 */
object NameDetector {

    // Romanized Korean family names (McCune-Reischauer / Revised, common spellings)
    private val KOREAN_SURNAMES = setOf(
        "Kim", "Lee", "Park", "Choi", "Jung", "Lim", "Kang", "Jeong", "Cho", "Shin",
        "Yoon", "Moon", "Seo", "Kwon", "An", "Im", "Baek", "Song", "Ryu", "Hong",
        "Ko", "Jung", "Ahn", "Chang", "Woo", "Ma", "Gang", "Sim", "Chun", "Byun",
        "Son", "Min", "Yang", "Hwang", "Huh", "Yeo", "No", "Gong", "Gu", "Noh", "Yun",
        "Jo", "Jun", "Jeon", "Na", "Gil", "Heo", "Uhm", "Paek", "Baik",
    )

    // Single-syllable Chinese surnames (Hanyu Pinyin) — most frequent first
    private val CHINESE_SURNAMES = setOf(
        "Wang", "Li", "Zhang", "Liu", "Chen", "Yang", "Huang", "Zhao", "Wu", "Zhou",
        "Xu", "Sun", "Ma", "Zhu", "Hu", "Guo", "He", "Gao", "Lin", "Luo", "Zheng",
        "Liang", "Xie", "Song", "Tang", "Han", "Cao", "Deng", "Xiao", "Cheng", "Cai",
        "Pan", "Yuan", "Yu", "Dong", "Wei", "Lv", "Shen", "Zeng", "Xiong", "Fu",
        "Jiang", "Cui", "Bai", "Xia", "Yue", "Xue", "Yan", "Lei", "Ruan", "Long",
        "Ye", "Dou", "Fang", "Jin", "Yin", "Tan", "Wan",
        // Double-syllable surnames
        "Ouyang", "Shangguan", "Sima", "Zhuge", "Dongfang", "Situ",
    )

    // Uppercase comic typography is not evidence of a proper name. Ambiguous
    // English words require an explicit glossary entry, not automatic shielding.
    fun safeAutomaticName(name: String): Boolean {
        val letters = name.filter { it.isLetter() }
        val first = name.trim().substringBefore(' ').lowercase()
        return letters.any { it.isLowerCase() } && first !in setOf("no", "he", "an", "im", "long", "son", "sun", "moon", "song")
    }

    private val wordRegex = Regex("[A-Za-z][A-Za-z'’-]*")

    /**
     * @param text raw region text
     * @param glossaryNames known character names (any capitalization)
     */
    fun find(text: String, glossaryNames: List<String> = emptyList()): List<NameHit> {
        if (text.isBlank()) return emptyList()
        val hits = mutableListOf<NameHit>()

        // 1. glossary matches (longest first)
        val tokens = tokenPositions(text)
        for (name in glossaryNames.sortedByDescending { it.length }) {
            val parts = name.trim().split(Regex("[\\s-]+")).filter { it.isNotEmpty() }
            if (parts.isEmpty()) continue
            val pattern = Regex("(?<![\\p{L}\\p{N}_])" +
                parts.joinToString("[\\s-]+") { Regex.escape(it) } + "(?![\\p{L}\\p{N}_])", RegexOption.IGNORE_CASE)
            pattern.findAll(text).forEach { match ->
                hits.add(NameHit(match.value, match.range.first, match.range.last + 1, NameHit.Source.GLOSSARY))
            }
        }

        // 2. surname-driven detection
        for (i in tokens.indices) {
            val (tok, start, end) = tokens[i]
            val family = when {
                KOREAN_SURNAMES.any { it.equals(tok, ignoreCase = true) } -> NameHit.Source.KOREAN_SURNAME
                CHINESE_SURNAMES.any { it.equals(tok, ignoreCase = true) } -> NameHit.Source.CHINESE_SURNAME
                else -> null
            } ?: continue
            if (!tok.first().isUpperCase()) continue
            var spanEnd = end
            var consumed = 1
            // Chinese: family + up to 2 given tokens; Korean: family + 1 given token (+ optional hyphen token)
            val limit = if (family == NameHit.Source.CHINESE_SURNAME) 2 else 1
            while (consumed < limit + 1 && i + consumed < tokens.size) {
                val (next, nStart, nEnd) = tokens[i + consumed]
                if (nStart - spanEnd > 1 || !next.first().isUpperCase()) break
                spanEnd = nEnd
                consumed++
                if (consumed - 1 >= limit) break
            }
            if (consumed > 1 && safeAutomaticName(text.substring(start, spanEnd))) {
                hits.add(NameHit(text.substring(start, spanEnd), start, spanEnd, family))
            }
        }

        return dedupe(hits)
    }

    /** Detect names in many texts (regions) — convenience for callers. */
    fun findAll(regions: List<TextRegion>, glossaryNames: List<String>): List<NameHit> =
        regions.flatMap { find(it.text, glossaryNames) }

    // ── internals ────────────────────────────────────────────────

    private fun tokenPositions(text: String): List<Triple<String, Int, Int>> =
        wordRegex.findAll(text).map { Triple(it.value, it.range.first, it.range.last + 1) }.toList()

    private data class SpanRange(val first: Int, val last: Int)

    private fun matchFrom(tokens: List<Triple<String, Int, Int>>, startIdx: Int, parts: List<String>): SpanRange? {
        if (startIdx + parts.size > tokens.size) return null
        for (candidate in startIdx..tokens.size - parts.size) {
            if (parts.indices.all { tokens[candidate + it].first.equals(parts[it], ignoreCase = true) })
                return SpanRange(candidate, candidate + parts.size - 1)
        }
        return null
    }

    private fun dedupe(hits: List<NameHit>): List<NameHit> {
        fun rank(source: NameHit.Source): Int = when (source) {
            NameHit.Source.GLOSSARY -> 3
            NameHit.Source.KOREAN_SURNAME -> 2
            NameHit.Source.CHINESE_SURNAME -> 2
            NameHit.Source.CAPITALIZED -> 1
        }
        val sorted = hits.sortedWith(compareBy({ it.start }, { -(it.end - it.start) }, { -rank(it.source) }))
        val out = mutableListOf<NameHit>()
        var coveredEnd = -1
        for (hit in sorted) {
            if (hit.start < coveredEnd) continue // overlaps a longer/earlier hit
            out.add(hit)
            coveredEnd = hit.end
        }
        return out
    }
}
