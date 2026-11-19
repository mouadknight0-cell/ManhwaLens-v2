package com.manhwalens.app.pipeline

/**
 * Maps user-facing source settings to OCR scripts, and OCR scripts back to
 * BCP-47 language codes used for translation.
 */
object LanguageMapper {

    enum class Script { KOREAN, JAPANESE, CHINESE, LATIN }

    const val SOURCE_AUTO = "auto"

    /** Targets offered in Settings: code to display name (native). */
    val supportedTargets: List<Pair<String, String>> = listOf(
        "ar" to "العربية",
        "en" to "English",
        "fr" to "Français",
        "es" to "Español",
        "de" to "Deutsch",
        "it" to "Italiano",
        "pt" to "Português",
        "tr" to "Türkçe",
        "ru" to "Русский",
        "hi" to "हिन्दी",
        "id" to "Bahasa Indonesia",
        "ja" to "日本語",
        "ko" to "한국어",
        "zh" to "中文",
        "vi" to "Tiếng Việt",
        "th" to "ไทย",
    )

    val supportedTargetCodes: Set<String> get() = supportedTargets.map { it.first }.toSet()

    /** Which recognizers to run for a given source setting. */
    fun scriptsFor(source: String): List<Script> = when (normalize(source)) {
        "ko" -> listOf(Script.KOREAN, Script.LATIN)
        "ja" -> listOf(Script.JAPANESE, Script.LATIN)
        "zh" -> listOf(Script.CHINESE, Script.LATIN)
        "en", "fr", "es", "de", "it", "pt", "tr", "id", "vi" -> listOf(Script.LATIN)
        SOURCE_AUTO -> listOf(Script.KOREAN, Script.JAPANESE, Script.CHINESE, Script.LATIN)
        else -> listOf(Script.KOREAN, Script.JAPANESE, Script.CHINESE, Script.LATIN)
    }

    /** Fallback language for a script when the user chose "auto". */
    fun autoLanguageFor(script: Script): String = when (script) {
        Script.KOREAN -> "ko"
        Script.JAPANESE -> "ja"
        Script.CHINESE -> "zh"
        Script.LATIN -> "en"
    }

    fun autoLanguageFor(scriptName: String): String = when (scriptName.uppercase()) {
        "KOREAN" -> "ko"
        "JAPANESE" -> "ja"
        "CHINESE" -> "zh"
        else -> "en"
    }

    /** Resolves the translation source code for a region. */
    fun sourceCodeFor(region: TextRegion, configuredSource: String): String {
        val normalized = normalize(configuredSource)
        if (normalized != SOURCE_AUTO) return normalized
        return region.languageHint ?: "en"
    }

    fun displayTargetName(code: String): String =
        supportedTargets.firstOrNull { it.first == normalize(code) }?.second ?: code

    fun normalize(code: String?): String = code?.trim()?.lowercase().orEmpty()
}
