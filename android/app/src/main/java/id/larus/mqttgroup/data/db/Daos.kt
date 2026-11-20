package id.larus.mqttgroup.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface IdentityDao {
    @Query("SELECT * FROM identity WHERE id = :id")
    fun observe(id: Int = IdentityEntity.SINGLETON): Flow<IdentityEntity?>

    @Query("SELECT * FROM identity WHERE id = :id")
    suspend fun get(id: Int = IdentityEntity.SINGLETON): IdentityEntity?

    @Upsert
    suspend fun put(identity: IdentityEntity)
}

@Dao
interface BrokerDao {
    @Query("SELECT * FROM broker ORDER BY createdAt")
    fun observeAll(): Flow<List<BrokerEntity>>

    /** A plain read for the backup writer, which wants a snapshot rather than a subscription. */
    @Query("SELECT * FROM broker ORDER BY createdAt")
    suspend fun all(): List<BrokerEntity>

    @Query("SELECT * FROM broker WHERE id = :id")
    suspend fun get(id: String): BrokerEntity?

    @Upsert
    suspend fun put(broker: BrokerEntity)

    @Query("DELETE FROM broker WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
abstract class GroupDao {
    @Query("SELECT * FROM group_chat ORDER BY createdAt")
    abstract fun observeAll(): Flow<List<GroupEntity>>

    @Query("SELECT * FROM group_chat WHERE groupId = :groupId")
    abstract suspend fun get(groupId: String): GroupEntity?

    @Query("SELECT * FROM group_chat ORDER BY createdAt")
    abstract suspend fun all(): List<GroupEntity>

    @Upsert
    abstract suspend fun put(group: GroupEntity)

    @Query("DELETE FROM group_chat WHERE groupId = :groupId")
    abstract suspend fun delete(groupId: String)

    @Query("UPDATE group_chat SET sendCounter = sendCounter + 1 WHERE groupId = :groupId")
    protected abstract suspend fun bumpCounter(groupId: String)

    @Query("SELECT sendCounter FROM group_chat WHERE groupId = :groupId")
    protected abstract suspend fun readCounter(groupId: String): Long?

    /**
     * Hands out the next publish counter. Wrapped in a transaction because two coroutines
     * publishing at once must not get the same number: the receiver would treat the second as a
     * replay and drop it.
     */
    @Transaction
    open suspend fun nextCounter(groupId: String): Long {
        bumpCounter(groupId)
        return readCounter(groupId) ?: 0
    }

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun putKey(key: GroupKeyEntity)

    @Query("SELECT * FROM group_key WHERE groupId = :groupId AND epoch = :epoch")
    abstract suspend fun key(groupId: String, epoch: Int): GroupKeyEntity?

    /** Every epoch of every group's key. The backup takes all of them, not just the current one:
     * a restore that could read only new messages would silently lose the history it was made
     * to protect. */
    @Query("SELECT * FROM group_key ORDER BY groupId, epoch")
    abstract suspend fun allKeys(): List<GroupKeyEntity>

    @Query("DELETE FROM group_key WHERE groupId = :groupId")
    abstract suspend fun deleteKeys(groupId: String)

    /**
     * Writes the group's location rules. Targeted rather than a whole-row upsert because this
     * arrives from two directions at once — the admin's own edit and an incoming control message
     * — and neither should be able to clobber [GroupEntity.shareLocation] or the send counter
     * with a stale copy read a moment earlier.
     */
    @Query(
        """
        UPDATE group_chat SET
            locationEnabled = :enabled,
            locSampleSec = :sampleSec,
            locPushSec = :pushSec,
            locMinDistanceM = :minDistanceM,
            locRetentionHours = :retentionHours
        WHERE groupId = :groupId
        """,
    )
    abstract suspend fun setLocationSettings(
        groupId: String,
        enabled: Boolean,
        sampleSec: Int,
        pushSec: Int,
        minDistanceM: Int,
        retentionHours: Int,
    )

    /** This phone's own consent for this group, and nothing else — see [GroupEntity.shareLocation]. */
    @Query("UPDATE group_chat SET shareLocation = :share WHERE groupId = :groupId")
    abstract suspend fun setShareLocation(groupId: String, share: Boolean)
}

@Dao
interface MemberDao {
    @Query("SELECT * FROM member WHERE groupId = :groupId ORDER BY isRemoved, displayName")
    fun observe(groupId: String): Flow<List<MemberEntity>>

    @Query("SELECT * FROM member WHERE groupId = :groupId AND userId = :userId")
    suspend fun get(groupId: String, userId: String): MemberEntity?

    @Upsert
    suspend fun put(member: MemberEntity)

    @Query("SELECT * FROM member")
    suspend fun all(): List<MemberEntity>

    // Both counters reset too: a rejoin starts a fresh device-local counter sequence at 0 (their
    // old GroupEntity was wiped by forget()), and leaving the old high-water marks in place would
    // make their rejoin announcement, and everything after it, look like a replay of something
    // already seen — the counter is bookkeeping for dedup, not a security boundary, so resetting
    // it here is safe even though the kick itself is only a request other clients honour.
    @Query(
        "UPDATE member SET isRemoved = 1, lastCounterMsg = 0, lastCounterCtl = 0 " +
            "WHERE groupId = :groupId AND userId = :userId",
    )
    suspend fun markRemoved(groupId: String, userId: String)

    /** [kind] is "msg" or "ctl" — whichever counter this envelope belonged to is the only one
     * that moves forward; the other is untouched. */
    @Query(
        """
        UPDATE member SET
            lastCounterMsg = CASE WHEN :kind = 'msg' THEN :counter ELSE lastCounterMsg END,
            lastCounterCtl = CASE WHEN :kind = 'ctl' THEN :counter ELSE lastCounterCtl END,
            lastSeenAt = :seenAt
        WHERE groupId = :groupId AND userId = :userId
        """,
    )
    suspend fun touch(groupId: String, userId: String, kind: String, counter: Long, seenAt: Long)

    @Query("DELETE FROM member WHERE groupId = :groupId")
    suspend fun deleteForGroup(groupId: String)
}

@Dao
interface MessageDao {
    @Query("SELECT * FROM message WHERE groupId = :groupId ORDER BY createdAt DESC")
    fun observe(groupId: String): Flow<List<MessageEntity>>

    @Query("SELECT * FROM message WHERE blobId = :blobId LIMIT 1")
    suspend fun findByBlobId(blobId: String): MessageEntity?

    @Upsert
    suspend fun put(message: MessageEntity)

    @Query("UPDATE message SET state = :state WHERE id = :id")
    suspend fun setState(id: String, state: String)

    /** A single-column update rather than re-upserting the whole row: the outbox flips [state]
     * on its own timeline, concurrently, and a full-row write here could otherwise clobber that
     * with a stale copy of it. */
    @Query("UPDATE message SET counter = :counter WHERE id = :id")
    suspend fun setCounter(id: String, counter: Long)

    /** Everything from someone else in this group that has not been opened yet — what
     * [markRead] is about to act on, and what a read receipt has to be sent for. */
    @Query("SELECT * FROM message WHERE groupId = :groupId AND senderId != :selfUserId AND read = 0")
    suspend fun unread(groupId: String, selfUserId: String): List<MessageEntity>

    @Query("UPDATE message SET read = 1 WHERE groupId = :groupId AND senderId != :selfUserId AND read = 0")
    suspend fun markRead(groupId: String, selfUserId: String)

    /** One row per group with anything unread, for the badge on the groups list. Groups with
     * nothing unread simply do not appear, rather than appearing with a zero. */
    @Query(
        "SELECT groupId, COUNT(*) as count FROM message " +
            "WHERE senderId != :selfUserId AND read = 0 GROUP BY groupId",
    )
    fun observeUnreadCounts(selfUserId: String): Flow<List<UnreadCount>>

    @Query("DELETE FROM message WHERE groupId = :groupId")
    suspend fun deleteForGroup(groupId: String)
}

data class UnreadCount(val groupId: String, val count: Int)

@Dao
interface MessageReceiptDao {
    @Query("SELECT * FROM message_receipt WHERE groupId = :groupId")
    fun observe(groupId: String): Flow<List<MessageReceiptEntity>>

    @Query("SELECT * FROM message_receipt WHERE messageId = :messageId AND userId = :userId")
    suspend fun get(messageId: String, userId: String): MessageReceiptEntity?

    @Upsert
    suspend fun put(receipt: MessageReceiptEntity)

    @Query("DELETE FROM message_receipt WHERE groupId = :groupId")
    suspend fun deleteForGroup(groupId: String)
}

@Dao
interface AttachmentDao {
    @Query("SELECT * FROM attachment WHERE blobId = :blobId")
    suspend fun get(blobId: String): AttachmentEntity?

    @Upsert
    suspend fun put(attachment: AttachmentEntity)

    @Query("DELETE FROM attachment WHERE blobId = :blobId")
    suspend fun delete(blobId: String)

    @Query("SELECT * FROM attachment WHERE groupId = :groupId")
    suspend fun forGroup(groupId: String): List<AttachmentEntity>

    @Query("DELETE FROM attachment WHERE groupId = :groupId")
    suspend fun deleteForGroup(groupId: String)
}

@Dao
interface LocationDao {
    @Query("SELECT * FROM location WHERE groupId = :groupId AND capturedAt >= :since ORDER BY capturedAt")
    fun observeHistory(groupId: String, since: Long): Flow<List<LocationEntity>>

    @Query(
        """
        SELECT * FROM location WHERE id IN (
            SELECT MAX(id) FROM location WHERE groupId = :groupId GROUP BY userId
        )
        """,
    )
    fun observeLatest(groupId: String): Flow<List<LocationEntity>>

    @Insert
    suspend fun insert(location: LocationEntity)

    /** The most recent fix from one person, or null if they have never been on the map. The
     * movement gate needs our own last *published* position, which is exactly this row. */
    @Query("SELECT * FROM location WHERE groupId = :groupId AND userId = :userId ORDER BY capturedAt DESC LIMIT 1")
    suspend fun latestFor(groupId: String, userId: String): LocationEntity?

    /** One person's track over a window, oldest first, for drawing a polyline. */
    @Query(
        """
        SELECT * FROM location
        WHERE groupId = :groupId AND userId = :userId AND capturedAt >= :since
        ORDER BY capturedAt
        """,
    )
    fun observeTrail(groupId: String, userId: String, since: Long): Flow<List<LocationEntity>>

    /**
     * Everyone's fixes inside a window, oldest first within each person.
     *
     * One query for the whole group rather than one per member: the map draws every path at
     * once, and N flows that each re-emit on every incoming fix would redraw the map N times for
     * what is one change. Ordering by userId first means the caller can group without sorting.
     */
    @Query(
        """
        SELECT * FROM location
        WHERE groupId = :groupId AND capturedAt >= :since AND capturedAt < :until
        ORDER BY userId, capturedAt
        """,
    )
    fun observeHistoryRange(groupId: String, since: Long, until: Long): Flow<List<LocationEntity>>

    @Query("DELETE FROM location WHERE capturedAt < :before")
    suspend fun pruneOlderThan(before: Long): Int

    /** Retention is per group, because the window is one of the admin's settings — so pruning
     * has to be too, rather than one global cutoff applied to every group's rows alike. */
    @Query("DELETE FROM location WHERE groupId = :groupId AND capturedAt < :before")
    suspend fun pruneGroupOlderThan(groupId: String, before: Long): Int

    @Query("DELETE FROM location WHERE groupId = :groupId AND (:userId IS NULL OR userId = :userId)")
    suspend fun deleteHistory(groupId: String, userId: String?)
}

@Dao
interface OutboxDao {
    @Query(
        "SELECT * FROM outbox WHERE status != 'FAILED' AND nextAttemptAt <= :now " +
            "AND (expiresAt IS NULL OR expiresAt > :now) ORDER BY id LIMIT :limit",
    )
    suspend fun due(now: Long, limit: Int = 32): List<OutboxEntity>

    /** Sweeps rows that went stale before a connection came back. Purely housekeeping: [due]
     * already refuses to publish them, so this only stops the table growing a tail of rings
     * nobody will ever hear. */
    @Query("DELETE FROM outbox WHERE expiresAt IS NOT NULL AND expiresAt <= :now")
    suspend fun deleteExpired(now: Long)

    @Query("SELECT COUNT(*) FROM outbox WHERE status = 'FAILED'")
    fun observeFailedCount(): Flow<Int>

    @Insert
    suspend fun enqueue(row: OutboxEntity): Long

    /** Drops anything still queued for a topic. Only meaningful for a retained latest-value
     * topic like `loc`, where an older payload is not a message that was missed but a worse
     * answer to the same question — see [OutboxRepository.enqueueLatest]. */
    @Query("DELETE FROM outbox WHERE topic = :topic AND status != 'SENDING'")
    suspend fun deleteQueuedForTopic(topic: String)

    @Query("DELETE FROM outbox WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("UPDATE outbox SET retryCount = :retryCount, status = :status, nextAttemptAt = :nextAttemptAt WHERE id = :id")
    suspend fun reschedule(id: Long, retryCount: Int, status: String, nextAttemptAt: Long)
}
