package id.larus.mqttgroup

import android.app.Application
import android.util.Log
import com.google.firebase.messaging.FirebaseMessaging
import id.larus.mqttgroup.location.RetentionWorker
import id.larus.mqttgroup.mqtt.ConnectionKeepAliveWorker
import id.larus.mqttgroup.push.PushToken
import id.larus.mqttgroup.push.PushTopics
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Holds the process-wide singletons. From phase 2 the MQTT connection joins them; for now it is
 * the encrypted database, the keystore-backed vault, and the blob store.
 */
class FamilyGroupApp : Application() {
    val container: AppContainer by lazy { AppContainer(this) }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        // Cheap to call on every process start — KEEP means it only actually schedules once.
        ConnectionKeepAliveWorker.schedule(this)
        RetentionWorker.schedule(this)

        // onNewToken only fires on first generation or rotation; a phone that already had one
        // before this process started would otherwise never have it read back into PushToken.
        FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
            if (task.isSuccessful) {
                task.result?.let { PushToken.set(this, it) }
            } else {
                Log.w(TAG, "FCM token fetch failed", task.exception)
            }
        }

        PushTopics.sync(container.groups, scope)
    }

    private companion object {
        const val TAG = "FamilyGroupApp"
    }
}
