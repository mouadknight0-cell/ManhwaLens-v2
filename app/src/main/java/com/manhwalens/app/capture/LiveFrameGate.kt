package com.manhwalens.app.capture

import kotlin.math.abs

/** Small luminance samples only; no OCR or content storage. Clock is supplied for deterministic tests. */
class LiveFrameGate(private val settleMs: Long = 180) {
    private var anchor: IntArray? = null
    private var changedAt = 0L
    private var consumed = -1L
    var revision = 0L
        private set

    private var waitingSince:Long?=null
    var fraction=0.0; private set
    private var motionSince:Long?=null
    @Synchronized fun observe(sample: IntArray, nowMs: Long, confirmMotion:Boolean=false): Boolean {
        val previous = anchor
        val changed = previous == null || previous.size != sample.size || significant(previous, sample)
        if(!changed) motionSince=null
        if(changed && previous!=null && confirmMotion) {
            val first=motionSince ?: nowMs.also { motionSince=it }
            if(nowMs-first<90) return false // a single compositor transition is not a scroll
        }
        if (changed) {
            motionSince=null
            anchor = sample.copyOf()
            if(waitingSince==null) waitingSince=nowMs
            changedAt = nowMs
            revision++
        }
        return changed
    }
    // Compare against the last changed anchor, not merely the previous frame: slow drift accumulates.
    private fun significant(a: IntArray, b: IntArray): Boolean {
        var compared = 0
        var changed = 0
        var total = 0L
        for (i in a.indices) {
            if (a[i] < 0 || b[i] < 0) continue // own overlay / system chrome excluded
            val delta = abs(a[i] - b[i])
            compared++
            total += delta
            if (delta >= 10) changed++
        }
        fraction=if(compared>0) changed.toDouble()/compared else 0.0
        return compared > 0 && (changed.toDouble() / compared >= 0.015 || total.toDouble() / compared >= 2.5)
    }
    @Synchronized fun ready(nowMs: Long): Boolean = anchor != null && consumed != revision && (nowMs - changedAt >= settleMs || nowMs-(waitingSince ?: nowMs)>=1200)
    @Synchronized fun claim(nowMs: Long): Long? {
        if (!ready(nowMs)) return null
        waitingSince=null
        consumed = revision
        return revision
    }
    @Synchronized fun reset() { waitingSince=null; motionSince=null; anchor = null; consumed = -1; revision++; changedAt = 0 }
    @Synchronized fun retry(nowMs: Long) { consumed = -1; changedAt = nowMs }
}
