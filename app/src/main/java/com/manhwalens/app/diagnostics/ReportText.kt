package com.manhwalens.app.diagnostics

import java.io.Writer

/** Single UTF-8 report framing; do not truncate source or split clipboard-sized chunks. */
object ReportText {
    private val secret = Regex("(?i)(password|token|api[_ -]?key|secret)\\s*[:=]\\s*\\S+")
    fun section(writer: Writer, title: String, content: String) {
        writer.write("\n== $title ==\n")
        writer.write(secret.replace(content, "$1=[REDACTED]"))
        writer.write("\n")
    }
}
