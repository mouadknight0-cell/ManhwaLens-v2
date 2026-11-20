package com.manhwalens.app.overlay

/** Available rectangles exclude capture controls and already committed dialogue. */
object DirectPlacement {
    fun intersects(a: ReplacementSurface, b: ReplacementSurface) = a.left < b.right && a.right > b.left && a.top < b.bottom && a.bottom > b.top
    fun free(screen: ReplacementSurface, occupied: List<ReplacementSurface>): List<ReplacementSurface> {
        var spaces = listOf(screen)
        for (o in occupied) {
            spaces = spaces.flatMap { s ->
                if (!intersects(s,o)) listOf(s) else listOf(
                    s.copy(right = minOf(s.right,o.left)), s.copy(left = maxOf(s.left,o.right)),
                    s.copy(bottom = minOf(s.bottom,o.top)), s.copy(top = maxOf(s.top,o.bottom)))
            }.filter { it.width > 0 && it.height > 0 }.distinct().sortedByDescending { it.width.toLong()*it.height }.take(48)
        }
        return spaces
    }
    fun place(space: ReplacementSurface, width: Int, height: Int, source: ReplacementSurface): ReplacementSurface {
        val w=width.coerceIn(1,space.width); val h=height.coerceIn(1,space.height)
        val x=((source.left+source.right-w)/2).coerceIn(space.left,space.right-w)
        val y=((source.top+source.bottom-h)/2).coerceIn(space.top,space.bottom-h)
        return ReplacementSurface(x,y,x+w,y+h,source.color, reliable=true)
    }
}
