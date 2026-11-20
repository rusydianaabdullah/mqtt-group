package id.larus.mqttgroup

import id.larus.mqttgroup.mqtt.Control
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ControlMessagesTest {

    @Test
    fun `profile round trips, avatar included`() {
        val avatar = ByteArray(64) { it.toByte() }
        val decoded = Control.decode(Control.Profile("u1", "Ayah", avatar).encode())
        assertEquals(Control.Profile("u1", "Ayah", avatar), decoded)
        assertArrayEquals(avatar, (decoded as Control.Profile).avatar)
    }

    @Test
    fun `a profile without an avatar stays without one`() {
        val decoded = Control.decode(Control.Profile("u1", "Ibu", null).encode())
        assertNull((decoded as Control.Profile).avatar)
    }

    @Test
    fun `kick and leave name who they are about`() {
        assertEquals(Control.Kick("u2"), Control.decode(Control.Kick("u2").encode()))
        assertEquals(Control.Leave("u3"), Control.decode(Control.Leave("u3").encode()))
    }

    @Test
    fun `delete round trips`() {
        assertEquals(Control.Delete, Control.decode(Control.Delete.encode()))
    }

    @Test
    fun `unknown and malformed messages are dropped, not guessed at`() {
        // An older build must ignore what a newer one sends rather than misread it.
        assertNull(Control.decode("""{"t":"promote","u":"u9"}""".toByteArray()))
        assertNull(Control.decode("""{"t":"kick"}""".toByteArray()))
        assertNull(Control.decode("not json".toByteArray()))
        assertNull(Control.decode(ByteArray(0)))
    }
}
