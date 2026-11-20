package id.larus.mqttgroup.call

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import id.larus.mqttgroup.mqtt.Call

/**
 * The noise an incoming call makes.
 *
 * This is ours to play because a notification channel's sound plays **once** and cannot be made
 * to loop, which is a chime and not a ringtone. So the system ringtone is played directly, the
 * vibrator is driven on a repeating pattern, and the channel that carries the notification is
 * silent (see [AndroidCallNotifier]) so the two do not talk over each other.
 *
 * Whatever starts has to stop, on every path out of a ringing call — answered, declined,
 * cancelled by the caller, or timed out. [CallEngine] calls [stop] on all four, and the reason
 * it is written as one method with no arguments is so that no future path can accidentally
 * become a fifth one that forgets.
 */
class AndroidRinger(private val context: Context) : Ringer {

    private val audio = context.getSystemService(AudioManager::class.java)

    private val vibrator: Vibrator? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Vibrator::class.java)
        }

    private var ringtone: Ringtone? = null

    @Synchronized
    override fun startIncoming(mode: Call.Mode) {
        stop()
        if (mode == Call.Mode.BUZZ) {
            // A buzz is "where is my phone" and "pick up, it is urgent", and both are questions
            // a silent phone is the reason for asking. So it plays on the alarm stream, which
            // is the one Android deliberately leaves audible when the ringer is off — the same
            // decision every find-my-device feature makes, and for the same reason. The UI says
            // so plainly before anyone presses it, because a family member who can override
            // your silent switch should not be a surprise.
            playRingtone(alarm = true)
            vibrate()
            return
        }
        when (audio?.ringerMode) {
            // Silent means silent. A call is not an exception to it — someone in a meeting who
            // set their phone to silent did so for exactly this.
            AudioManager.RINGER_MODE_SILENT -> Unit
            AudioManager.RINGER_MODE_VIBRATE -> vibrate()
            else -> {
                playRingtone()
                vibrate()
            }
        }
    }

    @Synchronized
    override fun stop() {
        runCatching { ringtone?.stop() }
        ringtone = null
        runCatching { vibrator?.cancel() }
    }

    private fun playRingtone(alarm: Boolean = false) {
        val type = if (alarm) RingtoneManager.TYPE_ALARM else RingtoneManager.TYPE_RINGTONE
        val uri = RingtoneManager.getActualDefaultRingtoneUri(context, type)
            ?: RingtoneManager.getDefaultUri(type)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            ?: return
        try {
            ringtone = RingtoneManager.getRingtone(context, uri)?.apply {
                audioAttributes = AudioAttributes.Builder()
                    .setUsage(
                        if (alarm) AudioAttributes.USAGE_ALARM
                        else AudioAttributes.USAGE_NOTIFICATION_RINGTONE,
                    )
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
                // Looping is the whole point, and it only exists from API 28. Below that the
                // ringtone plays once, which is worse than a loop and much better than nothing;
                // minSdk here is 29, so in practice this is always the real thing.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) isLooping = true
                play()
            }
        } catch (t: Throwable) {
            // A ringtone the user has since deleted, or a device with none at all. The vibration
            // and the notification still happen, which is the part that cannot fail.
            Log.w(TAG, "could not play the ringtone", t)
        }
    }

    private fun vibrate() {
        val effect = VibrationEffect.createWaveform(PATTERN, /* repeat index */ 0)
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        runCatching { vibrator?.vibrate(effect, attributes) }
    }

    private companion object {
        const val TAG = "AndroidRinger"

        /** Wait, buzz, pause, buzz — the shape every phone's ringer has, so that it reads as a
         * call rather than as a long notification. Repeats from index 0. */
        val PATTERN = longArrayOf(0, 700, 600, 700, 1_400)
    }
}
