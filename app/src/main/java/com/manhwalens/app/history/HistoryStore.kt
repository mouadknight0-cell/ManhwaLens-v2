package com.manhwalens.app.history

import android.content.Context
import android.graphics.Bitmap
import com.manhwalens.app.pipeline.CaptureResult
import com.manhwalens.app.pipeline.HistoryJson
import com.manhwalens.app.util.downscaleTo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * Persists translation history as an index.json + JPEG thumbnails
 * inside app-private storage. Nothing leaves the device.
 */
class HistoryStore(private val context: Context) {

    private val historyDir: File get() = File(context.filesDir, "history").apply { mkdirs() }
    private val imagesDir: File get() = File(historyDir, "images").apply { mkdirs() }
    private val indexFile: File get() = File(historyDir, "index.json")

    suspend fun saveThumbnail(id: String, bitmap: Bitmap): String? = withContext(Dispatchers.IO) {
        runCatching {
            val scaled = bitmap.downscaleTo(512)
            val file = File(imagesDir, "$id.jpg")
            FileOutputStream(file).use { out -> scaled.compress(Bitmap.CompressFormat.JPEG, 72, out) }
            file.absolutePath
        }.getOrNull()
    }

    @Synchronized
    private fun readIndex(): MutableList<CaptureResult> =
        HistoryJson.decode(runCatching { indexFile.readText() }.getOrNull()).toMutableList()

    @Synchronized
    private fun writeIndex(entries: List<CaptureResult>) {
        indexFile.writeText(HistoryJson.encode(entries))
    }

    suspend fun add(result: CaptureResult) = withContext(Dispatchers.IO) {
        val entries = readIndex()
        entries.add(0, result)
        writeIndex(entries.take(MAX_ENTRIES))
    }

    suspend fun list(): List<CaptureResult> = withContext(Dispatchers.IO) { readIndex() }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        val entries = readIndex().filterNot { it.id == id }
        writeIndex(entries)
        runCatching { File(imagesDir, "$id.jpg").delete() }
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        writeIndex(emptyList())
        imagesDir.listFiles()?.forEach { it.delete() }
    }

    suspend fun latest(): CaptureResult? = list().firstOrNull()

    companion object {
        const val MAX_ENTRIES = 100
    }
}
