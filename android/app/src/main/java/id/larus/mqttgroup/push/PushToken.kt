package id.larus.mqttgroup.push

import android.content.Context

/**
 * This phone's current FCM registration token, kept locally until there is somewhere to send it.
 *
 * Nothing in this app has a server, so the token by itself does nothing yet — a later phase
 * needs a way for group members to learn each other's tokens (most likely as another control
 * message over MQTT, the same channel that already carries group membership) and something
 * outside this phone able to call the FCM send API when a chat message is enqueued for a peer
 * who is not connected. Stored now so that wiring has something to read.
 */
object PushToken {
    private const val PREFS = "push_token"
    private const val KEY_TOKEN = "token"

    fun get(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_TOKEN, null)

    fun set(context: Context, token: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_TOKEN, token)
            .apply()
    }
}
