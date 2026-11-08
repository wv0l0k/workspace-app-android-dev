package com.workspaceapp.mobile

import android.content.Context

/**
 * Everything the app needs to remember locally: the server URL (the whole
 * point of the Home-Assistant-style "generic APK" - this is the only thing
 * that ever changes per install, so the app itself never needs rebuilding
 * when the server-side frontend changes), the current FCM registration token
 * pending being sent to that server, and a login token pending handoff to
 * the WebView right after SettingsActivity signs in natively.
 */
object Prefs {
    private const val PREFS_NAME = "workspace_prefs"
    private const val KEY_SERVER_URL = "server_url"
    private const val KEY_FCM_TOKEN = "fcm_token"
    private const val KEY_PENDING_LOGIN_TOKEN = "pending_login_token"
    private const val KEY_REMINDER_SCHEDULE = "reminder_schedule"
    private const val KEY_LAST_NOTIFIED_PREFIX = "last_notified_"
    private const val KEY_LOCAL_OCCURRENCE_PREFIX = "local_occurrence_"
    private const val KEY_EXACT_ALARM_ASKED = "exact_alarm_asked"

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

    /** "Disconnect from server" (the mobile Logout button): wipes everything
     *  remembered locally, back to a fresh install's state. */
    fun clearAll(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().clear().apply()
    }
}
