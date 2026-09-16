package com.example.HoneyMo.network

import android.os.Build
import android.util.Log
import okhttp3.*
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class StreamWebSocketClient(
    private val serverUrl: String,
    private val width: Int,
    private val height: Int,
    private val fps: Int,
    private val bitrate: Int,
    private val listener: StreamListener
) {

    interface StreamListener {
        fun onConnected()
        fun onDisconnected(reason: String)
        fun onKeyframeRequested()
        fun onError(error: String)
    }

    companion object {
        private const val TAG = "StreamWS"
        private const val MAX_QUEUE_SIZE_BYTES = 1024 * 1024 // 1 MB
    }

    private var client: OkHttpClient = OkHttpClient.Builder()
        .pingInterval(10, TimeUnit.SECONDS)
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS) // infinite for continuous stream
        .build()

    private var webSocket: WebSocket? = null
    private val isRunning = AtomicBoolean(false)
    private val isConnected = AtomicBoolean(false)

    fun connect() {
        isRunning.set(true)
        doConnect()
    }

    private fun doConnect() {
        if (!isRunning.get()) return

        Log.d(TAG, "Connecting to $serverUrl...")
        val request = Request.Builder().url(serverUrl).build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                Log.d(TAG, "WebSocket connected!")
                isConnected.set(true)
                sendInitMetadata()
                listener.onConnected()
            }

            override fun onMessage(ws: WebSocket, text: String) {
                Log.d(TAG, "Received message from server: $text")
                try {
                    val json = JSONObject(text)
                    when (json.optString("type")) {
                        "REQUEST_KEYFRAME" -> listener.onKeyframeRequested()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to parse message: ${e.message}")
                }
            }

            override fun onClosing(ws: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WebSocket closing: $code / $reason")
                isConnected.set(false)
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WebSocket closed: $code / $reason")
                isConnected.set(false)
                listener.onDisconnected(reason)
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WebSocket failure: ${t.message}", t)
                isConnected.set(false)
                listener.onError(t.message ?: "Connection failure")
            }
        })
    }

    private fun sendInitMetadata() {
        val deviceId = "${Build.MANUFACTURER}_${Build.MODEL}_${Build.SERIAL.takeIf { it != "unknown" } ?: Build.ID}".replace(" ", "_").lowercase()
        val meta = JSONObject().apply {
            put("type", "INIT")
            put("deviceId", deviceId)
            put("deviceName", "${Build.MANUFACTURER} ${Build.MODEL}")
            put("width", width)
            put("height", height)
            put("fps", fps)
            put("bitrate", bitrate)
            put("androidVersion", "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        }
        webSocket?.send(meta.toString())
    }

    fun sendFrame(data: ByteArray, isKeyFrame: Boolean): Boolean {
        val ws = webSocket ?: return false
        if (!isConnected.get()) return false

        // Drop non-keyframes if socket send queue is overflowing (> 1MB buffered)
        if (!isKeyFrame && ws.queueSize() > MAX_QUEUE_SIZE_BYTES) {
            Log.w(TAG, "Dropping P-frame due to network buffer congestion (${ws.queueSize()} bytes)")
            return false
        }

        return ws.send(data.toByteString(0, data.size))
    }

    fun isConnected(): Boolean = isConnected.get()

    fun disconnect() {
        isRunning.set(false)
        isConnected.set(false)
        try {
            webSocket?.close(1000, "User stopped stream")
        } catch (e: Exception) {
            Log.e(TAG, "Error closing websocket: ${e.message}")
        }
        webSocket = null
    }
}
