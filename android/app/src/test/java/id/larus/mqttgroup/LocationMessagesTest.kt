package id.larus.mqttgroup

import id.larus.mqttgroup.mqtt.Control
import id.larus.mqttgroup.mqtt.LocationPayload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocationMessagesTest {

    @Test
    fun `a position round trips`() {
        val payload = LocationPayload(
            lat = -6.2088,
            lon = 106.8456,
            accuracy = 12.5f,
            speed = 3.2f,
            bearing = 271f,
            capturedAt = 1_757_000_000_000L,
        )
        assertEquals(payload, LocationPayload.decode(payload.encode()))
    }

    @Test
    fun `a fix with no speed or bearing keeps its zeroes rather than becoming null`() {
        val payload = LocationPayload(1.0, 2.0, 0f, 0f, 0f, 1_000L)
        val decoded = LocationPayload.decode(payload.encode())
        assertEquals(0f, decoded!!.speed)
        assertEquals(0f, decoded.bearing)
    }

    @Test
    fun `coordinates outside the world are dropped`() {
        // Not defensiveness about our own encoder: these can only come from a modified build
        // inside the group, and MapLibre answers an impossible latitude with a native crash
        // rather than an exception anything can catch.
        assertNull(LocationPayload.decode("""{"la":91.0,"lo":0.0,"at":1}""".toByteArray()))
        assertNull(LocationPayload.decode("""{"la":0.0,"lo":181.0,"at":1}""".toByteArray()))
        assertNull(LocationPayload.decode("""{"la":-90.1,"lo":0.0,"at":1}""".toByteArray()))
        // The edges themselves are real places.
        assertNotNull(LocationPayload.decode("""{"la":90.0,"lo":180.0,"at":1}""".toByteArray()))
    }

    @Test
    fun `malformed positions are dropped, not guessed at`() {
        assertNull(LocationPayload.decode("not json".toByteArray()))
        assertNull(LocationPayload.decode("""{"lo":106.8}""".toByteArray()))
    }

    @Test
    fun `location settings round trip`() {
        val settings = Control.LocationSettings(
            enabled = true,
            sampleSec = 30,
            pushSec = 300,
            minDistanceM = 25,
            retentionHours = 168,
        )
        assertEquals(settings, Control.decode(settings.encode()))
    }

    @Test
    fun `an interval below the floor is clamped on arrival, not obeyed`() {
        // A zero here would be a location request that never sleeps — a flat battery on every
        // phone in the group, from one member running a modified build.
        val decoded = Control.decode(
            """{"t":"locset","e":true,"s":0,"p":0,"d":-5,"r":-1}""".toByteArray(),
        ) as Control.LocationSettings
        assertEquals(Control.MIN_SAMPLE_SEC, decoded.sampleSec)
        assertEquals(Control.MIN_SAMPLE_SEC, decoded.pushSec)
        assertEquals(0, decoded.minDistanceM)
        assertEquals(0, decoded.retentionHours)
    }

    @Test
    fun `an absurdly long interval is clamped too`() {
        val decoded = Control.decode(
            """{"t":"locset","e":true,"s":999999999,"p":999999999,"d":999999,"r":999999999}""".toByteArray(),
        ) as Control.LocationSettings
        assertEquals(Control.MAX_SEC, decoded.sampleSec)
        assertEquals(Control.MAX_DISTANCE_M, decoded.minDistanceM)
        assertEquals(Control.MAX_RETENTION_HOURS, decoded.retentionHours)
    }

    @Test
    fun `settings default to off when fields are missing`() {
        val decoded = Control.decode("""{"t":"locset"}""".toByteArray()) as Control.LocationSettings
        assertTrue(!decoded.enabled)
    }
}
