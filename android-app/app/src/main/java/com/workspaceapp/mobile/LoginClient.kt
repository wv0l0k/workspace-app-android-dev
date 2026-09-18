package com.workspaceapp.mobile

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
 * Calls `POST /api/auth/login` directly. This is what lets SettingsActivity's
 * domain + username + password form log someone straight into the app,
 * instead of just saving a server address and leaving the web app's own
 * login screen to handle it inside the WebView - that login screen still
 * exists and still does its job later, whenever a session token expires
 * naturally (see app.js's own `logout()` and 401 handling), just not on
 * first connect anymore.
 */
object LoginClient {
    private val client = OkHttpClient()

    sealed class Result {
        data class Success(val token: String) : Result()
        data class Failure(val message: String) : Result()
    }

    /** Callback fires on a background thread - the caller is responsible for hopping back to the UI thread. */
    fun login(serverUrl: String, username: String, password: String, callback: (Result) -> Unit) {
        val json = JSONObject().put("username", username).put("password", password).toString()
        val body = json.toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url("$serverUrl/api/auth/login")
            .post(body)
            .build()
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                callback(Result.Failure("Couldn't reach that server - check the address and your connection."))
            }

            override fun onResponse(call: Call, response: Response) {
                response.use { resp ->
                    val text = resp.body?.string().orEmpty()
                    if (resp.isSuccessful) {
                        val token = runCatching { JSONObject(text).getString("token") }.getOrNull()
                        if (token != null) callback(Result.Success(token))
                        else callback(Result.Failure("Unexpected response from that server."))
                    } else {
                        val detail = runCatching { JSONObject(text).getString("detail") }.getOrNull()
                        callback(Result.Failure(detail ?: "Login failed (HTTP ${resp.code})."))
                    }
                }
            }
        })
    }
}
