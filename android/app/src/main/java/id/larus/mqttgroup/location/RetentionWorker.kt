package id.larus.mqttgroup.location

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
 * Throws away location history past each group's retention window.
 *
 * Per group rather than one global cutoff, because retention is one of the admin's settings and
 * two groups can reasonably disagree about it — a family group keeping a week and a one-off trip
 * group keeping a day should not have to compromise on a single number.
 *
 * The uncomfortable part, stated plainly because the UI should too: this is *this phone's*
 * retention. Everyone in the group holds everyone's history, so a member who set "keep forever"
 * still has your track after you pruned yours. Serverless means there is no single copy to
 * delete, and no honest way to promise otherwise.
 *
 * Hourly. Finer would be pointless against windows measured in hours and days, and WorkManager
 * would coalesce it anyway; coarser would let a "keep 1 hour" setting hold two.
 */
class RetentionWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val container = (applicationContext as FamilyGroupApp).container
        val now = System.currentTimeMillis()
        for (group in container.groups.observeAll().first()) {
            // 0 is the explicit "keep forever", not a zero-length window — pruning everything
            // would be the exact opposite of what it asks for.
            if (group.locRetentionHours <= 0) continue
            val cutoff = now - group.locRetentionHours.toLong() * 60L * 60L * 1_000L
            container.database.locations().pruneGroupOlderThan(group.groupId, cutoff)
        }
        return Result.success()
    }

    companion object {
        private const val UNIQUE_NAME = "location-retention"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<RetentionWorker>(1, TimeUnit.HOURS).build()
            // KEEP for the same reason the keep-alive worker uses it: this is called on every
            // process start, and REPLACE would reset the schedule each time so it never fired on
            // a phone whose process restarts more often than the interval.
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(UNIQUE_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
