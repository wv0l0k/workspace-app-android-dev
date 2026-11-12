package com.workspaceapp.mobile

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Everything the app needs to remember locally: the server URL (the whole
 * point of the Home-Assistant-style "generic APK" - this is the only thing
 * that ever changes per install, so the app itself never needs rebuilding
 * when the server-side frontend changes), the current FCM registration token
 * pending being sent to that server, a login token pending handoff to the
 * WebView right after SettingsActivity signs in natively, and the session
 * token mirrored by the page for the background schedule refresh.
 */
object Prefs {
    private const val PREFS_NAME = "workspace_prefs"
    private const val KEY_SERVER_URL = "server_url"
    private const val KEY_FCM_TOKEN = "fcm_token"
    private const val KEY_AUTH_TOKEN = "auth_token"
    private const val KEY_PENDING_LOGIN_TOKEN = "pending_login_token"
    private const val KEY_REMINDER_SCHEDULE = "reminder_schedule"
    private const val KEY_LAST_NOTIFIED_PREFIX = "last_notified_"
    private const val KEY_LOCAL_OCCURRENCE_PREFIX = "local_occurrence_"
    private const val KEY_EXACT_ALARM_ASKED = "exact_alarm_asked"

    // Notification actions (Complete/Snooze) - see ReminderActionReceiver.
    private const val KEY_PENDING_ACTIONS = "pending_reminder_actions"
    private const val KEY_LOCAL_COMPLETION_PREFIX = "local_completion_"
    private const val KEY_LOCAL_COMPLETION_TIME_PREFIX = "local_completion_time_"
    private const val KEY_SNOOZE_UNTIL_PREFIX = "snooze_until_"
    private const val KEY_SNOOZE_COUNT_PREFIX = "snooze_count_"
    private const val KEY_SNOOZE_OCCURRENCE_PREFIX = "snooze_occurrence_"

    /** How long a locally-completed marker is kept if the synced schedule never
     *  stops containing the item (e.g. the completion never reached the server). */
    private const val LOCAL_COMPLETION_TTL_MS = 30L * 24 * 60 * 60 * 1000

    /** Per-reminder snooze state: when the nags resume, how many times this
     *  occurrence has already been snoozed, and which occurrence it was for. */
    data class SnoozeState(val untilMillis: Long, val count: Int, val occurrenceEpoch: Long)

    fun getServerUrl(context: Context): String? =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(KEY_SERVER_URL, null)

