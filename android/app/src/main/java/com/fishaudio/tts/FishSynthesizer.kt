package com.fishaudio.tts

import android.media.AudioFormat
import android.speech.tts.SynthesisCallback
import android.speech.tts.TextToSpeech
import okhttp3.Call
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Lock-free cross-thread stop handle. Publishing a call after cancellation cancels it immediately. */
class SynthesisSession {
    private val cancelled = AtomicBoolean(false)
    private val call = AtomicReference<Call?>(null)

    fun cancel() { cancelled.set(true); call.get()?.cancel() }
    fun attach(value: Call) { call.set(value); if (cancelled.get()) value.cancel() }
    fun release() { call.set(null) }
    fun isCancelled(): Boolean = cancelled.get()
    fun check() { if (cancelled.get()) throw SynthesisCancelled() }
}

private class SynthesisCancelled : IOException()
private class SynthesisFailure(val code: Int) : IOException()

/**
 * Blocking network synthesis: the framework owns the synthesis thread and callback lifetime.
 * No worker retains the callback; error and done run on this thread even after stop or exceptions.
 * Redirects/retries are disabled to keep credentials at Fish and avoid duplicate billed requests.
 * Endpoint injection exists only for native contract tests, never in user configuration.
 */
class FishSynthesizer(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS).writeTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS).callTimeout(120, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false).build(),
    private val endpoint: HttpUrl = "https://api.fish.audio/v1/tts".toHttpUrl(),
) {
    fun synthesize(apiKey: String, profile: VoiceProfile, text: String, speechRate: Int?, language: String?,
                   session: SynthesisSession, callback: SynthesisCallback) {
        try {
            session.check()
            if (apiKey.isBlank()) throw SynthesisFailure(TextToSpeech.ERROR_NOT_INSTALLED_YET)
            if (text.isBlank() || text.length > 4000) throw SynthesisFailure(TextToSpeech.ERROR_INVALID_REQUEST)
            // Deep-copy table parameters, then overlay only supplied system generation values.
            // Do not fill generation defaults or multiply/clamp a system rate; Fish validates its own bounds.
            // Prefixes apply once in the native synthesis path, including previews and Activity-free system calls.
            val body = JSONObject(profile.parameters.toString()).put("text", profile.prefixText + text).put("reference_id", profile.referenceId)
                .put("format", "opus").put("sample_rate", OggOpusReader.SAMPLE_RATE)
            // Bitrate is transport policy; preserve an explicitly configured upstream bitrate.
            if (!body.has("opus_bitrate")) body.put("opus_bitrate", 32000)
            if (!language.isNullOrBlank()) body.put("language", language)
            if (speechRate != null) {
                val prosody = body.optJSONObject("prosody") ?: JSONObject()
                prosody.put("speed", speechRate / 100.0)
                body.put("prosody", prosody)
            }
            val builder = Request.Builder().url(endpoint).header("Authorization", "Bearer $apiKey")
            if (profile.model.isNotBlank()) builder.header("model", profile.model)
            val request = builder.post(body.toString().toRequestBody("application/json".toMediaType())).build()
            val call = client.newCall(request)
            session.attach(call)
            session.check()
            call.execute().use { response ->
                if (!response.isSuccessful) throw SynthesisFailure(when (response.code) {
                    408, 504 -> TextToSpeech.ERROR_NETWORK_TIMEOUT
                    429, in 500..599 -> TextToSpeech.ERROR_NETWORK
                    400, 404, 422 -> TextToSpeech.ERROR_INVALID_REQUEST
                    else -> TextToSpeech.ERROR_SERVICE
                })
                val stream = response.body?.byteStream() ?: throw SynthesisFailure(TextToSpeech.ERROR_SYNTHESIS)
                OpusPcmDecoder().decode(stream, callback.maxBufferSize, session::check,
                    start = { checkOutput(callback.start(OggOpusReader.SAMPLE_RATE, AudioFormat.ENCODING_PCM_16BIT, 1)) },
                    emit = { buffer, count -> checkOutput(callback.audioAvailable(buffer, 0, count)) })
                session.check()
            }
        } catch (_: SynthesisCancelled) {
            // The framework has already stopped/discarded playback. Only the synthesis thread finishes the callback.
        } catch (failure: SynthesisFailure) {
            if (!session.isCancelled()) callback.error(failure.code)
        } catch (_: InvalidAudioException) {
            if (!session.isCancelled()) callback.error(TextToSpeech.ERROR_SYNTHESIS)
        } catch (_: InterruptedIOException) {
            if (!session.isCancelled()) callback.error(TextToSpeech.ERROR_NETWORK_TIMEOUT)
        } catch (_: IOException) {
            if (!session.isCancelled()) callback.error(TextToSpeech.ERROR_NETWORK)
        } catch (_: Exception) {
            if (!session.isCancelled()) callback.error(TextToSpeech.ERROR_SYNTHESIS)
        } finally {
            session.release()
            callback.done()
        }
    }

    private fun checkOutput(result: Int) {
        if (result == TextToSpeech.STOPPED) throw SynthesisCancelled()
        if (result != TextToSpeech.SUCCESS) throw SynthesisFailure(TextToSpeech.ERROR_OUTPUT)
    }
}
