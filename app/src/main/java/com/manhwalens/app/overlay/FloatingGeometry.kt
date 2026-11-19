package com.manhwalens.app.overlay

/** Absolute LEFT/TOP coordinates, independent of layout direction and screen density. */
object FloatingGeometry {
    fun move(position: Int, delta: Int, available: Int, size: Int): Int =
        (position + delta).coerceIn(0, (available - size).coerceAtLeast(0))
    fun snap(x: Int, available: Int, size: Int): Int {
        val max = (available - size).coerceAtLeast(0)
        return if (x < max / 2) 0 else max
    }
}
