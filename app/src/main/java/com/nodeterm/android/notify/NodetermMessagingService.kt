package com.nodeterm.android.notify

import com.google.firebase.FirebaseApp
import com.nodeterm.android.R
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

/**
 * The host-side push ENTRY POINT (spec §4.6). The desktop host pushes RUNNING / NEEDS YOU / done
 * events to paired phones (push-notify.ts); this service receives them and renders local
 * notifications.
 *
 * Delivery requires a Firebase project: add the `google-services` Gradle plugin and a
 * `google-services.json` (the host's push-grant model then carries this device's registration
 * token). WITHOUT one, Firebase is never initialized and this service simply never fires — the
 * app keeps working over the relay, where the same events arrive live in the inbox.
 *
 * Payload convention (host push-grants / push-notify): a `notification` block (title/body) and/or
 * data keys `nodeId`, `kind` (`needsYou` | `done` | `running`), `title`, `body`, `pendingId`.
 */
class NodetermMessagingService : FirebaseMessagingService() {

    override fun onMessageReceived(message: RemoteMessage) {
        // Without a google-services.json the FirebaseApp is never initialized; guard so an
        // unexpected wakeup degrades to a no-op instead of an IllegalStateException.
        if (FirebaseApp.getApps(this).isEmpty()) return
        val data = message.data
        val title = message.notification?.title
            ?: data["title"]
            ?: if (data["kind"] == "done") getString(R.string.notification_completed) else getString(R.string.notification_needs_you)
        val body = message.notification?.body
            ?: data["body"]
            ?: getString(R.string.notification_attention)
        val nodeId = data["nodeId"]
        val id = (data["eventId"] ?: nodeId ?: title).hashCode()
        NotificationHelper.show(applicationContext, title, body, nodeId, id)
    }

    override fun onNewToken(token: String) {
        // The host's push-grant model registers this device token (push-grants.ts / push-notify.ts
        // resolveTarget). P1: persist locally so a future registration can read it back.
        getSharedPreferences("push", MODE_PRIVATE).edit().putString("fcm_token", token).apply()
    }
}
