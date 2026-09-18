package com.example.HoneyMo

import android.Manifest
import android.app.Activity
import android.app.KeyguardManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.HoneyMo.receiver.BootReceiver
import com.example.HoneyMo.service.ScreenCaptureService
import com.example.HoneyMo.util.SessionPreferences
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// Constant Stream Configuration
private const val FIXED_SERVER_URL = "wss://honeymo-relay-server.onrender.com/ws/device"
private const val FIXED_FPS = 15
private const val FIXED_BITRATE = 1_000_000 // 1 Mbps (lightweight, non-fluctuating)

class MainActivity : ComponentActivity() {

    private var isBootLaunch by mutableStateOf(false)
    private var isInterruptedSession by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Allow display over lock screen and wake up screen on boot or incoming notification
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
            val km = getSystemService(KeyguardManager::class.java)
            km?.requestDismissKeyguard(this, null)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
            )
        }

        isBootLaunch = intent?.getBooleanExtra("EXTRA_BOOT_LAUNCH", false) == true
        isInterruptedSession = intent?.getBooleanExtra("EXTRA_INTERRUPTED_SESSION", false) == true ||
            SessionPreferences.wasRecordingActive(this)

        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Color(0xFF6366F1),
                    secondary = Color(0xFF10B981),
                    background = Color(0xFF0F172A),
                    surface = Color(0xFF1E293B),
                    onBackground = Color(0xFFF8FAFC),
                    onSurface = Color(0xFFF8FAFC)
                )
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    ScreenCaptureApp(
                        autoStartPrompt = isBootLaunch,
                        isInterrupted = isInterruptedSession,
                        onResetInterrupted = { isInterruptedSession = false }
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra("EXTRA_BOOT_LAUNCH", false)) {
            isBootLaunch = true
        }
        if (intent.getBooleanExtra("EXTRA_INTERRUPTED_SESSION", false) || SessionPreferences.wasRecordingActive(this)) {
            isInterruptedSession = true
        }
    }
}

