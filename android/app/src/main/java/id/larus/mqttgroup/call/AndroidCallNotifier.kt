package id.larus.mqttgroup.call

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import id.larus.mqttgroup.FamilyGroupApp
import id.larus.mqttgroup.R
import id.larus.mqttgroup.mqtt.Call
import id.larus.mqttgroup.ui.CallActivity

/**
 * A call's presence on the lock screen and in the tray.
 *
 * Three things here are not obvious and are each the result of a platform rule rather than a
 * preference:
 *
 * - **The channel is silent.** `IMPORTANCE_HIGH` with no sound and no vibration, because
 *   [AndroidRinger] owns both. A channel sound plays once and cannot loop, which is not a
 *   ringtone; leaving it on as well would mean a chime over the top of the actual ringing.
 * - **Every `CallStyle` notification here carries a full-screen intent, and it is not optional.**
 *   The platform refuses to post one at all unless it belongs to a foreground service, belongs
 *   to a user-initiated job, or has a `fullScreenIntent` — `NotificationManager.notify` throws
 *   `IllegalArgumentException: Not posted. CallStyle notifications must be for a foreground
 *   service or user initated job or use a fullScreenIntent` (sic). This app has none of the
 *   first two yet, so the intent is what makes posting legal, on the ongoing notification as
 *   much as the ringing one.
 *
 *   That is separate from whether the intent is *honoured*. Since Android 14 the permission is
 *   granted at install only to apps the system already counts as calling apps, and setting the
 *   intent without it is perfectly legal — the notification simply appears as a heads-up banner
 *   on a high-importance channel instead of taking over the screen, which is a fine degraded
 *   experience. So: always set it, and let [canUseFullScreen] decide only whether to bother the
 *   person about Settings.
 *
 *   The ongoing one passes `highPriority = false`, because an answered call that is already on
 *   screen should not relaunch itself over whatever the person moved on to; it needs the intent
 *   to exist, not to fire.
 * - **`setOngoing` while a call is up.** A ring the person can swipe away without answering or
 *   declining leaves the other end waiting on a call nobody is going to pick up.
 */
class AndroidCallNotifier(private val context: Context) : CallNotifier {

    private val manager = context.getSystemService(NotificationManager::class.java)

