package com.manhwalens.app.pipeline

import com.manhwalens.app.overlay.ReplacementSurface
import com.manhwalens.app.overlay.SurfacePlacement
import org.junit.Assert.*
import org.junit.Test

class IndependentAuditTest {
    @Test fun occlusionsUseCapturedCoordinatesBeforeGrouping() {
        val window = CapturedOcclusion(200, 800, 520, 850)
        val line = TextRegion("our status", 110, 405, 260, 422)
        assertTrue(window.intersects(line, 2f, 2f))
        assertFalse(window.intersects(line))
        assertFalse(window.intersects(TextRegion("nearby dialogue", 200, 850, 520, 880)))
        assertTrue(window.intersects(TextRegion("partly obscured", 190, 810, 210, 835)))
        assertFalse(CapturedOcclusion(0,0,0,0).intersects(line))
    }
    @Test fun screenClippingKeepsDarkMaskAndDoesNotInventAnEdgeRegion() {
        val screen = ReplacementSurface(0,24,500,800)
        val dark = 0xff252525.toInt()
        val region = TextRegion("test", 100,20,200,70, surface = ReplacementSurface(80,0,220,100,dark,true))
        val clipped = SurfacePlacement.visible(region, screen)!!
        assertEquals(24, clipped.top)
        assertEquals(98, clipped.left)
        assertEquals(dark, clipped.color)
        assertFalse(clipped.oval)
        assertNull(SurfacePlacement.visible(region.copy(left = 600, right = 700, surface = null), screen))
        assertNull(SurfacePlacement.visible(region, ReplacementSurface(0,0,0,0)))
    }
    @Test fun expandedOvalsCannotCoverOneAnotherEvenWithDisjointSourceBoxes() {
        val first = TextRegion("first", 30,30,70,70, surface = ReplacementSurface(0,0,110,100,-1,true))
        val second = TextRegion("second", 130,30,170,70, surface = ReplacementSurface(90,0,200,100,-1,true))
        val resolved = SurfacePlacement.resolveOverlaps(listOf(first,second))
        assertTrue(resolved.all { it.surface?.oval == false })
        assertEquals(first.text, resolved[0].text)
        assertEquals(28, resolved[0].surface!!.left)
        assertEquals(128, resolved[1].surface!!.left)
        assertTrue(SurfacePlacement.resolveOverlaps(listOf(first)).single().surface!!.oval)
    }
    @Test fun numericSoundEffectAndRejectedInputsDoNotRequireModel() {
        fun input(text: String, kind: RegionKind = RegionKind.DIALOGUE) = FrameTranslationPlan.Input(TextRegion(text,0,0,200,30),kind)
        val plan = FrameTranslationPlan.build(listOf(input("123"),input("BOOM",RegionKind.SOUND_EFFECT),input("M")),1000)
        assertFalse(FrameTranslationPlan.requiresModel(plan))
        assertFalse(FrameTranslationPlan.requiresModel(emptyList()))
        assertTrue(FrameTranslationPlan.requiresModel(FrameTranslationPlan.build(listOf(input("Where are you?")),1000)))
    }
    @Test fun confirmedNamesSurviveRepairAndOrdinaryOcrStillGetsRepaired() {
        assertEquals("B00K opened the DOOR.", TranslationInput.prepare("B00K opened the D00R.", listOf("B00K")))
        assertEquals("BOOK opened the DOOR.", TranslationInput.prepare("B00K opened the D00R.", emptyList()))
        assertEquals("B00K has 7000 books.", TranslationInput.prepare("B00K has 7000 books.", listOf("B00K")))
    }
    @Test fun nativeEmergencyBreaksMustNotSplitWordsOrDiacritics() {
        assertTrue(WordBoundary.splitsWord("الترجمة",3))
        assertTrue(WordBoundary.splitsWord("عَلَم",1))
        assertFalse(WordBoundary.splitsWord("مرحبا بكم",6))
        assertFalse(WordBoundary.splitsWord("hello-world",6))
        assertFalse(WordBoundary.splitsWord("مرحبا",0))
        assertFalse(WordBoundary.splitsWord("مرحبا",5))
    }
}
