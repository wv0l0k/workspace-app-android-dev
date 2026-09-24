package com.workspaceapp.mobile

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
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
    private lateinit var offlineView: View
    private val httpClient = OkHttpClient()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var navigationTimeoutRunnable: Runnable? = null

    // How long to wait for a navigation to actually finish (onPageFinished) or fail
    // outright (onReceivedError) before giving up on it ourselves. Set well above
    // sw.js's own NAV_TIMEOUT_MS (3s) - once a service worker is controlling the page,
    // it always resolves a navigation within that window, one way or another, so
    // anything still hanging well past it is stuck for some other reason (a WebView-
    // level glitch, not a slow connection) rather than something worth waiting longer
    // for. Better to land on the offline screen - and its working Retry button - than
    // stay on a blank WebView indefinitely.
    private val NAVIGATION_TIMEOUT_MS = 8000L

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

        setContentView(R.layout.activity_main)
        val root = findViewById<FrameLayout>(R.id.mainRoot)
        offlineView = findViewById(R.id.offlineView)
        findViewById<TextView>(R.id.offlineMessage).text = getString(R.string.offline_message, serverUrl)
        findViewById<Button>(R.id.offlineRetryButton).setOnClickListener {
            hideOffline()
            Prefs.getServerUrl(this)?.let { loadWithTimeout(it) }
        }

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
        // WebView's own default surface is plain white until real content actually
        // paints on top of it - matching the app's own background here means any gap
        // between "WebView says the navigation finished" and "the page has actually
        // drawn something" reads as this app's normal background, not a stray flash
        // of blank white.
        webView.setBackgroundColor(ContextCompat.getColor(this, R.color.bg_app))
        webView.visibility = View.GONE // shown once something has actually loaded - see onPageFinished/showOffline below
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                cancelNavigationTimeout()
                // Reaching here at all (as opposed to onReceivedError above) means this
                // navigation succeeded, so whatever offline screen might be showing -
                // from an earlier failed attempt - no longer applies.
                if (url != "about:blank") hideOffline()
                tryRegisterPushToken()
            }

            // Fires when a navigation fails outright at the network level - no
            // connection, DNS failure, and so on (what a phone shows as
            // "net::ERR_FAILED"). This is different from a page that loaded but is
            // now offline mid-session - that's handled entirely in JS, by the small
            // "You're offline" banner the page shows itself once it has something
            // cached to fall back on (see frontend/js/app.js). This callback only
            // fires when there was nothing to fall back on at all: nothing has ever
            // loaded successfully in this WebView, so there's no cached copy of the
            // app's own page for it to show instead. WebView still renders its own
            // plain, browser-style error page for this by default regardless of what
            // this callback does - loadUrl("about:blank") below, plus showing our own
            // matching-styled screen on top, is what actually replaces it.
            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                super.onReceivedError(view, request, error)
                if (request?.isForMainFrame == true) {
                    cancelNavigationTimeout()
                    view?.loadUrl("about:blank")
                    showOffline()
                }
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

        root.addView(webView, 0, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

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
            loadWithTimeout("$serverUrl/#/w/$workspaceId/$kind/i/$itemId")
        } else if (webView.url.isNullOrBlank()) {
            loadWithTimeout(serverUrl)
        }
    }

    /**
     * Starts a navigation and arms the timeout above for it. Every top-level
     * webView.loadUrl() in this Activity goes through here (deep links, the plain
     * server URL, and the Retry button) rather than calling loadUrl() directly, so
     * none of them can silently hang with nothing shown if a navigation just never
     * resolves.
     */
    private fun loadWithTimeout(url: String) {
        cancelNavigationTimeout()
        val runnable = Runnable {
            navigationTimeoutRunnable = null
            if (webView.visibility != View.VISIBLE) showOffline()
        }
        navigationTimeoutRunnable = runnable
        mainHandler.postDelayed(runnable, NAVIGATION_TIMEOUT_MS)
        webView.loadUrl(url)
    }

    private fun cancelNavigationTimeout() {
        navigationTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        navigationTimeoutRunnable = null
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

    private fun showOffline() {
        webView.visibility = View.GONE
        offlineView.visibility = View.VISIBLE
    }

    private fun hideOffline() {
        offlineView.visibility = View.GONE
        webView.visibility = View.VISIBLE
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
