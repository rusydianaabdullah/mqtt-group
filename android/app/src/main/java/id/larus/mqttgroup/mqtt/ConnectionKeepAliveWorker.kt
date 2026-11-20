package id.larus.mqttgroup.mqtt

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import id.larus.mqttgroup.FamilyGroupApp
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

/**
 * A belt-and-suspenders restart, for whatever [BootReceiver] and `stopWithTask="false"` do not
 * already cover: an OEM battery manager that kills the service outright without going through
 * `onDestroy`, or any other way the process quietly stops existing between messages. Fifteen
 * minutes is WorkManager's own floor for periodic work — nothing shorter is available without a
 * foreground-only job that would defeat the purpose.
 *
 * Gated the same way [BootReceiver] is: none of this runs unless the background-connection
 * toggle in Settings has actually been granted. Restarting a service on a phone that has not
 * consented to that would just be fighting the same restriction from a different angle.
 *
 * Starting the service when it is already running is harmless: `startForegroundService` just
 * re-enters `onStartCommand`, which does nothing beyond an `ACTION_WAKE` check.
 */
class ConnectionKeepAliveWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        if (!BackgroundPolicy.canPersistInBackground(applicationContext)) return Result.success()
        val container = (applicationContext as FamilyGroupApp).container
        if (container.brokers.observeActive().first() != null) {
            MqttService.start(applicationContext)
        }
        return Result.success()
    }

    companion object {
        private const val UNIQUE_NAME = "connection-keep-alive"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<ConnectionKeepAliveWorker>(15, TimeUnit.MINUTES).build()
            // KEEP, not REPLACE: this runs on every process start, and resetting an already
            // ticking periodic schedule each time would mean it never actually fires the first
            // time if the process restarts more often than the interval.
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(UNIQUE_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
