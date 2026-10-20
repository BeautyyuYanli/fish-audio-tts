package com.fishaudio.tts

import android.media.AudioFormat
import android.speech.tts.SynthesisCallback
import android.speech.tts.TextToSpeech
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import okio.Buffer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** A local HTTP peer and actual Android Opus codec; no Fish credentials or upstream service. */
@RunWith(AndroidJUnit4::class)
class FishSynthesizerTest {
    private lateinit var server: MockWebServer
    private lateinit var certificates: HandshakeCertificates
    private val profile = VoiceProfile("profile-one", "First", "ca3007f96ae7499ab87d27ea3599956a", "s2.1-pro-free",
        JSONObject().put("temperature", 0.4).put("normalize", false).put("language", "ja-JP")
            .put("prosody", JSONObject().put("speed", 1.25).put("volume", -3).put("normalize_loudness", false)))
    private val config = EngineConfiguration("test-key-not-a-credential", listOf(profile))

    @Before fun setUp() {
        val certificate = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
        val serverCertificates = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        certificates = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        server = MockWebServer()
        server.protocols = listOf(Protocol.HTTP_1_1)
        server.useHttps(serverCertificates.sslSocketFactory(), false)
        server.start()
    }
    @After fun tearDown() { server.shutdown() }

    @Test fun sendsDocumentedParametersAndExtractsOnlyPcmSynchronously() {
        enqueue(opus())
        val callback = RecordingCallback()
        synthesizer().synthesize(config.apiKey, profile, "A real API contract fixture.", 120, "en-US", SynthesisSession(), callback)
        val request = server.takeRequest(1, TimeUnit.SECONDS)!!
        assertEquals("POST", request.method)
        assertEquals("/v1/tts", request.path)
        assertEquals("Bearer test-key-not-a-credential", request.getHeader("Authorization"))
        assertEquals("s2.1-pro-free", request.getHeader("model"))
        val body = JSONObject(request.body.readUtf8())
        assertEquals(profile.referenceId, body.getString("reference_id"))
        assertEquals("opus", body.getString("format"))
        assertEquals(48000, body.getInt("sample_rate"))
        assertEquals(32000, body.getInt("opus_bitrate"))
        assertEquals("en-US", body.getString("language"))
        assertEquals(1.2, body.getJSONObject("prosody").getDouble("speed"), 0.0001)
        assertEquals(-3, body.getJSONObject("prosody").getInt("volume"))
        assertFalse(body.getJSONObject("prosody").getBoolean("normalize_loudness"))
        assertFalse(body.getBoolean("normalize"))
        assertEquals(0.4, body.getDouble("temperature"), 0.0)
        assertEquals(1.25, profile.parameters.getJSONObject("prosody").getDouble("speed"), 0.0)
        assertEquals("ja-JP", profile.parameters.getString("language"))
        assertEquals(listOf(48000, AudioFormat.ENCODING_PCM_16BIT, 1), callback.format)
        // Exact 2.037-second source duration after pre-skip and final granule trimming.
        assertEquals(195552, callback.audio.size())
        assertTrue(callback.audio.toByteArray().any { it != 0.toByte() })
        assertTrue(callback.lengths.all { it <= callback.maxBufferSize && it % 2 == 0 })
        assertTrue(callback.threads.all { it == Thread.currentThread() })
        assertTrue("Synthesis errors: ${callback.errors}", callback.errors.isEmpty())
        assertEquals("done", callback.events.last())
        assertEquals(1, callback.doneCount)
    }

