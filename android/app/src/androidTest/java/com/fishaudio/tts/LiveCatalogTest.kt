package com.fishaudio.tts

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.DataInputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Opt-in HTTPS catalog/bridge/UI check. A one-shot adb-reversed socket supplies credentials in memory. */
@RunWith(AndroidJUnit4::class)
class LiveCatalogTest {
    @Test fun opensRealCatalogsSelectsAndPersistsWithoutReturningTheKey() {
        val arguments = InstrumentationRegistry.getArguments()
        val port = arguments.getString("fishCatalogCredentialPort")?.toIntOrNull()
        assumeTrue("Live catalogs require a dedicated emulator and explicit opt-in.",
            port != null || arguments.getString("fishLiveCatalog") == "true")
        val store = ConfigurationStore(InstrumentationRegistry.getInstrumentation().targetContext)
        val original = store.read()
        try {
            store.clear()
            store.saveVoices(listOf(VoiceProfile("catalog-live", "", "catalog-reference")))
            if (port != null) {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress("127.0.0.1", port), 5000)
                    socket.soTimeout = 5000
                    val input = DataInputStream(socket.getInputStream())
                    val size = input.readInt()
                    require(size in 1..4096)
                    val bytes = ByteArray(size)
                    input.readFully(bytes)
                    try {
                        val credential = JSONObject(String(bytes, Charsets.UTF_8))
                        try { store.saveApiKey(credential.getString("apiKey")) }
                        finally { credential.remove("apiKey") }
                    } finally { bytes.fill(0) }
                }
            }
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                awaitJs(scenario, "document.body.innerText.includes('Configuration loaded.')")
                evaluate(scenario, "document.querySelector('[data-action=expand-voice]').click()")
                evaluate(scenario, "document.querySelector('details[id^=model-] > summary').click()")
                awaitJs(scenario, "document.querySelectorAll('details[id^=model-] button[data-option-value]:not([data-option-value=\"\"])').length > 0")
                assertEquals("true", evaluate(scenario, "Array.from(document.querySelectorAll('details[id^=model-] button[data-option-value]:not([data-option-value=\"\"])')).every(button => button.textContent.trim() === button.dataset.optionValue)"))
                val slug = JSONObject("{\"value\":" + evaluate(scenario, "document.querySelector('details[id^=model-] button[data-option-value]:not([data-option-value=\"\"])').dataset.optionValue") + "}").getString("value")
                evaluate(scenario, "document.querySelector('details[id^=model-] button[data-option-value]:not([data-option-value=\"\"])').click()")
                evaluate(scenario, "document.querySelector('[data-action=save-voice]').click()")
                awaitJs(scenario, "document.querySelector('article[data-voice]').dataset.dirty === 'false'")
                assertEquals(slug, store.read().voices.single().model)
                evaluate(scenario, "document.querySelector('details[id^=own-voice-] > summary').click()")
                if (port == null) {
                    awaitJs(scenario, "document.querySelector('details[id^=own-voice-]').textContent.includes('Save an API key before loading your voices.')")
                } else {
                    awaitJs(scenario, "(() => { const picker = document.querySelector('details[id^=own-voice-]'); return Boolean(picker.open && !picker.querySelector('[role=status]') && (picker.querySelector('button[data-option-value]') || picker.textContent.includes('No voices found in the current workspace.') || picker.querySelector('[role=alert]'))); })()")
                    assertEquals("0", evaluate(scenario, "document.querySelectorAll('details[id^=own-voice-] [role=alert]').length"))
                    val selected = evaluate(scenario, "(() => { const button = document.querySelector('details[id^=own-voice-] button[data-option-value]'); if (!button) return ''; const id = button.dataset.optionValue; button.click(); return id; })()")
                    if (selected != "\"\"") {
                        assertEquals(selected, evaluate(scenario, "document.querySelector('input[data-field=referenceId]').value"))
                        assertEquals("true", evaluate(scenario, "document.querySelector('input[id^=label-]').value.length > 0"))
                        evaluate(scenario, "document.querySelector('[data-action=save-voice]').click()")
                        awaitJs(scenario, "document.querySelector('article[data-voice]').dataset.dirty === 'false'")
                        assertEquals(selected, JSONObject.quote(store.read().voices.single().referenceId))
                    }
                }
                assertEquals("\"\"", evaluate(scenario, "document.getElementById('api-key').value"))
                assertEquals("0", evaluate(scenario, "localStorage.length + sessionStorage.length"))
            }
        } finally {
            store.clear()
            store.saveVoices(original.voices)
            store.saveLanguage(original.language)
            if (original.defaultVoiceId.isNotEmpty()) store.saveDefaultVoice(original.defaultVoiceId)
            if (original.apiKey.isNotBlank()) store.saveApiKey(original.apiKey)
        }
    }

    private fun evaluate(scenario: ActivityScenario<MainActivity>, script: String): String {
        val value = AtomicReference<String>()
        val ready = CountDownLatch(1)
        scenario.onActivity { it.bridge.webView.evaluateJavascript(script) { result -> value.set(result); ready.countDown() } }
        assertTrue("WebView evaluation timed out.", ready.await(5, TimeUnit.SECONDS))
        return value.get()
    }

    private fun awaitJs(scenario: ActivityScenario<MainActivity>, script: String) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(40)
        while (System.nanoTime() < deadline) {
            if (evaluate(scenario, script) == "true") return
            Thread.sleep(100)
        }
        fail("The live catalog did not reach the expected panel state.")
    }
}
