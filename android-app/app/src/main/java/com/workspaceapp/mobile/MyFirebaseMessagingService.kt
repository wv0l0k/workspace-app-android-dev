package com.workspaceapp.mobile

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

private const val CHANNEL_ID = "reminders"

/**
 * The backend always sends data-only messages (see send_push() in main.py) -
 * deliberately never the "notification" style FCM payload, which Android only
 * shows automatically when the app is backgrounded and otherwise silently
 * hands to onMessageReceived anyway. Building the notification ourselves here,
 * every time, means it shows up the same way regardless of whether the app
 * was open, backgrounded, or not running at all.
 */
class MyFirebaseMessagingService : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        // Can't register with the server from here directly - that needs the
        // app's login token, which only exists inside MainActivity's WebView.
        // Just stash it; MainActivity picks this up next time it's opened.
        Prefs.setPendingFcmToken(this, token)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)

        val title = message.data["title"] ?: getString(R.string.app_name)
        val body = message.data["body"] ?: ""
        val itemId = message.data["item_id"]
        val workspaceId = message.data["workspace_id"]
        val kind = message.data["kind"]
        val subtaskId = message.data["subtask_id"]

        // A repeating "nag" reminder (see IMPORTANCE_NAG_SECONDS in main.py) sends a
        // fresh push every time it re-fires, not just once - using a stable ID here
        // means each re-fire updates the same notification in place instead of
        // stacking a growing pile of duplicates. Subtask reminders need the subtask
        // id folded in too, since several subtasks share the same parent item_id.
        val notificationKey = if (subtaskId != null) "$itemId:$subtaskId" else (itemId ?: "unknown")
        val notificationId = notificationKey.hashCode()

        val tapIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("item_id", itemId)
            putExtra("workspace_id", workspaceId)
            putExtra("kind", kind)
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            notificationId,
            tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notificationManager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Reminders", NotificationManager.IMPORTANCE_HIGH)
            notificationManager.createNotificationChannel(channel)
        }

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(body)
            // Collapsed, a notification only ever shows one line of body text
            // no matter how much we pass in - BigTextStyle is what lets
            // someone expand it (swipe down) to read the rest of a longer
            // preview, e.g. the back half of a shopping list.
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        notificationManager.notify(notificationId, notification)
    }
}
