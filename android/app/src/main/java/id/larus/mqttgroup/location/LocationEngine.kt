package id.larus.mqttgroup.location

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Looper
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import id.larus.mqttgroup.data.db.GroupEntity
import id.larus.mqttgroup.data.repo.GroupRepository
import id.larus.mqttgroup.mqtt.LocationPayload
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Takes fixes and decides which of them are worth telling the group about.
 *
 * Lives inside [id.larus.mqttgroup.mqtt.MqttService] rather than in a service of its own, so
 * there is one foreground notification and one wakelock for the whole app instead of two.
 *
 * ### Why the platform provider and not the fused one
 *
 * plan.md called for `FusedLocationProviderClient`, which would mean `play-services-location`.
 * This app already refuses Google's libraries for QR scanning on the grounds that something
 * whose selling point is that nothing leaves the phone should not need them, and location is
 * the far stronger case of exactly that argument. So: [LocationManager], which since API 31
 * exposes the platform's own [LocationManager.FUSED_PROVIDER] — the same sensor fusion, no
 * dependency, and it works on a phone with no Play services at all. Below 31 it falls back to
 * GPS and network together and keeps whichever fix is better.
 *
 * What that costs is batching ([android.location.LocationRequest.getMaxUpdateDelayMillis], API
 * 31+ only): the modem cannot sleep through several intervals and deliver them in one wakeup.
 * At the intervals a family group actually uses — a minute at the fastest — that is a small
 * price for shipping no Google code.
 *
 * ### The publish gate
 *
 * Sampling and publishing are separate rates because they cost different things. A fix is cheap
 * and local; a publish is a radio wakeup on this phone and a notification-shaped event on
 * everyone else's. So every fix is stored, and only some are sent:
 *
 * ```
 * fix arrives
 *    ↓  has pushSec elapsed since the last publish?
 *    ├─ no  → store only
 *    └─ yes ↓  moved ≥ minDistanceM since the last published position?
 *           ├─ yes → publish
 *           └─ no  ↓  has 3× pushSec elapsed since the last publish?
 *                  ├─ yes → publish anyway (the heartbeat)
 *                  └─ no  → store only
 * ```
 *
 * The heartbeat is what separates "stationary" from "phone died", which the map otherwise
 * cannot tell apart — and which is the single question anyone opening it is actually asking.
 */
