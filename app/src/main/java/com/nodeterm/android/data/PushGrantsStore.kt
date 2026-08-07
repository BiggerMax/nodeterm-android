package com.nodeterm.android.data

import android.content.Context
import java.util.UUID

/**
 * Device-scoped push grant, aligned with the host's `push-grants.ts` model: a phone that
 * reaches a host gets a signed, device-scoped grant so the host knows WHERE to push and WHAT the
 * phone is allowed to subscribe to. P1 keeps the local half of that contract: a stable device id,
 * the FCM registration token (when a Firebase project is wired), and the host key this device is
 * paired with.
 */
class PushGrantsStore(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences("push", Context.MODE_PRIVATE)

    val deviceId: String
        get() = prefs.getString(KEY_DEVICE_ID, null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString(KEY_DEVICE_ID, it).apply()
        }

    /** The FCM registration token (written by NodetermMessagingService.onNewToken). */
    var fcmToken: String?
        get() = prefs.getString("fcm_token", null)
        set(value) = prefs.edit().putString("fcm_token", value).apply()

    /** The host public key (base64) this device is paired with — the identity pushes target. */
    fun setPairedHost(hostPublicKeyB64: String) {
        prefs.edit().putString(KEY_HOST_PUB, hostPublicKeyB64).apply()
    }

    fun pairedHost(): String? = prefs.getString(KEY_HOST_PUB, null)

    fun clear() {
        prefs.edit().remove(KEY_HOST_PUB).apply()
    }

    private companion object {
        const val KEY_DEVICE_ID = "deviceId"
        const val KEY_HOST_PUB = "pairedHostPub"
    }
}
