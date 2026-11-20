package com.manhwalens.app.pipeline

/** A visible address plus a broad dark bottom band is evidence of browser controls.
 * Never infer chrome solely from a number or dark comic panel.
 */
object BrowserChrome {
    fun bottomBarTop(width: Int, contentBottom: Int, addressVisible: Boolean, dark: (Int, Int) -> Boolean): Int {
        if (!addressVisible || width <= 0 || contentBottom <= 0) return contentBottom
        val limit = (contentBottom * 0.84).toInt()
        var top = contentBottom
        var misses = 0
        for (y in contentBottom - 1 downTo limit) {
            val xs = (0 until width step maxOf(1, width / 40))
            val darkRow = xs.count { dark(it, y) }.toFloat() / xs.count() >= 0.75f
            if (darkRow) { top = y; misses = 0 } else if (++misses >= 3) break
        }
        return if (contentBottom - top >= contentBottom * 0.025) top else contentBottom
    }
}
