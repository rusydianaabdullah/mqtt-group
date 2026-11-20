package id.larus.mqttgroup.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import id.larus.mqttgroup.FamilyGroupApp
import id.larus.mqttgroup.R
import id.larus.mqttgroup.call.CallEngine
import id.larus.mqttgroup.call.CallState
import id.larus.mqttgroup.call.Outcome
import id.larus.mqttgroup.mqtt.Call
import kotlinx.coroutines.delay

/**
 * The call, full screen.
 *
 * Its own activity rather than a screen inside [MainActivity] because it has to appear over the
 * lock screen without unlocking it, which is a property of an activity and its window rather
 * than anything Compose can ask for. It is also what the notification's full-screen intent
 * launches on the phones where the system grants us one — see
 * [id.larus.mqttgroup.call.AndroidCallNotifier].
 *
 * It closes itself when there is no call, and on nothing else. A person who backgrounds a
 * ringing phone has not declined the call, and an activity that finished on back would leave
 * the other end waiting with no way back to the buttons.
 */
class CallActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // From API 27, and minSdk here is 29, so the old window flags are not worth carrying.
        // requestDismissKeyguard is deliberately not called: the call UI should be usable on a
        // locked phone, not a reason to put an unlock prompt in front of someone answering it.
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        enableEdgeToEdge()

        val engine = (application as FamilyGroupApp).container.calls
        setContent {
            FamilyGroupTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val state by engine.state.collectAsStateWithLifecycle()
                    val peerName by engine.peerName.collectAsStateWithLifecycle()
                    val roundTrip by engine.roundTripMillis.collectAsStateWithLifecycle()

                    // The only way out. A call that ends while this is up shows its outcome for
                    // a moment first, which is the whole reason CallState.Ended exists.
                    LaunchedEffect(state) {
                        if (state is CallState.Ended) {
                            delay(ENDED_LINGER_MILLIS)
                            engine.dismiss()
                        }
                        if (state is CallState.Idle) finish()
                    }

                    CallScreen(state, peerName, roundTrip, engine)
                }
            }
        }
    }

    private companion object {
        /** Long enough to read "Declined" or "Missed", short enough not to be in the way. */
        const val ENDED_LINGER_MILLIS = 2_500L
    }
}

@Composable
private fun CallScreen(
    state: CallState,
    peerName: String?,
    roundTrip: Long?,
    engine: CallEngine,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(
            modifier = Modifier.padding(top = 72.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                // A name here is a claim and not a proof: a shared group key gives no
                // per-sender authenticity, exactly as for every chat message in this app.
                // A group call has no single name, so it says how many are in the room —
                // which is the question a group call actually asks.
                text = title(state, peerName),
                style = MaterialTheme.typography.headlineMedium,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(12.dp))
            CallStatus(state)
            // Shown once it is known and only while the call is up. A delayed call has two very
            // different causes — our buffering, which is ours to cut, and the path to the
            // broker, which is not — and this is the one that says which.
            roundTrip?.takeIf { state is CallState.Connected || state is CallState.Outgoing }?.let {
                Spacer(Modifier.height(6.dp))
                Text(
                    stringResource(R.string.call_round_trip, it),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (state is CallState.Connected) {
                Spacer(Modifier.height(8.dp))
                Elapsed(since = state.since)
                if (state.participants.isEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.call_waiting_for_anyone),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 48.dp),
            horizontalArrangement =
                if (state is CallState.Idle || state is CallState.Ended) Arrangement.Center
                else Arrangement.SpaceEvenly,
        ) {
            when (state) {
                // A buzz offers one button at either end, because there is nothing to answer:
                // whoever reaches a phone first stops it.
                is CallState.Incoming -> if (isBuzz(state)) {
                    RoundButton(
                        onClick = engine::hangup,
                        container = MaterialTheme.colorScheme.error,
                        icon = CallEndIcon,
                        label = R.string.buzz_stop,
                    )
                } else {
                    RoundButton(
                        onClick = engine::hangup,
                        container = MaterialTheme.colorScheme.error,
                        icon = CallEndIcon,
                        label = R.string.call_decline,
                    )
                    RoundButton(
                        onClick = engine::accept,
                        container = CALL_GREEN,
                        icon = CallIcon,
                        label = R.string.call_answer,
                    )
                }
                is CallState.Connected -> RoundButton(
                    onClick = engine::hangup,
                    container = MaterialTheme.colorScheme.error,
                    icon = CallEndIcon,
                    label = R.string.call_hang_up,
                )
                is CallState.Outgoing -> RoundButton(
                    onClick = engine::hangup,
                    container = MaterialTheme.colorScheme.error,
                    icon = CallEndIcon,
                    label = if (isBuzz(state)) R.string.buzz_stop else R.string.call_hang_up,
                )
                else -> Unit
            }
        }
    }
}

/**
 * Where the call has got to, as a pill rather than a line of grey text.
 *
 * The three states a caller passes through are not decoration: "Calling…" means the offer went
 * out and nothing has come back, "Ringing…" means a device that is awake actually has it, and
 * "Connected" means somebody answered. The middle one is the whole reason the `ring` message
 * exists, and it was previously rendered in the same muted body text as everything else, where
 * it read as a caption rather than as the call's progress.
 *
 * The dot pulses while waiting and holds steady once connected, so the difference is legible
 * without reading — which on a screen someone is holding to their ear is most of the point.
 */
@Composable
private fun CallStatus(state: CallState) {
    val waiting = state is CallState.Outgoing || state is CallState.Incoming
    val colour = when (state) {
        is CallState.Connected -> CALL_GREEN
        is CallState.Outgoing -> if (state.reached) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.onSurfaceVariant
        is CallState.Incoming -> MaterialTheme.colorScheme.primary
        is CallState.Ended -> MaterialTheme.colorScheme.error
        is CallState.Idle -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    // Only while there is something to wait for. A pulsing dot on "Connected" would suggest the
    // call is still trying to do something, which is the opposite of what it means.
    val alpha = if (waiting) {
        val transition = rememberInfiniteTransition(label = "call-status")
        transition.animateFloat(
            initialValue = 0.35f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(900, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "call-status-dot",
        ).value
    } else {
        1f
    }
    Pill(
        text = stringResource(statusText(state)),
        dot = true,
        dotColor = colour.copy(alpha = alpha),
        content = colour,
        container = colour.copy(alpha = 0.14f),
    )
}

/** Counts up from when the call connected, in its own composable so that the rest of the screen
 * is not recomposed once a second. */
@Composable
private fun Elapsed(since: Long) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(since) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1_000)
        }
    }
    val seconds = (now - since).coerceAtLeast(0) / 1000
    Text("%d:%02d".format(seconds / 60, seconds % 60), style = MaterialTheme.typography.titleMedium)
}

