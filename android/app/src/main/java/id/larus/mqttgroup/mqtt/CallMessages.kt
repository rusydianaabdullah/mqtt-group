package id.larus.mqttgroup.mqtt

import org.json.JSONObject

/**
 * The plaintext inside a call envelope: the signalling for one call, and no media at all.
 *
 * Kept apart from [Control] rather than added to it because the two have opposite lifetimes.
 * A control message is a durable fact about a group — who is a member, what the location rules
 * are — and is worth applying whenever it arrives. These are the opposite: they expire, they
 * are worthless late, and replaying one rings a phone about a call that is long over. The
 * topic they travel on is never retained for the same reason.
 *
 * **Nobody's identity is in the body.** Who is calling is [id.larus.mqttgroup.crypto.Envelope]'s
 * authenticated sender, exactly as for a profile announcement, so no member can publish a ring
 * on another member's behalf. What that still does not buy is authenticity *between* members:
 * a shared group key means any member can forge an offer that appears to come from any other
 * member, unchanged from every other message in this app. The incoming-call UI must not claim
 * more certainty about who is calling than the rest of the app does.
 */
sealed interface Call {

    /** Which call this is about. Minted by the caller, named by every later message. */
    val callId: String

    /**
     * "I am calling you." Sent once, by the caller.
     *
     * [toUserId] is the one member being dialled, or **null to ring the whole group**. Both are
     * ordinary: a direct call names a member, a group call names nobody and every member rings.
     * Everyone who is not being rung decrypts this and ignores it.
     *
     * An older build refuses to decode an offer with no target, so it does not ring for a group
     * call rather than misreading one — which is the right way round for this to degrade.
     */
    data class Offer(
        override val callId: String,
        val toUserId: String?,
        val mode: Mode,
        /**
         * How long this offer is good for, **as a duration and never as a deadline**.
         *
         * Each side counts it from when it locally minted or received the offer, never from a
         * timestamp in the body compared against the local clock. Two phones in one family are
         * routinely tens of seconds apart and occasionally hours; a wall-clock deadline gives
         * either a ring that never starts or one that never stops, and the bug only ever shows
         * up on somebody else's phone. The cost is that an offer delayed in the network rings
         * for its full life late rather than being discarded on arrival — which is what [Ring]
         * is for, since it is the caller who can tell the difference.
         */
        val ttlSec: Int,
    ) : Call

    /**
     * "Your offer reached me and I am ringing."
     *
     * The caller's UI says "calling" until this arrives and "ringing" afterwards, which is the
     * difference between having published into the void and having reached a phone that is
     * awake. With a browser tab that may be closed, or a phone that may be dozing, that is the
     * single most useful thing a caller can be told.
     */
    data class Ring(override val callId: String) : Call

    /**
     * "I am joining this call." Sent by whoever answers, and by anyone already in the room when
     * somebody new joins — which is how a late joiner learns who is already there, since
     * nothing on this topic is retained. See plan.md, phase 10.
     *
     * For two people this is simply "I am picking up", which is what it meant in phase 8.
     */
    data class Accept(override val callId: String) : Call

    /** "I am not picking up." [Reason.BUSY] is sent automatically when another call is already
     * up; [Reason.DECLINED] is a person pressing the red button. */
    data class Decline(override val callId: String, val reason: Reason) : Call

    /** "Never mind." Sent by the caller, before any answer. */
    data class Cancel(override val callId: String) : Call

    /**
     * **"I have left."** Sent by anyone in a connected call, and the reason it is not another
     * [Cancel]: before an answer the caller cancels and the callee declines, and after one
     * there is no caller and no callee, only people in a room.
     *
     * For two people "I have left" and "the call is over" are the same sentence, which is why
     * phase 8 could read it as the latter. For a room they are not — one person leaving is not
     * the room closing — so phase 10 reads it as the former. No bytes changed; only the
     * meaning, and only in the direction that was always more precise.
     */
    data class End(override val callId: String) : Call

