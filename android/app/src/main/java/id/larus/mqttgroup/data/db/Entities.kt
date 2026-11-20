package id.larus.mqttgroup.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * The whole schema, in one file because the tables only make sense together.
 *
 * Everything here lives inside the SQLCipher-encrypted database, so no column needs its own
 * encryption — including the broker password and the wrapped group keys, which get a second
 * layer from [id.larus.mqttgroup.crypto.KeyVault] anyway.
 *
 * Timestamps are epoch milliseconds. Ids that travel between phones are UUID strings, so a
 * group id can go into an MQTT topic without escaping.
 */

/** This phone's user. Exactly one row, id 0, created on first launch. */
@Entity(tableName = "identity")
data class IdentityEntity(
    @PrimaryKey val id: Int = SINGLETON,
    val userId: String,
    val displayName: String,
    val avatarBlobId: String?,
    val createdAt: Long,
) {
    companion object { const val SINGLETON = 0 }
}

/** An MQTT broker. Phase 2 fills this in; phase 7 allows more than one. */
@Entity(tableName = "broker")
data class BrokerEntity(
    @PrimaryKey val id: String,
    val label: String,
    val host: String,
    val port: Int,
    val tls: Boolean,
    /** Accept any server certificate. For a self-signed broker on a home LAN, and nothing else. */
    val allowInsecureTls: Boolean,
    val username: String?,
    val password: String?,
    val topicPrefix: String,
    val createdAt: Long,
)

@Entity(tableName = "group_chat")
data class GroupEntity(
    @PrimaryKey val groupId: String,
    val name: String,
    val brokerId: String,
    val topicPrefix: String,
    /** Which [GroupKeyEntity] generation this group currently publishes with. */
    val keyEpoch: Int,
    val isAdmin: Boolean,
    val createdAt: Long,
    /** Our own publish counter, so a restart never reuses one. */
    val sendCounter: Long = 0,

    // --- Location, phase 5 -------------------------------------------------------------------
    // The five `loc*` columns below are the *group's* settings, set by the admin and carried to
    // every other phone by [id.larus.mqttgroup.mqtt.Control.LocationSettings]. They describe how
    // often to look and how often to speak, not whether this phone participates at all — that is
    // [shareLocation], which is deliberately the one location column that never travels.

    /** Whether the admin has turned location on for this group at all. Off means the map tab is
     * still there but says so, and no phone samples anything. */
    val locationEnabled: Boolean = false,
    /** How often to ask the OS for a fix, in seconds. The cheaper of the two knobs: a fix that
     * is taken and not published still feeds the local trail and the movement gate. */
    val locSampleSec: Int = 60,
    /** How often a fix is actually published to the group, in seconds. Always >= [locSampleSec]
     * in practice; the engine clamps it if an admin sets it lower. */
    val locPushSec: Int = 300,
    /** Metres of movement required before a publish. 0 publishes every tick. This is where the
     * battery is actually saved — see LocationEngine's publish gate. */
    val locMinDistanceM: Int = 25,
    /** How long stored fixes are kept, in hours. 0 means forever. Enforced per-device, so a
     * member who set "forever" still holds your track after you pruned yours — see plan.md. */
    val locRetentionHours: Int = 168,

    /**
     * Whether *this phone* shares its own position with this group. Local, and the one piece of
     * location state an admin cannot set for someone else: the intervals are the group's rules,
     * but being on the map at all is the member's own answer, and it starts as no.
     *
     * Nothing publishes this. Other members infer it from whether fixes arrive, which is the
     * honest signal anyway — a phone that is off, out of battery or out of signal is
     * indistinguishable from one that has opted out, and pretending otherwise would be a
     * promise the network cannot keep.
     */
    val shareLocation: Boolean = false,
)

/**
 * A group key, sealed by the Keystore. Kept per epoch rather than per group so that rotating a
 * key on kick — phase 7 — can add a row instead of destroying the ability to read old messages.
 */
@Entity(tableName = "group_key", primaryKeys = ["groupId", "epoch"])
data class GroupKeyEntity(
    val groupId: String,
    val epoch: Int,
    val wrappedKey: ByteArray,
    val createdAt: Long,
) {
    override fun equals(other: Any?): Boolean = other is GroupKeyEntity &&
        groupId == other.groupId && epoch == other.epoch && createdAt == other.createdAt &&
        wrappedKey.contentEquals(other.wrappedKey)

    override fun hashCode(): Int =
        ((groupId.hashCode() * 31 + epoch) * 31 + wrappedKey.contentHashCode()) * 31 + createdAt.hashCode()
}

