package com.fishaudio.tts

import android.media.AudioFormat
import android.speech.tts.SynthesisCallback
import android.speech.tts.TextToSpeech
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject
import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.util.ArrayDeque
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * One service-owned, in-memory Fish generation session. Android calls synthesize serially;
 * WebSocket workers only enqueue bounded PCM, never retain or invoke a SynthesisCallback.
 * Each text is flushed; 500 ms without nonempty audio heuristically ends that Android request.
 * The same start/context survives for three idle minutes with an identical caller/configuration.
 * Delayed audio while idle invalidates the socket. Audio arriving after the next text was sent
 * cannot be distinguished from that text's output: the timeout intentionally accepts this risk.
 * PCM avoids treating an unfinished, connection-wide Ogg stream as an utterance-sized file.
 */
class FishLiveSynthesizer(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS).writeTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS).pingInterval(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false).build(),
    private val endpoint: HttpUrl = "https://api.fish.audio/v1/tts/live".toHttpUrl(),
    private val idleTimeoutMillis: Long = 180_000,
    private val firstAudioTimeoutMillis: Long = 30_000,
) {
    private val lock = Object()
    private val timer = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "fish-live-idle").apply { isDaemon = true }
    }
    private var connection: Connection? = null
    private var destroyed = false

    private data class Key(val apiKey: String, val callerUid: Int, val profileId: String,
                           val model: String, val prefix: String, val parameters: String)

    private inner class Connection(val key: Key) : WebSocketListener() {
        lateinit var socket: WebSocket
        val audio = ArrayDeque<ByteArray>()
        var queuedBytes = 0
        var lastAudioNanos = 0L
        var opened = false
        var active = true
        var ended = false
        var failure: IOException? = null
        var idleUntilNanos = 0L
        var idleTask: ScheduledFuture<*>? = null

        override fun onOpen(webSocket: WebSocket, response: Response) = synchronized(lock) {
            opened = true
            lock.notifyAll()
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            val event = try { FishLiveProtocol.decode(bytes) }
            catch (_: Exception) { synchronized(lock) { disconnect(this, InvalidAudioException("Invalid WebSocket event.")) }; return }
            synchronized(lock) {
                if (failure != null) return
                when (event.name) {
                    "audio" -> {
                        val data = event.audio ?: run { disconnect(this, InvalidAudioException("Missing PCM audio.")); return }
                        if (data.isEmpty()) return
                        if (!active || ended || queuedBytes + data.size > MAX_QUEUED_BYTES) {
                            disconnect(this, InvalidAudioException("Unexpected or excessive PCM audio."))
                            return
                        }
                        audio.add(data)
                        queuedBytes += data.size
                        lastAudioNanos = System.nanoTime()
                    }
                    "finish" -> {
                        if (event.reason != "stop") { disconnect(this, SynthesisFailure(TextToSpeech.ERROR_SERVICE)); return }
                        ended = true
                        if (connection === this) connection = null
                        idleTask?.cancel(false)
                        socket.close(1000, null)
                    }
                    "error" -> disconnect(this, SynthesisFailure(TextToSpeech.ERROR_SERVICE))
                }
                lock.notifyAll()
            }
        }

        override fun onMessage(webSocket: WebSocket, text: String) = synchronized(lock) {
            disconnect(this, InvalidAudioException("Expected binary MessagePack."))
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) = synchronized(lock) {
            webSocket.close(code, null)
            if (!ended) disconnect(this, IOException("WebSocket closed."))
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = synchronized(lock) {
            if (!ended) disconnect(this, response?.let { SynthesisFailure(when (it.code) {
                408, 504 -> TextToSpeech.ERROR_NETWORK_TIMEOUT
                429, in 500..599 -> TextToSpeech.ERROR_NETWORK
                400, 404, 422 -> TextToSpeech.ERROR_INVALID_REQUEST
                else -> TextToSpeech.ERROR_SERVICE
            }) } ?: if (t is InterruptedIOException) SocketTimeoutException() else IOException("WebSocket failed."))
        }
    }

    /** Null language preserves profile parameters; an explicit empty language overrides them. */
    fun synthesize(apiKey: String, profile: VoiceProfile, text: String, speechRate: Int?, language: String?,
                   callerUid: Int, session: SynthesisSession, callback: SynthesisCallback) {
        var current: Connection? = null
        var success = false
        try {
            session.check()
            if (apiKey.isBlank()) throw SynthesisFailure(TextToSpeech.ERROR_NOT_INSTALLED_YET)
            if (text.isBlank() || text.length > 4000) throw SynthesisFailure(TextToSpeech.ERROR_INVALID_REQUEST)
            val parameters = JSONObject(profile.parameters.toString()).put("text", "").put("reference_id", profile.referenceId)
                .put("format", "pcm").put("sample_rate", SAMPLE_RATE).put("condition_on_previous_chunks", true)
            if (language != null) parameters.put("language", language)
            if (speechRate != null) {
                val prosody = parameters.optJSONObject("prosody") ?: JSONObject()
                parameters.put("prosody", prosody.put("speed", speechRate / 100.0))
            }
            val key = Key(apiKey, callerUid, profile.id, profile.model, profile.prefixText, parameters.toString())
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(120)
            synchronized(lock) {
                session.check()
                if (destroyed) throw SynthesisCancelled()
                connection?.let { if (it.key != key) disconnect(it, SynthesisCancelled()) }
                val fresh = connection == null
                val live = connection ?: Connection(key).also {
                    val request = Request.Builder().url(endpoint).header("Authorization", "Bearer $apiKey")
                    if (profile.model.isNotBlank()) request.header("model", profile.model)
                    it.socket = client.newWebSocket(request.build(), it)
                    connection = it
                }
                current = live
                live.idleTask?.cancel(false)
                live.active = true
                live.lastAudioNanos = 0
                session.attach { synchronized(lock) { disconnect(live, SynthesisCancelled()) } }
                val openDeadline = minOf(deadline, System.nanoTime() + TimeUnit.SECONDS.toNanos(15))
                while (!live.opened) {
                    session.check()
                    live.failure?.let { throw it }
                    if (System.nanoTime() >= openDeadline) throw SocketTimeoutException()
                    waitUntil(openDeadline)
                }
                session.check()
                live.failure?.let { throw it }
                if (fresh) send(live, JSONObject().put("event", "start").put("request", parameters))
                send(live, JSONObject().put("event", "text").put("text", profile.prefixText + text))
                send(live, JSONObject().put("event", "flush"))
            }
            val live = current!!
            val firstDeadline = minOf(deadline, System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(firstAudioTimeoutMillis))
            val capacity = minOf(callback.maxBufferSize, 8192) / 2 * 2
            if (capacity < 2) throw SynthesisFailure(TextToSpeech.ERROR_OUTPUT)
            var started = false
            var pendingByte: Byte? = null
            var totalBytes = 0L
            while (true) {
                val data = nextAudio(live, deadline, firstDeadline, session) ?: break
                totalBytes += data.size
                if (totalBytes > 64L * 1024 * 1024) throw InvalidAudioException("PCM response is too large.")
                val pcm = pendingByte?.let { byteArrayOf(it) + data } ?: data
                val length = pcm.size / 2 * 2
                pendingByte = if (length != pcm.size) pcm.last() else null
                if (length > 0 && !started) {
                    checkOutput(callback.start(SAMPLE_RATE, AudioFormat.ENCODING_PCM_16BIT, 1))
                    started = true
                }
                var offset = 0
                while (offset < length) {
                    session.check()
                    if (System.nanoTime() >= deadline) throw SocketTimeoutException()
                    val size = minOf(capacity, length - offset)
                    checkOutput(callback.audioAvailable(pcm, offset, size))
                    offset += size
                }
            }
            session.check()
            if (!started || pendingByte != null) throw InvalidAudioException("Incomplete PCM audio.")
            success = true
        } catch (_: SynthesisCancelled) {
            // Only this synthesis thread completes the callback after cross-thread cancellation.
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
            if (!success) synchronized(lock) { current?.let { disconnect(it, SynthesisCancelled()) } }
            session.release()
            callback.done()
        }
    }

    private fun nextAudio(live: Connection, deadline: Long, firstDeadline: Long, session: SynthesisSession): ByteArray? = synchronized(lock) {
        while (live.audio.isEmpty()) {
            session.check()
            live.failure?.let { throw it }
            if (live.ended) { live.active = false; return@synchronized null }
            val now = System.nanoTime()
            if (now >= deadline) throw SocketTimeoutException()
            val audioDeadline = if (live.lastAudioNanos == 0L) firstDeadline
                else live.lastAudioNanos + TimeUnit.MILLISECONDS.toNanos(500)
            if (now >= audioDeadline) {
                if (live.lastAudioNanos == 0L) throw SocketTimeoutException()
                live.active = false
                live.idleUntilNanos = now + TimeUnit.MILLISECONDS.toNanos(idleTimeoutMillis)
                live.idleTask = timer.schedule({ synchronized(lock) {
                    if (connection === live && !live.active && System.nanoTime() >= live.idleUntilNanos) {
                        disconnect(live, IOException("Idle timeout."), graceful = true)
                    }
                } }, idleTimeoutMillis, TimeUnit.MILLISECONDS)
                return@synchronized null
            }
            waitUntil(minOf(deadline, audioDeadline))
        }
        session.check()
        live.failure?.let { throw it }
        if (System.nanoTime() >= deadline) throw SocketTimeoutException()
        live.audio.removeFirst().also { live.queuedBytes -= it.size }
    }

    /** Called with lock held; monitor waits release it so network and stop can always make progress. */
    private fun waitUntil(deadline: Long) {
        val remaining = deadline - System.nanoTime()
        if (remaining > 0) TimeUnit.NANOSECONDS.timedWait(lock, remaining)
    }

    private fun send(live: Connection, event: JSONObject) {
        if (!live.socket.send(FishLiveProtocol.encode(event))) throw IOException("Unable to send WebSocket event.")
    }

    /** Caller holds lock. An old socket's callbacks must never detach a replacement socket. */
    private fun disconnect(live: Connection, failure: IOException, graceful: Boolean = false) {
        if (connection === live) connection = null
        if (live.failure == null) live.failure = failure
        live.idleTask?.cancel(false)
        live.audio.clear()
        live.queuedBytes = 0
        if (graceful) live.socket.close(1000, "Idle timeout") else live.socket.cancel()
        lock.notifyAll()
    }

    fun stop() = synchronized(lock) { connection?.let { disconnect(it, SynthesisCancelled()) }; Unit }

    fun close() {
        synchronized(lock) { destroyed = true; stop() }
        timer.shutdownNow()
    }

    private fun checkOutput(result: Int) {
        if (result == TextToSpeech.STOPPED) throw SynthesisCancelled()
        if (result != TextToSpeech.SUCCESS) throw SynthesisFailure(TextToSpeech.ERROR_OUTPUT)
    }

    companion object {
        const val SAMPLE_RATE = 44100
        private const val MAX_QUEUED_BYTES = 1024 * 1024
    }
}
