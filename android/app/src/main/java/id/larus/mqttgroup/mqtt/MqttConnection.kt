package id.larus.mqttgroup.mqtt

import com.hivemq.client.mqtt.MqttClient
import com.hivemq.client.mqtt.MqttClientSslConfig
import com.hivemq.client.mqtt.MqttGlobalPublishFilter
import com.hivemq.client.mqtt.datatypes.MqttQos
import com.hivemq.client.mqtt.mqtt5.Mqtt5AsyncClient
import id.larus.mqttgroup.data.db.BrokerEntity
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.future.await
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.X509TrustManager

/** What the UI shows in the status line, and what the outbox pump waits on. */
sealed interface ConnectionState {
    data object Idle : ConnectionState
    data object Connecting : ConnectionState
    data object Connected : ConnectionState
    /** Reconnecting on its own. [reason] is for the user, not for a retry decision. */
    data class Waiting(val reason: String) : ConnectionState
}

/** A payload as it arrived, before anything has tried to decrypt it. */
data class IncomingMessage(val topic: String, val payload: ByteArray) {
    override fun equals(other: Any?): Boolean =
        other is IncomingMessage && topic == other.topic && payload.contentEquals(other.payload)

    override fun hashCode(): Int = topic.hashCode() * 31 + payload.contentHashCode()
}

/**
 * One MQTT connection, owned by the foreground service and shared by everything that publishes.
 *
 * Reconnection is HiveMQ's, not ours: it already backs off, and re-establishing a session on a
 * phone that has just changed from Wi-Fi to mobile data is exactly the fiddly case a library
 * gets right more often than we would. What we keep is the part that is ours — a durable queue
 * of what still has to go out, so nothing depends on a reconnect happening soon.
 *
 * Nothing here knows about encryption. Payloads arrive and leave as opaque bytes.
 */
class MqttConnection {

    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    /**
     * Replays a little, so a collector that starts a moment after a burst of retained messages
     * still sees them. Extra buffer because retained delivery on subscribe can be bursty.
     */
    private val _incoming = MutableSharedFlow<IncomingMessage>(replay = 16, extraBufferCapacity = 256)
    val incoming: SharedFlow<IncomingMessage> = _incoming.asSharedFlow()

    @Volatile
    private var client: Mqtt5AsyncClient? = null

    /**
     * Which client is the live one. Bumped by every [connect].
     *
     * Every client built here registers listeners that write [_state], and building a new client
     * does not destroy the old one — HiveMQ keeps its own threads, and a client that was
     * mid-automatic-reconnect when we replaced it goes on firing its disconnected listener
     * afterwards. Without this guard those late callbacks overwrite the *current* client's
     * Connected with a Waiting from a connection nobody is using any more: the app reports
     * itself offline while its socket is open, which is exactly the state a user cannot argue
     * with and cannot fix.
     */
    @Volatile
    private var generation = 0

    val isConnected: Boolean get() = client?.state?.isConnected == true

    /**
     * Serializes every [connect] and [disconnect] against each other. Without it, a network
     * blip that fires the connectivity callback while a broker-settings change (or another
     * blip) is already mid-connect races two CONNECT packets for the same client id onto the
     * wire at once — coroutine cancellation on our side does not un-send a packet already
     * handed to the socket. Brokers vary in how they treat that, but "reject the connection as
     * unauthorized" is a real, observed outcome, not a hypothetical one.
     */
    private val lock = Mutex()

    /**
     * Connects, and stays connected until [disconnect]. [will] is published by the broker if
     * this phone disappears without saying goodbye, which is how everyone else learns we are
     * offline without polling.
     */
    /**
     * Connects if not already connected, and does nothing if it is.
     *
     * This is what callers who mean "make sure we are online" should use. It is safe to call
     * from a retry loop as often as you like: the mutex serialises it, so a second caller
     * arriving while a connect is in flight waits for that one and then finds it done, rather
     * than tearing down a connection that was one packet away from succeeding.
     */
    suspend fun ensureConnected(broker: BrokerEntity, clientId: String, will: Will? = null) =
        lock.withLock {
            if (client?.state?.isConnected == true) return@withLock
            connectLocked(broker, clientId, will)
        }

    suspend fun connect(broker: BrokerEntity, clientId: String, will: Will? = null) = lock.withLock {
        connectLocked(broker, clientId, will)
    }

