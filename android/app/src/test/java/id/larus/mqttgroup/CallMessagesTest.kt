package id.larus.mqttgroup

import id.larus.mqttgroup.mqtt.Call
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CallMessagesTest {

    @Test
    fun `round-trips every message`() {
        val messages = listOf(
            Call.Offer("c-1", "u-2", Call.Mode.AUDIO, 45),
            Call.Offer("c-1", null, Call.Mode.AUDIO, 45),
            Call.Ring("c-1"),
            Call.Accept("c-1"),
            Call.Decline("c-1", Call.Reason.DECLINED),
            Call.Decline("c-1", Call.Reason.BUSY),
            Call.Cancel("c-1"),
            Call.End("c-1"),
        )
        for (message in messages) {
            assertEquals(message, Call.decode(message.encode()))
        }
    }

    @Test
    fun `drops what it cannot parse`() {
        assertNull(Call.decode("not json".toByteArray()))
        assertNull(Call.decode("{}".toByteArray()))
        // No call id is no call.
        assertNull(Call.decode("""{"t":"ring"}""".toByteArray()))
        // An older build must ignore a newer one's traffic rather than guess at it.
        assertNull(Call.decode("""{"t":"transfer","c":"c-1"}""".toByteArray()))
    }

    /**
     * An offer with no target rings the whole group — phase 10. It used to be malformed, which
     * is why an older build drops a group offer rather than misreading it, and why this test
     * changed rather than the format.
     */
    @Test
    fun `an offer with no target is a group call`() {
        val offer = Call.decode("""{"t":"offer","c":"c-1","m":"audio","l":45}""") as Call.Offer
        assertNull(offer.toUserId)
        assertEquals(Call.Mode.AUDIO, offer.mode)
    }

    /** An empty target is not "everyone" — it is a call to nobody, and a mistake either way. */
    @Test
    fun `a group offer round-trips without inventing a target`() {
        val offer = Call.Offer("c-1", null, Call.Mode.AUDIO, 45)
        assertEquals(offer, Call.decode(offer.encode()))
        assertNull(String(offer.encode()).contains("\"x\"").takeIf { it })
    }

    @Test
    fun `drops an offer missing the mode, which decides what to do with it`() {
        assertNull(Call.decode("""{"t":"offer","c":"c-1","x":"u-2","l":45}""".toByteArray()))
    }

    /** Adding video later must not make this build answer a video call with its microphone. */
    @Test
    fun `drops an offer in a mode it does not know`() {
        assertNull(Call.decode("""{"t":"offer","c":"c-1","x":"u-2","m":"video","l":45}""".toByteArray()))
    }

    /**
     * A ttl is not a slider anywhere in the UI, so these can only come from a modified build —
     * which is exactly why they are clamped rather than trusted. A day-long ttl is a phone that
     * rings until the battery dies; a zero is an offer that expires before it can be shown.
     */
    @Test
    fun `clamps a ttl it would not have sent`() {
        val huge = Call.decode("""{"t":"offer","c":"c","x":"u","m":"audio","l":86400}""") as Call.Offer
        assertEquals(Call.MAX_TTL_SEC, huge.ttlSec)
        val zero = Call.decode("""{"t":"offer","c":"c","x":"u","m":"audio","l":0}""") as Call.Offer
        assertEquals(Call.MIN_TTL_SEC, zero.ttlSec)
        val missing = Call.decode("""{"t":"offer","c":"c","x":"u","m":"audio"}""") as Call.Offer
        assertEquals(Call.DEFAULT_TTL_SEC, missing.ttlSec)
    }

    /** Only the wording is lost, never the fact that it was a no. */
    @Test
    fun `an unrecognised decline reason is still a decline`() {
        assertEquals(
            Call.Decline("c-1", Call.Reason.DECLINED),
            Call.decode("""{"t":"decline","c":"c-1","r":"meeting"}""".toByteArray()),
        )
    }

    private fun Call.Companion.decode(json: String) = decode(json.toByteArray())
}
