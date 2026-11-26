package com.manhwalens.app.overlay

import com.manhwalens.app.pipeline.TextRegion

/** Never relocate a mask: intersect with the visible screen, retaining sampled color.
 * A clipped ellipse is not a new smaller ellipse; use the source rectangle instead.
 */
object SurfacePlacement {
    fun source(region: TextRegion, color: Int = -1, reliable: Boolean = false) = ReplacementSurface(
        region.left - 2, region.top - 2, region.right + 2, region.bottom + 2, color, reliable = reliable)

    fun visible(region: TextRegion, bounds: ReplacementSurface): ReplacementSurface? {
        val evidence = region.surface
        val candidate = if (evidence != null && evidence.left >= bounds.left && evidence.top >= bounds.top &&
            evidence.right <= bounds.right && evidence.bottom <= bounds.bottom) evidence
        else source(region, evidence?.color ?: -1, evidence?.reliable == true)
        val clipped = candidate.copy(left = maxOf(candidate.left, bounds.left), top = maxOf(candidate.top, bounds.top),
            right = minOf(candidate.right, bounds.right), bottom = minOf(candidate.bottom, bounds.bottom))
        return clipped.takeIf { it.width > 0 && it.height > 0 }
    }

    /** Two expanded ovals can overlap even if neither covers the other's source.
     * Resolve against the original candidate set, not a progressively modified list.
     */
    fun resolveOverlaps(regions: List<TextRegion>): List<TextRegion> {
        val candidates = regions.map { it.surface ?: source(it) }
        return regions.mapIndexed { index, region ->
            val surface = candidates[index]
            if ((surface.oval || surface.mask!=null) && candidates.indices.any { other -> other != index && intersects(surface, candidates[other]) }) {
                region.copy(surface = source(region, surface.color, surface.reliable))
            } else region
        }
    }

    internal fun intersects(a:ReplacementSurface,b:ReplacementSurface):Boolean {
        if(!rectHits(a,b))return false
        val mask=a.mask ?: return if(b.mask!=null) intersects(b,a) else true
        val other=b.mask
        var j=0
        for(i in mask.spans.indices step 3){val y=a.top+mask.spans[i]*mask.step; val bottom=y+mask.step
            if(y>=b.bottom)break
            val l=a.left+mask.spans[i+1]*mask.step;val r=a.left+mask.spans[i+2]*mask.step
            if(bottom<=b.top || l>=b.right || r<=b.left)continue
            if(other==null)return true
            while(j<other.spans.size && b.top+(other.spans[j]+1)*other.step<=y)j+=3
            var k=j;while(k<other.spans.size && b.top+other.spans[k]*other.step<bottom){
                if(b.left+other.spans[k+1]*other.step<r && b.left+other.spans[k+2]*other.step>l)return true;k+=3
            }
        };return false
    }
    private fun rectHits(a: ReplacementSurface, b: ReplacementSurface) =
        a.left < b.right && a.right > b.left && a.top < b.bottom && a.bottom > b.top
}
