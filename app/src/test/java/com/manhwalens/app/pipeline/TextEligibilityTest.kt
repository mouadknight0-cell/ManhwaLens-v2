package com.manhwalens.app.pipeline

import org.junit.Assert.*
import org.junit.Test

/**
 * Regression coverage for the outside-bubble loss: OCR found the text, the
 * translation pipeline never saw it, reason=no_comic_surface / component_at_crop_edge.
 */
class TextEligibilityTest {
    private fun region(text: String, l: Int = 40, t: Int = 200, r: Int = 300, b: Int = 236,
                       confidence: Float = Float.NaN, surface: com.manhwalens.app.overlay.ReplacementSurface? = null) =
        TextRegion(text, l, t, r, b, confidence, "en", surface, 1)

    @Test fun loneShortTextOutsideAnyBubbleIsEligible() {
        val a = TextEligibility.assess(region("OH LOOK, ANOTHER LONER."), RegionClassifier.classify("OH LOOK, ANOTHER LONER."), enclosed = false)
        assertTrue("OH LOOK, ANOTHER LONER. must stay translatable", a.eligible)
        assertEquals("free_text", a.strategy)
        assertEquals("dialogue_without_bubble", a.reason)
    }

    @Test fun longNarrationOnWhiteBackgroundWithoutBubbleIsEligible() {
        val text = "THE STORM THAT BEGAN THREE DAYS AGO STILL HAD NOT BROKEN, AND THE VILLAGE WAITED IN SILENCE."
        val r = region(text, 20, 80, 700, 120, confidence = 0.91f)
        val a = TextEligibility.assess(r, RegionKind.NARRATION, enclosed = false)
        assertTrue(a.eligible)
        assertEquals("free_text", a.strategy)
        assertEquals("narration_free_text", a.reason)
    }

    @Test fun shortCaptionAboveTheArtworkIsEligible() {
        val a = TextEligibility.assess(region("THREE DAYS LATER…"), RegionKind.NARRATION, enclosed = false)
        assertTrue(a.eligible)
    }

    @Test fun textOnNonUniformBackgroundWithoutSurfaceIsEligible() {
        // No mask, no oval, no flat patch, no enclosure — illustrated backdrop, still eligible.
        val r = region("I CANNOT BELIEVE IT.", 100, 400, 340, 436, confidence = 0.72f)
        val a = TextEligibility.assess(r, RegionKind.DIALOGUE, enclosed = false)
        assertTrue(a.eligible)
        assertEquals("free_text", a.strategy)
    }

    @Test fun unknownAndDialogueClassificationsOutsideBubblesAreEligible() {
        val figure = "I NEVER WANT TO SEE YOU AGAIN."
        for (kind in listOf(RegionKind.UNKNOWN, RegionKind.DIALOGUE, RegionKind.NARRATION)) {
            val a = TextEligibility.assess(region(figure), kind, enclosed = false)
            assertTrue("kind=$kind must remain translatable", a.eligible)
        }
    }

    @Test fun cropEdgeContourFailureNoLongerDropsTheText() {
        // Contour detection rejects with component_at_crop_edge upstream; eligibility must
        // not turn the missing mask into an ineligibility. Patch evidence or free-text both pass.
        val withPatch = region("BEHIND YOU.", 0, 990, 320, 1030, surface = com.manhwalens.app.overlay.ReplacementSurface(
            0, 990, 320, 1030, 0xff888888.toInt(), reliable = false, patch = IntArray(8)
        ))
        val failedMask = region("BEHIND YOU.", 0, 990, 320, 1030, surface = com.manhwalens.app.overlay.ReplacementSurface(
            0, 990, 320, 1030, 0xffffffff.toInt(), reliable = true, capsule = true
        ))
        assertTrue(TextEligibility.assess(withPatch, RegionKind.UNKNOWN, enclosed = false).eligible)
        assertTrue(TextEligibility.assess(failedMask, RegionKind.UNKNOWN, enclosed = false).eligible)
    }

