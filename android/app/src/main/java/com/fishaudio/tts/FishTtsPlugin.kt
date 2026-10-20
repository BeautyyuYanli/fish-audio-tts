package com.fishaudio.tts

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.Bundle
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin
import org.json.JSONArray
import java.util.Locale
import java.util.UUID

/**
 * Settings bridge and an ordinary TextToSpeech client for previews of our registered engine.
 * Preview ownership stays on the main thread; generation IDs discard late initialization/listener
 * messages after stop, replacement, or Activity destruction. The service owns all synthesis.
 * Credentials are write-only across the bridge and never included in events or rejection details.
 */
@CapacitorPlugin(name = "FishTts")
class FishTtsPlugin : Plugin() {
    private val main = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null
    private var generation = 0
    private var pending: PluginCall? = null
    @Volatile private var previewState = "idle"
    @Volatile private var previewMessage = "No preview yet."

    @PluginMethod
    fun getConfiguration(call: PluginCall) {
        try { call.resolve(publicConfiguration(ConfigurationStore(context).read())) }
        catch (_: Exception) { call.reject("Saved configuration is unreadable. Clear app data in Android Settings and configure again.") }
    }

    @PluginMethod
    fun saveApiKey(call: PluginCall) {
        try {
            val config = ConfigurationStore(context).saveApiKey(call.getString("apiKey") ?: "")
            call.resolve(publicConfiguration(config))
        } catch (error: IllegalArgumentException) {
            call.reject(error.message ?: "Invalid configuration.")
        } catch (_: Exception) {
            call.reject("Unable to save protected configuration. Try again or clear app data in Android Settings.")
        } finally {
            // Do not retain the input credential in Capacitor's PluginCall object.
            call.data.remove("apiKey")
        }
    }

    @PluginMethod
    fun deleteApiKey(call: PluginCall) {
        try { call.resolve(publicConfiguration(ConfigurationStore(context).deleteApiKey())) }
        catch (_: Exception) { call.reject("Unable to delete the API key. Please try again.") }
    }

    @PluginMethod
    fun saveVoices(call: PluginCall) {
        try {
            val array = call.getArray("voices") ?: throw IllegalArgumentException("Provide a voice list.")
            val voices = (0 until array.length()).map { VoiceProfile.fromJson(array.getJSONObject(it)) }
            call.resolve(publicConfiguration(ConfigurationStore(context).saveVoices(voices)))
        } catch (error: IllegalArgumentException) {
            call.reject(error.message ?: "Invalid voice configuration.")
        } catch (_: org.json.JSONException) {
            call.reject("Each voice must contain a Reference ID and a JSON object of generation parameters.")
        } catch (_: Exception) {
            call.reject("Unable to save voices. Please try again.")
        }
    }

    @PluginMethod
    fun setDefaultVoice(call: PluginCall) {
        try {
            val voiceId = call.getString("voiceId") ?: throw IllegalArgumentException("Select a saved voice as the system default.")
            call.resolve(publicConfiguration(ConfigurationStore(context).saveDefaultVoice(voiceId)))
        } catch (error: IllegalArgumentException) {
            call.reject(error.message ?: "Invalid default voice.")
        } catch (_: Exception) {
            call.reject("Unable to save the default voice. Please try again.")
        }
    }

    @PluginMethod
    fun preview(call: PluginCall) {
        val text = call.getString("text") ?: ""
        if (text.isBlank() || text.length > 4000) { call.reject("Enter between 1 and 4000 characters of preview text."); return }
        val speed = call.getFloat("speed") ?: 1.0f
        if (!speed.isFinite() || speed < 0.5f || speed > 2.0f) {
            call.reject("Choose a preview speed between 0.5 and 2.0.")
            return
        }
        val config = try { ConfigurationStore(context).read() }
        catch (_: Exception) { call.reject("Unable to read configuration. Clear app data in Android Settings and configure again."); return }
        if (!config.configured) { call.reject("Save an API key and at least one voice before starting a preview."); return }
        val profile = config.voices.firstOrNull { it.id == call.getString("voiceId") }
        if (profile == null) { call.reject("Select a saved voice to preview."); return }
        val locale = VoiceCatalog.locales.firstOrNull { it.toLanguageTag() == call.getString("language") }
        if (locale == null) { call.reject("Select a supported preview language."); return }
        main.post { beginPreview(call, profile, locale, text, speed) }
    }

    @PluginMethod
    fun stop(call: PluginCall) {
        main.post {
            releasePreview()
            updateStatus("stopped", "Preview stopped.")
            call.resolve()
        }
    }

    @PluginMethod
    fun getPreviewStatus(call: PluginCall) { call.resolve(statusObject()) }

