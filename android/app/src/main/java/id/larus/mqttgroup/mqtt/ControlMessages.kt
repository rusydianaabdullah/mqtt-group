package id.larus.mqttgroup.mqtt

import org.json.JSONObject
import java.util.Base64

/**
 * The plaintext inside a control envelope. These are the only things that change a group's
 * membership, so each one carries who it is about and is judged against who sent it.
 *
 * There is no server to arbitrate, so authority is a local rule: a kick or a delete is obeyed
 * only when the sender is the group's admin. That is honest but limited — a member who keeps
 * the group key can ignore a kick and carry on reading, because the key is still the key. Real
 * revocation is key rotation, which is phase 7. The UI should not imply otherwise.
 */
sealed interface Control {

    /** "I am here, and this is what I look like." Sent on join and whenever the profile changes. */
    data class Profile(
        val userId: String,
        val displayName: String,
        val avatar: ByteArray?,
    ) : Control {
        override fun equals(other: Any?): Boolean = other is Profile &&
            userId == other.userId && displayName == other.displayName &&
            (avatar ?: ByteArray(0)).contentEquals(other.avatar ?: ByteArray(0))

        override fun hashCode(): Int = userId.hashCode() * 31 + displayName.hashCode()
    }

    /** Admin only. Everyone else's copy of the member list acts on it. */
    data class Kick(val targetUserId: String) : Control

    /** Sent by whoever is leaving, about themselves. */
    data class Leave(val userId: String) : Control

    /** Admin only. The group is over; every phone drops its messages and its key. */
    data object Delete : Control

    /**
     * Admin only. The group's location rules: whether sharing is on at all, how often each
     * phone takes a fix, how often it publishes one, how far it must have moved first, and how
     * long fixes are kept.
     *
     * These are the group's settings rather than each phone's preference on purpose — a family
     * group where everyone picks their own interval produces a map where some people are five
     * minutes stale and others thirty, and nobody can tell which. One admin-set rate makes
     * "last seen 3 minutes ago" mean the same thing for everyone on the screen.
     *
     * What it deliberately does *not* carry is who shares. Turning this on does not put anyone
     * on the map; each member still has their own switch, off until they touch it. An admin
     * sets the tempo, not the consent.
     */
    data class LocationSettings(
        val enabled: Boolean,
        val sampleSec: Int,
        val pushSec: Int,
        val minDistanceM: Int,
        val retentionHours: Int,
    ) : Control

    /** "I have this message." Sent automatically the moment a chat message is stored, so the
     * sender's bubble can move from a single check to a double one. */
    data class Delivered(val messageId: String) : Control

    /** "I have looked at this message." Sent when the chat screen for its group is opened,
     * never automatically — read status is the one receipt a person can actually feel watched
     * by, so it only fires from an actual open, not from merely receiving. */
    data class Read(val messageId: String) : Control

    fun encode(): ByteArray {
        val json = JSONObject().put(TYPE, typeName())
        when (this) {
            is Profile -> {
                json.put(USER, userId).put(NAME, displayName)
                avatar?.let { json.put(AVATAR, base64.encodeToString(it)) }
            }
            is Kick -> json.put(TARGET, targetUserId)
            is LocationSettings -> json
                .put(ENABLED, enabled)
                .put(SAMPLE, sampleSec)
                .put(PUSH, pushSec)
                .put(DISTANCE, minDistanceM)
                .put(RETENTION, retentionHours)
            is Leave -> json.put(USER, userId)
            Delete -> Unit
            is Delivered -> json.put(MESSAGE, messageId)
            is Read -> json.put(MESSAGE, messageId)
        }
        return json.toString().toByteArray()
    }

    private fun typeName(): String = when (this) {
        is Profile -> "profile"
        is Kick -> "kick"
        is LocationSettings -> "locset"
        is Leave -> "leave"
        Delete -> "delete"
        is Delivered -> "delivered"
        is Read -> "read"
    }

    companion object {
        private const val TYPE = "t"
        private const val USER = "u"
        private const val NAME = "n"
        private const val AVATAR = "a"
        private const val TARGET = "x"
        private const val MESSAGE = "m"
        private const val ENABLED = "e"
        private const val SAMPLE = "s"
        private const val PUSH = "p"
        private const val DISTANCE = "d"
        private const val RETENTION = "r"

        /** Ten seconds is as fast as this app will ever poll the GPS, admin or not. */
        const val MIN_SAMPLE_SEC = 10
        /** Six hours. Past this an interval is indistinguishable from "off", and saying off is
         * clearer than a number nobody will wait out. */
        const val MAX_SEC = 6 * 60 * 60
        const val MAX_DISTANCE_M = 1_000
        /** One year. 0 is the separate, explicit "keep forever". */
        const val MAX_RETENTION_HOURS = 24 * 365

        private val base64: Base64.Encoder = Base64.getEncoder().withoutPadding()
        private val decoder: Base64.Decoder = Base64.getDecoder()

        /** Returns null rather than throwing: a malformed control message is one to drop. */
        fun decode(payload: ByteArray): Control? = try {
            val json = JSONObject(String(payload))
            when (json.getString(TYPE)) {
                "profile" -> Profile(
                    userId = json.getString(USER),
                    displayName = json.getString(NAME),
                    avatar = json.optString(AVATAR).ifEmpty { null }?.let(decoder::decode),
                )
                "kick" -> Kick(json.getString(TARGET))
                // Clamped on the way in, not merely on the way out. An admin's slider cannot
                // produce a zero sample interval, but a modified build could, and a zero here
                // becomes a location request that never sleeps — a flat battery by lunchtime on
                // every phone in the group. The bounds are the same ones the settings UI offers.
                "locset" -> LocationSettings(
                    enabled = json.optBoolean(ENABLED, false),
                    sampleSec = json.optInt(SAMPLE, 60).coerceIn(MIN_SAMPLE_SEC, MAX_SEC),
                    pushSec = json.optInt(PUSH, 300).coerceIn(MIN_SAMPLE_SEC, MAX_SEC),
                    minDistanceM = json.optInt(DISTANCE, 25).coerceIn(0, MAX_DISTANCE_M),
                    retentionHours = json.optInt(RETENTION, 168).coerceIn(0, MAX_RETENTION_HOURS),
                )
                "leave" -> Leave(json.getString(USER))
                "delete" -> Delete
                "delivered" -> Delivered(json.getString(MESSAGE))
                "read" -> Read(json.getString(MESSAGE))
                else -> null
            }
        } catch (_: Exception) {
            null
        }
    }
}
