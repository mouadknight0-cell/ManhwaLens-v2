package com.manhwalens.app.engine

/**
 * The install state of the contextual model, as the UI and the engine both need to see it.
 *
 * Modelled as an explicit sealed hierarchy rather than a bag of booleans: "downloading", "downloaded
 * but unverified" and "verified" are genuinely different, and conflating them is how an app ends up
 * asking llama.cpp to load a half-written file.
 */
sealed class ModelState {
    object Absent : ModelState()
    data class Downloading(val bytes: Long, val total: Long) : ModelState() {
        val fraction: Float get() = if (total <= 0) 0f else (bytes.toDouble() / total).toFloat().coerceIn(0f, 1f)
        val percent: Int get() = (fraction * 100).toInt()
    }

    /** File present, hash not yet confirmed. Never usable for inference. */
    data class Unverified(val bytes: Long) : ModelState()

    /** Hash confirmed against [QwenModelSpec.SHA256]. The only state inference may run in. */
    data class Ready(val bytes: Long) : ModelState()

    data class Failed(val reason: String, val detail: String? = null) : ModelState()

    val isReady: Boolean get() = this is Ready

    /** Whether it makes sense to offer a download button. */
    val canDownload: Boolean get() = this is Absent || this is Failed
}

/** Why an install attempt did not end in [ModelState.Ready]. */
object ModelInstallFailure {
    const val NETWORK = "MODEL_NETWORK"
    const val HTTP = "MODEL_HTTP"
    const val SHORT_READ = "MODEL_SHORT_READ"
    const val SIZE_MISMATCH = "MODEL_SIZE_MISMATCH"
    const val NOT_GGUF = "MODEL_NOT_GGUF"
    const val HASH_MISMATCH = "MODEL_HASH_MISMATCH"
    const val NO_SPACE = "MODEL_NO_SPACE"
    const val CANCELLED = "MODEL_CANCELLED"
    const val IO = "MODEL_IO"
}

/**
 * Pure arithmetic and policy for a resumable download.
 *
 * Kept free of Android and networking types so the risky parts — resume offsets, free-space checks,
 * size gates — are unit-tested rather than debugged on a phone.
 */
object ModelDownloadPolicy {

    /**
     * How much free space to require before starting.
     *
     * The file itself, plus headroom: the OS needs room to write the tail while the allocator still
     * holds earlier pages, and freeing the device mid-download is worse than refusing to start.
     */
    const val SPACE_HEADROOM_BYTES = 64L * 1024 * 1024

    fun requiredSpaceBytes(total: Long = QwenModelSpec.SIZE_BYTES): Long = total + SPACE_HEADROOM_BYTES

    fun hasSpace(freeBytes: Long, total: Long = QwenModelSpec.SIZE_BYTES): Boolean =
        freeBytes >= requiredSpaceBytes(total)

    /**
     * The byte offset a resume should request, given what is already on disk.
     *
     * If the partial file is already longer than the expected total it cannot be a prefix of the
     * real file, so the only safe action is to start over rather than append to corruption.
     */
    fun resumeOffset(existingBytes: Long, total: Long = QwenModelSpec.SIZE_BYTES): Long = when {
        existingBytes <= 0 -> 0
        existingBytes >= total -> 0
        else -> existingBytes
    }

    /** True when the partial file is already complete and only needs verifying. */
    fun isComplete(existingBytes: Long, total: Long = QwenModelSpec.SIZE_BYTES): Boolean =
        existingBytes == total

    /**
     * Checks a finished download before it is hashed. Returns a failure code, or null when the bytes
     * are worth the cost of a full SHA-256.
     */
    fun precheck(
        bytes: Long,
        head: ByteArray,
        total: Long = QwenModelSpec.SIZE_BYTES,
    ): String? = when {
        bytes != total -> ModelInstallFailure.SIZE_MISMATCH
        !QwenModelSpec.looksLikeGguf(head) -> ModelInstallFailure.NOT_GGUF
        else -> null
    }

    /** Case-insensitive hex comparison, ignoring surrounding whitespace. */
    fun hashMatches(actual: String?, expected: String = QwenModelSpec.SHA256): Boolean =
        actual != null && actual.trim().equals(expected, ignoreCase = true)

    /** Progress throttling so a 378 MiB download does not emit thousands of UI updates. */
    fun shouldReport(previousBytes: Long, currentBytes: Long, total: Long): Boolean {
        if (total <= 0) return false
        val step = (total / 100).coerceAtLeast(1)
        return currentBytes == total || currentBytes - previousBytes >= step
    }
}
