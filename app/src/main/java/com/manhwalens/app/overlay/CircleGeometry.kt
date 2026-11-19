package com.manhwalens.app.overlay

import kotlin.math.ceil
import kotlin.math.hypot

object CircleGeometry {
    fun innerSide(diameter: Int): Int = (diameter * 0.68).toInt().coerceAtLeast(1)
    fun diameter(width: Int, height: Int, minimum: Int, maximum: Int): Int =
        ceil(hypot(width.toDouble(), height.toDouble())).toInt().coerceIn(minimum.coerceAtMost(maximum), maximum)
    fun covers(circle: CardPlacement.Box, source: CardPlacement.Box): Boolean {
        val cx = (circle.left + circle.right) / 2.0
        val cy = (circle.top + circle.bottom) / 2.0
        val radius = circle.width / 2.0
        return listOf(source.left to source.top, source.left to source.bottom,
            source.right to source.top, source.right to source.bottom).all { (x, y) -> hypot(x - cx, y - cy) <= radius }
    }
}
