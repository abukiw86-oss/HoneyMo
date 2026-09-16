package com.example.honeymo.preview.ui

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.example.honeymo.preview.audio.AudioPlayer
import com.example.honeymo.preview.data.DeviceInfo
import com.example.honeymo.preview.decoder.H264Decoder
import com.example.honeymo.preview.network.PreviewClient
import com.example.honeymo.preview.record.StreamRecorder
import com.example.honeymo.preview.util.ScreenshotHelper
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.text.SimpleDateFormat
import java.util.*

data class ActivityLog(
    val time: String,
    val message: String,
    val type: String // "info", "success", "warn", "error"
)

@Composable
fun StreamPlayerScreen(
    device: DeviceInfo,
    serverUrl: String,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    var liveFps by remember { mutableIntStateOf(0) }
    var livePing by remember { mutableLongStateOf(-1L) }
    var framesCount by remember { mutableLongStateOf(0L) }
    var bytesCount by remember { mutableLongStateOf(0L) }
    var connectionState by remember { mutableStateOf("Connecting...") }

    var isFullscreen by remember { mutableStateOf(false) }
    var showFullscreenControls by remember { mutableStateOf(true) }

    var isRecording by remember { mutableStateOf(false) }
    var recordingSeconds by remember { mutableIntStateOf(0) }
    var isMuted by remember { mutableStateOf(false) }

    var surfaceViewRef by remember { mutableStateOf<SurfaceView?>(null) }
    val activityLogs = remember { mutableStateListOf<ActivityLog>() }

    fun addLog(msg: String, type: String = "info") {
        val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        val time = timeFormat.format(Date())
        activityLogs.add(ActivityLog(time, msg, type))
    }

    // Audio & Video Pipeline
    val audioPlayer = remember { AudioPlayer() }
    val streamRecorder = remember { StreamRecorder() }

    val decoder = remember {
        H264Decoder(
            width = device.width,
            height = device.height,
            onFpsUpdated = { fps -> liveFps = fps }
        )
    }

    val previewClient = remember {
        PreviewClient(
            baseUrl = serverUrl,
            listener = object : PreviewClient.PreviewListener {
                override fun onDeviceListUpdated(devices: List<DeviceInfo>) {}

                override fun onFrameReceived(chunk: ByteArray) {
                    bytesCount += chunk.size

                    // Check for AAC ADTS syncword (0xFF 0xF...)
                    val isAudio = chunk.size >= 7 && (chunk[0].toInt() and 0xFF) == 0xFF && (chunk[1].toInt() and 0xF0) == 0xF0

                    if (isAudio) {
                        audioPlayer.feedAdtsFrame(chunk)
                        streamRecorder.onAudioFrame(chunk)
                    } else {
                        framesCount++
                        decoder.feedFrame(chunk)
                        streamRecorder.onVideoFrame(chunk)
                    }
                }

                override fun onConnected() {
                    connectionState = "Live"
                    addLog("Connected to stream relay", "success")
                }

                override fun onDisconnected(reason: String) {
                    connectionState = "Disconnected"
                    livePing = -1L
                    addLog("Relay disconnected: $reason", "warn")
                }

                override fun onError(error: String) {
                    connectionState = "Error"
                    livePing = -1L
                    addLog("Connection error: $error", "error")
                }

                override fun onPingUpdated(pingMs: Long) {
                    livePing = pingMs
                }
            }
        )
    }

    // Storage permission launcher for Android 9 / legacy environments
    var pendingStorageAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    val storagePermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            pendingStorageAction?.invoke()
        } else {
            Toast.makeText(context, "Storage permission is required to save media", Toast.LENGTH_SHORT).show()
        }
        pendingStorageAction = null
    }

    fun executeWithStorageCheck(action: () -> Unit) {
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            val hasPerm = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED

            if (!hasPerm) {
                pendingStorageAction = action
                storagePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                return
            }
        }
        action()
    }

    // Timer for recording duration
    LaunchedEffect(isRecording) {
        if (isRecording) {
            recordingSeconds = 0
            while (isActive) {
                delay(1000)
                recordingSeconds++
            }
        } else {
            recordingSeconds = 0
        }
    }

    // Fullscreen system bars controller
    val activity = context as? Activity
    DisposableEffect(isFullscreen) {
        activity?.window?.let { window ->
            val controller = WindowCompat.getInsetsController(window, window.decorView)
            if (isFullscreen) {
                controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                controller.hide(WindowInsetsCompat.Type.systemBars())
            } else {
                controller.show(WindowInsetsCompat.Type.systemBars())
            }
        }
        onDispose {
            activity?.window?.let { window ->
                val controller = WindowCompat.getInsetsController(window, window.decorView)
                controller.show(WindowInsetsCompat.Type.systemBars())
            }
        }
    }

    // Exit fullscreen on hardware back press
    BackHandler(enabled = isFullscreen) {
        isFullscreen = false
    }

    DisposableEffect(Unit) {
        addLog("Initialized stream for ${device.name}", "info")
        audioPlayer.start()

        onDispose {
            if (streamRecorder.isRecording()) {
                streamRecorder.stop(context)
            }
            audioPlayer.release()
            previewClient.disconnect()
            decoder.release()
        }
    }

    fun handleScreenshot() {
        executeWithStorageCheck {
            val sv = surfaceViewRef
            if (sv != null) {
                ScreenshotHelper.captureSurface(
                    surfaceView = sv,
                    context = context,
                    onSuccess = { fileName, _ ->
                        addLog("📸 Screenshot saved: $fileName", "success")
                        Toast.makeText(context, "📸 Screenshot saved to Pictures/HoneyMo", Toast.LENGTH_SHORT).show()
                    },
                    onError = { err ->
                        addLog("Screenshot error: $err", "error")
                        Toast.makeText(context, "Screenshot failed: $err", Toast.LENGTH_SHORT).show()
                    }
                )
            } else {
                Toast.makeText(context, "Video display is initializing...", Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun handleToggleRecord() {
        executeWithStorageCheck {
            if (!isRecording) {
                val started = streamRecorder.start(
                    context = context,
                    videoWidth = device.width,
                    videoHeight = device.height
                )
                if (started) {
                    isRecording = true
                    addLog("🔴 Started video & voice recording...", "warn")
                    Toast.makeText(context, "🔴 Recording started (Video + Audio)", Toast.LENGTH_SHORT).show()
                } else {
                    addLog("Failed to start recorder", "error")
                    Toast.makeText(context, "Failed to start recorder", Toast.LENGTH_SHORT).show()
                }
            } else {
                val (uri, fileName) = streamRecorder.stop(context)
                isRecording = false
                if (uri != null && fileName != null) {
                    addLog("🎬 Saved recording: $fileName (${recordingSeconds}s)", "success")
                    Toast.makeText(context, "🎬 Recording saved to Movies/HoneyMo", Toast.LENGTH_LONG).show()
                } else {
                    addLog("Recording cancelled (no video frames)", "warn")
                    Toast.makeText(context, "Recording stopped (no frames)", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    fun handleToggleMute() {
        isMuted = !isMuted
        audioPlayer.setMuted(isMuted)
        addLog(if (isMuted) "Audio muted" else "Audio unmuted", "info")
    }

    val aspectRatio = if (device.height > 0) device.width.toFloat() / device.height.toFloat() else 9f / 16f

    // -------------------------------------------------------------
    // FULLSCREEN VIEW MODE
    // -------------------------------------------------------------
    if (isFullscreen) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() }
                ) {
                    showFullscreenControls = !showFullscreenControls
                },
            contentAlignment = Alignment.Center
        ) {
            AndroidView(
                factory = { ctx ->
                    SurfaceView(ctx).apply {
                        surfaceViewRef = this
                        holder.addCallback(object : SurfaceHolder.Callback {
                            override fun surfaceCreated(holder: SurfaceHolder) {
                                decoder.start(holder.surface)
                                previewClient.connect(device.id)
                            }
                            override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, height: Int) {}
                            override fun surfaceDestroyed(holder: SurfaceHolder) {
                                decoder.stop()
                                previewClient.disconnect()
                            }
                        })
                    }
                },
                modifier = Modifier
                    .fillMaxSize()
                    .aspectRatio(aspectRatio, matchHeightConstraintsFirst = false)
            )

            // Fullscreen Overlay Controls
            AnimatedVisibility(
                visible = showFullscreenControls,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.fillMaxSize()
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0x77000000))
                        .padding(16.dp)
                ) {
                    // Top Bar in Fullscreen
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .align(Alignment.TopCenter),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Button(
                            onClick = { isFullscreen = false },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0x991E293B)),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text("⛶ Exit Fullscreen", color = Color.White, fontSize = 12.sp)
                        }

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            if (livePing >= 0) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(20.dp))
                                        .background(Color(0x990F172A))
                                        .padding(horizontal = 10.dp, vertical = 6.dp)
                                ) {
                                    Text(
                                        text = "⚡ $livePing ms",
                                        color = if (livePing < 100) Color(0xFF10B981) else if (livePing < 250) Color(0xFFF59E0B) else Color(0xFFEF4444),
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 11.sp
                                    )
                                }
                            }

                            // Recording indicator
                            if (isRecording) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(20.dp))
                                        .background(Color(0xCCEF4444))
                                        .padding(horizontal = 12.dp, vertical = 6.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(10.dp)
                                            .clip(CircleShape)
                                            .background(Color.White)
                                    )
                                    Text(
                                        text = "REC %02d:%02d".format(recordingSeconds / 60, recordingSeconds % 60),
                                        color = Color.White,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 12.sp
                                    )
                                }
                            }
                        }
                    }

                    // Bottom Floating Action Controls
                    Row(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Button(
                            onClick = {
                                previewClient.requestKeyframe()
                                addLog("Requested sync frame", "info")
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xCC334155)),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Text("🔄", fontSize = 14.sp)
                        }

                        Button(
                            onClick = { handleToggleMute() },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isMuted) Color(0xCCEF4444) else Color(0xCC334155)
                            ),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Text(if (isMuted) "🔇" else "🔊", fontSize = 14.sp)
                        }

                        Button(
                            onClick = { handleScreenshot() },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xCC3B82F6)),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Text("📸 Shot", color = Color.White, fontSize = 12.sp)
                        }

                        Button(
                            onClick = { handleToggleRecord() },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isRecording) Color(0xCCEF4444) else Color(0xCC10B981)
                            ),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Text(
                                text = if (isRecording) "⏹️ Stop (${recordingSeconds}s)" else "🔴 Record",
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp
                            )
                        }
                    }
                }
            }
        }
        return
    }

    // -------------------------------------------------------------
    // WEBPAGE STYLE DASHBOARD VIEW MODE
    // -------------------------------------------------------------
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0F172A))
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // 1. Header (Matches Webpage Navbar)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(Color(0xFF1E293B))
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                IconButton(
                    onClick = onBack,
                    modifier = Modifier.size(36.dp)
                ) {
                    Text("←", fontSize = 22.sp, color = Color.White, fontWeight = FontWeight.Bold)
                }

                Column {
                    Text(
                        text = device.name,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    Text(
                        text = "Real-Time Screen & Voice Relay",
                        fontSize = 11.sp,
                        color = Color(0xFF94A3B8)
                    )
                }
            }

            // Status Badge
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .background(if (connectionState == "Live") Color(0x2610B981) else Color(0x26EF4444))
                    .padding(horizontal = 10.dp, vertical = 4.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(if (connectionState == "Live") Color(0xFF10B981) else Color(0xFFEF4444))
                )
                Text(
                    text = if (connectionState == "Live" && livePing >= 0) "LIVE • ${livePing}ms" else connectionState.uppercase(),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (connectionState == "Live") Color(0xFF10B981) else Color(0xFFEF4444)
                )
            }
        }

        // 2. Metrics Grid (Matches Webpage .metrics-grid)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            MetricCard(
                title = "Framerate",
                value = "$liveFps fps",
                subtitle = "Target: ${device.fps} fps",
                modifier = Modifier.weight(1f)
            )
            MetricCard(
                title = "Latency / Ping",
                value = if (livePing >= 0) "$livePing ms" else "-- ms",
                subtitle = "WebSocket RTT",
                modifier = Modifier.weight(1f)
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            MetricCard(
                title = "Transferred",
                value = "%.1f MB".format(bytesCount / (1024f * 1024f)),
                subtitle = "$framesCount frames",
                modifier = Modifier.weight(1f)
            )
            MetricCard(
                title = "Resolution",
                value = "${device.width} x ${device.height}",
                subtitle = "Aspect 540p",
                modifier = Modifier.weight(1f)
            )
        }

        // 3. Video Player Container
        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFF000000)),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 340.dp, max = 480.dp)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black),
                contentAlignment = Alignment.Center
            ) {
                AndroidView(
                    factory = { ctx ->
                        SurfaceView(ctx).apply {
                            surfaceViewRef = this
                            holder.addCallback(object : SurfaceHolder.Callback {
                                override fun surfaceCreated(holder: SurfaceHolder) {
                                    decoder.start(holder.surface)
                                    previewClient.connect(device.id)
                                }
                                override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, height: Int) {}
                                override fun surfaceDestroyed(holder: SurfaceHolder) {
                                    decoder.stop()
                                    previewClient.disconnect()
                                }
                            })
                        }
                    },
                    modifier = Modifier
                        .fillMaxSize()
                        .aspectRatio(aspectRatio, matchHeightConstraintsFirst = false)
                )

                // Recording indicator pill over video
                if (isRecording) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(12.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(Color(0xCCEF4444))
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(Color.White)
                        )
                        Text(
                            text = "%02d:%02d".format(recordingSeconds / 60, recordingSeconds % 60),
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp
                        )
                    }
                }
            }
        }

        // 4. Player Controls Bar (Matches Webpage .player-controls)
        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "Stream Controls",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color(0xFF94A3B8)
                )

                // Controls Row 1
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    // Sync Keyframe
                    Button(
                        onClick = {
                            previewClient.requestKeyframe()
                            addLog("Sent REQUEST_KEYFRAME to ${device.name}", "info")
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF334155)),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(vertical = 8.dp)
                    ) {
                        Text("🔄 Sync", fontSize = 11.sp, color = Color.White)
                    }

                    // Fullscreen
                    Button(
                        onClick = { isFullscreen = true },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6366F1)),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(vertical = 8.dp)
                    ) {
                        Text("⛶ Full", fontSize = 11.sp, color = Color.White)
                    }
                }

                // Controls Row 2
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    // Mute / Unmute
                    Button(
                        onClick = { handleToggleMute() },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isMuted) Color(0xFFEF4444) else Color(0xFF334155)
                        ),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.weight(1.1f),
                        contentPadding = PaddingValues(vertical = 8.dp)
                    ) {
                        Text(if (isMuted) "🔇 Mute" else "🔊 Sound", fontSize = 11.sp, color = Color.White)
                    }

                    // Screenshot
                    Button(
                        onClick = { handleScreenshot() },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3B82F6)),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(vertical = 8.dp)
                    ) {
                        Text("📸 Shot", fontSize = 11.sp, color = Color.White)
                    }

                    // Record
                    Button(
                        onClick = { handleToggleRecord() },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isRecording) Color(0xFFEF4444) else Color(0xFF10B981)
                        ),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.weight(1.3f),
                        contentPadding = PaddingValues(vertical = 8.dp)
                    ) {
                        Text(
                            text = if (isRecording) "⏹️ Stop" else "🔴 Record",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }
                }
            }
        }

        // 5. Activity Log (Matches Webpage .logs-card)
        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Activity Log",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    TextButton(
                        onClick = { activityLogs.clear() },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                    ) {
                        Text("Clear", fontSize = 11.sp, color = Color(0xFF94A3B8))
                    }
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(110.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFF090D16))
                        .padding(8.dp)
                ) {
                    if (activityLogs.isEmpty()) {
                        Text(
                            text = "No logs yet",
                            color = Color(0xFF64748B),
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    } else {
                        val listState = rememberLazyListState()
                        LaunchedEffect(activityLogs.size) {
                            if (activityLogs.isNotEmpty()) {
                                listState.animateScrollToItem(activityLogs.size - 1)
                            }
                        }

                        LazyColumn(state = listState, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            items(activityLogs) { log ->
                                val color = when (log.type) {
                                    "success" -> Color(0xFF10B981)
                                    "warn" -> Color(0xFFF59E0B)
                                    "error" -> Color(0xFFEF4444)
                                    else -> Color(0xFF3B82F6)
                                }
                                Text(
                                    text = "[${log.time}] ${log.message}",
                                    color = color,
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun MetricCard(
    title: String,
    value: String,
    subtitle: String,
    modifier: Modifier = Modifier
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
        shape = RoundedCornerShape(12.dp),
        modifier = modifier
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = title.uppercase(),
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.5.sp,
                color = Color(0xFF94A3B8)
            )
            Text(
                text = value,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
            Text(
                text = subtitle,
                fontSize = 10.sp,
                color = Color(0xFF64748B)
            )
        }
    }
}
