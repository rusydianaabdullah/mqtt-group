package id.larus.mqttgroup.push

import android.util.Log
import com.google.firebase.messaging.FirebaseMessaging
import id.larus.mqttgroup.data.repo.GroupRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Keeps this phone's FCM topic subscriptions matching the groups it is actually in.
 *
 * There is no server tracking group membership, so nothing else knows when to unsubscribe a
 * group this phone has left — the fix is to resync against the live group list on every launch
 * rather than trying to catch every place membership can change (create, join, leave, kick,
 * delete-for-everyone, a restored backup). Diffing is cheap and subscribe/unsubscribe are
 * idempotent, so resyncing on every emission costs nothing extra.
 */
object PushTopics {
    private const val TAG = "PushTopics"

    fun sync(groups: GroupRepository, scope: CoroutineScope) {
        var previous = emptySet<String>()
        scope.launch {
            groups.observeAll().collect { all ->
                val current = all.map { it.groupId }.toSet()
                val messaging = FirebaseMessaging.getInstance()
                (current - previous).forEach { groupId ->
                    messaging.subscribeToTopic(PushSender.topicFor(groupId))
                        .addOnFailureListener { Log.w(TAG, "subscribe failed for $groupId", it) }
                }
                (previous - current).forEach { groupId ->
                    messaging.unsubscribeFromTopic(PushSender.topicFor(groupId))
                        .addOnFailureListener { Log.w(TAG, "unsubscribe failed for $groupId", it) }
                }
                previous = current
            }
        }
    }
}
