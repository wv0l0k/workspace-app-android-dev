package com.workspaceapp.mobile

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import org.json.JSONArray

/**
 * Keeps the phone's own copy of the reminder schedule and turns each outstanding
 * entry into an exact AlarmManager alarm. This is the offline half of reminders:
 * the server can only push (FCM) while the phone has a connection, but an alarm
 * is local, so it fires with no network at all.
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

    const val EXTRA_KEY = "key"
    const val EXTRA_TITLE = "title"
    const val EXTRA_BODY = "body"
    const val EXTRA_ITEM_ID = "item_id"
    const val EXTRA_WORKSPACE_ID = "workspace_id"
    const val EXTRA_KIND = "kind"
    const val EXTRA_SUBTASK_ID = "subtask_id"
    const val EXTRA_NAG_SECONDS = "nag_seconds"

    /** The occurrence's own absolute time (epoch seconds). Every nag for the same
     *  occurrence carries the same value, so the FCM side can tell "this reminder
     *  was already shown locally" apart from a genuinely newer occurrence. */
    const val EXTRA_OCCURRENCE = "occurrence"

    /** How many times one occurrence can be snoozed from its notification. */
    const val MAX_SNOOZES = 3

    /** How long each snooze postpones the nags. */
    const val SNOOZE_MILLIS = 10 * 60 * 1000L

    /** The same stable occurrence key the backend builds for GET /api/reminders/schedule. */
    fun occurrenceKey(itemId: String?, subtaskId: String?, occurrenceEpoch: Long): String =
        "${itemId ?: ""}:${subtaskId ?: ""}:$occurrenceEpoch"

    /**
     * Local nags keep going until the task/step is marked complete (the next sync
     * then removes it from the schedule and cancels its alarm), exactly like the
     * server's own nagging. The one exception is an occurrence more than a day old:
     * by then a repeating reminder will have produced a newer occurrence, and a
     * one-off should have been completed - continuing to wake the device forever
     * about it would only ever be a stale nuisance.
     */
    private const val MAX_NAG_AGE_SECONDS = 24 * 60 * 60L

    /**
     * Replaces the stored schedule and re-arms alarms. Called after every sync, so
     * it must be idempotent: alarms for occurrences the new schedule no longer
     * contains are cancelled, while occurrences that are still outstanding keep
     * whatever alarm/nag chain they already have. `[]` (or `null`) means "no
     * upcoming reminders".
     */
    @Synchronized
    fun saveAndSchedule(context: Context, json: String) {
        val clean = json.trim().ifEmpty { "[]" }
        // Validate before touching anything: a malformed payload must leave the
        // previously-armed schedule exactly as it is, not cancel it and then fail.
        val entries = try {
            JSONArray(clean)
        } catch (e: Exception) {
            return
        }
        // Only cancel what is genuinely no longer in the schedule (completed,
        // deleted, or edited to a different time). Occurrences still present -
        // including an already-due "active" one that is still nagging - keep their
        // existing alarm, so a sync doesn't restart/stop their nag chain.
        val keep = keysOf(entries)
        Prefs.getReminderSchedule(context)?.let { cancelMissing(context, it, keep) }
        // Forget completion/snooze bookkeeping for reminders this fresh schedule no
        // longer contains (normally: the completion synced, or the task was deleted).
        Prefs.pruneReminderState(context, notificationKeysOf(entries))
        Prefs.setReminderSchedule(context, clean)
        scheduleAll(context, entries)
    }

    /** Re-arm from the saved copy after a reboot - alarms do not survive one. */
    @Synchronized
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
    @Synchronized
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
            val occurrenceEpoch = o.optLong("fire_at", 0L)
            val fireAtMillis = occurrenceEpoch * 1000L
            val nag = if (o.isNull("nag_seconds")) null else o.optInt("nag_seconds").takeIf { it > 0 }
            val itemId = o.optString("item_id").takeIf { it.isNotBlank() }
            val subtaskId = if (o.isNull("subtask_id")) null else o.optString("subtask_id").takeIf { it.isNotBlank() }
            val notificationKey = ReminderNotifications.keyFor(itemId, subtaskId)

            // Completed from the notification (possibly still offline, so the
            // schedule hasn't caught up): never re-arm, and drop anything already armed.
            if (Prefs.isLocallyCompleted(context, notificationKey, occurrenceEpoch)) {
                cancel(context, key)
                continue
            }

            val nextMillis: Long
            val snooze = Prefs.getSnooze(context, notificationKey)
            if (occurrenceEpoch > 0 && snooze.occurrenceEpoch == occurrenceEpoch && snooze.untilMillis > now) {
                // A snooze taken before this sync/reboot is still in effect - resume
                // at its end rather than at the original time or a fresh nag.
                nextMillis = snooze.untilMillis
            } else if (fireAtMillis > now) {
                // A normal future occurrence: arm it for its exact time.
                nextMillis = fireAtMillis
            } else if (nag != null && occurrenceEpoch > 0 &&
                now / 1000 - occurrenceEpoch <= MAX_NAG_AGE_SECONDS) {
                // Already due but still in the stored schedule: it was outstanding
                // at the last sync and the server has not reported it completed, so
                // keep the reminder alive. If its nag alarm is already armed, leave
                // it alone - resetting it on every sync would keep pushing the next
                // nag further away. Otherwise start one now.
                //
                // Deliberately derived from fire_at rather than the server's
                // "active" flag: the saved copy can predate the occurrence (e.g. a
                // reboot before the next sync), and a stale `active: false` must not
                // silence a reminder that is still unresolved.
                if (alarmExists(context, key)) continue
                nextMillis = now + nag * 1000L
            } else {
                // Past and not outstanding (e.g. a one-off already handled by the
                // server's own reminder loop): nothing to arm.
                continue
            }

            arm(
                context,
                key = key,
                fireAtMillis = nextMillis,
                occurrenceEpoch = occurrenceEpoch,
                title = o.optString("title").ifBlank { "Workspace reminder" },
                body = o.optString("body"),
                itemId = itemId,
                workspaceId = o.optString("workspace_id").takeIf { it.isNotBlank() },
                kind = o.optString("kind").ifBlank { "task" },
                subtaskId = subtaskId,
                nagSeconds = nag,
            )
        }
    }

    /** Arms the next repeat of an unresolved local reminder, after one has fired. */
    @Synchronized
    fun scheduleNextNag(context: Context, fired: Intent) {
        val key = fired.getStringExtra(EXTRA_KEY) ?: return
        val nagSeconds = fired.getIntExtra(EXTRA_NAG_SECONDS, -1)
        if (nagSeconds <= 0) return

        val itemId = fired.getStringExtra(EXTRA_ITEM_ID)
        val subtaskId = fired.getStringExtra(EXTRA_SUBTASK_ID)
        val notificationKey = ReminderNotifications.keyFor(itemId, subtaskId)
        val occurrence = fired.getLongExtra(EXTRA_OCCURRENCE, 0L)

        // Completed from the notification: the completion marker is the local
        // source of truth until the server's schedule catches up. (A later
        // occurrence of a repeating reminder is not covered by it.)
        if (Prefs.isLocallyCompleted(context, notificationKey, occurrence)) return

        if (occurrence > 0 && System.currentTimeMillis() / 1000 - occurrence > MAX_NAG_AGE_SECONDS) return

        // While a snooze is in effect its own alarm is the one to run; a stale nag
        // that fired concurrently must not shorten (or duplicate) it. (Re-arming at
        // the snooze's end also covers an alarm that was delivered a little early.)
        val snooze = Prefs.getSnooze(context, notificationKey)
        if (occurrence > 0 && snooze.occurrenceEpoch == occurrence &&
            snooze.untilMillis > System.currentTimeMillis()) {
            arm(
                context,
                key = key,
                fireAtMillis = snooze.untilMillis,
                occurrenceEpoch = occurrence,
                title = fired.getStringExtra(EXTRA_TITLE) ?: "Workspace reminder",
                body = fired.getStringExtra(EXTRA_BODY) ?: "",
                itemId = itemId,
                workspaceId = fired.getStringExtra(EXTRA_WORKSPACE_ID),
                kind = fired.getStringExtra(EXTRA_KIND),
                subtaskId = subtaskId,
                nagSeconds = nagSeconds,
            )
            return
        }

        arm(
            context,
            key = key,
            fireAtMillis = System.currentTimeMillis() + nagSeconds * 1000L,
            occurrenceEpoch = occurrence,
            title = fired.getStringExtra(EXTRA_TITLE) ?: "Workspace reminder",
            body = fired.getStringExtra(EXTRA_BODY) ?: "",
            itemId = itemId,
            workspaceId = fired.getStringExtra(EXTRA_WORKSPACE_ID),
            kind = fired.getStringExtra(EXTRA_KIND),
            subtaskId = subtaskId,
            nagSeconds = nagSeconds,
        )
    }

    /**
     * The notification's Snooze action. Postpones this occurrence by SNOOZE_MILLIS
     * (up to MAX_SNOOZES times), purely on-device - the server knows nothing about
     * snoozes. Returns false when the limit for this occurrence is already used.
     */
    @Synchronized
    fun snooze(context: Context, fired: Intent): Boolean {
        val itemId = fired.getStringExtra(EXTRA_ITEM_ID)
        val subtaskId = fired.getStringExtra(EXTRA_SUBTASK_ID)
        val notificationKey = ReminderNotifications.keyFor(itemId, subtaskId)
        val occurrence = fired.getLongExtra(EXTRA_OCCURRENCE, 0L)
        if (Prefs.isLocallyCompleted(context, notificationKey, occurrence)) return false

        val state = Prefs.getSnooze(context, notificationKey)
        val used = if (occurrence > 0 && state.occurrenceEpoch == occurrence) state.count else 0
        if (used >= MAX_SNOOZES) return false

        val until = System.currentTimeMillis() + SNOOZE_MILLIS
        Prefs.setSnooze(context, notificationKey, until, used + 1, occurrence)

        val key = fired.getStringExtra(EXTRA_KEY)?.takeIf { it.isNotBlank() }
            ?: occurrenceKey(itemId, subtaskId, occurrence)
        arm(
            context,
            key = key,
            fireAtMillis = until,
            occurrenceEpoch = occurrence,
            title = fired.getStringExtra(EXTRA_TITLE) ?: "Workspace reminder",
            body = fired.getStringExtra(EXTRA_BODY) ?: "",
            itemId = itemId,
            workspaceId = fired.getStringExtra(EXTRA_WORKSPACE_ID),
            kind = fired.getStringExtra(EXTRA_KIND),
            subtaskId = subtaskId,
            nagSeconds = fired.getIntExtra(EXTRA_NAG_SECONDS, -1).takeIf { it > 0 },
        )
        return true
    }

    /**
     * Cancels every armed alarm belonging to one item/step (all of its occurrences,
     * including a snooze alarm). Used by the Complete action: it can come from an
     * FCM push, which doesn't know the schedule's occurrence key.
     */
    @Synchronized
    fun cancelForNotification(context: Context, itemId: String?, subtaskId: String?) {
        val json = Prefs.getReminderSchedule(context) ?: return
        val entries = try {
            JSONArray(json)
        } catch (e: Exception) {
            return
        }
        val target = ReminderNotifications.keyFor(itemId, subtaskId)
        for (i in 0 until entries.length()) {
            val o = entries.optJSONObject(i) ?: continue
            val key = o.optString("key")
            if (key.isBlank()) continue
            val entryItem = o.optString("item_id").takeIf { it.isNotBlank() }
            val entrySubtask = if (o.isNull("subtask_id")) null else o.optString("subtask_id").takeIf { it.isNotBlank() }
            if (ReminderNotifications.keyFor(entryItem, entrySubtask) == target) cancel(context, key)
        }
    }

    /** Cancels an armed alarm by its occurrence key. */
    fun cancel(context: Context, key: String) {
        val intent = Intent(context, ReminderAlarmReceiver::class.java).setAction(ACTION_REMINDER)
        // FLAG_NO_CREATE: if no matching alarm exists there is nothing to cancel,
        // and this must not leave a stray PendingIntent behind.
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            key.hashCode(),
            intent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        ) ?: return
        context.getSystemService(AlarmManager::class.java).cancel(pendingIntent)
        pendingIntent.cancel()
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

    /** Cancels alarms from the previous saved copy that the new schedule no longer
     *  contains (a completed/deleted task, or one whose reminder time changed). */
    private fun cancelMissing(context: Context, oldJson: String, keep: Set<String>) {
        val old = try {
            JSONArray(oldJson)
        } catch (e: Exception) {
            return
        }
        for (i in 0 until old.length()) {
            val key = old.optJSONObject(i)?.optString("key").orEmpty()
            if (key.isNotBlank() && key !in keep) cancel(context, key)
        }
    }

    private fun keysOf(entries: JSONArray): Set<String> {
        val keys = HashSet<String>()
        for (i in 0 until entries.length()) {
            val key = entries.optJSONObject(i)?.optString("key").orEmpty()
            if (key.isNotBlank()) keys.add(key)
        }
        return keys
    }

    /** The item/subtask identities present in a schedule - the keys completion and
     *  snooze bookkeeping are stored under. */
    private fun notificationKeysOf(entries: JSONArray): Set<String> {
        val keys = HashSet<String>()
        for (i in 0 until entries.length()) {
            val o = entries.optJSONObject(i) ?: continue
            val itemId = o.optString("item_id").takeIf { it.isNotBlank() }
            val subtaskId = if (o.isNull("subtask_id")) null else o.optString("subtask_id").takeIf { it.isNotBlank() }
            keys.add(ReminderNotifications.keyFor(itemId, subtaskId))
        }
        return keys
    }

    private fun alarmExists(context: Context, key: String): Boolean {
        val intent = Intent(context, ReminderAlarmReceiver::class.java).setAction(ACTION_REMINDER)
        return PendingIntent.getBroadcast(
            context,
            key.hashCode(),
            intent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        ) != null
    }

    private fun arm(
        context: Context,
        key: String,
        fireAtMillis: Long,
        occurrenceEpoch: Long,
        title: String,
        body: String,
        itemId: String?,
        workspaceId: String?,
        kind: String?,
        subtaskId: String?,
        nagSeconds: Int?,
    ) {
        val intent = Intent(context, ReminderAlarmReceiver::class.java).apply {
            action = ACTION_REMINDER
            putExtra(EXTRA_KEY, key)
            putExtra(EXTRA_OCCURRENCE, occurrenceEpoch)
            putExtra(EXTRA_TITLE, title)
            putExtra(EXTRA_BODY, body)
            itemId?.let { putExtra(EXTRA_ITEM_ID, it) }
            workspaceId?.let { putExtra(EXTRA_WORKSPACE_ID, it) }
            kind?.let { putExtra(EXTRA_KIND, it) }
            subtaskId?.let { putExtra(EXTRA_SUBTASK_ID, it) }
            nagSeconds?.let { putExtra(EXTRA_NAG_SECONDS, it) }
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