@Entity(tableName = "member", primaryKeys = ["groupId", "userId"])
data class MemberEntity(
    val groupId: String,
    val userId: String,
    val displayName: String,
    val avatarBlobId: String?,
    val isAdmin: Boolean,
    val lastSeenAt: Long,
    /** Kicked or left. Kept as a tombstone so their old messages still show a name. */
    val isRemoved: Boolean = false,
    /**
     * Highest counter seen from them, to drop replays — kept separately per topic kind rather
     * than as one shared value. A sender's counter is one global sequence, but MQTT does not
     * guarantee delivery order *across different topics*, only within one — especially not
     * during session resumption after being offline. A photo publishes its bytes on `/att` and
     * its reference on `/msg` back to back with sequential counters; if the reference (higher
     * counter) is ever applied before the bytes (lower counter) arrive, a single shared
     * high-water mark would make the bytes look like a replay of something already seen and
     * drop them — forever, since nothing re-sends them. Attachments themselves are exempt from
     * this check entirely (see GroupSync) since they are content-addressed and harmless to
     * reapply; these two remain for /msg and /ctl, which do need it.
     */
    val lastCounterMsg: Long = 0,
    val lastCounterCtl: Long = 0,
)

@Entity(
    tableName = "message",
    indices = [Index(value = ["groupId", "createdAt"])],
)
data class MessageEntity(
    @PrimaryKey val id: String,
    val groupId: String,
    val senderId: String,
    /** "text", "image", "audio", "file". */
    val type: String,
    val body: String?,
    val blobId: String?,
    val createdAt: Long,
    /** PENDING, SENT, FAILED, RECEIVED. */
    val state: String,
    val counter: Long,
    /** Whether the person using this phone has opened the chat since this arrived. Meaningless
     * for our own messages — the unread queries all exclude our own senderId anyway. */
    val read: Boolean = false,
    /** The message this one is a reply to, by id. Null for most messages. Not a foreign key —
     * the original may belong to an epoch this phone never had a key for, or may simply not
     * have arrived yet, and a reply should still show up rather than being rejected over it. */
    val replyToId: String? = null,
)

/**
 * One row per (message, other member) once that member has told us they got it or read it.
 * Kept apart from [MessageEntity] because a group has many recipients per message, and this is
 * the only piece of chat state that is inherently one-to-many rather than one row per message.
 */
@Entity(tableName = "message_receipt", primaryKeys = ["messageId", "userId"])
data class MessageReceiptEntity(
    val messageId: String,
    val groupId: String,
    val userId: String,
    /** DELIVERED or READ. READ implies DELIVERED, but both are kept as whichever is latest so a
     * delivery receipt that arrives late never downgrades an already-read message. */
    val state: String,
    val at: Long,
)

@Entity(tableName = "attachment")
data class AttachmentEntity(
    @PrimaryKey val blobId: String,
    val groupId: String?,
    val mime: String,
    val sizeBytes: Long,
    val sha256: String,
    val chunksTotal: Int,
    val chunksHave: Int,
    val createdAt: Long,
)

@Entity(
    tableName = "location",
    indices = [Index(value = ["groupId", "userId", "capturedAt"])],
)
data class LocationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val groupId: String,
    val userId: String,
    val lat: Double,
    val lon: Double,
    val accuracy: Float,
    val speed: Float,
    val bearing: Float,
    val capturedAt: Long,
)

/**
 * Outbound MQTT, durable. Nothing publishes without a row here first, so "did this leave the
 * phone" has exactly one answer even across a crash or a week offline.
 */
@Entity(
    tableName = "outbox",
    indices = [Index(value = ["status", "nextAttemptAt"])],
)
data class OutboxEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val groupId: String,
    val topic: String,
    val payload: ByteArray,
    val qos: Int,
    val retained: Boolean,
    val createdAt: Long,
    val retryCount: Int = 0,
    /** QUEUED, SENDING, FAILED. Rows disappear on success rather than becoming SENT. */
    val status: String = "QUEUED",
    val nextAttemptAt: Long = 0,
    /** The chat message this row is the wire copy of, if it is one — a control or attachment
     * publish leaves this null, since there is no per-message "unsent" indicator to update for
     * either of those. */
    val refMessageId: String? = null,
    /**
     * When this row stops being worth publishing, or null for the rows that never stop — which
     * is almost all of them.
     *
     * The outbox exists so that a message written in a lift still arrives an hour later, and for
     * chat that is exactly right. Call signalling is the one thing here where surviving a dead
     * network is the *wrong* behaviour: a ring delivered twenty minutes late rings a phone about
     * a call that is long over, and an accept delivered after the caller hung up connects
     * nothing. Those rows carry a deadline and [OutboxDao.due] simply stops returning them.
     *
     * A column rather than a second send path, so there is still exactly one answer to "did this
     * leave the phone", and so "this was worthless late" is a property of the row instead of a
     * special case at each call site.
     */
    val expiresAt: Long? = null,
) {
    override fun equals(other: Any?): Boolean = other is OutboxEntity &&
        id == other.id && topic == other.topic && payload.contentEquals(other.payload)

    override fun hashCode(): Int = (id.hashCode() * 31 + topic.hashCode()) * 31 + payload.contentHashCode()
}
