package com.manhwalens.app.pipeline

import org.junit.Assert.*
import org.junit.Test

class PhoneReportRegressionTest {
    private fun r(text: String, l: Int = 100, t: Int = 100, right: Int = 300, b: Int = 124) =
        TextRegion(text, l, t, right, b, languageHint = "en")

    @Test fun overlappingOcrBoxesCanStillBeSuccessiveLines() {
        val lines = listOf(r("WHEN DID THEY GET", 431, 862, 577, 926), r("BEHIND ME?", 424, 925, 585, 960))
        assertEquals("WHEN DID THEY GET BEHIND ME?", DialogueAssembler.assemble(lines) { _, _ -> true }.single().text)
    }
    @Test fun regionNameTailIsNotSplitAtOnePixelOverlap() {
        val lines = listOf(r("ITS NOT SOMETHING YOU WOULD FIND IN", 329, 972, 567, 1042), r("THE UN NAM REGION!", 320, 1041, 559, 1074))
        assertEquals(1, DialogueAssembler.assemble(lines) { _, _ -> true }.size)
    }
    @Test fun shortLastLineJoinsWithVisualEvidence() {
        val lines = listOf(r("TOWARDS", 85, 635, 301, 663), r("HIM...", 158, 672, 223, 693))
        assertEquals("TOWARDS HIM...", DialogueAssembler.assemble(lines) { _, _ -> true }.single().text)
        assertEquals(2, DialogueAssembler.assemble(lines) { _, _ -> false }.size)
    }
    @Test fun sameRowAndLargeOverlapDoNotJoin() {
        assertEquals(2, DialogueAssembler.assemble(listOf(r("ONE"), r("TWO", t = 102, b = 126))) { _, _ -> true }.size)
        assertEquals(2, DialogueAssembler.assemble(listOf(r("ONE"), r("TWO", t = 115, b = 139))) { _, _ -> true }.size)
    }
    @Test fun touchingAndSlightlyOverlappingLightBoxesAreSampled() {
        for (top in listOf(123, 124, 128)) {
            assertTrue(LightCorridor.isClear(r("A"), r("B", t = top, b = top + 24), 720, 1600) { _, _ -> true })
        }
    }
    @Test fun sparseGlyphEdgesDoNotBreakLightCorridor() {
        assertTrue(LightCorridor.isClear(r("A"), r("B", t = 130, b = 154), 720, 1600) { x, _ -> x % 10 != 0 })
    }
    @Test fun singleDarkOutlineRowVetoesJoin() {
        assertFalse(LightCorridor.isClear(r("A"), r("B", t = 130, b = 154), 720, 1600) { _, y -> y != 127 })
    }
    @Test fun outOfFrameIsNeverSampled() {
        assertFalse(LightCorridor.isClear(r("A", l = -1), r("B", l = -1, t = 130, b = 154), 720, 1600) { _, _ -> error("out of bounds") })
    }
    @Test fun repairsOnlyConstrainedEnglishArtifacts() {
        assertEquals("AND... I AM THE MONSTER", EnglishOcrRepair.repair("AND... | AM THE MONSTER"))
        assertEquals("I am PLANNING TO LEAVE", EnglishOcrRepair.repair("IAM PLANNING TO LEAVE"))
        listOf("WHAT HAVE Y DONE?", "ITS MINE", "THE UN NAM REGION!", "IAM", "A | B").forEach {
            assertEquals(it, EnglishOcrRepair.repair(it))
        }
    }
    @Test fun newDashboardSignatureRequiresAllSpecificMarkers() {
        assertTrue(SourceScreenGuard.isOwnInterface(listOf(r("Ready"), r("Setup needed"), r("Capture: Active"), r("See all"), r("Settings"))))
        assertFalse(SourceScreenGuard.isOwnInterface(listOf(r("READY!"), r("SETUP NEEDED"), r("SEE ALL"))))
        assertFalse(SourceScreenGuard.isOwnInterface(listOf(r("Capture: the fortress"), r("Settings"))))
    }
}
