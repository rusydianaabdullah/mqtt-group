package id.larus.mqttgroup.push

import android.util.Base64
import id.larus.mqttgroup.BuildConfig

import android.util.Base64
import android.util.Log
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * Tells fcm.larus.id to publish to a group's FCM topic whenever a chat message goes out, so a
 * member whose phone is not connected to the broker right now gets a system notification and can
 * open the app — MQTT then reconnects the ordinary way, through the app's own foreground/open
 * lifecycle (see MainActivity's LifecycleStartEffect), not through anything this class does.
 *
 * fcm-service always sends a real FCM `notification` payload, not just data (see its
 * app/notify.py) — that is what makes Android show a tray notification automatically while the
 * recipient is backgrounded, but it also means the title and body cross Google's servers in the
 * clear and get shown verbatim. So they stay generic: no message content, no sender name, no
 * group name. Everything that actually matters keeps travelling only as MQTT ciphertext, exactly
 * as before; this call adds nothing but "someone posted, go look."
 */
object PushSender {
    private const val TAG = "PushSender"
    private const val ENDPOINT = "https://fcm.larus.id/notify"

    // The fcm-service instance this app was pointed at is shared infrastructure behind Basic
    // auth, not a per-user secret — same trust level as the MQTT broker credentials already
    // shipped in this app for the default broker. Anyone who extracted it could only publish to
    // this app's own topics, whose names are random UUIDs they would first have to know.
    private val AUTH_HEADER =
        "Basic " + Base64.encodeToString(BuildConfig.FCM_AUTH.toByteArray(), Base64.NO_WRAP)

    /**
     * [senderId] rides along as data so a receiving phone that turns out to be the sender's own
     * — every member's phone is subscribed to the same topic, sender included — can recognise
     * its own outgoing message and skip notifying about it.
     */
    fun notifyGroup(groupId: String, senderId: String) {
        Thread {
            try {
                send(groupId, senderId)
            } catch (t: Throwable) {
                Log.w(TAG, "push notify failed", t)
            }
        }.start()
    }

    /**
     * Wakes the callee's devices for an incoming call.
     *
     * `kind: "call"` is a different shape of push, not merely a different label. The relay sends
     * it with **no** `notification` payload and a 45-second TTL instead of an hour (see
     * fcm-service's README, "The two kinds"), and both matter:
     *
     * - A `notification` payload is drawn by the OS itself when the app is backgrounded, and
     *   [id.larus.mqttgroup.push.PushMessagingService.onMessageReceived] then never runs. That
     *   is fine for a chat doorbell and fatal for a call: no code of ours running means no
     *   ringtone, no vibration and no full-screen incoming-call UI. Data-only is the only way
     *   the app's own code is what wakes up.
     * - An hour-long TTL would let a push held behind a tunnel ring the phone long after the
     *   caller gave up. 45 seconds is about the life of the offer it accompanies.
     *
     * It still says nothing identifying. Everything that matters — that this is a call, and who
     * from — is learned by decrypting the MQTT offer on the device.
     */
    fun notifyCall(groupId: String, senderId: String, callId: String, mode: String) {
        Thread {
            try {
                val body = JSONObject()
                    .put("topic", topicFor(groupId))
                    .put("kind", "call")
                    .put(
                        "data",
                        JSONObject()
                            .put(DATA_GROUP_ID, groupId)
                            .put(DATA_SENDER_ID, senderId)
                            .put(DATA_CALL_ID, callId)
                            // The mode rides along because a browser's service worker has to act
                            // on it before anything is decrypted: a buzz re-alerts on a timer so
                            // it reads as ringing, a call it could not answer says "missed".
                            .put(DATA_MODE, mode),
                    )
                post(body)
            } catch (t: Throwable) {
                // The call still goes out over MQTT; this only wakes a device that is not
                // currently connected. Failing it is a callee who may not hear the ring, not a
                // call that does not happen.
                Log.w(TAG, "call push failed", t)
            }
        }.start()
    }

    private fun send(groupId: String, senderId: String) {
        val body = JSONObject()
            .put("topic", topicFor(groupId))
            .put("title", "Family Group")
            .put("body", "New message")
            .put("data", JSONObject().put(DATA_GROUP_ID, groupId).put(DATA_SENDER_ID, senderId))
        post(body)
    }

    private fun post(body: JSONObject) {
        val connection = URL(ENDPOINT).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Authorization", AUTH_HEADER)
            OutputStreamWriter(connection.outputStream).use { it.write(body.toString()) }
            val code = connection.responseCode
            if (code !in 200..299) {
                val errorBody = connection.errorStream?.bufferedReader()?.use { it.readText() }
                Log.w(TAG, "push notify rejected: HTTP $code $errorBody")
            }
        } finally {
            connection.disconnect()
        }
    }

    const val DATA_GROUP_ID = "groupId"
    const val DATA_SENDER_ID = "senderId"

    /** Present only on a call push, and the cheapest way for a woken app to tell one from the
     * chat doorbell before anything has been decrypted. The relay also stamps `kind`. */
    const val DATA_CALL_ID = "callId"
    const val DATA_MODE = "mode"

    fun topicFor(groupId: String): String = "group-$groupId"
}
