package com.fishaudio.tts

import okhttp3.Call
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

class CatalogException(message: String) : IOException(message)
data class OwnedVoice(val id: String, val title: String)
data class OwnedVoicePage(val voices: List<OwnedVoice>, val hasMore: Boolean)

/**
 * Read-only Fish catalogs. The schema is public; only self=true voice requests receive the key.
 * The plugin owns asynchronous execution/cancellation. Parsers expose only slugs or voice ID/title,
 * bound received JSON, and never forward upstream error bodies. Endpoint injection is for tests.
 */
class FishCatalogClient(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).callTimeout(25, TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).build(),
    private val baseUrl: HttpUrl = "https://api.fish.audio/".toHttpUrl(),
    private val maxResponseBytes: Int = 8 * 1024 * 1024,
) {
    fun modelsCall(): Call = client.newCall(Request.Builder().url(baseUrl.resolve("openapi.json")!!)
        .header("Accept", "application/json").header("Cache-Control", "no-cache").build())

    fun voicesCall(apiKey: String, page: Int): Call {
        require(apiKey.isNotBlank()) { "Save an API key before loading your voices." }
        require(page in 1..1000000) { "Invalid voice catalog page." }
        val url = baseUrl.resolve("model")!!.newBuilder().addQueryParameter("self", "true")
            .addQueryParameter("page_size", PAGE_SIZE.toString()).addQueryParameter("page_number", page.toString()).build()
        return client.newCall(Request.Builder().url(url).header("Accept", "application/json")
            .header("Authorization", "Bearer $apiKey").header("Cache-Control", "no-cache").build())
    }

    fun readModels(response: Response): List<String> {
        val schema = readJson(response)
        val parameters = schema.getJSONObject("paths").getJSONObject("/v1/tts").getJSONObject("post").getJSONArray("parameters")
        val parameter = (0 until parameters.length()).map { parameters.getJSONObject(it) }
            .firstOrNull { it.optString("in") == "header" && it.optString("name") == "model" }
            ?: throw CatalogException("Fish Audio's TTS model list is unavailable. Please retry.")
        val values = parameter.getJSONObject("schema").getJSONArray("enum")
        require(values.length() in 1..1000) { "Invalid TTS model list." }
        return (0 until values.length()).map { values.getString(it).also { slug ->
            require(slug.matches(VoiceProfile.MODEL_SLUG)) { "Invalid TTS model slug." }
        } }.distinct()
    }

    fun readVoices(response: Response, page: Int): OwnedVoicePage {
        val json = readJson(response)
        val items = json.getJSONArray("items")
        require(items.length() <= PAGE_SIZE) { "Invalid voice catalog page size." }
        val voices = (0 until items.length()).map { items.getJSONObject(it) }.filter { it.getString("type") == "tts" }
            .map { item ->
                val id = item.getString("_id")
                require(id.matches(Regex("[A-Za-z0-9_-]{1,128}"))) { "Invalid voice catalog ID." }
                OwnedVoice(id, item.getString("title").take(100).ifBlank { id })
            }.distinctBy { it.id }
        val hasMore = if (!json.isNull("has_more")) json.getBoolean("has_more")
            else page.toLong() * PAGE_SIZE < json.getLong("total")
        return OwnedVoicePage(voices, hasMore)
    }

    private fun readJson(response: Response): JSONObject {
        if (!response.isSuccessful) throw CatalogException(when (response.code) {
            401, 403 -> "Fish Audio denied access. Check the saved API key and workspace access."
            429 -> "Fish Audio's rate limit was reached. Please retry later."
            else -> "Fish Audio's catalog is unavailable. Please retry."
        })
        val body = response.body ?: throw CatalogException("Fish Audio returned an empty catalog.")
        if (body.contentLength() > maxResponseBytes) throw CatalogException("Fish Audio's catalog response is too large.")
        val output = ByteArrayOutputStream()
        body.byteStream().use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count == 0) throw CatalogException("Fish Audio's catalog response stalled.")
                if (output.size() + count > maxResponseBytes) throw CatalogException("Fish Audio's catalog response is too large.")
                output.write(buffer, 0, count)
            }
        }
        return JSONObject(output.toString(Charsets.UTF_8.name()))
    }

    companion object { const val PAGE_SIZE = 100 }
}
