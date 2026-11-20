package id.larus.mqttgroup.mqtt

import id.larus.mqttgroup.call.CallEngine
import id.larus.mqttgroup.crypto.Envelope
import id.larus.mqttgroup.data.blob.BlobStore
import id.larus.mqttgroup.data.db.AppDatabase
import id.larus.mqttgroup.data.db.AttachmentEntity
import id.larus.mqttgroup.data.db.LocationEntity
import id.larus.mqttgroup.data.db.MemberEntity
import id.larus.mqttgroup.data.db.MessageEntity
import id.larus.mqttgroup.data.db.MessageReceiptEntity
import id.larus.mqttgroup.data.repo.GroupRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * The receiving half: subscribes to every group this phone belongs to, opens what arrives, and
 * applies it.
 *
 * Everything here is defensive. A payload from a broker is attacker-controlled until it has
 * opened under a group key, and even then it only proves *someone in the group* sent it — a
 * shared key cannot tell two members apart. So authority is checked against the local member
 * list, replays are dropped by counter, and anything unrecognised is ignored rather than
 * guessed at.
 */
class GroupSync(
    private val db: AppDatabase,
    private val groups: GroupRepository,
    private val blobs: BlobStore,
    private val connections: ConnectionPool,
    private val scope: CoroutineScope,
    private val notifier: ChatNotifier,
    private val calls: CallEngine,
    /** The group whose chat screen is on-screen right now, or null. A message for that group
     * does not need a notification — the person is already looking at it. */
    private val activeGroupId: StateFlow<String?>,
) {
    private var job: Job? = null

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch {
            // Two independent jobs, because they answer to different clocks: what arrives is a
            // stream, while what we are subscribed to is a function of the group list and of
            // which brokers happen to be up.
            launch {
                connections.incoming.collect { handle(it.message) }
            }
            launch {
                // Re-subscribes on every reconnect, not only the first time this runs. Whether a
                // broker actually kept our subscriptions across a disconnect — a resumed session
                // — or started us over with none is not something to gamble on: re-issuing them
                // is a harmless no-op when they were already there, and is the only thing that
                // saves us when they were not. Without this, a reconnect could leave the app
                // quietly unsubscribed from everything, looking connected while receiving
                // nothing.
                //
                // [subscribed] is what keeps that from becoming a subscribe storm: the states
                // map changes on every blip of every broker, and only an entry that is not
                // already there does any work. A broker dropping clears its own entries, so the
                // next Connected re-issues exactly the filters that connection lost and no
                // others.
                val subscribed = mutableSetOf<Pair<String, String>>()
                combine(groups.observeAll(), connections.states) { all, states -> all to states }
                    .collect { (all, states) ->
                        subscribed.removeAll { (brokerId, _) ->
                            states[brokerId] !is ConnectionState.Connected
                        }
                        for (group in all) {
                            if (states[group.brokerId] !is ConnectionState.Connected) continue
                            val handle = group.brokerId to group.groupId
                            if (!subscribed.add(handle)) continue
                            connections.peek(group.brokerId)
                                ?.subscribe(Topics.everything(group.topicPrefix, group.groupId))
                        }
                    }
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    private suspend fun handle(message: IncomingMessage) {
        val header = Envelope.peek(message.payload) ?: return
        val groupId = groupIdFor(message.topic) ?: return
        db.groups().get(groupId) ?: return
        val identity = db.identity().get() ?: return

        val senderId = header.senderId.toString()
        // Our own messages come back to us from the broker; they are already in the database.
        if (senderId == identity.userId) return

        val key = groups.groupKey(groupId, header.epoch) ?: return
        val envelope = Envelope.open(key, message.payload) ?: return

        val kind = kindOf(message.topic) ?: return
        val known = db.members().get(groupId, senderId)

        // Attachments are exempt: they are content-addressed (blobId + sha256) and harmless to
        // apply twice, and MQTT does not guarantee delivery order *across different topics* —
        // only within one — so a photo's bytes on /att can legitimately arrive after its
        // reference on /msg already advanced this sender's counter. Checking them against a
        // shared high-water mark made real photos look like replays and drop them forever. /set
        // is exempt for the same reason /loc is: the broker replays the retained copy on every
        // resubscribe, with the counter it was published under, and applying it again writes the
        // settings it already wrote.
        if (kind == "msg" || kind == "ctl") {
            val lastCounter = known?.let { if (kind == "msg") it.lastCounterMsg else it.lastCounterCtl } ?: 0
            // A counter that does not advance is a replay of something we have already applied.
            if (known != null && envelope.counter <= lastCounter) return
        }

        when (kind) {
            // Settings carry the same Control bodies as /ctl; only their durability differs.
            "ctl", "set" -> applyControl(groupId, senderId, envelope.plaintext)
            "msg" -> applyMessage(groupId, senderId, envelope.counter, envelope.plaintext)
            "att" -> applyAttachment(groupId, message.topic, envelope.plaintext)
            "loc" -> applyLocation(groupId, senderId, envelope.plaintext)
            // Handed straight over without touching the database: a call is entirely in-memory
            // state that stops existing when it stops, and the engine is the only thing that
            // knows whether this one is ours.
            "call" -> Call.decode(envelope.plaintext)?.let { calls.onMessage(groupId, senderId, it) }
            // presence is still unused. Dropping what we do not understand is correct: an
            // older build should ignore a newer one's traffic, not guess at it.
            else -> Unit
        }

        if (kind == "msg" || kind == "ctl") {
            db.members().touch(groupId, senderId, kind, envelope.counter, System.currentTimeMillis())
        }
    }

    private suspend fun applyControl(groupId: String, senderId: String, plaintext: ByteArray) {
        when (val control = Control.decode(plaintext) ?: return) {
            is Control.Profile -> {
                // The sender id comes from the authenticated envelope header, not from the
                // body, so nobody can announce a profile on someone else's behalf.
                val existing = db.members().get(groupId, senderId)
                val avatarBlob = control.avatar?.let(blobs::put) ?: existing?.avatarBlobId
                if (existing?.avatarBlobId != null && control.avatar != null) {
                    blobs.delete(existing.avatarBlobId)
                }
                db.members().put(
                    MemberEntity(
                        groupId = groupId,
                        userId = senderId,
                        displayName = control.displayName,
                        avatarBlobId = avatarBlob,
                        isAdmin = existing?.isAdmin ?: false,
                        lastSeenAt = System.currentTimeMillis(),
                        isRemoved = false,
                        lastCounterMsg = existing?.lastCounterMsg ?: 0,
                        lastCounterCtl = existing?.lastCounterCtl ?: 0,
                    ),
                )
                // A stranger just announced themselves: they have no way to know who was
                // already here, since a join only carries the joiner's own profile. Answering
                // with ours is how an admin's name reaches a member who joined after them —
                // otherwise it sits as "Waiting for their name…" until the admin happens to
                // touch their profile again. Bounded to one reply per newly-seen sender, since
                // the next message from someone we already know skips this branch.
                if (existing == null) {
                    groups.announceSelf(groupId)
                    // And the group's location rules, for a group whose retained copy predates
                    // /set existing — see GroupRepository.republishLocationSettings.
                    groups.republishLocationSettings(groupId)
                }
            }

            is Control.Kick -> {
                if (!isAdmin(groupId, senderId)) return
                val identity = db.identity().get() ?: return
                if (control.targetUserId == identity.userId) {
                    // Kicked ourselves out: drop the group and its key rather than sitting in a
                    // group we have been asked to leave.
                    groups.forget(groupId)
                } else {
                    db.members().markRemoved(groupId, control.targetUserId)
                }
            }

            is Control.Leave -> db.members().markRemoved(groupId, senderId)

            Control.Delete -> {
                if (!isAdmin(groupId, senderId)) return
                groups.forget(groupId)
            }

            is Control.LocationSettings -> {
                // Admin only, checked the same way a kick is: the envelope proves a member sent
                // this, never which member claimed what about themselves.
                if (!isAdmin(groupId, senderId)) return
                groups.applyLocationSettings(groupId, control)
            }

            is Control.Delivered -> applyReceipt(groupId, senderId, control.messageId, "DELIVERED")
            is Control.Read -> applyReceipt(groupId, senderId, control.messageId, "READ")
        }
    }

    /** READ implies DELIVERED, so a delivery receipt that arrives after a read one (out-of-order
     * delivery, or simply a slower link) must never downgrade it back. */
    private suspend fun applyReceipt(groupId: String, senderId: String, messageId: String, state: String) {
        val existing = db.receipts().get(messageId, senderId)
        if (existing?.state == "READ") return
        db.receipts().put(
            MessageReceiptEntity(
                messageId = messageId,
                groupId = groupId,
                userId = senderId,
                state = state,
                at = System.currentTimeMillis(),
            ),
        )
    }

    private suspend fun applyMessage(groupId: String, senderId: String, counter: Long, plaintext: ByteArray) {
        val (preview, messageId) = when (val payload = ChatPayload.decode(plaintext) ?: return) {
            is ChatPayload.Text -> {
                db.messages().put(
                    MessageEntity(
                        id = payload.id,
                        groupId = groupId,
                        senderId = senderId,
                        type = "text",
                        body = payload.body,
                        blobId = null,
                        createdAt = System.currentTimeMillis(),
                        state = "RECEIVED",
                        counter = counter,
                        replyToId = payload.replyToId,
                    ),
                )
                payload.body to payload.id
            }

            is ChatPayload.Attachment -> {
                db.messages().put(
                    MessageEntity(
                        id = payload.id,
                        groupId = groupId,
                        senderId = senderId,
                        type = payload.kind,
                        body = payload.caption,
                        blobId = payload.blobId,
                        createdAt = System.currentTimeMillis(),
                        state = "RECEIVED",
                        counter = counter,
                        replyToId = payload.replyToId,
                    ),
                )
                val label = if (payload.kind == "audio") "Voice message" else "Photo"
                (payload.caption?.ifBlank { null }?.let { "$label: $it" } ?: label) to payload.id
            }
        }

        // "Got it" goes out immediately, before anyone has opened the chat — that is what a
        // second grey check reports. The blue one is a separate, later receipt from markRead.
        groups.sendDeliveredReceipt(groupId, messageId)

        if (activeGroupId.value != groupId) {
            val group = db.groups().get(groupId) ?: return
            val senderName = db.members().get(groupId, senderId)?.displayName?.ifBlank { null } ?: "Someone"
            notifier.notifyMessage(group.groupId, group.name, senderName, preview)
        }
    }

    /**
     * Someone else's position.
     *
     * Not counter-checked, and exempted from that gate above for the same reason attachments
     * are, though by a different route: `loc` is published QoS 0 and *retained*, so the broker
     * replays the last one on every subscribe — every reconnect, every re-subscribe after a
     * network blip. Under a shared high-water mark that replay is indistinguishable from a
     * replay attack, and the position would be dropped; the map would then sit empty until the
     * person's next interval tick, which is exactly the wait retaining it was meant to remove.
     *
     * Replaying an old position is also close to harmless: it is idempotent (the same row, at
     * the same [LocationPayload.capturedAt]), and it cannot make the map show something *newer*
     * than the truth, only re-assert something already seen. The timestamp travels inside the
     * payload, so the UI ages it honestly no matter when it arrives.
     */
    private suspend fun applyLocation(groupId: String, senderId: String, plaintext: ByteArray) {
        val payload = LocationPayload.decode(plaintext) ?: return
        // A retained fix arrives again on every resubscribe. Storing a duplicate row each time
        // would inflate the trail into a stack of identical points at one spot — and the last
        // fix from someone who has stopped sharing would grow a new copy on every reconnect,
        // forever.
        val latest = db.locations().latestFor(groupId, senderId)
        if (latest != null && latest.capturedAt >= payload.capturedAt) return
        db.locations().insert(
            LocationEntity(
                groupId = groupId,
                userId = senderId,
                lat = payload.lat,
                lon = payload.lon,
                accuracy = payload.accuracy,
                speed = payload.speed,
                bearing = payload.bearing,
                capturedAt = payload.capturedAt,
            ),
        )
    }

    /**
     * The bytes for an image message, on their own topic so a phone that is not interested in
     * loading pictures right now could in principle subscribe without them — and so that one
     * more-than-1MB blob does not block the small control and text traffic behind it in a single
     * ordered stream.
     *
     * [topic] ends in `.../att/<blobId>`; the id is not carried in the plaintext because the
     * topic already names it and repeating it would just be one more thing to check agrees.
     */
    private suspend fun applyAttachment(groupId: String, topic: String, plaintext: ByteArray) {
        val blobId = topic.substringAfterLast('/')
        blobs.put(blobId, plaintext)
        db.attachments().put(
            AttachmentEntity(
                blobId = blobId,
                groupId = groupId,
                // The reference message carries the real mime type; it may not have arrived
                // yet, and this row does not gate anything on it.
                mime = "",
                sizeBytes = plaintext.size.toLong(),
                sha256 = BlobStore.sha256(plaintext),
                chunksTotal = 1,
                chunksHave = 1,
                createdAt = System.currentTimeMillis(),
            ),
        )
        // The chat message referencing this blob may already be sitting in the database with
        // nothing on disk to back it yet. Writing it again does not change it, but it does nudge
        // the Flow the chat screen observes — a file merely appearing on disk otherwise triggers
        // no recomposition at all.
        db.messages().findByBlobId(blobId)?.let { db.messages().put(it) }
    }

    private suspend fun isAdmin(groupId: String, userId: String): Boolean =
        db.members().get(groupId, userId)?.isAdmin == true

    // These were a private copy of a layout [Topics] already owns, and the copies drifted:
    // Topics gained `call` and `aud`, this did not, and every call message between two phones
    // was dropped here before reaching anything — nothing logged, nothing failing, no ring.
    // One list now, in the file that builds the topics.
    private fun groupIdFor(topic: String): String? = Topics.groupIdOf(topic)

    private fun kindOf(topic: String): String? = Topics.kindOf(topic)
}
