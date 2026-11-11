package com.workspaceapp.mobile

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast

/**
 * Handles the two action buttons on a reminder notification. Runs entirely
 * on-device, so both work with no connection:
 *
 *  - Complete records the task/step as completed locally (which silences both
 *    delivery paths immediately) and queues the server update for the frontend to
 *    send whenever it is next online.
 *  - Snooze is device-only and never touches the server: it postpones this
 *    occurrence by ReminderScheduler.SNOOZE_MILLIS, at most
 *    ReminderScheduler.MAX_SNOOZES times.
 *
 * Not exported - only this app's own notification PendingIntents target it.
 */
class ReminderActionReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_COMPLETE = "com.workspaceapp.mobile.REMINDER_COMPLETE"
        const val ACTION_SNOOZE = "com.workspaceapp.mobile.REMINDER_SNOOZE"

        /** Sent to MainActivity after a completion is queued, so an already-open app
         *  can send it right away instead of waiting for the next resume/online. */
        const val ACTION_FLUSH_ACTIONS = "com.workspaceapp.mobile.FLUSH_REMINDER_ACTIONS"
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_COMPLETE -> complete(context, intent)
            ACTION_SNOOZE -> snooze(context, intent)
        }
    }

    private fun complete(context: Context, intent: Intent) {
        val itemId = intent.getStringExtra(ReminderScheduler.EXTRA_ITEM_ID)
        val subtaskId = intent.getStringExtra(ReminderScheduler.EXTRA_SUBTASK_ID)
        val workspaceId = intent.getStringExtra(ReminderScheduler.EXTRA_WORKSPACE_ID)
        val notificationKey = ReminderNotifications.keyFor(itemId, subtaskId)
        val occurrence = intent.getLongExtra(ReminderScheduler.EXTRA_OCCURRENCE, 0L)

        // The local marker is what actually stops the nags from this moment on,
        // online or not (see ReminderScheduler.scheduleAll / scheduleNextNag and
        // MyFirebaseMessagingService). It records *this* occurrence, so a repeating
        // task/step's later occurrences still fire normally.
        Prefs.setLocalCompletion(context, notificationKey, occurrence)
        Prefs.clearSnooze(context, notificationKey)
        if (occurrence > 0) Prefs.setLastLocalOccurrence(context, notificationKey, occurrence)

        // One tap should silence every occurrence of this item/step, including a
        // snooze alarm - not just the one whose notification was tapped.
        ReminderScheduler.cancelForNotification(context, itemId, subtaskId)
        intent.getStringExtra(ReminderScheduler.EXTRA_KEY)
            ?.takeIf { it.isNotBlank() }
            ?.let { ReminderScheduler.cancel(context, it) }
        ReminderNotifications.cancel(context, notificationKey)

        // Only a real item can be completed server-side. Queue the PATCH for the
        // frontend (which holds the auth token); it retries until it gets through.
        if (!itemId.isNullOrBlank()) {
            Prefs.addPendingReminderAction(context, "complete", itemId, subtaskId, workspaceId, occurrence)
            context.sendBroadcast(Intent(ACTION_FLUSH_ACTIONS).setPackage(context.packageName))
        }
    }

    private fun snooze(context: Context, intent: Intent) {
        val itemId = intent.getStringExtra(ReminderScheduler.EXTRA_ITEM_ID)
        val subtaskId = intent.getStringExtra(ReminderScheduler.EXTRA_SUBTASK_ID)
        val notificationKey = ReminderNotifications.keyFor(itemId, subtaskId)

        if (!ReminderScheduler.snooze(context, intent)) {
            // Limit reached (or it was completed in the meantime). Leave the
            // notification alone - its Complete action still works.
            if (!Prefs.isLocallyCompleted(context, notificationKey)) {
                Toast.makeText(
                    context,
                    "Snooze limit reached - mark the task complete instead.",
                    Toast.LENGTH_SHORT
                ).show()
            }
            return
        }
        ReminderNotifications.cancel(context, notificationKey)
    }
}
