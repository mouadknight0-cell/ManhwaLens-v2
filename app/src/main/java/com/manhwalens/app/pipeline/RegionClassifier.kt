package com.manhwalens.app.pipeline

/**
 * Classifies text regions so narration, dialogue and sound effects are
 * handled appropriately. Heuristic (no ML model), tuned for Latin-script
 * manhwa text.
 */
enum class RegionKind { DIALOGUE, NARRATION, SOUND_EFFECT, UNKNOWN }

object RegionClassifier {

    private val SFX_VERB_NOOK = Regex("^[^a-z]{0,3}(BOOM|BANG|CRASH|SMASH|GRRR|BAM|POW|WHAM|SLAM|RUMBLE|SWOOSH|HISS|ROAR|ZZZT|THUD|KAPOW|SPLAT|CRACK|KRAK|THWUMP|SHOOM|VOOM|CLANG|CRUNCH)[!.…-]*$", RegexOption.IGNORE_CASE)

    fun classify(text: String): RegionKind {
        val t = text.trim()
        if (t.isEmpty()) return RegionKind.UNKNOWN
        // Sound effects: short, mostly uppercase, no lowercase words.
        val letters = t.filter { it.isLetter() }
        val isMostlyUpper = letters.isNotEmpty() && letters.count { it.isUpperCase() }.toFloat() / letters.length >= 0.85f
        if (t.length <= 28 && isMostlyUpper && SFX_VERB_NOOK.matches(t)) {
            return RegionKind.SOUND_EFFECT
        }
        // Dialogue with quotation marks
        if (t.contains('"') || t.contains('“') || t.contains('”') || t.contains('«')) return RegionKind.DIALOGUE
        // Rhetorical/emotional punctuation strongly implies speech
        if (t.endsWith("!") || t.endsWith("?") || t.endsWith("!?") || t.endsWith("?!")) return RegionKind.DIALOGUE
        // Narrative pages: full sentences without speech markers
        if (t.length >= 60) return RegionKind.NARRATION
        return RegionKind.DIALOGUE
    }

    /** Dialogue is translated first; sound effects and narration afterwards. */
    fun readingPriority(kind: RegionKind): Int = when (kind) {
        RegionKind.DIALOGUE -> 0
        RegionKind.NARRATION -> 1
        RegionKind.SOUND_EFFECT -> 2
        RegionKind.UNKNOWN -> 1
    }
}