    private suspend fun connectLocked(broker: BrokerEntity, clientId: String, will: Will?) {
        disconnectLocked()
        val mine = ++generation
        _state.value = ConnectionState.Connecting

        val built = buildClient(broker, clientId, mine)
        client = built

        try {
            var connect = built.connectWith()
                // A clean start with a long session expiry: the broker keeps our subscriptions
                // and any QoS 1 messages we missed while the phone was asleep.
                .cleanStart(false)
                .sessionExpiryInterval(SESSION_EXPIRY_SECONDS)
                .keepAlive(KEEP_ALIVE_SECONDS)
            if (broker.username != null && broker.password != null) {
                connect = connect.simpleAuth()
                    .username(broker.username)
                    .password(broker.password.toByteArray())
                    .applySimpleAuth()
            }
            if (will != null) {
                connect = connect.willPublish()
                    .topic(will.topic)
                    .payload(will.payload)
                    .qos(MqttQos.AT_LEAST_ONCE)
                    .retain(true)
                    .applyWillPublish()
            }
            val connAck = connect.send().await()
            // The one piece of ground truth for "did the broker actually resume our session, or
            // start us over with none" — logged rather than guessed at, since cleanStart(false)
            // and a session expiry interval are only a request; the broker's CONNACK is what
            // says whether it was honoured. A broker that does not support MQTT 5 session
            // expiry, or caps it below what we asked for, will show sessionPresent=false here on
            // every reconnect no matter what this client sends.
            android.util.Log.i(
                "MqttConnection",
                "Connected to ${broker.host}:${broker.port} as $clientId — " +
                    "sessionPresent=${connAck.isSessionPresent}",
            )
            _state.value = ConnectionState.Connected
        } catch (e: kotlinx.coroutines.CancellationException) {
            // Never swallowed. It used to be — caught by the Exception branch below — which
            // turned "this coroutine was cancelled" into "the broker refused us", left the
            // caller believing it was still connecting, and let the cancelled block run on past
            // the point it was supposed to stop.
            _state.value = ConnectionState.Idle
            throw e
        } catch (e: Exception) {
            _state.value = ConnectionState.Waiting(e.readable())
            // Not rethrown: the automatic reconnect is still running, so this is a state to
            // display rather than a failure for the caller to handle.
        }
    }

    /** Subscribes to [filter]. Routing what arrives into [incoming] is not this call's job any
     * more — see the global listener registered in [buildClient]. */
    suspend fun subscribe(filter: String, qos: MqttQos = MqttQos.AT_LEAST_ONCE) {
        val active = client ?: return
        active.subscribeWith()
            .topicFilter(filter)
            .qos(qos)
            .send()
            .await()
    }

    suspend fun unsubscribe(filter: String) {
        client?.unsubscribeWith()?.topicFilter(filter)?.send()?.await()
    }

