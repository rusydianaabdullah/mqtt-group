package id.larus.mqttgroup.data.repo

import id.larus.mqttgroup.crypto.Aead
import id.larus.mqttgroup.crypto.Envelope
import id.larus.mqttgroup.crypto.Invite
import id.larus.mqttgroup.crypto.KeyVault
import id.larus.mqttgroup.data.blob.BlobStore
import id.larus.mqttgroup.data.db.AppDatabase
import id.larus.mqttgroup.data.db.AttachmentEntity
import id.larus.mqttgroup.data.db.BrokerEntity
import id.larus.mqttgroup.data.db.GroupEntity
import id.larus.mqttgroup.data.db.GroupKeyEntity
import id.larus.mqttgroup.data.db.MemberEntity
import id.larus.mqttgroup.data.db.LocationEntity
import id.larus.mqttgroup.data.db.MessageEntity
import id.larus.mqttgroup.mqtt.ChatPayload
import id.larus.mqttgroup.mqtt.Control
import id.larus.mqttgroup.mqtt.LocationPayload
import id.larus.mqttgroup.mqtt.Topics
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID

/**
 * Groups, their keys, and who is in them.
 *
 * Also the one place that seals and opens payloads, because doing it anywhere else would mean
 * a second copy of the rules about epochs and counters.
 */
