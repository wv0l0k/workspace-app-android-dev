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

    /** "Disconnect from server" (the mobile Logout button): wipes everything
     *  remembered locally, back to a fresh install's state. */
    fun clearAll(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().clear().apply()
    }
}
