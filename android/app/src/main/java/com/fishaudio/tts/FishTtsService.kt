package com.fishaudio.tts

import android.speech.tts.SynthesisCallback
import android.speech.tts.SynthesisRequest
import android.speech.tts.TextToSpeech
import android.speech.tts.TextToSpeechService
import android.speech.tts.Voice
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference

/**
 * Android binds directly to this service, including cold starts without MainActivity or a WebView.
 * Configuration is loaded from native storage per utterance. No Activity, JS, or preview state is used.
 * onSynthesizeText is serial on Android's synthesis thread; onStop can cancel it from another thread.
 */
class FishTtsService : TextToSpeechService() {
    private lateinit var store: ConfigurationStore
    private val synthesizer = FishSynthesizer()
    private val liveSynthesizer = FishLiveSynthesizer()
    private val active = AtomicReference<SynthesisSession?>(null)
    @Volatile private var loadedLocale = Locale.US

    override fun onCreate() {
        super.onCreate()
        store = ConfigurationStore(this)
        loadedLocale = VoiceCatalog.localeFor(Locale.getDefault().language) ?: Locale.US
    }

    override fun onGetLanguage(): Array<String> = loadedLocale.let { arrayOf(it.isO3Language, it.isO3Country, "") }

    override fun onIsLanguageAvailable(lang: String, country: String, variant: String): Int =
        VoiceCatalog.availability(lang, country, configuration().configured)

    override fun onLoadLanguage(lang: String, country: String, variant: String): Int {
        val available = onIsLanguageAvailable(lang, country, variant)
        if (available >= TextToSpeech.LANG_AVAILABLE) loadedLocale = VoiceCatalog.localeFor(lang)!!
        return available
    }

    override fun onGetVoices(): List<Voice> = VoiceCatalog.voices(configuration())

    override fun onGetDefaultVoiceNameFor(lang: String, country: String, variant: String): String? {
        val config = configuration()
        val locale = VoiceCatalog.localeFor(lang) ?: return null
        val profile = config.defaultVoice ?: return null
        return if (config.configured) VoiceCatalog.name(profile, locale) else null
    }

    override fun onIsValidVoiceName(voiceName: String): Int =
        if (VoiceCatalog.selection(voiceName, configuration()) != null) TextToSpeech.SUCCESS else TextToSpeech.ERROR

    override fun onLoadVoice(voiceName: String): Int {
        val selection = VoiceCatalog.selection(voiceName, configuration()) ?: return TextToSpeech.ERROR
        loadedLocale = selection.second
        return TextToSpeech.SUCCESS
    }

    override fun onSynthesizeText(request: SynthesisRequest, callback: SynthesisCallback) {
        val session = SynthesisSession()
        active.set(session)
        try {
            val config = try { store.read() } catch (_: Exception) {
                liveSynthesizer.stop()
                callback.error(TextToSpeech.ERROR_SERVICE)
                callback.done()
                return
            }
            val selection = if (request.voiceName.isNullOrEmpty()) {
                val locale = VoiceCatalog.localeFor(request.language)
                val profile = config.defaultVoice
                if (locale != null && profile != null) profile to locale else null
            } else VoiceCatalog.selection(request.voiceName, config)
            if (!config.configured || selection == null) {
                liveSynthesizer.stop()
                callback.error(if (!config.configured) TextToSpeech.ERROR_NOT_INSTALLED_YET else TextToSpeech.ERROR_INVALID_REQUEST)
                callback.done()
                return
            }
            // Fish supports rate through prosody.speed; it has no documented pitch control.
            val language = if (config.language == "unset") "" else selection.second.toLanguageTag()
            if (config.synthesisMode == SynthesisMode.CONTINUOUS) {
                liveSynthesizer.synthesize(config.apiKey, selection.first, request.charSequenceText.toString(),
                    request.speechRate.takeIf { it > 0 }, language, request.callerUid, session, callback)
            } else {
                liveSynthesizer.stop()
                synthesizer.synthesize(config.apiKey, selection.first, request.charSequenceText.toString(),
                    request.speechRate.takeIf { it > 0 }, language, session, callback)
            }
        } finally {
            active.compareAndSet(session, null)
        }
    }

    override fun onStop() { active.get()?.cancel(); liveSynthesizer.stop() }

    override fun onDestroy() {
        onStop()
        liveSynthesizer.close()
        super.onDestroy()
    }

    private fun configuration(): EngineConfiguration = runCatching { store.read() }.getOrDefault(EngineConfiguration())
}