    /**
     * What kind of call this is. An unknown mode is dropped rather than assumed, which is what
     * lets a new one be added without an older build misreading it — it simply does not ring.
     */
    enum class Mode(val wire: String) {
        /**
         * Voice, over the broker. No longer carried: a call cost a steady 36 kbps each way
         * through somebody's broker for as long as people talked, which is a great deal to
         * spend on infrastructure that may be metered or shared, and the microphone and
         * media-playback foreground-service permissions it needed are ones Play asks an app to
         * justify. The capture, codec and jitter-buffer code is gone; this name stays because
         * the wire format is shared with the web app and the vectors pin it, and because a
         * word an older build already knows is safer to keep than to reuse.
         */
        AUDIO("audio"),

        /**
         * Make that phone ring, and nothing else. No microphone, no stream, no bandwidth beyond
         * the handful of signalling messages — "where did I leave it" and "pick up, it is
         * urgent", which is most of what a call was being used for anyway.
         */
        BUZZ("buzz");

        companion object {
            fun from(wire: String?): Mode? = entries.firstOrNull { it.wire == wire }
        }
    }

    enum class Reason(val wire: String) {
        DECLINED("declined"),
        BUSY("busy");

        companion object {
            /** An unrecognised reason is still a decline; only the wording is lost. */
            fun from(wire: String?): Reason = entries.firstOrNull { it.wire == wire } ?: DECLINED
        }
    }

    fun encode(): ByteArray {
        val json = JSONObject().put(TYPE, typeName()).put(CALL, callId)
        when (this) {
            is Offer -> {
                // Absent, not empty: a key that is present and blank is a target nobody has,
                // which an older build would read as a direct call to no one.
                toUserId?.let { json.put(TARGET, it) }
                json.put(MODE, mode.wire).put(LIFE, ttlSec)
            }
            is Decline -> json.put(REASON, reason.wire)
            is Ring, is Accept, is Cancel, is End -> Unit
        }
        return json.toString().toByteArray()
    }

    private fun typeName(): String = when (this) {
        is Offer -> "offer"
        is Ring -> "ring"
        is Accept -> "accept"
        is Decline -> "decline"
        is Cancel -> "cancel"
        is End -> "end"
    }

    companion object {
        private const val TYPE = "t"
        private const val CALL = "c"
        private const val TARGET = "x"
        private const val MODE = "m"
        private const val LIFE = "l"
        private const val REASON = "r"

        /** Long enough that someone can reach a phone in another room, short enough that the
         * caller has not given up. Also what the wake-up push's TTL is set to. */
        const val DEFAULT_TTL_SEC = 45

        /** Clamped on the way in, not merely on the way out. This slider does not exist in the
         * UI, but a modified build could send a ttl of a day — a phone that rings until the
         * battery dies — or of zero, an offer that expires before it can be shown. */
        const val MIN_TTL_SEC = 10
        const val MAX_TTL_SEC = 120

        /** Returns null for anything malformed, which is a message to drop. */
        fun decode(payload: ByteArray): Call? = try {
            val json = JSONObject(String(payload))
            val callId = json.optString(CALL).takeIf { it.isNotEmpty() }
            when {
                callId == null -> null
                else -> when (json.optString(TYPE)) {
                    "offer" -> {
                        // A missing target is a group call, not a malformed message.
                        val target = json.optString(TARGET).takeIf { it.isNotEmpty() }
                        val mode = Mode.from(json.optString(MODE).takeIf { it.isNotEmpty() })
                        if (mode == null) null
                        else Offer(
                            callId = callId,
                            toUserId = target,
                            mode = mode,
                            ttlSec = json.optInt(LIFE, DEFAULT_TTL_SEC)
                                .coerceIn(MIN_TTL_SEC, MAX_TTL_SEC),
                        )
                    }
                    "ring" -> Ring(callId)
                    "accept" -> Accept(callId)
                    "decline" -> Decline(callId, Reason.from(json.optString(REASON)))
                    "cancel" -> Cancel(callId)
                    "end" -> End(callId)
                    // An older build ignoring a newer one's traffic, rather than guessing.
                    else -> null
                }
            }
        } catch (_: org.json.JSONException) {
            null
        }
    }
}
