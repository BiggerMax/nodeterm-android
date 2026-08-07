package com.nodeterm.android.net

import android.util.Log
import com.nodeterm.android.core.rpc.RelaySocket
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.util.concurrent.TimeUnit

/**
 * OkHttp WebSocket adapter for [RelaySocket.Transport].
 *
 * Text frames (handshake control) and binary frames (E2EE boxes) are preserved exactly, matching
 * the reference `wrapWebSocket` (relay-socket.ts). Sends issued before the socket opens are
 * queued — bytes sitting in the queue are real, un-flushed bytes, so they count toward
 * [bufferedAmount] (the honest-by-construction backpressure number).
 *
 * OkHttp exposes no post-open bufferedAmount, so after 'open' we report 0 (documented limitation);
 * the client mostly RECEIVES terminal output, and its own sends (keystrokes/RPCs) are small.
 */
class OkHttpRelayTransport(
    url: String,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()
) : RelaySocket.Transport {

    private val queue = ArrayDeque<RelaySocket.Message>()
    private var queuedBytes = 0L
    private var open = false
    private var onMsg: ((RelaySocket.Message) -> Unit)? = null
    private var onCloseCb: (() -> Unit)? = null
    private var closeFired = false

    /** Why the socket closed, for the UI (set before [RelayEvent.Closed] surfaces). */
    @Volatile
    var closeReason: String? = null
        private set

    override var bufferedAmount: Long
        get() = if (open) 0 else queuedBytes
        set(_) {
            // Read-only for callers; the transport owns the number.
        }

    private val webSocket: WebSocket = client.newWebSocket(
        Request.Builder().url(url).build(),
        object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                open = true
                queuedBytes = 0
                while (queue.isNotEmpty()) {
                    sendRaw(queue.removeFirst())
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                onMsg?.invoke(RelaySocket.Message.Text(text))
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                onMsg?.invoke(RelaySocket.Message.Binary(bytes.toByteArray()))
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                closeReason = "Relay closed the connection (code $code${if (reason.isNotBlank()) ": $reason" else ""})."
                Log.w(TAG, "relay ws closed code=$code reason=$reason")
                fireClose()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                // A failure (TLS/DNS/connect-refused/upgrade-timeout) is a close — the caller
                // tears the session down and reconnects with a fresh token.
                val http = response?.code?.let { " (HTTP $it)" } ?: ""
                closeReason = "Could not reach the relay: ${t.message ?: t.javaClass.simpleName}$http."
                Log.e(TAG, "relay ws failure: ${t.message} http=${response?.code}${response?.message?.let { " ($it)" } ?: ""}")
                fireClose()
            }
        }
    )

    override fun sendText(text: String) {
        sendRaw(RelaySocket.Message.Text(text))
    }

    override fun sendBinary(bytes: ByteArray) {
        sendRaw(RelaySocket.Message.Binary(bytes))
    }

    private fun sendRaw(message: RelaySocket.Message) {
        if (open) {
            when (message) {
                is RelaySocket.Message.Text -> webSocket.send(message.text)
                is RelaySocket.Message.Binary -> webSocket.send(ByteString.of(*message.bytes))
            }
        } else {
            queue.addLast(message)
            queuedBytes += when (message) {
                is RelaySocket.Message.Text -> message.text.toByteArray().size.toLong()
                is RelaySocket.Message.Binary -> message.bytes.size.toLong()
            }
        }
    }

    override fun close() {
        fireClose()
        webSocket.close(1000, "client closing")
        client.dispatcher.executorService.shutdown()
    }

    /**
     * App-layer force close with a visible reason (e.g. a handshake timeout). Unlike the
     * intentional [close] path — which RelaySocket suppresses via `intentionallyClosed` — this
     * fires the transport's onClose so the session surfaces [RelayEvent.Closed] with [reason].
     */
    fun forceCloseWithReason(reason: String) {
        closeReason = reason
        fireClose()
        webSocket.close(1000, reason)
        client.dispatcher.executorService.shutdown()
    }

    override fun onMessage(cb: (RelaySocket.Message) -> Unit) {
        onMsg = cb
    }

    override fun onClose(cb: () -> Unit) {
        onCloseCb = cb
        // The socket may have failed BEFORE RelaySocket registered this callback (the WebSocket
        // dials in the constructor) — deliver the missed close now so the session is not stuck
        // in CONNECTING with the reason swallowed.
        if (closeFired) cb()
    }

    private companion object {
        const val TAG = "NodetermRelay"
    }

    private fun fireClose() {
        if (closeFired) return
        closeFired = true
        onCloseCb?.invoke()
    }
}
