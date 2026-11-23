package com.manhwalens.app.engine

import android.content.Context
import com.manhwalens.app.diagnostics.Diagnostics
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Downloads, resumes and verifies the contextual model.
 *
 * Deliberately blunt about failure: nothing is installed until the bytes on disk hash to the pinned
 * SHA-256. A download that stops at 99%, returns an HTML error page, or arrives from a captive portal
 * is rejected before it can ever be handed to llama.cpp.
 *
 * Progress is reported through a callback rather than a Flow so the caller decides the threading, and
 * reporting is throttled (see [ModelDownloadPolicy.shouldReport]) because a 378 MiB transfer would
 * otherwise emit tens of thousands of recompositions.
 */
class ModelDownloader(
    private val context: Context,
    private val store: FileModelStore,
    private val onState: (ModelState) -> Unit,
) {

    /**
     * Runs a full install: space check → resumable download → structural check → hash → promote.
     *
     * Returns the terminal [ModelState]; it never throws for an expected failure. Cancellation is
     * propagated as a [CancellationException] after publishing [ModelState.Absent], so a user-
     * cancelled download does not leave the UI stuck in "Downloading".
     */
    suspend fun install(): ModelState = withContext(Dispatchers.IO) {
        val total = QwenModelSpec.SIZE_BYTES

        if (!ModelDownloadPolicy.hasSpace(freeBytes())) {
            return@withContext fail(ModelInstallFailure.NO_SPACE)
        }

        val partial = store.partialFile
        var offset = ModelDownloadPolicy.resumeOffset(if (partial.isFile) partial.length() else 0L)

        // A complete partial file only needs verifying, not re-downloading.
        if (ModelDownloadPolicy.isComplete(offset, total)) {
            return@withContext verifyAndInstall()
        }

        onState(ModelState.Downloading(offset, total))
        try {
            while (offset < total) {
                offset = attempt(offset, total)
            }
        } catch (cancelled: CancellationException) {
            onState(ModelState.Absent)
            throw cancelled
        } catch (failure: Throwable) {
            Diagnostics.warn("engine", "model_download", "download failed", throwable = failure)
            return@withContext fail(ModelInstallFailure.NETWORK, failure.javaClass.simpleName)
        }

        verifyAndInstall()
    }

    /**
     * One HTTP request, appending from [offset]. Returns the new length on disk.
     *
     * Uses a Range request when resuming. A server that ignores Range (206 → 200) would otherwise
     * produce a corrupt file, so the response code is checked and the transfer restarted from zero
     * in that case.
     */
    private fun attempt(offset: Long, total: Long): Long {
        val connection = (URL(QwenModelSpec.DOWNLOAD_URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = 30_000
            readTimeout = 60_000
            instanceFollowRedirects = true
            if (offset > 0) setRequestProperty("Range", "bytes=$offset-")
        }
        try {
            val code = connection.responseCode
            if (code !in 200..299) {
                throw IOException("${ModelInstallFailure.HTTP}:$code")
            }
            // The server ignored our Range and is sending the whole file again.
            val restart = offset > 0 && code == HttpURLConnection.HTTP_OK
            val writeFrom = if (restart) 0L else offset
            val partial = store.partialFile
            partial.parentFile?.mkdirs()

            var written = writeFrom
            var lastReported = writeFrom
            connection.inputStream.use { input ->
                FileOutputStream(partial, writeFrom > 0).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        output.write(buffer, 0, read)
                        written += read
                        if (ModelDownloadPolicy.shouldReport(lastReported, written, total)) {
                            lastReported = written
                            onState(ModelState.Downloading(written.coerceAtMost(total), total))
                        }
                        if (written >= total) break
                    }
                    output.flush()
                }
            }
            // A short read is not an error to retry blindly: return progress and let the loop resume.
            return written
        } finally {
            connection.disconnect()
        }
    }

    /** Structural precheck, then the full SHA-256, then atomically promote the file into place. */
    private suspend fun verifyAndInstall(): ModelState {
        val partial = store.partialFile
        val bytes = if (partial.isFile) partial.length() else 0L
        val head = runCatching {
            partial.inputStream().use { input -> ByteArray(8).also { input.read(it) } }
        }.getOrDefault(ByteArray(0))

        ModelDownloadPolicy.precheck(bytes, head)?.let { return fail(it) }

        onState(ModelState.Unverified(bytes))
        val digest = sha256(partial)
        if (!ModelDownloadPolicy.hashMatches(digest)) {
            // The bytes are wrong; keeping them would poison every future resume.
            partial.delete()
            return fail(ModelInstallFailure.HASH_MISMATCH, digest?.take(12))
        }
        if (!store.promotePartial()) {
            return fail(ModelInstallFailure.IO, "rename failed")
        }
        store.markVerified()
        val ready = ModelState.Ready(store.storedBytes())
        onState(ready)
        Diagnostics.info("engine", "model_install", "contextual model installed and verified",
            context = mapOf("bytes" to ready.bytes.toString()))
        return ready
    }

    /** Streams the file through SHA-256; never allocates the whole 378 MiB. */
    private fun sha256(file: File): String? = try {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                md.update(buffer, 0, read)
            }
        }
        md.digest().joinToString("") { "%02x".format(it) }
    } catch (failure: IOException) {
        null
    }

    private fun freeBytes(): Long = store.partialFile.parentFile?.usableSpace ?: 0L

    private fun fail(reason: String, detail: String? = null): ModelState {
        val state = ModelState.Failed(reason, detail)
        onState(state)
        return state
    }

    /** Deletes the model and any partial download, and publishes the resulting state. */
    fun remove(): ModelState {
        val removed = store.remove()
        val state = if (removed) ModelState.Absent else ModelState.Failed(ModelInstallFailure.IO, "delete failed")
        onState(state)
        return state
    }
}