class GroupRepository(
    private val db: AppDatabase,
    private val vault: KeyVault,
    private val blobs: BlobStore,
    private val outbox: OutboxRepository,
) {
    fun observeAll(): Flow<List<GroupEntity>> = db.groups().observeAll()

    fun observeMembers(groupId: String): Flow<List<MemberEntity>> = db.members().observe(groupId)

    suspend fun member(groupId: String, userId: String): MemberEntity? =
        db.members().get(groupId, userId)

    suspend fun get(groupId: String): GroupEntity? = db.groups().get(groupId)

    /**
     * Creates a group. The key is generated here and never leaves this phone except inside an
     * invite QR — there is no key exchange to get wrong because there is no exchange at all.
     */
    suspend fun create(name: String, broker: BrokerEntity, self: MemberEntity.() -> Unit = {}): GroupEntity {
        val identity = db.identity().get() ?: error("no identity yet")
        val group = GroupEntity(
            groupId = UUID.randomUUID().toString(),
            name = name.trim(),
            brokerId = broker.id,
            topicPrefix = Topics.normalise(broker.topicPrefix),
            keyEpoch = 0,
            isAdmin = true,
            createdAt = System.currentTimeMillis(),
        )
        db.groups().put(group)
        db.groups().putKey(
            GroupKeyEntity(
                groupId = group.groupId,
                epoch = 0,
                wrappedKey = vault.wrap(Aead.randomKey()),
                createdAt = group.createdAt,
            ),
        )
        db.members().put(
            MemberEntity(
                groupId = group.groupId,
                userId = identity.userId,
                displayName = identity.displayName,
                avatarBlobId = identity.avatarBlobId,
                isAdmin = true,
                lastSeenAt = group.createdAt,
            ).apply(self),
        )
        return group
    }

    /**
     * Joins from a scanned code. Returns null if this phone is already in the group, so a
     * second scan is a no-op rather than a way to reset your own membership.
     */
    suspend fun join(invite: Invite, brokerId: String): GroupEntity? {
        val identity = db.identity().get() ?: error("no identity yet")
        if (db.groups().get(invite.groupId) != null) return null

        val now = System.currentTimeMillis()
        val group = GroupEntity(
            groupId = invite.groupId,
            name = invite.groupName,
            brokerId = brokerId,
            topicPrefix = Topics.normalise(invite.topicPrefix),
            keyEpoch = invite.epoch,
            isAdmin = invite.adminId == identity.userId,
            createdAt = now,
        )
        db.groups().put(group)
        db.groups().putKey(
            GroupKeyEntity(
                groupId = group.groupId,
                epoch = invite.epoch,
                wrappedKey = vault.wrap(invite.groupKey),
                createdAt = now,
            ),
        )
        // The admin is known from the invite, so their authority is established before they
        // have said anything — a kick that arrives before their profile does still counts.
        db.members().put(
            MemberEntity(
                groupId = group.groupId,
                userId = invite.adminId,
                displayName = "",
                avatarBlobId = null,
                isAdmin = true,
                lastSeenAt = 0,
            ),
        )
        db.members().put(
            MemberEntity(
                groupId = group.groupId,
                userId = identity.userId,
                displayName = identity.displayName,
                avatarBlobId = identity.avatarBlobId,
                isAdmin = group.isAdmin,
                lastSeenAt = now,
            ),
        )
        return group
    }

    /** The invite to show as a QR. Only meaningful for a group this phone administers. */
    suspend fun invite(groupId: String, broker: BrokerEntity): Invite? {
        val group = db.groups().get(groupId) ?: return null
        val identity = db.identity().get() ?: return null
        val key = groupKey(groupId, group.keyEpoch) ?: return null
        return Invite(
            groupId = group.groupId,
            groupName = group.name,
            adminId = identity.userId,
            epoch = group.keyEpoch,
            groupKey = key,
            host = broker.host,
            port = broker.port,
            tls = broker.tls,
            allowInsecureTls = broker.allowInsecureTls,
            username = broker.username,
            password = broker.password,
            topicPrefix = group.topicPrefix,
        )
    }

    suspend fun groupKey(groupId: String, epoch: Int): ByteArray? =
        db.groups().key(groupId, epoch)?.let { vault.unwrap(it.wrappedKey) }

    /**
     * Seals [plaintext] for [groupId] and queues it. The counter comes from the database so a
     * restart never reuses one, which would otherwise look to receivers like a replay.
     *
     * Returns the counter the message was sealed with, so a caller that needs to record its own
     * copy (chat does) does not have to ask the database for a second one — or null if there was
     * nothing to seal it with.
     */
    suspend fun publish(
        groupId: String,
        topicSuffix: (prefix: String, groupId: String) -> String,
        plaintext: ByteArray,
        qos: Int = 1,
        retained: Boolean = false,
        /** The chat message this publish is the wire copy of, if it is one — see
         * [OutboxEntity.refMessageId]. */
        refMessageId: String? = null,
        /** How long this is worth publishing, or null to keep trying until it goes, which is
         * what everything but call signalling wants — see [OutboxEntity.expiresAt]. */
        expiresInMillis: Long? = null,
    ): Long? {
        val group = db.groups().get(groupId) ?: return null
        val identity = db.identity().get() ?: return null
        val key = groupKey(groupId, group.keyEpoch) ?: return null
        val counter = db.groups().nextCounter(groupId)
        val payload = Envelope.seal(
            key = key,
            epoch = group.keyEpoch,
            senderId = UUID.fromString(identity.userId),
            counter = counter,
            plaintext = plaintext,
        )
        outbox.enqueue(
            groupId = groupId,
            topic = topicSuffix(group.topicPrefix, groupId),
            payload = payload,
            qos = qos,
            retained = retained,
            refMessageId = refMessageId,
            expiresInMillis = expiresInMillis,
        )
        return counter
    }

    fun observeMessages(groupId: String) = db.messages().observe(groupId)

    /**
     * Sends a text message: sealed and queued like anything else here, plus a local copy so the
     * sender sees their own message immediately rather than waiting for the broker to echo it
     * back. It starts PENDING and the outbox flips it to SENT (once the broker actually has it)
     * or FAILED (after it has given up retrying) — this row does not decide that for itself.
     */
    suspend fun sendMessage(groupId: String, body: String, replyToId: String? = null): MessageEntity? {
        val identity = db.identity().get() ?: return null
        val id = UUID.randomUUID().toString()
        val payload = ChatPayload.Text(id, body, replyToId).encode()
        val message = MessageEntity(
            id = id,
            groupId = groupId,
            senderId = identity.userId,
            type = "text",
            body = body,
            blobId = null,
            createdAt = System.currentTimeMillis(),
            state = "PENDING",
            counter = 0,
            read = true,
            replyToId = replyToId,
        )
        db.messages().put(message)
        val counter = publish(groupId, Topics::messages, payload, refMessageId = id) ?: run {
            db.messages().setState(id, "FAILED")
            return null
        }
        db.messages().setCounter(id, counter)
        return message.copy(counter = counter)
    }

    /**
     * Sends a picture or a voice note — anything that is bytes plus a mime type. [bytes] is
     * already downscaled/compressed or encoded by the caller; this is not the place to
     * second-guess what fits, since the cap is a property of the blob store, not of chat.
     *
     * The bytes go out twice: once as the attachment itself on its own topic, so a phone that
     * skips loading media can still ignore it, and once as a small reference alongside the text
     * messages, which is what makes it show up in the chat at all. Both are sealed the same way
     * as everything else, and unlike the local blob store — encrypted under a per-phone key that
     * only this device holds — these travel under the group key, since every member's phone has
     * to be able to open them and re-encrypt its own local copy.
     */
    suspend fun sendAttachment(
        groupId: String,
        bytes: ByteArray,
        mime: String,
        kind: String,
        caption: String? = null,
        replyToId: String? = null,
    ): MessageEntity? {
        val identity = db.identity().get() ?: return null
        val blobId = UUID.randomUUID().toString()
        val sha256 = BlobStore.sha256(bytes)
        val id = UUID.randomUUID().toString()

        blobs.put(blobId, bytes)
        db.attachments().put(
            AttachmentEntity(
                blobId = blobId,
                groupId = groupId,
                mime = mime,
                sizeBytes = bytes.size.toLong(),
                sha256 = sha256,
                chunksTotal = 1,
                chunksHave = 1,
                createdAt = System.currentTimeMillis(),
            ),
        )
        var message = MessageEntity(
            id = id,
            groupId = groupId,
            senderId = identity.userId,
            type = kind,
            body = caption,
            blobId = blobId,
            createdAt = System.currentTimeMillis(),
            state = "PENDING",
            counter = 0,
            read = true,
            replyToId = replyToId,
        )
        db.messages().put(message)

        publish(groupId, { prefix, gid -> Topics.attachment(prefix, gid, blobId) }, bytes)
        val payload = ChatPayload.Attachment(
            id, kind, blobId, mime, bytes.size.toLong(), sha256, caption, replyToId,
        ).encode()
        val counter = publish(groupId, Topics::messages, payload, refMessageId = id) ?: run {
            db.messages().setState(id, "FAILED")
            return null
        }
        db.messages().setCounter(id, counter)
        message = message.copy(counter = counter)
        return message
    }

    /** Announces who we are, which is how everyone else's member list fills in. */
    suspend fun announceSelf(groupId: String) {
        val identity = db.identity().get() ?: return
        val avatar = identity.avatarBlobId?.let(blobs::get)
        publish(
            groupId = groupId,
            topicSuffix = Topics::control,
            plaintext = Control.Profile(identity.userId, identity.displayName, avatar).encode(),
        )
        // And apply it here, because nothing else will. GroupSync drops our own messages coming
        // back from the broker — they are already in the database — so the one member row this
        // announcement never reached was our own, which kept whatever name and picture it was
        // created with. On screen that is your old initial sitting in your own group's member
        // list and on your own map pin, for as long as the group exists.
        val existing = db.members().get(groupId, identity.userId)
        db.members().put(
            MemberEntity(
                groupId = groupId,
                userId = identity.userId,
                displayName = identity.displayName,
                avatarBlobId = identity.avatarBlobId,
                // Everything that is not the profile is preserved: admin standing, the counters
                // used for replay detection, and whether we were removed.
                isAdmin = existing?.isAdmin ?: db.groups().get(groupId)?.isAdmin ?: false,
                lastSeenAt = System.currentTimeMillis(),
                isRemoved = existing?.isRemoved ?: false,
                lastCounterMsg = existing?.lastCounterMsg ?: 0,
                lastCounterCtl = existing?.lastCounterCtl ?: 0,
            ),
        )
    }

    /**
     * Re-states the group's location rules, retained, without changing them. Admin only and a
     * no-op for anyone else.
     *
     * This is what heals a group whose settings were last published by a build that only knew
     * about /ctl: there is no retained copy for a new member to pick up, and the admin has no
     * reason to open the settings screen again. Sending it when a new member announces
     * themselves — the same moment [announceSelf] answers with a name — puts one there.
     */
    suspend fun republishLocationSettings(groupId: String) {
        val group = db.groups().get(groupId) ?: return
        if (!group.isAdmin) return
        val settings = Control.LocationSettings(
            enabled = group.locationEnabled,
            sampleSec = group.locSampleSec,
            pushSec = group.locPushSec,
            minDistanceM = group.locMinDistanceM,
            retentionHours = group.locRetentionHours,
        )
        publish(groupId, Topics::settings, settings.encode(), retained = true)
        publish(groupId, Topics::control, settings.encode())
    }

    /**
     * Removes someone. Only an admin's kick is obeyed by other phones, and even then it is a
     * request other clients honour rather than a lock on the door: the person still holds the
     * group key. Callers must say so in the UI.
     */
    suspend fun kick(groupId: String, userId: String) {
        val group = db.groups().get(groupId) ?: return
        if (!group.isAdmin) return
        db.members().markRemoved(groupId, userId)
        publish(groupId, Topics::control, Control.Kick(userId).encode())
    }

    suspend fun leave(groupId: String) {
        val identity = db.identity().get() ?: return
        publish(groupId, Topics::control, Control.Leave(identity.userId).encode())
        forget(groupId)
    }

    suspend fun deleteForEveryone(groupId: String) {
        val group = db.groups().get(groupId) ?: return
        if (!group.isAdmin) return
        publish(groupId, Topics::control, Control.Delete.encode())
        forget(groupId)
    }

    /**
     * Drops a group from this phone: its messages, its members and — the part that matters —
     * its key, after which anything of it still on disk or on the broker is unreadable here.
     */
    suspend fun forget(groupId: String) {
        // Blobs first: once the rows are gone there is nothing left pointing at the files, and
        // an orphaned encrypted file would sit there until the app is uninstalled.
        db.attachments().forGroup(groupId).forEach { blobs.delete(it.blobId) }
        db.attachments().deleteForGroup(groupId)
        db.receipts().deleteForGroup(groupId)
        db.messages().deleteForGroup(groupId)
        db.locations().deleteHistory(groupId, null)
        db.members().deleteForGroup(groupId)
        db.groups().deleteKeys(groupId)
        db.groups().delete(groupId)
    }

    fun observeReceipts(groupId: String) = db.receipts().observe(groupId)

    /** One row per group with anything unread, keyed by group id, for the badge on the groups
     * list. A group with nothing unread just does not appear in the map. */
    fun observeUnreadCounts(selfUserId: String) =
        db.messages().observeUnreadCounts(selfUserId).map { rows -> rows.associate { it.groupId to it.count } }

    /**
     * Marks everything from someone else in [groupId] as opened, and tells each sender so —
     * this is the one receipt that reflects an actual person looking at the screen, so it is
     * never sent for anything but that.
     */
    suspend fun markRead(groupId: String, selfUserId: String) {
        val unread = db.messages().unread(groupId, selfUserId)
        if (unread.isEmpty()) return
        db.messages().markRead(groupId, selfUserId)
        unread.forEach { publish(groupId, Topics::control, Control.Read(it.id).encode()) }
    }

    /** Fired the moment an incoming message is stored, so its sender learns it arrived even
     * before anyone has opened the chat to read it. */
    suspend fun sendDeliveredReceipt(groupId: String, messageId: String) {
        publish(groupId, Topics::control, Control.Delivered(messageId).encode())
    }

    // --- Location ------------------------------------------------------------------------------

    /** Everyone's latest known position in a group, for the map's pins. */
    fun observeLatestLocations(groupId: String) = db.locations().observeLatest(groupId)

    /** Everyone's fixes inside a window, for drawing the whole group's paths at once. */
    fun observeHistoryRange(groupId: String, since: Long, until: Long) =
        db.locations().observeHistoryRange(groupId, since, until)

    /** One person's track since [since], oldest first, for the polyline behind their pin. */
    fun observeTrail(groupId: String, userId: String, since: Long) =
        db.locations().observeTrail(groupId, userId, since)

    /**
     * Changes the group's location rules and tells everyone. Admin only, and silently a no-op
     * otherwise — the settings UI does not offer the controls to a member in the first place,
     * so reaching here without being admin means something went wrong rather than something the
     * user needs explained.
     *
     * The local write happens first and does not wait for the publish: the outbox may sit on
     * this for a week if the phone is offline, and an admin whose own slider snapped back until
     * the network returned would reasonably conclude the app was broken.
     */
    suspend fun setLocationSettings(groupId: String, settings: Control.LocationSettings) {
        val group = db.groups().get(groupId) ?: return
        if (!group.isAdmin) return
        applyLocationSettings(groupId, settings)
        // Retained on /set so it reaches whoever subscribes next — a member who joins a month
        // from now, or one whose session the broker expired — and unretained on /ctl as well so
        // that a phone running an older build, which knows nothing about /set, still hears it.
        publish(groupId, Topics::settings, settings.encode(), retained = true)
        publish(groupId, Topics::control, settings.encode())
    }

    /** The same write without the publish, for [id.larus.mqttgroup.mqtt.GroupSync] applying an
     * admin's message on the receiving end. Clamps [Control.LocationSettings.pushSec] up to the
     * sample interval: publishing more often than a fix is taken cannot produce anything but the
     * same position twice, and would spend the radio to say nothing. */
    suspend fun applyLocationSettings(groupId: String, settings: Control.LocationSettings) {
        db.groups().setLocationSettings(
            groupId = groupId,
            enabled = settings.enabled,
            sampleSec = settings.sampleSec,
            pushSec = maxOf(settings.pushSec, settings.sampleSec),
            minDistanceM = settings.minDistanceM,
            retentionHours = settings.retentionHours,
        )
    }

    /** This phone's own answer to "share my location with this group". Local; never published. */
    suspend fun setShareLocation(groupId: String, share: Boolean) {
        db.groups().setShareLocation(groupId, share)
    }

    /**
     * Records and publishes one of our own fixes.
     *
     * Stored locally *and* sent, in that order, so our own trail is drawn from the same rows as
     * everyone else's rather than from a parallel source that could disagree with it.
     *
     * Retained, so a phone opening the map after a week offline sees where everyone was rather
     * than an empty screen. QoS 0 for the reason in [LocationPayload]: a redelivered stale
     * position is worse than a missing one.
     */
    suspend fun publishLocation(groupId: String, payload: LocationPayload) {
        val identity = db.identity().get() ?: return
        db.locations().insert(
            LocationEntity(
                groupId = groupId,
                userId = identity.userId,
                lat = payload.lat,
                lon = payload.lon,
                accuracy = payload.accuracy,
                speed = payload.speed,
                bearing = payload.bearing,
                capturedAt = payload.capturedAt,
            ),
        )
        // Sealed here rather than through publish(), because this is the one topic whose
        // queued backlog should be replaced rather than appended to — see
        // [OutboxRepository.enqueueLatest]. The counter is still taken from the same sequence so
        // a receiver's replay check sees nothing unusual.
        val group = db.groups().get(groupId) ?: return
        val key = groupKey(groupId, group.keyEpoch) ?: return
        val counter = db.groups().nextCounter(groupId)
        outbox.enqueueLatest(
            groupId = groupId,
            topic = Topics.location(group.topicPrefix, groupId, identity.userId),
            payload = Envelope.seal(
                key = key,
                epoch = group.keyEpoch,
                senderId = UUID.fromString(identity.userId),
                counter = counter,
                plaintext = payload.encode(),
            ),
        )
    }

    /**
     * Records a fix without publishing it — taken, but held back by the movement gate. It still
     * belongs in the local history: "stayed put for an hour" is a real answer the trail should
     * be able to show, and it costs nothing, the fix having already been paid for.
     */
    suspend fun recordOwnLocation(groupId: String, payload: LocationPayload) {
        val identity = db.identity().get() ?: return
        db.locations().insert(
            LocationEntity(
                groupId = groupId,
                userId = identity.userId,
                lat = payload.lat,
                lon = payload.lon,
                accuracy = payload.accuracy,
                speed = payload.speed,
                bearing = payload.bearing,
                capturedAt = payload.capturedAt,
            ),
        )
    }

    /** Our own last stored fix for a group, which is what the movement gate measures against. */
    suspend fun lastOwnLocation(groupId: String): LocationEntity? {
        val identity = db.identity().get() ?: return null
        return db.locations().latestFor(groupId, identity.userId)
    }

    /** "Delete history", scoped to one person or — with a null [userId] — the whole group. */
    suspend fun deleteLocationHistory(groupId: String, userId: String? = null) {
        db.locations().deleteHistory(groupId, userId)
    }
}
