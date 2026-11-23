package com.manhwalens.app.engine

import java.io.File

/**
 * Owns the model file on disk: where it lives, whether it is whole, and how it is removed.
 *
 * Kept as an interface so the engine is testable without Android. The real implementation stores the
 * file in app-private storage (not cache — the OS may evict cache, and re-downloading 378 MiB at a
 * bus stop is not acceptable), and records verification in a sidecar file so a verified model is not
 * re-hashed on every launch.
 */
interface ModelStore {
    /** Absolute path of the model file, or null if nothing is installed. */
    fun path(): String?

    /** True when a file exists at [path]. Says nothing about integrity. */
    fun installed(): Boolean

    /**
     * True only when the file has been hash-verified against [QwenModelSpec.SHA256].
     *
     * A file that exists but was never verified is *not* installed as far as inference is concerned.
     */
    fun verified(): Boolean

    /** Bytes currently used on disk by the model (and its partial download), for the settings row. */
    fun storedBytes(): Long

    /** Marks the file as verified after a successful hash check. */
    fun markVerified()

    /** Deletes the model and any partial download. Returns true when nothing is left behind. */
    fun remove(): Boolean
}

/**
 * A plain-filesystem store. Deliberately constructor-injected with a directory so unit tests can use
 * a temp folder and the app can pass `context.filesDir/models`.
 */
class FileModelStore(private val directory: File) : ModelStore {

    private val modelFile: File get() = File(directory, QwenModelSpec.FILE_NAME)

    /** Partial download target. Downloading to a `.part` file is what makes resume safe. */
    val partialFile: File get() = File(directory, QwenModelSpec.FILE_NAME + ".part")

    private val verifiedMarker: File get() = File(directory, QwenModelSpec.FILE_NAME + ".verified")

    override fun path(): String? = modelFile.takeIf { it.isFile }?.absolutePath

    override fun installed(): Boolean = modelFile.isFile

    /**
     * A verified marker alone is not enough: the model file could have been deleted or truncated
     * while the marker survived. Both must agree, and the size must match exactly.
     */
    override fun verified(): Boolean {
        val file = modelFile
        if (!file.isFile) return false
        if (file.length() != QwenModelSpec.SIZE_BYTES) return false
        if (!verifiedMarker.isFile) return false
        val recorded = runCatching { verifiedMarker.readText().trim() }.getOrNull()
        return QwenModelDownload.hashMatches(recorded)
    }

    override fun storedBytes(): Long =
        (if (modelFile.isFile) modelFile.length() else 0L) +
            (if (partialFile.isFile) partialFile.length() else 0L)

    override fun markVerified() {
        directory.mkdirs()
        // Written atomically enough for this purpose: a torn marker fails the string comparison above
        // and simply forces a re-verify rather than admitting a bad file.
        runCatching { verifiedMarker.writeText(QwenModelSpec.SHA256) }
    }

    override fun remove(): Boolean {
        val removedModel = !modelFile.exists() || modelFile.delete()
        val removedPartial = !partialFile.exists() || partialFile.delete()
        val removedMarker = !verifiedMarker.exists() || verifiedMarker.delete()
        return removedModel && removedPartial && removedMarker
    }

    /** Moves a completed, hashed `.part` file into place. */
    fun promotePartial(): Boolean {
        val partial = partialFile
        if (!partial.isFile) return false
        val target = modelFile
        target.delete()
        return partial.renameTo(target)
    }
}

/** Helper so store and policy agree on what a hash match means. */
internal object QwenModelDownload {
    fun hashMatches(actual: String?) = ModelDownloadPolicy.hashMatches(actual)
}