class LocationEngine(
    private val context: Context,
    private val groups: GroupRepository,
    private val scope: CoroutineScope,
) {
    private val locationManager = context.getSystemService<LocationManager>()

    private var job: Job? = null

    /** The request currently registered with the OS, so an unchanged settings write does not
     * tear down and rebuild a perfectly good one — restarting updates drops the provider's
     * warm state and costs a slow first fix every time. */
    private var active: Request? = null
    private var listener: LocationListener? = null

    /**
     * Whether this phone is currently sampling positions for any group — that is, whether some
     * group is both location-enabled by its admin and switched on by the owner.
     *
     * Read by [id.larus.mqttgroup.mqtt.MqttService] when Android 15 takes its `dataSync` budget
     * away: if location work is genuinely in progress the service can carry on as a `location`
     * foreground service, and if it is not there is no honest type left to run under.
     */
    val isSharing: Boolean get() = active != null

    /**
     * Per group, the last position we actually *published* — which is what both halves of the
     * gate measure against, and is deliberately not the same as the last position stored. Every
     * fix is stored; the gate exists precisely to send fewer of them, so measuring "have I moved"
     * against the stored one would compare a fix to itself and never publish anything again.
     *
     * In memory rather than a column, because it is only a rate limiter: starting fresh after a
     * restart costs one publish earlier than strictly necessary, which is the right way to be
     * wrong.
     */
    private val lastPublished = mutableMapOf<String, LocationPayload>()

    /**
     * The groups we are currently sharing with, kept from the collector so a fix does not have
     * to go to the database to find out where to send itself. Written on the collector's
     * coroutine and read on the main looper, hence @Volatile.
     */
    @Volatile
    private var sharing: List<GroupEntity> = emptyList()

    /** Fixes arrive on the main looper and are handled in a coroutine, so two could otherwise
     * overlap inside the gate and both decide they were the one allowed to publish. */
    private val gate = Mutex()

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch {
            // collectLatest so an admin changing the interval, or the user flipping their own
            // share switch, re-derives the request immediately rather than at the next tick.
            groups.observeAll().collectLatest { all ->
                apply(all.filter { it.locationEnabled && it.shareLocation })
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        unregister()
    }

    /** What the OS is asked for: the loosest requirement across every group we share with, so no
     * group is starved of a fix it needed by another group's slower settings. */
    private data class Request(val intervalMs: Long, val minDistanceM: Float)

    private fun apply(groupsSharing: List<GroupEntity>) {
        sharing = groupsSharing
        // Positions for a group we no longer share with would otherwise pin its gate open
        // forever, and quietly leak a last-known position across a "stop sharing".
        lastPublished.keys.retainAll(groupsSharing.mapTo(mutableSetOf()) { it.groupId })
        if (groupsSharing.isEmpty() || !hasPermission()) {
            unregister()
            return
        }
        val wanted = Request(
            intervalMs = groupsSharing.minOf { it.locSampleSec }.toLong() * 1_000L,
            // The gate proper is per group and lives below; this is only the hint handed to the
            // OS so it can skip waking us for a phone sitting still. It has to be the *smallest*
            // of the groups' distances — anything larger would silently starve the group that
            // asked for the finer granularity.
            minDistanceM = groupsSharing.minOf { it.locMinDistanceM }.toFloat(),
        )
        if (wanted == active) return
        register(wanted)
    }

    private fun register(request: Request) {
        val manager = locationManager ?: return
        unregister()
        val callback = LocationListener { fix -> onFix(fix) }
        for (provider in providers(manager)) {
            try {
                manager.requestLocationUpdates(
                    provider,
                    request.intervalMs,
                    request.minDistanceM,
                    callback,
                    Looper.getMainLooper(),
                )
            } catch (_: SecurityException) {
                // Permission revoked between the check above and here. Nothing to do but stop;
                // the next settings change re-evaluates.
                unregister()
                return
            } catch (_: IllegalArgumentException) {
                // The provider disappeared — GPS turned off in Settings, say. Try the next one
                // rather than giving up on all of them.
            }
        }
        listener = callback
        active = request
    }

    private fun unregister() {
        listener?.let { locationManager?.removeUpdates(it) }
        listener = null
        active = null
    }

    /**
     * The platform fused provider where it exists, since it is the same fusion the Play services
     * client wraps, minus the dependency. Older phones get GPS and network at once and keep
     * whichever fix is better — network alone is too coarse to place someone on a street, and
     * GPS alone never resolves indoors.
     */
    private fun providers(manager: LocationManager): List<String> = buildList {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            manager.allProviders.contains(LocationManager.FUSED_PROVIDER)
        ) {
            add(LocationManager.FUSED_PROVIDER)
        } else {
            if (manager.allProviders.contains(LocationManager.GPS_PROVIDER)) {
                add(LocationManager.GPS_PROVIDER)
            }
            if (manager.allProviders.contains(LocationManager.NETWORK_PROVIDER)) {
                add(LocationManager.NETWORK_PROVIDER)
            }
        }
    }

    private fun onFix(fix: Location) {
        val payload = LocationPayload(
            lat = fix.latitude,
            lon = fix.longitude,
            accuracy = if (fix.hasAccuracy()) fix.accuracy else 0f,
            speed = if (fix.hasSpeed()) fix.speed else 0f,
            bearing = if (fix.hasBearing()) fix.bearing else 0f,
            // The fix's own time, not now: a provider can hand over a cached position, and
            // stamping it with the current clock would present a ten-minute-old reading as
            // current on every other phone in the group.
            capturedAt = fix.time.takeIf { it > 0 } ?: System.currentTimeMillis(),
        )
        scope.launch {
            gate.withLock {
                for (group in sharing) {
                    if (shouldPublish(group, payload)) {
                        groups.publishLocation(group.groupId, payload)
                        lastPublished[group.groupId] = payload
                    } else {
                        groups.recordOwnLocation(group.groupId, payload)
                    }
                }
            }
        }
    }

    private fun shouldPublish(group: GroupEntity, fix: LocationPayload): Boolean {
        val pushMs = group.locPushSec.toLong() * 1_000L
        // Nothing published yet this process: send one straight away rather than making the
        // group wait out a full interval to learn someone just turned sharing on.
        val last = lastPublished[group.groupId] ?: return true

        val elapsed = fix.capturedAt - last.capturedAt
        if (elapsed < pushMs) return false
        if (group.locMinDistanceM <= 0) return true
        // Past 3x the interval, publish regardless of movement — see the heartbeat note above.
        if (elapsed >= pushMs * HEARTBEAT_MULTIPLIER) return true

        val moved = FloatArray(1)
        Location.distanceBetween(last.lat, last.lon, fix.lat, fix.lon, moved)
        return moved[0] >= group.locMinDistanceM
    }

    private fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED

    private companion object {
        const val HEARTBEAT_MULTIPLIER = 3
    }
}
