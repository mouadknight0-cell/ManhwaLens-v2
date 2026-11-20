package com.manhwalens.app.pipeline

/** Immutable screen-space window bounds, snapshotted with the retained frame.
 * Reject contaminated OCR lines BEFORE grouping. Never guess the covered words.
 */
data class CapturedOcclusion(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    fun intersects(region: TextRegion, scaleX: Float = 1f, scaleY: Float = 1f): Boolean =
        right > left && bottom > top && region.width > 0 && region.height > 0 &&
            region.left * scaleX < right && region.right * scaleX > left &&
            region.top * scaleY < bottom && region.bottom * scaleY > top
}
