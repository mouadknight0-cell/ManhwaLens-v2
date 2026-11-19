package com.manhwalens.app.diagnostics

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class DiagnosticToolsTest {
    @Test fun longCopyIsLosslessIncludingArabicAndEmoji() {
        val text = "تشخيص 😀 original\n".repeat(20000)
        val pieces = ClipboardChunks.split(text)
        assertEquals(text, pieces.joinToString(""))
        assertTrue(pieces.all { it.length <= 50000 })
        assertTrue(pieces.none { Character.isLowSurrogate(it.first()) || Character.isHighSurrogate(it.last()) })
    }
    @Test fun copyHandlesEmptyAndBoundaryReports() {
        assertEquals(listOf(""), ClipboardChunks.split(""))
        assertEquals(listOf("abcd", "ef"), ClipboardChunks.split("abcdef", 4))
    }
    @Test fun bugResetDoesNotDeleteOtherData() {
        val dir = Files.createTempDirectory("diagnostic-boundaries").toFile()
        try {
            val bugs = listOf("bug-123.txt", "issue-456.txt")
            val keep = listOf("current.log", "previous.log", "translation-samples.txt", "records.json", "settings.xml", "report-errors-123.txt", "bug-keep.json")
            (bugs + keep).forEach { dir.resolve(it).writeText("test") }
            DiagnosticFilePolicy.clearBugs(dir)
            assertTrue(bugs.none { dir.resolve(it).exists() })
            assertTrue(keep.all { dir.resolve(it).readText() == "test" })
        } finally { dir.deleteRecursively() }
    }
}
