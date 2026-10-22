package com.fishaudio.tts

import android.speech.tts.SynthesisCallback
import android.speech.tts.TextToSpeech
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.msgpack.core.MessagePack
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** Real local WebSocket handshakes/MessagePack/PCM and Android callback contracts; no upstream credentials. */
class FishLiveSynthesizerTest {
    private lateinit var server: MockWebServer
    private val clients = mutableListOf<FishLiveSynthesizer>()
    private val workers = Executors.newScheduledThreadPool(2)
    private val profile = VoiceProfile("first", "Voice", "reference-one", "s2.1-pro", JSONObject()
        .put("condition_on_previous_chunks", false).put("normalize", false)
        .put("prosody", JSONObject().put("speed", 1.5).put("volume", -3)), "[calm] ")
    private val pcm = byteArrayOf(1, 2, 3, 4, 5, 6)

    @Before fun setUp() { server = MockWebServer(); server.start() }
    @After fun tearDown() {
        clients.forEach { it.close() }
        workers.shutdownNow()
        server.shutdown()
    }

    private fun synthesizer(idleMillis: Long = 180_000, firstMillis: Long = 30_000) =
        FishLiveSynthesizer(OkHttpClient.Builder().followRedirects(false).retryOnConnectionFailure(false).build(),
            server.url("/v1/tts/live"), idleMillis, firstMillis).also { clients.add(it) }

    private fun speak(client: FishLiveSynthesizer, text: String = "Hello.", selected: VoiceProfile = profile,
                      rate: Int = 120, uid: Int = 10, key: String = "fixture-key", session: SynthesisSession = SynthesisSession(),
                      callback: PcmCallback = PcmCallback()): PcmCallback {
        client.synthesize(key, selected, text, rate, "en-US", uid, session, callback)
        return callback
    }

