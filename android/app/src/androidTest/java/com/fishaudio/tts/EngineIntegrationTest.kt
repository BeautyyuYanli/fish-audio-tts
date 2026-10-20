package com.fishaudio.tts

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.os.Bundle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Real Android components and Keystore; no valid Fish credentials and no claim of live speech success. */
@RunWith(AndroidJUnit4::class)
class EngineIntegrationTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val store get() = ConfigurationStore(context)
    private lateinit var original: EngineConfiguration
    private val reference = "ca3007f96ae7499ab87d27ea3599956a"
    private val secondReference = "9a9cf47702da476aa4629e2506d4a857"
    private val testKey = "test-not-a-real-fish-api-key"
    private fun profile(id: String = "first", referenceId: String = reference) =
        VoiceProfile(id, id, referenceId, parameters = JSONObject().put("temperature", 0.4))

    @Before fun setUp() { original = store.read(); store.clear() }
    @After fun tearDown() {
        store.clear()
        store.saveVoices(original.voices)
        if (original.defaultVoiceId.isNotEmpty()) store.saveDefaultVoice(original.defaultVoiceId)
        if (original.apiKey.isNotBlank()) store.saveApiKey(original.apiKey)
    }

    @Test fun engineAndDataHandlersAreDiscoverable() {
        val manager = context.packageManager
        val engine = manager.resolveService(Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE).setPackage(context.packageName), PackageManager.GET_META_DATA)
        assertNotNull(engine)
        assertTrue(engine!!.serviceInfo.exported)
        assertEquals("com.fishaudio.tts.FishTtsService", engine.serviceInfo.name)
        assertTrue(engine.serviceInfo.metaData.getInt("android.speech.tts") != 0)
        for (action in listOf(TextToSpeech.Engine.ACTION_CHECK_TTS_DATA, TextToSpeech.Engine.ACTION_GET_SAMPLE_TEXT, TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA)) {
            val activity = manager.resolveActivity(Intent(action).setPackage(context.packageName), PackageManager.MATCH_DEFAULT_ONLY)
            assertNotNull(action, activity)
            assertTrue(activity!!.activityInfo.exported)
        }
    }

    @Test fun keyAndVoiceSavesAreIndependentAndDeletingKeyPreservesProfiles() {
        store.saveVoices(listOf(profile()))
        assertFalse(store.read().configured)
        store.saveApiKey(testKey)
        val file = File(context.noBackupFilesDir, "fish-configuration.bin")
        val first = file.readBytes()
        assertFalse(String(first, Charsets.ISO_8859_1).contains(testKey))
        store.saveVoices(listOf(profile(), profile("second", secondReference)))
        assertEquals(testKey, store.read().apiKey)
        store.saveApiKey("")
        assertEquals(2, store.read().voices.size)
        assertEquals(testKey, store.read().apiKey)
        store.deleteApiKey()
        val saved = ConfigurationStore(context).read()
        assertEquals("", saved.apiKey)
        assertEquals(listOf(reference, secondReference), saved.voices.map { it.referenceId })
        assertEquals(0.4, saved.voices.first().parameters.getDouble("temperature"), 0.0)
        assertFalse(first.contentEquals(file.readBytes()))
        store.clear()
        assertFalse(file.exists())
    }

    @Test fun savedDefaultSurvivesIndependentSavesAndFallsBackWhenDeleted() {
        val first = profile()
        // Identical references must still allow independent default selection by profile identity.
        val second = profile("second")
        store.saveVoices(listOf(first, second))
        assertEquals(first.id, store.read().defaultVoiceId)
        store.saveDefaultVoice(second.id)
        store.saveApiKey(testKey)
        assertEquals(second.id, ConfigurationStore(context).read().defaultVoiceId)
        store.saveVoices(listOf(second, first))
        store.saveVoices(listOf(first, second))
        assertEquals(second.id, store.read().defaultVoiceId)
        store.deleteApiKey()
        assertEquals(second.id, store.read().defaultVoiceId)
        store.saveApiKey(testKey)
        store.saveApiKey("")
        assertEquals(second.id, store.read().defaultVoiceId)
        try {
            store.saveDefaultVoice("missing-profile")
            fail("An unknown default voice must not be saved.")
        } catch (_: IllegalArgumentException) {}
        assertEquals(second.id, store.read().defaultVoiceId)
        store.saveVoices(listOf(first))
        assertEquals(first.id, store.read().defaultVoiceId)
        store.saveVoices(emptyList())
        assertEquals("", store.read().defaultVoiceId)
        assertNull(store.read().defaultVoice)
    }

    @Test fun systemClientUsesSavedDefaultUnlessItSelectsAnotherVoice() {
        val first = profile()
        val second = profile("second", secondReference)
        store.saveVoices(listOf(first, second))
        store.saveDefaultVoice(second.id)
        store.saveApiKey(testKey)
        val engine = connect()
        try {
            assertEquals(second.id, VoiceCatalog.selection(engine.voice.name, store.read())!!.first.id)
            assertTrue(engine.setLanguage(Locale.JAPAN) >= TextToSpeech.LANG_AVAILABLE)
            assertEquals(VoiceCatalog.name(second, Locale.JAPAN), engine.voice.name)
            val explicit = engine.voices.first { it.name == VoiceCatalog.name(first, Locale.US) }
            assertEquals(TextToSpeech.SUCCESS, engine.setVoice(explicit))
            assertEquals(explicit.name, engine.voice.name)
        } finally { engine.shutdown() }
    }

    @Test fun systemClientDiscoversAndSelectsMultipleProfilesWithoutAnActivity() {
        val first = profile()
        val second = profile("second", secondReference)
        store.saveVoices(listOf(first, second))
        store.saveApiKey(testKey)
        val engine = connect()
        try {
            assertTrue(engine.engines.any { it.name == context.packageName })
            assertEquals(2 * VoiceCatalog.locales.size, engine.voices.size)
            for (profile in listOf(first, second)) {
                val voice = engine.voices.first { it.name == VoiceCatalog.name(profile, Locale.JAPAN) }
                assertTrue(voice.isNetworkConnectionRequired)
                assertEquals(TextToSpeech.SUCCESS, engine.setVoice(voice))
                assertEquals(voice.name, engine.voice.name)
            }
            assertTrue(engine.isLanguageAvailable(Locale.US) >= TextToSpeech.LANG_AVAILABLE)
            assertEquals(TextToSpeech.LANG_NOT_SUPPORTED, engine.isLanguageAvailable(Locale.forLanguageTag("xx-XX")))
        } finally { engine.shutdown() }
    }

    @Test fun unconfiguredSystemSynthesisReportsAnErrorAndCompletes() {
        val engine = connect()
        val completed = CountDownLatch(1)
        val error = AtomicInteger(0)
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String) {}
            override fun onDone(id: String) { completed.countDown() }
            @Suppress("OVERRIDE_DEPRECATION")
            override fun onError(id: String) { error.set(TextToSpeech.ERROR_SYNTHESIS); completed.countDown() }
            @Suppress("OVERRIDE_DEPRECATION")
            override fun onError(id: String, code: Int) { error.set(code); completed.countDown() }
        })
        try {
            assertEquals(TextToSpeech.SUCCESS, engine.speak("No credentials should produce an error.", TextToSpeech.QUEUE_FLUSH, Bundle(), "missing-configuration"))
            assertTrue("System client did not receive a terminal callback", completed.await(5, TimeUnit.SECONDS))
            assertEquals(TextToSpeech.ERROR_NOT_INSTALLED_YET, error.get())
        } finally { engine.shutdown() }
    }

    @Test fun vueFormsSaveIndependentlyAndVoiceListSupportsAddEditAndRemove() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            awaitJs(scenario, "document.body.innerText.includes('Configuration loaded.')")
            assertEquals("2", evaluate(scenario, "document.querySelectorAll('form').length"))
            assertEquals("0", evaluate(scenario, "document.querySelectorAll('style,link[rel=stylesheet],svg,img').length"))
            evaluate(scenario, "document.getElementById('add-voice').click()")
            setInput(scenario, "input[data-field=referenceId]", reference)
            setInput(scenario, "textarea[data-field=parameters]", JSONObject().put("temperature", 0.4)
                .put("prosody", JSONObject().put("speed", 1.25).put("volume", -3)).toString())
            setInput(scenario, "#api-key", testKey)
            evaluate(scenario, "document.getElementById('save-api-key').click()")
            awaitJs(scenario, "document.body.innerText.includes('API key saved in protected native storage.')")
            assertTrue(store.read().voices.isEmpty())
            assertEquals(JSONObject.quote(reference), evaluate(scenario, "document.querySelector('input[data-field=referenceId]').value"))
            assertEquals("\"\"", evaluate(scenario, "document.getElementById('api-key').value"))

            evaluate(scenario, "document.getElementById('save-voices').click()")
            awaitJs(scenario, "!document.body.innerText.includes('Unsaved voice changes.')")
            assertEquals(1, store.read().voices.size)
            val firstId = store.read().voices.first().id
            assertTrue(firstId.isNotBlank())
            assertEquals("", store.read().voices.first().model)
            evaluate(scenario, "document.getElementById('add-voice').click()")
            setInput(scenario, "input[data-field=referenceId]", secondReference, 1)
            evaluate(scenario, "document.getElementById('save-voices').click()")
            awaitJs(scenario, "!document.body.innerText.includes('Unsaved voice changes.')")
            assertEquals(2, store.read().voices.size)

            setInput(scenario, "textarea[data-field=parameters]", JSONObject().put("temperature", 0.8).toString())
            evaluate(scenario, "document.querySelector('form fieldset fieldset:last-of-type button').click()")
            evaluate(scenario, "document.getElementById('save-voices').click()")
            awaitJs(scenario, "!document.body.innerText.includes('Unsaved voice changes.')")
            val config = store.read()
            assertEquals(1, config.voices.size)
            assertEquals(firstId, config.voices.first().id)
            assertEquals(0.8, config.voices.first().parameters.getDouble("temperature"), 0.0)
            assertEquals(testKey, config.apiKey)
            scenario.recreate()
            awaitJs(scenario, "document.body.innerText.includes('Configuration loaded.')")
            assertEquals("\"\"", evaluate(scenario, "document.getElementById('api-key').value"))
            assertEquals("0", evaluate(scenario, "localStorage.length"))
            assertEquals("0", evaluate(scenario, "sessionStorage.length"))
            assertEquals(JSONObject.quote(reference), evaluate(scenario, "document.querySelector('input[data-field=referenceId]').value"))
        }
    }

    @Test fun vueSavesPrefixModelAndDefaultWithoutDiscardingUnsavedVoiceEdits() {
        val first = profile()
        val second = profile("second", secondReference)
        store.saveVoices(listOf(first, second))
        store.saveApiKey(testKey)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            awaitJs(scenario, "document.body.innerText.includes('Configuration loaded.')")
            assertEquals(JSONObject.quote("1"), evaluate(scenario, "document.getElementById('preview-speed').value"))
            assertEquals("true", evaluate(scenario, "document.getElementById('preview-pitch').disabled"))
            val prefix = "[calm]\n "
            setInput(scenario, "textarea[data-field=prefixText]", prefix, 1)
            selectOption(scenario, "select[id^=model-]", "drama-3", 1)
            selectOption(scenario, "#default-voice", second.id)
            awaitJs(scenario, "document.body.innerText.includes('Default system voice saved.')")
            assertEquals(second.id, store.read().defaultVoiceId)
            assertEquals("", store.read().voices[1].model)
            assertEquals("", store.read().voices[1].prefixText)
            assertEquals(JSONObject.quote(prefix), evaluate(scenario, "document.querySelectorAll('textarea[data-field=prefixText]')[1].value"))
            assertEquals(JSONObject.quote("drama-3"), evaluate(scenario, "document.querySelectorAll('select[id^=model-]')[1].value"))
            assertEquals("true", evaluate(scenario, "document.body.innerText.includes('Unsaved voice changes.')"))
            evaluate(scenario, "document.getElementById('save-voices').click()")
            awaitJs(scenario, "!document.body.innerText.includes('Unsaved voice changes.')")
            assertEquals(second.id, store.read().defaultVoiceId)
            assertEquals("drama-3", store.read().voices[1].model)
            assertEquals(prefix, store.read().voices[1].prefixText)
            scenario.recreate()
            awaitJs(scenario, "document.body.innerText.includes('Configuration loaded.')")
            assertEquals(JSONObject.quote(second.id), evaluate(scenario, "document.getElementById('default-voice').value"))
            assertEquals(JSONObject.quote("drama-3"), evaluate(scenario, "document.querySelectorAll('select[id^=model-]')[1].value"))
            assertEquals(JSONObject.quote(prefix), evaluate(scenario, "document.querySelectorAll('textarea[data-field=prefixText]')[1].value"))
            setInput(scenario, "#preview-speed", "0.1")
            evaluate(scenario, "document.getElementById('start-preview').click()")
            awaitJs(scenario, "document.body.innerText.includes('Choose a preview speed between 0.5 and 2.0.')")
            setInput(scenario, "#preview-speed", "1.5")
            assertEquals(JSONObject.quote("1.5"), evaluate(scenario, "document.getElementById('preview-speed').value"))
            assertEquals("true", evaluate(scenario, "document.getElementById('preview-pitch').disabled"))
        }
    }

    private fun selectOption(scenario: ActivityScenario<MainActivity>, selector: String, value: String, index: Int = 0) {
        evaluate(scenario, """
            (() => {
              const select = document.querySelectorAll(${JSONObject.quote(selector)})[$index];
              select.value = ${JSONObject.quote(value)};
              select.dispatchEvent(new Event('change', {bubbles: true}));
              return true;
            })()
        """.trimIndent())
    }

    private fun setInput(scenario: ActivityScenario<MainActivity>, selector: String, value: String, index: Int = 0) {
        evaluate(scenario, """
            (() => {
              const input = document.querySelectorAll(${JSONObject.quote(selector)})[$index];
              input.value = ${JSONObject.quote(value)};
              input.dispatchEvent(new Event('input', {bubbles: true}));
              return true;
            })()
        """.trimIndent())
    }

    private fun connect(): TextToSpeech {
        val ready = CountDownLatch(1)
        val result = AtomicInteger(TextToSpeech.ERROR)
        val engine = TextToSpeech(context, { code -> result.set(code); ready.countDown() }, context.packageName)
        assertTrue("TTS initialization timed out", ready.await(5, TimeUnit.SECONDS))
        assertEquals(TextToSpeech.SUCCESS, result.get())
        return engine
    }

    private fun evaluate(scenario: ActivityScenario<MainActivity>, script: String): String {
        val result = AtomicReference<String>()
        val ready = CountDownLatch(1)
        scenario.onActivity { activity -> activity.bridge.webView.evaluateJavascript(script) { result.set(it); ready.countDown() } }
        assertTrue("WebView evaluation timed out", ready.await(5, TimeUnit.SECONDS))
        return result.get()
    }

    private fun awaitJs(scenario: ActivityScenario<MainActivity>, script: String) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (System.nanoTime() < deadline) {
            if (evaluate(scenario, script) == "true") return
            Thread.sleep(100)
        }
        fail("Panel did not reach the expected state.")
    }
}
