package id.larus.mqttgroup.call

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import id.larus.mqttgroup.crypto.Envelope
import id.larus.mqttgroup.mqtt.AudioPacket
import id.larus.mqttgroup.mqtt.ConnectionPool
import id.larus.mqttgroup.data.repo.GroupRepository
import id.larus.mqttgroup.data.repo.IdentityRepository
import id.larus.mqttgroup.mqtt.Call
import id.larus.mqttgroup.mqtt.Topics
import id.larus.mqttgroup.push.PushSender
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

/**
 * Everything a call needs that [CallMachine] refuses to know: the database, the outbox, a clock,
 * and who this phone is.
 *
 * The split is the point. Which message a red button sends, and whether a second offer rings or
 * is refused, are decided by a pure function with tests. This class only carries events to it
 * and does what it says — so the parts that cannot be tested without a phone are also the parts
 * with no decisions in them.
 *
 * One call at a time, for the whole process. A phone has one speaker and one person holding it.
 */
class CallEngine(
    private val appContext: Context,
    private val identity: IdentityRepository,
    private val groups: GroupRepository,
    private val connections: ConnectionPool,
    private val scope: CoroutineScope,
    /** Raised when a call starts ringing, silenced whenever it stops. Supplied by the app so
     * that a unit test — or a future headless build — can hand over something that does
     * nothing. */
    private val ringer: Ringer,
    private val notifier: CallNotifier,
) {
    private val _state = MutableStateFlow<CallState>(CallState.Idle)
    val state: StateFlow<CallState> = _state.asStateFlow()

    /** The group whose topic this call's messages travel on. A call belongs to a group because
     * that is whose key it is encrypted with; the wire format never says so, since the topic
     * already does. */
    @Volatile
    var groupId: String? = null
        private set

    /** Who we are talking to, as a name to show. Filled in from the member list, and null until
     * it is — see [peerName] for why a call can start ringing before this is known. */
    private val _peerName = MutableStateFlow<String?>(null)
    val peerName: StateFlow<String?> = _peerName.asStateFlow()

    /** Serialises the whole reduce-then-act cycle. Events arrive from the MQTT receive loop, a
     * timer and the UI thread at once, and a call that is read, decided and written in three
     * steps has to be the only one doing it. */
    private val lock = Mutex()

    private var timeoutJob: Job? = null

    /**
     * How long the broker took to carry one message there and back, in milliseconds, or null
     * before it is known.
     *
     * Measured from publishing the offer to receiving the callee's `ring` — a genuine round
     * trip through the broker, timed entirely on *this* phone's monotonic clock, so it needs no
     * agreement about the time of day and none of the clock-skew care that made
     * [Call.Offer.ttlSec] a duration.
     *
     * It exists because "the call is delayed" has two very different causes and the fix differs
     * completely. Buffering is ours to cut and we have. The path to the broker is not: a broker
     * on another continent costs a few hundred milliseconds that no amount of tuning here will
     * recover, and the only answer is a broker that is closer. This number says which one you
     * are looking at.
     *
     * It includes the far end's own handling of the offer, which is small against a network.
     */
    private val _roundTripMillis = MutableStateFlow<Long?>(null)
    val roundTripMillis: StateFlow<Long?> = _roundTripMillis.asStateFlow()

    /** Monotonic, so it survives the clock being changed mid-call. */
    private var offerAt: Long = 0L

    /** Start a call: to one member, or to the whole group when [peerId] is null. */
    fun dial(groupId: String, peerId: String?, mode: Call.Mode = Call.Mode.BUZZ) {
        scope.launch {
            val callId = UUID.randomUUID().toString()
            this@CallEngine.groupId = groupId
            // A group call has no single name to show; the screen lists who is in the room
            // instead, which is the question a group call actually asks.
            if (peerId != null) resolveName(groupId, peerId) else _peerName.value = null
            apply(groupId, Event.Dial(callId, peerId, mode), ttlSec = CallMachine.ttlFor(mode))
            // The offer is already on the outbox; this only wakes a callee who is not currently
            // connected to the broker. Fired after, and never waited on: a call whose push
            // failed still reaches a phone that was online all along.
            identity.get()?.userId?.let { PushSender.notifyCall(groupId, it, callId, mode.wire) }
        }
    }

    fun accept() = fire(Event.Accept)

    /** Decline, cancel or hang up — whichever this call's state makes it. */
    fun hangup() = fire(Event.Hangup)

    fun dismiss() = fire(Event.Dismiss)

    /**
     * A call message arrived on a group's topic and opened under its key.
     *
     * [senderId] is [Envelope.Header.senderId], the authenticated one — never anything read out
     * of the body. That is what stops a member publishing a ring on another member's behalf,
     * and it is the same rule profile announcements follow.
     */
    fun onMessage(groupId: String, senderId: String, message: Call) {
        scope.launch {
            // This build carries no voice — the capture, codec and playback are gone, along
            // with the foreground-service permissions they needed. AUDIO survives in the wire
            // vocabulary because the format is shared with the web app, so an offer can still
            // arrive carrying it; ringing for one would promise a conversation this phone has
            // no way to hold. Dropped here rather than in the machine, which is pure signalling
            // and has no business knowing what a device can do.
            if (message is Call.Offer && message.mode != Call.Mode.BUZZ) return@launch
            // An offer is the one message that can start a call, so it is the one that sets
            // which group we are in. Everything after it is already scoped by the machine's
            // call-id check.
            if (message is Call.Offer) {
                this@CallEngine.groupId = groupId
                resolveName(groupId, senderId)
            } else if (groupId != this@CallEngine.groupId) {
                // Signalling for a call in a different group than the one we are on. The machine
                // would drop it on the call id anyway; stopping here saves the round trip.
                return@launch
            }
            apply(groupId, Event.Received(senderId, message), ttlSec = (message as? Call.Offer)?.ttlSec)
        }
    }

    private fun fire(event: Event) {
        scope.launch {
            val group = groupId ?: return@launch
            apply(group, event, ttlSec = null)
        }
    }

    /**
     * The one path that changes state: reduce, publish what the machine asked for, then make the
     * phone match the new state.
     *
     * [ttlSec] is how long a ring should last when this event starts one — the caller's own
     * [CallMachine.TTL_SEC] when dialling, and the *offer's* value when answering one, so that
     * both ends count roughly the same span even though each counts it on its own clock.
     */
    private suspend fun apply(groupId: String, event: Event, ttlSec: Int?) = lock.withLock {
        val self = identity.get()?.userId ?: return@withLock
        val before = _state.value
        val transition = CallMachine.reduce(before, event, self, System.currentTimeMillis())
        val after = transition.state
        if (after == before && transition.publish == null) return@withLock

        // Started before the publish rather than after, so the measurement covers the whole
        // path the offer actually travels — the outbox and the pump included, since a person
        // waiting for a call to connect is waiting for all of it.
        if (event is Event.Dial) {
            offerAt = SystemClock.elapsedRealtime()
            _roundTripMillis.value = null
        }

        _state.value = after
        transition.publish?.let { publish(groupId, it) }

        val nowReached = before is CallState.Outgoing && !before.reached &&
            after is CallState.Outgoing && after.reached
        if (nowReached && offerAt != 0L) {
            _roundTripMillis.value = SystemClock.elapsedRealtime() - offerAt
        }

        // A callee tells the caller it is ringing only once it really is — after the state has
        // moved and the ringer has been told — because the whole value of that message is that
        // it reports a fact about this phone rather than an intention.
        val startedRinging = before !is CallState.Incoming && after is CallState.Incoming

        react(before, after, ttlSec)

        if (startedRinging && after is CallState.Incoming) {
            publish(groupId, Call.Ring(after.callId))
        }
    }

    /** Makes the phone match the state: ring or stop, show or clear the notification, arm or
     * cancel the timer. Nothing here decides anything. */
    private fun react(before: CallState, after: CallState, ttlSec: Int?) {
        when (after) {
            is CallState.Incoming -> {
                ringer.startIncoming(after.mode)
                notifier.showIncoming(after, _peerName.value)
                arm(after.callId, ttlSec ?: CallMachine.TTL_SEC)
            }
            is CallState.Outgoing -> {
                // No sound for our own dialling in this phase: a ringback tone is media, and
                // media is phase 9. The screen says "Calling…" and then "Ringing…".
                notifier.showOngoing(after, _peerName.value)
                if (before !is CallState.Outgoing) arm(after.callId, ttlSec ?: CallMachine.TTL_SEC)
            }
            is CallState.Connected -> {
                ringer.stop()
                disarm()
                notifier.showOngoing(after, _peerName.value)
                // Once, on the transition. Answering does not happen twice, but the notification
                // is refreshed on other events and starting a second microphone would be a
                // spectacular way to find that out.
            }
            is CallState.Ended -> {
                ringer.stop()
                disarm()
                notifier.showEnded(after, _peerName.value)
                // Nothing schedules a Dismiss: the screen does it when the person has seen the
                // outcome, and a phone with no screen showing gets there via the notification
                // timing out. A state machine that erases its own last word would race the UI
                // that is trying to display it.
            }
            is CallState.Idle -> {
                ringer.stop()
                disarm()
                notifier.clear()
                _peerName.value = null
                groupId = null
            }
        }
    }

    /**
     * Arms the one timer a call has, counted from *now* rather than from any timestamp on the
     * wire — see [Call.Offer.ttlSec]. The call id rides along so that a timer which fires after
     * its call has been replaced can be recognised and ignored by the machine.
     */
    private fun arm(callId: String, ttlSec: Int) {
        disarm()
        timeoutJob = scope.launch {
            delay(ttlSec * 1_000L)
            val group = groupId ?: return@launch
            apply(group, Event.Timeout(callId), ttlSec = null)
        }
    }

    private fun disarm() {
        timeoutJob?.cancel()
        timeoutJob = null
    }

    /**
     * Onto the outbox like everything else in this app, and with a deadline, which nothing else
     * in this app has. A ring that survives an hour offline is a phone ringing about a call that
     * is long over — see [id.larus.mqttgroup.data.db.OutboxEntity.expiresAt].
     */
    private suspend fun publish(groupId: String, message: Call) {
        val sent = groups.publish(
            groupId = groupId,
            topicSuffix = Topics::call,
            plaintext = message.encode(),
            qos = 1,
            retained = false,
            expiresInMillis = SIGNAL_LIFE_MILLIS,
        )
        if (sent == null) Log.w(TAG, "no key for $groupId; call message dropped")
    }

    /**
     * Looks the caller's name up from the member list.
     *
     * A call can start ringing before this resolves, and that is deliberate: on a phone woken by
     * a push, ringing three seconds late so the screen can say a name is worse than ringing now
     * and filling the name in. The UI reads [peerName] and shows the number-plate-free "Incoming
     * call" until it appears.
     *
     * And when it does appear it is a *claim*, not a proof — a shared group key gives no
     * per-sender authenticity, so any member could have sent this offer under any member's id.
     * The screen must not say otherwise.
     */
    private suspend fun resolveName(groupId: String, userId: String) {
        _peerName.value = runCatching { groups.member(groupId, userId)?.displayName }.getOrNull()
    }

    private companion object {
        const val TAG = "CallEngine"

        /**
         * Matches the offer's life, and the TTL on the wake-up push. Applied to every call
         * message, not only the offer: an accept that arrives after the caller gave up connects
         * nothing, and an end that arrives an hour late would tear down whatever call happened
         * to be running then, if the call ids ever collided.
         */
        const val SIGNAL_LIFE_MILLIS = CallMachine.TTL_SEC * 1_000L
    }
}

/** The noise an incoming call makes. Separated so the engine has no Android in it beyond a log. */
interface Ringer {
    fun startIncoming(mode: Call.Mode)
    fun stop()
}

/** The lock-screen and tray presence of a call. Same reason as [Ringer]. */
interface CallNotifier {
    fun showIncoming(state: CallState.Incoming, peerName: String?)
    fun showOngoing(state: CallState, peerName: String?)
    fun showEnded(state: CallState.Ended, peerName: String?)
    fun clear()
}
