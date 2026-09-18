package com.workspaceapp.mobile

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.firebase.messaging.FirebaseMessaging
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException

/**
 * The whole app, really: a WebView pointed at whatever server URL is saved in
 * Prefs. Nothing about the actual workspace UI lives here - that's the exact
 * same frontend the browser gets, unmodified. This class only adds the things
 * a browser tab can't do on its own: showing a real Android notification for
 * a push, telling the server which device to send those pushes to, actually
 * opening a file picker for the page's `<input type="file">` (WebView doesn't
 * do this on its own - see the WebChromeClient below), and exposing a small
 * `window.AndroidApp` bridge (see WebAppInterface) for the couple of things
 * that need native help, like fully disconnecting on Logout.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private val httpClient = OkHttpClient()

    // WebView's own file-chooser support needs a WebChromeClient - without one,
    // the page's <input type="file"> (used by the image-insert button) silently
    // does nothing at all when tapped, since there's nothing to show a picker.
    private var pendingFileChooserCallback: ValueCallback<Array<Uri>>? = null

    private val fileChooserLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val callback = pendingFileChooserCallback
        pendingFileChooserCallback = null
        val data = result.data
        if (callback == null) return@registerForActivityResult
        if (result.resultCode != Activity.RESULT_OK || data == null) {
            callback.onReceiveValue(null)
            return@registerForActivityResult
        }
        val clipData = data.clipData
        val uris = when {
            clipData != null -> Array(clipData.itemCount) { i -> clipData.getItemAt(i).uri }
            data.data != null -> arrayOf(data.data!!)
            else -> null
        }
        callback.onReceiveValue(uris)
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val serverUrl = Prefs.getServerUrl(this)
        if (serverUrl.isNullOrBlank()) {
            startActivity(Intent(this, SettingsActivity::class.java))
            finish()
            return
        }

        requestNotificationPermissionIfNeeded()

        webView = WebView(this)
        webView.settings.javaScriptEnabled = true
        // Off by default in WebView (unlike a real browser) - without this,
        // the app's own login (which lives in localStorage) can't persist,
        // and push-token registration below has nothing to read.
        webView.settings.domStorageEnabled = true
        // Without these, WebView can render the page as if it were a desktop
        // site and apply its own font-boosting heuristics on top - the same
        // CSS pixel size can then paint visibly differently across elements
        // (e.g. an input's placeholder vs. a button's label), even though
        // nothing in the stylesheet itself is inconsistent. This makes it
        // respect the page's own viewport meta tag and CSS sizes exactly,
        // the same way Chrome for Android would.
        webView.settings.useWideViewPort = true
        webView.settings.loadWithOverviewMode = true
        webView.settings.textZoom = 100
        // The frontend's own JS uses `typeof window.AndroidApp !== "undefined"`
        // to know it's running inside this app rather than a normal browser -
        // that's how it treats Logout as a full "disconnect" (see app.js) and
        // adopts a pending native login token at boot (see WebAppInterface).
        webView.addJavascriptInterface(WebAppInterface(this), "AndroidApp")
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                tryRegisterPushToken()
            }
        }
        webView.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                view: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                pendingFileChooserCallback?.onReceiveValue(null) // an unfinished previous request, if any - don't leak it
                pendingFileChooserCallback = filePathCallback
                // createIntent() already reads the <input>'s accept type (image/*
                // for the insert-image button) and single/multiple-selection mode,
                // so this opens exactly the picker the page asked for.
                val intent = fileChooserParams?.createIntent() ?: Intent(Intent.ACTION_GET_CONTENT).apply {
                    type = "image/*"
                    addCategory(Intent.CATEGORY_OPENABLE)
                }
                return try {
                    fileChooserLauncher.launch(intent)
                    true
                } catch (e: Exception) {
                    pendingFileChooserCallback = null
                    filePathCallback.onReceiveValue(null)
                    false
                }
            }
        }

        setContentView(webView)

        // Ask Firebase for the current token up front too, in case onNewToken
        // (in MyFirebaseMessagingService) hasn't fired yet on this install.
        FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
            if (task.isSuccessful) {
                task.result?.let { Prefs.setPendingFcmToken(this, it) }
                tryRegisterPushToken()
            }
        }

        loadDeepLinkOrHome(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        loadDeepLinkOrHome(intent)
    }

    override fun onResume() {
        super.onResume()
        // Covers the case where onNewToken fired while this Activity wasn't
        // running - pick up whatever's waiting in Prefs every time we come back.
        tryRegisterPushToken()
    }

    /** Opens straight to a specific item if we were launched from a notification tap. */
    private fun loadDeepLinkOrHome(intent: Intent?) {
        val serverUrl = Prefs.getServerUrl(this) ?: return
        val itemId = intent?.getStringExtra("item_id")
        val workspaceId = intent?.getStringExtra("workspace_id")
        val kind = intent?.getStringExtra("kind")
        if (!itemId.isNullOrBlank() && !workspaceId.isNullOrBlank() && !kind.isNullOrBlank()) {
            webView.loadUrl("$serverUrl/#/w/$workspaceId/$kind/i/$itemId")
        } else if (webView.url.isNullOrBlank()) {
            webView.loadUrl(serverUrl)
        }
    }

    /**
     * Registration needs two things that live in two different places: the FCM
     * token (native side, from Firebase) and the app's own login token (JS
     * side, in the WebView's localStorage - the exact same one the browser
     * version of this app uses). This pulls the second one out via
     * evaluateJavascript and, if we're actually logged in, sends both to the
     * server together. Safe to call often - it's a no-op whenever either
     * piece isn't available yet (not logged in, or no FCM token yet).
     */
    private fun tryRegisterPushToken() {
        val fcmToken = Prefs.getPendingFcmToken(this) ?: return
        val serverUrl = Prefs.getServerUrl(this) ?: return
        if (!::webView.isInitialized) return
        webView.evaluateJavascript("(function(){ return window.localStorage.getItem('token'); })()") { result ->
            // evaluateJavascript returns a JSON-encoded string - "abc123", or
            // the literal text null with no quotes if the key isn't set.
            val authToken = result?.trim('"')?.takeIf { it.isNotBlank() && it != "null" } ?: return@evaluateJavascript
            sendTokenToServer(serverUrl, authToken, fcmToken)
        }
    }

    private fun sendTokenToServer(serverUrl: String, authToken: String, fcmToken: String) {
        val json = JSONObject().put("token", fcmToken).toString()
        val body = json.toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url("$serverUrl/api/push/register")
            .addHeader("Authorization", "Bearer $authToken")
            .post(body)
            .build()
        httpClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                // Best-effort - onResume will just try again next time the app
                // is opened, no need to retry immediately or surface an error.
            }
            override fun onResponse(call: Call, response: Response) {
                response.close()
            }
        })
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return // permission didn't exist before Android 13
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        // Standard "let the WebView go back through its own history first"
        // pattern - only exits the app once there's nowhere left to go back to.
        if (::webView.isInitialized && webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }
}
