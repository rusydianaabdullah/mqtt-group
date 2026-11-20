package id.larus.mqttgroup.mqtt

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import android.os.Build
import androidx.annotation.RequiresApi
import id.larus.mqttgroup.FamilyGroupApp
import id.larus.mqttgroup.R
import id.larus.mqttgroup.location.LocationEngine
import id.larus.mqttgroup.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The one long-lived component. It holds the MQTT connection, the outbox pump and the location
 * engine — one service, one notification, one wakelock, rather than a second copy of all of that
 * running alongside.
 *
 * Started when a broker is configured and stopped when the user disconnects. Android will kill
 * it under memory pressure; START_STICKY plus the durable outbox means that costs a reconnect
 * and nothing else.
 */
class MqttService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var pump: OutboxPump
    private lateinit var sync: GroupSync
    private lateinit var location: LocationEngine

    /** Fired whenever the OS reports a network became available. HiveMQ's own automatic
     * reconnect only knows time, not connectivity — left alone it might still be sitting out
     * the rest of a 60s backoff from the *previous* network dropping just as this one came up,
     * which is the single biggest source of "message arrived late" in a pure-MQTT design with
     * no push to interrupt it. */
    private val reconnectTick = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    override fun onCreate() {
        super.onCreate()
        val container = (application as FamilyGroupApp).container
        pump = OutboxPump(container.outbox, container.connections, container.groups, scope)
        sync = GroupSync(
            container.database,
            container.groups,
            container.blobs,
            container.connections,
            scope,
            ChatNotifier(applicationContext),
            container.calls,
            container.activeGroupId,
        )

        location = LocationEngine(applicationContext, container.groups, scope)

        createChannel()
        goForeground()

        // Started unconditionally and left running: it registers nothing with the OS until some
        // group is both location-enabled by its admin and switched on by this phone's owner, and
        // it has to be watching for that moment to arrive — the alternative is a location engine
        // that only wakes up when the app is opened, which is the one time it is least needed.
        location.start()

        // Re-post the notification on a slow timer.
        //
        // The text is deliberately static — it used to narrate the connection state, which meant
        // the shade entry rewrote itself every time the socket so much as blinked. Nothing about
        // the notification changes now, so this timer is the *only* thing that re-posts it.
        //
        // Since Android 14 a user can swipe a foreground-service notification away, and without
        // this it would stay gone for as long as the service keeps running. The service then
        // runs with no
        // visible indicator at all: observed on a device as isForeground=true with a
        // foregroundNoti attached while the system's live notification list held nothing for us
        // and the shade read "You're all caught up". A foreground service the user cannot see is
        // the thing Play's policy exists to forbid, and it is dishonest besides.
        //
        // Through goForeground, not notificationManager().notify(). Re-posting the service's own
        // notification by id was observed to leave the service demoted — the record kept its
        // ServiceRecord but came back isForeground=false, types=0x0, and the notification lost
        // its FOREGROUND_SERVICE flag. startForeground re-asserts the notification *and* the
        // types together, which is the state that actually needs restoring.
        scope.launch {
            while (true) {
                delay(NOTIFICATION_REFRESH_MS)
                goForeground()
            }
        }

        scope.launch {
            // Which brokers are actually needed is a question about *groups*, not about the
            // broker table: a broker nobody's group points at is a saved configuration, not a
            // reason to hold a socket open. So this follows the group list, and the moment the
            // last group on a broker goes, that connection is retired.
            container.groups.observeAll().collect { all ->
                val wanted = all.map { it.brokerId }.toSet()
                container.connections.retireExcept(wanted)
                if (wanted.isEmpty()) {
                    pump.stop()
                    sync.stop()
                    return@collect
                }
                bringOnline(wanted)
            }
        }

        scope.launch {
            // Retry ticks are served separately from the group list, and with a plain collect —
            // see the note in bringOnline's caller history: collectLatest here would let each
            // tick cancel the connect the previous tick asked for.
            reconnectTick.debounce(1_000).collect {
                bringOnline(container.groups.observeAll().first().map { it.brokerId }.toSet())
            }
        }

        // The watcher that means nobody has to keep tapping Offline.
        //
        // HiveMQ's own automatic reconnect deliberately stops after
        // MqttConnection.MAX_RECONNECT_ATTEMPTS, which is right for a phone in a pocket and
        // wrong for a phone in a hand: once it has given up, the connection stays down until
        // something outside it asks again, and the only things that otherwise do are the
        // keep-alive worker's fifteen-minute tick and the user's thumb. So while the app is
        // actually on screen, this keeps asking.
        //
        // Mapped to a plain boolean and made distinct before collecting, because Waiting carries
        // a *reason* that changes with every failed attempt — collecting the raw state would
        // restart the loop, and so reset the backoff to its minimum, on each of them. Reduced to
        // "visible and not connected", the flag only flips when one of those two things really
        // changes, so one continuous backoff runs per outage.
        //
        // isConnected, not the state flow, decides when to stop: the flow is what the *user* is
        // shown and can lag a beat behind the socket, while this loop needs the socket's own
        // answer.
        scope.launch {
            container.appVisible
                .combine(container.connections.aggregate) { visible, state ->
                    visible && state != ConnectionState.Connected
                }
                .distinctUntilChanged()
                .collectLatest { keepTrying ->
                    if (!keepTrying) return@collectLatest
                    var wait = FOREGROUND_RETRY_MIN_MS
                    while (true) {
                        delay(wait)
                        if (container.connections.aggregate.value is ConnectionState.Connected) break
                        reconnectTick.tryEmit(Unit)
                        wait = (wait * 2).coerceAtMost(FOREGROUND_RETRY_MAX_MS)
                    }
                }
        }

        val connectivityManager = getSystemService<ConnectivityManager>()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                reconnectTick.tryEmit(Unit)
            }
        }
        connectivityManager?.registerDefaultNetworkCallback(callback)
        networkCallback = callback
    }

    /**
     * Make sure every broker in [brokerIds] is connected, and that the pump and the sync are
     * running on top of them.
     *
     * Idempotent and cheap when there is nothing to do, so it is safe to call from a retry loop —
     * [ConnectionPool.ensureConnected] carries that guarantee. Each broker is attempted on its
     * own: one unreachable host must not stop the others from coming up, which is the whole
     * point of the groups having separate brokers in the first place.
     */
    private suspend fun bringOnline(brokerIds: Set<String>) {
        val container = (application as FamilyGroupApp).container
        val identity = container.identity.ensure()
        for (brokerId in brokerIds) {
            val broker = container.brokers.get(brokerId) ?: continue
            // The client id is per broker as well as per phone. Two connections from one phone
            // sharing an identifier is a broker kicking one of them off the moment the other
            // connects, forever — and it looks exactly like a flapping network.
            container.connections.ensureConnected(
                broker,
                clientId = "fg-${identity.userId.take(8)}-${brokerId.take(6)}",
            )
        }
        pump.start()
        sync.start()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Every start is a chance to pick the service type up again: the usual reason the
        // location type was refused at onCreate is that location permission had not been granted
        // yet, and the screen that grants it starts the service right afterwards — from the
        // foreground, which is exactly when the OS allows the type to be added.
        goForeground()
        // Any start is also a chance to come back online. The automatic reconnect gives up after
        // MqttConnection.MAX_RECONNECT_ATTEMPTS, so something has to pick it up again afterwards:
        // this is what turns the fifteen-minute keep-alive worker, a boot, and a tap on a
        // notification into a reconnect rather than a no-op. Free when there is nothing to do:
        // a tick reaching an already-connected client is a no-op inside ensureConnected.
        if ((application as FamilyGroupApp).container.connections.aggregate.value !is ConnectionState.Connected) {
            reconnectTick.tryEmit(Unit)
        }
        when (intent?.action) {
            ACTION_WAKE -> pump.wake()
            // An explicit ask from the Groups screen's reconnect button: force a fresh attempt
            // right now regardless of whatever HiveMQ's own backoff is mid-way through.
            // disconnect() first, rather than emitting the tick directly, is what gets past the
            // reconnectTick flow's own "skip if already Connected/Connecting" guard — after this,
            // the state is Idle, so the guard no longer applies.
            ACTION_RECONNECT -> {
                val container = (application as FamilyGroupApp).container
                scope.launch {
                    container.connections.disconnectAll()
                    reconnectTick.tryEmit(Unit)
                }
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        networkCallback?.let { getSystemService<ConnectivityManager>()?.unregisterNetworkCallback(it) }
        pump.stop()
        sync.stop()
        // Before the scope is cancelled: removeUpdates is what actually releases the GPS, and
        // leaking a registered listener past the service is how a phone ends up warm in a pocket.
        location.stop()
        val container = (application as FamilyGroupApp).container
        // Bounded and blocking, not scope.launch{} followed by scope.cancel(): the launch would
        // almost always be cancelled before it ever got to run, since cancel() on the scope that
        // owns it fires right on its heels with nothing to make it wait its turn. That silently
        // skipped disconnectLocked()'s _state update to Idle, leaving it stuck reporting
        // Connected long after the client was gone — which then made the very next reconnect
        // attempt skip itself entirely, believing there was nothing to do. Two seconds is enough
        // for a disconnect and short enough that onDestroy still returns quickly; the will exists
        // precisely so that an abrupt exit past that bound still tells everyone else we are gone.
        runBlocking { withTimeoutOrNull(2_000) { container.connections.disconnectAll() } }
        scope.cancel()
        super.onDestroy()
    }

    /**
     * Android 15 gives a `dataSync` foreground service six hours in any twenty-four, and then
     * calls this. The service must drop the type or stop within seconds, or the system kills the
     * process with `ForegroundServiceDidNotStopInTimeException` — a crash on a timer, which is
     * what this app shipped with before.
     *
     * Two ways out, and which one applies depends on what the phone is actually doing:
     *
     * - Sharing location for some group: re-enter the foreground as a `location` service only.
     *   That type has no such budget, the connection survives, and nothing the user can see
     *   changes. Dropping `dataSync` costs nothing here because the same socket serves both.
     * - Not sharing: there is no type left to run under, so stop. The connection goes down and
     *   messages wait. [ConnectionKeepAliveWorker] and the next app launch both bring it back,
     *   and the budget itself resets when the user next brings the app to the foreground — which
     *   for a chat app is the ordinary course of a day.
     *
     * Six hours is the floor, not the expectation: the budget resets on foreground use, so a
     * phone whose owner opens the app at all rarely reaches it.
     */
    @RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
    override fun onTimeout(startId: Int) = onDataSyncBudgetSpent()

    /** API 36 added the type argument and deprecated the single-argument form above. Both are
     * overridden because either may be called depending on the platform the phone is running. */
    @RequiresApi(36)
    override fun onTimeout(startId: Int, fgsType: Int) = onDataSyncBudgetSpent()

    private fun onDataSyncBudgetSpent() {
        if (hasLocationPermission() && location.isSharing) {
            runCatching {
                startForeground(
                    NOTIFICATION_ID,
                    notification(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
                )
            }.onSuccess { return }
        }
        stopSelf()
    }

    /**
     * The manifest declares `dataSync|location`, and the two-argument [startForeground] asks for
     * *every* declared type — which throws a SecurityException, killing the process, on a phone
     * that has not granted location. So the type is named explicitly and location is claimed only
     * when it has actually been granted; a chat-only user runs as a plain data-sync service.
     */
    private fun goForeground() {
        val notification = notification()
        val wanted = ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC or
            if (hasLocationPermission()) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0
        try {
            startForeground(NOTIFICATION_ID, notification, wanted)
        } catch (_: SecurityException) {
            // Permission granted but the OS still refused the location type. This is the
            // expected path after a boot or a keep-alive restart: the service is starting from
            // the background, and without ACCESS_BACKGROUND_LOCATION — which this app
            // deliberately does not declare, see the manifest — the location type cannot be
            // added there. Data sync alone keeps the connection alive and messages arriving;
            // the next start from the foreground upgrades it and positions resume.
            try {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
                )
            } catch (_: Exception) {
                // Nothing left to try. Better a service the OS may reclaim than a crash loop.
            }
        }
    }

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private fun notification(): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.notification_connection_text))
            .setContentIntent(open)
            .setOngoing(true)
            // Silent, minimal, and unchanging: this notification is a legal requirement for a
            // background connection, not something anyone wants to be told about. Settings links
            // to its channel so it can be hidden from the shade without stopping the service.
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setShowWhen(false)
            .build()
    }

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.channel_connection),
            NotificationManager.IMPORTANCE_MIN,
        ).apply {
            description = getString(R.string.channel_connection_description)
            setShowBadge(false)
        }
        notificationManager().createNotificationChannel(channel)
    }

    private fun notificationManager() = getSystemService(NotificationManager::class.java)

    companion object {
        const val CHANNEL_ID = "connection"
        private const val NOTIFICATION_ID = 1
        const val ACTION_WAKE = "id.larus.mqttgroup.WAKE"
        const val ACTION_RECONNECT = "id.larus.mqttgroup.RECONNECT"

        /** How long the foreground watcher waits before its first retry, and the ceiling it
         * backs off to. Five seconds is short enough that a dropped connection heals before
         * anyone reaches for the button, and thirty is the point past which more attempts stop
         * buying anything on a link that is genuinely down. */
        /** How often the foreground notification is re-posted, so a dismissal is temporary. */
        private const val NOTIFICATION_REFRESH_MS = 4 * 60_000L

        private const val FOREGROUND_RETRY_MIN_MS = 5_000L
        private const val FOREGROUND_RETRY_MAX_MS = 30_000L

        fun start(context: Context) {
            context.startForegroundService(Intent(context, MqttService::class.java))
        }

        /** The Groups screen's manual reconnect button. Starts the service first if it is not
         * already running — a phone that has not granted background persistence may well have
         * had it stopped, and this is the one button that has to work regardless. */
        fun reconnect(context: Context) {
            context.startForegroundService(
                Intent(context, MqttService::class.java).setAction(ACTION_RECONNECT),
            )
        }

        /** Nudges the pump after something has been enqueued. */
        fun wake(context: Context) {
            context.startForegroundService(
                Intent(context, MqttService::class.java).setAction(ACTION_WAKE),
            )
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, MqttService::class.java))
        }
    }
}
