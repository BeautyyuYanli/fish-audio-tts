package com.fishaudio.tts

import org.json.JSONObject
import java.util.UUID

/**
 * Per-row upstream configuration. Prefix whitespace is significant; parameters remain upstream values.
 * Models use safe slugs rather than an offline list; Fish owns availability.
 */
class VoiceProfile(
    val id: String,
    val label: String,
    val referenceId: String,
    val model: String = "",
    val parameters: JSONObject = JSONObject(),
    val prefixText: String = "",
) {
    fun validate() {
        require(id.matches(Regex("[A-Za-z0-9_-]{1,128}"))) { "Invalid voice profile ID." }
        require(label.length <= 100) { "Voice labels must contain at most 100 characters." }
        require(referenceId.matches(Regex("[A-Za-z0-9_-]{1,128}"))) { "Enter a valid Fish Audio Reference ID." }
        require(model.isEmpty() || model.matches(MODEL_SLUG)) { "Select a valid Fish Audio model slug or use the upstream default." }
        require(prefixText.length <= 4000) { "Prefix text must contain at most 4000 characters per voice." }
        require(parameters.toString().length <= 16384) { "Generation parameters must contain at most 16384 characters per voice." }
        require(parameters.keys().asSequence().none { it in RESERVED }) {
            "Generation parameters cannot contain text, reference_id, references, model, format or sample_rate. Use the voice fields; audio format is managed by Android."
        }
        require(!parameters.has("prosody") || parameters.isNull("prosody") || parameters.opt("prosody") is JSONObject) { "The prosody parameter must be a JSON object or null." }
    }

    fun toJson(): JSONObject = JSONObject().put("id", id).put("label", label).put("referenceId", referenceId)
        .put("model", model).put("parameters", JSONObject(parameters.toString()))
        .put("prefixText", prefixText)

    companion object {
        val MODEL_SLUG = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
        private val RESERVED = setOf("text", "reference_id", "references", "model", "format", "sample_rate")

        fun fromJson(json: JSONObject): VoiceProfile = VoiceProfile(
            json.optString("id").ifEmpty { UUID.randomUUID().toString() }, json.optString("label").trim(),
            json.getString("referenceId").trim(), json.optString("model"), json.getJSONObject("parameters"), json.optString("prefixText"),
        ).also { it.validate() }
    }
}

/** The saved default uses stable profile identity; old configurations and deleted defaults use the first remaining voice. */
class EngineConfiguration(
    val apiKey: String = "",
    val voices: List<VoiceProfile> = emptyList(),
    preferredVoiceId: String = "",
) {
    val defaultVoice: VoiceProfile? = voices.firstOrNull { it.id == preferredVoiceId } ?: voices.firstOrNull()
    val defaultVoiceId: String get() = defaultVoice?.id.orEmpty()
    val configured: Boolean get() = apiKey.isNotBlank() && voices.isNotEmpty()
}
