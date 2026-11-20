package id.larus.mqttgroup

import id.larus.mqttgroup.crypto.Aead
import id.larus.mqttgroup.crypto.Envelope
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.UUID

class EnvelopeTest {

    private val key = Aead.randomKey()
    private val sender = UUID.randomUUID()
    private val body = """{"t":"msg","body":"on my way"}""".toByteArray()

    @Test
    fun `round trips with its header intact`() {
        val wire = Envelope.seal(key, epoch = 0, senderId = sender, counter = 42, plaintext = body)
        val opened = requireNotNull(Envelope.open(key, wire))
        assertEquals(0, opened.epoch)
        assertEquals(sender, opened.senderId)
        assertEquals(42L, opened.counter)
        assertArrayEquals(body, opened.plaintext)
    }

    @Test
    fun `the header can be read without the key`() {
        // This is what lets a receiver pick the right epoch's key, and drop a replayed counter,
        // before spending any work on decryption.
        val wire = Envelope.seal(key, epoch = 3, senderId = sender, counter = 7, plaintext = body)
        val header = requireNotNull(Envelope.peek(wire))
        assertEquals(3, header.epoch)
        assertEquals(sender, header.senderId)
        assertEquals(7L, header.counter)
    }

    @Test
    fun `rewriting the header breaks the payload`() {
        // The header is authenticated as AAD, so a broker that renames the sender or rewinds
        // the counter produces something that no longer opens at all.
        val wire = Envelope.seal(key, epoch = 0, senderId = sender, counter = 5, plaintext = body)
        wire[Envelope.HEADER_BYTES - 1] = 4
        assertNull(Envelope.open(key, wire))
    }

    @Test
    fun `foreign and malformed payloads are rejected, not crashed on`() {
        assertNull(Envelope.open(key, ByteArray(0)))
        assertNull(Envelope.open(key, "not an envelope at all".toByteArray()))
        assertNull(Envelope.peek(ByteArray(Envelope.HEADER_BYTES)))
    }

    @Test
    fun `another group's key does not open it`() {
        val wire = Envelope.seal(key, epoch = 0, senderId = sender, counter = 1, plaintext = body)
        assertNull(Envelope.open(Aead.randomKey(), wire))
    }

    @Test
    fun `epochs outside a byte are refused at seal time`() {
        val error = runCatching {
            Envelope.seal(key, epoch = 256, senderId = sender, counter = 0, plaintext = body)
        }.exceptionOrNull()
        assertEquals(IllegalArgumentException::class.java, error?.javaClass)
    }
}
