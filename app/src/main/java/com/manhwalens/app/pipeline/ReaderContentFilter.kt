package com.manhwalens.app.pipeline

/** Narrow exclusions: real system insets and URL-shaped chrome, not short dialogue. */
object ReaderContentFilter {
    private val address = Regex("(?i)(?:https?\\s*:\\s*/\\s*/|www\\s*\\.|[a-z0-9-]+\\s*\\.\\s*(?:com|org|net|io|app)(?:\\s*/|\\b))")
    /** Multiple specific chrome markers, never a single generic word such as tabs. */
    fun isTabOverview(regions: List<TextRegion>): Boolean {
        val text = regions.joinToString(" ") { it.text }.lowercase()
        return text.contains("search your tabs") &&
            (text.contains("inactive items") || text.contains("unused or duplicate tabs"))
    }
    fun hasAddress(regions: List<TextRegion>): Boolean = regions.any { address.containsMatchIn(it.text) }
    fun isLoadingPage(regions: List<TextRegion>): Boolean {
        val text = regions.joinToString(" ") { it.text }.lowercase()
        return text.contains("progressive image loading") &&
            (text.contains("stay up to date") || text.contains("manga district"))
    }
    private val timestamp=Regex("(?i)^(?:(?:today|yesterday)\\s+)?\\d{1,2}:\\d{2}(?:\\s*[ap]m)?$")
    private val uiLabels=setOf("settings","photos","collections","artifacts","projects","preview","free plan","upgrade","search your tabs")
    fun keep(region: TextRegion, contentTop: Int, contentBottom: Int): Boolean {
        if (region.top < contentTop || region.bottom > contentBottom) return false
        val t=region.text.trim()
        return !address.containsMatchIn(t) && !timestamp.matches(t) && t.lowercase() !in uiLabels && !t.all { it.isDigit() || it.isWhitespace() }
    }
}
