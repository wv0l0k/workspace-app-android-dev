package com.workspaceapp.mobile

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat

/**
 * Builds reminder notifications for both delivery paths:
 *
 *  - a Firebase push (`MyFirebaseMessagingService`, only works online), and
 *  - a locally-scheduled AlarmManager alarm (`ReminderAlarmReceiver`, works offline).
 *
 * They deliberately share the same notification id and the same "last notified"
 * bookkeeping (see Prefs.getLastNotified), so if both paths fire for the same
 * occurrence around the same time they update one notification instead of
 * stacking or sounding twice. Whichever arrives first wins; the other is
 * suppressed for a short window.
 */
object ReminderNotifications {

    const val CHANNEL_ID = "reminders"

    /** How close two notifications for the same key have to be to count as the
     *  same occurrence. Long enough to cover FCM/local delivery skew, short
     *  enough that a genuine 1-minute nag still alerts on its next round. */
    const val DEDUPE_WINDOW_MS = 45_000L

    /**
     * Same identity the FCM service has always used: the item id, plus the step
     * id for a checklist step (several steps share one parent item id).
     */
    fun keyFor(itemId: String?, subtaskId: String?): String =
        if (subtaskId != null) "${itemId ?: "unknown"}:$subtaskId" else (itemId ?: "unknown")

    fun show(
        context: Context,
        key: String,
        title: String,
        body: String,
        itemId: String?,
        workspaceId: String?,
        kind: String?,
        subtaskId: String?,
    ) {
        val notificationId = key.hashCode()

        val tapIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            itemId?.let { putExtra("item_id", it) }
            workspaceId?.let { putExtra("workspace_id", it) }
            kind?.let { putExtra("kind", it) }
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            notificationId,
            tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // A locally-scheduled reminder keeps re-arming itself while it's unresolved
        // (see ReminderAlarmReceiver). Swiping the notification away should stop
        // that, the same way marking the task done eventually does on the server.
        val dismissIntent = Intent(context, ReminderDismissReceiver::class.java).apply {
            action = ReminderScheduler.ACTION_DISMISS
            putExtra(ReminderScheduler.EXTRA_KEY, key)
        }
        val dismissPendingIntent = PendingIntent.getBroadcast(
            context,
            notificationId,
            dismissIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notificationManager = context.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Reminders", NotificationManager.IMPORTANCE_HIGH)
            notificationManager.createNotificationChannel(channel)
        }

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(body)
            // Collapsed, a notification only ever shows one line of body text - this
            // is what lets someone expand it to read the rest of a longer preview.
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setSmallIcon(R.drawable.ic_stat_workspace)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setDeleteIntent(dismissPendingIntent)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .build()

        notificationManager.notify(notificationId, notification)
    }
}
