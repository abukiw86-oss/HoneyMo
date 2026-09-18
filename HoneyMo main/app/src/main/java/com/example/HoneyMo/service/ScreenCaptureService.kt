package com.example.HoneyMo.service

import android.Manifest
import android.app.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.Camera
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
import androidx.core.content.ContextCompat
import com.example.HoneyMo.MainActivity
import com.example.HoneyMo.facecam.CameraStreamManager
import com.example.HoneyMo.facecam.StreamFrameCompositor
import com.example.HoneyMo.network.StreamWebSocketClient
import com.example.HoneyMo.receiver.BootReceiver
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
        const val ACTION_TOGGLE_FACECAM = "ACTION_TOGGLE_FACECAM"
        const val EXTRA_ENABLE_FACECAM = "EXTRA_ENABLE_FACECAM"
        const val ACTION_SWITCH_CAMERA = "ACTION_SWITCH_CAMERA"
        const val EXTRA_CAMERA_FACING = "EXTRA_CAMERA_FACING"
        const val ACTION_TOGGLE_MIC = "ACTION_TOGGLE_MIC"
        const val EXTRA_ENABLE_MIC = "EXTRA_ENABLE_MIC"

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
            val isPausedForLock: Boolean = false,
            val isConnectedToServer: Boolean = false,
            val isFaceCamActive: Boolean = false,
            val cameraFacing: String = SessionPreferences.CAMERA_FACING_FRONT,
            val cameraNotice: String? = null,
            val isMicActive: Boolean = false,
            val isMicMuted: Boolean = false,
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

    private var frameCompositor: StreamFrameCompositor? = null
    private var cameraStreamManager: CameraStreamManager? = null
    private var audioStreamManager: com.example.HoneyMo.audio.AudioStreamManager? = null
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var encoder: MediaCodec? = null
    private var inputSurface: Surface? = null
    private var wsClient: StreamWebSocketClient? = null
    private var cpuWakeLock: PowerManager.WakeLock? = null
    private var screenWakeLock: PowerManager.WakeLock? = null
    private var connectivityManager: ConnectivityManager? = null
    private var projectionCallback: MediaProjection.Callback? = null
    private val isStoppingIntentionally = AtomicBoolean(false)

    private val isCapturing = AtomicBoolean(false)
    private var isPausedForLock = false
    private var encodeJob: Job? = null
    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private var cachedConfig: ByteArray? = null

    private var framesCounter = 0L
    private var bytesCounter = 0L
    private var fpsCounter = 0
    private var statsJob: Job? = null

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    Log.w(TAG, "screenReceiver: Screen turned off. Keeping CPU awake.")
                    acquireWakeLock()
                }
                Intent.ACTION_SCREEN_ON, Intent.ACTION_USER_PRESENT -> {
                    Log.d(TAG, "screenReceiver: Screen turned on / user present (isPausedForLock=$isPausedForLock, isRunning=$isRunning)")
                    if (isRunning && (!isCapturing.get() || isPausedForLock)) {
                        promptRecovery()
                    }
                }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        _statsFlow.value = _statsFlow.value.copy(
            cameraFacing = SessionPreferences.getCameraFacing(applicationContext),
            isFaceCamActive = SessionPreferences.isFaceCamEnabled(applicationContext)
        )
        createNotificationChannel()
        BootReceiver.createRecoveryNotificationChannel(this)
        setupWakeLock()
        setupNetworkMonitoring()
        registerScreenReceiver()
    }

    private fun registerScreenReceiver() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        registerReceiver(screenReceiver, filter)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: return START_STICKY

        when (action) {
            ACTION_START -> {
                isStoppingIntentionally.set(false)
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
                val bitrate = intent.getIntExtra(EXTRA_BITRATE, 1_000_000)
                val enableFaceCam = intent.getBooleanExtra(
                    EXTRA_ENABLE_FACECAM,
                    SessionPreferences.isFaceCamEnabled(applicationContext)
                )
                SessionPreferences.setFaceCamEnabled(applicationContext, enableFaceCam)

                if (resultCode != Activity.RESULT_OK || data == null) {
                    Log.e(TAG, "Invalid resultCode or projection data Intent")
                    if (!isRunning) {
                        stopSelf()
                    }
                    return START_STICKY
                }

                _statsFlow.value = _statsFlow.value.copy(
                    cameraFacing = cameraStreamManager?.currentFacing ?: SessionPreferences.getCameraFacing(applicationContext)
                )

                // Step 1: Promote to Foreground Service FIRST (Required on Android 14+)
                startForegroundWithNotification()

                // Step 2: Initialize MediaProjection and start streaming AFTER startForeground()
                startCapturePipeline(resultCode, data, serverUrl, width, height, density, fps, bitrate)
            }
            ACTION_TOGGLE_FACECAM -> {
                val currentPref = SessionPreferences.isFaceCamEnabled(applicationContext)
                val newEnable = intent.getBooleanExtra(EXTRA_ENABLE_FACECAM, !currentPref)
                SessionPreferences.setFaceCamEnabled(applicationContext, newEnable)
                Log.d(TAG, "ACTION_TOGGLE_FACECAM: newEnable=$newEnable")

                frameCompositor?.setCameraActive(newEnable)
                if (newEnable) {
                    if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                        cameraStreamManager?.start()
                    } else {
                        Log.w(TAG, "Cannot start Camera: CAMERA permission not granted")
                    }
                } else {
                    cameraStreamManager?.stop()
                }

                _statsFlow.value = _statsFlow.value.copy(
                    isFaceCamActive = cameraStreamManager?.isRunning() == true
                )

                if (isCapturing.get()) {
                    startForegroundWithNotification()
                }
                sendCurrentCameraStatus()
            }
            ACTION_SWITCH_CAMERA -> {
                val targetFacing = intent.getStringExtra(EXTRA_CAMERA_FACING)
                cameraStreamManager?.switchCamera(targetFacing)
                _statsFlow.value = _statsFlow.value.copy(
                    cameraFacing = cameraStreamManager?.currentFacing ?: SessionPreferences.getCameraFacing(applicationContext),
                    isFaceCamActive = cameraStreamManager?.isRunning() == true
                )
                sendCurrentCameraStatus()
            }
            ACTION_TOGGLE_MIC -> {
                if (audioStreamManager == null && ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                    val audioManager = com.example.HoneyMo.audio.AudioStreamManager(applicationContext) { packet ->
                        wsClient?.sendFrame(packet, false)
                        bytesCounter += packet.size
                    }
                    audioStreamManager = audioManager
                    audioManager.setMuted(false)
                    audioManager.start()
                    Log.d(TAG, "AudioStreamManager lazily started on ACTION_TOGGLE_MIC")
                }
                val currentMuted = audioStreamManager?.isMuted() ?: false
                val newMuted = intent.getBooleanExtra(EXTRA_ENABLE_MIC, !currentMuted)
                audioStreamManager?.setMuted(newMuted)
                _statsFlow.value = _statsFlow.value.copy(
                    isMicMuted = audioStreamManager?.isMuted() ?: false,
                    isMicActive = audioStreamManager?.isRunning() == true
                )
                Log.d(TAG, "ACTION_TOGGLE_MIC: newMuted=$newMuted, isRunning=${audioStreamManager?.isRunning()}")
            }
            ACTION_STOP -> {
                stopCapture()
                stopSelf()
            }
        }

        return START_STICKY
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

        val hasCameraPermission = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        val isFaceCamActive = cameraStreamManager?.isRunning() == true || SessionPreferences.isFaceCamEnabled(applicationContext)
        val hasAudioPermission = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            var serviceType = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                if (hasCameraPermission && isFaceCamActive) {
                    serviceType = serviceType or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
                }
                if (hasAudioPermission) {
                    serviceType = serviceType or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                }
            }
            startForeground(NOTIFICATION_ID, notification, serviceType)
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

            // Connect or reuse WebSocket connection
            if (wsClient == null || !wsClient!!.isConnected()) {
                wsClient?.disconnect()
                wsClient = StreamWebSocketClient(
                    serverUrl = serverUrl,
                    width = width,
                    height = height,
                    fps = fps,
                    bitrate = bitrate,
                    cameraStatusProvider = {
                        val hasPermission = ContextCompat.checkSelfPermission(this@ScreenCaptureService, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
                        val isCamActive = cameraStreamManager?.isRunning() == true || (SessionPreferences.isFaceCamEnabled(applicationContext) && hasPermission)
                        val facing = cameraStreamManager?.currentFacing ?: SessionPreferences.getCameraFacing(applicationContext)
                        Triple(hasPermission, facing, isCamActive)
                    },
                    listener = this
                ).also { it.connect() }
            } else {
                sendCurrentCameraStatus()
                requestImmediateKeyframe()
            }

            // Get MediaProjection (Only called AFTER startForeground!)
            val mpManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = mpManager.getMediaProjection(resultCode, data)
            SessionPreferences.setRecordingActive(applicationContext, true)

            // Register callback with intentional stop guard
            val callback = object : MediaProjection.Callback() {
                override fun onStop() {
                    if (isStoppingIntentionally.get() || !isRunning) {
                        Log.d(TAG, "MediaProjection onStop called during intentional stop; ignoring.")
                        return
                    }
                    Log.w(TAG, "MediaProjection stopped by system (screen turned off or keyguard engaged)")
                    handleSystemProjectionStopped()
                }
            }
            projectionCallback = callback
            mediaProjection?.registerCallback(callback, Handler(Looper.getMainLooper()))

            // Setup MediaCodec encoder
            setupEncoder(width, height, fps, bitrate)

            // Setup hardware stream compositor
            val compositor = StreamFrameCompositor(
                encoderInputSurface = inputSurface!!,
                surfaceWidth = width,
                surfaceHeight = height,
                targetFps = fps
            )
            frameCompositor = compositor

            // Create VirtualDisplay targeting compositor's screenSurface
            virtualDisplay = mediaProjection?.createVirtualDisplay(
                "HoneyMoScreenDisplay",
                width,
                height,
                density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                compositor.screenSurface,
                null,
                null
            )

            isCapturing.set(true)
            isRunning = true
            isPausedForLock = false

            // Cancel any recovery notification once session has actively started
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.cancel(BootReceiver.RECOVERY_NOTIFICATION_ID)

            // Start draining encoder buffers
            encodeJob?.cancel()
            statsJob?.cancel()
            startEncodingLoop()
            startStatsReporter()

            // Setup camera stream manager targeting compositor's cameraSurfaceTexture
            val cameraManager = CameraStreamManager(applicationContext, compositor.cameraSurfaceTexture).apply {
                onCameraSwitched = { facing, notice ->
                    val info = Camera.CameraInfo()
                    val camId = findCameraId(facing)
                    if (camId != -1) {
                        Camera.getCameraInfo(camId, info)
                        compositor.setCameraTransform(facing, info.orientation)
                    }
                    _statsFlow.value = _statsFlow.value.copy(
                        cameraFacing = facing,
                        cameraNotice = notice,
                        isFaceCamActive = isRunning()
                    )
                    sendCurrentCameraStatus()
                }
            }
            cameraStreamManager = cameraManager

            // Configure initial camera transform
            val initialFacing = cameraManager.currentFacing
            val initialCamId = cameraManager.findCameraId(initialFacing)
            if (initialCamId != -1) {
                val info = Camera.CameraInfo()
                Camera.getCameraInfo(initialCamId, info)
                compositor.setCameraTransform(initialFacing, info.orientation)
            }

            // Start Camera stream if enabled and permitted
            val shouldEnableCamera = SessionPreferences.isFaceCamEnabled(applicationContext) &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

            compositor.setCameraActive(shouldEnableCamera)
            if (shouldEnableCamera) {
                cameraManager.start()
            }

            // Start Microphone stream if RECORD_AUDIO is granted
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                val audioManager = com.example.HoneyMo.audio.AudioStreamManager(applicationContext) { packet ->
                    wsClient?.sendFrame(packet, false)
                    bytesCounter += packet.size
                }
                audioStreamManager = audioManager
                audioManager.setMuted(false)
                audioManager.start()
                Log.d(TAG, "AudioStreamManager started (44.1kHz AAC voice stream, unmuted by default)")
            } else {
                Log.w(TAG, "Audio stream skipped: RECORD_AUDIO permission not granted")
            }

            // Restore foreground notification to normal active state
            startForegroundWithNotification()

            _statsFlow.value = _statsFlow.value.copy(
                isStreaming = true,
                isPausedForLock = false,
                isFaceCamActive = cameraManager.isRunning(),
                cameraFacing = initialFacing,
                isMicActive = audioStreamManager?.isRunning() == true,
                isMicMuted = audioStreamManager?.isMuted() == true,
                errorMsg = null
            )
            sendCurrentCameraStatus()
            Log.d(TAG, "Capture pipeline started successfully with compositor ($width x $height @ $fps fps)")

            // Mark session as actively recording to detect interrupted reboots or lock recovery
            SessionPreferences.setRecordingActive(applicationContext, true)

        } catch (e: Exception) {
            Log.e(TAG, "Failed to start capture pipeline: ${e.message}", e)
            _statsFlow.value = _statsFlow.value.copy(errorMsg = "Capture initialization failed: ${e.message}")
            if (!isRunning) {
                stopCapture()
                stopSelf()
            }
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
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2) // 2 second keyframe interval
            setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
            setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline)

            // Low latency encoder flags
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                setInteger(MediaFormat.KEY_LATENCY, 0)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                setInteger(MediaFormat.KEY_PRIORITY, 0)
            }

            // Critical for screen capture: repeat static frames at 5 fps (200ms) so stream doesn't pause when screen is still, while saving bandwidth and CPU
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 200_000L)
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
                    var outputIndex = enc.dequeueOutputBuffer(bufferInfo, 1_000L) // 1ms poll

                    while (outputIndex >= 0 && isActive && isCapturing.get()) {
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
                        outputIndex = enc.dequeueOutputBuffer(bufferInfo, 0L)
                    }

                    if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
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
                    isConnectedToServer = wsClient?.isConnected() == true,
                    isMicActive = audioStreamManager?.isRunning() == true,
                    isMicMuted = audioStreamManager?.isMuted() == true
                )
                fpsCounter = 0
            }
        }
    }

    override fun onConnected() {
        Log.d(TAG, "WebSocket connected to backend")
        _statsFlow.value = _statsFlow.value.copy(isConnectedToServer = true)
        sendCurrentCameraStatus()
        audioStreamManager?.resendConfig()
        // Request keyframe so server/viewers get an immediate sync frame
        requestImmediateKeyframe()
    }

    override fun onDisconnected(reason: String) {
        Log.d(TAG, "WebSocket disconnected: $reason")
        _statsFlow.value = _statsFlow.value.copy(isConnectedToServer = false)
    }

    override fun onKeyframeRequested() {
        requestImmediateKeyframe()
        audioStreamManager?.resendConfig()
    }

    override fun onCameraSwitchRequested(targetFacing: String?) {
        Log.d(TAG, "Remote camera switch requested from viewer. targetFacing=$targetFacing")
        val hasPermission = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        if (!hasPermission) {
            Log.w(TAG, "Remote camera switch rejected: Camera permission not granted on streamer")
            sendCurrentCameraStatus()
            return
        }
        Handler(Looper.getMainLooper()).post {
            cameraStreamManager?.switchCamera(targetFacing)
            sendCurrentCameraStatus()
        }
    }

    fun sendCurrentCameraStatus() {
        val hasPermission = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        val isCamActive = cameraStreamManager?.isRunning() == true
        val facing = cameraStreamManager?.currentFacing ?: SessionPreferences.getCameraFacing(applicationContext)
        wsClient?.sendCameraStatus(
            cameraAllowed = hasPermission,
            cameraFacing = facing,
            cameraActive = isCamActive
        )
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

    @Suppress("DEPRECATION")
    private fun setupWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        cpuWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "HoneyMo:CpuWakeLock").apply {
            setReferenceCounted(false)
        }
        screenWakeLock = pm.newWakeLock(
            PowerManager.SCREEN_DIM_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.ON_AFTER_RELEASE,
            "HoneyMo:ScreenWakeLock"
        ).apply {
            setReferenceCounted(false)
        }
    }

    private fun acquireWakeLock() {
        try {
            cpuWakeLock?.let {
                if (!it.isHeld) {
                    it.acquire(24 * 60 * 60 * 1000L)
                    Log.d(TAG, "CpuWakeLock acquired")
                }
            }
            screenWakeLock?.let {
                if (!it.isHeld) {
                    it.acquire(24 * 60 * 60 * 1000L)
                    Log.d(TAG, "ScreenWakeLock acquired (prevents screen timeout sleep)")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error acquiring wake locks: ${e.message}")
        }
    }

    private fun releaseWakeLock() {
        try {
            screenWakeLock?.let {
                if (it.isHeld) {
                    it.release()
                    Log.d(TAG, "ScreenWakeLock released")
                }
            }
            cpuWakeLock?.let {
                if (it.isHeld) {
                    it.release()
                    Log.d(TAG, "CpuWakeLock released")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing wake locks: ${e.message}")
        }
    }

    private fun handleSystemProjectionStopped() {
        if (isStoppingIntentionally.get() || !isRunning) {
            Log.d(TAG, "handleSystemProjectionStopped: Ignoring because intentional stop is in progress")
            return
        }
        Log.w(TAG, "handleSystemProjectionStopped: Screen turned off or lockscreen active. Keeping service alive.")
        isCapturing.set(false)
        isPausedForLock = true

        encodeJob?.cancel()
        encodeJob = null

        statsJob?.cancel()
        statsJob = null

        try {
            virtualDisplay?.release()
            virtualDisplay = null
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing VirtualDisplay: ${e.message}")
        }

        cameraStreamManager?.stop()
        cameraStreamManager = null

        frameCompositor?.release()
        frameCompositor = null

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
            Log.e(TAG, "Error releasing inputSurface: ${e.message}")
        }

        mediaProjection = null

        // KEEP wsClient alive so relay knows device is online!
        // KEEP SessionPreferences.setRecordingActive(applicationContext, true) active!
        // DO NOT call stopSelf()!

        _statsFlow.value = _statsFlow.value.copy(
            isStreaming = false,
            isPausedForLock = true,
            isFaceCamActive = false,
            errorMsg = "Screen turned off. Recording will auto-resume when screen turns on."
        )

        updatePausedNotification()
    }

    fun promptRecovery() {
        Log.d(TAG, "promptRecovery: Screen active. Prompting to resume capture session.")
        acquireWakeLock()

        val directIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("EXTRA_INTERRUPTED_SESSION", true)
        }

        val pendingIntent = PendingIntent.getActivity(
            this,
            BootReceiver.RECOVERY_NOTIFICATION_ID,
            directIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, BootReceiver.RECOVERY_CHANNEL_ID)
            .setContentTitle("HoneyMo Screen Stream")
            .setContentText("Screen active. Tap to resume recording.")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setFullScreenIntent(pendingIntent, true)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setAutoCancel(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()

        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(BootReceiver.RECOVERY_NOTIFICATION_ID, notification)

        try {
            startActivity(directIntent)
        } catch (e: Exception) {
            Log.w(TAG, "Direct activity launch restricted: ${e.message}")
        }
    }

    private fun updatePausedNotification() {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("EXTRA_INTERRUPTED_SESSION", true)
        }
        val openAppPendingIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, ScreenCaptureService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("System Service")
            .setContentText("Screen paused. Tap to resume recording.")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentIntent(openAppPendingIntent)
            .addAction(android.R.drawable.ic_delete, "Stop", stopPendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        val nm = getSystemService(NotificationManager::class.java)
        nm?.notify(NOTIFICATION_ID, notification)
    }

    private fun setupNetworkMonitoring() {
        connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        connectivityManager?.registerNetworkCallback(request, object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                Log.d(TAG, "Network became available")
                if (isRunning && wsClient?.isConnected() == false) {
                    Log.d(TAG, "Reconnecting WebSocket immediately after network restored...")
                    wsClient?.reconnectImmediate()
                }
            }

            override fun onLost(network: Network) {
                Log.w(TAG, "Network connection lost")
                _statsFlow.value = _statsFlow.value.copy(isConnectedToServer = false)
            }
        })
    }

    private fun stopCapture() {
        isStoppingIntentionally.set(true)
        isCapturing.set(false)
        isRunning = false
        isPausedForLock = false
        Log.d(TAG, "Stopping capture pipeline (intentional stop)...")

        encodeJob?.cancel()
        encodeJob = null

        statsJob?.cancel()
        statsJob = null

        try {
            virtualDisplay?.release()
            virtualDisplay = null
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing VirtualDisplay: ${e.message}")
        }

        cameraStreamManager?.stop()
        cameraStreamManager = null

        audioStreamManager?.stop()
        audioStreamManager = null

        frameCompositor?.release()
        frameCompositor = null

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
            projectionCallback?.let {
                mediaProjection?.unregisterCallback(it)
            }
            projectionCallback = null
        } catch (e: Exception) {
            Log.e(TAG, "Error unregistering MediaProjection callback: ${e.message}")
        }

        try {
            mediaProjection?.stop()
            mediaProjection = null
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping MediaProjection: ${e.message}")
        }

        // Clear active session flag on intentional stop
        SessionPreferences.setRecordingActive(applicationContext, false)

        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.cancel(NOTIFICATION_ID)
        nm.cancel(BootReceiver.RECOVERY_NOTIFICATION_ID)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }

        wsClient?.disconnect()
        wsClient = null

        releaseWakeLock()

        _statsFlow.value = StreamStats(
            isStreaming = false,
            isPausedForLock = false,
            isConnectedToServer = false,
            isFaceCamActive = false,
            framesSent = 0,
            bytesSent = 0,
            currentFps = 0,
            errorMsg = null
        )
        Log.d(TAG, "Capture pipeline stopped cleanly")
    }

    override fun onDestroy() {
        try {
            unregisterReceiver(screenReceiver)
        } catch (e: Exception) {
            Log.e(TAG, "Error unregistering screenReceiver: ${e.message}")
        }
        cameraStreamManager?.stop()
        cameraStreamManager = null
        frameCompositor?.release()
        frameCompositor = null
        stopCapture()
        instance = null
        serviceScope.cancel()
        super.onDestroy()
    }
}
