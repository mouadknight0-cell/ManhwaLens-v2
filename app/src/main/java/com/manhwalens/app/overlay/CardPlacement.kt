package com.manhwalens.app.overlay

/** Deterministic safe placement. Null means use one scrollable reading panel, never overlap. */
object CardPlacement {
    data class Box(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val width get() = right - left
        val height get() = bottom - top
        fun overlaps(other: Box, gap: Int) = left < other.right + gap && right + gap > other.left &&
            top < other.bottom + gap && bottom + gap > other.top
    }
    fun place(preferred: Box, bounds: Box, occupied: List<Box>, gap: Int): Box? {
        if (preferred.width > bounds.width || preferred.height > bounds.height) return null
        val xs = listOf(preferred.left.coerceIn(bounds.left, bounds.right - preferred.width)) +
            occupied.flatMap { listOf(it.right + gap, it.left - gap - preferred.width) }
        val ys = listOf(preferred.top.coerceIn(bounds.top, bounds.bottom - preferred.height)) +
            occupied.flatMap { listOf(it.bottom + gap, it.top - gap - preferred.height) }
        return xs.distinct().flatMap { x -> ys.distinct().map { y -> Box(x, y, x + preferred.width, y + preferred.height) } }
            .filter { it.left >= bounds.left && it.top >= bounds.top && it.right <= bounds.right && it.bottom <= bounds.bottom }
            .filter { box -> occupied.none { box.overlaps(it, gap) } }
            .minByOrNull { kotlin.math.abs(it.left - preferred.left) + kotlin.math.abs(it.top - preferred.top) }
    }
}
