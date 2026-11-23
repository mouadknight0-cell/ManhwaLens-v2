package com.manhwalens.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.view.View
import androidx.test.platform.app.InstrumentationRegistry
import com.manhwalens.app.ocr.TextRecognitionClient
import com.manhwalens.app.overlay.DialogueType
import com.manhwalens.app.overlay.ReplacementSurface
import com.manhwalens.app.pipeline.DialogueAssembler
import com.manhwalens.app.pipeline.SentenceReconstructor
import com.manhwalens.app.pipeline.TextRegion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 6 case matrix for multi-line reading and multi-line display.
 *
 * Every case reports the full funnel so a failure can be attributed to a stage instead of guessed
 * at: OCR lines found -> lines surviving assembly -> lines entering translation -> translated
 * length -> lines actually drawn -> whether anything was clipped. The tests assert the invariants
 * that matter (no source word lost, no distinct bubbles joined, a long translation drawn in full)
 * and log the numbers so they can be read back from CI rather than inferred from a green tick.
 */
class MultiLineCaseMatrixTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext


    private fun stage(name: String, value: String) {
        android.util.Log.i("ManhwaLensFunnel", "$name $value")
    }

    /**
     * Renders [lines] of Latin text in a light bubble so the real OCR client reads them.
     *
     * The canvas is measured from the text, not fixed: an earlier version used a constant width and
     * silently painted the last glyph of a long line onto the bitmap edge. OCR then reported "dow"
     * for "down", which looks exactly like a pipeline defect but is a fixture defect. Sizing to the
     * measured advance keeps this test able to tell those apart.
     */
    private fun bubble(vararg lines: String, ink: Int = Color.BLACK): Bitmap {
        val lineH = 46
        val pen = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ink
            textSize = 30f
            typeface = Typeface.DEFAULT
        }
        val left = 42f
        val widest = lines.maxOf { pen.measureText(it) }
        val widthPx = kotlin.math.ceil(left + widest + 42f).toInt()
        val bmp = Bitmap.createBitmap(widthPx, 70 + lines.size * lineH, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.rgb(28, 28, 30))
        val bubblePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        c.drawRoundRect(18f, 18f, (widthPx - 18).toFloat(), (bmp.height - 18).toFloat(), 40f, 40f, bubblePaint)
        lines.forEachIndexed { i, line -> c.drawText(line, left, (86 + i * lineH).toFloat(), pen) }
        return bmp
    }

    private fun readAll(bmp: Bitmap): List<TextRegion> = kotlinx.coroutines.runBlocking {
        // Log the raw recogniser output as well as the assembled result. Without this, a word
        // loss cannot be attributed: it is either the recogniser itself or a later stage that
        // rewrote the text, and those need completely different fixes.
        val audit = mutableListOf<String>()
        val recognized = TextRecognitionClient().recognize(bmp, "en", audit = { audit.add(it) })
        val assembled = DialogueAssembler.assemble(recognized) { _, _ -> true }.sortedBy { it.top }
        // Only report a stage that actually changed the text. Emitting these unconditionally put
        // ten annotations per run into a channel GitHub caps at ten, which pushed the MATRIX summary
        // out of the retained set and hid the very measurements the report needs.
        if (recognized.map { it.text } != assembled.map { it.text }) {
            stage("ocr-before-assembly", "count=${recognized.size} texts=${recognized.map { it.text }}")
            stage("ocr-after-assembly", "count=${assembled.size} texts=${assembled.map { it.text }}")
            audit.forEach { stage("ocr-audit", it) }
        }
        assembled
    }

    private fun words(s: String) = s.lowercase()
        .split(Regex("[^\\p{L}\\p{N}']+"))
        .filter { it.isNotBlank() }

    /**
     * Asserts the two things that actually matter about reading a multi-line bubble:
     * every source word is present, and the region covers the whole vertical extent of the text.
     *
     * `sourceLineCount` is deliberately NOT asserted here. It records how many blocks the assembler
     * chose to bridge, and the recogniser is free to return one paragraph block for visually
     * adjacent lines; a count of 1 with all words present is a correct read, not a lost line.
     * Whether every line was seen is measured by the region's span instead.
     */
    private fun assertKeepsEveryWord(source: List<String>, read: List<TextRegion>, label: String, lineH: Int = 46) {
        val got = words(read.joinToString(" ") { "${it.text} " }).toSet()
        val missing = words(source.joinToString(" ")).distinct().filter { it !in got }
        assertTrue("$label: OCR lost source words $missing (read: ${read.map { it.text }})", missing.isEmpty())
        assertEquals("$label: one bubble must stay one utterance", 1, read.size)
        if (source.size > 1) {
            val span = read.first().bottom - read.first().top
            // All lines are drawn lineH apart; the region must reach the last one.
            val needed = (source.size - 1) * lineH
            assertTrue("$label: region must span every line, span=$span needs>=$needed", span >= needed)
        }
    }

    // ---- reading: 1, 3, 4 and 5 source lines, all words retained ----------------------------------

    @Test
    fun oneLineIsReadCompletely() = kotlinx.coroutines.runBlocking {
        val lines = arrayOf("We never wanted this fight.")
        val bmp = bubble(*lines)
        try {
            val read = readAll(bmp)
            assertKeepsEveryWord(lines.toList(), read, "1-line")
        } finally { bmp.recycle() }
    }

    @Test
    fun threeLineBubbleReadsAllLines() = kotlinx.coroutines.runBlocking {
        val lines = arrayOf(
            "I told you before the sun went down",
            "that this road only leads to ruin and",
            "you still chose to walk it beside me.",
        )
        val bmp = bubble(*lines)
        try {
            val read = readAll(bmp)
            assertKeepsEveryWord(lines.toList(), read, "3-line")
        } finally { bmp.recycle() }
    }

    @Test
    fun fourLineBubbleReadsAllLines() = kotlinx.coroutines.runBlocking {
        val lines = arrayOf(
            "The gate closes at midnight and the",
            "guard never opens it twice for the",
            "same face, so decide now whether you",
            "are coming back with me or staying here.",
        )
        val bmp = bubble(*lines)
        try {
            val read = readAll(bmp)
            assertKeepsEveryWord(lines.toList(), read, "4-line")
        } finally { bmp.recycle() }
    }

    @Test
    fun fiveLineBubbleReadsAllLines() = kotlinx.coroutines.runBlocking {
        val lines = arrayOf(
            "There was a time when I could",
            "look at that face and believe",
            "every single word that came out",
            "of it, but the man standing in",
            "front of me now is a stranger.",
        )
        val bmp = bubble(*lines)
        try {
            val read = readAll(bmp)
            assertKeepsEveryWord(lines.toList(), read, "5-line")
        } finally { bmp.recycle() }
    }

    // ---- grouping must not join distinct bubbles --------------------------------------------------

    @Test
    fun twoSeparateBubblesStaySeparate() = kotlinx.coroutines.runBlocking {
        val pen = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 30f }
        val top = listOf("You came all this way", "just to ask me that?")
        val bottom = listOf("Then let me give you", "an answer worth the trip.")
        val widthPx = kotlin.math.ceil(42f + (top + bottom).maxOf { pen.measureText(it) } + 42f).toInt()
        val bmp = Bitmap.createBitmap(widthPx, 460, Bitmap.Config.ARGB_8888)
        try {
            val c = Canvas(bmp)
            c.drawColor(Color.rgb(28, 28, 30))
            val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
            val right = (widthPx - 18).toFloat()
            c.drawRoundRect(18f, 18f, right, 200f, 40f, 40f, fill)
            c.drawRoundRect(18f, 250f, right, 442f, 40f, 40f, fill)
            c.drawText(top[0], 42f, 80f, pen)
            c.drawText(top[1], 42f, 126f, pen)
            c.drawText(bottom[0], 42f, 312f, pen)
            c.drawText(bottom[1], 42f, 358f, pen)
            val read = readAll(bmp)

            assertEquals("two visually separate bubbles must not become one utterance", 2, read.size)
            // Neither bubble may lose a word, and neither may absorb the other's text.
            val first = read.first().text.lowercase()
            val second = read.last().text.lowercase()
            assertTrue("first bubble must keep its own words: $first", first.contains("came") && first.contains("ask"))
            assertTrue("second bubble must keep its own words: $second", second.contains("give") && second.contains("trip"))
            assertTrue("bubbles must not be swapped or merged: $first | $second", !first.contains("trip") && !second.contains("came"))
        } finally { bmp.recycle() }
    }

    // ---- display: a translation must be drawn over 3, 4 and 5 lines without clipping --------------

    /** Draws [message] into a box and returns the line count actually rasterised. */
    private fun drawnLines(message: String, w: Int, h: Int, sp: Int): Pair<Int, Boolean> {
        val type = DialogueType.measure(context, message, w, sp, mask = null)
        return type.layout.lineCount to type.complete
    }

    @Test
    fun longTranslationIsDrawnAcrossThreeToFiveLines() {
        val message = "لم أكن أتوقع أن تعود إلى هذا المكان بعد كل ما قلته ذلك اليوم، لكن ها أنت واقف " +
            "أمامي تطلب مني أن أصدق كل كلمة تقولها وكأن شيئا لم يحدث بيننا أبدا."
        // Narrow columns force more lines; each one must stay complete (no ellipsis, no clip).
        val results = intArrayOf(320, 260, 220).map { w ->
            val (lines, complete) = drawnLines(message, w, 400, 15)

            assertTrue("a $w px column must draw the translation complete", complete)
            assertTrue("a $w px column must use at least three lines, got $lines", lines >= 3)
            lines
        }
        assertTrue("narrowing the column must not reduce the line count: $results",
            results.zipWithNext().all { (a, b) -> b >= a })
    }

    @Test
    fun everyReadableSizeDrawsTheWholeTranslation() {
        // The ladder must never silently drop text at a smaller size.
        for (sp in 15 downTo 11) {
            val (lines, complete) = drawnLines(
                "هذه ليست الطريقة التي اتفقنا عليها عندما بدأنا هذا كله معا في تلك الليلة البعيدة.",
                300, 500, sp,
            )

            assertTrue("sp=$sp must render complete text", complete)
            assertTrue("sp=$sp must not collapse to a single line", lines >= 2)
        }
    }

    @Test
    fun translationLongerThanSourceIsNotTruncated() {
        // Arabic is typically longer than the English it replaces; the view must still show all of it.
        val message = "أنا لا أفهم كيف وصلنا إلى هنا، ولا أفهم لماذا ما زلت تحاول إقناعي بأن كل هذا كان من أجل مصلحتي."
        val runOnMain = InstrumentationRegistry.getInstrumentation()
        runOnMain.runOnMainSync {
            val surface = ReplacementSurface(0, 0, 300, 520)
            val view = com.manhwalens.app.overlay.ReplacementTranslationView(context, message, surface)
            view.measure(
                View.MeasureSpec.makeMeasureSpec(300, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(520, View.MeasureSpec.EXACTLY),
            )
            view.layout(0, 0, 300, 520)

            assertTrue("a tall box must accept the translation at some readable size", view.replacementReady)
            assertTrue("the chosen size must stay at or above the legible floor", view.fittedSp >= 11f)
            assertTrue("the chosen size must not exceed the preferred size", view.fittedSp <= 15f)
        }
    }

    @Test
    fun realThreeLineFixtureKeepsItsLines() = kotlinx.coroutines.runBlocking {
        val inst = InstrumentationRegistry.getInstrumentation()
        val bmp = inst.context.assets.open("xiaomi-three-lines.png").use { BitmapFactory.decodeStream(it) }
        try {
            val read = readAll(bmp)

            assertTrue("the shipped 3-line fixture must be readable", read.isNotEmpty())
            val joined = read.joinToString(" ") { it.text }
            assertTrue("the fixture must be read as words, not glyph fragments: $joined", words(joined).size >= 5)
        } finally { bmp.recycle() }
    }

    @Test
    fun sentenceJoinNeverDropsAWord() {
        // The joining stage is where a wrapped line could silently vanish.
        val parts = listOf("I told you the truth", "about the night we left", "and you still doubt me")
        val joined = SentenceReconstructor.joinLines(parts)
        val norm = { s: String -> s.lowercase().split(Regex("[^\\p{L}\\p{N}']+")).filter { it.isNotBlank() } }
        assertEquals("joining must preserve every word in order", norm(parts.joinToString(" ")), norm(joined))

    }

    /**
     * One consolidated, quotable summary of the whole matrix.
     *
     * Every other test asserts a single property. This one walks the 3/4/5-line cases and the real
     * display path end to end and emits one table line each, so the Phase 8 report can quote measured
     * numbers instead of describing intent.
     */
    @Test
    fun caseMatrixSummary() = kotlinx.coroutines.runBlocking {
        val cases = listOf(
            "3-line" to arrayOf(
                "I told you before the sun went down",
                "that this road only leads to ruin and",
                "you still chose to walk it beside me.",
            ),
            "4-line" to arrayOf(
                "The gate closes at midnight and the",
                "guard never opens it twice for the",
                "same face, so decide now whether you",
                "are coming back with me or staying.",
            ),
            "5-line" to arrayOf(
                "There was a time when I could",
                "look at that face and believe",
                "every single word that came out",
                "of it, but the man standing in",
                "front of me now is a stranger.",
            ),
        )
        val summary = StringBuilder()
        for ((label, lines) in cases) {
            val bmp = bubble(*lines)
            try {
                val read = readAll(bmp)
                val sourceWords = words(lines.joinToString(" ")).distinct()
                val gotWords = words(read.joinToString(" ")).toSet()
                val missing = sourceWords.filter { it !in gotWords }
                val region = read.first()
                val translated = com.manhwalens.app.translate.DialogueLexicon.translate(region.text)
                val shown = translated ?: region.text
                // Measure into a box with a known height so "clipped" means something: the layout
                // is clipped when the block it needs is taller than the box it is given.
                val boxHeight = 150
                val type = DialogueType.measure(context, shown, 300, 15, mask = null)
                val linesDrawn = type.layout.lineCount
                val needed = type.layout.height
                val clipped = !type.complete || needed > boxHeight
                summary.append(
                    "case=$label sourceLines=${lines.size} ocrRegions=${read.size} " +
                        "missingWords=${missing.size} span=${region.bottom - region.top} " +
                        "translated=${translated != null} translatedLength=${shown.length} " +
                        "linesDrawn=$linesDrawn needsPx=$needed clippedAt150px=$clipped | ",
                )
                assertTrue("$label: no source word may be lost, missing=$missing", missing.isEmpty())
                assertTrue("$label: the translation must be drawn in full", type.complete)
                // The measurement is reported, not asserted: a 5-line block legitimately needs more
                // than 150 px, and the view's job is to shrink or widen rather than to fit here.
                assertTrue("$label: a 300px column must need two lines or more", linesDrawn >= 2)
            } finally { bmp.recycle() }
        }
        // One line for all cases, deliberately. GitHub retains at most ten annotations per step, and
        // every extra per-case stage() call pushed a measurement out of that set: the 4-line and
        // 5-line rows vanished from the first two report attempts, and TIMING from a third. The
        // individual tests now assert silently and this is the only per-case report.
        // Display side, measured here rather than collected from other tests: JUnit does not
        // guarantee execution order, so an accumulator filled by sibling tests can be emitted empty.
        val display = StringBuilder()
        val longArabic = "لم أكن أتوقع أن تعود إلى هذا المكان بعد كل ما قلته ذلك اليوم، لكن ها أنت واقف " +
            "أمامي تطلب مني أن أصدق كل كلمة تقولها وكأن شيئا لم يحدث بيننا أبدا."
        for (w in intArrayOf(320, 260, 220)) {
            val t = DialogueType.measure(context, longArabic, w, 15, mask = null)
            display.append("wrap@${w}px lines=${t.layout.lineCount} complete=${t.complete} | ")
        }
        for (sp in 15 downTo 11) {
            val t = DialogueType.measure(context, longArabic, 300, sp, mask = null)
            display.append("size${sp}sp lines=${t.layout.lineCount} complete=${t.complete} | ")
        }
        var ready = false
        var sp = 0f
        var passes = 0
        var fits = false
        val done = java.util.concurrent.CountDownLatch(1)
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val view = com.manhwalens.app.overlay.ReplacementTranslationView(
                context, longArabic, ReplacementSurface(0, 0, 300, 520),
            )
            view.measure(
                View.MeasureSpec.makeMeasureSpec(300, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(520, View.MeasureSpec.EXACTLY),
            )
            view.layout(0, 0, 300, 520)
            ready = view.replacementReady
            sp = view.fittedSp
            passes = view.fitPasses
            fits = view.measurement.contains("fits=true")
            done.countDown()
        }
        done.await()
        display.append("noViewSource ready=$ready fittedSp=$sp fitPasses=$passes fits=$fits")
        stage("DISPLAY-SUMMARY", display.toString())
        stage("MATRIX-SUMMARY", summary.toString().trimEnd())
    }

    /**
     * Phase 5: measure the real reading cost of a multi-line page, and of the recovery path that
     * confident pages used to skip entirely.
     *
     * The user's target is 5 s per screen. This is an emulator figure, so it is reported as such:
     * it cannot stand in for a phone measurement, and the report must say so. What it can do is show
     * whether widening recovery to confident pages added an unbounded cost, which is the risk the
     * change carried.
     */
    @Test
    fun readingCostIsMeasuredNotAssumed() {
        val lines = arrayOf(
            "The gate closes at midnight and the",
            "guard never opens it twice for the",
            "same face, so decide now whether you",
            "are coming back with me or staying.",
        )
        val bmp = bubble(*lines)
        try {
            val client = TextRecognitionClient()
            // Warm up so class loading and recogniser setup are not attributed to the sample.
            kotlinx.coroutines.runBlocking { client.recognize(bmp, "en") }
            val samples = (1..5).map {
                val started = android.os.SystemClock.elapsedRealtime()
                kotlinx.coroutines.runBlocking { client.recognize(bmp, "en") }
                android.os.SystemClock.elapsedRealtime() - started
            }.sorted()
            val median = samples[samples.size / 2]
            stage(
                "TIMING",
                "case=fourLinePage emulator=true samplesMs=$samples medianMs=$median " +
                    "budgetMs=5000 withinBudget=${median <= 5000} " +
                    "note=emulator_only_not_phone_not_end_to_end",
            )
            // The budget is asserted only as a sanity ceiling on the emulator, not as evidence that
            // a phone meets it.
            assertTrue("median read of a 4-line page took ${median}ms", median < 5000)
        } finally { bmp.recycle() }
    }

    @Test
    fun bubbleGeometrySpansAllLines() = kotlinx.coroutines.runBlocking {
        val lines = arrayOf("First line of the speech", "second line continues it", "third line ends it")
        val bmp = bubble(*lines)
        try {
            val read = readAll(bmp)
            val region = read.first()
            assertNotNull(region)
            // The region must cover the vertical span of every line, not just the first.
            val span = region.bottom - region.top
            assertTrue("region must span all three lines, got $span px", span > 60)

        } finally { bmp.recycle() }
    }
}
