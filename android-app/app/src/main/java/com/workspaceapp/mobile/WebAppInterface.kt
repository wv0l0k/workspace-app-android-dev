package com.workspaceapp.mobile

import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface

/**
 * Exposed to the web app as `window.AndroidApp` (see
 * MainActivity.addJavascriptInterface). Its mere presence is how the
 * frontend detects "I'm running inside the native app" at all - e.g. to
 * treat the Logout button as a full "disconnect from server" instead of a
 * normal logout (see app.js) - there's no separate "is this native" flag or
 * method; the object either exists or it doesn't.
 *
 * Methods here run on a background thread by default (that's how
 * @JavascriptInterface works), so anything touching the UI or starting an
 * Activity has to hop back to the main thread itself - see resetApp() below.
 * getPendingLoginToken() is the exception: reading a String out of
 * SharedPreferences is safe from any thread, and JS calls into this
 * synchronously anyway (it's not like evaluateJavascript, which is async).
 */
class WebAppInterface(private val activity: MainActivity) {

    /**
     * Called once by app.js right at boot. SettingsActivity signs in with the
     * API directly (see LoginClient) before the WebView ever loads, so by the
     * time the page's own script runs, a token may already be waiting here -
     * consumed exactly once, so a stale token can never leak into a later,
     * separate login.
     */
    @JavascriptInterface
    fun getPendingLoginToken(): String = Prefs.consumePendingLoginToken(activity) ?: ""

    /**
     * The frontend hands over the latest reminder schedule (JSON, from
     * GET /api/reminders/schedule) after boot and after anything changes. The
     * native side stores it and re-arms AlarmManager alarms, which is what lets
     * reminders fire later with no network at all. Called from a background
     * thread, which is fine - see ReminderScheduler.
     */
    @JavascriptInterface
    fun setReminderSchedule(json: String) {
        try {
            ReminderScheduler.saveAndSchedule(activity, json)
        } catch (e: Exception) {
            // A malformed payload must never take down the WebView bridge; the
            // previous schedule (if any) simply stays armed.
            android.util.Log.w("ReminderScheduler", "Could not apply reminder schedule", e)
        }
    }

    /**
     * Drains completions taken from notification actions. The page performs the
     * PATCH itself (it has the session and the item in memory); ReminderActionSyncWorker
     * drains the same queue in the background when the app is closed. Atomically
     * emptied here so two flushes can't send the same action twice; anything that
     * fails is put back with restorePendingReminderActions().
     */
    @JavascriptInterface
    fun takePendingReminderActions(): String = Prefs.takePendingReminderActions(activity)

    @JavascriptInterface
    fun restorePendingReminderActions(json: String) {
        try {
            Prefs.restorePendingReminderActions(activity, json)
        } catch (e: Exception) {
            android.util.Log.w("ReminderActions", "Could not restore pending reminder actions", e)
        }
    }

    /**
     * Mirrors the page's current session token into native storage. The background
     * refresh (ReminderSyncWorker) runs with no WebView, so it cannot read
     * localStorage - this is how it authenticates. An empty token means the page
     * logged out or its session ended, so the background work stops too.
     */
    @JavascriptInterface
    fun setSessionToken(token: String) {
        if (token.isBlank()) {
            Prefs.clearAuthToken(activity)
            ReminderSyncWorker.cancel(activity)
            ReminderActionSyncWorker.cancel(activity)
            return
        }
        Prefs.setAuthToken(activity, token)
        ReminderSyncWorker.ensurePeriodic(activity)
        // A token arriving is exactly when a completion queued while offline can
        // finally be sent.
        ReminderActionSyncWorker.syncSoon(activity)
    }

    @JavascriptInterface
    fun resetApp() {
        Handler(Looper.getMainLooper()).post {
            // Cancel armed alarms and background refreshes before wiping storage, or
            // a disconnected server's reminders would keep firing / being re-armed
            // after the account is gone.
            ReminderSyncWorker.cancel(activity)
            ReminderActionSyncWorker.cancel(activity)
            ReminderScheduler.clearAll(activity)
            Prefs.clearAll(activity)
            activity.startActivity(Intent(activity, SettingsActivity::class.java))
            activity.finish()
        }
    }
}