    @Test fun drama3ProfileIsAcceptedAndSentAsTheModelHeader() {
        val drama = VoiceProfile.fromJson(JSONObject().put("id", "drama-profile").put("label", "Drama 3")
            .put("referenceId", profile.referenceId).put("model", "drama-3").put("parameters", JSONObject()))
        enqueue(opus())
        val callback = RecordingCallback()
        synthesizer().synthesize(config.apiKey, drama, "Drama 3.", 100, "en-US", SynthesisSession(), callback)
        val request = server.takeRequest(1, TimeUnit.SECONDS)!!
        assertEquals("drama-3", request.getHeader("model"))
        assertEquals(drama.referenceId, JSONObject(request.body.readUtf8()).getString("reference_id"))
        assertTrue("Synthesis errors: ${callback.errors}", callback.errors.isEmpty())
        assertEquals(1, callback.doneCount)
    }

    @Test fun prefixesArePrependedOnceWithoutTrimmingOrTruncatingSystemText() {
        val text = " " + "x".repeat(3998) + "\n"
        for (prefix in listOf(null, "[calm] ", " \n[whisper]\n  ")) {
            val json = profile.toJson()
            if (prefix == null) json.remove("prefixText") else json.put("prefixText", prefix)
            val restored = VoiceProfile.fromJson(json)
            enqueue(opus())
            val callback = RecordingCallback()
            synthesizer().synthesize(config.apiKey, restored, text, 100, "en-US", SynthesisSession(), callback)
            val request = server.takeRequest(1, TimeUnit.SECONDS)!!
            val body = JSONObject(request.body.readUtf8())
            assertEquals(prefix.orEmpty() + text, body.getString("text"))
            assertFalse(body.has("prefixText"))
            assertTrue(callback.errors.isEmpty())
            assertEquals(1, callback.doneCount)
        }
    }

    @Test fun forwardsSystemRateWithoutClampingOrMultiplyingTableValues() {
        // The fixture checks forwarding, not upstream acceptance of rates outside Fish's range.
        for ((rate, expected) in listOf(1 to 0.01, 500 to 5.0)) {
            enqueue(opus())
            synthesizer().synthesize(config.apiKey, profile, "Speed.", rate, "en-US", SynthesisSession(), RecordingCallback())
            val body = JSONObject(server.takeRequest().body.readUtf8())
            assertEquals(expected, body.getJSONObject("prosody").getDouble("speed"), 0.0)
        }
    }

    @Test fun omitsUnspecifiedParametersAndModelSoUpstreamDefaultsApply() {
        enqueue(opus())
        val empty = VoiceProfile("empty-profile", "", profile.referenceId)
        val callback = RecordingCallback()
        synthesizer().synthesize(config.apiKey, empty, "Defaults.", null, null, SynthesisSession(), callback)
        val request = server.takeRequest()
        val body = JSONObject(request.body.readUtf8())
        assertNull(request.getHeader("model"))
        assertEquals(setOf("text", "reference_id", "format", "sample_rate", "opus_bitrate"), body.keys().asSequence().toSet())
        assertTrue("Synthesis errors: ${callback.errors}", callback.errors.isEmpty())
    }

    @Test fun retainsTableFallbacksWhenSystemValuesAreAbsent() {
        enqueue(opus())
        synthesizer().synthesize(config.apiKey, profile, "Fallbacks.", null, null, SynthesisSession(), RecordingCallback())
        val body = JSONObject(server.takeRequest().body.readUtf8())
        assertEquals("ja-JP", body.getString("language"))
        assertEquals(1.25, body.getJSONObject("prosody").getDouble("speed"), 0.0)
        assertEquals(-3, body.getJSONObject("prosody").getInt("volume"))
        assertFalse(body.has("top_p"))
    }

