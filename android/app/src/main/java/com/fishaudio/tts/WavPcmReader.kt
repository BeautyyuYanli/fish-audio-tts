package com.fishaudio.tts

import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class InvalidAudioException(message: String) : IOException(message)

/**
 * Extracts PCM from Fish's documented 16-bit mono WAV response without buffering the utterance.
 * Accepts RIFF ancillary chunks, zero/UINT32_MAX lengths and Fish's paired streaming placeholders,
 * while retaining size limits on bytes actually received. Other finite lengths are checked strictly.
 * Validates frame alignment, format and actual sample rate; compressed or mislabeled audio is rejected.
 * The caller owns the stream and cancellation; all reads/emissions run synchronously on its thread.
 */
class WavPcmReader private constructor(private val input: InputStream, private val dataLength: Long?) {
    fun copyPcm(maxBufferSize: Int, checkCancelled: () -> Unit, emit: (ByteArray, Int) -> Unit): Long {
        val capacity = minOf(maxBufferSize, 8192) / 2 * 2
        if (capacity < 2) throw InvalidAudioException("PCM output buffer is too small.")
        val buffer = ByteArray(capacity)
        var total = 0L
        while (dataLength == null || total < dataLength) {
            checkCancelled()
            val wanted = if (dataLength == null) capacity else minOf(capacity.toLong(), dataLength - total).toInt()
            var count = 0
            while (count < wanted) {
                checkCancelled()
                val read = input.read(buffer, count, wanted - count)
                if (read < 0) break
                if (read == 0) throw InvalidAudioException("Audio stream made no progress.")
                count += read
            }
            if (dataLength != null && count < wanted) throw InvalidAudioException("WAV audio ended early.")
            if (count == 0) break
            if (count % 2 != 0) throw InvalidAudioException("WAV contains an incomplete PCM frame.")
            total += count
            if (total > MAX_AUDIO_BYTES) throw InvalidAudioException("Audio response is too large.")
            checkCancelled()
            emit(buffer, count)
            if (count < wanted) break
        }
        if (total == 0L) throw InvalidAudioException("Fish Audio returned no audio.")
        return total
    }

    companion object {
        const val SAMPLE_RATE = 24000
        private const val MAX_AUDIO_BYTES = 32L * 1024 * 1024
        private const val MAX_HEADER_BYTES = 65536L
        private const val FISH_STREAMING_RIFF_LENGTH = 0xffffff24L
        private const val FISH_STREAMING_DATA_LENGTH = 0xffffff00L

        fun open(input: InputStream, checkCancelled: () -> Unit): WavPcmReader {
            val riff = input.exact(12)
            if (riff.ascii(0, 4) != "RIFF" || riff.ascii(8, 4) != "WAVE") throw InvalidAudioException("Expected a RIFF WAV response.")
            val riffLength = ByteBuffer.wrap(riff, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xffffffffL
            var hasFormat = false
            var headerBytes = 12L
            while (headerBytes <= MAX_HEADER_BYTES) {
                checkCancelled()
                val header = input.exact(8)
                val id = header.ascii(0, 4)
                val length = ByteBuffer.wrap(header, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xffffffffL
                headerBytes += 8
                if (id == "data") {
                    if (!hasFormat) throw InvalidAudioException("WAV format is missing.")
                    // Fish streams valid PCM with these placeholder lengths instead of a final byte count.
                    val fishStreaming = riffLength == FISH_STREAMING_RIFF_LENGTH && length == FISH_STREAMING_DATA_LENGTH
                    val knownLength = length.takeUnless { it == 0L || it == 0xffffffffL || fishStreaming }
                    if (knownLength != null && (knownLength % 2 != 0L || knownLength > MAX_AUDIO_BYTES)) throw InvalidAudioException("Invalid WAV data size.")
                    return WavPcmReader(input, knownLength)
                }
                if (length + headerBytes > MAX_HEADER_BYTES) throw InvalidAudioException("WAV header is too large.")
                val chunk = input.exact(length.toInt())
                if (id == "fmt ") {
                    if (hasFormat || length < 16) throw InvalidAudioException("Invalid WAV format chunk.")
                    val fmt = ByteBuffer.wrap(chunk).order(ByteOrder.LITTLE_ENDIAN)
                    val encoding = fmt.short.toInt() and 0xffff
                    val channels = fmt.short.toInt() and 0xffff
                    val rate = fmt.int
                    val byteRate = fmt.int
                    val alignment = fmt.short.toInt() and 0xffff
                    val bits = fmt.short.toInt() and 0xffff
                    if (encoding != 1 || channels != 1 || rate != SAMPLE_RATE || byteRate != SAMPLE_RATE * 2 || alignment != 2 || bits != 16)
                        throw InvalidAudioException("Expected 24000 Hz, mono, 16-bit PCM WAV.")
                    hasFormat = true
                }
                if (length % 2 != 0L) input.exact(1)
                headerBytes += length + length % 2
            }
            throw InvalidAudioException("WAV data chunk is missing.")
        }

        private fun InputStream.exact(length: Int): ByteArray {
            val bytes = ByteArray(length)
            var offset = 0
            while (offset < length) {
                val count = read(bytes, offset, length - offset)
                if (count < 0) throw InvalidAudioException("WAV header ended early.")
                if (count == 0) throw InvalidAudioException("Audio stream made no progress.")
                offset += count
            }
            return bytes
        }

        private fun ByteArray.ascii(offset: Int, length: Int): String = String(this, offset, length, Charsets.US_ASCII)
    }
}
