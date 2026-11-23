package com.manhwalens.app.engine

/**
 * The exact model artifact this build is allowed to download.
 *
 * Verified against the Hugging Face API rather than taken from a filename:
 *
 * ```
 * GET /api/models/unsloth/Qwen3-0.6B-GGUF/tree/main
 *   Qwen3-0.6B-Q4_K_M.gguf  size 396705472  lfs.sha256 ac2d9771…0d524a
 * GET /api/models/unsloth/Qwen3-0.6B-GGUF
 *   license apache-2.0, gguf.architecture "qwen3", gguf.chat_template present
 * ```
 *
 * **The repo named in the original specification (`tensorblock/Qwen_Qwen3-0.6B-GGUF`) does not
 * contain this file.** Its tree holds only Q2_K and Q3_K_M, even though its README table lists
 * Q4_K_M. Pinning it would have shipped a download that can never verify.
 *
 * Also note the size: 396,705,472 bytes = 378.3 MiB, **not** the ~462 MiB the specification
 * predicted. The real number is used everywhere, because a wrong expected size is indistinguishable
 * from a corrupt download.
 */
object QwenModelSpec {

    const val ID = "qwen3-0.6b-q4_k_m"

    const val FILE_NAME = "Qwen3-0.6B-Q4_K_M.gguf"

    /** Exact byte length from the Hub LFS metadata. */
    const val SIZE_BYTES = 396_705_472L

    /** Hub LFS SHA-256 of the file, used as the integrity contract. */
    const val SHA256 = "ac2d97712095a558e31573f62f466a3f9d93990898b0ec79d7c974c1780d524a"

    const val DOWNLOAD_URL =
        "https://huggingface.co/unsloth/Qwen3-0.6B-GGUF/resolve/main/Qwen3-0.6B-Q4_K_M.gguf"

    const val SOURCE_REPO = "unsloth/Qwen3-0.6B-GGUF"
    const val LICENSE = "Apache-2.0"
    const val ARCHITECTURE = "qwen3"

    /** Displayed in settings so the user knows what they are about to spend bandwidth on. */
    fun humanSize(bytes: Long = SIZE_BYTES): String {
        val mib = bytes / (1024.0 * 1024.0)
        return if (mib >= 1024) String.format("%.2f GiB", mib / 1024) else String.format("%.1f MiB", mib)
    }

    /**
     * Cheap structural check of a downloaded file, applied before the (expensive) full hash.
     *
     * GGUF files begin with the magic bytes "GGUF" and carry a little-endian version at offset 4.
     * A truncated or HTML-error-page download fails here in microseconds instead of after hashing
     * 378 MiB.
     */
    fun looksLikeGguf(head: ByteArray): Boolean {
        if (head.size < 8) return false
        if (head[0] != 'G'.code.toByte() || head[1] != 'G'.code.toByte() ||
            head[2] != 'U'.code.toByte() || head[3] != 'F'.code.toByte()
        ) return false
        val version = (head[4].toInt() and 0xFF) or
            ((head[5].toInt() and 0xFF) shl 8) or
            ((head[6].toInt() and 0xFF) shl 16) or
            ((head[7].toInt() and 0xFF) shl 24)
        // v1 is obsolete; v2 and v3 are what llama.cpp writes today.
        return version in 2..3
    }
}