    fun setServerUrl(context: Context, url: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_SERVER_URL, url.trimEnd('/')).apply()
    }

    fun getPendingFcmToken(context: Context): String? =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(KEY_FCM_TOKEN, null)

    fun setPendingFcmToken(context: Context, token: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_FCM_TOKEN, token).apply()
    }

    // ---------- Background schedule refresh ----------
    //
    // The frontend owns the login session, but the WorkManager refresh has to run
    // while the WebView isn't open, so the page mirrors its current token here
    // (WebAppInterface.setSessionToken). This is the same token the WebView keeps
    // in localStorage - it is only ever sent back to this app's own server.

    fun getAuthToken(context: Context): String? =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(KEY_AUTH_TOKEN, null)

    fun setAuthToken(context: Context, token: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_AUTH_TOKEN, token).apply()
    }

    fun clearAuthToken(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().remove(KEY_AUTH_TOKEN).apply()
    }

    /**
     * SettingsActivity signs in with the API directly (see LoginClient) to get
     * a session token before the WebView ever loads - this is where that token
     * waits for WebAppInterface.getPendingLoginToken() to hand it to the page
     * exactly once. Not meant to be read twice: the getter clears it as it
     * returns, so a stale token can never accidentally get reused later.
     */
    fun setPendingLoginToken(context: Context, token: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_PENDING_LOGIN_TOKEN, token).apply()
    }

    fun consumePendingLoginToken(context: Context): String? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val token = prefs.getString(KEY_PENDING_LOGIN_TOKEN, null)
        if (token != null) prefs.edit().remove(KEY_PENDING_LOGIN_TOKEN).apply()
        return token
    }

    /**
     * The phone's copy of the reminder schedule produced by
     * GET /api/reminders/schedule (see ReminderScheduler). Stored as the raw JSON
     * array; the native side never needs to know how it was built.
     */
    fun getReminderSchedule(context: Context): String? =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(KEY_REMINDER_SCHEDULE, null)

    fun setReminderSchedule(context: Context, json: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_REMINDER_SCHEDULE, json).apply()
    }

    /**
     * The latest occurrence time (epoch seconds) that this item/step's local alarm
     * has already handled. FCM uses it to discard a push for an occurrence the phone
     * already showed - e.g. a message queued while offline and delivered on
     * reconnect. A genuinely later occurrence has a larger timestamp and is shown.
     */
    fun getLastLocalOccurrence(context: Context, notificationKey: String): Long =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getLong(KEY_LOCAL_OCCURRENCE_PREFIX + notificationKey, 0L)

    fun setLastLocalOccurrence(context: Context, notificationKey: String, occurrenceEpoch: Long) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (occurrenceEpoch <= getLastLocalOccurrence(context, notificationKey)) return
        prefs.edit().putLong(KEY_LOCAL_OCCURRENCE_PREFIX + notificationKey, occurrenceEpoch).apply()
    }

    /**
     * When a reminder for this key last became a visible notification, from either
     * the FCM path or a local alarm. Used only to suppress the duplicate that the
     * other path would otherwise post moments later for the same occurrence.
     */
    fun getLastNotified(context: Context, key: String): Long =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getLong(KEY_LAST_NOTIFIED_PREFIX + key, 0L)

    fun setLastNotified(context: Context, key: String, atMillis: Long) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putLong(KEY_LAST_NOTIFIED_PREFIX + key, atMillis).apply()
    }

    /** Whether the Android 12+ exact-alarm settings screen has already been shown. */
    fun wasExactAlarmAsked(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getBoolean(KEY_EXACT_ALARM_ASKED, false)

    fun setExactAlarmAsked(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_EXACT_ALARM_ASKED, true).apply()
    }

    // ---------- Notification actions: local completion ----------
    //
    // A notification's "Mark complete" action has to stop the nags immediately even
    // with no connection. The server is the source of truth, but the phone cannot
    // wait for it: it records the completion locally first (keyed by the same
    // item/subtask identity notifications use) and queues a PATCH for the frontend
    // to send once it is online. Until then, every alarm/FCM path checks this marker
    // and stays silent.
    //
    // The marker records the occurrence that was completed, not just "this item is
    // done forever": a repeating task/step produces later occurrences, and those
    // must still fire. Only occurrences at or before the completed one are silenced.
    //
    // The marker is pruned once a genuinely fresh schedule no longer contains the
    // item (the server has caught up), or after 30 days as a last resort.

    /** Returns the occurrence (epoch seconds) this item/step was last completed at,
     *  or 0 if it has not been completed locally. */
    fun getLocalCompletionOccurrence(context: Context, notificationKey: String): Long =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getLong(KEY_LOCAL_COMPLETION_PREFIX + notificationKey, 0L)

    /**
     * Whether this occurrence has been completed on the phone. An unknown occurrence
     * (0, e.g. a push from a backend without fire_at) is treated as covered by any
     * completion marker; a genuinely later occurrence of a repeating reminder is not.
     */
    fun isLocallyCompleted(context: Context, notificationKey: String, occurrenceEpoch: Long = 0L): Boolean {
        val completedOccurrence = getLocalCompletionOccurrence(context, notificationKey)
        if (completedOccurrence <= 0L) return false
        return occurrenceEpoch <= 0L || occurrenceEpoch <= completedOccurrence
    }

    fun setLocalCompletion(context: Context, notificationKey: String, occurrenceEpoch: Long) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        // Never move the marker backwards: a stale notification action for an older
        // occurrence must not resurrect one already completed more recently. A push
        // from an older backend without fire_at still has to silence something, so
        // fall back to "now" - otherwise the marker would stay 0 and silence nothing.
        val existing = prefs.getLong(KEY_LOCAL_COMPLETION_PREFIX + notificationKey, 0L)
        val completedAt = if (occurrenceEpoch > 0) occurrenceEpoch else System.currentTimeMillis() / 1000
        val marker = maxOf(existing, completedAt)
        prefs.edit()
            .putLong(KEY_LOCAL_COMPLETION_PREFIX + notificationKey, marker)
            .putLong(KEY_LOCAL_COMPLETION_TIME_PREFIX + notificationKey, System.currentTimeMillis())
            .apply()
    }

    // ---------- Notification actions: snooze ----------

    fun getSnooze(context: Context, notificationKey: String): SnoozeState {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return SnoozeState(
            untilMillis = prefs.getLong(KEY_SNOOZE_UNTIL_PREFIX + notificationKey, 0L),
            count = prefs.getInt(KEY_SNOOZE_COUNT_PREFIX + notificationKey, 0),
            occurrenceEpoch = prefs.getLong(KEY_SNOOZE_OCCURRENCE_PREFIX + notificationKey, 0L),
        )
    }

    fun setSnooze(
        context: Context,
        notificationKey: String,
        untilMillis: Long,
        count: Int,
        occurrenceEpoch: Long,
    ) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putLong(KEY_SNOOZE_UNTIL_PREFIX + notificationKey, untilMillis)
            .putInt(KEY_SNOOZE_COUNT_PREFIX + notificationKey, count)
            .putLong(KEY_SNOOZE_OCCURRENCE_PREFIX + notificationKey, occurrenceEpoch)
            .apply()
    }

    /** The snooze has elapsed: keep its count (so the per-occurrence limit still
     *  applies) but stop suppressing the reminder. */
    fun expireSnooze(context: Context, notificationKey: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putLong(KEY_SNOOZE_UNTIL_PREFIX + notificationKey, 0L).apply()
    }

    /** The reminder was completed (or its occurrence is gone): forget snooze entirely. */
    fun clearSnooze(context: Context, notificationKey: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .remove(KEY_SNOOZE_UNTIL_PREFIX + notificationKey)
            .remove(KEY_SNOOZE_COUNT_PREFIX + notificationKey)
            .remove(KEY_SNOOZE_OCCURRENCE_PREFIX + notificationKey)
            .apply()
    }

    /**
     * Drops completion/snooze bookkeeping for reminders a fresh schedule no longer
     * contains. Called from ReminderScheduler.saveAndSchedule() so this state cannot
     * grow forever as tasks are completed and deleted.
     */
    @Synchronized
    fun pruneReminderState(context: Context, activeNotificationKeys: Set<String>) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val editor = prefs.edit()
        val now = System.currentTimeMillis()
        var changed = false

        for (key in prefs.all.keys) {
            when {
                // Must come before KEY_LOCAL_COMPLETION_PREFIX: the time key also
                // starts with it, and would otherwise be mistaken for a marker and
                // deleted on the very first sync (silently disabling the TTL).
                key.startsWith(KEY_LOCAL_COMPLETION_TIME_PREFIX) -> {
                    // Removed together with its occurrence marker below; a stray time
                    // value with no marker is safe to drop too.
                    val notificationKey = key.substring(KEY_LOCAL_COMPLETION_TIME_PREFIX.length)
                    if (Prefs.getLocalCompletionOccurrence(context, notificationKey) <= 0L) {
                        editor.remove(key)
                        changed = true
                    }
                }
                key.startsWith(KEY_LOCAL_COMPLETION_PREFIX) -> {
                    val notificationKey = key.substring(KEY_LOCAL_COMPLETION_PREFIX.length)
                    val completedAt = prefs.getLong(KEY_LOCAL_COMPLETION_TIME_PREFIX + notificationKey, 0L)
                    if (notificationKey !in activeNotificationKeys ||
                        (completedAt > 0 && now - completedAt > LOCAL_COMPLETION_TTL_MS)) {
                        editor.remove(key)
                        editor.remove(KEY_LOCAL_COMPLETION_TIME_PREFIX + notificationKey)
                        changed = true
                    }
                }
                key.startsWith(KEY_SNOOZE_UNTIL_PREFIX) ||
                    key.startsWith(KEY_SNOOZE_COUNT_PREFIX) ||
                    key.startsWith(KEY_SNOOZE_OCCURRENCE_PREFIX) -> {
                    val prefix = when {
                        key.startsWith(KEY_SNOOZE_UNTIL_PREFIX) -> KEY_SNOOZE_UNTIL_PREFIX
                        key.startsWith(KEY_SNOOZE_COUNT_PREFIX) -> KEY_SNOOZE_COUNT_PREFIX
                        else -> KEY_SNOOZE_OCCURRENCE_PREFIX
                    }
                    if (key.substring(prefix.length) !in activeNotificationKeys) {
                        editor.remove(key)
                        changed = true
                    }
                }
            }
        }
        if (changed) editor.apply()
    }

    // ---------- Notification actions: queued server updates ----------
    //
    // Completing from a notification works offline, so the server PATCH is deferred.
    // The receiver appends an action here; the frontend (which owns the auth token)
    // drains the queue over the JS bridge the next time it is online.

    @Synchronized
    fun getPendingReminderActions(context: Context): String =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_PENDING_ACTIONS, null) ?: "[]"

    /** Atomically removes and returns the queue, so two flushes can't both send it. */
    @Synchronized
    fun takePendingReminderActions(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val json = prefs.getString(KEY_PENDING_ACTIONS, null) ?: "[]"
        prefs.edit().putString(KEY_PENDING_ACTIONS, "[]").apply()
        return json
    }

    @Synchronized
    fun addPendingReminderAction(
        context: Context,
        type: String,
        itemId: String,
        subtaskId: String?,
        workspaceId: String?,
        occurrenceEpoch: Long,
    ) {
        val current = try {
            JSONArray(getPendingReminderActions(context))
        } catch (e: Exception) {
            JSONArray()
        }
        val action = JSONObject().apply {
            put("type", type)
            put("item_id", itemId)
            put("subtask_id", subtaskId ?: JSONObject.NULL)
            put("workspace_id", workspaceId ?: JSONObject.NULL)
            put("occurrence", occurrenceEpoch)
        }
        // Keep only the newest completion for a given item/step: if two were taken
        // before a flush, the later occurrence is the one that matters.
        val filtered = JSONArray()
        for (i in 0 until current.length()) {
            val existing = current.optJSONObject(i) ?: continue
            if (!sameAction(existing, action)) filtered.put(existing)
        }
        filtered.put(action)
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_PENDING_ACTIONS, filtered.toString()).apply()
    }

    /** Puts back actions the frontend could not send (still offline, or a server
     *  error), merging them with anything queued in the meantime. */
    @Synchronized
    fun restorePendingReminderActions(context: Context, json: String) {
        val restored = try {
            JSONArray(json)
        } catch (e: Exception) {
            return
        }
        val current = try {
            JSONArray(getPendingReminderActions(context))
        } catch (e: Exception) {
            JSONArray()
        }
        for (i in 0 until restored.length()) {
            val action = restored.optJSONObject(i) ?: continue
            if (!containsAction(current, action)) current.put(action)
        }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_PENDING_ACTIONS, current.toString()).apply()
    }

    private fun containsAction(queue: JSONArray, candidate: JSONObject): Boolean {
        for (i in 0 until queue.length()) {
            val existing = queue.optJSONObject(i) ?: continue
            if (sameAction(existing, candidate)) return true
        }
        return false
    }

    private fun sameAction(a: JSONObject, b: JSONObject): Boolean {
        val aSubtask = if (a.isNull("subtask_id")) "" else a.optString("subtask_id")
        val bSubtask = if (b.isNull("subtask_id")) "" else b.optString("subtask_id")
        return a.optString("type") == b.optString("type") &&
            a.optString("item_id") == b.optString("item_id") &&
            aSubtask == bSubtask
    }

    /** "Disconnect from server" (the mobile Logout button): wipes everything
     *  remembered locally, back to a fresh install's state. */
    fun clearAll(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().clear().apply()
    }
}
