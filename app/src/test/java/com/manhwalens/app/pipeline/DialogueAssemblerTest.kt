package com.manhwalens.app.pipeline
import org.junit.Assert.*
import org.junit.Test
class DialogueAssemblerTest {
    private fun line(text: String, left: Int, top: Int, right: Int) = TextRegion(text, left, top, right, top + 24, languageHint = "en")
    @Test fun reconstructsFragmentedBalloonBeforeTranslation() {
        val lines = listOf(line("IT WOULD", 442, 1175, 565), line("ONLY BE NATURAL FOR THE ENTIRE POISON SECT TO", 394, 1207, 610), line("FALL TO HIS HANDS!", 430, 1240, 580))
        val result = DialogueAssembler.assemble(lines) { _, _ -> true }
        assertEquals(1, result.size)
        assertEquals("IT WOULD ONLY BE NATURAL FOR THE ENTIRE POISON SECT TO FALL TO HIS HANDS!", result.single().text)
    }
    @Test fun prefixWhileStaysWithSentence() {
        val result = DialogueAssembler.assemble(listOf(line("WHILE", 392, 330, 460), line("HE DUG HIMSELF BACK UP", 328, 361, 530))) { _, _ -> true }
        assertEquals("WHILE HE DUG HIMSELF BACK UP", result.single().text)
    }
    @Test fun borderOrDarkGutterPreventsMerging() {
        assertEquals(2, DialogueAssembler.assemble(listOf(line("ONE", 100, 100, 230), line("TWO", 100, 130, 230))) { _, _ -> false }.size)
    }
    @Test fun adjacentSpeakersAndDistantTextRemainSeparate() {
        val lines = listOf(line("ONE", 10, 100, 130), line("TWO", 140, 100, 250), line("THREE", 10, 400, 130))
        assertEquals(3, DialogueAssembler.assemble(lines) { _, _ -> true }.size)
    }
}