    @Test fun selectedProfilesRouteTheirOwnReferenceAndGenerationParameters() {
        val other = VoiceProfile("profile-two", "Second", "9a9cf47702da476aa4629e2506d4a857",
            parameters = JSONObject().put("temperature", 0.9), prefixText = "[bright] ")
        val multiple = EngineConfiguration(config.apiKey, listOf(profile, other))
        val selected = VoiceCatalog.selection(VoiceCatalog.name(other, java.util.Locale.US), multiple)!!
        enqueue(opus())
        synthesizer().synthesize(multiple.apiKey, selected.first, "Second voice.", 100, selected.second.toLanguageTag(), SynthesisSession(), RecordingCallback())
        val request = server.takeRequest()
        val body = JSONObject(request.body.readUtf8())
        assertEquals(other.referenceId, body.getString("reference_id"))
        assertEquals("[bright] Second voice.", body.getString("text"))
        assertEquals(0.9, body.getDouble("temperature"), 0.0)
        assertEquals(1.0, body.getJSONObject("prosody").getDouble("speed"), 0.0)
        assertNull(request.getHeader("model"))
        assertFalse(body.has("normalize"))
        assertNull(VoiceCatalog.selection(VoiceCatalog.name(other, java.util.Locale.US), config))
    }

    @Test fun acceptsOpusOverOddHttpChunkBoundariesAndPreservesConfiguredBitrate() {
        val custom = VoiceProfile("custom", "", profile.referenceId, parameters = JSONObject().put("opus_bitrate", 24000))
        server.enqueue(MockResponse().setChunkedBody(Buffer().write(opus()), 3))
        val callback = RecordingCallback()
        synthesizer().synthesize(config.apiKey, custom, "Streaming Opus.", 100, "en-US", SynthesisSession(), callback)
        assertEquals(24000, JSONObject(server.takeRequest().body.readUtf8()).getInt("opus_bitrate"))
        assertTrue(callback.errors.isEmpty())
        assertEquals(listOf(48000, AudioFormat.ENCODING_PCM_16BIT, 1), callback.format)
        assertEquals(195552, callback.audio.size())
        assertEquals(1, callback.doneCount)
        assertEquals("done", callback.events.last())
    }

    @Test fun consecutiveUtterancesTrimBatchedPagesAndPreSkipSpanningPackets() {
        val batched = InstrumentationRegistry.getInstrumentation().context.assets.open("tone-1000000.opus").use { it.readBytes() }
        val extendedSkip = batched.copyOf().also {
            // The original header is 27 bytes plus one lacing byte; adjust pre-skip and re-sign its page.
            it[38] = (2000 and 255).toByte(); it[39] = (2000 ushr 8).toByte()
            val headerPage = it.copyOf(47).also { page -> page.fill(0, 22, 26) }
            java.nio.ByteBuffer.wrap(it).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                .putInt(22, OggOpusReader.checksum(headerPage))
        }
        val synthesizer = synthesizer()
        for ((audio, size) in listOf(batched to 195552, extendedSkip to 192176, opus() to 195552)) {
            enqueue(audio)
            val callback = RecordingCallback()
            synthesizer.synthesize(config.apiKey, profile, "Consecutive audio.", 100, "en-US", SynthesisSession(), callback)
            assertTrue("Synthesis errors: ${callback.errors}", callback.errors.isEmpty())
            assertEquals(size, callback.audio.size())
            assertEquals(1, callback.doneCount)
            assertEquals("done", callback.events.last())
        }
    }

