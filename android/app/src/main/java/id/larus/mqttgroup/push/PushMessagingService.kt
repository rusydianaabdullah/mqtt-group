package id.larus.mqttgroup.push

import android.util.Log
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import id.larus.mqttgroup.FamilyGroupApp
import id.larus.mqttgroup.mqtt.ChatNotifier
import id.larus.mqttgroup.mqtt.MqttService
import kotlinx.coroutines.runBlocking

/**
 * FCM is a wake-up bell first: bringing MQTT online is the one thing this class must always do,
 * because the actual content stays end-to-end encrypted on the broker where it has always lived.
 *
 * fcm-service (fcm.larus.id) always sends a real `notification` payload alongside the data one
 * (see its app/notify.py) — title/body stay generic ("Family Group" / "New message", no content,
 * no sender, no group name), but that field is what lets Android show a system-tray notification
 * automatically while this app is fully backgrounded, without any code here running at all.
 *
 * This service's own onMessageReceived only ever fires in the two cases the OS does not handle
 * that way: the app is in the foreground when the push arrives, and app-launched-from-notification
 * intents this app does not currently thread through here. Foreground is the case a manual test
 * most often exercises — two open chat windows side by side — so it gets its own placeholder
 * notification, replaced moments later by [ChatNotifier]'s real one once GroupSync actually
 * fetches and decrypts the message.
 */
class PushMessagingService : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        PushToken.set(applicationContext, token)
    }

    // The FCM SDK already dispatches this callback off the main thread, so a short blocking
    // round trip to the local (Room) database is fine here — no goAsync() needed, and it would
    // not apply anyway: that call belongs to BroadcastReceiver, not Service.
    override fun onMessageReceived(message: RemoteMessage) = runBlocking {
        Log.d(TAG, "wake push received, bringing MQTT online")
        MqttService.start(applicationContext)

        val groupId = message.data[PushSender.DATA_GROUP_ID] ?: return@runBlocking
        val senderId = message.data[PushSender.DATA_SENDER_ID]

        // A call wake-up ends here. Bringing MQTT up, which happened above, is the entire job:
        // the offer follows over the broker as ciphertext and CallEngine rings from that, with
        // the caller's real name, once it has actually opened. Posting a chat placeholder for it
        // would put "New message" in the tray for something that is about to become a ringing
        // phone, and nothing would ever take it back.
        if (message.data[PushSender.DATA_CALL_ID] != null) {
            Log.d(TAG, "call wake push; MQTT is coming up, the offer follows over the broker")
            return@runBlocking
        }
        val container = (application as FamilyGroupApp).container

        if (container.activeGroupId.value == groupId) return@runBlocking // already viewing this chat
        try {
            val identity = container.identity.get()
            if (senderId != null && senderId == identity?.userId) return@runBlocking // our own send
            val groupName = container.groups.get(groupId)?.name ?: return@runBlocking
            ChatNotifier(applicationContext).notifyPending(groupId, groupName)
        } catch (t: Throwable) {
            Log.w(TAG, "placeholder notification failed", t)
        }
    }

    private companion object {
        const val TAG = "PushMessagingService"
    }
}
