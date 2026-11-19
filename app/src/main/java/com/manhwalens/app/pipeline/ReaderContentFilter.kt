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
    fun keep(region: TextRegion, contentTop: Int, contentBottom: Int): Boolean {
        if (region.top < contentTop || region.bottom > contentBottom) return false
        return !address.containsMatchIn(region.text)
    }
}
