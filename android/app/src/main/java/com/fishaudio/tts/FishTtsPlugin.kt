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
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.io.InterruptedIOException
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Settings bridge and an ordinary TextToSpeech client for previews of our registered engine.
 * Preview ownership stays on the main thread; generation IDs discard late initialization/listener
 * messages after stop, replacement, or Activity destruction. The service owns all synthesis.
 * Credentials are write-only across the bridge and never included in events or rejection details.
 * Catalog calls run on OkHttp workers, are cancelled on key changes/destruction, and own no TTS state.
 */
@CapacitorPlugin(name = "FishTts")
class FishTtsPlugin : Plugin() {
    private val main = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null
    private var generation = 0
    private var pending: PluginCall? = null
    private val catalog = FishCatalogClient()
    private val catalogCalls = ConcurrentHashMap<Call, PluginCall>()
    @Volatile private var catalogDestroyed = false
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
            cancelCatalogCalls()
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
        try {
            val config = ConfigurationStore(context).deleteApiKey()
            cancelCatalogCalls()
            call.resolve(publicConfiguration(config))
        }
        catch (_: Exception) { call.reject("Unable to delete the API key. Please try again.") }
    }

    @PluginMethod
    fun saveVoice(call: PluginCall) {
        try {
            val json = call.getObject("voice") ?: throw IllegalArgumentException("Provide a voice profile.")
            val voice = VoiceProfile.fromJson(json)
            val config = ConfigurationStore(context).saveVoice(voice)
            call.resolve(publicConfiguration(config).put("savedVoiceId", voice.id))
        } catch (error: IllegalArgumentException) {
            call.reject(error.message ?: "Invalid voice configuration.")
        } catch (_: org.json.JSONException) {
            call.reject("Provide a Reference ID and a JSON object of generation parameters.")
        } catch (_: Exception) {
            call.reject("Unable to save this voice. Please try again.")
        }
    }

    @PluginMethod
    fun removeVoice(call: PluginCall) {
        try {
            val id = call.getString("voiceId") ?: throw IllegalArgumentException("Select a saved voice to remove.")
            call.resolve(publicConfiguration(ConfigurationStore(context).removeVoice(id)))
        } catch (error: IllegalArgumentException) {
            call.reject(error.message ?: "Invalid voice profile.")
        } catch (_: Exception) {
            call.reject("Unable to remove this voice. Please try again.")
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
    fun setSynthesisMode(call: PluginCall) {
        try {
            val mode = SynthesisMode.fromValue(call.getString("synthesisMode") ?: "")
            call.resolve(publicConfiguration(ConfigurationStore(context).saveSynthesisMode(mode)))
        } catch (error: IllegalArgumentException) {
            call.reject(error.message ?: "Invalid synthesis mode.")
        } catch (_: Exception) {
            call.reject("Unable to save the synthesis mode. Please try again.")
        }
    }

    @PluginMethod
    fun getSynthesisModels(call: PluginCall) {
        queryCatalog(call, catalog.modelsCall()) { response -> JSObject().put("models", JSONArray(catalog.readModels(response))) }
    }

    @PluginMethod
    fun getOwnVoices(call: PluginCall) {
        val page = call.getInt("pageNumber") ?: 1
        val request = try { catalog.voicesCall(ConfigurationStore(context).read().apiKey, page) }
        catch (error: IllegalArgumentException) { call.reject(error.message ?: "Invalid voice catalog request."); return }
        catch (_: Exception) { call.reject("Unable to read the saved API key."); return }
        queryCatalog(call, request) { response ->
            val result = catalog.readVoices(response, page)
            JSObject().put("voices", JSONArray(result.voices.map { JSONObject().put("id", it.id).put("title", it.title) }))
                .put("hasMore", result.hasMore)
        }
    }

    private fun queryCatalog(call: PluginCall, request: Call, parse: (Response) -> JSObject) {
        catalogCalls[request] = call
        if (catalogDestroyed) { cancelCatalogCalls(); return }
        request.enqueue(object : Callback {
            override fun onFailure(request: Call, error: IOException) = finish(Result.failure(error))
            override fun onResponse(request: Call, response: Response) = finish(runCatching { response.use(parse) })
            private fun finish(result: Result<JSObject>) {
                val pendingCall = catalogCalls.remove(request) ?: return
                result.fold(pendingCall::resolve) { error ->
                    pendingCall.reject(when (error) {
                        is CatalogException -> error.message ?: "Unable to load the Fish Audio catalog."
                        is InterruptedIOException -> "Fish Audio's catalog request timed out. Please retry."
                        is IOException -> "Unable to load the Fish Audio catalog. Check your network connection."
                        else -> "Fish Audio returned an invalid catalog. Please retry."
                    })
                }
            }
        })
    }

    private fun cancelCatalogCalls() {
        for (request in catalogCalls.keys) {
            val pendingCall = catalogCalls.remove(request) ?: continue
            request.cancel()
            pendingCall.reject("Catalog request cancelled.")
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
        .put("synthesisMode", config.synthesisMode.value)

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
        catalogDestroyed = true
        cancelCatalogCalls()
        main.post { releasePreview() }
        super.handleOnDestroy()
    }
}
