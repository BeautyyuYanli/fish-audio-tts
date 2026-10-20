package com.fishaudio.tts

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileNotFoundException
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Single-process native store shared by the service and plugin, independent of Activity lifetime.
 * AES-GCM uses a non-exportable Android Keystore key and a new IV for each atomic write.
 * The ciphertext lives in credential-protected noBackupFilesDir; no browser or backup copy exists.
 * Locks cover only local reads/writes, never synthesis, network I/O, or cancellation.
 */
class ConfigurationStore(context: Context) {
    private val file = AtomicFile(File(context.applicationContext.noBackupFilesDir, "fish-configuration.bin"))

    fun read(): EngineConfiguration = synchronized(lock) { readLocked() }

    /** Key and profile forms save independently, preserving the other form's persisted data. */
    fun saveApiKey(apiKey: String): EngineConfiguration = synchronized(lock) {
        val previous = readLocked()
        val value = apiKey.trim().ifEmpty { previous.apiKey }
        require(value.matches(Regex("[!-~]{1,4096}"))) { "Enter a valid Fish Audio API key without spaces." }
        writeLocked(EngineConfiguration(value, previous.voices, previous.defaultVoiceId))
    }

    fun saveVoices(voices: List<VoiceProfile>): EngineConfiguration = synchronized(lock) {
        require(voices.size <= 50) { "Save at most 50 voice profiles." }
        voices.forEach { it.validate() }
        require(voices.map { it.id }.distinct().size == voices.size) { "Voice profile IDs must be unique." }
        val previous = readLocked()
        writeLocked(EngineConfiguration(previous.apiKey, voices, previous.defaultVoiceId))
    }

    fun saveDefaultVoice(voiceId: String): EngineConfiguration = synchronized(lock) {
        val previous = readLocked()
        require(previous.voices.any { it.id == voiceId }) { "Select a saved voice as the system default." }
        writeLocked(EngineConfiguration(previous.apiKey, previous.voices, voiceId))
    }

    fun deleteApiKey(): EngineConfiguration = synchronized(lock) {
        val previous = readLocked()
        writeLocked(EngineConfiguration(voices = previous.voices, preferredVoiceId = previous.defaultVoiceId))
    }

    private fun writeLocked(config: EngineConfiguration): EngineConfiguration {
        val json = JSONObject().put("apiKey", config.apiKey)
            .put("voices", JSONArray(config.voices.map { it.toJson() }))
            .put("defaultVoiceId", config.defaultVoiceId)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(create = true))
        val encrypted = cipher.iv + cipher.doFinal(json.toString().toByteArray(Charsets.UTF_8))
        val output = file.startWrite()
        try {
            output.write(encrypted)
            file.finishWrite(output)
        } catch (error: Exception) {
            file.failWrite(output)
            throw error
        }
        return config
    }

    fun clear() = synchronized(lock) { clearLocked() }

    private fun clearLocked() {
        file.delete()
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        store.deleteEntry(KEY_ALIAS)
    }

    private fun readLocked(): EngineConfiguration {
        val encrypted = try { file.openRead().use { it.readBytes() } }
        catch (_: FileNotFoundException) { return EngineConfiguration() }
        check(encrypted.size >= 28) { "Saved configuration is unreadable. Clear app data in Android Settings and configure again." }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(create = false), GCMParameterSpec(128, encrypted.copyOfRange(0, 12)))
        val json = JSONObject(String(cipher.doFinal(encrypted.copyOfRange(12, encrypted.size)), Charsets.UTF_8))
        val voices = if (json.has("voices")) {
            val array = json.getJSONArray("voices")
            (0 until array.length()).map { VoiceProfile.fromJson(array.getJSONObject(it)) }
        } else {
            // Preserve an already-saved single-voice configuration when first opening the list-based panel.
            val reference = json.optString("referenceId")
            if (reference.isBlank()) emptyList() else listOf(VoiceProfile(
                UUID.nameUUIDFromBytes(reference.toByteArray(Charsets.UTF_8)).toString(), "", reference,
                json.getString("model"), JSONObject().put("language", json.getString("language"))
                    .put("prosody", JSONObject().put("speed", json.getDouble("speed"))),
            ))
        }
        return EngineConfiguration(json.getString("apiKey"), voices, json.optString("defaultVoiceId"))
    }

    private fun key(create: Boolean): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        check(create) { "Saved key is unavailable. Clear app data in Android Settings and configure again." }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build())
        }.generateKey()
    }

    companion object {
        private const val KEY_ALIAS = "fish-tts-configuration-v1"
        private val lock = Any()
    }
}
