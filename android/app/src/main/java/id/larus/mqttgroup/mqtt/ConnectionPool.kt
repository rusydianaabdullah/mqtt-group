package id.larus.mqttgroup.mqtt

import id.larus.mqttgroup.data.db.BrokerEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** A message, and which broker it arrived from. */
data class RoutedMessage(val brokerId: String, val message: IncomingMessage)

/**
 * One [MqttConnection] per broker, created on demand and shut down when no group needs it.
 *
 * Groups each name their own broker, so a phone in three groups may hold one connection or
 * three. Nothing above this class counts them: [aggregate] answers "are we online" for the
 * status bar, [incoming] merges every broker's traffic into one stream tagged with where it came
 * from, and callers that need a specific broker ask for it by id.
 *
 * The pool owns the connections and outlives the foreground service, the same way the single
 * connection did — a service restart should cost a reconnect, not a torn-down object graph.
 */
class ConnectionPool {

    /** Its own scope, not the service's: the pipes feeding [incoming] have to survive a service
     * restart, and dying with it would silently stop delivery to a pool that is still connected. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val lock = Mutex()
    private val connections = mutableMapOf<String, MqttConnection>()
    private val pipes = mutableMapOf<String, Job>()
    private val watchers = mutableMapOf<String, Job>()

    private val _states = MutableStateFlow<Map<String, ConnectionState>>(emptyMap())

    /** Every live connection's state, by broker id. */
    val states: StateFlow<Map<String, ConnectionState>> = _states.asStateFlow()

    private val _incoming = MutableSharedFlow<RoutedMessage>(replay = 16, extraBufferCapacity = 256)

    /** Everything arriving on any broker. Replays a little for the same reason the per-connection
     * flow does: retained delivery on subscribe is bursty and a collector may start just after. */
    val incoming: SharedFlow<RoutedMessage> = _incoming.asSharedFlow()

    /**
     * One state for the whole app, for a status line that has room for one.
     *
     * Deliberately pessimistic: with three brokers and one of them down, the honest answer is not
     * "connected". A group whose broker is the broken one is receiving nothing, and a bar that
     * says everything is fine is how someone sits waiting for a message that cannot arrive. The
     * per-group screens can show which one — see [stateOf].
     */
    val aggregate: StateFlow<ConnectionState> = _states
        .map { byBroker ->
            when {
                byBroker.isEmpty() -> ConnectionState.Idle
                byBroker.values.all { it is ConnectionState.Connected } -> ConnectionState.Connected
                byBroker.values.any { it is ConnectionState.Connecting } -> ConnectionState.Connecting
                else -> byBroker.values.filterIsInstance<ConnectionState.Waiting>().firstOrNull()
                    ?: ConnectionState.Connecting
            }
        }
        // Eagerly, not WhileSubscribed: this is what the notification and the retry watcher both
        // read, and neither should have to keep a subscription alive to stop it going stale.
        .stateIn(scope, SharingStarted.Eagerly, ConnectionState.Idle)

    /** The connection for [brokerId] if one exists. Never creates: callers that only want to
     * publish or subscribe should not bring a broker up as a side effect. */
    fun peek(brokerId: String): MqttConnection? = connections[brokerId]

    fun stateOf(brokerId: String): ConnectionState =
        _states.value[brokerId] ?: ConnectionState.Idle

    /** True when at least one broker is up — which is when the outbox has somewhere to drain to. */
    val anyConnected: Boolean get() = connections.values.any { it.isConnected }

    /**
     * Connects to [broker] if it is not already connected, and leaves it connected.
     *
     * Idempotent, and safe to call from a retry loop: the per-connection mutex means a second
     * caller arriving mid-connect waits for that one rather than tearing it down.
     */
    suspend fun ensureConnected(broker: BrokerEntity, clientId: String) {
        val connection = lock.withLock { openLocked(broker.id) }
        connection.ensureConnected(broker, clientId)
    }

    /**
     * Closes every connection whose broker is not in [keep], because no group uses it any more.
     *
     * The last group on a broker leaving should take the socket with it. Otherwise a phone
     * accumulates connections to brokers it has no business talking to, each with its own
     * keep-alive waking the radio for a group that no longer exists.
     */
    suspend fun retireExcept(keep: Set<String>) {
        val going = lock.withLock { connections.keys - keep }
        for (brokerId in going) close(brokerId)
    }

    suspend fun disconnectAll() {
        for (brokerId in connections.keys.toList()) close(brokerId)
    }

    private suspend fun close(brokerId: String) {
        val connection = lock.withLock {
            pipes.remove(brokerId)?.cancel()
            watchers.remove(brokerId)?.cancel()
            connections.remove(brokerId)
        }
        connection?.disconnect()
        _states.value = _states.value - brokerId
    }

    /** Callable only while holding [lock]. */
    private fun openLocked(brokerId: String): MqttConnection {
        connections[brokerId]?.let { return it }
        val connection = MqttConnection()
        connections[brokerId] = connection
        // One pipe per connection, tagging what arrives so GroupSync can tell a message on
        // broker A from the identically-topicked message on broker B — two groups can legally
        // share a topic prefix and even a group id across different brokers.
        pipes[brokerId] = scope.launch {
            connection.incoming.collect { _incoming.tryEmit(RoutedMessage(brokerId, it)) }
        }
        watchers[brokerId] = scope.launch {
            connection.state.collect { state ->
                _states.value = _states.value + (brokerId to state)
            }
        }
        return connection
    }
}
