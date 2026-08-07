package com.nodeterm.android.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.nodeterm.android.core.e2ee.E2ee
import com.nodeterm.android.core.e2ee.SshKeys
import com.nodeterm.android.core.model.PairingOffer
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Persistent session state — TWO transport shapes, mutually exclusive (unpair clears both):
 *  - RELAY: the decoded pairing offer (relay endpoint + pinned host pubkey) + the client's NaCl
 *    keypair, the SECRET key encrypted at rest with a Keystore-backed AES key;
 *  - LAN: direct SSH credentials — host/port/user + the ed25519 secret key the pairing installed
 *    into ~/.ssh/authorized_keys, plus the SSH host-key fingerprint (TOFU pin) if one was seen.
 * Both keep a flag once the human has confirmed the SAS out-of-band (relay only; LAN skips SAS).
 */
class SessionStore(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences("session", Context.MODE_PRIVATE)

    private val keyAlias = "nodeterm_client_box"

    data class Stored(
        val offer: PairingOffer,
        val keys: E2ee.KeyPair,
        val sasConfirmed: Boolean
    )

    /** LAN / SSH direct-connection credentials (free tier — no relay). */
    data class LanSession(
        val host: String,
        val port: Int,
        val user: String,
        /** The ed25519 keypair installed into the host's ~/.ssh/authorized_keys at pairing. */
        val sshKey: SshKeys.KeyPair,
        /** SHA-256 fingerprint of the host's SSH host key, once verified (TOFU pin). */
        val hostKeyFingerprint: String? = null
    )

    fun load(): Stored? {
        val relay = prefs.getString(KEY_RELAY, null) ?: return null
        val token = prefs.getString(KEY_TOKEN, null) ?: return null
        val hostPub = prefs.getString(KEY_HOST_PUB, null) ?: return null
        val clientPub = prefs.getString(KEY_CLIENT_PUB, null) ?: return null
        val clientSecEnc = prefs.getString(KEY_CLIENT_SEC_ENC, null) ?: return null
        val secret = decrypt(clientSecEnc) ?: return null
        return Stored(
            offer = PairingOffer(relay, token, hostPub),
            keys = E2ee.KeyPair(Base64.decode(clientPub, Base64.NO_WRAP), secret),
            sasConfirmed = prefs.getBoolean(KEY_SAS_CONFIRMED, false)
        )
    }

    /** Persist the offer + a fresh keypair (or the existing one), resetting the SAS confirmation. */
    fun save(offer: PairingOffer, keys: E2ee.KeyPair) {
        prefs.edit()
            .putString(KEY_RELAY, offer.relayEndpoint)
            .putString(KEY_TOKEN, offer.pairingToken)
            .putString(KEY_HOST_PUB, offer.hostPublicKeyB64)
            .putString(KEY_CLIENT_PUB, Base64.encodeToString(keys.publicKey, Base64.NO_WRAP))
            .putString(KEY_CLIENT_SEC_ENC, encrypt(keys.secretKey))
            .putBoolean(KEY_SAS_CONFIRMED, false)
            .apply()
    }

    /** Load the persisted LAN / SSH session (null when paired through the relay instead). */
    fun loadLan(): LanSession? {
        val host = prefs.getString(KEY_LAN_HOST, null) ?: return null
        val user = prefs.getString(KEY_LAN_USER, null) ?: return null
        val pub = prefs.getString(KEY_LAN_SSH_PUB, null) ?: return null
        val secEnc = prefs.getString(KEY_LAN_SSH_SEC, null) ?: return null
        val secret = decrypt(secEnc) ?: return null
        return LanSession(
            host = host,
            port = prefs.getInt(KEY_LAN_PORT, 22),
            user = user,
            sshKey = SshKeys.KeyPair(Base64.decode(pub, Base64.NO_WRAP), secret),
            hostKeyFingerprint = prefs.getString(KEY_LAN_HOST_KEY_FP, null)
        )
    }

    /**
     * Persist the LAN / SSH session (ed25519 secret encrypted at rest). Deliberately does NOT
     * touch `sasConfirmed` — that flag belongs to the relay shape, and the LAN shape is always
     * treated as confirmed via `lanSession != null` (no SAS on key auth), so writing it here
     * would create a fragile ordering dependency with [save].
     */
    fun saveLan(session: LanSession) {
        prefs.edit()
            .putString(KEY_LAN_HOST, session.host)
            .putInt(KEY_LAN_PORT, session.port)
            .putString(KEY_LAN_USER, session.user)
            .putString(KEY_LAN_SSH_PUB, Base64.encodeToString(session.sshKey.publicKey, Base64.NO_WRAP))
            .putString(KEY_LAN_SSH_SEC, encrypt(session.sshKey.secretKey))
            .apply()
    }

    /** Pin the SSH host key fingerprint after the first (trusted) connection (TOFU). */
    fun saveLanHostKeyFingerprint(fingerprint: String) {
        prefs.edit().putString(KEY_LAN_HOST_KEY_FP, fingerprint).apply()
    }

    /**
     * Whether the direct LAN/SSH transport should be preferred over the relay on restore. Set
     * once a LAN session actually works (free-tier hosts grant a relay token but never join the
     * relay room, so the relay path times out — LAN restore skips that stall entirely).
     */
    fun prefersLan(): Boolean = prefs.getBoolean(KEY_PREFER_LAN, false)

    fun setPreferLan(prefer: Boolean) {
        prefs.edit().putBoolean(KEY_PREFER_LAN, prefer).apply()
    }

    fun markSasConfirmed() {
        prefs.edit().putBoolean(KEY_SAS_CONFIRMED, true).apply()
    }

    /** Stable device id presented to the host during pairing (survives restarts; cleared on unpair). */
    fun getOrCreateDeviceId(): String {
        prefs.getString(KEY_DEVICE_ID, null)?.let { return it }
        val id = java.util.UUID.randomUUID().toString()
        prefs.edit().putString(KEY_DEVICE_ID, id).apply()
        return id
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    // ---- Keystore-backed AES-GCM encryption of the NaCl secret key ---------------------------

    private fun getOrCreateKey(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (ks.getKey(keyAlias, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        gen.init(
            KeyGenParameterSpec.Builder(keyAlias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return gen.generateKey()
    }

    private fun encrypt(plain: ByteArray): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val out = cipher.doFinal(plain)
        // IV ‖ ciphertext
        return Base64.encodeToString(cipher.iv + out, Base64.NO_WRAP)
    }

    private fun decrypt(encoded: String): ByteArray? = try {
        val blob = Base64.decode(encoded, Base64.NO_WRAP)
        val iv = blob.copyOfRange(0, 12)
        val ct = blob.copyOfRange(12, blob.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, iv))
        cipher.doFinal(ct)
    } catch (_: Exception) {
        // Keystore reset / key invalidated — the secret is gone; treat as no session.
        null
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val KEY_RELAY = "offer.relayEndpoint"
        const val KEY_TOKEN = "offer.pairingToken"
        const val KEY_HOST_PUB = "offer.hostPublicKeyB64"
        const val KEY_CLIENT_PUB = "keys.clientPublicKey"
        const val KEY_CLIENT_SEC_ENC = "keys.clientSecretEncrypted"
        const val KEY_SAS_CONFIRMED = "sasConfirmed"
        const val KEY_DEVICE_ID = "deviceId"

        // LAN / SSH direct session
        const val KEY_LAN_HOST = "lan.host"
        const val KEY_LAN_PORT = "lan.port"
        const val KEY_LAN_USER = "lan.user"
        const val KEY_LAN_SSH_PUB = "lan.sshPublicKey"
        const val KEY_LAN_SSH_SEC = "lan.sshSecretEncrypted"
        const val KEY_LAN_HOST_KEY_FP = "lan.hostKeyFingerprint"
        const val KEY_PREFER_LAN = "lan.prefer"
    }
}
