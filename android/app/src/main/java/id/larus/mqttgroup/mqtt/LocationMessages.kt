package id.larus.mqttgroup.mqtt

import org.json.JSONObject

/**
 * The plaintext inside a `loc` envelope: one position, from one person, at one moment.
 *
 * Deliberately the same six raw fields the `location` table stores and nothing else — no map
 * vendor's types, no address, no "place name". Reverse geocoding would mean handing someone
 * else's coordinates to a third party, which is the one thing this app exists not to do; the
 * map draws from numbers and nothing looks them up.
 *
 * Published QoS 0 and retained. Retained is what makes opening the map show everyone
 * immediately instead of an empty screen until the next tick — the broker holds the latest fix
 * per person and replays it on subscribe. QoS 0 because a position that did not arrive is
 * already replaced by a better one: re-delivering a stale fix costs battery to tell someone
 * where you were two intervals ago, which is worse than silence.
 */
data class LocationPayload(
    val lat: Double,
    val lon: Double,
    /** Metres of horizontal uncertainty, as the OS reported it. Drawn as the halo around a pin. */
    val accuracy: Float,
    /** Metres per second, or 0 when the fix carried none. */
    val speed: Float,
    /** Degrees from true north, or 0 when the fix carried none. */
    val bearing: Float,
    /**
     * When the fix was taken, not when it was sent. Those differ by however long the phone was
     * offline, and the difference is the whole reason to carry it: a receiver showing "4 minutes
     * ago" from the send time would confidently mislabel a position from yesterday.
     */
    val capturedAt: Long,
) {
    fun encode(): ByteArray = JSONObject()
        .put(LAT, lat)
        .put(LON, lon)
        .put(ACC, accuracy.toDouble())
        .put(SPD, speed.toDouble())
        .put(BRG, bearing.toDouble())
        .put(AT, capturedAt)
        .toString()
        .toByteArray()

    companion object {
        private const val LAT = "la"
        private const val LON = "lo"
        private const val ACC = "ac"
        private const val SPD = "sp"
        private const val BRG = "br"
        private const val AT = "at"

        /**
         * Returns null rather than throwing, like every other codec here: a malformed position
         * is one to drop.
         *
         * The range checks are not paranoia about our own encoder — they are about what a
         * broker can hand us. A payload only reaches this point after opening under the group
         * key, so it came from a member, but a member running a modified build could still put
         * a latitude of 900 on the wire, and MapLibre's answer to that is a native crash rather
         * than an exception anyone can catch.
         */
        fun decode(plaintext: ByteArray): LocationPayload? = try {
            val json = JSONObject(String(plaintext))
            val lat = json.getDouble(LAT)
            val lon = json.getDouble(LON)
            if (lat.isNaN() || lon.isNaN() || lat < -90.0 || lat > 90.0 || lon < -180.0 || lon > 180.0) {
                null
            } else {
                LocationPayload(
                    lat = lat,
                    lon = lon,
                    accuracy = json.optDouble(ACC, 0.0).toFloat(),
                    speed = json.optDouble(SPD, 0.0).toFloat(),
                    bearing = json.optDouble(BRG, 0.0).toFloat(),
                    capturedAt = json.optLong(AT, System.currentTimeMillis()),
                )
            }
        } catch (_: Exception) {
            null
        }
    }
}
