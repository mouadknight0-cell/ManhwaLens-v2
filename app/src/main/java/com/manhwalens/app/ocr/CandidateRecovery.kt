package com.manhwalens.app.ocr

import com.manhwalens.app.pipeline.TextRegion

/**
 * Completeness test for a recovery candidate, used by [ColorRecovery] when deciding whether a
 * re-read of a crop may replace text that was already recognized.
 *
 * RC4 decided that question with a score comparison and then deleted *every* overlapping original:
 *
 * ```
 * if (same.none { score(it) > score(hit) }) { output.removeAll(same); output.add(hit) }
 * ```
 *
 * A short clean crop routinely scores higher than a long noisy one, so a three-line re-read could
 * destroy the only five-line transcription of a balloon. That is the reported "4-5 lines became 2",
 * and it cannot be caught by any confidence threshold because it is a loss of *content*, not of
 * quality.
 *
 * Deliberately narrow: this answers one question — would adopting the candidate lose words the
 * original had? It does not rank candidates, and it never prefers one on score.
 */
internal object CandidateRecovery {

    /**
     * Words as the pipeline means them: letter/digit runs, case-folded, punctuation dropped.
     *
     * Digits are kept because "50" carries meaning, and apostrophes stay inside words so that
     * "don't" is not split into "don" + "t" and falsely reported as recovered.
     */
    fun tokens(text: String): Set<String> =
        text.lowercase()
            .split(Regex("[^\\p{L}\\p{N}']+"))
            .filter { it.isNotBlank() }
            .toSet()

    /**
     * True when [candidate] is missing at least one word that [original] contains.
     *
     * An identical or richer candidate returns false and may replace the original; a candidate that
     * dropped a word returns true and is refused. An original with no words at all cannot be
     * "lost", so the candidate is allowed to replace it.
     */
    fun losesText(original: TextRegion, candidate: TextRegion): Boolean {
        val source = tokens(original.text)
        if (source.isEmpty()) return false
        val replacement = tokens(candidate.text)
        if (replacement.isEmpty()) return true
        return !replacement.containsAll(source)
    }

    /** Words the original had that the candidate does not. Used for diagnostics only. */
    fun missingWords(original: TextRegion, candidate: TextRegion): List<String> {
        val replacement = tokens(candidate.text)
        return tokens(original.text).filter { it !in replacement }.sorted()
    }
}
