package com.manhwalens.app.context

import android.content.Context
import java.io.File

/**
 * Supplies `context.filesDir` to [EvidenceStore] and nothing else.
 *
 * The whole of the store's logic — atomic writes, quarantine, the cap, isolation — lives in the
 * plain-JVM class, so it is covered by ordinary tests. This file exists only because `Context` does.
 *
 * A separate directory from story memory: captures are evidence a person collected deliberately, and
 * they must be deletable without touching what the engine has learned about a work.
 */
object EvidenceStoreFile {

    private const val DIRECTORY = "evidence-cases"

    private lateinit var store: EvidenceStore
    private lateinit var root: File

    @Synchronized
    fun init(context: Context) {
        if (::store.isInitialized) return
        root = File(context.filesDir, DIRECTORY)
        store = EvidenceStore(root)
    }

    fun store(): EvidenceStore = store

    /** The directory, for the export's file provider. */
    fun directory(): File = root
}
