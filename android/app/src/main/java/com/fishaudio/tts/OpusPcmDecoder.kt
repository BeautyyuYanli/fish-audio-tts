package com.fishaudio.tts

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaFormat
import java.io.InputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit

/**
 * One synthesis owns one platform Opus decoder, released on every exit. Network reads,
 * decoding and PCM callbacks stay on the synthesis thread; stop only cancels the network call.
 * Decode each packet before reading another, so a stalled network cannot hold ready PCM.
 * Apply Ogg pre-skip/end trimming here, with codec pre-skip disabled, including partial packets.
 */
class OpusPcmDecoder {
    fun decode(input: InputStream, maxBufferSize: Int, checkCancelled: () -> Unit,
               start: () -> Unit, emit: (ByteArray, Int) -> Unit) {
        val capacity = minOf(maxBufferSize, 8192) / 2 * 2
        if (capacity < 2) throw InvalidAudioException("PCM output buffer is too small.")
        val ogg = OggOpusReader(input, checkCancelled)
        val codecHeader = ogg.header.copyOf().also { it[10] = 0; it[11] = 0 }
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, OggOpusReader.SAMPLE_RATE, 1).apply {
            setByteBuffer("csd-0", ByteBuffer.wrap(codecHeader))
            setByteBuffer("csd-1", nativeLong(0))
            setByteBuffer("csd-2", nativeLong(80_000_000))
            setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 65536)
        }
        val codec = try { MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS) }
            catch (_: IOException) { throw InvalidAudioException("Opus decoder is unavailable.") }
        try {
            codec.configure(format, null, null, 0)
            codec.start()
            val info = MediaCodec.BufferInfo()
            val buffer = ByteArray(capacity)
            var decodedSamples = 0L
            var queuedSamples = 0L
            var audioBytes = 0L
            var started = false
            var endGranule: Long? = null

            fun drain(timeoutUs: Long): Boolean {
                checkCancelled()
                when (val index = codec.dequeueOutputBuffer(info, timeoutUs)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val output = codec.outputFormat
                        if (output.getInteger(MediaFormat.KEY_SAMPLE_RATE) != OggOpusReader.SAMPLE_RATE ||
                            output.getInteger(MediaFormat.KEY_CHANNEL_COUNT) != 1 ||
                            (output.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                                output.getInteger(MediaFormat.KEY_PCM_ENCODING) != AudioFormat.ENCODING_PCM_16BIT))
                            throw InvalidAudioException("Unexpected Opus decoder output format.")
                        if (!started) { start(); started = true }
                    }
                    in 0..Int.MAX_VALUE -> {
                        try {
                            if (info.size % 2 != 0 || info.size < 0) throw InvalidAudioException("Incomplete PCM frame.")
                            val count = info.size / 2
                            val first = maxOf(decodedSamples, ogg.preSkip.toLong())
                            val last = minOf(decodedSamples + count, endGranule ?: Long.MAX_VALUE)
                            if (last > first) {
                                if (!started) throw InvalidAudioException("Missing PCM format.")
                                val pcm = codec.getOutputBuffer(index) ?: throw InvalidAudioException("Missing PCM buffer.")
                                pcm.position(info.offset + ((first - decodedSamples) * 2).toInt())
                                var remaining = (last - first) * 2
                                audioBytes += remaining
                                if (audioBytes > 64L * 1024 * 1024) throw InvalidAudioException("Decoded audio is too large.")
                                while (remaining > 0) {
                                    checkCancelled()
                                    val length = minOf(remaining, capacity.toLong()).toInt()
                                    pcm.get(buffer, 0, length)
                                    emit(buffer, length)
                                    remaining -= length
                                }
                            }
                            decodedSamples += count
                            return info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        } finally { codec.releaseOutputBuffer(index, false) }
                    }
                }
                return false
            }

            fun queue(data: ByteArray?, timeUs: Long) {
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                while (true) {
                    checkCancelled()
                    val index = codec.dequeueInputBuffer(10_000)
                    if (index >= 0) {
                        val target = codec.getInputBuffer(index) ?: throw InvalidAudioException("Missing Opus input buffer.")
                        target.clear()
                        if (data != null) {
                            if (data.size > target.remaining()) throw InvalidAudioException("Opus packet exceeds decoder capacity.")
                            target.put(data)
                        }
                        codec.queueInputBuffer(index, 0, data?.size ?: 0, timeUs,
                            if (data == null) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0)
                        return
                    }
                    drain(10_000)
                    if (System.nanoTime() >= deadline) throw InvalidAudioException("Opus decoder stalled.")
                }
            }

            while (true) {
                val packet = ogg.nextPacket() ?: break
                endGranule = packet.endGranule
                queue(packet.data, queuedSamples * 1_000_000 / OggOpusReader.SAMPLE_RATE)
                queuedSamples += packet.samples
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                while (decodedSamples < queuedSamples) {
                    drain(10_000)
                    if (System.nanoTime() >= deadline) throw InvalidAudioException("Opus decoder stalled.")
                }
                if (decodedSamples != queuedSamples) throw InvalidAudioException("Unexpected Opus sample count.")
            }
            queue(null, queuedSamples * 1_000_000 / OggOpusReader.SAMPLE_RATE)
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (!drain(10_000)) {
                if (System.nanoTime() >= deadline) throw InvalidAudioException("Opus decoder did not finish.")
            }
            if (audioBytes == 0L) throw InvalidAudioException("Fish Audio returned no audio.")
            if (decodedSamples != queuedSamples || endGranule == null || audioBytes != (endGranule - ogg.preSkip) * 2)
                throw InvalidAudioException("Incomplete decoded Opus audio.")
        } finally { codec.release() }
    }

    private fun nativeLong(value: Long): ByteBuffer = ByteBuffer.wrap(ByteBuffer.allocate(8).order(ByteOrder.nativeOrder()).putLong(value).array())
}
