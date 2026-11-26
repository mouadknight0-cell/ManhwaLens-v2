package com.manhwalens.app.pipeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Exhaustive vocabulary protection for the OCR corrector.
 *
 * Phase 1's acceptance criteria say a known word must never be replaced by an unrelated word.
 * Asserting that for a handful of examples is not a proof; this walks **every** entry in
 * `reading-en.txt` through the real repair stage.
 *
 * The vocabulary is read from the same classpath resource the production code loads, so the
 * test cannot drift from the data it is protecting.
 */
class VocabularyProtectionTest {

    private companion object {
        /** The ranked list production uses, loaded the same way `ReadingEnglish` loads it. */
        val VOCABULARY: List<String> by lazy {
            requireNotNull(
                ReadingEnglish::class.java.getResourceAsStream("/reading-en.txt")
            ).bufferedReader().use { it.readLines() }.filter { it.isNotBlank() }
        }
    }

    /**
     * No vocabulary word may be rewritten into a *different* word by the repair stage.
     *
     * The contract is intentionally one-directional: the repair stage is allowed to be a no-op
     * (that is the common case), and it is allowed to change a word's *case*, but it may not
     * turn one vocabulary entry into a different vocabulary entry.
     */
    @Test
    fun noVocabularyWordBecomesADifferentWord() {
        val corrupted = mutableListOf<String>()
        for (word in VOCABULARY) {
            val upper = word.uppercase()
            val repaired = ReadingEnglish.repair(upper)
            // Compare case-insensitively: casing is the pipeline's business, identity is not.
            val sameWord = repaired.trim().lowercase() != upper.lowercase()
            val gainedOrLostSpace = repaired.trim().contains(' ') && !word.contains(' ')
            if (sameWord || gainedOrLostSpace) corrupted += "$word -> $repaired"
        }
        assertEquals(
            "vocabulary words were rewritten by the repair stage: " +
                corrupted.take(20).joinToString(", "),
            emptyList<String>(),
            corrupted,
        )
    }

    /**
     * The same guarantee through the production entry point (`TranslationInput.prepare`),
     * which is what `TranslationClient` actually calls.
     *
     * Run on a fixed stride rather than all 18,000 entries: `prepare` also runs name detection
     * and censusing, so the full sweep belongs in CI as a separate slow job. The stride is
     * deterministic, so the same words are covered on every run.
     */
    @Test
    fun productionEntryPointDoesNotRewriteKnownWords() {
        val corrupted = mutableListOf<String>()
        var checked = 0
        for (index in VOCABULARY.indices step 17) {
            val word = VOCABULARY[index]
            val upper = word.uppercase()
            val prepared = TranslationInput.prepare(upper, emptyList())
            checked++
            if (prepared.trim().lowercase() != upper.lowercase()) corrupted += "$word -> $prepared"
        }
        assertTrue("expected a meaningful sample, checked only $checked", checked > 500)
        assertEquals(
            "prepare() rewrote known words: " + corrupted.take(20).joinToString(", "),
            emptyList<String>(),
            corrupted,
        )
    }

    /**
     * Case must not change the decision.
     *
     * The guard used to depend on the raw spelling, so this walks a sample in three casings and
     * requires identical outcomes.
     */
    @Test
    fun casingDoesNotChangeTheDecision() {
        val corrupted = mutableListOf<String>()
        for (index in VOCABULARY.indices step 53) {
            val word = VOCABULARY[index]
            val upper = word.uppercase()
            val title = word.replaceFirstChar { it.uppercaseChar() }
            val fromUpper = ReadingEnglish.repair(upper).trim().lowercase()
            val fromTitle = ReadingEnglish.repair(title).trim().lowercase()
            val fromLower = ReadingEnglish.repair(word).trim().lowercase()
            if (fromUpper != fromTitle || fromTitle != fromLower) {
                corrupted += "$word ($upper/$title/$word -> $fromUpper/$fromTitle/$fromLower)"
            }
        }
        assertEquals(
            "casing changed the repair outcome: " + corrupted.take(10).joinToString(", "),
            emptyList<String>(),
            corrupted,
        )
    }