@Composable
private fun RoundButton(
    onClick: () -> Unit,
    container: Color,
    icon: ImageVector,
    label: Int,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Button(
            onClick = onClick,
            shape = CircleShape,
            colors = ButtonDefaults.buttonColors(containerColor = container),
            modifier = Modifier.size(72.dp),
            contentPadding = PaddingValues(0.dp),
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(32.dp))
        }
        Spacer(Modifier.height(8.dp))
        Text(stringResource(label), style = MaterialTheme.typography.labelMedium)
    }
}

/**
 * Who this call is with. A direct call is a person; a group call is a count, because listing
 * four names in a headline is unreadable and picking one of them is a lie.
 */
/** A buzz is not a call, and every screen it touches has to say so. */
private fun isBuzz(state: CallState): Boolean = when (state) {
    is CallState.Outgoing -> state.mode == Call.Mode.BUZZ
    is CallState.Incoming -> state.mode == Call.Mode.BUZZ
    is CallState.Connected -> state.mode == Call.Mode.BUZZ
    else -> false
}

@Composable
private fun title(state: CallState, peerName: String?): String = when {
    state is CallState.Connected && state.isGroup ->
        stringResource(R.string.call_in_room, state.participants.size + 1)
    state is CallState.Outgoing && state.isGroup -> stringResource(R.string.call_group)
    else -> peerName ?: stringResource(R.string.call_unknown_caller)
}

/**
 * Two words for a buzz and only two: which phone is being buzzed, and stopped once anybody stops
 * it. It never borrows the call vocabulary — no "incoming call", no ringing/answered split —
 * because a buzz has no busy, no declined and no missed, and every way it ends is somebody
 * stopping it. Inventing distinctions between those would report on the mechanism rather than on
 * what happened.
 */
private fun statusText(state: CallState): Int = when (state) {
    is CallState.Incoming -> if (isBuzz(state)) R.string.buzz_incoming else R.string.call_incoming
    is CallState.Outgoing ->
        if (isBuzz(state)) {
            if (state.isGroup) R.string.buzz_outgoing_group else R.string.buzz_outgoing
        } else if (state.isGroup && !state.reached) {
            R.string.call_group_ringing
        } else if (state.reached) {
            R.string.call_ringing
        } else {
            R.string.call_calling
        }
    is CallState.Connected ->
        if (isBuzz(state)) R.string.buzz_incoming else R.string.call_connected
    is CallState.Ended -> if (isBuzz(state)) {
        R.string.buzz_stopped
    } else {
        when (state.outcome) {
            Outcome.DECLINED -> R.string.call_outcome_declined
            Outcome.BUSY -> R.string.call_outcome_busy
            Outcome.CANCELLED -> R.string.call_outcome_cancelled
            Outcome.UNANSWERED -> R.string.call_outcome_unanswered
            Outcome.HUNG_UP -> R.string.call_outcome_ended
        }
    }
    is CallState.Idle -> R.string.buzz_stopped
}

/**
 * Answer green, and the same green for a connected call — it is not in the Material scheme and
 * should not be: it means one thing here (this call is up, or about to be) and nothing anywhere
 * else in the app.
 */
private val CALL_GREEN = Color(0xFF2E7D32)
