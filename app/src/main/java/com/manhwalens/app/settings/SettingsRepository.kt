package com.manhwalens.app.settings

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * SharedPreferences-backed settings exposed as StateFlows for Compose.
 */
class SettingsRepository(context: Context) {

    private val prefs = context.getSharedPreferences("manhwalens_settings", Context.MODE_PRIVATE)

    init {
        // v1.1.0 scope decision: the pipeline is locked to English → Arabic.
        // Force-migrate previously saved language pairs once; EN→AR only from now on.
        if (!prefs.getBoolean(KEY_MIGRATION_EN_AR_V2, false)) {
            prefs.edit()
                .putString(KEY_SOURCE, "en")
                .putString(KEY_TARGET, "ar")
                .putBoolean(KEY_MIGRATION_EN_AR_V2, true)
                .apply()
        }
    }

    private val _sourceLanguage = MutableStateFlow(prefs.getString(KEY_SOURCE, DEFAULT_SOURCE) ?: DEFAULT_SOURCE)
    val sourceLanguage: StateFlow<String> = _sourceLanguage.asStateFlow()

    private val _targetLanguage = MutableStateFlow(prefs.getString(KEY_TARGET, DEFAULT_TARGET) ?: DEFAULT_TARGET)
    val targetLanguage: StateFlow<String> = _targetLanguage.asStateFlow()

    private val _bubbleEnabled = MutableStateFlow(prefs.getBoolean(KEY_BUBBLE, false))
    val bubbleEnabled: StateFlow<Boolean> = _bubbleEnabled.asStateFlow()

    private val _onboardingSeen = MutableStateFlow(prefs.getBoolean(KEY_ONBOARDING, false))
    val onboardingSeen: StateFlow<Boolean> = _onboardingSeen.asStateFlow()

    fun setSourceLanguage(code: String) {
        prefs.edit().putString(KEY_SOURCE, code).apply()
        _sourceLanguage.value = code
    }

    fun setTargetLanguage(code: String) {
        prefs.edit().putString(KEY_TARGET, code).apply()
        _targetLanguage.value = code
    }

    fun setBubbleEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_BUBBLE, enabled).apply()
        _bubbleEnabled.value = enabled
    }

    private val defaultReadings=com.manhwalens.app.translate.ReadingGlossary.defaults
    val readings=MutableStateFlow(prefs.getString("arabic_readings",null)?.let { com.manhwalens.app.translate.ReadingGlossary.parse(it) } ?: defaultReadings)
    fun saveReadings(text:String):Boolean {
        val parsed=com.manhwalens.app.translate.ReadingGlossary.parse(text) ?: return false
        prefs.edit().putString("arabic_readings",text).apply(); readings.value=parsed; return true
    }
    private fun parseCorrections(s:String)=s.lineSequence().filter { it.isNotBlank() }.associate { it.substringBefore('=').trim() to it.substringAfter('=',"").trim() }
    val corrections=MutableStateFlow(parseCorrections(prefs.getString("post_ar","البارير=الحاجز\nانها=إنها") ?: ""))
    fun saveCorrections(s:String):Boolean {
        val map=parseCorrections(s); if(map.size>100 || map.any { (k,v)->k.length !in 1..64 || v.length !in 1..64 || !com.manhwalens.app.translate.ArabicOutput.hasLetters(k) || !com.manhwalens.app.translate.ArabicOutput.hasLetters(v) }) return false
        prefs.edit().putString("post_ar",s).apply(); corrections.value=map;return true
    }
    private val _debug = MutableStateFlow(prefs.getBoolean("debug_geometry", false))
    val debug = _debug.asStateFlow()
    fun setDebug(enabled:Boolean) { prefs.edit().putBoolean("debug_geometry",enabled).apply(); _debug.value=enabled }

    fun markOnboardingSeen() {
        prefs.edit().putBoolean(KEY_ONBOARDING, true).apply()
        _onboardingSeen.value = true
    }

    companion object {
        private const val KEY_SOURCE = "source_language"
        private const val KEY_TARGET = "target_language"
        private const val KEY_BUBBLE = "bubble_enabled"
        private const val KEY_ONBOARDING = "onboarding_seen"

        // Legacy key: the app is Korean-first; Arabic is the default target for its audience.
        // v1.1.0 locks the pipeline to English → Arabic (see migration below).
        const val DEFAULT_SOURCE = "en"
        const val DEFAULT_TARGET = "ar"
        private const val KEY_MIGRATION_EN_AR_V2 = "migration_en_ar_v2"
    }
}
