package com.workspaceapp.mobile

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import org.json.JSONArray

/**
 * Keeps the phone's own copy of the reminder schedule and turns each future entry
 * into an exact AlarmManager alarm. This is the offline half of reminders: the
 * server can only push (FCM) while the phone has a connection, but an alarm is
 * local, so it fires with no network at all.
 *
 * The schedule is produced by the backend (GET /api/reminders/schedule) and
 * handed over by the frontend through WebAppInterface.setReminderSchedule().
 * Repeating reminders are already expanded there, and fire_at is an absolute
 * epoch timestamp, so this class never needs timezone/recurrence logic.
 *
 * Called from a @JavascriptInterface background thread, so nothing here touches
 * the UI; SharedPreferences and AlarmManager are both safe from any thread.
 */
object ReminderScheduler {

    const val ACTION_REMINDER = "com.workspaceapp.mobile.REMINDER"
    const val ACTION_DISMISS = "com.workspaceapp.mobile.REMINDER_DISMISS"

    const val EXTRA_KEY = "key"
    const val EXTRA_TITLE = "title"
    const val EXTRA_BODY = "body"
    const val EXTRA_ITEM_ID = "item_id"
    const val EXTRA_WORKSPACE_ID = "workspace_id"
    const val EXTRA_KIND = "kind"
    const val EXTRA_SUBTASK_ID = "subtask_id"
    const val EXTRA_NAG_SECONDS = "nag_seconds"
    const val EXTRA_NAG_COUNT = "nag_count"

    /**
     * Safety stop for local nagging when the phone never reconnects and the user
     * never dismisses the notification. Without it, an unresolved task with a
     * 1-minute interval would keep waking the device forever. The server has
     * different behaviour (it nags until the task is done), but it also can only
     * do that while reachable; once the app syncs again, the real schedule wins.
     */
    private const val MAX_NAGS = 100

    /**
     * Replaces the stored schedule and re-arms alarms. Called after every sync, so
     * it is deliberately idempotent: cancel everything the previous copy armed,
     * then arm only what is current. `[]` (or `null`) means "no upcoming reminders".
     */
    fun saveAndSchedule(context: Context, json: String) {
        val clean = json.trim().ifEmpty { "[]" }
        // Validate before touching anything: a malformed payload must leave the
        // previously-armed schedule exactly as it is, not cancel it and then fail.
        val entries = try {
            JSONArray(clean)
        } catch (e: Exception) {
            return
        }
        Prefs.getReminderSchedule(context)?.let { cancelAll(context, it) }
        Prefs.setReminderSchedule(context, clean)
        scheduleAll(context, entries)
    }

    /** Re-arm from the saved copy after a reboot - alarms do not survive one. */
    fun rescheduleSaved(context: Context) {
        val json = Prefs.getReminderSchedule(context) ?: return
        val entries = try {
            JSONArray(json)
        } catch (e: Exception) {
            return
        }
        scheduleAll(context, entries)
    }

    /** Used when disconnecting from the server: no reminders should outlive it. */
    fun clearAll(context: Context) {
        Prefs.getReminderSchedule(context)?.let { cancelAll(context, it) }
        Prefs.setReminderSchedule(context, "[]")
    }

    private fun scheduleAll(context: Context, entries: JSONArray) {
        val now = System.currentTimeMillis()
        for (i in 0 until entries.length()) {
            val o = entries.optJSONObject(i) ?: continue
            val key = o.optString("key")
            if (key.isBlank()) continue
            val fireAtMillis = o.optLong("fire_at", 0L) * 1000L
            // Only future occurrences. Anything already due is the server's live
            // reminder loop's job (and firing every past occurrence on app open
            // would mean a pile of stale notifications after being offline).
            if (fireAtMillis <= now) continue

            arm(
                context,
                key = key,
                fireAtMillis = fireAtMillis,
                title = o.optString("title").ifBlank { "Workspace reminder" },
                body = o.optString("body"),
                itemId = o.optString("item_id").takeIf { it.isNotBlank() },
                workspaceId = o.optString("workspace_id").takeIf { it.isNotBlank() },
                kind = o.optString("kind").ifBlank { "task" },
                subtaskId = if (o.isNull("subtask_id")) null else o.optString("subtask_id").takeIf { it.isNotBlank() },
                nagSeconds = if (o.isNull("nag_seconds")) null else o.optInt("nag_seconds").takeIf { it > 0 },
                nagCount = 0,
            )
        }
    }

