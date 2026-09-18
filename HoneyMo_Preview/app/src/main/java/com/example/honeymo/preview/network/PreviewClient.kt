package com.example.honeymo.preview.network

import android.util.Log
import com.example.honeymo.preview.data.DeviceInfo
import kotlinx.coroutines.*
import okhttp3.*
import okio.ByteString
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class PreviewClient(
    private val baseUrl: String,
    private val listener: PreviewListener
) {

    open class PreviewListener {
        open fun onDeviceListUpdated(devices: List<DeviceInfo>) {}
        open fun onFrameReceived(chunk: ByteArray) {}
        open fun onAudioReceived(isConfig: Boolean, ptsUs: Long, chunk: ByteArray) {}
        open fun onConnected() {}
        open fun onDisconnected(reason: String) {}
        open fun onError(error: String) {}
        open fun onPingUpdated(pingMs: Long) {}
        open fun onCameraStatusUpdated(cameraAllowed: Boolean, cameraFacing: String, cameraActive: Boolean) {}
        open fun onStatsUpdated(fps: Int, bitrateKbps: Int, totalFrames: Long, totalBytes: Long) {}
    }

    companion object {
        private const val TAG = "PreviewClient"
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(10, TimeUnit.SECONDS)
        .build()

    private var webSocket: WebSocket? = null
    private val isRunning = AtomicBoolean(false)
    private var currentDeviceId: String? = null
    private var pingJob: Job? = null
    private val clientScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    fun fetchDevices() {
        clientScope.launch {
            val httpUrl = baseUrl
                .replace("ws://", "http://")
                .replace("wss://", "https://")
                .trimEnd('/') + "/api/devices"

            try {
                val request = Request.Builder().url(httpUrl).build()
                httpClient.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val body = response.body?.string() ?: return@use
                        val json = JSONObject(body)
                        val array = json.optJSONArray("devices")
                        val list = mutableListOf<DeviceInfo>()
                        if (array != null) {
                            for (i in 0 until array.length()) {
                                val item = array.getJSONObject(i)
                                list.add(parseDeviceInfo(item))
                            }
                        }
                        withContext(Dispatchers.Main) {
                            listener.onDeviceListUpdated(list)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error fetching devices via REST: ${e.message}")
            }
        }
    }

    fun connect(deviceId: String? = null) {
        currentDeviceId = deviceId
        isRunning.set(true)

        val wsUrl = buildWsUrl(deviceId)
        Log.d(TAG, "Connecting to WebSocket: $wsUrl")

        val request = Request.Builder().url(wsUrl).build()
        webSocket = httpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                Log.d(TAG, "WebSocket connected!")
                withContextUi { listener.onConnected() }
                if (deviceId != null) {
                    requestKeyframe()
                }
                startPingLoop()
            }

            override fun onMessage(ws: WebSocket, text: String) {
                try {
                    val json = JSONObject(text)
                    val type = json.optString("type")
                    if (type == "PONG") {
                        val sentTime = json.optLong("time")
                        if (sentTime > 0) {
                            val rtt = System.currentTimeMillis() - sentTime
                            withContextUi {
                                try {
                                    listener.onPingUpdated(rtt)
                                } catch (e: Throwable) {
                                    Log.w(TAG, "listener.onPingUpdated error: ${e.message}")
                                }
                            }
                        }
                    } else if (type == "DEVICE_LIST") {
                        val array = json.optJSONArray("devices")
                        val list = mutableListOf<DeviceInfo>()
                        if (array != null) {
                            for (i in 0 until array.length()) {
                                list.add(parseDeviceInfo(array.getJSONObject(i)))
                            }
                        }
                        withContextUi { listener.onDeviceListUpdated(list) }
                    } else if (type == "CAMERA_STATUS") {
                        val allowed = json.optBoolean("cameraAllowed", false)
                        val facing = json.optString("cameraFacing", "front")
                        val active = json.optBoolean("cameraActive", false)
                        Log.d(TAG, "Received CAMERA_STATUS: allowed=$allowed, facing=$facing, active=$active")
                        withContextUi { listener.onCameraStatusUpdated(allowed, facing, active) }
                    } else if (type == "STATS") {
                        val stats = json.optJSONObject("stats")
                        if (stats != null) {
                            val fps = stats.optInt("fps", 0)
                            val bitrateKbps = stats.optInt("bitrateKbps", 0)
                            val totalFrames = stats.optLong("totalFrames", 0L)
                            val totalBytes = stats.optLong("totalBytes", 0L)
                            withContextUi {
                                listener.onStatsUpdated(fps, bitrateKbps, totalFrames, totalBytes)
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error parsing JSON message: ${e.message}")
                }
            }

            override fun onMessage(ws: WebSocket, bytes: ByteString) {
                val byteArray = bytes.toByteArray()
                // Check if this is an audio packet (HMA1 header: [0x48, 0x4D, 0x41, 0x31])
                if (byteArray.size >= 17 &&
                    byteArray[0] == 'H'.code.toByte() &&
                    byteArray[1] == 'M'.code.toByte() &&
                    byteArray[2] == 'A'.code.toByte() &&
                    byteArray[3] == '1'.code.toByte()
                ) {
                    val type = byteArray[4].toInt()
                    val ptsUs = java.nio.ByteBuffer.wrap(byteArray, 5, 8).long
                    val payloadLen = java.nio.ByteBuffer.wrap(byteArray, 13, 4).int
                    val end = minOf(byteArray.size, 17 + payloadLen)
                    if (end >= 17) {
                        val payload = byteArray.copyOfRange(17, end)
                        listener.onAudioReceived(type == 0, ptsUs, payload)
                    }
                } else {
                    // Binary H.264 video frame received
                    listener.onFrameReceived(byteArray)
                }
            }

            override fun onClosing(ws: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WebSocket closing: $code / $reason")
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WebSocket closed: $code / $reason")
                withContextUi { listener.onDisconnected(reason) }
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WebSocket failure: ${t.message}", t)
                withContextUi { listener.onError(t.message ?: "Connection failure") }
            }
        })
    }

    private fun startPingLoop() {
        pingJob?.cancel()
        pingJob = clientScope.launch {
            while (isActive && isRunning.get()) {
                delay(2000)
                if (isRunning.get() && webSocket != null) {
                    try {
                        val pingMsg = JSONObject().apply {
                            put("type", "PING")
                            put("time", System.currentTimeMillis())
                        }
                        webSocket?.send(pingMsg.toString())
                    } catch (e: Exception) {
                        Log.w(TAG, "Error sending ping: ${e.message}")
                    }
                }
            }
        }
    }

    fun requestKeyframe() {
        val devId = currentDeviceId ?: return
        val msg = JSONObject().apply {
            put("type", "REQUEST_KEYFRAME")
            put("deviceId", devId)
        }
        webSocket?.send(msg.toString())
        Log.d(TAG, "Sent REQUEST_KEYFRAME for device $devId")
    }

    fun switchCamera(targetFacing: String? = null) {
        val devId = currentDeviceId ?: return
        val msg = JSONObject().apply {
            put("type", "SWITCH_CAMERA")
            put("deviceId", devId)
            if (targetFacing != null) {
                put("targetFacing", targetFacing)
            }
        }
        webSocket?.send(msg.toString())
        Log.d(TAG, "Sent SWITCH_CAMERA for device $devId (targetFacing: $targetFacing)")
    }

    fun disconnect() {
        isRunning.set(false)
        pingJob?.cancel()
        pingJob = null
        try {
            webSocket?.close(1000, "User disconnected")
        } catch (e: Exception) {
            Log.e(TAG, "Error closing websocket: ${e.message}")
        }
        webSocket = null
    }

    private fun buildWsUrl(deviceId: String?): String {
        var base = baseUrl.trimEnd('/')
        if (!base.startsWith("ws://") && !base.startsWith("wss://")) {
            base = "ws://$base"
        }
        val path = if (base.endsWith("/ws/viewer")) base else "$base/ws/viewer"
        return if (deviceId != null) "$path?deviceId=$deviceId" else path
    }

    private fun parseDeviceInfo(obj: JSONObject): DeviceInfo {
        val statsObj = obj.optJSONObject("stats")
        val currentFps = statsObj?.optInt("fps", 0) ?: 0
        val currentBitrate = statsObj?.optInt("bitrateKbps", 0) ?: 0

        return DeviceInfo(
            id = obj.optString("id"),
            name = obj.optString("name", "Unknown Device"),
            width = obj.optInt("width", 720),
            height = obj.optInt("height", 1280),
            fps = if (currentFps > 0) currentFps else obj.optInt("fps", 30),
            bitrate = if (currentBitrate > 0) currentBitrate * 1000 else obj.optInt("bitrate", 2000000),
            connectedAt = obj.optString("connectedAt", ""),
            cameraAllowed = obj.optBoolean("cameraAllowed", false),
            cameraFacing = obj.optString("cameraFacing", "front"),
            cameraActive = obj.optBoolean("cameraActive", false)
        )
    }

    private fun withContextUi(block: () -> Unit) {
        clientScope.launch(Dispatchers.Main) {
            block()
        }
    }
}
