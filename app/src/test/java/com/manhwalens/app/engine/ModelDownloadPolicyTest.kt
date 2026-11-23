package com.manhwalens.app.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Model install integrity and resume arithmetic.
 *
 * Downloading 378 MiB onto a budget phone is the most failure-prone part of this feature, and a
 * half-written file handed to llama.cpp is a crash. These tests pin the gates.
 */
class ModelDownloadPolicyTest {

    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun `pinned size and hash match the published artifact`() {
        // Regression guard: if anyone edits the constants without re-checking the Hub, this fails.
        assertEquals(396_705_472L, QwenModelSpec.SIZE_BYTES)
        assertEquals("ac2d97712095a558e31573f62f466a3f9d93990898b0ec79d7c974c1780d524a", QwenModelSpec.SHA256)
        assertEquals("Qwen3-0.6B-Q4_K_M.gguf", QwenModelSpec.FILE_NAME)
        assertEquals("qwen3", QwenModelSpec.ARCHITECTURE)
        assertEquals("Apache-2.0", QwenModelSpec.LICENSE)
    }

    @Test
    fun `the pinned download url points at the verified repository`() {
        assertTrue(QwenModelSpec.DOWNLOAD_URL.startsWith("https://huggingface.co/"))
        assertTrue(QwenModelSpec.DOWNLOAD_URL.contains("unsloth/Qwen3-0.6B-GGUF"))
        assertTrue(QwenModelSpec.DOWNLOAD_URL.endsWith(QwenModelSpec.FILE_NAME))
        // The repository named in the original spec does not contain this file; pinning it would
        // make every download unverifiable.
        assertFalse(QwenModelSpec.DOWNLOAD_URL.contains("tensorblock"))
    }

    @Test
    fun `human size reflects the real artifact, not the spec estimate`() {
        assertEquals("378.3 MiB", QwenModelSpec.humanSize())
    }

    @Test
    fun `a fresh download starts at zero`() {
        assertEquals(0L, ModelDownloadPolicy.resumeOffset(0))
    }

    @Test
    fun `a partial download resumes from its current length`() {
        assertEquals(1_000_000L, ModelDownloadPolicy.resumeOffset(1_000_000))
    }

    @Test
    fun `an oversized partial file restarts instead of appending`() {
        // A file longer than the expected total cannot be a prefix of the real artifact.
        assertEquals(0L, ModelDownloadPolicy.resumeOffset(QwenModelSpec.SIZE_BYTES + 1))
        assertEquals(0L, ModelDownloadPolicy.resumeOffset(QwenModelSpec.SIZE_BYTES))
    }

    @Test
    fun `completeness requires an exact length`() {
        assertTrue(ModelDownloadPolicy.isComplete(QwenModelSpec.SIZE_BYTES))
        assertFalse(ModelDownloadPolicy.isComplete(QwenModelSpec.SIZE_BYTES - 1))
        assertFalse(ModelDownloadPolicy.isComplete(0))
    }

    @Test
    fun `space check includes headroom`() {
        assertFalse(ModelDownloadPolicy.hasSpace(QwenModelSpec.SIZE_BYTES))
        assertTrue(ModelDownloadPolicy.hasSpace(QwenModelSpec.SIZE_BYTES + ModelDownloadPolicy.SPACE_HEADROOM_BYTES))
    }

    @Test
    fun `precheck rejects a short file before hashing it`() {
        val head = ggufHeader()
        assertEquals(
            ModelInstallFailure.SIZE_MISMATCH,
            ModelDownloadPolicy.precheck(QwenModelSpec.SIZE_BYTES - 10, head),
        )
    }

    @Test
    fun `precheck rejects an html error page`() {
        val html = "<!DOCTYPE html><html>".toByteArray()
        assertEquals(ModelInstallFailure.NOT_GGUF, ModelDownloadPolicy.precheck(QwenModelSpec.SIZE_BYTES, html))
    }

    @Test
    fun `precheck accepts a well formed gguf header`() {
        assertNull(ModelDownloadPolicy.precheck(QwenModelSpec.SIZE_BYTES, ggufHeader()))
    }

    @Test
    fun `gguf detection validates magic and version`() {
        assertTrue(QwenModelSpec.looksLikeGguf(ggufHeader()))
        assertFalse(QwenModelSpec.looksLikeGguf("GGUF".toByteArray()))
        assertFalse(QwenModelSpec.looksLikeGguf(byteArrayOf(0, 0, 0, 0, 3, 0, 0, 0)))
        // Version 1 is obsolete.
        assertFalse(QwenModelSpec.looksLikeGguf(byteArrayOf(0x47, 0x47, 0x55, 0x46, 1, 0, 0, 0)))
    }

