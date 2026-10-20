package com.fishaudio.tts

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Real FFmpeg output anchors CRC/duration checks; synthetic pages exercise Ogg framing errors. */
class OggOpusReaderTest {
    private fun fixture() = javaClass.getResourceAsStream("/tone-20000.opus")!!.use { it.readBytes() }
    private val tags = "OpusTags".toByteArray() + ByteArray(8)

    @Test fun readsRealOpusIncrementallyAcrossOddNetworkBoundaries() {
        val bytes = fixture()
        val source = ByteArrayInputStream(bytes)
        val input = object : FilterInputStream(source) {
            override fun read(buffer: ByteArray, offset: Int, length: Int) = super.read(buffer, offset, minOf(length, 3))
        }
        var checks = 0
        val reader = OggOpusReader(input) { checks++ }
        assertTrue("Headers must not consume the whole response.", source.available() > bytes.size / 2)
        assertEquals(312, reader.preSkip)
        val packets = generateSequence { reader.nextPacket() }.toList()
        assertTrue(packets.size > 100)
        assertTrue(packets.all { it.samples == 960 })
        assertEquals(97776L + reader.preSkip, packets.last().endGranule)
        assertTrue(packets.sumOf { it.samples } >= packets.last().endGranule!!)
        assertEquals(0, source.available())
        assertTrue(checks > bytes.size / 3)
    }

    @Test fun reassemblesAnOpusPacketContinuedAcrossPages() {
        val real = OggOpusReader(ByteArrayInputStream(fixture())) {}
        val data = real.nextPacket()!!.data
        val largeTags = tags + ByteArray(284)
        val bytes = page(0, 2, 0, intArrayOf(19), real.header) +
            page(1, 0, -1, intArrayOf(255), largeTags.copyOfRange(0, 255)) +
            page(2, 1, 0, intArrayOf(45), largeTags.copyOfRange(255, 300)) +
            page(3, 4, 960, intArrayOf(data.size), data)
        val reader = OggOpusReader(ByteArrayInputStream(bytes)) {}
        assertArrayEquals(data, reader.nextPacket()!!.data)
        assertNull(reader.nextPacket())
    }

    @Test fun rejectsCorruptionTruncationAndMissingEndPages() {
        val bytes = fixture()
        val finalPage = pageOffsets(bytes).last()
        val invalid = listOf(
            byteArrayOf(), "RIFF is not Opus".toByteArray(), bytes.copyOf(26),
            bytes.copyOf(47), bytes.copyOf(finalPage), bytes.copyOf(bytes.size - 1),
            bytes.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() },
            bytes + byteArrayOf(1), bytes + bytes,
        )
        for (value in invalid) assertThrows(InvalidAudioException::class.java) { readAll(value) }
    }

    @Test fun rejectsStereoUnsupportedMappingAndBrokenPageContinuity() {
        val bytes = fixture()
        val pages = pageOffsets(bytes)
        val invalid = listOf(
            editPage(bytes, 0) { it[28 + 9] = 2 },
            editPage(bytes, 0) { it[28 + 18] = 1 },
            editPage(bytes, pages[1]) { it[5] = 1 },
            editPage(bytes, pages[1]) { it[18] = 7 },
            editPage(bytes, pages[1]) { it[14] = (it[14].toInt() xor 1).toByte() },
            editPage(bytes, pages.last()) { ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).putLong(6, Long.MAX_VALUE) },
        )
        for (value in invalid) assertThrows(InvalidAudioException::class.java) { readAll(value) }
    }

    @Test fun rejectsUnboundedPacketsAndInvalidPacketDurations() {
        val reader = OggOpusReader(ByteArrayInputStream(fixture())) {}
        val headers = page(0, 2, 0, intArrayOf(19), reader.header) + page(1, 0, 0, intArrayOf(16), tags)
        val oversized = headers + page(2, 0, -1, IntArray(255) { 255 }, ByteArray(65025)) +
            page(3, 1, -1, intArrayOf(255, 255, 255), ByteArray(765))
        assertThrows(InvalidAudioException::class.java) { readAll(oversized) }
        for (data in listOf(byteArrayOf(), byteArrayOf(3), byteArrayOf(3, 0), byteArrayOf(3, 63))) {
            val value = headers + page(2, 4, 960, intArrayOf(data.size), data)
            assertThrows(InvalidAudioException::class.java) { readAll(value) }
        }
    }

    @Test fun cancellationAndNetworkTimeoutsEscapeWithoutBeingReclassifiedAsAudioErrors() {
        val failure = SocketTimeoutException("fixture timeout")
        val input = object : InputStream() { override fun read(): Int = throw failure }
        assertSame(failure, assertThrows(SocketTimeoutException::class.java) { OggOpusReader(input) {} })
        val source = ByteArrayInputStream(fixture())
        assertThrows(InterruptedException::class.java) { OggOpusReader(source) { throw InterruptedException() } }
        assertEquals(fixture().size, source.available())
    }

    private fun readAll(bytes: ByteArray) {
        val reader = OggOpusReader(ByteArrayInputStream(bytes)) {}
        while (reader.nextPacket() != null) { /* Consume and validate the final page and EOF. */ }
    }

    private fun page(sequence: Int, flags: Int, granule: Long, lacing: IntArray, body: ByteArray): ByteArray {
        val result = ByteBuffer.allocate(27 + lacing.size + body.size).order(ByteOrder.LITTLE_ENDIAN)
            .put("OggS".toByteArray()).put(0).put(flags.toByte()).putLong(granule).putInt(42).putInt(sequence)
            .putInt(0).put(lacing.size.toByte()).put(lacing.map { it.toByte() }.toByteArray()).put(body).array()
        ByteBuffer.wrap(result).order(ByteOrder.LITTLE_ENDIAN).putInt(22, OggOpusReader.checksum(result))
        return result
    }

    private fun pageOffsets(bytes: ByteArray): List<Int> {
        val offsets = mutableListOf<Int>()
        var offset = 0
        while (offset < bytes.size) {
            offsets.add(offset)
            val segments = bytes[offset + 26].toInt() and 255
            offset += 27 + segments + (0 until segments).sumOf { bytes[offset + 27 + it].toInt() and 255 }
        }
        return offsets
    }

    private fun editPage(bytes: ByteArray, offset: Int, edit: (ByteArray) -> Unit): ByteArray {
        val end = pageOffsets(bytes).firstOrNull { it > offset } ?: bytes.size
        val page = bytes.copyOfRange(offset, end)
        edit(page)
        page.fill(0, 22, 26)
        ByteBuffer.wrap(page).order(ByteOrder.LITTLE_ENDIAN).putInt(22, OggOpusReader.checksum(page))
        return bytes.copyOf().also { page.copyInto(it, offset) }
    }
}
