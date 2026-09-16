package com.example.HoneyMo.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.*
import android.util.Log
import android.view.Surface
import androidx.core.app.NotificationCompat
import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.example.HoneyMo.MainActivity
import com.example.HoneyMo.audio.AudioCaptureEncoder
import com.example.HoneyMo.network.StreamWebSocketClient
import com.example.HoneyMo.util.SessionPreferences
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

class ScreenCaptureService : Service(), StreamWebSocketClient.StreamListener {

    companion object {
        private const val TAG = "ScreenCaptureService"
        const val CHANNEL_ID = "stream_channel"
        const val NOTIFICATION_ID = 1001

        const val ACTION_START = "ACTION_START"
        const val ACTION_STOP = "ACTION_STOP"

        const val EXTRA_RESULT_CODE = "EXTRA_RESULT_CODE"
        const val EXTRA_DATA = "EXTRA_DATA"
        const val EXTRA_SERVER_URL = "EXTRA_SERVER_URL"
        const val EXTRA_WIDTH = "EXTRA_WIDTH"
        const val EXTRA_HEIGHT = "EXTRA_HEIGHT"
        const val EXTRA_DENSITY = "EXTRA_DENSITY"
        const val EXTRA_FPS = "EXTRA_FPS"
        const val EXTRA_BITRATE = "EXTRA_BITRATE"

        data class StreamStats(
            val isStreaming: Boolean = false,
            val isConnectedToServer: Boolean = false,
            val framesSent: Long = 0,
            val bytesSent: Long = 0,
            val currentFps: Int = 0,
            val errorMsg: String? = null
        )

        private val _statsFlow = MutableStateFlow(StreamStats())
        val statsFlow: StateFlow<StreamStats> = _statsFlow.asStateFlow()

        var isRunning = false
            private set

        var instance: ScreenCaptureService? = null
            private set
    }

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var encoder: MediaCodec? = null
    private var inputSurface: Surface? = null
    private var wsClient: StreamWebSocketClient? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var connectivityManager: ConnectivityManager? = null

    private val isCapturing = AtomicBoolean(false)
    private var encodeJob: Job? = null
    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private var cachedConfig: ByteArray? = null

    private var framesCounter = 0L
    private var bytesCounter = 0L
    private var fpsCounter = 0
    private var statsJob: Job? = null

    private var audioEncoder: AudioCaptureEncoder? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannel()
        setupWakeLock()
        setupNetworkMonitoring()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: return START_NOT_STICKY