    init {
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Calls", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Incoming and ongoing calls"
                setSound(null, null)
                enableVibration(false)
                lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
            },
        )
    }

    /**
     * Whether a full-screen incoming-call UI will actually be shown if we ask for one.
     *
     * Below Android 14 this was granted by declaring the permission. From 14 it is granted at
     * install only to apps providing calling or alarm functionality, and everyone else has to
     * be sent to Settings by the person. [id.larus.mqttgroup.ui.SettingsScreen] offers that
     * trip; this is what it reads to decide whether to.
     */
    fun canUseFullScreen(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE ||
            manager.canUseFullScreenIntent()

    override fun showIncoming(state: CallState.Incoming, peerName: String?) {
        val caller = person(peerName)
        val notification = base(caller)
            .setStyle(
                NotificationCompat.CallStyle.forIncomingCall(
                    caller,
                    action(ACTION_DECLINE),
                    action(ACTION_ACCEPT),
                ),
            )
            .setContentText(
                context.getString(
                    if (state.mode == Call.Mode.BUZZ) {
                        R.string.buzz_incoming
                    } else {
                        R.string.call_incoming
                    },
                ),
            )
            .setOngoing(true)
            .setFullScreenIntent(screen(), true)
            .build()
        post(notification)
    }

    override fun showOngoing(state: CallState, peerName: String?) {
        post(ongoingNotification(state, peerName) ?: return)
    }

    /**
     * Null when the state is not one a call is held up for, which is the signal to take the
     * notification down rather than to show something.
     */
    private fun ongoingNotification(state: CallState, peerName: String?): android.app.Notification? {
        val caller = person(peerName)
        val text = when (state) {
            // A buzz says the same thing from the moment it leaves until somebody stops it:
            // there is no ringing/answered distinction to report, because nobody answers one.
            is CallState.Outgoing -> if (state.mode == Call.Mode.BUZZ) {
                if (state.isGroup) R.string.buzz_outgoing_group else R.string.buzz_outgoing
            } else if (state.reached) {
                R.string.call_ringing
            } else {
                R.string.call_calling
            }
            is CallState.Connected ->
                if (state.mode == Call.Mode.BUZZ) {
                    R.string.buzz_incoming
                } else {
                    R.string.call_connected
                }
            else -> return null
        }
        return base(caller)
            .setStyle(NotificationCompat.CallStyle.forOngoingCall(caller, action(ACTION_HANGUP)))
            .setContentText(context.getString(text))
            .setOngoing(true)
            .setContentIntent(screen())
            // Required, not decorative — see the note on this class. Without it the platform
            // refuses the post and throws, which is what crashed the app on the first dial.
            .setFullScreenIntent(screen(), false)
            .apply {
                // The call timer, which Android draws itself from this and keeps ticking
                // without the notification being reposted every second.
                if (state is CallState.Connected) {
                    setUsesChronometer(true)
                    setWhen(state.since)
                }
            }
            .build()
    }

    /**
     * The last word on a call, as an ordinary dismissible notification rather than a call one:
     * it is no longer a call, and `CallStyle` would keep offering buttons for something that is
     * over. A missed call is the one outcome worth leaving on screen — the others were all seen
     * by whoever caused them.
     */
    override fun showEnded(state: CallState.Ended, peerName: String?) {
        if (state.outcome != Outcome.UNANSWERED) {
            clear()
            return
        }
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(peerName ?: context.getString(R.string.call_unknown_caller))
            .setContentText(context.getString(R.string.call_missed))
            .setCategory(NotificationCompat.CATEGORY_MISSED_CALL)
            .setAutoCancel(true)
            .setContentIntent(screen())
            .build()
        post(notification)
    }

    override fun clear() {
        runCatching { manager.cancel(NOTIFICATION_ID) }
    }

    /**
     * Posting is allowed to fail; the call is not.
     *
     * `notify` throws for a whole family of reasons that have nothing to do with whether a call
     * should continue — a blocked channel, revoked notification permission, an OEM's own quota,
     * and the `CallStyle` precondition above. [CallEngine] drives this from a coroutine on the
     * application scope, so an exception escaping here does not fail one notification, it takes
     * down the process mid-call. A silent call is bad; a crash is worse.
     */
    private fun post(notification: android.app.Notification) {
        runCatching { manager.notify(NOTIFICATION_ID, notification) }
            .onFailure { Log.w(TAG, "call notification refused by the system", it) }
    }

    private fun base(caller: Person) = NotificationCompat.Builder(context, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_notification)
        .setCategory(NotificationCompat.CATEGORY_CALL)
        .setPriority(NotificationCompat.PRIORITY_MAX)
        .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
        .addPerson(caller)

    /**
     * The caller, as far as we can honestly put it.
     *
     * A shared group key gives no per-sender authenticity — any member could have published this
     * offer under any member's id — so this is a claim rather than a proof, and the same is true
     * of the name on every chat message in the app. Where the name is not known at all, which is
     * the ordinary case for the first second of a call that arrived by push, the string says so
     * rather than inventing one.
     */
    private fun person(peerName: String?): Person = Person.Builder()
        .setName(peerName ?: context.getString(R.string.call_unknown_caller))
        .setImportant(true)
        .build()

    private fun screen(): PendingIntent = PendingIntent.getActivity(
        context,
        REQUEST_SCREEN,
        Intent(context, CallActivity::class.java)
            .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun action(action: String): PendingIntent = PendingIntent.getBroadcast(
        context,
        action.hashCode(),
        Intent(context, CallActionReceiver::class.java).setAction(action),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    companion object {
        private const val TAG = "CallNotifier"
        private const val CHANNEL_ID = "calls"

        /** One, because there is one call. */
        private const val NOTIFICATION_ID = 0x0CA11
        private const val REQUEST_SCREEN = 0x0CA12

        const val ACTION_ACCEPT = "id.larus.mqttgroup.CALL_ACCEPT"
        const val ACTION_DECLINE = "id.larus.mqttgroup.CALL_DECLINE"
        const val ACTION_HANGUP = "id.larus.mqttgroup.CALL_HANGUP"
    }
}

/**
 * Answer and decline from the notification itself, without the screen ever coming up.
 *
 * A receiver rather than an activity because answering from the lock screen should not have to
 * unlock the phone first — which starting an activity would require, and which is precisely the
 * moment someone is least able to do it.
 */
class CallActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val engine = (context.applicationContext as FamilyGroupApp).container.calls
        when (intent.action) {
            AndroidCallNotifier.ACTION_ACCEPT -> engine.accept()
            AndroidCallNotifier.ACTION_DECLINE, AndroidCallNotifier.ACTION_HANGUP -> engine.hangup()
        }
    }
}
