package com.fishaudio.tts

import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import java.util.Locale

/** Every profile has its own network Voices. Profile identity disambiguates rows using the same reference. */
object VoiceCatalog {
    val locales: List<Locale> = listOf("en-US", "zh-CN", "ja-JP", "ko-KR", "de-DE", "fr-FR",
        "es-ES", "pt-BR", "it-IT", "ru-RU", "ar-SA", "nl-NL", "pl-PL").map(Locale::forLanguageTag)

    fun localeFor(language: String): Locale? = locales.firstOrNull {
        language.equals(it.language, true) || language.equals(it.isO3Language, true)
    }

    fun availability(language: String, country: String, configured: Boolean): Int {
        val locale = localeFor(language) ?: return TextToSpeech.LANG_NOT_SUPPORTED
        if (!configured) return TextToSpeech.LANG_MISSING_DATA
        return if (country.isNotEmpty() && (country.equals(locale.country, true) || country.equals(locale.isO3Country, true)))
            TextToSpeech.LANG_COUNTRY_AVAILABLE else TextToSpeech.LANG_AVAILABLE
    }

    fun name(profile: VoiceProfile, locale: Locale): String = "fish:${profile.id}:${profile.referenceId}:${locale.toLanguageTag()}"

    @Suppress("DEPRECATION") // Legacy TTS clients still inspect networkTts; Voice also sets the modern network flag.
    fun voices(config: EngineConfiguration): List<Voice> = if (!config.configured) emptyList() else config.voices.flatMap { profile ->
        locales.map {
            Voice(name(profile, it), it, Voice.QUALITY_NORMAL, Voice.LATENCY_VERY_HIGH, true,
                setOf(TextToSpeech.Engine.KEY_FEATURE_NETWORK_SYNTHESIS))
        }
    }

    fun selection(voiceName: String, config: EngineConfiguration): Pair<VoiceProfile, Locale>? {
        if (!config.configured) return null
        for (profile in config.voices) {
            val locale = locales.firstOrNull { voiceName == name(profile, it) }
            if (locale != null) return profile to locale
        }
        return null
    }

    fun dataNames(): ArrayList<String> = ArrayList(locales.map { "${it.isO3Language}-${it.isO3Country}" })
}