    private inner class Peer(val onFlush: (Peer) -> Unit = { it.audio(pcm) }) : WebSocketListener() {
        lateinit var socket: WebSocket
        val events = LinkedBlockingQueue<JSONObject>()
        val flushed = CountDownLatch(1)
        val closed = CountDownLatch(1)
        override fun onOpen(webSocket: WebSocket, response: Response) { socket = webSocket }
        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            val event = MessagePack.newDefaultUnpacker(bytes.toByteArray()).use { JSONObject(it.unpackValue().toJson()) }
            events.add(event)
            if (event.getString("event") == "flush") { onFlush(this); flushed.countDown() }
        }
        fun audio(data: ByteArray) {
            val encoded = MessagePack.newDefaultBufferPacker().use {
                it.packMapHeader(2).packString("event").packString("audio").packString("audio")
                    .packBinaryHeader(data.size).writePayload(data)
                it.toByteArray().toByteString()
            }
            socket.send(encoded)
        }
        fun event(name: String, reason: String = "stop") {
            socket.send(FishLiveProtocol.encode(JSONObject().put("event", name).put("reason", reason)))
        }
        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, null) }
        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { closed.countDown() }
        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { closed.countDown() }
        fun next() = events.poll(2, TimeUnit.SECONDS) ?: throw AssertionError("Missing client event")
    }

    private fun peer(onFlush: (Peer) -> Unit = { it.audio(pcm) }): Peer = Peer(onFlush).also {
        server.enqueue(MockResponse().withWebSocketUpgrade(it))
    }

    @Test fun flushesEachSegmentAndReusesOneStartWithPreviousChunkConditioning() {
        val peer = peer()
        val client = synthesizer()
        val before = System.nanoTime()
        val first = speak(client)
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - before) >= 500)
        val second = speak(client, "Next sentence.")
        for (callback in listOf(first, second)) {
            assertArrayEquals(pcm, callback.audio.toByteArray())
            assertEquals(listOf(44100, 2, 1), callback.format)
            assertEquals(1, callback.doneCount)
            assertTrue(callback.errors.isEmpty())
            assertTrue(callback.threads.all { it === Thread.currentThread() })
            assertTrue(callback.lengths.all { it <= 3 && it % 2 == 0 })
        }
        val handshake = server.takeRequest(1, TimeUnit.SECONDS)!!
        assertEquals("/v1/tts/live", handshake.path)
        assertEquals("Bearer fixture-key", handshake.getHeader("Authorization"))
        assertEquals(profile.model, handshake.getHeader("model"))
        val start = peer.next()
        assertEquals("start", start.getString("event"))
        val request = start.getJSONObject("request")
        assertEquals("", request.getString("text"))
        assertEquals("pcm", request.getString("format"))
        assertEquals(44100, request.getInt("sample_rate"))
        assertTrue(request.getBoolean("condition_on_previous_chunks"))
        assertFalse(request.getBoolean("normalize"))
        assertEquals("en-US", request.getString("language"))
        assertEquals(profile.referenceId, request.getString("reference_id"))
        assertEquals(1.2, request.getJSONObject("prosody").getDouble("speed"), 0.0)
        assertEquals(-3, request.getJSONObject("prosody").getInt("volume"))
        assertEquals("[calm] Hello.", peer.next().getString("text"))
        assertEquals("flush", peer.next().getString("event"))
        assertEquals("[calm] Next sentence.", peer.next().getString("text"))
        assertEquals("flush", peer.next().getString("event"))
        assertEquals(1, server.requestCount)
        assertFalse(peer.closed.await(100, TimeUnit.MILLISECONDS))
        assertFalse(profile.parameters.getBoolean("condition_on_previous_chunks"))
    }

    @Test fun languageChangesStartNewContextsAndExplicitEmptyOverridesTheProfile() {
        val json = profile.toJson()
        json.getJSONObject("parameters").put("language", "ja-JP")
        val selected = VoiceProfile.fromJson(json)
        val client = synthesizer()
        for ((language, expected) in listOf("en-US" to "en-US", "" to "", null to "ja-JP")) {
            val peer = peer()
            val callback = PcmCallback()
            client.synthesize("fixture-key", selected, "Hello.", 120, language, 10, SynthesisSession(), callback)
            val start = peer.next()
            assertEquals("start", start.getString("event"))
            assertEquals(expected, start.getJSONObject("request").getString("language"))
            assertArrayEquals(pcm, callback.audio.toByteArray())
            assertTrue(callback.errors.isEmpty())
            assertEquals("ja-JP", selected.parameters.getString("language"))
        }
        assertEquals(3, server.requestCount)
    }

    @Test fun waitsFor500MsAfterTheLastAudioAndReassemblesSplitPcmSamples() {
        val peer = peer {
            it.audio(pcm.copyOfRange(0, 3))
            workers.schedule({ it.audio(pcm.copyOfRange(3, 6)) }, 300, TimeUnit.MILLISECONDS)
        }
        val before = System.nanoTime()
        val callback = speak(synthesizer())
        assertTrue(peer.flushed.await(1, TimeUnit.SECONDS))
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - before) >= 800)
        assertArrayEquals(pcm, callback.audio.toByteArray())
        assertTrue(callback.errors.isEmpty())
    }

    @Test fun noFirstAudioIsATimeoutEvenIfTheServerSendsEmptyAudio() {
        peer { it.audio(byteArrayOf()) }
        val callback = speak(synthesizer(firstMillis = 150))
        assertEquals(listOf(TextToSpeech.ERROR_NETWORK_TIMEOUT), callback.errors)
        assertEquals(0, callback.audio.size())
        assertEquals(1, callback.doneCount)
    }

    @Test fun idleExpiryClosesTheConnectionAndNextSegmentStartsANewSession() {
        val first = peer()
        val client = synthesizer(idleMillis = 100)
        assertTrue(speak(client).errors.isEmpty())
        assertTrue(first.closed.await(2, TimeUnit.SECONDS))
        val second = peer()
        assertTrue(speak(client).errors.isEmpty())
        assertEquals("start", second.next().getString("event"))
        assertEquals(2, server.requestCount)
    }

    @Test fun reusedConnectionIsNotClosedByItsPreviousIdleTimerWhileWaitingForAudio() {
        var flushes = 0
        peer {
            flushes++
            if (flushes == 1) it.audio(pcm)
            else workers.schedule({ it.audio(pcm) }, 300, TimeUnit.MILLISECONDS)
        }
        val client = synthesizer(idleMillis = 100)
        assertTrue(speak(client).errors.isEmpty())
        assertTrue(speak(client).errors.isEmpty())
        assertEquals(1, server.requestCount)
    }

    @Test fun callerVoiceRatePrefixModelAndKeyChangesStartFreshContexts() {
        val client = synthesizer()
        val variants = listOf(
            profile,
            VoiceProfile("second", "Other", profile.referenceId, profile.model, profile.parameters, profile.prefixText),
            VoiceProfile("second", "Other", "another-reference", profile.model, profile.parameters, profile.prefixText),
            VoiceProfile("second", "Other", "another-reference", "drama-3", profile.parameters, "[whisper] "),
        )
        for (voice in variants) { peer(); assertTrue(speak(client, selected = voice).errors.isEmpty()) }
        val last = variants.last()
        peer(); assertTrue(speak(client, selected = last, uid = 11).errors.isEmpty())
        peer(); assertTrue(speak(client, selected = last, uid = 11, rate = 150).errors.isEmpty())
        peer(); assertTrue(speak(client, selected = last, uid = 11, rate = 150, key = "changed-fixture-key").errors.isEmpty())
        assertEquals(7, server.requestCount)
    }

    @Test fun lateAudioWhileIdleDiscardsThatConnectionInsteadOfReusingItsTail() {
        val first = peer()
        val client = synthesizer()
        assertTrue(speak(client).errors.isEmpty())
        first.audio(byteArrayOf(100, 101))
        assertTrue(first.closed.await(2, TimeUnit.SECONDS))
        peer()
        val callback = speak(client)
        assertArrayEquals(pcm, callback.audio.toByteArray())
        assertEquals(2, server.requestCount)
    }

    @Test fun cancellationBeforeFirstAudioUnblocksSynthesisAndNextRequestReconnects() {
        val peer = peer { }
        val client = synthesizer()
        val session = SynthesisSession()
        val result = workers.submit<PcmCallback> { speak(client, session = session) }
        assertTrue(peer.flushed.await(2, TimeUnit.SECONDS))
        session.cancel()
        val callback = result.get(2, TimeUnit.SECONDS)
        assertTrue(callback.errors.isEmpty())
        assertEquals(1, callback.doneCount)
        assertEquals(0, callback.audio.size())
        peer()
        assertTrue(speak(client).errors.isEmpty())
        assertEquals(2, server.requestCount)
    }

    @Test fun stopAndDestructionCloseIdleConnections() {
        val first = peer()
        val client = synthesizer()
        speak(client)
        client.stop()
        assertTrue(first.closed.await(2, TimeUnit.SECONDS))
        val second = peer()
        speak(client)
        client.close()
        assertTrue(second.closed.await(2, TimeUnit.SECONDS))
        assertEquals(2, server.requestCount)
    }

    @Test fun outputFailureStopsTheSocketAndCompletesOnTheSynthesisThread() {
        val peer = peer()
        val callback = speak(synthesizer(), callback = PcmCallback(reject = true))
        assertEquals(listOf(TextToSpeech.ERROR_OUTPUT), callback.errors)
        assertEquals(1, callback.doneCount)
        assertTrue(callback.threads.all { it === Thread.currentThread() })
        assertTrue(peer.closed.await(2, TimeUnit.SECONDS))
    }

    @Test fun errorsTruncatedPcmAndMalformedMessagesNeverBecomeSuccessfulSegments() {
        val client = synthesizer()
        peer { it.event("finish", "error") }
        assertEquals(listOf(TextToSpeech.ERROR_SERVICE), speak(client).errors)
        peer { it.audio(byteArrayOf(1)) }
        assertEquals(listOf(TextToSpeech.ERROR_SYNTHESIS), speak(client).errors)
        peer { it.socket.send("unexpected text frame") }
        assertEquals(listOf(TextToSpeech.ERROR_SYNTHESIS), speak(client).errors)
        assertEquals(3, server.requestCount)
    }

    @Test fun remoteDisconnectIsAnErrorAndNeverAutomaticallyReplaysText() {
        peer { it.socket.close(1000, null) }
        val callback = speak(synthesizer())
        assertEquals(listOf(TextToSpeech.ERROR_NETWORK), callback.errors)
        assertEquals(1, callback.doneCount)
        assertEquals(1, server.requestCount)
    }

    @Test fun successfulPeerFinishDrainsQueuedAudioAndNextRequestCreatesANewContext() {
        val first = peer { it.audio(pcm); it.event("finish") }
        val client = synthesizer()
        val callback = speak(client)
        assertTrue(callback.errors.isEmpty())
        assertArrayEquals(pcm, callback.audio.toByteArray())
        assertEquals(1, callback.doneCount)
        assertTrue(first.closed.await(2, TimeUnit.SECONDS))
        val next = peer()
        assertTrue(speak(client).errors.isEmpty())
        assertEquals("start", next.next().getString("event"))
        assertEquals(2, server.requestCount)
    }

    @Test fun handshakeFailuresAreMappedWithoutFollowingRedirects() {
        val client = synthesizer()
        for ((status, expected) in listOf(401 to TextToSpeech.ERROR_SERVICE, 429 to TextToSpeech.ERROR_NETWORK,
            422 to TextToSpeech.ERROR_INVALID_REQUEST, 504 to TextToSpeech.ERROR_NETWORK_TIMEOUT, 302 to TextToSpeech.ERROR_SERVICE)) {
            server.enqueue(MockResponse().setResponseCode(status).setHeader("Location", server.url("/unexpected")))
            val callback = speak(client)
            assertEquals(listOf(expected), callback.errors)
            assertEquals(1, callback.doneCount)
            assertEquals("/v1/tts/live", server.takeRequest(1, TimeUnit.SECONDS)!!.path)
        }
        assertEquals(5, server.requestCount)
    }

    @Test fun cancelledOrInvalidRequestsNeverOpenAConnection() {
        val client = synthesizer()
        val cancelled = SynthesisSession().also { it.cancel() }
        assertTrue(speak(client, session = cancelled).errors.isEmpty())
        assertEquals(listOf(TextToSpeech.ERROR_INVALID_REQUEST), speak(client, text = " ").errors)
        assertEquals(listOf(TextToSpeech.ERROR_NOT_INSTALLED_YET), speak(client, key = "").errors)
        assertEquals(0, server.requestCount)
    }
}