    @Test fun rejectsInvalidTruncatedAndEmptyAudioAndAlwaysFinishes() {
        val fixtures = listOf(
            "Not an Ogg response".toByteArray(),
            byteArrayOf(),
            opus().copyOf(10),
            opus().dropLast(1).toByteArray(),
            opus().copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() },
        )
        for (fixture in fixtures) {
            enqueue(fixture)
            val callback = RecordingCallback()
            synthesizer().synthesize(config.apiKey, profile, "Malformed audio.", 100, "en-US", SynthesisSession(), callback)
            assertEquals(listOf(TextToSpeech.ERROR_SYNTHESIS), callback.errors)
            assertEquals(1, callback.doneCount)
            assertEquals("done", callback.events.last())
        }
    }

    @Test fun mapsHttpErrorsWithoutReturningOrLoggingResponseBodies() {
        for ((http, error) in listOf(401 to TextToSpeech.ERROR_SERVICE, 402 to TextToSpeech.ERROR_SERVICE,
            422 to TextToSpeech.ERROR_INVALID_REQUEST, 429 to TextToSpeech.ERROR_NETWORK,
            503 to TextToSpeech.ERROR_NETWORK)) {
            server.enqueue(MockResponse().setResponseCode(http).setBody("Sensitive untrusted upstream detail"))
            val callback = RecordingCallback()
            synthesizer().synthesize(config.apiKey, profile, "Errors.", 100, "en-US", SynthesisSession(), callback)
            assertEquals(listOf(error), callback.errors)
            assertEquals(listOf("error", "done"), callback.events)
        }
    }

    @Test fun missingConfigurationAndBlankTextDoNotSendRequests() {
        val missing = RecordingCallback()
        synthesizer().synthesize("", profile, "Hello.", 100, "en-US", SynthesisSession(), missing)
        assertEquals(listOf(TextToSpeech.ERROR_NOT_INSTALLED_YET), missing.errors)
        val blank = RecordingCallback()
        synthesizer().synthesize(config.apiKey, profile, " ", 100, "en-US", SynthesisSession(), blank)
        assertEquals(listOf(TextToSpeech.ERROR_INVALID_REQUEST), blank.errors)
        assertEquals(0, server.requestCount)
        assertEquals(1, missing.doneCount)
        assertEquals(1, blank.doneCount)
    }

    @Test fun cancellationUnblocksPendingNetworkReadWithoutCallbackOnStopThread() {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val session = SynthesisSession()
        val callback = RecordingCallback()
        val executor = Executors.newSingleThreadExecutor()
        try {
            val future = executor.submit {
                synthesizer().synthesize(config.apiKey, profile, "Cancel.", 100, "en-US", session, callback)
            }
            assertNotNull(server.takeRequest(2, TimeUnit.SECONDS))
            session.cancel()
            future.get(2, TimeUnit.SECONDS)
            assertTrue(callback.errors.isEmpty())
            assertEquals(listOf("done"), callback.events)
            assertTrue(callback.threads.none { it == Thread.currentThread() })
            assertTrue(future.isDone)
        } finally { executor.shutdownNow() }
    }

    @Test fun cancellationBeforeCallPublicationPreventsNetworkAccess() {
        val session = SynthesisSession().also { it.cancel() }
        val callback = RecordingCallback()
        synthesizer().synthesize(config.apiKey, profile, "Cancelled.", 100, "en-US", session, callback)
        assertEquals(listOf("done"), callback.events)
        assertEquals(0, server.requestCount)
    }

    @Test fun cancellationUnblocksAStalledPcmStreamAfterAudioHasStarted() {
        server.enqueue(MockResponse().setBody(Buffer().write(opus()))
            // MockWebServer also throttles request-body reads; keep the first window larger than the JSON request.
            .throttleBody(8192, 5, TimeUnit.SECONDS))
        val session = SynthesisSession()
        val callback = RecordingCallback()
        val executor = Executors.newSingleThreadExecutor()
        try {
            val future = executor.submit {
                synthesizer().synthesize(config.apiKey, profile, "Stop streaming.", 100, "en-US", session, callback)
            }
            assertTrue(callback.firstAudio.await(2, TimeUnit.SECONDS))
            session.cancel()
            future.get(2, TimeUnit.SECONDS)
            assertTrue(callback.errors.isEmpty())
            assertTrue(callback.audio.size() in 1 until 195552)
            assertEquals(1, callback.doneCount)
            assertEquals("done", callback.events.last())
        } finally { executor.shutdownNow() }
    }

    @Test fun stalledResponseTimesOutAndFinishesOnTheSynthesisThread() {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val callback = RecordingCallback()
        val client = clientBuilder().callTimeout(300, TimeUnit.MILLISECONDS).build()
        FishSynthesizer(client, server.url("/v1/tts")).synthesize(config.apiKey, profile, "Timeout.", 100, "en-US", SynthesisSession(), callback)
        assertEquals(listOf(TextToSpeech.ERROR_NETWORK_TIMEOUT), callback.errors)
        assertEquals("done", callback.events.last())
        assertTrue(callback.threads.all { it == Thread.currentThread() })
    }

    @Test fun outputRejectionStopsDeliveringAudioAndStillFinishes() {
        enqueue(opus())
        val callback = RecordingCallback(rejectAudio = true)
        synthesizer().synthesize(config.apiKey, profile, "Output error.", 100, "en-US", SynthesisSession(), callback)
        assertEquals(listOf(TextToSpeech.ERROR_OUTPUT), callback.errors)
        assertEquals(1, callback.lengths.size)
        assertEquals(1, callback.doneCount)
    }

    @Test fun unsupportedLanguageIsNotAdvertisedAndIsoCodesMatch() {
        assertNull(VoiceCatalog.localeFor("not-a-language"))
        assertEquals(VoiceCatalog.localeFor("en"), VoiceCatalog.localeFor("eng"))
        assertEquals(TextToSpeech.LANG_NOT_SUPPORTED, VoiceCatalog.availability("xyz", "", true))
        assertEquals(TextToSpeech.LANG_MISSING_DATA, VoiceCatalog.availability("eng", "USA", false))
        assertEquals(TextToSpeech.LANG_COUNTRY_AVAILABLE, VoiceCatalog.availability("eng", "USA", true))
        assertEquals(TextToSpeech.LANG_AVAILABLE, VoiceCatalog.availability("eng", "GBR", true))
        assertNull(VoiceCatalog.selection("fish:unknown-profile:unknown-reference:en-US", config))
        assertEquals("en-US", VoiceCatalog.selection("fish:${profile.id}:${profile.referenceId}:en-US", config)?.second?.toLanguageTag())
    }

    private fun clientBuilder() = OkHttpClient.Builder().sslSocketFactory(certificates.sslSocketFactory(), certificates.trustManager)
    private fun synthesizer() = FishSynthesizer(clientBuilder().callTimeout(5, TimeUnit.SECONDS).build(), server.url("/v1/tts"))
    private fun enqueue(bytes: ByteArray) { server.enqueue(MockResponse().setBody(Buffer().write(bytes))) }

    private fun opus(): ByteArray = InstrumentationRegistry.getInstrumentation().context.assets.open("tone-20000.opus").use { it.readBytes() }
}

