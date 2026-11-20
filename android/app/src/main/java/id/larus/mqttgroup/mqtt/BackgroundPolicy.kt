package id.larus.mqttgroup.mqtt

import android.content.Context
import android.os.PowerManager

/**
 * Whether this phone has actually granted the "Allow background connection" prompt in Settings
 * (battery-optimization exemption). When it has not, the app does not fight Android's own
 * restrictions with a foreground service that clings on regardless, a boot receiver, and a
 * periodic worker that would only get killed again anyway on a phone that has said no to exactly
 * that. Consent from the toggle is what turns that fight on, not a decision this app makes for
 * itself.
 */
object BackgroundPolicy {
    fun canPersistInBackground(context: Context): Boolean {
        val powerManager = context.getSystemService(PowerManager::class.java) ?: return false
        return powerManager.isIgnoringBatteryOptimizations(context.packageName)
    }
}