private class PcmCallback(private val reject: Boolean = false) : SynthesisCallback {
    val audio = ByteArrayOutputStream()
    val lengths = mutableListOf<Int>()
    val errors = mutableListOf<Int>()
    val threads = mutableListOf<Thread>()
    var format: List<Int> = emptyList()
    var doneCount = 0
    override fun getMaxBufferSize() = 3
    override fun hasStarted() = format.isNotEmpty()
    override fun hasFinished() = doneCount != 0
    override fun start(sampleRateInHz: Int, audioFormat: Int, channelCount: Int): Int {
        threads.add(Thread.currentThread()); format = listOf(sampleRateInHz, audioFormat, channelCount)
        return TextToSpeech.SUCCESS
    }
    override fun audioAvailable(buffer: ByteArray, offset: Int, length: Int): Int {
        threads.add(Thread.currentThread()); lengths.add(length); audio.write(buffer, offset, length)
        return if (reject) TextToSpeech.ERROR else TextToSpeech.SUCCESS
    }
    override fun done(): Int { threads.add(Thread.currentThread()); doneCount++; return TextToSpeech.SUCCESS }
    override fun error() = error(TextToSpeech.ERROR_SYNTHESIS)
    override fun error(errorCode: Int) { threads.add(Thread.currentThread()); errors.add(errorCode) }
}
