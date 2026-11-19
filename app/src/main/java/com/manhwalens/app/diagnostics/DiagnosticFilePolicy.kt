package com.manhwalens.app.diagnostics

import java.io.File

/** Exact archive boundary shared by enumeration, deletion and regression tests. */
object DiagnosticFilePolicy {
    fun isBugFile(name: String): Boolean = (name.startsWith("issue-") || name.startsWith("bug-")) && name.endsWith(".txt")
    fun clearBugs(directory: File) {
        directory.listFiles()?.filter { it.isFile && isBugFile(it.name) }?.forEach {
            check(it.delete()) { "Could not delete ${it.name}" }
        }
    }
}
