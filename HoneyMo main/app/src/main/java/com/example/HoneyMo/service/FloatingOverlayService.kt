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
import kotlinx.coroutines.launch

class FloatingOverlayService : Service(), SavedStateRegistryOwner {

    companion object {
        const val ACTION_START = "OVERLAY_START"
        const val ACTION_STOP = "OVERLAY_STOP"
    }

    private lateinit var windowManager: WindowManager
    private var composeView: ComposeView? = null
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var voiceCommandManager: VoiceCommandManager? = null

    private val lifecycleRegistry = LifecycleRegistry(this)
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
            val wsClient = ScreenCaptureService.instance?.wsClient
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
            else WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
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

                val (icon, color) = when {
                    isListening || agentStatus == "LISTENING" -> "👂" to Color.Green
                    agentStatus == "THINKING" -> "🤔" to Color.Yellow
                    agentStatus == "EXECUTING" -> "⚡" to Color(0xFFF59E0B) // Orange
                    agentStatus == "SPEAKING" -> "🔊" to Color.Blue
                    else -> "🎙️" to Color.White
                }

                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(64.dp)
                        .background(Color(0x88000000), CircleShape)
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
                    Text(
                        text = icon,
                        fontSize = 32.sp,
                        color = color
                    )
                }
            }
        }

        windowManager.addView(composeView, params)
    }

    override fun onDestroy() {
        super.onDestroy()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        composeView?.let {
            windowManager.removeView(it)
        }
        scope.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
