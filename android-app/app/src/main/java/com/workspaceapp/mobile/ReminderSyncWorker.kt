package com.workspaceapp.mobile

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Keeps the phone's locally-scheduled reminders current while the app is closed.
 *
 * The offline half of reminders (see ReminderScheduler) only knows what the
 * frontend last handed it. Without this, a task created or edited on another
 * device - or a repeat that moved on - would not reach this phone until the app
 * was opened. WorkManager runs this worker on its own:
 *
 *  - a one-off "refresh soon" after a silent FCM ping (`{"type": "sync"}`) or a
 *    reboot, which is the fast path, and
 *  - a periodic ~3-hour safety net for when a ping is dropped (FCM only works
 *    with a connection, and Doze may defer either of them).
 *
 * Both are constrained to "has a network", so they never wake the radio just to
 * fail. The request is the same plain GET the frontend uses; recurrence is
 * expanded server-side, so this class stores an already-flat schedule and never
 * interprets repeat rules.
 *
 * Retries are deliberately bounded (see retryOrGiveUp): 5xx/IO errors use
 * WorkManager's exponential backoff a few times, then give up until the next
 * periodic run or ping, so a wrong server address cannot keep waking the phone
 * forever. An expired session stops the work entirely until the app is opened
 * and the page hands over a fresh token.
 */
class ReminderSyncWorker(appContext: Context, params: WorkerParameters) :
    Worker(appContext, params) {

    override fun doWork(): Result {
        val context = applicationContext
        val token = Prefs.getAuthToken(context) ?: return Result.success()
        val serverUrl = Prefs.getServerUrl(context) ?: return Result.success()

        val request = Request.Builder()
            .url("$serverUrl/api/reminders/schedule?horizon_days=30")
            .addHeader("Authorization", "Bearer $token")
            .get()
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                when {
                    response.isSuccessful -> {
                        val body = response.body?.string()
                        if (body.isNullOrBlank()) {
                            retryOrGiveUp()
                        } else if (Prefs.getAuthToken(context) != token) {
                            // The person disconnected while this request was in
                            // flight; don't re-arm alarms for a server that is gone.
                            Result.success()
                        } else {
                            ReminderScheduler.saveAndSchedule(context, body)
                            Result.success()
                        }
                    }
                    // The session expired or was revoked. Try again once the app is
                    // open and the page has a fresh token; meanwhile stay quiet.
                    response.code == 401 || response.code == 403 -> {
                        Prefs.clearAuthToken(context)
                        cancel(context)
                        Result.success()
                    }
                    // Any other 4xx is about this request, not the connection -
                    // retrying would just repeat the same answer.
                    response.code in 400..499 -> Result.success()
                    else -> retryOrGiveUp()
                }
            }
        } catch (e: Exception) {
            // Offline despite the constraint, DNS failure, server down, timeout...
            retryOrGiveUp()
        }
    }

    /**
     * A bounded retry: WorkManager's backoff, but not forever. The periodic refresh
     * is the real safety net, so after a few failed attempts there is no value in
     * keeping this particular request alive - and a wrong server address must not
     * turn into a wake-up every few hours forever.
     */
    private fun retryOrGiveUp(): Result =
        if (runAttemptCount < MAX_RETRIES) Result.retry() else Result.success()

    companion object {
        private const val UNIQUE_PERIODIC = "reminder_sync_periodic"
        private const val UNIQUE_ONE_OFF = "reminder_sync_now"

        /** Attempts (not counting the first run) for one invocation of this worker. */
        private const val MAX_RETRIES = 3

        /** The safety-net interval. The FCM ping is what makes a change show up
         *  promptly; this only has to catch the pings that never arrived. */
        private const val REFRESH_INTERVAL_HOURS = 3L

        private val client = OkHttpClient()

        private fun networkConstraints(): Constraints =
            Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

        /** Fetch a fresh schedule in the background, soon, and show nothing. */
        fun refreshSoon(context: Context) {
            if (Prefs.getAuthToken(context) == null) return
            val request = OneTimeWorkRequest.Builder(ReminderSyncWorker::class.java)
                .setConstraints(networkConstraints())
                .build()
            // KEEP, not REPLACE: a refresh already running (or queued) by an earlier
            // ping is fetching the same current state, so a second one is wasted work.
            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_ONE_OFF, ExistingWorkPolicy.KEEP, request
            )
        }

        /** The periodic fallback. Safe to call often - existing work is kept. */
        fun ensurePeriodic(context: Context) {
            if (Prefs.getAuthToken(context) == null) return
            val request = PeriodicWorkRequest.Builder(
                ReminderSyncWorker::class.java, REFRESH_INTERVAL_HOURS, TimeUnit.HOURS
            )
                .setConstraints(networkConstraints())
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request
            )
        }

        /** Used on logout/disconnect and when the server rejects the token. */
        fun cancel(context: Context) {
            val workManager = WorkManager.getInstance(context)
            workManager.cancelUniqueWork(UNIQUE_ONE_OFF)
            workManager.cancelUniqueWork(UNIQUE_PERIODIC)
        }
    }
}
