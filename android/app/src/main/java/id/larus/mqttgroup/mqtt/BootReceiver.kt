package id.larus.mqttgroup.mqtt

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import id.larus.mqttgroup.FamilyGroupApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Restarts the connection after a reboot — but only for a phone that has actually granted the
 * background-connection toggle in Settings. Without that consent, this app does not try to
 * outlive the phone rebooting any more than it tries to outlive being backgrounded: someone who
 * has not opted in gets it back the next time they open the app, not before.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        if (!BackgroundPolicy.canPersistInBackground(context)) return
        val container = (context.applicationContext as FamilyGroupApp).container
        // onReceive must return quickly; goAsync() covers the database read this needs first.
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                if (container.brokers.observeActive().first() != null) {
                    MqttService.start(context)
                }
            } finally {
                pending.finish()
            }
        }
    }
}
