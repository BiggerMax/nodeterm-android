package com.nodeterm.android.core.model

import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class PairingTest {

    private fun encodeUrl(code: String): String =
        "nodeterm://pair?code=" + Base64.getUrlEncoder().encodeToString(code.toByteArray(Charsets.UTF_8))

    @Test
    fun decodeFullUrl() {
        val json = """{"relayEndpoint":"wss://relay.nodeterm.dev","pairingToken":"tok-123","hostPublicKeyB64":"EyxEK+AQ+9V+cmAzKKp25x/MwVA6riGTJ9FNnJmT9HI="}"""
        val offer = PairingCodec.decodeOffer(encodeUrl(json))
        assertNotNull(offer)
        assertEquals("wss://relay.nodeterm.dev", offer.relayEndpoint)
        assertEquals("tok-123", offer.pairingToken)
        assertEquals("EyxEK+AQ+9V+cmAzKKp25x/MwVA6riGTJ9FNnJmT9HI=", offer.hostPublicKeyB64)
    }

    @Test
    fun decodeBareCode() {
        val json = """{"relayEndpoint":"wss://r.example.com","pairingToken":"t","hostPublicKeyB64":"AAAA"}"""
        val code = Base64.getUrlEncoder().encodeToString(json.toByteArray(Charsets.UTF_8))
        val offer = PairingCodec.decodeOffer(code)
        assertNotNull(offer)
        assertEquals("wss://r.example.com", offer.relayEndpoint)
    }

    @Test
    fun decodeAllowsLoopbackWs() {
        val json = """{"relayEndpoint":"ws://127.0.0.1:8787","pairingToken":"t","hostPublicKeyB64":"AAAA"}"""
        assertNotNull(PairingCodec.decodeOffer(encodeUrl(json)))
    }

    @Test
    fun rejectsNonLoopbackPlaintextWs() {
        val json = """{"relayEndpoint":"ws://relay.evil.example","pairingToken":"t","hostPublicKeyB64":"AAAA"}"""
        assertNull(PairingCodec.decodeOffer(encodeUrl(json)))
    }

    @Test
    fun rejectsNonWebsocketScheme() {
        val json = """{"relayEndpoint":"http://relay.evil.example","pairingToken":"t","hostPublicKeyB64":"AAAA"}"""
        assertNull(PairingCodec.decodeOffer(encodeUrl(json)))
    }

    @Test
    fun rejectsMissingFields() {
        assertNull(PairingCodec.decodeOffer(encodeUrl("""{"relayEndpoint":"wss://x"}""")))
        assertNull(PairingCodec.decodeOffer(encodeUrl("""{"pairingToken":"","hostPublicKeyB64":"AAAA"}""")))
    }

    @Test
    fun rejectsGarbage() {
        assertNull(PairingCodec.decodeOffer("not a code at all"))
        assertNull(PairingCodec.decodeOffer("nodeterm://wrong?code=abc"))
        assertNull(PairingCodec.decodeOffer("https://pair?code=abc"))
        assertNull(PairingCodec.decodeOffer(""))
        assertNull(PairingCodec.decodeOffer("nodeterm://pair?code=!!!not-base64!!!"))
    }
}