private class RecordingCallback(private val rejectAudio: Boolean = false) : SynthesisCallback {
    val events = mutableListOf<String>()
    val errors = mutableListOf<Int>()
    val threads = mutableListOf<Thread>()
    val lengths = mutableListOf<Int>()
    val audio = ByteArrayOutputStream()
    val firstAudio = java.util.concurrent.CountDownLatch(1)
    var format: List<Int> = emptyList()
    var doneCount = 0
    override fun getMaxBufferSize() = 511
    override fun hasStarted() = format.isNotEmpty()
    override fun hasFinished() = doneCount != 0
    override fun start(rate: Int, encoding: Int, channels: Int): Int {
        record("start"); format = listOf(rate, encoding, channels); return TextToSpeech.SUCCESS
    }
    override fun audioAvailable(buffer: ByteArray, offset: Int, length: Int): Int {
        record("audio"); lengths.add(length); audio.write(buffer, offset, length); firstAudio.countDown()
        return if (rejectAudio) TextToSpeech.ERROR else TextToSpeech.SUCCESS
    }
    override fun done(): Int { record("done"); doneCount++; return TextToSpeech.SUCCESS }
    override fun error() { error(TextToSpeech.ERROR_SYNTHESIS) }
    override fun error(code: Int) { record("error"); errors.add(code) }
    private fun record(event: String) { events.add(event); threads.add(Thread.currentThread()) }
}
