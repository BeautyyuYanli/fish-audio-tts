package com.fishaudio.tts

import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONArray
import org.json.JSONObject
import org.msgpack.core.MessagePack
import org.msgpack.core.MessagePacker

/** Fish's binary MessagePack events. Audio is raw PCM; WebSocket message boundaries need not align samples. */
internal object FishLiveProtocol {
    data class Event(val name: String, val audio: ByteArray?, val reason: String?)
    private const val MAX_MESSAGE_BYTES = 1024 * 1024

    fun encode(event: JSONObject): ByteString = MessagePack.newDefaultBufferPacker().use {
        pack(it, event)
        it.toByteArray().toByteString()
    }

    private fun pack(packer: MessagePacker, value: Any?) {
        when (value) {
            null, JSONObject.NULL -> packer.packNil()
            is JSONObject -> {
                packer.packMapHeader(value.length())
                for (key in value.keys()) { packer.packString(key); pack(packer, value.get(key)) }
            }
            is JSONArray -> {
                packer.packArrayHeader(value.length())
                for (index in 0 until value.length()) pack(packer, value.get(index))
            }
            is Boolean -> packer.packBoolean(value)
            is Byte, is Short, is Int, is Long -> packer.packLong((value as Number).toLong())
            is Number -> packer.packDouble(value.toDouble())
            is String -> packer.packString(value)
            else -> throw IllegalArgumentException("Unsupported generation parameter.")
        }
    }

    fun decode(bytes: ByteString): Event {
        if (bytes.size > MAX_MESSAGE_BYTES) throw InvalidAudioException("WebSocket message is too large.")
        return MessagePack.DEFAULT_UNPACKER_CONFIG.withStringSizeLimit(65536).newUnpacker(bytes.toByteArray()).use {
            val count = it.unpackMapHeader()
            if (count !in 1..32) throw InvalidAudioException("Invalid WebSocket event.")
            var name = ""
            var audio: ByteArray? = null
            var reason: String? = null
            repeat(count) { _ ->
                when (it.unpackString()) {
                    "event" -> name = it.unpackString()
                    "reason" -> reason = it.unpackString()
                    "audio" -> {
                        val size = it.unpackBinaryHeader()
                        if (size !in 0..MAX_MESSAGE_BYTES) throw InvalidAudioException("Audio message is too large.")
                        audio = it.readPayload(size)
                    }
                    else -> it.skipValue()
                }
            }
            if (name.isEmpty() || it.hasNext()) throw InvalidAudioException("Invalid WebSocket event.")
            Event(name, audio, reason)
        }
    }
}
