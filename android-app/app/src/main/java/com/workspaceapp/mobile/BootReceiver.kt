package com.workspaceapp.mobile

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * AlarmManager alarms do not survive a reboot, so re-arm the saved local
 * reminder schedule when the phone starts up (or the app is updated).
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            "android.intent.action.QUICKBOOT_POWERON" -> {
                ReminderScheduler.rescheduleSaved(context)
                // The saved schedule may have aged while the phone was off. Restore
                // the periodic safety net and fetch a fresh copy once there's a
                // connection (no-op when nobody is signed in).
                ReminderSyncWorker.ensurePeriodic(context)
                ReminderSyncWorker.refreshSoon(context)
                // A Complete tapped just before the phone restarted may still be
                // queued; send it now that there may be a connection again.
                ReminderActionSyncWorker.syncSoon(context)
            }
        }
    }
}