    /**
     * Apostrophe representation must not lose a known word.
     *
     * `YOURE`, `YOU'RE` and `YOU’RE` are one word: their lookup form must be identical, and
     * none of the three may be rewritten into an *unrelated* word.
     *
     * Note the deliberate distinction: the reviewed rules are allowed to insert an apostrophe
     * (`WASNT` -> `WASN'T`, `ISNT` -> `ISN'T`, both existing pinned behaviour). This phase does
     * not add any such rule of its own, but it must not break the ones that exist. So the
     * assertion is "the letters never change", not "the string never changes".
     */
    @Test
    fun apostropheRepresentationDoesNotLoseKnownWords() {
        val triples = listOf(
            Triple("YOURE", "YOU'RE", "YOU\u2019RE"),
            Triple("DONT", "DON'T", "DON\u2019T"),
            Triple("WASNT", "WASN'T", "WASN\u2019T"),
            Triple("ISNT", "ISN'T", "ISN\u2019T"),
            Triple("COULDNT", "COULDN'T", "COULDN\u2019T"),
        )
        for ((bare, ascii, curly) in triples) {
            val forms = setOf(
                ReadingEnglish.lookupForm(bare),
                ReadingEnglish.lookupForm(ascii),
                ReadingEnglish.lookupForm(curly),
            )
            assertEquals("lookup forms must agree for $bare", 1L, forms.size.toLong())
            listOf(bare, ascii, curly).forEach { form ->
                val repaired = ReadingEnglish.repair(form)
                // The letters must be preserved in order; only apostrophes may be added.
                assertEquals(
                    "$form was rewritten into a different word: '$repaired'",
                    form.replace("'", "").replace("\u2019", ""),
                    repaired.replace("'", "").replace("\u2019", ""),
                )
                // And it may never become an unrelated domain word.
                assertFalse(
                    "$form was replaced by a domain word: '$repaired'",
                    repaired.equals("HOUSE", true) || repaired.equals("HELL", true),
                )
            }
        }
    }

    /**
     * The domain list must be a *veto*, never a source of replacements for known words.
     *
     * Documents the measured scale of the defect this phase fixed: `suggest` would rewrite 237
     * of the vocabulary's own entries if it were ever allowed to run on them. Every one of those
     * 237 must be untouched in practice, because the vocabulary is consulted first.
     */
    @Test
    fun domainListWouldCorrectHundredsOfKnownWordsButNeverDoes() {
        var wouldCorrect = 0
        for (word in VOCABULARY) {
            if (ComicSpelling.suggest(word) != null) wouldCorrect++
        }
        assertTrue(
            "expected the domain list to be a real hazard (measured 237); saw $wouldCorrect",
            wouldCorrect > 100,
        )
        // ...and yet none of them changes, which is the property that matters.
        var changed = 0
        for (word in VOCABULARY) {
            if (ReadingEnglish.repair(word.uppercase()).trim().lowercase() != word.lowercase()) changed++
        }
        assertEquals("known words were changed by the domain list", 0L, changed.toLong())
    }

    /**
     * The only contraction the domain list can still rewrite is `YOURE`, and it is unreachable.
     *
     * `Isnt`/`Wasnt`-style forms are handled by reviewed string rules that run *before* the
     * token loop, so they never reach the domain list at all. Once the two measured corruptions
     * are vetoed, the single remaining proposal is `youre` -> `house`; `youre` is in the ranked
     * vocabulary at index 13295, so `repair` returns it unchanged. Reaching it would require the
     * vocabulary guard to be bypassed, which is exactly what this test would detect.
     */
    @Test
    fun remainingDomainSuggestionIsUnreachableThroughRepair() {
        assertNull("the two measured contractions are vetoed", ComicSpelling.suggest("youve"))
        assertNull("the two measured contractions are vetoed", ComicSpelling.suggest("theyll"))

        // `youre` is the residual hazard at the suggestion level...
        val residual = ComicSpelling.suggest("youre")
        if (residual != null) {
            assertEquals("house", residual)
            // ...and it must never be acted on, because the vocabulary already knows the word.
            assertTrue("youre must be in the ranked vocabulary to stay protected", ReadingEnglish.isCommonWord("youre"))
            assertEquals("YOURE", ReadingEnglish.repair("YOURE"))
            assertEquals("YOU'RE", ReadingEnglish.repair("YOU'RE"))
        }
    }

    /** The resource is the one production uses, and it is not empty. */
    @Test
    fun vocabularyResourceIsTheProductionOne() {
        assertTrue("the ranked vocabulary must be loaded", VOCABULARY.isNotEmpty())
        assertEquals("reading-en.txt size changed; re-measure the protection sweep", 18000L, VOCABULARY.size.toLong())
    }
}
