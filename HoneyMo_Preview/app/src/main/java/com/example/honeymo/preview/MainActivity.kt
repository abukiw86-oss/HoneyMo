package com.example.honeymo.preview

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.example.honeymo.preview.data.DeviceInfo
import com.example.honeymo.preview.network.PreviewClient
import com.example.honeymo.preview.ui.DeviceListScreen
import com.example.honeymo.preview.ui.StreamPlayerScreen

// Fixed Relay Server Endpoint (Hidden from UI)
private const val FIXED_SERVER_URL = "wss://honeymo-relay-server.onrender.com"

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Color(0xFF10B981),
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
                    HoneyMoPreviewApp()
                }
            }
        }
    }
}

@Composable
fun HoneyMoPreviewApp() {
    var devices by remember { mutableStateOf<List<DeviceInfo>>(emptyList()) }
    var isConnectedToServer by remember { mutableStateOf(false) }
    var selectedDevice by remember { mutableStateOf<DeviceInfo?>(null) }
    var client: PreviewClient? by remember { mutableStateOf(null) }

    fun refreshConnection() {
        client?.disconnect()

        val newClient = PreviewClient(
            baseUrl = FIXED_SERVER_URL,
            listener = object : PreviewClient.PreviewListener {
                override fun onDeviceListUpdated(newList: List<DeviceInfo>) {
                    devices = newList
                    // If currently viewed device disconnected, return to list
                    if (selectedDevice != null && newList.none { it.id == selectedDevice?.id }) {
                        selectedDevice = null
                    }
                }

                override fun onIconStateChanged(deviceId: String, isIconVisible: Boolean) {
                    if (selectedDevice?.id == deviceId) {
                        selectedDevice = selectedDevice?.copy(isIconVisible = isIconVisible)
                    }
                }

                override fun onFrameReceived(chunk: ByteArray) {}

                override fun onConnected() {
                    isConnectedToServer = true
                }

                override fun onDisconnected(reason: String) {
                    isConnectedToServer = false
                }

                override fun onError(error: String) {
                    isConnectedToServer = false
                }
            }
        )

        client = newClient
        newClient.fetchDevices()
        newClient.connect()
    }

    LaunchedEffect(Unit) {
        refreshConnection()
    }

    DisposableEffect(Unit) {
        onDispose {
            client?.disconnect()
        }
    }

    if (selectedDevice != null) {
        BackHandler {
            selectedDevice = null
        }

        StreamPlayerScreen(
            device = selectedDevice!!,
            serverUrl = FIXED_SERVER_URL,
            onBack = { selectedDevice = null }
        )
    } else {
        DeviceListScreen(
            devices = devices,
            isConnected = isConnectedToServer,
            onRefresh = { refreshConnection() },
            onSelectDevice = { dev -> selectedDevice = dev }
        )
    }
}
