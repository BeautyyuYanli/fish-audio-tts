package com.fishaudio.tts

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.json.JSONException
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Official schema/protocol fixtures; these do not establish real account access. */
class FishCatalogClientTest {
    private lateinit var server: MockWebServer
    private val key = "test-not-a-real-credential"
    @Before fun setUp() { server = MockWebServer(); server.start() }
    @After fun tearDown() { server.shutdown() }
    private fun catalog(client: OkHttpClient = OkHttpClient.Builder().followRedirects(false).build(), limit: Int = 8 * 1024 * 1024) =
        FishCatalogClient(client, server.url("/"), limit)

    @Test fun readsOnlyTheTtsHeaderEnumWithoutSendingCredentials() {
        server.enqueue(MockResponse().setBody("""{"paths":{"/v1/tts":{"post":{"parameters":[
          {"name":"model","in":"query","schema":{"enum":["wrong-place"]}},
          {"name":"model","in":"header","schema":{"enum":["s1","future-model.v4","s1"]}}
        ]}}}}"""))
        val catalog = catalog()
        catalog.modelsCall().execute().use { assertEquals(listOf("s1", "future-model.v4"), catalog.readModels(it)) }
        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/openapi.json", request.path)
        assertNull(request.getHeader("Authorization"))
    }

    @Test fun requestsOwnedVoicesAndPagesWithTheSavedKey() {
        server.enqueue(MockResponse().setBody("""{"items":[
          {"_id":"first-voice","title":"My clone","type":"tts"},
          {"_id":"conversion","title":"Unsupported conversion","type":"svc"},
          {"_id":"second_voice","title":"","type":"tts"},
          {"_id":"first-voice","title":"Duplicate","type":"tts"}
        ],"total":300,"has_more":true}"""))
        val catalog = catalog()
        catalog.voicesCall(key, 2).execute().use {
            assertEquals(OwnedVoicePage(listOf(OwnedVoice("first-voice", "My clone"), OwnedVoice("second_voice", "second_voice")), true), catalog.readVoices(it, 2))
        }
        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/model", request.requestUrl!!.encodedPath)
        assertEquals("true", request.requestUrl!!.queryParameter("self"))
        assertEquals("100", request.requestUrl!!.queryParameter("page_size"))
        assertEquals("2", request.requestUrl!!.queryParameter("page_number"))
        assertNull(request.requestUrl!!.queryParameter("title"))
        assertEquals("Bearer $key", request.getHeader("Authorization"))
    }

    @Test fun honorsExplicitPaginationAndFallsBackToTotalIncludingEmptyPages() {
        val catalog = catalog()
        for ((metadata, expected) in listOf("\"total\":300,\"has_more\":false" to false,
            "\"total\":101,\"has_more\":null" to true, "\"total\":0" to false)) {
            server.enqueue(MockResponse().setBody("{\"items\":[],$metadata}"))
            catalog.voicesCall(key, 1).execute().use { assertEquals(OwnedVoicePage(emptyList(), expected), catalog.readVoices(it, 1)) }
        }
    }

    @Test fun rejectsMissingCredentialsAndBadPagesBeforeNetworkAccess() {
        val catalog = catalog()
        assertThrows(IllegalArgumentException::class.java) { catalog.voicesCall("", 1) }
        assertThrows(IllegalArgumentException::class.java) { catalog.voicesCall(key, 0) }
        assertEquals(0, server.requestCount)
    }

    @Test fun httpFailuresDoNotExposeBodiesOrFollowRedirects() {
        val catalog = catalog()
        for (status in listOf(401, 403, 429, 503, 302)) {
            server.enqueue(MockResponse().setResponseCode(status).setHeader("Location", server.url("/unexpected"))
                .setBody("Sensitive untrusted detail $key"))
            val error = assertThrows(CatalogException::class.java) {
                catalog.voicesCall(key, 1).execute().use { catalog.readVoices(it, 1) }
            }
            assertFalse(error.message!!.contains(key))
            assertFalse(error.message!!.contains("Sensitive"))
            assertEquals("/model", server.takeRequest().requestUrl!!.encodedPath)
        }
        assertEquals(5, server.requestCount)
    }

    @Test fun malformedOrOversizedResponsesFailInsteadOfSupplyingInvalidChoices() {
        val catalog = catalog(limit = 256)
        server.enqueue(MockResponse().setBody("not json"))
        assertThrows(JSONException::class.java) { catalog.modelsCall().execute().use { catalog.readModels(it) } }
        server.enqueue(MockResponse().setBody("""{"paths":{"/v1/tts":{"post":{"parameters":[{"in":"header","name":"model","schema":{"enum":["bad slug"]}}]}}}}"""))
        assertThrows(IllegalArgumentException::class.java) { catalog.modelsCall().execute().use { catalog.readModels(it) } }
        for (chunked in listOf(false, true)) {
            val body = " ".repeat(257)
            server.enqueue(if (chunked) MockResponse().setChunkedBody(body, 17) else MockResponse().setBody(body))
            assertThrows(CatalogException::class.java) { catalog.modelsCall().execute().use { catalog.readModels(it) } }
        }
    }

    @Test fun timeoutAndCancellationUnblockCatalogRequests() {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val timed = catalog(OkHttpClient.Builder().callTimeout(300, TimeUnit.MILLISECONDS).build())
        assertThrows(IOException::class.java) { timed.modelsCall().execute().close() }
        server.takeRequest()
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val request = catalog().voicesCall(key, 1)
        val worker = Executors.newSingleThreadExecutor()
        try {
            val finished = worker.submit<Boolean> {
                try { request.execute().close(); false } catch (_: IOException) { true }
            }
            assertNotNull(server.takeRequest(2, TimeUnit.SECONDS))
            request.cancel()
            assertTrue(finished.get(2, TimeUnit.SECONDS))
        } finally { worker.shutdownNow() }
    }
}
