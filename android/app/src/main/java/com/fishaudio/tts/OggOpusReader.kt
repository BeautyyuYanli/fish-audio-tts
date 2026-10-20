package com.fishaudio.tts

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class InvalidAudioException(message: String) : IOException(message)

/**
 * Forward-only reader for Fish's mono Ogg Opus transport (RFC 3533 / RFC 7845).
 * Keeps one Ogg page and one bounded packet, never the utterance. Validates checksums,
 * page continuity and an explicit end page, so truncation cannot masquerade as success.
 * The caller owns the stream, cancellation and decoder. Chained/multiplexed streams are rejected.
 */
class OggOpusReader(private val input: InputStream, private val checkCancelled: () -> Unit) {
    data class Packet(val data: ByteArray, val samples: Int, val endGranule: Long?)

    private var page = ByteArray(0)
    private var segments = 0
    private var segment = 0
    private var offset = 0
    private var flags = 0
    private var granule = 0L
    private var previousGranule = 0L
    private var serial: Int? = null
    private var sequence = 0
    private var bytesRead = 0L
    private var ended = false
    private val pending = ByteArrayOutputStream()
    private var packetSamples = 0L

    val header: ByteArray = readPacket() ?: throw InvalidAudioException("Missing Opus header.")
    val preSkip: Int

    init {
        if (header.size != 19 || !header.startsWithAscii("OpusHead") || header[8].toInt() != 1 ||
            header[9].toInt() != 1 || header[18].toInt() != 0 || flags != 2 || segments != 1)
            throw InvalidAudioException("Expected a mono Ogg Opus stream.")
        preSkip = (header[10].toInt() and 255) or ((header[11].toInt() and 255) shl 8)
        val tags = readPacket() ?: throw InvalidAudioException("Missing Opus tags.")
        if (tags.size < 16 || !tags.startsWithAscii("OpusTags")) throw InvalidAudioException("Invalid Opus tags.")
    }

    fun nextPacket(): Packet? {
        val data = readPacket() ?: return null
        if (data.isEmpty()) throw InvalidAudioException("Empty Opus packet.")
        // RFC 6716 section 3.1: the TOC determines decoded duration, at a fixed 48 kHz.
        val toc = data[0].toInt() and 255
        val config = toc ushr 3
        val frameSamples = when {
            config >= 16 -> 120 shl (config and 3)
            config >= 12 -> 480 shl (config and 1)
            config and 3 == 3 -> 2880
            else -> 480 shl (config and 3)
        }
        val frames = when (toc and 3) {
            0 -> 1
            1, 2 -> 2
            else -> if (data.size >= 2) data[1].toInt() and 63 else 0
        }
        val samples = frames * frameSamples
        if (samples !in 1..5760) throw InvalidAudioException("Invalid Opus packet duration.")
        packetSamples += samples
        val end = granule.takeIf { flags and 4 != 0 }
        if (end != null && (end < preSkip || (segment == segments && end > packetSamples)))
            throw InvalidAudioException("Invalid Opus end position.")
        return Packet(data, samples, end)
    }

    private fun readPacket(): ByteArray? {
        while (true) {
            checkCancelled()
            if (segment == segments) {
                if (flags and 4 != 0) {
                    if (pending.size() != 0) throw InvalidAudioException("Incomplete final Opus packet.")
                    if (!ended && input.read() != -1) throw InvalidAudioException("Unexpected data after Ogg end.")
                    ended = true
                    return null
                }
                readPage()
            }
            while (segment < segments) {
                val size = page[27 + segment++].toInt() and 255
                if (pending.size() + size > MAX_PACKET_BYTES) throw InvalidAudioException("Opus packet is too large.")
                pending.write(page, offset, size)
                offset += size
                if (size < 255) return pending.toByteArray().also { pending.reset() }
            }
        }
    }

    private fun readPage() {
        val fixed = exact(27)
        if (!fixed.startsWithAscii("OggS") || fixed[4].toInt() != 0) throw InvalidAudioException("Expected an Ogg page.")
        flags = fixed[5].toInt() and 255
        if (flags and 0xf8 != 0 || (flags and 1 != 0) != (pending.size() != 0))
            throw InvalidAudioException("Invalid Ogg continuation.")
        val fields = ByteBuffer.wrap(fixed).order(ByteOrder.LITTLE_ENDIAN)
        granule = fields.getLong(6)
        val pageSerial = fields.getInt(14)
        val pageSequence = fields.getInt(18)
        if (serial == null) {
            if (flags != 2 || pageSequence != 0 || granule != 0L) throw InvalidAudioException("Missing Ogg beginning.")
            serial = pageSerial
        } else if (flags and 2 != 0 || serial != pageSerial) throw InvalidAudioException("Unexpected Ogg stream.")
        if (pageSequence != sequence++) throw InvalidAudioException("Missing or reordered Ogg page.")
        segments = fixed[26].toInt() and 255
        val lacing = exact(segments)
        if (flags and 4 != 0 && (segments == 0 || lacing.last().toInt() and 255 == 255))
            throw InvalidAudioException("Incomplete Ogg end page.")
        val size = lacing.sumOf { it.toInt() and 255 }
        page = fixed + lacing + exact(size)
        val expectedCrc = fields.getInt(22)
        page.fill(0, 22, 26)
        if (checksum(page) != expectedCrc) throw InvalidAudioException("Ogg checksum mismatch.")
        if (granule < -1 || (granule >= 0 && granule < previousGranule) || (flags and 4 != 0 && granule < 0))
            throw InvalidAudioException("Invalid Ogg granule position.")
        if (granule >= 0) previousGranule = granule
        offset = 27 + segments
        segment = 0
    }

    private fun exact(size: Int): ByteArray {
        if (bytesRead + size > MAX_AUDIO_BYTES) throw InvalidAudioException("Opus response is too large.")
        val result = ByteArray(size)
        var position = 0
        while (position < size) {
            checkCancelled()
            val count = input.read(result, position, size - position)
            if (count <= 0) throw InvalidAudioException("Ogg stream ended early.")
            position += count
        }
        bytesRead += size
        return result
    }

    companion object {
        const val SAMPLE_RATE = 48000
        private const val MAX_PACKET_BYTES = 65536
        private const val MAX_AUDIO_BYTES = 32L * 1024 * 1024
        private val CRC_TABLE = IntArray(256) { value ->
            var crc = value shl 24
            repeat(8) { crc = (crc shl 1) xor (if (crc < 0) 0x04c11db7 else 0) }
            crc
        }

        internal fun checksum(bytes: ByteArray): Int {
            var crc = 0
            for (byte in bytes) crc = (crc shl 8) xor CRC_TABLE[((crc ushr 24) xor (byte.toInt() and 255)) and 255]
            return crc
        }

        private fun ByteArray.startsWithAscii(value: String): Boolean =
            size >= value.length && value.indices.all { this[it].toInt() == value[it].code }
    }
}
