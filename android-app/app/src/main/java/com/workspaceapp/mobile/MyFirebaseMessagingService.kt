package com.workspaceapp.mobile

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

/**
 * The backend always sends data-only messages (see send_push() in main.py) -
 * deliberately never the "notification" style FCM payload, which Android only
 * shows automatically when the app is backgrounded and otherwise silently
 * hands to onMessageReceived anyway. Building the notification ourselves (see
 * ReminderNotifications, shared with the offline AlarmManager path) means it
 * shows up the same way regardless of whether the app was open, backgrounded,
 * or not running at all.
 *
 * The data map is encrypted for this specific device (see PushCrypto): it arrives
 * as {"enc": "1", "payload": "..."} and is decrypted here with the key derived from
 * the login session mirrored by the WebView. Firebase therefore never sees a task
 * title, note, or item id.
 *
 * This path only works while the phone has a connection. The locally-scheduled
 * alarm path exists so the same reminder still fires offline; when both are
 * active, the shared "last notified" bookkeeping below prevents a duplicate.
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

        val data = decryptPayload(message.data)
        if (data.isEmpty()) return

        // A silent "the reminder schedule changed" ping (see request_sync_ping() in
        // main.py): refresh the local schedule in the background and show nothing.
        // This is what makes a task edited on another device reach a closed app.
        if (data["type"] == "sync") {
            ReminderSyncWorker.refreshSoon(this)
            // Also a good moment to retry a completion queued from a notification
            // while the phone had no connection.
            ReminderActionSyncWorker.syncSoon(this)
            return
        }

        val itemId = data["item_id"]
        // No title *and* no item id means this isn't a reminder at all - it's a
        // control message this build doesn't know (a newer silent ping type, say).
        // Showing it would just produce an empty notification titled "Workspace".
        if (data["title"].isNullOrBlank() && itemId.isNullOrBlank()) return

        val title = data["title"] ?: getString(R.string.app_name)
        val body = data["body"] ?: ""
        val workspaceId = data["workspace_id"]
        val kind = data["kind"]
        val subtaskId = data["subtask_id"]

        // Stable identity shared with the local-alarm path: item id, plus the
        // subtask id for a checklist step (several steps share the parent item id).
        val key = ReminderNotifications.keyFor(itemId, subtaskId)

        // If the phone's local alarm already handled this same occurrence, this push
        // is a duplicate - most commonly one FCM queued while the phone was offline
        // and delivered on reconnect. The occurrence time (not arrival time) is what
        // matters: a push for a genuinely later occurrence has a larger fire_at and
        // is still shown.
        val fireAt = data["fire_at"]?.toLongOrNull() ?: 0L
        if (fireAt > 0 && Prefs.getLastLocalOccurrence(this, key) >= fireAt) return

        // Completed from a notification (the server PATCH may still be queued), or
        // snoozed from one: stay silent, exactly like the local path.
        if (Prefs.isLocallyCompleted(this, key, fireAt)) return
        val now = System.currentTimeMillis()
        val snooze = Prefs.getSnooze(this, key)
        if (snooze.untilMillis > now && (fireAt <= 0 || snooze.occurrenceEpoch == fireAt)) return
        if (fireAt > 0 && snooze.occurrenceEpoch == fireAt && snooze.untilMillis > 0 && snooze.untilMillis <= now) {
            // The snooze has elapsed; this is the delivery that resumes the reminder.
            Prefs.expireSnooze(this, key)
        }

        // If a local alarm for this same occurrence just fired (or is about to),
        // don't alert twice. Both paths use the same notification id, so an update
        // would still land in the right place; this just suppresses the second sound.
        if (now - Prefs.getLastNotified(this, key) < ReminderNotifications.DEDUPE_WINDOW_MS) return
        Prefs.setLastNotified(this, key, now)

        ReminderNotifications.show(
            context = this,
            key = key,
            title = title,
            body = body,
            itemId = itemId,
            workspaceId = workspaceId,
            kind = kind,
            subtaskId = subtaskId,
            occurrenceEpoch = fireAt,
            nagSeconds = data["nag_seconds"]?.toIntOrNull()?.takeIf { it > 0 },
        )
    }

    /**
     * Returns the message's data map, decrypted when it is the encrypted envelope.
     *
     * An empty result means "show nothing": the device is logged out, or the payload
     * was encrypted with a key this install no longer has. In the latter case a
     * generic notification is still posted, so a reminder is not silently lost, but
     * it contains no content, no deep link, and no actions.
     */
    private fun decryptPayload(raw: Map<String, String>): Map<String, String> {
        if (!PushCrypto.isEncrypted(raw)) return raw

        val sessionToken = Prefs.getAuthToken(this) ?: return emptyMap()
        val decrypted = PushCrypto.decrypt(sessionToken, raw["payload"] ?: "")
        if (decrypted == null) {
            ReminderNotifications.showUndecryptable(this)
            return emptyMap()
        }
        return decrypted
    }
}
