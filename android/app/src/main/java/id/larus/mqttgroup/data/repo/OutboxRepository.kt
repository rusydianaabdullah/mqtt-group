package id.larus.mqttgroup.data.repo

import id.larus.mqttgroup.data.db.AppDatabase
import id.larus.mqttgroup.data.db.OutboxEntity
import kotlinx.coroutines.flow.Flow
import kotlin.math.min

/**
 * The durable side of publishing. Nothing in this app calls the MQTT client directly: a message
 * becomes a row here first, and only stops existing once the broker has acknowledged it. That
 * is what makes "I sent that while I was in the lift" survive a dead network, a doze, or the
 * process being killed.
 *
 * Rows are deleted on success rather than marked sent, so the table stays the size of the
 * backlog rather than the size of the history.
 */
class OutboxRepository(private val db: AppDatabase) {

    suspend fun enqueue(
        groupId: String,
        topic: String,
        payload: ByteArray,
        qos: Int = 1,
        retained: Boolean = false,
        refMessageId: String? = null,
        /**
         * How long this row is worth publishing, or null to keep it until it goes. Only call
         * signalling sets it; see [OutboxEntity.expiresAt] for why the outbox's usual promise
         * is the wrong one there.
         */
        expiresInMillis: Long? = null,
    ): Long {
        val now = System.currentTimeMillis()
        return db.outbox().enqueue(
            OutboxEntity(
                groupId = groupId,
                topic = topic,
                payload = payload,
                qos = qos,
                retained = retained,
                createdAt = now,
                refMessageId = refMessageId,
                expiresAt = expiresInMillis?.let { now + it },
            ),
        )
    }

    /**
     * Enqueues a payload for a *retained latest-value* topic, replacing anything already queued
     * for it.
     *
     * Location is the only such topic, and it inverts the outbox's usual promise. Everywhere
     * else, a backlog is the point: a chat message written in a lift must still arrive an hour
     * later. A position is the opposite — after a week offline, publishing seven days of
     * where-I-was in sequence would spend the radio replaying a journey nobody asked for, and
     * every one of those publishes is immediately overwritten by the next, since the topic is
     * retained and holds only the last value. Only the newest fix was ever going to survive, so
     * only the newest is worth keeping.
     *
     * This is plan.md's "drop stale loc rows" rule, done by superseding rather than by a
     * timeout: it needs no threshold to tune, and it cannot get the answer wrong — the row it
     * throws away is one it is replacing in the same breath.
     *
     * SENDING rows are left alone: that publish is already in flight, and deleting its row
     * would leave the pump holding a completion for something no longer in the table.
     */
    suspend fun enqueueLatest(
        groupId: String,
        topic: String,
        payload: ByteArray,
        qos: Int = 0,
        retained: Boolean = true,
    ): Long {
        db.outbox().deleteQueuedForTopic(topic)
        return enqueue(groupId, topic, payload, qos, retained)
    }

    suspend fun due(now: Long = System.currentTimeMillis()): List<OutboxEntity> = db.outbox().due(now)

    /** Discards rows whose moment has passed. [due] already skips them; this is what keeps them
     * from accumulating across a long offline stretch. */
    suspend fun dropExpired(now: Long = System.currentTimeMillis()) = db.outbox().deleteExpired(now)

    /** The row actually left the phone — the point [MessageEntity.state] "SENT" is meant to
     * describe, as opposed to merely being queued for it. */
    suspend fun onPublished(row: OutboxEntity) {
        db.outbox().delete(row.id)
        row.refMessageId?.let { db.messages().setState(it, "SENT") }
    }

    /**
     * Backs a failed row off and puts it back. After [MAX_RETRIES] it is parked as FAILED and
     * surfaced in the UI, because silently retrying something forever is how a message gets
     * lost without anyone noticing — and, if this row is a chat message, the same word is what
     * the message's own bubble shows.
     */
    suspend fun onFailed(row: OutboxEntity, now: Long = System.currentTimeMillis()) {
        val attempt = row.retryCount + 1
        if (attempt >= MAX_RETRIES) {
            db.outbox().reschedule(row.id, attempt, "FAILED", now)
            row.refMessageId?.let { db.messages().setState(it, "FAILED") }
        } else {
            db.outbox().reschedule(row.id, attempt, "QUEUED", now + backoffMillis(attempt))
        }
    }

    fun observeFailedCount(): Flow<Int> = db.outbox().observeFailedCount()

    companion object {
        const val MAX_RETRIES = 12

        /** 1s, 2s, 4s … capped at five minutes, which is where a phone's radio economics land. */
        fun backoffMillis(attempt: Int): Long =
            min(1_000L shl min(attempt - 1, 20), 5 * 60_000L)
    }
}
