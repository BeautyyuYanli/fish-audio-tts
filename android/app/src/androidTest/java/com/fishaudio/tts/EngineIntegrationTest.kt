package com.fishaudio.tts

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.os.Bundle
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
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
    private fun profile(id: String = "first", referenceId: String = reference, label: String = id, model: String = "") =
        VoiceProfile(id, label, referenceId, model, parameters = JSONObject().put("temperature", 0.4))

    @Before fun setUp() { original = store.read(); store.clear() }
    @After fun tearDown() {
        store.clear()
        store.saveVoices(original.voices)
        store.saveLanguage(original.language)
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

    @Test fun globalLanguagePersistsAcrossIndependentMutations() {
        assertEquals("unset", store.read().language)
        store.saveLanguage("unset")
        assertEquals("unset", ConfigurationStore(context).read().language)
        val first = profile()
        val second = profile("second")
        store.saveApiKey(testKey)
        store.saveVoices(listOf(first, second))
        store.saveDefaultVoice(second.id)
        store.saveVoice(profile(label = "Updated"))
        store.saveApiKey("")
        val saved = ConfigurationStore(context).read()
        assertEquals("unset", saved.language)
        assertEquals(testKey, saved.apiKey)
        assertEquals(second.id, saved.defaultVoiceId)
        store.saveLanguage("system")
        val reset = store.read()
        assertEquals("system", reset.language)
        assertEquals(saved.apiKey, reset.apiKey)
        assertEquals(saved.defaultVoiceId, reset.defaultVoiceId)
        assertEquals(saved.voices.map { it.toJson().toString() }, reset.voices.map { it.toJson().toString() })
        store.saveLanguage("unset")
        store.removeVoice(first.id)
        store.deleteApiKey()
        assertEquals("unset", ConfigurationStore(context).read().language)
        assertEquals(second.id, store.read().defaultVoiceId)
        assertThrows(IllegalArgumentException::class.java) { store.saveLanguage("en-US") }
        assertEquals("unset", store.read().language)
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

    @Test fun keyedUpdatesAndRemovalPreserveOtherProfilesAndCredentials() {
        val first = profile()
        val second = profile("second")
        store.saveApiKey(testKey)
        store.saveVoice(first)
        store.saveVoice(second)
        store.saveDefaultVoice(second.id)
        val updated = VoiceProfile(first.id, "Updated", first.referenceId, "drama-3",
            JSONObject().put("temperature", 0.9), "[calm]\n ")
        store.saveVoice(updated)
        val saved = store.read()
        assertEquals(listOf(first.id, second.id), saved.voices.map { it.id })
        assertEquals(second.toJson().toString(), saved.voices[1].toJson().toString())
        assertEquals(second.id, saved.defaultVoiceId)
        assertEquals(testKey, saved.apiKey)
        assertEquals("[calm]\n ", saved.voices[0].prefixText)
        store.removeVoice(second.id)
        assertEquals(first.id, store.read().defaultVoiceId)
        assertEquals(updated.toJson().toString(), store.read().voices.single().toJson().toString())
        assertThrows(IllegalArgumentException::class.java) { store.removeVoice("missing") }
        store.removeVoice(first.id)
        assertTrue(store.read().voices.isEmpty())
        assertEquals("", store.read().defaultVoiceId)
        assertEquals(testKey, store.read().apiKey)
    }

    @Test fun concurrentKeyedWritesDoNotLoseAnotherProfile() {
        val workers = java.util.concurrent.Executors.newFixedThreadPool(2)
        val ready = CountDownLatch(1)
        try {
            val writes = listOf(profile(), profile("second")).map { voice ->
                workers.submit { ready.await(); ConfigurationStore(context).saveVoice(voice) }
            }
            ready.countDown()
            writes.forEach { it.get(10, TimeUnit.SECONDS) }
            assertEquals(setOf("first", "second"), store.read().voices.map { it.id }.toSet())
        } finally { workers.shutdownNow() }
    }

    @Test fun keyedUpdateAtCapacityDoesNotPermitAnExtraProfile() {
        store.saveVoices((1..50).map { profile("profile-" + it) })
        store.saveDefaultVoice("profile-50")
        store.saveVoice(profile("profile-50", label = "Updated at capacity"))
        assertEquals("Updated at capacity", store.read().voices.last().label)
        assertThrows(IllegalArgumentException::class.java) { store.saveVoice(profile("extra")) }
        assertEquals(50, store.read().voices.size)
        assertEquals("profile-50", store.read().defaultVoiceId)
    }

    @Test fun vueSavesOneProfileDespiteAnotherInvalidDraft() {
        store.saveVoices(listOf(profile(label = "Reading voice"), profile("second", secondReference, "Narrator clone", "drama-3")))
        store.saveApiKey(testKey)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            awaitJs(scenario, "document.body.innerText.includes('Configuration loaded.')")
            assertEquals("false", evaluate(scenario, "document.getElementById('global-configuration').open"))
            assertEquals("0", evaluate(scenario, "document.querySelectorAll('#default-voice,#save-voices').length"))
            expandVoice(scenario, 0)
            expandVoice(scenario, 1)
            openAdvanced(scenario, 1)
            val prefix = "[calm]\n "
            setInput(scenario, "textarea[data-field=prefixText]", prefix, 1)
            setInput(scenario, "textarea[data-field=parameters]", "invalid JSON", 1)
            setInput(scenario, "input[id^=label-]", "Edited reading voice")
            saveRow(scenario, 0)
            awaitJs(scenario, "document.querySelectorAll('article[data-voice]')[0].dataset.dirty === 'false'")
            assertEquals("Edited reading voice", store.read().voices[0].label)
            assertEquals("", store.read().voices[1].prefixText)
            assertEquals(0.4, store.read().voices[1].parameters.getDouble("temperature"), 0.0)
            assertEquals(JSONObject.quote("invalid JSON"), evaluate(scenario, "document.querySelectorAll('textarea[data-field=parameters]')[1].value"))
            assertEquals("true", evaluate(scenario, "document.querySelectorAll('[data-action=expand-voice]')[1].getAttribute('aria-expanded') === 'true'"))
            assertEquals("true", evaluate(scenario, "document.querySelectorAll('[data-action=default-voice]')[1].disabled"))
            assertEquals("false", evaluate(scenario, "document.getElementById('start-preview').disabled"))
            selectOption(scenario, "#preview-voice", "second")
            assertEquals("true", evaluate(scenario, "document.getElementById('start-preview').disabled"))
            saveRow(scenario, 1)
            awaitJs(scenario, "document.querySelectorAll('article[data-voice]')[1].textContent.includes('Generation parameters must be a valid JSON object.')")
            assertEquals("", store.read().voices[1].prefixText)
            setInput(scenario, "textarea[data-field=parameters]", "{\"temperature\":0.8}", 1)
            saveRow(scenario, 1)
            awaitJs(scenario, "document.querySelectorAll('article[data-voice]')[1].dataset.dirty === 'false'")
            assertEquals(prefix, store.read().voices[1].prefixText)
            assertEquals("drama-3", store.read().voices[1].model)
            assertEquals(0.8, store.read().voices[1].parameters.getDouble("temperature"), 0.0)
            scenario.recreate()
            awaitJs(scenario, "document.body.innerText.includes('Configuration loaded.')")
            assertEquals(JSONObject.quote(prefix), evaluate(scenario, "document.querySelectorAll('textarea[data-field=prefixText]')[1].value"))
            assertEquals("\"\"", evaluate(scenario, "document.getElementById('api-key').value"))
            assertEquals("0", evaluate(scenario, "localStorage.length + sessionStorage.length"))
        }
    }

    @Test fun vueDefaultAndKeySavesPreserveAnUnrelatedDraft() {
        store.saveVoices(listOf(profile(model = "drama-3"), profile("second", secondReference)))
        store.saveApiKey(testKey)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            awaitJs(scenario, "document.body.innerText.includes('Configuration loaded.')")
            expandVoice(scenario, 0)
            openAdvanced(scenario, 0)
            val prefix = " [whisper]\n "
            setInput(scenario, "textarea[data-field=prefixText]", prefix)
            evaluate(scenario, "document.querySelectorAll('[data-action=default-voice]')[1].click()")
            awaitJs(scenario, "document.body.innerText.includes('Default system voice saved.')")
            assertEquals("second", store.read().defaultVoiceId)
            assertEquals("", store.read().voices[0].prefixText)
            assertEquals("true", evaluate(scenario, "document.querySelectorAll('[data-action=expand-voice]')[0].getAttribute('aria-expanded') === 'true'"))
            assertEquals("false", evaluate(scenario, "document.querySelectorAll('[data-action=expand-voice]')[1].getAttribute('aria-expanded') === 'true'"))
            assertEquals("true", evaluate(scenario, "document.querySelectorAll('[data-action=default-voice]')[1].checked"))
            assertEquals(JSONObject.quote("first"), evaluate(scenario, "document.getElementById('preview-voice').value"))
            evaluate(scenario, "document.querySelector('#global-configuration > summary').click()")
            setInput(scenario, "#api-key", testKey)
            evaluate(scenario, "document.getElementById('save-api-key').click()")
            awaitJs(scenario, "document.body.innerText.includes('API key saved in protected native storage.')")
            assertEquals(JSONObject.quote(prefix), evaluate(scenario, "document.querySelector('textarea[data-field=prefixText]').value"))
            assertEquals("true", evaluate(scenario, "document.querySelector('article[data-voice]').dataset.dirty === 'true'"))
            saveRow(scenario, 0)
            awaitJs(scenario, "document.querySelector('article[data-voice]').dataset.dirty === 'false'")
            assertEquals(prefix, store.read().voices[0].prefixText)
            assertEquals("second", store.read().defaultVoiceId)
            setInput(scenario, "#preview-speed", "0.1")
            evaluate(scenario, "document.getElementById('start-preview').click()")
            awaitJs(scenario, "document.body.innerText.includes('Choose a preview speed between 0.5 and 2.0.')")
            setInput(scenario, "#preview-speed", "1.5")
            assertEquals("true", evaluate(scenario, "document.getElementById('preview-pitch').disabled"))
            scenario.recreate()
            awaitJs(scenario, "document.body.innerText.includes('Configuration loaded.')")
            assertEquals("true", evaluate(scenario, "document.querySelectorAll('[data-action=default-voice]')[1].checked"))
            assertEquals(JSONObject.quote("drama-3"), evaluate(scenario, "document.querySelector('details[id^=model-] > summary').textContent.trim()"))
        }
    }

    @Test fun vueGlobalLanguagePersistsAcrossActivityRecreationAndPreservesDrafts() {
        store.saveVoices(listOf(profile(label = "Reading voice"), profile("second", secondReference, "Narrator")))
        store.saveApiKey(testKey)
        val captureScreenshots = InstrumentationRegistry.getArguments().getString("captureScreenshots") == "true"
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            awaitJs(scenario, "document.body.innerText.includes('Configuration loaded.')")
            assertEquals("false", evaluate(scenario, "document.getElementById('global-language').checked"))
            assertEquals("true", evaluate(scenario, "document.getElementById('preview-language').disabled"))
            expandVoice(scenario, 0)
            openAdvanced(scenario, 0)
            setInput(scenario, "textarea[data-field=parameters]", "invalid draft")
            evaluate(scenario, "document.querySelector('#global-configuration > summary').click()")
            setInput(scenario, "#api-key", testKey)
            evaluate(scenario, "document.getElementById('global-language').click()")
            awaitJs(scenario, "document.body.innerText.includes('Global language saved.')")
            assertEquals("system", ConfigurationStore(context).read().language)
            assertEquals(JSONObject.quote(testKey), evaluate(scenario, "document.getElementById('api-key').value"))
            assertEquals(JSONObject.quote("invalid draft"), evaluate(scenario, "document.querySelector('textarea[data-field=parameters]').value"))
            assertEquals(0.4, store.read().voices[0].parameters.getDouble("temperature"), 0.0)
            evaluate(scenario, "document.querySelectorAll('[data-action=default-voice]')[1].click()")
            awaitJs(scenario, "document.body.innerText.includes('Default system voice saved.')")
            assertEquals("system", store.read().language)
            scenario.recreate()
            awaitJs(scenario, "document.body.innerText.includes('Configuration loaded.')")
            assertEquals("true", evaluate(scenario, "document.getElementById('global-language').checked"))
            assertEquals("false", evaluate(scenario, "document.getElementById('preview-language').disabled"))
            evaluate(scenario, "document.querySelector('#global-configuration > summary').click()")
            if (captureScreenshots) {
                captureScreenshot("language-system.png")
                evaluate(scenario, "document.getElementById('preview-heading').scrollIntoView()")
                awaitJs(scenario, "document.getElementById('preview-heading').getBoundingClientRect().top < 100")
                captureScreenshot("preview-language-system.png")
                evaluate(scenario, "window.scrollTo(0, 0)")
            }
            evaluate(scenario, "document.getElementById('global-language').click()")
            awaitJs(scenario, "document.body.innerText.includes('Global language saved.')")
            assertEquals("unset", store.read().language)
            assertEquals("true", evaluate(scenario, "document.getElementById('preview-language').disabled"))
            if (captureScreenshots) {
                captureScreenshot("language-unset.png")
                evaluate(scenario, "document.getElementById('preview-heading').scrollIntoView()")
                awaitJs(scenario, "document.getElementById('preview-heading').getBoundingClientRect().top < 100")
                captureScreenshot("preview-language-unset.png")
            }
            assertEquals("second", store.read().defaultVoiceId)
        }
    }

    @Test fun vueRemovalPreservesAnotherDraftAndFallsBackToTheRemainingDefault() {
        store.saveVoices(listOf(profile(), profile("second", secondReference)))
        store.saveDefaultVoice("second")
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            awaitJs(scenario, "document.body.innerText.includes('Configuration loaded.')")
            expandVoice(scenario, 0)
            openAdvanced(scenario, 0)
            setInput(scenario, "textarea[data-field=parameters]", "invalid draft")
            expandVoice(scenario, 1)
            evaluate(scenario, "document.querySelectorAll('[data-action=remove-voice]')[1].click()")
            awaitJs(scenario, "document.querySelectorAll('article[data-voice]').length === 1")
            assertEquals("first", store.read().defaultVoiceId)
            assertEquals(1, store.read().voices.size)
            assertEquals(JSONObject.quote("invalid draft"), evaluate(scenario, "document.querySelector('textarea[data-field=parameters]').value"))
            assertEquals("true", evaluate(scenario, "document.querySelector('[data-action=default-voice]').checked"))
            evaluate(scenario, "document.getElementById('add-voice').click()")
            evaluate(scenario, "document.querySelectorAll('[data-action=remove-voice]')[1].click()")
            awaitJs(scenario, "document.querySelectorAll('article[data-voice]').length === 1")
            assertEquals(1, store.read().voices.size)
        }
    }

    @Test fun styledRowsHaveSeparateTouchTargetsAndLongNamesDoNotOverflow() {
        store.saveVoices(listOf(profile(label = "A".repeat(100)), profile("second", secondReference, "Narrator clone")))
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            awaitJs(scenario, "document.body.innerText.includes('Configuration loaded.')")
            assertEquals("1", evaluate(scenario, "document.querySelectorAll('h1').length"))
            assertEquals("false", evaluate(scenario, "document.getElementById('global-configuration').open"))
            val targets = JSONObject(evaluate(scenario, """
                (() => {
                  const card = document.querySelector('article[data-voice]');
                  const choice = card.querySelector('.default-choice').getBoundingClientRect();
                  const toggle = card.querySelector('[data-action=expand-voice]').getBoundingClientRect();
                  return {width: choice.width, height: choice.height, toggleHeight: toggle.height,
                    right: choice.right, left: toggle.left, font: getComputedStyle(document.documentElement).fontSize};
                })()
            """.trimIndent()))
            assertTrue("Unexpected touch geometry: $targets", targets.getDouble("width") >= 48 &&
                targets.getDouble("height") >= 48 && targets.getDouble("toggleHeight") >= 48 &&
                // Device-scale conversion can differ by millionths of a CSS pixel at a shared edge.
                targets.getDouble("right") <= targets.getDouble("left") + 0.01)
            assertEquals("true", evaluate(scenario, "document.documentElement.scrollWidth <= document.documentElement.clientWidth"))
            expandVoice(scenario, 0)
            assertEquals("first", store.read().defaultVoiceId)
            assertEquals("true", evaluate(scenario, """
                (() => {
                  const input = document.querySelector('input[data-field=referenceId]');
                  return document.querySelector('label[for="' + input.id + '"]').getBoundingClientRect().bottom <= input.getBoundingClientRect().top;
                })()
            """.trimIndent()))
            evaluate(scenario, "document.getElementById('add-voice').click()")
            evaluate(scenario, "document.getElementById('add-voice').click()")
            assertEquals("true", evaluate(scenario, "Array.from(document.querySelectorAll('[data-action=default-voice]')).slice(2).every(radio => radio.disabled && !radio.checked)"))
            assertEquals("true", evaluate(scenario, "document.documentElement.scrollWidth <= document.documentElement.clientWidth"))
        }
    }

    /** A real touch opens the actual IME; DOM-only focus would not exercise Android keyboard behavior. */
    @Test fun referenceEditorStaysVisibleAboveTheAndroidKeyboard() {
        store.saveVoices(listOf(profile(label = "Reading voice"), profile("second", secondReference, "Conversation voice")))
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            awaitJs(scenario, "document.body.innerText.includes('Configuration loaded.')")
            expandVoice(scenario, 0)
            val height = evaluate(scenario, "visualViewport.height").toDouble()
            val point = JSONObject(evaluate(scenario, """
                (() => {
                  const input = document.querySelector('input[data-field=referenceId]');
                  input.scrollIntoView({block: 'center'});
                  const rect = input.getBoundingClientRect();
                  return {x: rect.x + rect.width / 2, y: rect.y + rect.height / 2, width: innerWidth};
                })()
            """.trimIndent()))
            var x = 0f
            var y = 0f
            scenario.onActivity { activity ->
                val view = activity.bridge.webView
                val origin = IntArray(2)
                view.getLocationOnScreen(origin)
                val scale = view.width / point.getDouble("width")
                x = (origin[0] + point.getDouble("x") * scale).toFloat()
                y = (origin[1] + point.getDouble("y") * scale).toFloat()
            }
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val downTime = SystemClock.uptimeMillis()
            for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, x, y, 0)
                event.source = InputDevice.SOURCE_TOUCHSCREEN
                try { instrumentation.sendPointerSync(event) } finally { event.recycle() }
            }
            try {
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
                var visible = false
                while (!visible && System.nanoTime() < deadline) {
                    scenario.onActivity { activity ->
                        visible = ViewCompat.getRootWindowInsets(activity.window.decorView)?.isVisible(WindowInsetsCompat.Type.ime()) == true
                    }
                    if (!visible) Thread.sleep(100)
                }
                assertTrue("Android keyboard did not open after tapping the input.", visible)
                awaitJs(scenario, "visualViewport.height < $height")
                awaitJs(scenario, """
                    (() => {
                      const input = document.querySelector('input[data-field=referenceId]');
                      const rect = input.getBoundingClientRect();
                      return document.activeElement === input && rect.top >= visualViewport.offsetTop &&
                        rect.bottom <= visualViewport.offsetTop + visualViewport.height;
                    })()
                """.trimIndent())
                assertEquals("true", evaluate(scenario, "document.documentElement.scrollWidth <= document.documentElement.clientWidth"))
            } finally {
                scenario.onActivity { activity ->
                    WindowCompat.getInsetsController(activity.window, activity.bridge.webView).hide(WindowInsetsCompat.Type.ime())
                }
            }
        }
    }

    @Test fun bundledLicensesAreReadableWithNetworkLoadsBlocked() {
        // This exercises Capacitor's real local asset server in the installed APK,
        // rather than a browser fixture or a cached remote license page.
        val notices = context.assets.open("public/licenses/notices.json").bufferedReader().use { JSONObject(it.readText()) }
        val components = notices.getJSONArray("components")
        assertTrue(components.length() > 0)
        assertTrue(context.assets.open("public/licenses/public_suffix_list.dat").use { it.read() } >= 0)
        val captureScreenshots = InstrumentationRegistry.getArguments().getString("captureScreenshots") == "true"
        if (captureScreenshots) {
            // Documentation images use only example profiles and a non-credential;
            // the existing setup/cleanup restores the emulator's original settings.
            store.saveVoices(listOf(profile(label = "Reading voice"), profile("second", secondReference, "Narrator")))
            store.saveApiKey(testKey)
        }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            awaitJs(scenario, "document.getElementById('open-source-licenses') !== null")
            if (captureScreenshots) {
                awaitJs(scenario, "document.getElementById('page-status').textContent === 'Configuration loaded.'")
                expandVoice(scenario, 0)
                evaluate(scenario, "window.scrollTo(0, 0)")
                captureScreenshot("voices.png")
                evaluate(scenario, "document.getElementById('preview-heading').scrollIntoView()")
                awaitJs(scenario, "window.scrollY > 0 && document.getElementById('preview-heading').getBoundingClientRect().top < 100")
                captureScreenshot("preview.png")
            }
            scenario.onActivity { activity -> activity.bridge.webView.settings.blockNetworkLoads = true }
            evaluate(scenario, "document.getElementById('open-source-licenses').open = true")
            awaitJs(scenario, "document.querySelectorAll('.license-component').length === ${components.length()}")
            evaluate(scenario, """
                Array.from(document.querySelectorAll('.license-component')).forEach(element => element.open = true)
            """.trimIndent())
            assertEquals("true", evaluate(scenario, """
                (() => {
                  const text = document.getElementById('open-source-licenses').innerText;
                  return text.includes('Permission is hereby granted, free of charge') &&
                    text.includes('Drifty Co.') && text.includes('Yuxi (Evan) You') &&
                    text.includes('The Apache Software Foundation') &&
                    text.includes('Mozilla Public License Version 2.0') &&
                    document.documentElement.scrollWidth <= document.documentElement.clientWidth;
                })()
            """.trimIndent()))
        }
    }

    /** Optional real-APK documentation output, never run with a user's credentials in the panel. */
    private fun captureScreenshot(name: String) {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        automation.waitForIdle(200, 5_000)
        // UI idle does not wait for WebView's compositor to present a scroll.
        SystemClock.sleep(700)
        val bitmap = checkNotNull(automation.takeScreenshot())
        try {
            val output = File(context.externalCacheDir, "screenshots/$name")
            check(output.parentFile!!.mkdirs() || output.parentFile!!.isDirectory)
            output.outputStream().use { check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)) }
        } finally {
            bitmap.recycle()
        }
    }

    private fun expandVoice(scenario: ActivityScenario<MainActivity>, index: Int) {
        evaluate(scenario, """
            (() => {
              const button = document.querySelectorAll('[data-action=expand-voice]')[$index];
              if (button.getAttribute('aria-expanded') !== 'true') button.click();
              return true;
            })()
        """.trimIndent())
        awaitJs(scenario, "document.querySelectorAll('[data-action=expand-voice]')[$index].getAttribute('aria-expanded') === 'true'")
    }

    private fun openAdvanced(scenario: ActivityScenario<MainActivity>, index: Int) {
        evaluate(scenario, "document.querySelectorAll('details.advanced')[$index].open = true")
        awaitJs(scenario, "document.querySelectorAll('details.advanced')[$index].open")
    }

    private fun saveRow(scenario: ActivityScenario<MainActivity>, index: Int) {
        evaluate(scenario, "document.querySelectorAll('[data-action=save-voice]')[$index].click()")
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
