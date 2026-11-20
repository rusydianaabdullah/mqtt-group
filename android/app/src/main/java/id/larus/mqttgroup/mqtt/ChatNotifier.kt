package id.larus.mqttgroup.mqtt

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import id.larus.mqttgroup.R
import id.larus.mqttgroup.ui.MainActivity

/**
 * Chat messages, separate from [MqttService]'s silent connection notification — that one exists
 * only because Android requires it for a foreground service; this one is the point of having the
 * app installed at all, so it gets a channel a person can actually hear.
 */
class ChatNotifier(private val context: Context) {

    private val manager = context.getSystemService(NotificationManager::class.java)

    init {
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Messages", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "New messages in your groups"
            },
        )
    }

    /** One notification per group, replaced rather than stacked — the latest message is what
     * matters, and Android's own summary line already says "and N more" once a group has both a
     * previous and a new notification merged. */
    fun notifyMessage(groupId: String, groupName: String, senderName: String, preview: String) {
        val open = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(EXTRA_GROUP_ID, groupId)
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            groupId.hashCode(),
            open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(groupName)
            .setContentText("$senderName: $preview")
            .setStyle(NotificationCompat.BigTextStyle().bigText("$senderName: $preview"))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()
        manager.notify(groupId.hashCode(), notification)
    }

    /**
     * A placeholder raised the moment the FCM wake-up bell rings, before the real message has
     * even been fetched off the broker and decrypted — so there is nothing to show yet but "a
     * message is coming". Posted under the same notification id [notifyMessage] uses for the
     * same group, so the real content that follows moments later replaces this rather than
     * stacking a second notification on top of it.
     */
    fun notifyPending(groupId: String, groupName: String) {
        val open = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(EXTRA_GROUP_ID, groupId)
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            groupId.hashCode(),
            open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(groupName)
            .setContentText("New message")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()
        manager.notify(groupId.hashCode(), notification)
    }

    fun cancel(groupId: String) {
        manager.cancel(groupId.hashCode())
    }

    companion object {
        private const val CHANNEL_ID = "messages"
        const val EXTRA_GROUP_ID = "open_group_id"
    }
}
