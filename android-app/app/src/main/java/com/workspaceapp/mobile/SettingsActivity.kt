package com.workspaceapp.mobile

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * The "connect to your server" screen - shown on first launch (nothing saved
 * yet), and again any time the app's Logout button is used, since on mobile
 * that button means "fully disconnect" rather than a normal logout (see
 * WebAppInterface.resetApp() and app.js). Unlike a bare "type a URL" screen,
 * this one signs you in directly: domain, username, and password all at
 * once, matching the web login card's look so the two don't feel like two
 * different apps.
 */
class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        val serverUrlInput = findViewById<EditText>(R.id.serverUrlInput)
        val usernameInput = findViewById<EditText>(R.id.usernameInput)
        val passwordInput = findViewById<EditText>(R.id.passwordInput)
        val saveButton = findViewById<Button>(R.id.saveButton)
        val errorText = findViewById<TextView>(R.id.errorText)

        // Only has anything to prefill if this screen is shown without a full
        // reset having happened first (a reset clears this too) - mostly
        // relevant if someone backs out of this screen without finishing and
        // comes back to it later.
        Prefs.getServerUrl(this)?.let { serverUrlInput.setText(it) }

        fun showError(message: String) {
            errorText.text = message
            errorText.visibility = View.VISIBLE
        }

        fun setLoading(loading: Boolean) {
            saveButton.isEnabled = !loading
            saveButton.text = getString(if (loading) R.string.settings_connecting else R.string.settings_save)
        }

        saveButton.setOnClickListener {
            errorText.visibility = View.GONE

            var domain = serverUrlInput.text.toString().trim()
            val username = usernameInput.text.toString().trim()
            val password = passwordInput.text.toString()

            if (domain.isEmpty() || username.isEmpty() || password.isEmpty()) {
                showError("Fill in your server address, username, and password.")
                return@setOnClickListener
            }
            if (!domain.startsWith("http://") && !domain.startsWith("https://")) {
                domain = "https://$domain"
            }
            domain = domain.trimEnd('/')

            setLoading(true)
            LoginClient.login(domain, username, password) { result ->
                runOnUiThread {
                    setLoading(false)
                    when (result) {
                        is LoginClient.Result.Success -> {
                            // The token goes to Prefs, not straight into the WebView -
                            // MainActivity hasn't created one yet. WebAppInterface hands
                            // it to the page itself the moment it boots (see app.js's
                            // resolveInitialToken()).
                            Prefs.setServerUrl(this, domain)
                            Prefs.setPendingLoginToken(this, result.token)
                            startActivity(Intent(this, MainActivity::class.java))
                            finish()
                        }
                        is LoginClient.Result.Failure -> showError(result.message)
                    }
                }
            }
        }
    }
}