    /**
     * Publishes, returning false rather than throwing when it did not get through — the caller
     * is the outbox, and a failure there means "leave the row and try again later".
     */
    suspend fun publish(topic: String, payload: ByteArray, qos: Int, retained: Boolean): Boolean {
        val active = client ?: return false
        return try {
            active.publishWith()
                .topic(topic)
                .payload(payload)
                .qos(MqttQos.fromCode(qos) ?: MqttQos.AT_LEAST_ONCE)
                .retain(retained)
                .send()
                .await()
            true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Publishes at QoS 0 without waiting for anything and without reporting whether it worked.
     *
     * For voice, and for nothing else. Every other publish in this app goes through the outbox
     * precisely so that "did this leave the phone" has an answer; a voice packet is the one
     * payload where the answer is worthless, because by the time it could be acted on the packet
     * is already too old to play. Seventeen of these a second must also not suspend a capture
     * thread that has to be back for the next 20ms of audio.
     *
     * Silently does nothing when there is no connection. That is not a swallowed error: a call
     * whose broker has dropped is already being torn down by the connection state, and logging
     * seventeen failures a second on the way there helps nobody.
     */
    fun publishNow(topic: String, payload: ByteArray) {
        val active = client ?: return
        runCatching {
            active.publishWith()
                .topic(topic)
                .payload(payload)
                .qos(MqttQos.AT_MOST_ONCE)
                .retain(false)
                .send()
        }
    }

    suspend fun disconnect() = lock.withLock { disconnectLocked() }

    /** The actual teardown, callable only while already holding [lock] — [connect] needs this
     * without recursing into a non-reentrant mutex a second time. */
    private suspend fun disconnectLocked() {
        val active = client ?: return
        client = null
        runCatching {
            active.disconnectWith()
                // MQTT 5 lets a client-sent DISCONNECT override the session expiry negotiated
                // at CONNECT — a bare disconnect() defaults that to zero on some brokers,
                // telling it to drop our subscriptions and anything queued for us right then.
                // Every disconnect this app ever sends — a network blip, a broker-settings
                // change, the manual reconnect button, even the background policy stopping the
                // service — means "be right back," never "forget me," so this always restates
                // the same interval CONNECT asked for.
                .sessionExpiryInterval(SESSION_EXPIRY_SECONDS)
                .send()
                .await()
        }
        _state.value = ConnectionState.Idle
    }

    private fun buildClient(
        broker: BrokerEntity,
        clientId: String,
        myGeneration: Int,
    ): Mqtt5AsyncClient {
        var builder = MqttClient.builder()
            .useMqttVersion5()
            .identifier(clientId)
            .serverHost(broker.host)
            .serverPort(broker.port)
            .automaticReconnect()
            .initialDelay(1, TimeUnit.SECONDS)
            // Capped rather than unbounded: a phone that wakes from a long doze should retry
            // within a minute, not sit out an hour-long backoff.
            .maxDelay(MAX_RECONNECT_DELAY_SECONDS, TimeUnit.SECONDS)
            .applyAutomaticReconnect()
            .addConnectedListener {
                if (myGeneration == generation) _state.value = ConnectionState.Connected
            }
            .addDisconnectedListener { context ->
                // A client we have already replaced. Stop its retry loop and, above all, do not
                // let it touch _state — see [generation].
                if (myGeneration != generation) {
                    context.reconnector.reconnect(false)
                    return@addDisconnectedListener
                }
                // HiveMQ's automatic reconnect retries forever by default. Forever is the wrong
                // answer on a phone: a broker that is misconfigured, renamed or simply gone
                // means every one of those attempts is a radio wake that will never succeed, and
                // the person watching "Reconnecting…" has no way to tell it from a bad minute of
                // signal. So it gets [MAX_RECONNECT_ATTEMPTS] tries — with HiveMQ's own backoff
                // between them, which reaches the one-minute cap well inside seven — and then
                // stops and says so.
                //
                // Stopping is not giving up. The connectivity callback in MqttService reconnects
                // the moment a network comes back, the keep-alive worker nudges it every fifteen
                // minutes, and the Offline button in the bar is there for someone who would
                // rather not wait. attempts resets itself on every successful connect, so a
                // flaky link gets a fresh seven each time rather than a lifetime budget.
                if (context.reconnector.attempts >= MAX_RECONNECT_ATTEMPTS - 1) {
                    context.reconnector.reconnect(false)
                    _state.value = ConnectionState.Waiting("can't reach the broker")
                } else {
                    _state.value = ConnectionState.Waiting(context.cause.readable())
                }
            }

        if (broker.tls) {
            builder = builder.sslConfig(sslConfig(broker.allowInsecureTls))
        }
        return builder.buildAsync().also { built ->
            // Global, not per-subscribeWith(): a resumed session (sessionPresent=true in the
            // CONNACK) can have the broker start replaying everything it queued immediately,
            // before this app's own subscribe() calls — made asynchronously afterward, one topic
            // at a time — have actually run. A callback attached to a single subscribeWith()
            // call only takes effect once that call's SUBACK arrives; registered here, before
            // connect() even starts, this catches a replayed backlog regardless of how that race
            // comes out. This was the actual cause of "message sent while offline never arrives
            // after reconnecting" even though the broker itself was resuming the session
            // correctly the whole time.
            built.publishes(MqttGlobalPublishFilter.ALL) { publish ->
                _incoming.tryEmit(IncomingMessage(publish.topic.toString(), publish.payloadAsBytes))
            }
        }
    }

    private fun sslConfig(allowInsecure: Boolean): MqttClientSslConfig {
        val builder = MqttClientSslConfig.builder()
        // A home broker with a self-signed certificate is a normal way to run this app, and the
        // alternative — asking a phone user to install a CA — is worse. The setting is off by
        // default and the UI says plainly what turning it on costs.
        return if (allowInsecure) {
            builder.trustManagerFactory(TrustEverything.factory)
                .hostnameVerifier { _, _ -> true }
                .build()
        } else {
            builder.build()
        }
    }

    /** The last-will message: what the broker publishes for us if we vanish. */
    data class Will(val topic: String, val payload: ByteArray) {
        override fun equals(other: Any?): Boolean =
            other is Will && topic == other.topic && payload.contentEquals(other.payload)

        override fun hashCode(): Int = topic.hashCode() * 31 + payload.contentHashCode()
    }

    private companion object {
        // A week rather than a day: a phone that is off, in a dead zone, or simply uninstalled-
        // and-not-yet-reinstalled for a few days should still come back to a broker that kept
        // its session — everything queued at QoS 1 in the meantime — rather than starting fresh.
        const val SESSION_EXPIRY_SECONDS = 7 * 86_400L
        const val KEEP_ALIVE_SECONDS = 60
        const val MAX_RECONNECT_DELAY_SECONDS = 60L

        /** Consecutive automatic reconnect attempts before the client stops trying by itself.
         * Counted from the first attempt after a disconnect and reset by any success. */
        const val MAX_RECONNECT_ATTEMPTS = 7
    }
}

/** Turns a connection failure into something worth putting in front of a person. */
internal fun Throwable?.readable(): String = when {
    this == null -> "Disconnected"
    message.isNullOrBlank() -> this::class.java.simpleName
    else -> message!!
}

/**
 * Trusts any certificate, for the self-signed-broker case only. Isolated here so the one place
 * that disables certificate checking is easy to find and impossible to enable by accident.
 */
private object TrustEverything {
    val factory: javax.net.ssl.TrustManagerFactory =
        object : javax.net.ssl.TrustManagerFactory(
            object : javax.net.ssl.TrustManagerFactorySpi() {
                override fun engineInit(ks: java.security.KeyStore?) = Unit
                override fun engineInit(spec: javax.net.ssl.ManagerFactoryParameters?) = Unit
                override fun engineGetTrustManagers(): Array<javax.net.ssl.TrustManager> =
                    arrayOf(object : X509TrustManager {
                        override fun checkClientTrusted(chain: Array<X509Certificate>?, authType: String?) = Unit
                        override fun checkServerTrusted(chain: Array<X509Certificate>?, authType: String?) = Unit
                        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
                    })
            },
            null,
            "insecure",
        ) {}
}