        when (action) {
            ACTION_START -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
                val data = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(EXTRA_DATA, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(EXTRA_DATA)
                }
                val serverUrl = intent.getStringExtra(EXTRA_SERVER_URL) ?: "wss://honeymo-relay-server.onrender.com/ws/device"
                val width = intent.getIntExtra(EXTRA_WIDTH, 544)
                val height = intent.getIntExtra(EXTRA_HEIGHT, 960)
                val density = intent.getIntExtra(EXTRA_DENSITY, 320)
                val fps = intent.getIntExtra(EXTRA_FPS, 15)
                val bitrate = intent.getIntExtra(EXTRA_BITRATE, 2_000_000)

                if (resultCode != Activity.RESULT_OK || data == null) {
                    Log.e(TAG, "Invalid resultCode or projection data Intent")
                    stopSelf()
                    return START_NOT_STICKY
                }

                // Step 1: Promote to Foreground Service FIRST (Required on Android 14+)
                startForegroundWithNotification()

                // Step 2: Initialize MediaProjection and start streaming AFTER startForeground()
                startCapturePipeline(resultCode, data, serverUrl, width, height, density, fps, bitrate)
            }
            ACTION_STOP -> {
                stopCapture()
                stopSelf()
            }
        }

        return START_NOT_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "System Service",
                NotificationManager.IMPORTANCE_MIN
            ).apply {
                description = "Background service"
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_SECRET
                enableLights(false)
                enableVibration(false)
                setSound(null, null)
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    private fun startForegroundWithNotification() {
        val stopIntent = Intent(this, ScreenCaptureService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val openAppPendingIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("System Service")
            .setContentText("Running in background")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentIntent(openAppPendingIntent)
            .addAction(android.R.drawable.ic_delete, "Stop", stopPendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setSilent(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val hasMicPerm = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
            val fgsType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && hasMicPerm) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            } else {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            }
            startForeground(NOTIFICATION_ID, notification, fgsType)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun startCapturePipeline(
        resultCode: Int,
        data: Intent,
        serverUrl: String,
        width: Int,
        height: Int,
        density: Int,
        fps: Int,
        bitrate: Int
    ) {
        if (isCapturing.get()) {
            Log.w(TAG, "Capture already active")
            return
        }

        try {
            acquireWakeLock()

            // Connect WebSocket
            wsClient = StreamWebSocketClient(
                serverUrl = serverUrl,
                width = width,
                height = height,
                fps = fps,
                bitrate = bitrate,
                listener = this
            ).also { it.connect() }

            // Start audio capture & AAC encoder if RECORD_AUDIO permission is granted
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                audioEncoder = AudioCaptureEncoder { adtsChunk ->
                    wsClient?.sendFrame(adtsChunk, false)
                }.apply {
                    start()
                }
                Log.d(TAG, "Live voice recording & streaming enabled")
            } else {
                Log.w(TAG, "RECORD_AUDIO permission not granted, continuing with video only")
            }

            // Get MediaProjection (Only called AFTER startForeground!)
            val mpManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = mpManager.getMediaProjection(resultCode, data)

            // Register callback
            mediaProjection?.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    Log.d(TAG, "MediaProjection stopped by system")
                    stopCapture()
                    stopSelf()
                }
            }, Handler(Looper.getMainLooper()))

            // Setup MediaCodec encoder
            setupEncoder(width, height, fps, bitrate)

            // Create VirtualDisplay
            virtualDisplay = mediaProjection?.createVirtualDisplay(
                "HoneyMoScreenDisplay",
                width,
                height,
                density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                inputSurface,
                null,
                null
            )

            isCapturing.set(true)
            isRunning = true

            // Start draining encoder buffers
            startEncodingLoop()
            startStatsReporter()

            _statsFlow.value = _statsFlow.value.copy(
                isStreaming = true,
                errorMsg = null
            )
            Log.d(TAG, "Capture pipeline started successfully ($width x $height @ $fps fps)")

            // Mark session as actively recording to detect interrupted reboots
            SessionPreferences.setRecordingActive(applicationContext, true)

        } catch (e: Exception) {
            Log.e(TAG, "Failed to start capture pipeline: ${e.message}", e)
            _statsFlow.value = _statsFlow.value.copy(errorMsg = "Capture initialization failed: ${e.message}")
            stopCapture()
            stopSelf()
        }
    }

    private fun setupEncoder(width: Int, height: Int, fps: Int, bitrate: Int) {
        // Dimensions must be 16-pixel aligned for many hardware AVC encoders
        val alignedWidth = (width + 15) / 16 * 16
        val alignedHeight = (height + 15) / 16 * 16

        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, alignedWidth, alignedHeight).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1) // 1 second keyframe interval
            setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)

            // Critical for screen capture: repeat static frames so stream doesn't pause when screen is still
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 1_000_000L / fps)
            }
        }

        encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).apply {
            configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            inputSurface = createInputSurface()
            start()
        }
    }

    private fun startEncodingLoop() {
        encodeJob = serviceScope.launch(Dispatchers.IO) {
            val bufferInfo = MediaCodec.BufferInfo()
            val enc = encoder ?: return@launch

            while (isActive && isCapturing.get()) {
                try {
                    val outputIndex = enc.dequeueOutputBuffer(bufferInfo, 10_000L) // 10ms timeout

                    if (outputIndex >= 0) {
                        val outputBuffer: ByteBuffer? = enc.getOutputBuffer(outputIndex)
                        if (outputBuffer != null && bufferInfo.size > 0) {
                            outputBuffer.position(bufferInfo.offset)
                            outputBuffer.limit(bufferInfo.offset + bufferInfo.size)

                            val chunk = ByteArray(bufferInfo.size)
                            outputBuffer.get(chunk)

                            val isCodecConfig = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                            val isKeyFrame = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0

                            if (isCodecConfig) {
                                // Cache SPS and PPS NAL units
                                cachedConfig = chunk
                                wsClient?.sendFrame(chunk, true)
                            } else {
                                if (isKeyFrame && cachedConfig != null && !containsConfig(chunk)) {
                                    // Prepend SPS/PPS if not embedded in IDR
                                    val fullFrame = ByteArray(cachedConfig!!.size + chunk.size)
                                    System.arraycopy(cachedConfig!!, 0, fullFrame, 0, cachedConfig!!.size)
                                    System.arraycopy(chunk, 0, fullFrame, cachedConfig!!.size, chunk.size)
                                    wsClient?.sendFrame(fullFrame, true)
                                } else {
                                    wsClient?.sendFrame(chunk, isKeyFrame)
                                }
                            }

                            framesCounter++
                            bytesCounter += chunk.size
                            fpsCounter++
                        }
                        enc.releaseOutputBuffer(outputIndex, false)
                    } else if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        val newFormat = enc.outputFormat
                        Log.d(TAG, "Encoder output format changed: $newFormat")
                    }
                } catch (e: Exception) {
                    if (isCapturing.get()) {
                        Log.e(TAG, "Exception in encoder drain loop: ${e.message}")
                    }
                    break
                }
            }
        }
    }

    private fun containsConfig(chunk: ByteArray): Boolean {
        // Quick check if SPS (type 7) is present in first 32 bytes
        for (i in 0 until minOf(chunk.size - 4, 32)) {
            if (chunk[i].toInt() == 0 && chunk[i + 1].toInt() == 0) {
                val nalStart = if (chunk[i + 2].toInt() == 1) i + 3 else if (chunk[i + 2].toInt() == 0 && chunk[i + 3].toInt() == 1) i + 4 else -1
                if (nalStart != -1 && nalStart < chunk.size) {
                    val nalType = chunk[nalStart].toInt() and 0x1F
                    if (nalType == 7) return true
                }
            }
        }
        return false
    }

    private fun startStatsReporter() {
        statsJob = serviceScope.launch {
            while (isActive && isCapturing.get()) {
                delay(1000)
                _statsFlow.value = _statsFlow.value.copy(
                    framesSent = framesCounter,
                    bytesSent = bytesCounter,
                    currentFps = fpsCounter,
                    isConnectedToServer = wsClient?.isConnected() == true
                )
                fpsCounter = 0
            }
        }
    }

    override fun onConnected() {
        Log.d(TAG, "WebSocket connected to backend")
        _statsFlow.value = _statsFlow.value.copy(isConnectedToServer = true)
        // Request keyframe so server/viewers get an immediate sync frame
        requestImmediateKeyframe()
    }

    override fun onDisconnected(reason: String) {
        Log.d(TAG, "WebSocket disconnected: $reason")
        _statsFlow.value = _statsFlow.value.copy(isConnectedToServer = false)
    }

    override fun onKeyframeRequested() {
        requestImmediateKeyframe()
    }

    override fun onError(error: String) {
        Log.e(TAG, "WebSocket error: $error")
        _statsFlow.value = _statsFlow.value.copy(
            isConnectedToServer = false,
            errorMsg = error
        )
    }

    private fun requestImmediateKeyframe() {
        try {
            val params = Bundle().apply {
                putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
            }
            encoder?.setParameters(params)
            Log.d(TAG, "Requested sync keyframe from hardware MediaCodec")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to request sync keyframe: ${e.message}")
        }
    }

    private fun setupWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "HoneyMo:CaptureWakeLock").apply {
            setReferenceCounted(false)
        }
    }

    private fun acquireWakeLock() {
        try {
            wakeLock?.let {
                if (!it.isHeld) {
                    it.acquire(12 * 60 * 60 * 1000L) // 12 hours max safety limit
                    Log.d(TAG, "WakeLock acquired")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error acquiring wake lock: ${e.message}")
        }
    }

    private fun releaseWakeLock() {
        try {
            wakeLock?.let {
                if (it.isHeld) {
                    it.release()
                    Log.d(TAG, "WakeLock released")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing wake lock: ${e.message}")
        }
    }

    private fun setupNetworkMonitoring() {
        connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        connectivityManager?.registerNetworkCallback(request, object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                Log.d(TAG, "Network became available")
                if (isCapturing.get() && wsClient?.isConnected() == false) {
                    Log.d(TAG, "Reconnecting WebSocket after network restored...")
                    wsClient?.connect()
                }
            }

            override fun onLost(network: Network) {
                Log.w(TAG, "Network connection lost")
                _statsFlow.value = _statsFlow.value.copy(isConnectedToServer = false)
            }
        })
    }

    private fun stopCapture() {
        if (!isCapturing.getAndSet(false)) return
        isRunning = false
        Log.d(TAG, "Stopping capture pipeline...")

        encodeJob?.cancel()
        encodeJob = null

        statsJob?.cancel()
        statsJob = null

        try {
            audioEncoder?.stop()
            audioEncoder = null
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping audioEncoder: ${e.message}")
        }

        try {
            virtualDisplay?.release()
            virtualDisplay = null
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing VirtualDisplay: ${e.message}")
        }

        try {
            encoder?.stop()
            encoder?.release()
            encoder = null
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing MediaCodec: ${e.message}")
        }

        try {
            inputSurface?.release()
            inputSurface = null
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing input surface: ${e.message}")
        }

        try {
            mediaProjection?.stop()
            mediaProjection = null
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping MediaProjection: ${e.message}")
        }

        // Clear active session flag on intentional stop
        SessionPreferences.setRecordingActive(applicationContext, false)

        wsClient?.disconnect()
        wsClient = null

        releaseWakeLock()

        _statsFlow.value = StreamStats(
            isStreaming = false,
            isConnectedToServer = false
        )
        Log.d(TAG, "Capture pipeline stopped")
    }

    override fun onDestroy() {
        stopCapture()
        instance = null
        serviceScope.cancel()
        super.onDestroy()
    }
}