    @Test fun bubbleEvidenceSelectsBubbleStrategy() {
        val contour = region("STAY BACK!", surface = com.manhwalens.app.overlay.ReplacementSurface(
            10, 10, 210, 70, 0xffffffff.toInt(), reliable = true,
            mask = com.manhwalens.app.overlay.BubbleMask(4, intArrayOf(0, 0, 50), intArrayOf(4, 4, 46, 20), 0xff000000.toInt())
        ))
        val oval = region("STAY BACK!", surface = com.manhwalens.app.overlay.ReplacementSurface(
            10, 10, 210, 70, 0xffffffff.toInt(), oval = true, reliable = true))
        val enclosed = region("STAY BACK!")
        assertEquals("bubble", TextEligibility.assess(contour, RegionKind.DIALOGUE, false).strategy)
        assertEquals("bubble", TextEligibility.assess(oval, RegionKind.DIALOGUE, false).strategy)
        assertEquals("bubble", TextEligibility.assess(enclosed, RegionKind.DIALOGUE, enclosed = true).strategy)
    }

    @Test fun flatUniformBackgroundIsNeverTreatedAsAProvenBubble() {
        // Colour uniformity is not a closed balloon. It keeps the text eligible but selects
        // the non-destructive rendering strategy instead of an opaque replacement.
        val flat = region("STAY BACK!", surface = com.manhwalens.app.overlay.ReplacementSurface(
            10, 10, 210, 70, 0xfffdfdfd.toInt(), reliable = true))
        val a = TextEligibility.assess(flat, RegionKind.DIALOGUE, enclosed = false)
        assertTrue(a.eligible)
        assertEquals("free_text", a.strategy)
        assertEquals("flat_background_only", a.reason)
    }

    @Test fun translationIsNeverGatedOnASurfaceBeingPresent() {
        // Direct guard for the reported defect: every combination of kind and surface evidence
        // (including none at all) must stay eligible for translatable dialogue.
        val text = "I NEVER WANT TO SEE YOU AGAIN."
        val surfaces = listOf(
            null,
            com.manhwalens.app.overlay.ReplacementSurface(10, 10, 210, 70, 0xffffffff.toInt(), reliable = false),
            com.manhwalens.app.overlay.ReplacementSurface(10, 10, 210, 70, 0xff202020.toInt(), reliable = true),
            com.manhwalens.app.overlay.ReplacementSurface(10, 10, 210, 70, 0xff202020.toInt(), capsule = true),
            com.manhwalens.app.overlay.ReplacementSurface(10, 10, 210, 70, 0xff202020.toInt(), patch = IntArray(8)),
        )
        for (kind in RegionKind.entries) for (surface in surfaces) for (enclosed in listOf(false, true)) {
            val a = TextEligibility.assess(region(text, surface = surface), kind, enclosed)
            assertTrue("kind=$kind surface=$surface enclosed=$enclosed was dropped: ${a.reason}", a.eligible)
        }
    }

    @Test fun uiControlsChromeAndNoiseStayExcluded() {
        assertEquals("ui_control_label", TextEligibility.assess(region("Next chapter"), RegionKind.UNKNOWN, false).reason)
        assertEquals("ui_control_label", TextEligibility.assess(region("SETTINGS"), RegionKind.UNKNOWN, false).reason)
        assertEquals("ui_control_label", TextEligibility.assess(region("Search your tabs"), RegionKind.UNKNOWN, false).reason)
        assertEquals("ui_control_label", TextEligibility.assess(region("Table of contents"), RegionKind.UNKNOWN, false).reason)
        assertEquals("no_letters", TextEligibility.assess(region("152"), RegionKind.UNKNOWN, false).reason)
        assertEquals("low_confidence_noise", TextEligibility.assess(region("??? misread noise", confidence = 0.10f), RegionKind.UNKNOWN, false).reason)
    }

    @Test fun genuineLowerShortDialogueIsNotRejectedAsUi() {
        // Must not be swallowed by the page-control list.
        assertTrue(TextEligibility.assess(region("No!"), RegionKind.DIALOGUE, false).eligible)
        assertTrue(TextEligibility.assess(region("Okay."), RegionKind.DIALOGUE, false).eligible)
    }
}
