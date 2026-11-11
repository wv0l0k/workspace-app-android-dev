package com.workspaceapp.mobile

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Fires when a locally-scheduled reminder's AlarmManager alarm goes off. Runs
 * entirely on-device: no WebView, no network, no server. This is what makes a
 * reminder arrive when the phone is offline.
 */
class ReminderAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        // This key identifies the *occurrence* (item + time) and is what the alarm
        // itself is tracked/cancelled by. Notifications, though, must use the same
        // item/subtask identity as the FCM path, or the two paths would stack as
        // separate notifications instead of updating one.
        val alarmKey = intent.getStringExtra(ReminderScheduler.EXTRA_KEY) ?: return
        val title = intent.getStringExtra(ReminderScheduler.EXTRA_TITLE) ?: "Workspace reminder"
        val body = intent.getStringExtra(ReminderScheduler.EXTRA_BODY) ?: ""
        val itemId = intent.getStringExtra(ReminderScheduler.EXTRA_ITEM_ID)
        val subtaskId = intent.getStringExtra(ReminderScheduler.EXTRA_SUBTASK_ID)
        val notificationKey = ReminderNotifications.keyFor(itemId, subtaskId)
        val occurrence = intent.getLongExtra(ReminderScheduler.EXTRA_OCCURRENCE, 0L)
        val now = System.currentTimeMillis()

        // Completed from a notification (possibly while offline, so the synced
        // schedule still contains this occurrence): stay silent and drop the chain.
        if (Prefs.isLocallyCompleted(context, notificationKey, occurrence)) {
            ReminderScheduler.cancel(context, alarmKey)
            ReminderNotifications.cancel(context, notificationKey)
            return
        }

        // A snooze is still in effect. Normally the snooze replaced this alarm, but
        // one already dispatched just before the tap can still land here - and it
        // must not cut the snooze short or alert early. At the end of the snooze the
        // alarm becomes the one that resumes the chain.
        val snooze = Prefs.getSnooze(context, notificationKey)
        if (occurrence > 0 && snooze.occurrenceEpoch == occurrence) {
            if (snooze.untilMillis > now) {
                // Delivered early - put the alarm back at the snooze's real end.
                ReminderScheduler.scheduleNextNag(context, intent)
                return
            }
            if (snooze.untilMillis > 0) Prefs.expireSnooze(context, notificationKey)
        }

        // The FCM push for the same occurrence may have arrived first (when online,
        // both paths are active). Same key, same notification id, but without this
        // guard the second one would still re-alert/sound. Whichever is first wins.
        if (now - Prefs.getLastNotified(context, notificationKey) >= ReminderNotifications.DEDUPE_WINDOW_MS) {
            ReminderNotifications.show(
                context,
                key = notificationKey,
                title = title,
                body = body,
                itemId = itemId,
                workspaceId = intent.getStringExtra(ReminderScheduler.EXTRA_WORKSPACE_ID),
                kind = intent.getStringExtra(ReminderScheduler.EXTRA_KIND),
                subtaskId = subtaskId,
                occurrenceEpoch = occurrence,
                nagSeconds = intent.getIntExtra(ReminderScheduler.EXTRA_NAG_SECONDS, -1).takeIf { it > 0 },
            )
            Prefs.setLastNotified(context, notificationKey, now)
        }

        // Record that this occurrence has been handled locally (whether or not the
        // notification above was the one shown - FCM may have won the race). From
        // now on, any FCM delivery for this same occurrence is a duplicate: most
        // importantly a push FCM queued while the phone was offline and delivers on
        // reconnect, which used to alert a second time.
        if (occurrence > 0) {
            Prefs.setLastLocalOccurrence(context, notificationKey, occurrence)
        }

        // Keep nagging at the same interval the server would, until the task/step is
        // marked complete (the next schedule sync then cancels this alarm). Swiping
        // the notification away deliberately does NOT stop it.
        ReminderScheduler.scheduleNextNag(context, intent)
    }
}
