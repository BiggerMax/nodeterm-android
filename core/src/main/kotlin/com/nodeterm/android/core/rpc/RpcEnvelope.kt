package com.nodeterm.android.core.rpc

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * The RPC envelope spoken over the encrypted relay channel (TAG_RPC = 0x01).
 *
 * `{kind: 'req', id, method, params}` / `{kind: 'notify', method, params}` /
 * `{kind: 'res', id, ok, body}` / `{kind: 'keepalive'}` — mirrors relay-socket.ts.
 */
sealed class RpcMessage {
    data class Request(val id: String, val method: String, val params: JsonElement?) : RpcMessage()
    data class Notify(val method: String, val params: JsonElement?) : RpcMessage()
    data class Response(val id: String, val ok: Boolean, val body: JsonElement?) : RpcMessage()
    data object Keepalive : RpcMessage()
}

object RpcCodec {
    private val json = Json { ignoreUnknownKeys = true }

    fun encode(msg: RpcMessage): String = buildJsonObject {
        when (msg) {
            is RpcMessage.Request -> {
                put("kind", "req")
                put("id", msg.id)
                put("method", msg.method)
                putParams(this, msg.params)
            }
            is RpcMessage.Notify -> {
                put("kind", "notify")
                put("method", msg.method)
                putParams(this, msg.params)
            }
            is RpcMessage.Response -> {
                put("kind", "res")
                put("id", msg.id)
                put("ok", msg.ok)
                // Mirror the reference: a null body is OMITTED (JSON.stringify drops undefined).
                if (msg.body != null) put("body", msg.body)
            }
            RpcMessage.Keepalive -> put("kind", "keepalive")
        }
    }.toString()

    /** Decode an envelope; returns null for any malformed input — never throws. */
    fun decode(text: String): RpcMessage? = try {
        val obj = json.parseToJsonElement(text).jsonObject
        when (obj["kind"]?.jsonPrimitive?.contentOrNull) {
            "req" -> {
                val id = obj["id"]?.jsonPrimitive?.contentOrNull ?: return null
                val method = obj["method"]?.jsonPrimitive?.contentOrNull ?: return null
                RpcMessage.Request(id, method, obj["params"].asNullable())
            }
            "notify" -> {
                val method = obj["method"]?.jsonPrimitive?.contentOrNull ?: return null
                RpcMessage.Notify(method, obj["params"].asNullable())
            }
            "res" -> {
                val id = obj["id"]?.jsonPrimitive?.contentOrNull ?: return null
                val ok = obj["ok"]?.jsonPrimitive?.booleanOrNull ?: return null
                RpcMessage.Response(id, ok, obj["body"].asNullable())
            }
            "keepalive" -> RpcMessage.Keepalive
            else -> null
        }
    } catch (_: Exception) {
        null
    }

    /** A missing key and an explicit JSON `null` both mean "absent" — normalize to Kotlin null. */
    private fun JsonElement?.asNullable(): JsonElement? = this?.takeIf { it != JsonNull }

    private fun putParams(obj: JsonObjectBuilder, params: JsonElement?) {
        obj.put("params", params ?: JsonNull)
    }
}
