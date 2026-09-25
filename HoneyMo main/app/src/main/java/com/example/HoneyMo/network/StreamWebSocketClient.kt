package com.example.HoneyMo.network

import android.os.Build
import android.util.Log
import com.example.HoneyMo.agent.AgentCommand
import kotlinx.coroutines.*
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
    private val username: String = "User",
    private val cameraStatusProvider: (() -> Triple<Boolean, String, Boolean>)? = null,
    private val listener: StreamListener
) {

    interface StreamListener {
        fun onConnected()
        fun onDisconnected(reason: String)
        fun onKeyframeRequested()
        fun onCameraSwitchRequested(targetFacing: String?)
        fun onError(error: String)

        // ---- AI Agent callbacks (default no-op implementations keep existing callers working) ----
        fun onScreenshotRequested() {}
        fun onCommandReceived(command: AgentCommand) {}
        fun onTtsTextReceived(text: String) {}
        fun onAgentStatusReceived(status: String, message: String) {}
    }

    companion object {
        private const val TAG = "StreamWS"
        private const val MAX_QUEUE_SIZE_BYTES = 128 * 1024 // 128 KB (CBR 1Mbps: caps buffering latency to ~1s)
        private const val MAX_VOICE_QUEUE_BYTES = 512 * 1024 // 512 KB for raw PCM voice chunks
    }

    private var client: OkHttpClient = OkHttpClient.Builder()
        .pingInterval(10, TimeUnit.SECONDS)
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS) // infinite for continuous stream
        .build()

    private var webSocket: WebSocket? = null
    private val isRunning = AtomicBoolean(false)
    private val isConnected = AtomicBoolean(false)
    private var reconnectJob: Job? = null
    private val clientScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var retryCount = 0

    fun connect() {
        isRunning.set(true)
        retryCount = 0
        doConnect()
    }

    fun reconnectImmediate() {
        if (!isRunning.get()) return
        reconnectJob?.cancel()
        retryCount = 0
        clientScope.launch {
            delay(200)
            if (isRunning.get() && !isConnected.get()) {
                Log.d(TAG, "Triggering immediate reconnect...")
                doConnect()
            }
        }
    }

    private fun doConnect() {
        if (!isRunning.get()) return

        Log.d(TAG, "Connecting to $serverUrl...")
        val request = Request.Builder().url(serverUrl).build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                Log.d(TAG, "WebSocket connected!")
                isConnected.set(true)
                retryCount = 0
                reconnectJob?.cancel()
                sendInitMetadata()
                listener.onConnected()
            }

            override fun onMessage(ws: WebSocket, text: String) {
                try {
                    val json = JSONObject(text)
                    when (json.optString("type")) {
                        "REQUEST_KEYFRAME" -> {
                            Log.d(TAG, "Server requested keyframe")
                            listener.onKeyframeRequested()
                        }
                        "SWITCH_CAMERA" -> {
                            val targetFacing = json.optString("targetFacing").takeIf { it.isNotBlank() }
                            Log.d(TAG, "Server requested switch camera (targetFacing: $targetFacing)")
                            listener.onCameraSwitchRequested(targetFacing)
                        }
                        "PING" -> {
                            val pong = JSONObject().apply {
                                put("type", "PONG")
                                put("time", json.optLong("time"))
                                put("serverTime", System.currentTimeMillis())
                            }
                            ws.send(pong.toString())
                        }

                        // ---- AI Agent messages from server ----

                        "SCREENSHOT_REQUEST" -> {
                            Log.d(TAG, "Server requested on-demand screenshot")
                            listener.onScreenshotRequested()
                        }
                        "COMMAND" -> {
                            Log.d(TAG, "Received COMMAND from server: action=${json.optString("action")}")
                            try {
                                val command = AgentCommand.fromJson(json)
                                listener.onCommandReceived(command)
                            } catch (e: Exception) {
                                Log.e(TAG, "Failed to parse COMMAND: ${e.message}")
                            }
                        }
                        "TTS_TEXT" -> {
                            val text = json.optString("text")
                            if (text.isNotBlank()) {
                                Log.d(TAG, "Received TTS_TEXT: ${text.take(60)}")
                                listener.onTtsTextReceived(text)
                            }
                        }
                        "AGENT_STATUS" -> {
                            val status = json.optString("status", "IDLE")
                            val message = json.optString("message", "")
                            Log.d(TAG, "Agent status: $status — $message")
                            listener.onAgentStatusReceived(status, message)
                        }
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
                if (isRunning.get()) {
                    scheduleReconnect()
                }
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WebSocket failure: ${t.message}", t)
                isConnected.set(false)
                listener.onError(t.message ?: "Connection failure")
                if (isRunning.get()) {
                    scheduleReconnect()
                }
            }
        })
    }

    private fun scheduleReconnect() {
        if (!isRunning.get()) return
        reconnectJob?.cancel()
        reconnectJob = clientScope.launch {
            val delayMs = minOf(1000L * (1 shl minOf(retryCount, 4)), 10000L) // 1s, 2s, 4s, 8s, max 10s
            Log.d(TAG, "Scheduling reconnect in ${delayMs}ms (attempt #${retryCount + 1})...")
            delay(delayMs)
            retryCount++
            if (isRunning.get() && !isConnected.get()) {
                doConnect()
            }
        }
    }

    private fun sendInitMetadata() {
        val deviceId = "${Build.MANUFACTURER}_${Build.MODEL}_${Build.SERIAL.takeIf { it != "unknown" } ?: Build.ID}".replace(" ", "_").lowercase()
        val (cameraAllowed, cameraFacing, cameraActive) = cameraStatusProvider?.invoke() ?: Triple(false, "front", false)
        val meta = JSONObject().apply {
            put("type", "INIT")
            put("deviceId", deviceId)
            put("deviceName", "${Build.MANUFACTURER} ${Build.MODEL}")
            put("username", username)   // ← Jarvis: attach username
            put("width", width)
            put("height", height)
            put("fps", fps)
            put("bitrate", bitrate)
            put("androidVersion", "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            put("cameraAllowed", cameraAllowed)
            put("cameraFacing", cameraFacing)
            put("cameraActive", cameraActive)
        }
        webSocket?.send(meta.toString())
        Log.d(TAG, "Sent INIT metadata: user=$username, allowed=$cameraAllowed, facing=$cameraFacing, active=$cameraActive")
    }

    fun sendCameraStatus(cameraAllowed: Boolean, cameraFacing: String, cameraActive: Boolean) {
        val payload = JSONObject().apply {
            put("type", "CAMERA_STATUS")
            put("cameraAllowed", cameraAllowed)
            put("cameraFacing", cameraFacing)
            put("cameraActive", cameraActive)
        }
        webSocket?.send(payload.toString())
        Log.d(TAG, "Sent CAMERA_STATUS: allowed=$cameraAllowed, facing=$cameraFacing, active=$cameraActive")
    }

    fun sendFrame(data: ByteArray, isKeyFrame: Boolean): Boolean {
        val ws = webSocket ?: return false
        if (!isConnected.get()) return false

        // Drop non-keyframes if socket send queue is overflowing (> 128 KB buffered)
        if (!isKeyFrame && ws.queueSize() > MAX_QUEUE_SIZE_BYTES) {
            Log.w(TAG, "Dropping P-frame due to network buffer congestion (${ws.queueSize()} bytes)")
            return false
        }

        return ws.send(data.toByteString(0, data.size))
    }

    // ---- AI Agent send methods ----

    /** Signal that the user has started speaking — server begins buffering audio */
    fun sendVoiceStart() {
        val payload = JSONObject().apply {
            put("type", "VOICE_START")
            put("username", username)
        }
        webSocket?.send(payload.toString())
        Log.d(TAG, "Sent VOICE_START")
    }

    /**
     * Send a raw PCM audio chunk (16-bit mono 44100Hz) to the server for STT.
     * These are NOT HMA1-wrapped — they are raw binary PCM.
     */
    fun sendVoiceChunk(pcmData: ByteArray): Boolean {
        val ws = webSocket ?: return false
        if (!isConnected.get()) return false
        // Drop chunks if send queue is overloaded to avoid memory buildup
        if (ws.queueSize() > MAX_VOICE_QUEUE_BYTES) {
            Log.w(TAG, "Dropping voice PCM chunk — queue congested (${ws.queueSize()} bytes)")
            return false
        }
        return ws.send(pcmData.toByteString(0, pcmData.size))
    }

    /** Signal that the user has stopped speaking — server triggers STT + AI pipeline */
    fun sendVoiceEnd() {
        val payload = JSONObject().apply {
            put("type", "VOICE_END")
            put("username", username)
        }
        webSocket?.send(payload.toString())
        Log.d(TAG, "Sent VOICE_END")
    }

    /** Send a compressed JPEG screenshot to the server in response to SCREENSHOT_REQUEST */
    fun sendScreenshotData(jpegBytes: ByteArray): Boolean {
        val ws = webSocket ?: return false
        if (!isConnected.get()) return false
        val sent = ws.send(jpegBytes.toByteString(0, jpegBytes.size))
        Log.d(TAG, "Sent SCREENSHOT_DATA: ${jpegBytes.size} bytes, queued=${sent}")
        return sent
    }

    /** Report back to the server whether an AI command was executed successfully */
    fun sendActionResult(success: Boolean, message: String, username: String) {
        val payload = JSONObject().apply {
            put("type", "ACTION_RESULT")
            put("success", success)
            put("message", message)
            put("username", username)
        }
        webSocket?.send(payload.toString())
        Log.d(TAG, "Sent ACTION_RESULT: success=$success, message=$message")
    }

    fun isConnected(): Boolean = isConnected.get()

    fun disconnect() {
        isRunning.set(false)
        isConnected.set(false)
        reconnectJob?.cancel()
        reconnectJob = null
        try {
            webSocket?.close(1000, "User stopped stream")
        } catch (e: Exception) {
            Log.e(TAG, "Error closing websocket: ${e.message}")
        }
        webSocket = null
    }
}
