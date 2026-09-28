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

    @JavascriptInterface
    fun resetApp() {
        Handler(Looper.getMainLooper()).post {
            Prefs.clearAll(activity)
            activity.startActivity(Intent(activity, SettingsActivity::class.java))
            activity.finish()
        }
    }
}
