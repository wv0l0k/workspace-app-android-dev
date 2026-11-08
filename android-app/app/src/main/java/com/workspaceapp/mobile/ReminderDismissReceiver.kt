package com.workspaceapp.mobile

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Runs when the user swipes a locally-scheduled reminder notification away.
 * Cancels that reminder's pending nag alarm so an ignored-but-dismissed
 * reminder doesn't keep waking the phone until the next sync.
 */
class ReminderDismissReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        intent.getStringExtra(ReminderScheduler.EXTRA_KEY)?.let {
            ReminderScheduler.cancel(context, it)
        }
    }
}
