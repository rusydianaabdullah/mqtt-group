package id.larus.mqttgroup

import id.larus.mqttgroup.crypto.Aead
import id.larus.mqttgroup.crypto.Invite
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InviteTest {

    private fun invite(key: ByteArray = Aead.randomKey()) = Invite(
        groupId = "9f1c2a3e-0000-4000-8000-000000000001",
        groupName = "Keluarga",
        adminId = "9f1c2a3e-0000-4000-8000-0000000000aa",
        epoch = 0,
        groupKey = key,
        host = "mqtt.example.org",
        port = 8883,
        tls = true,
        allowInsecureTls = false,
        username = "family",
        password = "s3cret",
        topicPrefix = "familygroup",
    )

    @Test
    fun `round trips every field`() {
        val original = invite()
        val decoded = requireNotNull(Invite.decode(original.encode()))
        assertEquals(original, decoded)
        assertArrayEquals(original.groupKey, decoded.groupKey)
    }

    @Test
    fun `optional credentials survive being absent`() {
        val anonymous = invite().copy(username = null, password = null)
        val decoded = requireNotNull(Invite.decode(anonymous.encode()))
        assertNull(decoded.username)
        assertNull(decoded.password)
    }

    @Test
    fun `codes are tagged so a scanner can tell ours apart`() {
        assertTrue(invite().encode().startsWith(Invite.PREAMBLE))
    }

    @Test
    fun `anything else scans as nothing`() {
        // The scanner points at whatever the user aims it at, so these are the ordinary case.
        assertNull(Invite.decode("https://example.com"))
        assertNull(Invite.decode("WIFI:S:home;T:WPA;P:hunter2;;"))
        assertNull(Invite.decode(""))
        assertNull(Invite.decode("FG1:not-base64!!"))
        assertNull(Invite.decode("FG2:" + invite().encode().removePrefix(Invite.PREAMBLE)))
    }

    @Test
    fun `a truncated scan is rejected rather than half-read`() {
        val whole = invite().encode()
        assertNull(Invite.decode(whole.substring(0, whole.length / 2)))
    }

    @Test
    fun `a key of the wrong size is refused`() {
        // Letting a short key through would fail later, somewhere far less obvious than here.
        val shortKey = invite(ByteArray(16)).encode()
        assertNull(Invite.decode(shortKey))
    }

    @Test
    fun `fits comfortably in a QR code`() {
        // Version 40-L holds 2953 bytes; staying well under keeps the modules big enough to
        // scan from a phone screen held at arm's length.
        assertTrue(invite().encode().length < 500)
    }
}
