package com.manhwalens.app.pipeline

/** Defense against accidentally translating our own captured UI, not a replacement for fresh capture. */
object SourceScreenGuard {
    private val markers = listOf("translate screen now", "ready when you are", "capture session", "disable floating bubble", "read manhwa in natural arabic")
    fun isOwnInterface(regions: List<TextRegion>): Boolean {
        val text = regions.joinToString(" ") { it.text }.lowercase()
        return markers.count { text.contains(it) } >= 2
    }
}
