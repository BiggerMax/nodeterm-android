package com.nodeterm.android.core.remote

import com.nodeterm.android.core.e2ee.E2ee
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.Base64

/**
 * The host's v0.2.37 "Settings → Phone" pairing — the one-shot LAN pairing the QR encodes.
 * Mirrors the host's `pairing` service byte-for-byte:
 *
 *  - the QR payload is a raw JSON object (`buildPairingPayload`): SSH params for the legacy
 *    LAN path PLUS an optional `relay` block (hostId / hostPublicKeyB64 / relayEndpoint) when
 *    "Remote access from your phone" is enabled;
 *  - the phone POSTs `{epk, box}` to `http://<host>:<pairPort>/pair` where `box` is an E2EE
 *    box over `{token, publicKey, deviceName, deviceId}` under the ECDH shared key derived
 *    from the host's relay public key (`hostKey`);
 *  - the host replies `{box}` (same shared key) containing
 *    `{ok, deviceId, agentToken, relay{...}, relayDeviceToken}` — `relayDeviceToken` is the
 *    credential for the standing relay host connection (`wss://relay…?token=…`).
 *
 * The Android client uses the RELAY path only (no SSH): it installs an ed25519 key with the
 * host (the pair server requires one) and connects through the relay with the device token.
 */
@Serializable
data class HostPairingPayload(
    val v: Int = 1,
    val host: String = "",
    val port: Int = 22,
    val user: String = "",
    val token: String = "",
    val pairPort: Int = 0,
    val nodeterm: Boolean = true,
    val name: String = "",
    val hostKey: String? = null,
    val relay: RelayBlock? = null
) {
    @Serializable
    data class RelayBlock(
        val hostId: String = "",
        val hostPublicKeyB64: String = "",
        val relayEndpoint: String = ""
    )
}

/** The unsealed `/pair` response body (mirrors the host's `responseObj`). */
@Serializable
data class PairResult(
    val ok: Boolean = false,
    val deviceId: String = "",
    val agentToken: String = "",
    val relay: HostPairingPayload.RelayBlock? = null,
    val relayDeviceToken: String? = null
)

object HostPairingCodec {
    val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    /** Decode the QR payload (raw JSON, NOT a `nodeterm://pair?code=` offer). Null on any bad input. */
    fun decodePayload(text: String): HostPairingPayload? = try {
        json.decodeFromString(HostPairingPayload.serializer(), text).takeIf {
            it.host.isNotBlank() && it.pairPort > 0 && it.token.isNotBlank()
        }
    } catch (_: Exception) {
        null
    }

    /**
     * The `ssh-ed25519 <base64(blob)> <comment>` authorized_keys line the pair server validates
     * (blob = u32len(name) ‖ name ‖ u32len(32) ‖ 32-byte public key). Mirrors
     * `isValidEd25519PublicKey` + the key-blob layout sshd expects.
     */
    fun sshEd25519Line(publicKey: ByteArray, comment: String): String {
        val name = "ssh-ed25519".toByteArray(Charsets.US_ASCII)
        val blob = u32(name.size) + name + u32(publicKey.size) + publicKey
        return "ssh-ed25519 ${Base64.getEncoder().encodeToString(blob)} $comment"
    }

    private fun u32(n: Int): ByteArray = byteArrayOf(
        (n ushr 24).toByte(), (n ushr 16).toByte(), (n ushr 8).toByte(), n.toByte()
    )
}

/**
 * One sealed `/pair` exchange: derives the ECDH shared key from the host's relay public key
 * and an ephemeral client keypair, holds it for the request seal and the response open.
 */
class SealedPairSession(ephemeral: E2ee.KeyPair, hostKeyB64: String) {
    private val shared = E2ee.deriveSharedKey(hostKeyB64, ephemeral.secretKey)
    private val epkB64 = E2ee.publicKeyToB64(ephemeral.publicKey)

    /** Wire body: `{"epk":…, "box": base64(nonce‖box)}`. */
    fun buildRequest(payload: HostPairingPayload, sshPublicKeyLine: String, deviceName: String, deviceId: String): String {
        val body = buildJsonObject {
            put("token", payload.token)
            put("publicKey", sshPublicKeyLine)
            put("deviceName", deviceName)
            put("deviceId", deviceId)
        }.toString()
        val box = E2ee.encrypt(body.toByteArray(Charsets.UTF_8), shared)
        return buildJsonObject {
            put("epk", epkB64)
            put("box", Base64.getEncoder().encodeToString(box))
        }.toString()
    }

    /** Open the sealed response (`{"box":…}`). Null on a failed MAC / malformed input. */
    fun openResponse(boxB64: String): PairResult? = try {
        val box = Base64.getDecoder().decode(boxB64)
        val plain = E2ee.decrypt(box, shared) ?: return null
        HostPairingCodec.json.decodeFromString(PairResult.serializer(), String(plain, Charsets.UTF_8))
    } catch (_: Exception) {
        null
    }
}
