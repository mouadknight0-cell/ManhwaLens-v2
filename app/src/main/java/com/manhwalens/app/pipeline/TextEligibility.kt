package com.manhwalens.app.pipeline

/**
 * Translation eligibility for OCR regions — deliberately independent of comic-surface
 * detection. A closed balloon, a flat fill, an oval estimate or a stroke patch are
 * *rendering strategy* signals, never a hard requirement for translating text.
 *
 * The only reasons text is kept away from the translator are protections that existed
 * before: no letters, reader/browser chrome (enforced upstream by ReaderContentFilter),
 * exact UI page-control labels, and confidently-empty noise. Everything else — narration,
 * captions, thought text, standalone scene text, background-embedded text, UNKNOWN or
 * DIALOGUE-classified text outside bubbles — is eligible. When no safe background exists
 * the renderer switches to a non-destructive strategy instead of dropping the text.
 */
object TextEligibility {
    /**
     * @param strategy rendering hint for a downstream consumer: "bubble" when pixel evidence
     *   supports background replacement, "free_text" when it does not.
     */
    data class Assessment(
        val eligible: Boolean,
        val strategy: String,
        val reason: String,
        val signals: List<String>,
    )

    /** Exact, case-insensitive whole-text matches that are page/app controls, not manhwa.
     *  Deliberately excludes short answer words ("no", "yes", "ok") that real dialogue uses. */
    private val uiControlLabels = setOf(
        "settings", "menu", "close", "back", "next", "previous", "prev", "home", "search", "share",
        "download", "downloads", "bookmark", "bookmarks", "library", "contents", "chapters", "options",
        "delete", "save", "cancel", "apply", "retry", "skip", "continue", "more", "report", "subscribe",
        "sign in", "log in", "sign up", "read now", "next chapter", "previous chapter", "chapter list",
        "table of contents", "free plan", "upgrade", "search your tabs", "inactive items", "minimize",
        "rotate screen", "auto rotate", "brightness", "font size", "page view", "reading mode",
    )

    private const val MIN_LETTERS = 2
    private const val MIN_HEIGHT_PX = 8
    private const val MIN_CONFIDENCE = 0.15f

    fun isUiControlLabel(text: String): Boolean {
        val t = text.trim().lowercase().replace(Regex("\\s+"), " ")
        return t in uiControlLabels
    }

    fun assess(region: TextRegion, kind: RegionKind, enclosed: Boolean = false): Assessment {
        val text = region.text.trim()
        val letters = text.count(Char::isLetter)
        val surface = region.surface
        val signals = listOf(
            "letters=$letters",
            "height=${region.height}",
            "width=${region.width}",
            "confidence=${if (region.confidence.isFinite()) "%.2f".format(region.confidence) else "NaN"}",
            "kind=$kind",
            "enclosed=$enclosed",
            "oval=${surface?.oval == true}",
            "mask=${surface?.mask?.kind ?: "none"}",
            "patch=${surface?.patch != null}",
            "reliable=${surface?.reliable == true}",
            "capsule=${surface?.capsule == true}",
        )
        fun reject(reason: String) = Assessment(false, "rejected", reason, signals)

        if (letters < MIN_LETTERS) return reject("no_letters")
        if (region.height < MIN_HEIGHT_PX) return reject("tiny_glyphs")
        if (isUiControlLabel(text)) return reject("ui_control_label")
        if (region.confidence.isFinite() && region.confidence < MIN_CONFIDENCE) return reject("low_confidence_noise")

        // Any surface evidence selects the replacement strategy; none is required for eligibility.
        // A flat fill alone is never "bubble": SurfaceDetector only proves colour uniformity,
        // not a closed balloon contour, so it stays on the non-destructive plate strategy.
        if (surface?.mask != null || surface?.oval == true || surface?.patch != null || enclosed) {
            return Assessment(true, "bubble", "surface_evidence", signals)
        }
        if (surface?.reliable == true) {
            return Assessment(true, "free_text", "flat_background_only", signals)
        }
        val why = when (kind) {
            RegionKind.NARRATION -> "narration_free_text"
            RegionKind.UNKNOWN -> "unknown_kind_free_text"
            RegionKind.DIALOGUE -> "dialogue_without_bubble"
            RegionKind.SOUND_EFFECT -> "sound_effect_free_text"
        }
        return Assessment(true, "free_text", why, signals)
    }
}
