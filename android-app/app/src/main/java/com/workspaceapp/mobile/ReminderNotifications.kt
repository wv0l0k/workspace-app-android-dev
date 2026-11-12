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
 *
 * Both paths also expose the same two actions (see ReminderActionReceiver):
 * "Complete", which records the completion on-device immediately and queues the
 * server update for the next time the app is online, and "Snooze", which is
 * local-only and limited to ReminderScheduler.MAX_SNOOZES per occurrence.
 */
object ReminderNotifications {

    const val CHANNEL_ID = "reminders"

    /** A second, deliberately quiet channel: the "you'll be reminded again in N
     *  minutes" confirmation shown after a snooze is status feedback, not another
     *  reminder, and must not buzz or make the reminder sound again. */
    const val CHANNEL_ID_SNOOZE = "reminder_snoozed"
    const val SNOOZE_CONFIRM_TIMEOUT_MS = 3_000L

    /** Fixed id so a second snooze replaces the previous confirmation instead of
     *  stacking them; deliberately unrelated to any reminder's own id. */
    private const val SNOOZE_CONFIRM_ID = 0x5E0021

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
        occurrenceEpoch: Long = 0L,
        nagSeconds: Int? = null,
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

        // Deliberately no deleteIntent: swiping a reminder away must NOT stop it
        // nagging. Nags end only when the task/step is marked complete, at which
        // point the server schedule no longer contains it and the next sync cancels
        // its alarm (see ReminderScheduler.saveAndSchedule).
        val notificationManager = context.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Reminders", NotificationManager.IMPORTANCE_HIGH)
            notificationManager.createNotificationChannel(channel)
        }

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(body)
            // Collapsed, a notification only ever shows one line of body text - this
            // is what lets someone expand it to read the rest of a longer preview.
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setSmallIcon(R.drawable.ic_stat_workspace)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)

        builder.addAction(
            R.drawable.ic_action_complete,
            "Complete",
            actionIntent(
                context,
                ReminderActionReceiver.ACTION_COMPLETE,
                notificationId,
                key,
                occurrenceEpoch,
                title,
                body,
                itemId,
                workspaceId,
                kind,
                subtaskId,
                nagSeconds,
            )
        )

        // Once this occurrence's snooze allowance is used up, don't offer an action
        // that could only fail - Complete is the remaining way to stop the nags.
        if (snoozesRemaining(context, key, occurrenceEpoch) > 0) {
            builder.addAction(
                R.drawable.ic_action_snooze,
                "Snooze",
                actionIntent(
                    context,
                    ReminderActionReceiver.ACTION_SNOOZE,
                    notificationId,
                    key,
                    occurrenceEpoch,
                    title,
                    body,
                    itemId,
                    workspaceId,
                    kind,
                    subtaskId,
                    nagSeconds,
                )
            )
        }

        notificationManager.notify(notificationId, builder.build())
    }

    fun cancel(context: Context, key: String) {
        context.getSystemService(NotificationManager::class.java).cancel(key.hashCode())
    }

    /**
     * A short-lived confirmation after Snooze: tells the person the reminder will
     * come back rather than looking like it simply vanished, then removes itself
     * after a few seconds (setTimeoutAfter) so it doesn't linger in the shade.
     *
     * Uses its own low-importance channel so it stays silent - the reminder sound
     * belongs to the reminder, not to this receipt. No actions and no content
     * intent: there is nothing to do with it.
     */
    fun showSnoozed(context: Context, title: String?, minutes: Int) {
        val notificationManager = context.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID_SNOOZE,
                "Snooze confirmations",
                NotificationManager.IMPORTANCE_LOW
            )
            notificationManager.createNotificationChannel(channel)
        }

        val unit = if (minutes == 1) "minute" else "minutes"
        val text = if (title.isNullOrBlank()) {
            "We'll remind you again in $minutes $unit."
        } else {
            "\"$title\" will remind you again in $minutes $unit."
        }

        val notification = NotificationCompat.Builder(context, CHANNEL_ID_SNOOZE)
            .setContentTitle("Snoozed")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setSmallIcon(R.drawable.ic_stat_workspace)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
            .setTimeoutAfter(SNOOZE_CONFIRM_TIMEOUT_MS)
            .build()
        notificationManager.notify(SNOOZE_CONFIRM_ID, notification)
    }

    private fun snoozesRemaining(context: Context, key: String, occurrenceEpoch: Long): Int {
        val state = Prefs.getSnooze(context, key)
        val used = if (occurrenceEpoch > 0 && state.occurrenceEpoch == occurrenceEpoch) state.count else 0
        return (ReminderScheduler.MAX_SNOOZES - used).coerceAtLeast(0)
    }

    /**
     * An action PendingIntent carries everything the receiver needs to either queue
     * a completion or re-arm the same local alarm chain for a snooze. The occurrence
     * key matches ReminderScheduler's own, so a snooze replaces the currently-armed
     * nag rather than creating a parallel one.
     */
    private fun actionIntent(
        context: Context,
        action: String,
        notificationId: Int,
        key: String,
        occurrenceEpoch: Long,
        title: String,
        body: String,
        itemId: String?,
        workspaceId: String?,
        kind: String?,
        subtaskId: String?,
        nagSeconds: Int?,
    ): PendingIntent {
        val intent = Intent(context, ReminderActionReceiver::class.java).apply {
            this.action = action
            putExtra(ReminderScheduler.EXTRA_KEY, ReminderScheduler.occurrenceKey(itemId, subtaskId, occurrenceEpoch))
            putExtra(ReminderScheduler.EXTRA_OCCURRENCE, occurrenceEpoch)
            putExtra(ReminderScheduler.EXTRA_TITLE, title)
            putExtra(ReminderScheduler.EXTRA_BODY, body)
            itemId?.let { putExtra(ReminderScheduler.EXTRA_ITEM_ID, it) }
            workspaceId?.let { putExtra(ReminderScheduler.EXTRA_WORKSPACE_ID, it) }
            kind?.let { putExtra(ReminderScheduler.EXTRA_KIND, it) }
            subtaskId?.let { putExtra(ReminderScheduler.EXTRA_SUBTASK_ID, it) }
            nagSeconds?.takeIf { it > 0 }?.let { putExtra(ReminderScheduler.EXTRA_NAG_SECONDS, it) }
        }
        // Distinct request codes for the two actions, derived from the notification
        // id so the same notification's actions update in place on re-post.
        val requestCode = notificationId * 2 + if (action == ReminderActionReceiver.ACTION_COMPLETE) 0 else 1
        return PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