    @Test
    fun `hash comparison is case insensitive`() {
        assertTrue(ModelDownloadPolicy.hashMatches(QwenModelSpec.SHA256.uppercase()))
        assertTrue(ModelDownloadPolicy.hashMatches("  ${QwenModelSpec.SHA256}  "))
        assertFalse(ModelDownloadPolicy.hashMatches("deadbeef"))
        assertFalse(ModelDownloadPolicy.hashMatches(null))
    }

    @Test
    fun `progress reporting is throttled to about one percent`() {
        val total = 100_000_000L
        assertFalse(ModelDownloadPolicy.shouldReport(0, 100, total))
        assertTrue(ModelDownloadPolicy.shouldReport(0, 1_000_000, total))
        // Always report the final byte, otherwise the UI sticks at 99%.
        assertTrue(ModelDownloadPolicy.shouldReport(total - 1, total, total))
    }

    @Test
    fun `a store with nothing installed reports absent`() {
        val store = FileModelStore(temporary.newFolder("models"))
        assertFalse(store.installed())
        assertNull(store.path())
        assertFalse(store.verified())
        assertEquals(0L, store.storedBytes())
    }

    @Test
    fun `a file of the right size without a marker is not verified`() {
        val dir = temporary.newFolder("models-unverified")
        val store = FileModelStore(dir)
        File(dir, QwenModelSpec.FILE_NAME).writeBytes(ByteArray(16))
        assertTrue("file exists", store.installed())
        // Presence is not integrity: an unverified file must never be loaded.
        assertFalse(store.verified())
    }

    @Test
    fun `a truncated file is never verified even with a marker`() {
        val dir = temporary.newFolder("models-truncated")
        val store = FileModelStore(dir)
        File(dir, QwenModelSpec.FILE_NAME).writeBytes(ByteArray(16))
        store.markVerified()
        assertFalse("truncated model must not verify", store.verified())
    }

    @Test
    fun `verification requires both the exact size and the recorded hash`() {
        val dir = temporary.newFolder("models-full")
        val store = FileModelStore(dir)
        // Sparse file of the exact expected length; content is irrelevant to the store, which only
        // checks length + marker. The real hash check happens in the downloader.
        java.io.RandomAccessFile(File(dir, QwenModelSpec.FILE_NAME), "rw").use { it.setLength(QwenModelSpec.SIZE_BYTES) }
        assertFalse(store.verified())
        store.markVerified()
        assertTrue(store.verified())
    }

    @Test
    fun `stored bytes count the partial download too`() {
        val dir = temporary.newFolder("models-partial")
        val store = FileModelStore(dir)
        File(dir, QwenModelSpec.FILE_NAME + ".part").writeBytes(ByteArray(1024))
        assertEquals(1024L, store.storedBytes())
    }

    @Test
    fun `removal deletes model, partial and marker`() {
        val dir = temporary.newFolder("models-remove")
        val store = FileModelStore(dir)
        File(dir, QwenModelSpec.FILE_NAME).writeBytes(ByteArray(8))
        File(dir, QwenModelSpec.FILE_NAME + ".part").writeBytes(ByteArray(8))
        store.markVerified()
        assertTrue(store.remove())
        assertFalse(store.installed())
        assertFalse(store.verified())
        assertEquals(0L, store.storedBytes())
    }

    @Test
    fun `promoting a partial file installs it`() {
        val dir = temporary.newFolder("models-promote")
        val store = FileModelStore(dir)
        File(dir, QwenModelSpec.FILE_NAME + ".part").writeBytes(ByteArray(8))
        assertTrue(store.promotePartial())
        assertTrue(store.installed())
        assertFalse(File(dir, QwenModelSpec.FILE_NAME + ".part").exists())
    }

    private fun ggufHeader(version: Int = 3): ByteArray = byteArrayOf(
        'G'.code.toByte(), 'G'.code.toByte(), 'U'.code.toByte(), 'F'.code.toByte(),
        version.toByte(), 0, 0, 0,
    )

    @Test
    fun `the download url host is the verified one and not the repo that lacks the file`() {
        assertNotNull(QwenModelSpec.DOWNLOAD_URL)
        assertEquals("unsloth/Qwen3-0.6B-GGUF", QwenModelSpec.SOURCE_REPO)
    }
}
