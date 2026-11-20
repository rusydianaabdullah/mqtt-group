package id.larus.mqttgroup

import id.larus.mqttgroup.crypto.Aead
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import javax.crypto.AEADBadTagException

class AeadTest {

    private val key = Aead.randomKey()
    private val message = "meet me at the usual place".toByteArray()

    @Test
    fun `round trips`() {
        assertArrayEquals(message, Aead.open(key, Aead.seal(key, message)))
    }

    @Test
    fun `sealing twice gives different bytes`() {
        // A fresh nonce each time, so identical plaintexts do not look identical on the wire —
        // otherwise an observer learns when the same message is sent again.
        val first = Aead.seal(key, message)
        val second = Aead.seal(key, message)
        assertNotEquals(first.toList(), second.toList())
        assertArrayEquals(message, Aead.open(key, second))
    }

    @Test
    fun `the wrong key does not open it`() {
        val sealed = Aead.seal(key, message)
        assertThrows(AEADBadTagException::class.java) { Aead.open(Aead.randomKey(), sealed) }
    }

    @Test
    fun `a flipped bit is detected`() {
        val sealed = Aead.seal(key, message)
        sealed[sealed.size - 3] = (sealed[sealed.size - 3].toInt() xor 0x01).toByte()
        assertThrows(AEADBadTagException::class.java) { Aead.open(key, sealed) }
    }

    @Test
    fun `associated data is bound to the ciphertext`() {
        val sealed = Aead.seal(key, message, aad = "group-a".toByteArray())
        assertThrows(AEADBadTagException::class.java) {
            Aead.open(key, sealed, aad = "group-b".toByteArray())
        }
        assertArrayEquals(message, Aead.open(key, sealed, aad = "group-a".toByteArray()))
    }

    @Test
    fun `truncated payloads are rejected before decryption`() {
        assertThrows(IllegalArgumentException::class.java) { Aead.open(key, ByteArray(8)) }
    }

    @Test
    fun `keys are the size the format expects`() {
        assertEquals(32, Aead.randomKey().size)
    }
}