    /** Arms the next repeat of an unresolved local reminder, after one has fired. */
    fun scheduleNextNag(context: Context, fired: Intent) {
        val key = fired.getStringExtra(EXTRA_KEY) ?: return
        val nagSeconds = fired.getIntExtra(EXTRA_NAG_SECONDS, -1)
        val nagCount = fired.getIntExtra(EXTRA_NAG_COUNT, 0)
        if (nagSeconds <= 0 || nagCount >= MAX_NAGS) return

        arm(
            context,
            key = key,
            fireAtMillis = System.currentTimeMillis() + nagSeconds * 1000L,
            title = fired.getStringExtra(EXTRA_TITLE) ?: "Workspace reminder",
            body = fired.getStringExtra(EXTRA_BODY) ?: "",
            itemId = fired.getStringExtra(EXTRA_ITEM_ID),
            workspaceId = fired.getStringExtra(EXTRA_WORKSPACE_ID),
            kind = fired.getStringExtra(EXTRA_KIND),
            subtaskId = fired.getStringExtra(EXTRA_SUBTASK_ID),
            nagSeconds = nagSeconds,
            nagCount = nagCount + 1,
        )

        // Remember which alarm belongs to this notification, so swiping the
        // notification away can cancel it even when the visible notification was
        // posted by the FCM path (which only knows the item/subtask key, not the
        // occurrence key this alarm is tracked by).
        Prefs.setActiveAlarmKey(
            context,
            ReminderNotifications.keyFor(
                fired.getStringExtra(EXTRA_ITEM_ID),
                fired.getStringExtra(EXTRA_SUBTASK_ID),
            ),
            key,
        )
    }

    /**
     * Cancels an armed alarm. `key` may be either an occurrence key (from a local
     * notification's delete intent) or an item/subtask notification key (from an
     * FCM-posted one); in the latter case the nag alarm recorded for it is used.
     */
    fun cancel(context: Context, key: String) {
        if (cancelExact(context, key)) return
        val active = Prefs.getActiveAlarmKey(context, key) ?: return
        if (cancelExact(context, active)) Prefs.setActiveAlarmKey(context, key, null)
    }

    private fun cancelExact(context: Context, occurrenceKey: String): Boolean {
        val intent = Intent(context, ReminderAlarmReceiver::class.java).setAction(ACTION_REMINDER)
        // FLAG_NO_CREATE: if no matching alarm exists there is nothing to cancel,
        // and this must not leave a stray PendingIntent behind.
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            occurrenceKey.hashCode(),
            intent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        ) ?: return false
        context.getSystemService(AlarmManager::class.java).cancel(pendingIntent)
        pendingIntent.cancel()
        return true
    }

    private fun cancelAll(context: Context, json: String) {
        val entries = try {
            JSONArray(json)
        } catch (e: Exception) {
            return
        }
        for (i in 0 until entries.length()) {
            val key = entries.optJSONObject(i)?.optString("key").orEmpty()
            if (key.isNotBlank()) cancel(context, key)
        }
    }

    private fun arm(
        context: Context,
        key: String,
        fireAtMillis: Long,
        title: String,
        body: String,
        itemId: String?,
        workspaceId: String?,
        kind: String?,
        subtaskId: String?,
        nagSeconds: Int?,
        nagCount: Int,
    ) {
        val intent = Intent(context, ReminderAlarmReceiver::class.java).apply {
            action = ACTION_REMINDER
            putExtra(EXTRA_KEY, key)
            putExtra(EXTRA_TITLE, title)
            putExtra(EXTRA_BODY, body)
            itemId?.let { putExtra(EXTRA_ITEM_ID, it) }
            workspaceId?.let { putExtra(EXTRA_WORKSPACE_ID, it) }
            kind?.let { putExtra(EXTRA_KIND, it) }
            subtaskId?.let { putExtra(EXTRA_SUBTASK_ID, it) }
            nagSeconds?.let { putExtra(EXTRA_NAG_SECONDS, it) }
            putExtra(EXTRA_NAG_COUNT, nagCount)
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            key.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val alarmManager = context.getSystemService(AlarmManager::class.java)
        val canExact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager.canScheduleExactAlarms()
        if (canExact) {
            // Allowed to fire in Doze. (Android still throttles repeated exact
            // alarms in deep idle to roughly one every 9 minutes, so very short
            // nag intervals are best-effort while the phone is idle and offline.)
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, fireAtMillis, pendingIntent)
        } else {
            // Exact-alarm permission not granted (Android 12+): still arm it, just
            // let the system choose the precise moment rather than dropping it.
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, fireAtMillis, pendingIntent)
        }
    }
}
