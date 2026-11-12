package com.workspaceapp.mobile

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * Sends the "Complete" actions taken from a notification to the server, without
 * the app being open.
 *
 * ReminderActionReceiver records the completion locally the instant the button is
 * tapped (so the nags stop even offline) and queues the server update in Prefs.
 * The frontend drains that queue whenever it is running, but that used to be the
 * only path - so a completion tapped on a closed app could sit unsent for as long
 * as the app stayed closed, even with a connection. This worker closes that gap:
 * it is enqueued as soon as an action is queued, network-constrained, so
 * WorkManager either sends it immediately or waits for the connection to return.
 *
 * It mirrors what app.js applyNotificationComplete() does, including re-reading
 * the item and skipping an action whose occurrence has already been replaced by a
 * later one - sending that blindly would complete the *next* occurrence of a
 * repeating reminder. The queue itself is drained atomically through Prefs, so
 * this and a foreground flush can never both send the same action.
 */
class ReminderActionSyncWorker(appContext: Context, params: WorkerParameters) :
    Worker(appContext, params) {

    private enum class Outcome {
        APPLIED,   // server accepted it (or it was already in that state)
        DROP,      // can never succeed (item/step gone, stale occurrence, bad body)
        RETRY,     // connection or server-side failure - put it back
        AUTH,      // session expired - stop and wait for the page to hand over a new one
    }

    override fun doWork(): Result {
        val context = applicationContext
        val token = Prefs.getAuthToken(context) ?: return Result.success()
        val serverUrl = Prefs.getServerUrl(context) ?: return Result.success()

        val actions = try {
            JSONArray(Prefs.takePendingReminderActions(context))
        } catch (e: Exception) {
            JSONArray()
        }
        if (actions.length() == 0) return Result.success()

        val remaining = JSONArray()
        var applied = false
        var retry = false
        var authFailed = false

        for (i in 0 until actions.length()) {
            val action = actions.optJSONObject(i) ?: continue
            if (action.optString("type") != "complete") continue // unknown action - drop it
            if (authFailed) {
                remaining.put(action)
                continue
            }
            when (sendComplete(context, serverUrl, token, action)) {
                Outcome.APPLIED -> applied = true
                Outcome.DROP -> Unit
                Outcome.RETRY -> { remaining.put(action); retry = true }
                Outcome.AUTH -> { remaining.put(action); authFailed = true }
            }
        }

        // Merge whatever did not get through back with anything queued meanwhile.
        if (remaining.length() > 0) {
            Prefs.restorePendingReminderActions(context, remaining.toString())
        }

        if (authFailed) {
            // Same as ReminderSyncWorker: quiet until the page supplies a fresh
            // token. The actions stay queued and the frontend will flush them too.
            Prefs.clearAuthToken(context)
            cancel(context)
            ReminderSyncWorker.cancel(context)
            return Result.success()
        }

        // A completed repeat moves the server on to its next occurrence; refresh
        // the local schedule so the phone's future alarms match.
        if (applied) ReminderSyncWorker.refreshSoon(context)

        return if (retry) retryOrGiveUp() else Result.success()
    }

    private fun sendComplete(
        context: Context,
        serverUrl: String,
        token: String,
        action: JSONObject,
    ): Outcome {
        val itemId = action.optString("item_id").trim().toIntOrNull() ?: return Outcome.DROP
        if (itemId <= 0) return Outcome.DROP
        val subtaskId = if (action.isNull("subtask_id")) {
            null
        } else {
            action.optString("subtask_id").trim().takeIf { it.isNotEmpty() }
        }
        val occurrence = action.optLong("occurrence", 0L)

        // If the server's schedule has already moved this reminder on (a repeating
        // task completed elsewhere, or its day passed while this was queued), the
        // queued occurrence is stale - completing now would hit the wrong one.
        if (occurrence > 0 && occurrenceIsStale(context, itemId, subtaskId, occurrence)) {
            return Outcome.DROP
        }

        // Read the current item first: a step completion has to send the whole
        // checklist, and either way "already done" should be a no-op, not a write.
        val item = fetchItem(serverUrl, token, itemId)
        return when (val got = item) {
            is ItemResult.Failed -> got.outcome
            is ItemResult.Found -> {
                val body = got.json
                if (subtaskId != null) {
                    val checklist = body.optJSONArray("checklist") ?: return Outcome.DROP
                    var found = false
                    var alreadyDone = false
                    val steps = JSONArray()
                    for (i in 0 until checklist.length()) {
                        val step = checklist.optJSONObject(i) ?: continue
                        val copy = JSONObject(step.toString())
                        if (copy.optString("id") == subtaskId) {
                            found = true
                            if (copy.optBoolean("done")) {
                                alreadyDone = true
                            } else {
                                copy.put("done", true)
                            }
                        }
                        steps.put(copy)
                    }
                    if (!found || alreadyDone) return Outcome.DROP
                    patchItem(serverUrl, token, itemId, JSONObject().put("checklist", steps))
                } else {
                    if (body.optBoolean("done")) return Outcome.DROP
                    patchItem(serverUrl, token, itemId, JSONObject().put("done", true))
                }
            }
        }
    }

    private sealed class ItemResult {
        class Found(val json: JSONObject) : ItemResult()
        class Failed(val outcome: Outcome) : ItemResult()
    }

    private fun fetchItem(serverUrl: String, token: String, itemId: Int): ItemResult {
        val request = Request.Builder()
            .url("$serverUrl/api/items/$itemId")
            .addHeader("Authorization", "Bearer $token")
            .get()
            .build()
        return try {
            client.newCall(request).execute().use { response ->
                when {
                    response.isSuccessful -> {
                        val body = response.body?.string()
                        val json = try {
                            JSONObject(body ?: return ItemResult.Failed(Outcome.RETRY))
                        } catch (e: Exception) {
                            return ItemResult.Failed(Outcome.RETRY)
                        }
                        ItemResult.Found(json)
                    }
                    response.code == 401 || response.code == 403 -> ItemResult.Failed(Outcome.AUTH)
                    response.code in 400..499 -> ItemResult.Failed(Outcome.DROP)
                    else -> ItemResult.Failed(Outcome.RETRY)
                }
            }
        } catch (e: Exception) {
            ItemResult.Failed(Outcome.RETRY)
        }
    }

    private fun patchItem(serverUrl: String, token: String, itemId: Int, body: JSONObject): Outcome {
        val request = Request.Builder()
            .url("$serverUrl/api/items/$itemId")
            .addHeader("Authorization", "Bearer $token")
            .patch(body.toString().toRequestBody(JSON))
            .build()
        return try {
            client.newCall(request).execute().use { response ->
                when {
                    response.isSuccessful -> Outcome.APPLIED
                    response.code == 401 || response.code == 403 -> Outcome.AUTH
                    response.code in 400..499 -> Outcome.DROP
                    else -> Outcome.RETRY
                }
            }
        } catch (e: Exception) {
            Outcome.RETRY
        }
    }

    /**
     * True when the saved schedule knows this item/step at a strictly later
     * occurrence than the queued action - the same "earliest occurrence is the
     * current one" rule the frontend uses. Unknown schedule means no opinion.
     */
    private fun occurrenceIsStale(
        context: Context,
        itemId: Int,
        subtaskId: String?,
        occurrence: Long,
    ): Boolean {
        val raw = Prefs.getReminderSchedule(context) ?: return false
        val entries = try {
            JSONArray(raw)
        } catch (e: Exception) {
            return false
        }
        var earliest = Long.MAX_VALUE
        for (i in 0 until entries.length()) {
            val entry = entries.optJSONObject(i) ?: continue
            if (entry.optInt("item_id", -1) != itemId) continue
            val entrySubtask = if (entry.isNull("subtask_id")) "" else entry.optString("subtask_id")
            if (entrySubtask != (subtaskId ?: "")) continue
            val fireAt = entry.optLong("fire_at", 0L)
            if (fireAt > 0 && fireAt < earliest) earliest = fireAt
        }
        return earliest != Long.MAX_VALUE && earliest > occurrence
    }

    /** Bounded retries, like ReminderSyncWorker: the periodic refresh is the safety
     *  net, and the page's own flush still runs whenever the app is opened. */
    private fun retryOrGiveUp(): Result =
        if (runAttemptCount < MAX_RETRIES) Result.retry() else Result.success()

    companion object {
        private const val UNIQUE_WORK = "reminder_actions_sync"
        private const val MAX_RETRIES = 3

        private val client = OkHttpClient()
        private val JSON = "application/json; charset=utf-8".toMediaType()

        /** Send any queued notification actions as soon as a connection exists. */
        fun syncSoon(context: Context) {
            if (Prefs.getAuthToken(context) == null) return
            val queued = Prefs.getPendingReminderActions(context)
            if (queued.isBlank() || queued.trim() == "[]") return
            val request = OneTimeWorkRequest.Builder(ReminderActionSyncWorker::class.java)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .build()
            // APPEND_OR_REPLACE, not KEEP: if a run is already in flight, an action
            // queued while it processed the earlier batch must still get its own
            // run afterwards. Each extra run is a cheap no-op once the queue is empty.
            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_WORK, ExistingWorkPolicy.APPEND_OR_REPLACE, request
            )
        }

        /** Logout/disconnect or a rejected session: stop trying until a new token. */
        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_WORK)
        }
    }
}
