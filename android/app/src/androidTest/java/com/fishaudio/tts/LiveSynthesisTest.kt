package com.fishaudio.tts

import android.media.AudioFormat
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.DataInputStream
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Opt-in real HTTPS synthesis through Android's TextToSpeech client/service and file callbacks.
 * A one-shot loopback socket (via adb reverse) supplies credentials in memory, never in test source,
 * instrumentation arguments or logs. The native encrypted store is restored after verification.
 * Without fishCredentialPort this test is skipped; run only on a dedicated test emulator.
 */
@RunWith(AndroidJUnit4::class)
class LiveSynthesisTest {
    @Test fun configuredSystemEngineSynthesizesRealPcmToFileWithoutAnActivity() {
        val port = InstrumentationRegistry.getArguments().getString("fishCredentialPort")?.toIntOrNull()
        assumeTrue("Live verification requires a credential socket.", port != null && port in 1024..65535)
        val credentials = Socket().use { socket ->
            socket.connect(InetSocketAddress("127.0.0.1", port!!), 5000)
            socket.soTimeout = 5000
            val input = DataInputStream(socket.getInputStream())
            val length = input.readInt()
            require(length in 1..8192) { "Invalid credential payload size." }
            val bytes = ByteArray(length)
            input.readFully(bytes)
            JSONObject(String(bytes, Charsets.UTF_8))
        }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = ConfigurationStore(context)
        val original = store.read()
        val output = File.createTempFile("fish-live-", ".wav", context.cacheDir)
        var engine: TextToSpeech? = null
        try {
            val profile = VoiceProfile(UUID.randomUUID().toString(), "Live verification", credentials.getString("referenceId"))
            store.saveVoices(listOf(profile))
            store.saveApiKey(credentials.getString("apiKey"))
            credentials.remove("apiKey")
            val ready = CountDownLatch(1)
            val initialized = AtomicInteger(TextToSpeech.ERROR)
            val client = TextToSpeech(context, { code -> initialized.set(code); ready.countDown() }, context.packageName)
            engine = client
            assertTrue("TTS initialization timed out.", ready.await(10, TimeUnit.SECONDS))
            assertEquals(TextToSpeech.SUCCESS, initialized.get())
            val voice = client.voices.first { it.name == VoiceCatalog.name(profile, Locale.US) }
            assertEquals(TextToSpeech.SUCCESS, client.setVoice(voice))
            assertEquals(TextToSpeech.SUCCESS, client.setSpeechRate(1.0f))
            val completed = CountDownLatch(1)
            val done = AtomicBoolean(false)
            val error = AtomicInteger(0)
            val rate = AtomicInteger(0)
            val format = AtomicInteger(0)
            val channels = AtomicInteger(0)
            val audioBytes = AtomicLong(0)
            val nonSilent = AtomicBoolean(false)
            client.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String) {}
                override fun onDone(id: String) { done.set(true); completed.countDown() }
                @Suppress("OVERRIDE_DEPRECATION")
                override fun onError(id: String) { onError(id, TextToSpeech.ERROR_SYNTHESIS) }
                @Suppress("OVERRIDE_DEPRECATION")
                override fun onError(id: String, code: Int) { error.set(code); completed.countDown() }
                override fun onStop(id: String, interrupted: Boolean) { error.set(TextToSpeech.STOPPED); completed.countDown() }
                override fun onBeginSynthesis(id: String, sampleRateInHz: Int, audioFormat: Int, channelCount: Int) {
                    rate.set(sampleRateInHz); format.set(audioFormat); channels.set(channelCount)
                }
                override fun onAudioAvailable(id: String, audio: ByteArray) {
                    audioBytes.addAndGet(audio.size.toLong())
                    if (audio.any { it != 0.toByte() }) nonSilent.set(true)
                }
            })
            assertEquals(TextToSpeech.SUCCESS, client.synthesizeToFile("Hello.", Bundle(), output, "live-file"))
            assertTrue("Live synthesis timed out.", completed.await(125, TimeUnit.SECONDS))
            assertEquals("Live system synthesis error code.", 0, error.get())
            assertTrue("No completion callback.", done.get())
            assertEquals(24000, rate.get())
            assertEquals(AudioFormat.ENCODING_PCM_16BIT, format.get())
            assertEquals(1, channels.get())
            assertTrue("No PCM callbacks.", audioBytes.get() > 0)
            assertTrue("Only silent PCM was returned.", nonSilent.get())
            assertTrue("No synthesized WAV file.", output.length() > 44)
            output.inputStream().use { input ->
                val header = ByteArray(4)
                DataInputStream(input).readFully(header)
                assertEquals("RIFF", String(header, Charsets.US_ASCII))
            }
        } finally {
            credentials.remove("apiKey")
            engine?.shutdown()
            output.delete()
            store.clear()
            store.saveVoices(original.voices)
            if (original.defaultVoiceId.isNotEmpty()) store.saveDefaultVoice(original.defaultVoiceId)
            if (original.apiKey.isNotBlank()) store.saveApiKey(original.apiKey)
        }
    }
}
