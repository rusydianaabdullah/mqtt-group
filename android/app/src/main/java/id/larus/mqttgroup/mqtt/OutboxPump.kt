package id.larus.mqttgroup.mqtt

import id.larus.mqttgroup.data.repo.GroupRepository
import id.larus.mqttgroup.data.repo.OutboxRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Drains the outbox whenever there is a connection to drain it through.
 *
 * The loop deliberately does nothing while disconnected — it suspends on the connection state
 * rather than polling — so an offline phone spends no battery discovering it is still offline.
 * When a row is due later, it sleeps exactly that long.
 */
class OutboxPump(
    private val outbox: OutboxRepository,
    private val connections: ConnectionPool,
    private val groups: GroupRepository,
    private val scope: CoroutineScope,
) {
    /** Nudged whenever something is enqueued, so a new message goes out without waiting. */
    private val wakeups = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    private var job: Job? = null

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch {
            while (isActive) {
                // Suspends until *some* broker is up. Not all of them: with three brokers and
                // one down, the two that work should still be draining, and a row for the broken
                // one simply fails its attempt and backs off the way it always has.
                connections.states.first { states ->
                    states.values.any { it is ConnectionState.Connected }
                }

                // Before anything else, because a phone that has just come back from an hour
                // offline may be holding rings that expired while it was away, and the first
                // thing it should do with them is nothing.
                outbox.dropExpired()

                val batch = outbox.due()
                if (batch.isEmpty()) {
                    // Nothing due: wait for an enqueue, or for the next row to come due.
                    waitForWork()
                    continue
                }

                for (row in batch) {
                    if (!connections.anyConnected) break
                    // Each row goes out on its own group's broker. A row whose group is gone, or
                    // whose broker is not currently up, counts as a failed attempt: onFailed
                    // backs it off rather than dropping it, which is exactly right for a broker
                    // that is temporarily down and harmless for one that is gone for good, since
                    // deleting the group deletes its rows.
                    val brokerId = groups.get(row.groupId)?.brokerId
                    val connection = brokerId?.let(connections::peek)
                    val sent = connection?.publish(row.topic, row.payload, row.qos, row.retained) ?: false
                    if (sent) outbox.onPublished(row) else outbox.onFailed(row)
                }
            }
        }
    }

    fun wake() {
        wakeups.tryEmit(Unit)
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    /**
     * Sleeps until something is enqueued, or until a backed-off row is likely due. The wake-up
     * covers the case that matters; the timeout is only there to pick up retries.
     */
    private suspend fun waitForWork() {
        withTimeoutOrNull(IDLE_POLL_MILLIS) { wakeups.first() }
    }

    private companion object {
        const val IDLE_POLL_MILLIS = 30_000L
    }
}
