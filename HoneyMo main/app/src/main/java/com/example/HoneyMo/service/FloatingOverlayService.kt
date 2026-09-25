package com.example.HoneyMo.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.example.HoneyMo.agent.VoiceCommandManager
import com.example.HoneyMo.util.SessionPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * Persistent floating overlay service that shows a small mic-button orb on top of all apps.
 * The orb colour reflects Jarvis agent state (IDLE / LISTENING / THINKING / EXECUTING / SPEAKING).
 * Tapping the orb starts / stops voice command capture.
 *
 * Implements LifecycleOwner + SavedStateRegistryOwner so ComposeView works outside an Activity.
 */
class FloatingOverlayService : Service(), LifecycleOwner, SavedStateRegistryOwner {

    companion object {
        const val ACTION_START = "OVERLAY_START"
        const val ACTION_STOP  = "OVERLAY_STOP"
    }

    private lateinit var windowManager: WindowManager
    private var composeView: ComposeView? = null
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var voiceCommandManager: VoiceCommandManager? = null

    // ---- LifecycleOwner implementation ----
    private val lifecycleRegistry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = lifecycleRegistry

    // ---- SavedStateRegistryOwner implementation ----
    private val savedStateRegistryController = SavedStateRegistryController.create(this)
    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateRegistryController.savedStateRegistry

    override fun onCreate() {
        super.onCreate()
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        if (composeView == null) {
            // VoiceCommandManager needs the shared wsClient — obtain it via the public getter
            val wsClient = ScreenCaptureService.instance?.getWsClient()
            if (wsClient != null) {
                voiceCommandManager = VoiceCommandManager(this, wsClient)
            }
            showOverlay()
        }
        return START_STICKY
    }

    private fun showOverlay() {
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = 16
            y = 200
        }

        composeView = ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@FloatingOverlayService)
            setViewTreeSavedStateRegistryOwner(this@FloatingOverlayService)
            setContent {
                val agentStatus = ScreenCaptureService.agentStatusFlow.collectAsState().value
                val isListening = voiceCommandManager?.isListening?.collectAsState()?.value == true

                val (icon, bgColor) = when {
                    isListening || agentStatus == "LISTENING" -> "👂" to Color(0xFF1B4332)
                    agentStatus == "THINKING"                 -> "🤔" to Color(0xFF3B2A00)
                    agentStatus == "EXECUTING"                -> "⚡" to Color(0xFF3D1F00)
                    agentStatus == "SPEAKING"                 -> "🔊" to Color(0xFF00204D)
                    else                                      -> "🎙️" to Color(0x88000000)
                }

                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(64.dp)
                        .background(bgColor, CircleShape)
                        .clickable {
                            if (SessionPreferences.isAgentEnabled(context)) {
                                if (isListening) {
                                    voiceCommandManager?.stopListening()
                                } else {
                                    voiceCommandManager?.startListening()
                                }
                            }
                        }
                ) {
                    Text(text = icon, fontSize = 28.sp)
                }
            }
        }

        windowManager.addView(composeView, params)
    }

    override fun onDestroy() {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        composeView?.let {
            try { windowManager.removeView(it) } catch (_: Exception) {}
        }
        composeView = null
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