    @PluginMethod
    fun openTtsSettings(call: PluginCall) {
        main.post {
            try {
                try {
                    // AOSP's TTS settings action has no public Settings constant.
                    activity.startActivity(Intent("com.android.settings.TTS_SETTINGS"))
                } catch (_: ActivityNotFoundException) {
                    activity.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                }
                call.resolve()
            } catch (_: Exception) { call.reject("Unable to open TTS settings. Open Android Settings and search for text-to-speech.") }
        }
    }

    private fun beginPreview(call: PluginCall, profile: VoiceProfile, locale: Locale, text: String, speed: Float) {
        releasePreview()
        val token = generation
        pending = call
        updateStatus("initializing", "Connecting to the Fish Audio system TTS engine...")
        tts = TextToSpeech(context.applicationContext, { result ->
            main.post {
                if (token != generation) return@post
                val engine = tts
                if (result != TextToSpeech.SUCCESS || engine == null) {
                    failPreview("Unable to initialize the Fish Audio system TTS engine.")
                    return@post
                }
                // TextToSpeech can fall back to another installed engine. Require our exact network voice.
                val voiceName = VoiceCatalog.name(profile, locale)
                val voice = engine.voices?.firstOrNull { it.name == voiceName && it.isNetworkConnectionRequired }
                if (voice == null || engine.setVoice(voice) != TextToSpeech.SUCCESS) {
                    failPreview("The configured Fish Audio network voice is unavailable. Save the API key and voice list, then try again.")
                    return@post
                }
                if (engine.setSpeechRate(speed) != TextToSpeech.SUCCESS) {
                    failPreview("Unable to set the preview speed.")
                    return@post
                }
                engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(id: String) { postStatus(token, "playing", "Playing synthesized speech.") }
                    override fun onDone(id: String) {
                        main.post { if (token == generation) { updateStatus("done", "Preview completed."); releasePreview() } }
                    }
                    @Deprecated("Android legacy callback")
                    @Suppress("OVERRIDE_DEPRECATION")
                    override fun onError(id: String) { onError(id, TextToSpeech.ERROR_SYNTHESIS) }
                    @Suppress("OVERRIDE_DEPRECATION")
                    override fun onError(id: String, errorCode: Int) {
                        main.post { if (token == generation) failPreview(errorMessage(errorCode)) }
                    }
                    override fun onStop(id: String, interrupted: Boolean) {
                        main.post { if (token == generation) { updateStatus("stopped", "Preview stopped."); releasePreview() } }
                    }
                })
                updateStatus("synthesizing", "Requesting speech from Fish Audio...")
                if (engine.speak(text, TextToSpeech.QUEUE_FLUSH, Bundle(), UUID.randomUUID().toString()) != TextToSpeech.SUCCESS) {
                    failPreview("The system TTS engine could not queue the preview.")
                } else {
                    pending?.resolve()
                    pending = null
                }
            }
        }, context.packageName)
    }

    private fun postStatus(token: Int, state: String, message: String) {
        main.post { if (token == generation) updateStatus(state, message) }
    }

    private fun failPreview(message: String) {
        pending?.reject(message)
        pending = null
        updateStatus("error", message)
        releasePreview()
    }

    private fun releasePreview() {
        generation++
        pending?.reject("Preview stopped.")
        pending = null
        tts?.stop()
        tts?.shutdown()
        tts = null
    }

    private fun updateStatus(state: String, message: String) {
        previewState = state
        previewMessage = message
        notifyListeners("previewStatus", statusObject())
    }

    private fun statusObject(): JSObject = JSObject().put("state", previewState).put("message", previewMessage)

    private fun publicConfiguration(config: EngineConfiguration): JSObject = JSObject()
        .put("hasApiKey", config.apiKey.isNotBlank()).put("configured", config.configured)
        .put("voices", JSONArray(config.voices.map { it.toJson() }))
        .put("defaultVoiceId", config.defaultVoiceId)

    private fun errorMessage(code: Int): String = when (code) {
        TextToSpeech.ERROR_NETWORK_TIMEOUT -> "Fish Audio timed out. Check your connection and try again."
        TextToSpeech.ERROR_NETWORK -> "Fish Audio network request failed. Check your connection or rate limit and try again."
        TextToSpeech.ERROR_NOT_INSTALLED_YET -> "Save an API key and Reference ID before synthesizing speech."
        TextToSpeech.ERROR_INVALID_REQUEST -> "Fish Audio rejected the text, Reference ID or generation parameters. Check configuration and try again."
        TextToSpeech.ERROR_SERVICE -> "Fish Audio request failed. Check your API key, account access and credits."
        TextToSpeech.ERROR_OUTPUT -> "Android could not accept the audio output."
        TextToSpeech.ERROR_SYNTHESIS -> "Audio synthesis or decoding failed. Please retry."
        else -> "Speech synthesis failed. Check the API key, Reference ID and network connection."
    }

    override fun handleOnDestroy() {
        main.post { releasePreview() }
        super.handleOnDestroy()
    }
}
