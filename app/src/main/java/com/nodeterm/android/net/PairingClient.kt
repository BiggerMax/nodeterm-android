package com.nodeterm.android.net

import android.util.Log
import com.nodeterm.android.core.e2ee.E2ee
import com.nodeterm.android.core.e2ee.SshKeys
import com.nodeterm.android.core.remote.HostPairingCodec
import com.nodeterm.android.core.remote.HostPairingPayload
import com.nodeterm.android.core.remote.PairResult
import com.nodeterm.android.core.remote.SealedPairSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * The v0.2.37 one-shot pairing client: POSTs a sealed `{epk, box}` to the host's transient
 * pair server (`http://<host>:<pairPort>/pair`) and opens the sealed response. The box is an
 * E2EE box over `{token, publicKey, deviceName, deviceId}` under the ECDH shared key derived
 * from the host's relay public key, so the single-use pairing token never crosses the LAN in
 * plaintext (mirrors the iOS app's sealed exchange).
 */
class PairingClient(
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()
) {

    /** Sealed `POST /pair`. Throws [IllegalStateException] with a human-readable message on failure. */
    suspend fun pair(
        payload: HostPairingPayload,
        deviceName: String,
        deviceId: String,
        sshKeys: SshKeys.KeyPair
    ): PairResult = withContext(Dispatchers.IO) {
        val hostKey = payload.hostKey
            ?: throw IllegalStateException("The pairing code carries no host key.")
        val session = SealedPairSession(E2ee.generateKeyPair(), hostKey)
        val sshLine = HostPairingCodec.sshEd25519Line(sshKeys.publicKey, "android")
        val requestJson = session.buildRequest(payload, sshLine, deviceName, deviceId)
        val url = "http://${payload.host}:${payload.pairPort}/pair"
        Log.i(TAG, "pair POST $url device=$deviceId")
        val request = Request.Builder()
            .url(url)
            .post(requestJson.toRequestBody(JSON))
            .build()
        http.newCall(request).execute().use { response ->
            val bodyText = response.body?.string().orEmpty()
            Log.i(TAG, "pair response HTTP ${response.code} len=${bodyText.length}")
            if (!response.isSuccessful) {
                throw IllegalStateException(
                    "Pairing rejected by host (HTTP ${response.code}${errorSuffix(bodyText)})."
                )
            }
            val boxB64 = try {
                HostPairingCodec.json.parseToJsonElement(bodyText).jsonObject["box"]?.jsonPrimitive?.content
            } catch (_: Exception) {
                null
            }
            val result = boxB64?.let { session.openResponse(it) }
                ?: throw IllegalStateException("Host returned an unreadable pairing response.")
            if (!result.ok) throw IllegalStateException("Host rejected the pairing request.")
            Log.i(
                TAG,
                "pair OK deviceId=${result.deviceId} relayToken=${result.relayDeviceToken != null} " +
                    "relayEndpoint=${result.relay?.relayEndpoint}"
            )
            result
        }
    }

    private fun errorSuffix(body: String): String {
        val clean = body.takeIf { it.isNotBlank() && it.length < 120 } ?: return ""
        return " — $clean"
    }

    private companion object {
        val JSON: okhttp3.MediaType = "application/json; charset=utf-8".toMediaType()
        const val TAG = "NodetermPair"
    }
}