@Composable
fun ScreenCaptureApp(
    autoStartPrompt: Boolean = false,
    isInterrupted: Boolean = false,
    onResetInterrupted: () -> Unit = {}
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val stats by ScreenCaptureService.statsFlow.collectAsState()

    // Keep screen turned on while actively streaming
    val activity = context as? Activity
    DisposableEffect(stats.isStreaming) {
        if (stats.isStreaming) {
            activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose {
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    var showPermissionDeniedDialog by remember { mutableStateOf(false) }

    // Battery Optimization check
    var isBatteryOptIgnored by remember {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        mutableStateOf(pm.isIgnoringBatteryOptimizations(context.packageName))
    }

    // Camera permission for Selfie / Camera Stream (Composited directly on GPU - no overlay permission needed!)
    var hasCameraPermission by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    var isFaceCamEnabled by remember {
        mutableStateOf(SessionPreferences.isFaceCamEnabled(context))
    }

    // Microphone permission for Voice Streaming
    var hasAudioPermission by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
    }

    val audioPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        hasAudioPermission = isGranted
        if (isGranted) {
            Toast.makeText(context, "Microphone permission granted. Voice streaming ready.", Toast.LENGTH_SHORT).show()
            val isServiceActive = stats.isStreaming || ScreenCaptureService.isRunning || stats.isPausedForLock
            if (isServiceActive) {
                val intent = Intent(context, ScreenCaptureService::class.java).apply {
                    action = ScreenCaptureService.ACTION_TOGGLE_MIC
                    putExtra(ScreenCaptureService.EXTRA_ENABLE_MIC, false)
                }
                context.startService(intent)
            }
        } else {
            Toast.makeText(context, "Microphone permission is required for voice streaming", Toast.LENGTH_SHORT).show()
        }
    }

    fun toggleMicMute() {
        if (!hasAudioPermission) {
            audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        val currentMuted = stats.isMicMuted
        val newMuted = !currentMuted
        val isServiceActive = stats.isStreaming || ScreenCaptureService.isRunning || stats.isPausedForLock
        if (isServiceActive) {
            val intent = Intent(context, ScreenCaptureService::class.java).apply {
                action = ScreenCaptureService.ACTION_TOGGLE_MIC
                putExtra(ScreenCaptureService.EXTRA_ENABLE_MIC, newMuted)
            }
            context.startService(intent)
        }
        val msg = if (newMuted) "Microphone muted" else "Microphone unmuted (voice active)"
        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
    }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        hasCameraPermission = isGranted
        if (isGranted) {
            isFaceCamEnabled = true
            SessionPreferences.setFaceCamEnabled(context, true)
            Toast.makeText(context, "Camera permission granted. Camera stream enabled.", Toast.LENGTH_SHORT).show()

            val isServiceActive = stats.isStreaming || ScreenCaptureService.isRunning || stats.isPausedForLock
            if (isServiceActive) {
                val intent = Intent(context, ScreenCaptureService::class.java).apply {
                    action = ScreenCaptureService.ACTION_TOGGLE_FACECAM
                    putExtra(ScreenCaptureService.EXTRA_ENABLE_FACECAM, true)
                }
                context.startService(intent)
            }
        } else {
            ScreenCaptureService.instance?.sendCurrentCameraStatus()
            Toast.makeText(context, "Camera permission is required for Camera stream", Toast.LENGTH_SHORT).show()
        }
    }

    var selectedFacing by remember {
        mutableStateOf(SessionPreferences.getCameraFacing(context))
    }

    LaunchedEffect(stats.cameraFacing) {
        selectedFacing = stats.cameraFacing
    }

    fun switchCamera() {
        val newFacing = if (selectedFacing == SessionPreferences.CAMERA_FACING_FRONT) {
            SessionPreferences.CAMERA_FACING_BACK
        } else {
            SessionPreferences.CAMERA_FACING_FRONT
        }
        selectedFacing = newFacing
        SessionPreferences.setCameraFacing(context, newFacing)

        val isServiceActive = stats.isStreaming || ScreenCaptureService.isRunning || stats.isPausedForLock
        if (isServiceActive) {
            val intent = Intent(context, ScreenCaptureService::class.java).apply {
                action = ScreenCaptureService.ACTION_SWITCH_CAMERA
                putExtra(ScreenCaptureService.EXTRA_CAMERA_FACING, newFacing)
            }
            context.startService(intent)
        }
        val label = if (newFacing == SessionPreferences.CAMERA_FACING_BACK) "Back Camera (Main)" else "Front Camera (Selfie)"
        Toast.makeText(context, "Switched to $label", Toast.LENGTH_SHORT).show()
    }

    fun toggleFaceCam() {
        val newEnabled = !isFaceCamEnabled
        isFaceCamEnabled = newEnabled
        SessionPreferences.setFaceCamEnabled(context, newEnabled)

        val isServiceActive = stats.isStreaming || ScreenCaptureService.isRunning || stats.isPausedForLock
        if (isServiceActive) {
            val intent = Intent(context, ScreenCaptureService::class.java).apply {
                action = ScreenCaptureService.ACTION_TOGGLE_FACECAM
                putExtra(ScreenCaptureService.EXTRA_ENABLE_FACECAM, newEnabled)
            }
            context.startService(intent)
        }
        val msg = if (newEnabled) "Face Cam enabled" else "Face Cam disabled"
        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
    }

    // Screen capture permission launcher
    val captureLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            showPermissionDeniedDialog = false

            // Cancel any recovery notification once session has started
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.cancel(BootReceiver.RECOVERY_NOTIFICATION_ID)

            // Calculate scaled 540p resolution maintaining screen aspect ratio
            val metrics = context.resources.displayMetrics
            val screenWidth = metrics.widthPixels
            val screenHeight = metrics.heightPixels
            val isPortrait = screenHeight >= screenWidth

            val targetWidth: Int
            val targetHeight: Int

            if (isPortrait) {
                // Scale width to 540 and height proportionally
                val scale = 540f / screenWidth.toFloat()
                val rawH = (screenHeight * scale).toInt()
                targetWidth = (540 + 15) / 16 * 16 // 16-pixel aligned for AVC encoder
                targetHeight = (rawH + 15) / 16 * 16
            } else {
                // Scale height to 540 and width proportionally
                val scale = 540f / screenHeight.toFloat()
                val rawW = (screenWidth * scale).toInt()
                targetWidth = (rawW + 15) / 16 * 16
                targetHeight = (540 + 15) / 16 * 16
            }

            val serviceIntent = Intent(context, ScreenCaptureService::class.java).apply {
                action = ScreenCaptureService.ACTION_START
                putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(ScreenCaptureService.EXTRA_DATA, result.data)
                putExtra(ScreenCaptureService.EXTRA_SERVER_URL, FIXED_SERVER_URL)
                putExtra(ScreenCaptureService.EXTRA_WIDTH, targetWidth)
                putExtra(ScreenCaptureService.EXTRA_HEIGHT, targetHeight)
                putExtra(ScreenCaptureService.EXTRA_DENSITY, metrics.densityDpi)
                putExtra(ScreenCaptureService.EXTRA_FPS, FIXED_FPS)
                putExtra(ScreenCaptureService.EXTRA_BITRATE, FIXED_BITRATE)
                putExtra(ScreenCaptureService.EXTRA_ENABLE_FACECAM, isFaceCamEnabled && hasCameraPermission)
            }

            ContextCompat.startForegroundService(context, serviceIntent)
            Toast.makeText(context, "Streaming started (540p @ 15fps)", Toast.LENGTH_SHORT).show()
        } else {
            // User rejected or dismissed the screen capture dialog
            Toast.makeText(context, "Screen capture permission is required", Toast.LENGTH_SHORT).show()
            showPermissionDeniedDialog = true
        }
    }

    // Permissions launcher for Notifications & Microphone
    val permissionsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val audioGranted = grants[Manifest.permission.RECORD_AUDIO] ?: (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
        hasAudioPermission = audioGranted
        val mpManager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as android.media.projection.MediaProjectionManager
        captureLauncher.launch(mpManager.createScreenCaptureIntent())
    }

    fun startStreaming() {
        val permissionsToRequest = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(Manifest.permission.RECORD_AUDIO)
        }

        if (permissionsToRequest.isNotEmpty()) {
            permissionsLauncher.launch(permissionsToRequest.toTypedArray())
            return
        }

        val mpManager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as android.media.projection.MediaProjectionManager
        captureLauncher.launch(mpManager.createScreenCaptureIntent())
    }

    fun stopStreaming() {
        onResetInterrupted()
        showPermissionDeniedDialog = false
        SessionPreferences.setRecordingActive(context, false)
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.cancel(BootReceiver.RECOVERY_NOTIFICATION_ID)
        nm.cancel(ScreenCaptureService.NOTIFICATION_ID)
        val serviceIntent = Intent(context, ScreenCaptureService::class.java).apply {
            action = ScreenCaptureService.ACTION_STOP
        }
        context.startService(serviceIntent)
        Toast.makeText(context, "Screen streaming stopped", Toast.LENGTH_SHORT).show()
    }

    val isInterruptedSessionActive = isInterrupted || SessionPreferences.wasRecordingActive(context)
    var showRecoveryDialog by remember(isInterruptedSessionActive) {
        mutableStateOf(isInterruptedSessionActive && !stats.isStreaming)
    }

    LaunchedEffect(isInterruptedSessionActive, stats.isStreaming) {
        if (isInterruptedSessionActive && !stats.isStreaming) {
            showRecoveryDialog = true
        }
    }

    // Automatically trigger screen capture prompt ONLY if autoStartPrompt is true without interrupted state
    LaunchedEffect(autoStartPrompt) {
        if (autoStartPrompt && !isInterruptedSessionActive && !stats.isStreaming) {
            delay(350)
            startStreaming()
        }
    }

    // Trap hardware Back button when in interrupted recovery mode or showing dialog
    BackHandler(enabled = (isInterrupted || showRecoveryDialog || showPermissionDeniedDialog) && !stats.isStreaming) {
        if (showRecoveryDialog) {
            // Keep recovery dialog visible
        } else {
            showPermissionDeniedDialog = true
            coroutineScope.launch {
                delay(350)
                startStreaming()
            }
        }
    }

    // Explicit Recovery Dialogue shown after device reboot/unlock
    if (showRecoveryDialog && !stats.isStreaming) {
        AlertDialog(
            onDismissRequest = {
                // Keep showing until user selects an action
            },
            title = {
                Text(
                    text = "Resume Screen Recording",
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            },
            text = {
                Text(
                    text = "Screen recording was active before the device restarted. Tap 'Resume Recording' to continue streaming your screen.",
                    color = Color(0xFFCBD5E1)
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showRecoveryDialog = false
                        onResetInterrupted()
                        coroutineScope.launch {
                            delay(150)
                            startStreaming()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6366F1))
                ) {
                    Text("Resume Recording", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showRecoveryDialog = false
                        onResetInterrupted()
                        stopStreaming()
                    }
                ) {
                    Text("Stop", color = Color(0xFF94A3B8))
                }
            },
            containerColor = Color(0xFF1E293B)
        )
    }

    // Interactive loop dialog if user cancelled/rejected screen capture permission
    if (showPermissionDeniedDialog && !stats.isStreaming && !showRecoveryDialog) {
        AlertDialog(
            onDismissRequest = {
                // Re-prompt on dismiss with debounce delay
                showPermissionDeniedDialog = false
                coroutineScope.launch {
                    delay(350)
                    startStreaming()
                }
            },
            title = {
                Text(
                    text = "Screen Capture Required",
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            },
            text = {
                Text(
                    text = "HoneyMo requires screen capture permission to stream your device. Please tap 'Start Capturing' to grant permission.",
                    color = Color(0xFFCBD5E1)
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showPermissionDeniedDialog = false
                        coroutineScope.launch {
                            delay(350)
                            startStreaming()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6366F1))
                ) {
                    Text("Start Capturing", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showPermissionDeniedDialog = false
                        coroutineScope.launch {
                            delay(350)
                            startStreaming()
                        }
                    }
                ) {
                    Text("Retry", color = Color(0xFF94A3B8))
                }
            },
            containerColor = Color(0xFF1E293B)
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // App Header
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(vertical = 8.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFF6366F1)),
                contentAlignment = Alignment.Center
            ) {
                Text("📱", fontSize = 24.sp)
            }
            Column {
                Text(
                    text = "HoneyMo Streamer",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                Text(
                    text = "Hardware H.264 Screen Capture Client",
                    fontSize = 12.sp,
                    color = Color(0xFF94A3B8)
                )
            }
        }

        // Live Status Card
        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Stream Status", fontWeight = FontWeight.SemiBold, color = Color(0xFF94A3B8))

                    val badgeColor = when {
                        stats.isStreaming -> Color(0xFF10B981)
                        stats.isPausedForLock || ScreenCaptureService.isRunning -> Color(0xFFF59E0B)
                        else -> Color(0xFFEF4444)
                    }
                    val badgeBg = when {
                        stats.isStreaming -> Color(0x2610B981)
                        stats.isPausedForLock || ScreenCaptureService.isRunning -> Color(0x26F59E0B)
                        else -> Color(0x26EF4444)
                    }
                    val badgeText = when {
                        stats.isStreaming -> "STREAMING"
                        stats.isPausedForLock || ScreenCaptureService.isRunning -> "PAUSED (SCREEN OFF)"
                        else -> "IDLE"
                    }

                    // Status Badge
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier
                            .clip(RoundedCornerShape(20.dp))
                            .background(badgeBg)
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(badgeColor)
                        )
                        Text(
                            text = badgeText,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = badgeColor
                        )
                    }
                }

                // Telemetry metrics
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    MetricItem(label = "Server", value = if (stats.isConnectedToServer) "Connected" else "Offline")
                    MetricItem(label = "Live FPS", value = "${stats.currentFps} fps")
                    MetricItem(
                        label = "Camera",
                        value = if (stats.isFaceCamActive) {
                            if (selectedFacing == SessionPreferences.CAMERA_FACING_BACK) "Back (1/4)" else "Front (1/4)"
                        } else if (hasCameraPermission && isFaceCamEnabled) {
                            "Ready"
                        } else {
                            "Off"
                        }
                    )
                    MetricItem(
                        label = "Data Sent",
                        value = "%.1f MB".format(stats.bytesSent / (1024f * 1024f))
                    )
                }

                stats.errorMsg?.let { error ->
                    Text(
                        text = error,
                        color = Color(0xFFEF4444),
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }

        // Stream Preset Details (Read-only status info)
        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Stream Configuration",
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                    fontSize = 14.sp
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    MetricItem(label = "Resolution", value = "540p")
                    MetricItem(label = "Frame Rate", value = "15 FPS")
                    MetricItem(label = "Bitrate", value = "1.0 Mbps")
                }
            }
        }

        // Face Cam / Camera Control Card
        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(if (selectedFacing == SessionPreferences.CAMERA_FACING_BACK) "📷" else "🤳", fontSize = 18.sp)
                        Text(
                            text = "Camera Overlay",
                            fontWeight = FontWeight.SemiBold,
                            color = Color.White,
                            fontSize = 15.sp
                        )
                    }

                    if (hasCameraPermission) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            // Facing badge
                            val isBack = selectedFacing == SessionPreferences.CAMERA_FACING_BACK
                            val facingColor = if (isBack) Color(0xFFF59E0B) else Color(0xFF818CF8)
                            val facingBg = if (isBack) Color(0x26F59E0B) else Color(0x26818CF8)
                            Text(
                                text = if (isBack) "BACK" else "FRONT",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = facingColor,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(facingBg)
                                    .padding(horizontal = 8.dp, vertical = 3.dp)
                            )

                            // On/Off status badge
                            val badgeColor = if (isFaceCamEnabled) Color(0xFF10B981) else Color(0xFF94A3B8)
                            val badgeBg = if (isFaceCamEnabled) Color(0x2610B981) else Color(0x2694A3B8)
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                modifier = Modifier
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(badgeBg)
                                    .padding(horizontal = 8.dp, vertical = 3.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(6.dp)
                                        .clip(CircleShape)
                                        .background(badgeColor)
                                )
                                Text(
                                    text = if (isFaceCamEnabled) "ON" else "OFF",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = badgeColor
                                )
                            }
                        }
                    }
                }

                if (!hasCameraPermission) {
                    Text(
                        text = "Stream your front or back camera in the bottom-left corner (1/4th screen width) directly above your screen stream.",
                        color = Color(0xFF94A3B8),
                        fontSize = 13.sp
                    )
                    Button(
                        onClick = {
                            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6366F1)),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("ENABLE CAMERA (ALLOW PERMISSION)", fontWeight = FontWeight.Bold)
                    }
                } else {
                    Text(
                        text = if (isFaceCamEnabled) {
                            val camName = if (selectedFacing == SessionPreferences.CAMERA_FACING_BACK) "Back camera" else "Front selfie camera"
                            "$camName streams directly into video feed at 1/4th screen width (bottom-left) above screen stream. Zero overlay permissions needed. Auto-switches if one fails."
                        } else {
                            "Camera stream is turned off. Screen stream only."
                        },
                        color = Color(0xFF94A3B8),
                        fontSize = 13.sp
                    )

                    // Notice if camera was automatically switched due to failure
                    stats.cameraNotice?.let { notice ->
                        Card(
                            colors = CardDefaults.cardColors(containerColor = Color(0xFF2E2619)),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "ℹ️ $notice",
                                color = Color(0xFFFDE68A),
                                fontSize = 12.sp,
                                modifier = Modifier.padding(10.dp)
                            )
                        }
                    }

                    // Camera Switcher Button (Switch between Front / Selfie and Back / Main)
                    Button(
                        onClick = { switchCamera() },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4F46E5)),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = if (selectedFacing == SessionPreferences.CAMERA_FACING_FRONT) {
                                "🔄 SWITCH TO BACK CAMERA"
                            } else {
                                "🔄 SWITCH TO FRONT (SELFIE) CAMERA"
                            },
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }

                    // Toggle Button: "DISABLE CAMERA STREAM" when active, "ENABLE CAMERA STREAM" when disabled
                    Button(
                        onClick = {
                            if (!hasCameraPermission) {
                                cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                            } else {
                                toggleFaceCam()
                            }
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isFaceCamEnabled) Color(0xFF334155) else Color(0xFF10B981)
                        ),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = if (isFaceCamEnabled) "DISABLE CAMERA STREAM" else "ENABLE CAMERA STREAM (1/4th)",
                            fontWeight = FontWeight.Bold,
                            color = if (isFaceCamEnabled) Color(0xFFF8FAFC) else Color.White
                        )
                    }
                }
            }
        }

        // Voice Audio & Microphone Control Card
        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(if (stats.isMicMuted) "🔇" else "🎙️", fontSize = 18.sp)
                        Text(
                            text = "Voice Streaming (Mic)",
                            fontWeight = FontWeight.SemiBold,
                            color = Color.White,
                            fontSize = 15.sp
                        )
                    }

                    // Status badge
                    val micStatusText = when {
                        !hasAudioPermission -> "NO PERM"
                        stats.isMicMuted -> "MUTED"
                        stats.isMicActive -> "LIVE (44.1k)"
                        else -> "READY"
                    }
                    val micStatusColor = when {
                        !hasAudioPermission -> Color(0xFFF59E0B)
                        stats.isMicMuted -> Color(0xFFEF4444)
                        stats.isMicActive -> Color(0xFF10B981)
                        else -> Color(0xFF94A3B8)
                    }
                    val micStatusBg = when {
                        !hasAudioPermission -> Color(0x26F59E0B)
                        stats.isMicMuted -> Color(0x26EF4444)
                        stats.isMicActive -> Color(0x2610B981)
                        else -> Color(0x2694A3B8)
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(micStatusBg)
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(micStatusColor)
                        )
                        Text(
                            text = micStatusText,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = micStatusColor
                        )
                    }
                }

                if (!hasAudioPermission) {
                    Text(
                        text = "Grant Microphone Permission to stream live voice audio with your screen capture.",
                        color = Color(0xFF94A3B8),
                        fontSize = 13.sp
                    )
                    Button(
                        onClick = {
                            audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6366F1)),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("ENABLE MICROPHONE (ALLOW PERMISSION)", fontWeight = FontWeight.Bold)
                    }
                } else {
                    Text(
                        text = "Ultra-low latency 44.1 kHz AAC mono audio streaming (~32 kbps). Optimized for small connections with zero stutter.",
                        color = Color(0xFF94A3B8),
                        fontSize = 13.sp
                    )

                    // Mute / Unmute Button
                    Button(
                        onClick = { toggleMicMute() },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (stats.isMicMuted) Color(0xFF10B981) else Color(0xFFEF4444)
                        ),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = if (stats.isMicMuted) "🎙️ UNMUTE MICROPHONE (SEND VOICE)" else "🔇 MUTE MICROPHONE (SILENCE)",
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }
                }
            }
        }

        // Battery Optimization Warning Banner
        if (!isBatteryOptIgnored) {
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF2E2619)),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "⚠️ Battery Optimization Active",
                        color = Color(0xFFFBBF24),
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )
                    Text(
                        text = "Android may terminate continuous background capture unless battery optimization is disabled.",
                        color = Color(0xFFFDE68A),
                        fontSize = 12.sp
                    )
                    Button(
                        onClick = {
                            try {
                                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                                    data = Uri.parse("package:${context.packageName}")
                                }
                                context.startActivity(intent)
                                val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
                                isBatteryOptIgnored = pm.isIgnoringBatteryOptimizations(context.packageName)
                            } catch (e: Exception) {
                                Toast.makeText(context, "Cannot open battery settings directly", Toast.LENGTH_SHORT).show()
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF59E0B)),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.align(Alignment.End)
                    ) {
                        Text("Disable Optimization", color = Color.Black, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        Spacer(modifier = Modifier.weight(1f, fill = false))

        // Action Button (Start / Stop)
        val isServiceActive = stats.isStreaming || ScreenCaptureService.isRunning || stats.isPausedForLock
        Button(
            onClick = {
                if (isServiceActive) {
                    stopStreaming()
                } else {
                    startStreaming()
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (isServiceActive) Color(0xFFEF4444) else Color(0xFF6366F1)
            )
        ) {
            Text(
                text = if (isServiceActive) "STOP SCREEN SHARING" else "START SCREEN CAPTURE",
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
                color = Color.White
            )
        }
    }
}

@Composable
fun MetricItem(label: String, value: String) {
    Column {
        Text(label, fontSize = 11.sp, color = Color(0xFF94A3B8))
        Text(value, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.White)
    }
}
