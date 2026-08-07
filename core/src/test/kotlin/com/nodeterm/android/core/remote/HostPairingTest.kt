package com.nodeterm.android.core.remote

import com.nodeterm.android.core.e2ee.E2ee
import com.nodeterm.android.core.e2ee.SshKeys
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class HostPairingTest {

    // The actual QR payload captured from a v0.2.37 host (Settings → Phone).
    private val realPayload = """
        {"v":1,"host":"192.168.0.110","port":22,"user":"yuanjie","token":"8qwJ762eB0MWsbIW_34evDRFGV4M1BB2","pairPort":62241,"nodeterm":true,"name":"MacBook Air de 袁杰","hostKey":"7bip6Fm+8P1LMQwgE+8/zI3oboMSabKzLqiu2s53sis=","relay":{"hostId":"IuNnJNePFrsG1dIIBiY6xV","hostPublicKeyB64":"7bip6Fm+8P1LMQwgE+8/zI3oboMSabKzLqiu2s53sis=","relayEndpoint":"wss://relay.nodeterm.dev"}}
    """.trimIndent()

    @Test
    fun `decodes the real v0_2_37 qr payload`() {
        val p = HostPairingCodec.decodePayload(realPayload)
        assertNotNull(p)
        assertEquals("192.168.0.110", p!!.host)
        assertEquals(62241, p.pairPort)
        assertEquals("8qwJ762eB0MWsbIW_34evDRFGV4M1BB2", p.token)
        assertEquals("MacBook Air de 袁杰", p.name)
        assertNotNull(p.relay)
        assertEquals("wss://relay.nodeterm.dev", p.relay!!.relayEndpoint)
        assertEquals("IuNnJNePFrsG1dIIBiY6xV", p.relay.hostId)
        assertEquals("7bip6Fm+8P1LMQwgE+8/zI3oboMSabKzLqiu2s53sis=", p.hostKey)
    }

    @Test
    fun `rejects garbage payloads`() {
        assertNull(HostPairingCodec.decodePayload("not json"))
        assertNull(HostPairingCodec.decodePayload("{}"))
        assertNull(HostPairingCodec.decodePayload(""))
        assertNull(HostPairingCodec.decodePayload("""{"host":"","pairPort":0,"token":""}"""))
    }

    @Test
    fun `ssh ed25519 line matches the host validator shape`() {
        val keys = SshKeys.generateEd25519()
        val line = HostPairingCodec.sshEd25519Line(keys.publicKey, "android")
        assertTrue(line.startsWith("ssh-ed25519 "))
        val blob = Base64.getDecoder().decode(line.removePrefix("ssh-ed25519 ").substringBefore(' '))
        // u32(len=11) ‖ "ssh-ed25519" ‖ u32(32) ‖ 32-byte key
        val nameLen = readU32(blob, 0)
        assertEquals(11, nameLen)
        assertEquals("ssh-ed25519", String(blob, 4, 11, Charsets.US_ASCII))
        val keyLen = readU32(blob, 4 + 11)
        assertEquals(32, keyLen)
        assertTrue(blob.size == 4 + 11 + 4 + 32)
    }

    @Test
    fun `sealed pair request opens on the host side and the response round-trips`() {
        // Host identity (nacl.box keypair) — the same key the payload's hostKey carries.
        val hostKeys = E2ee.generateKeyPair()
        val hostKeyB64 = E2ee.publicKeyToB64(hostKeys.publicKey)

        // Payload like the QR, but with the real host pubkey so ECDH works.
        val payload = HostPairingCodec.decodePayload(realPayload)!!
        val payloadWithKey = payload.copy(hostKey = hostKeyB64)
        val payloadRelay = payload.relay!!.copy(hostPublicKeyB64 = hostKeyB64)

        val sshKeys = SshKeys.generateEd25519()
        val sshLine = HostPairingCodec.sshEd25519Line(sshKeys.publicKey, "android")
        val session = SealedPairSession(E2ee.generateKeyPair(), hostKeyB64)

        // Client builds the sealed request.
        val wire = session.buildRequest(payloadWithKey, sshLine, "Redmi Note 12 Pro", "test-device")
        val outer = HostPairingCodec.json.parseToJsonElement(wire).jsonObject
        val epk = outer["epk"]!!.jsonPrimitive.content
        val boxB64 = outer["box"]!!.jsonPrimitive.content

        // Host side: derive the same shared key from our epk + host secret, decrypt.
        val shared = E2ee.deriveSharedKey(epk, hostKeys.secretKey)
        val plain = E2ee.decrypt(Base64.getDecoder().decode(boxB64), shared)!!
        val body = HostPairingCodec.json.parseToJsonElement(String(plain, Charsets.UTF_8)).jsonObject
        assertEquals(payload.token, body["token"]!!.jsonPrimitive.content)
        assertEquals(sshLine, body["publicKey"]!!.jsonPrimitive.content)
        assertEquals("Redmi Note 12 Pro", body["deviceName"]!!.jsonPrimitive.content)
        assertEquals("test-device", body["deviceId"]!!.jsonPrimitive.content)

        // Host responds sealed; client opens it.
        val hostResponse = buildJsonObject {
            put("ok", JsonPrimitive(true))
            put("deviceId", JsonPrimitive("d-1"))
            put("agentToken", JsonPrimitive("agent-1"))
            put("relayDeviceToken", JsonPrimitive("relay-dev-token"))
            putJsonObject("relay") {
                put("hostId", JsonPrimitive(payloadRelay.hostId))
                put("hostPublicKeyB64", JsonPrimitive(hostKeyB64))
                put("relayEndpoint", JsonPrimitive("wss://relay.nodeterm.dev"))
            }
        }
        val respBox = E2ee.encrypt(hostResponse.toString().toByteArray(Charsets.UTF_8), shared)
        val respJson = """{"box":"${Base64.getEncoder().encodeToString(respBox)}"}"""

        val result = session.openResponse(
            HostPairingCodec.json.parseToJsonElement(respJson).jsonObject["box"]!!.jsonPrimitive.content
        )
        assertNotNull(result)
        assertTrue(result!!.ok)
        assertEquals("relay-dev-token", result.relayDeviceToken)
        assertEquals("wss://relay.nodeterm.dev", result.relay!!.relayEndpoint)
        assertEquals(hostKeyB64, result.relay.hostPublicKeyB64)
    }

    @Test
    fun `openResponse returns null on a forged box`() {
        val hostKeys = E2ee.generateKeyPair()
        val payload = HostPairingCodec.decodePayload(realPayload)!!
            .copy(hostKey = E2ee.publicKeyToB64(hostKeys.publicKey))
        val session = SealedPairSession(E2ee.generateKeyPair(), payload.hostKey!!)
        val forged = E2ee.encrypt("garbage".toByteArray(), ByteArray(32) { 1 })
        assertNull(session.openResponse(Base64.getEncoder().encodeToString(forged)))
    }

    private fun readU32(b: ByteArray, off: Int): Int =
        ((b[off].toInt() and 0xff) shl 24) or
            ((b[off + 1].toInt() and 0xff) shl 16) or
            ((b[off + 2].toInt() and 0xff) shl 8) or
            (b[off + 3].toInt() and 0xff)
}
