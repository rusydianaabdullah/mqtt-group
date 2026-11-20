package id.larus.mqttgroup

import id.larus.mqttgroup.mqtt.AudioPacket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AudioMessagesTest {

    private fun packet(vararg frames: ByteArray) = AudioPacket(7, 1, frames.toList())

    @Test
    fun `round-trips`() {
        val p = AudioPacket(0x01020304, 42, listOf(byteArrayOf(1, 2, 3), byteArrayOf(4)))
        assertEquals(p, AudioPacket.decode(p.encode()))
    }

    /**
     * The sequence number is unsigned on the wire and signed here. The bits must survive intact
     * even though the number reads as negative, because the browser reads the same bytes as
     * 4294967294 and the two have to agree about which packet this is.
     */
    @Test
    fun `carries a sequence number past the top of a signed int`() {
        val p = AudioPacket(1, -2, listOf(byteArrayOf(9)))
        assertEquals(-2, AudioPacket.decode(p.encode())?.seq)
    }

    @Test
    fun `packs several frames and keeps their order and boundaries`() {
        val p = packet(byteArrayOf(1), byteArrayOf(2, 2), byteArrayOf(3, 3, 3))
        val back = requireNotNull(AudioPacket.decode(p.encode()))
        assertEquals(listOf(1, 2, 3), back.frames.map { it.size })
        assertEquals(3, back.frames[2][0].toInt())
    }

    @Test
    fun `drops what it cannot parse`() {
        assertNull(AudioPacket.decode(ByteArray(0)))
        assertNull(AudioPacket.decode("not an audio packet".toByteArray()))
        // Right length, wrong magic — an older build's traffic, or another protocol entirely.
        assertNull(AudioPacket.decode(ByteArray(32)))
    }

    /** A truncated packet is the ordinary result of a broker or a network cutting one short. */
    @Test
    fun `drops a packet whose frame runs off the end`() {
        val encoded = packet(byteArrayOf(1, 2, 3, 4)).encode()
        assertNull(AudioPacket.decode(encoded.copyOfRange(0, encoded.size - 2)))
    }

    /**
     * Without a bound, a crafted length field is an allocation of whatever size the sender
     * chooses — on a topic every member of the group can publish to.
     */
    @Test
    fun `refuses a frame count and a frame length it could not have sent`() {
        val header = byteArrayOf(0x46, 0x47, 0x41, 0x31, 0, 0, 0, 1, 0, 0, 0, 1)
        assertNull(AudioPacket.decode(header + byteArrayOf(0)))          // zero frames
        assertNull(AudioPacket.decode(header + byteArrayOf(99)))         // more than MAX_FRAMES
        assertNull(
            // One frame claiming 65535 bytes, with none of them present.
            AudioPacket.decode(header + byteArrayOf(1, 0xFF.toByte(), 0xFF.toByte())),
        )
    }

    @Test
    fun `refuses to encode nothing`() {
        val threw = runCatching { AudioPacket(1, 1, emptyList()).encode() }.isFailure
        assertEquals(true, threw)
    }
}
