package com.example.HoneyMo.facecam

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Outline
import android.graphics.PixelFormat
import android.graphics.SurfaceTexture
import android.graphics.drawable.GradientDrawable
import android.hardware.Camera
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.*
import android.widget.FrameLayout
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.example.HoneyMo.util.SessionPreferences
import java.util.concurrent.atomic.AtomicBoolean

@Suppress("DEPRECATION")
class FaceCamOverlayManager(private val context: Context) {

    companion object {
        private const val TAG = "FaceCamOverlayManager"
    }

    private val windowManager: WindowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private var overlayView: FrameLayout? = null
    private var textureView: TextureView? = null

    private var camera: Camera? = null
    private var cameraId: Int = -1
    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val isRunning = AtomicBoolean(false)

    var currentFacing: String = SessionPreferences.getCameraFacing(context)
        private set

    var onCameraSwitched: ((facing: String, notice: String?) -> Unit)? = null

    fun isShowing(): Boolean = isRunning.get()

    fun show() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { show() }
            return
        }

        if (isRunning.get()) return

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "Cannot show FaceCam: CAMERA permission not granted")
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(context)) {
            Log.w(TAG, "Cannot show FaceCam: SYSTEM_ALERT_WINDOW permission not granted")
            return
        }

        try {
            startBackgroundThread()
            createOverlayView()
            isRunning.set(true)
            Log.d(TAG, "FaceCam overlay attached to screen (bottom-left corner, 1/4th screen width)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to show FaceCam overlay: ${e.message}", e)
            hide()
        }
    }

    fun hide() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { hide() }
            return
        }

        if (!isRunning.getAndSet(false) && overlayView == null) return

        try {
            closeCamera()
            stopBackgroundThread()

            overlayView?.let { view ->
                if (view.isAttachedToWindow) {
                    windowManager.removeView(view)
                }
            }
            overlayView = null
            textureView = null
            Log.d(TAG, "FaceCam overlay removed and camera closed")
        } catch (e: Exception) {
            Log.e(TAG, "Error while hiding FaceCam: ${e.message}")
        }
    }

    fun switchCamera(targetFacing: String? = null) {
        val newFacing = targetFacing ?: if (currentFacing == SessionPreferences.CAMERA_FACING_FRONT) {
            SessionPreferences.CAMERA_FACING_BACK
        } else {
            SessionPreferences.CAMERA_FACING_FRONT
        }
        currentFacing = newFacing
        SessionPreferences.setCameraFacing(context, newFacing)
        Log.d(TAG, "switchCamera called: target=$newFacing")

        if (isRunning.get() && textureView?.surfaceTexture != null) {
            backgroundHandler?.post {
                closeCameraInternal()
                openCamera(textureView!!.surfaceTexture!, allowFallback = true)
            }
        } else {
            onCameraSwitched?.invoke(currentFacing, null)
        }
    }

    private fun createOverlayView() {
        val displayMetrics = context.resources.displayMetrics
        val screenWidth = displayMetrics.widthPixels

        // 1/4th of the screen width as explicitly required
        val faceCamWidth = screenWidth / 4
        // 4:3 portrait selfie aspect ratio
        val faceCamHeight = (faceCamWidth * 4) / 3

        val params = WindowManager.LayoutParams(
            faceCamWidth,
            faceCamHeight,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                WindowManager.LayoutParams.TYPE_PHONE
            },
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            // Position at Left Bottom above screen stream
            gravity = Gravity.BOTTOM or Gravity.START
            x = (16 * displayMetrics.density).toInt()
            y = (24 * displayMetrics.density).toInt()
        }

        val container = FrameLayout(context).apply {
            val cornerRadius = 14 * displayMetrics.density
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, cornerRadius)
                }
            }
            clipToOutline = true
            elevation = 16 * displayMetrics.density
            background = GradientDrawable().apply {
                this.cornerRadius = cornerRadius
                setColor(Color.BLACK)
                setStroke((2 * displayMetrics.density).toInt(), Color.parseColor("#6366F1"))
            }
        }

        val texture = TextureView(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                    backgroundHandler?.post {
                        openCamera(surface, allowFallback = true)
                    }
                }

                override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {}

                override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                    closeCamera()
                    return true
                }

                override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}
            }
        }

        // Draggable touch listener with double-tap gesture to flip cameras
        container.setOnTouchListener(object : View.OnTouchListener {
            private var initialX = 0
            private var initialY = 0
            private var initialTouchX = 0f
            private var initialTouchY = 0f
            private var lastTapTime = 0L

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = params.x
                        initialY = params.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY

                        val now = System.currentTimeMillis()
                        if (now - lastTapTime < 300) {
                            // Double tap to flip camera
                            switchCamera()
                        }
                        lastTapTime = now
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        params.x = initialX + (event.rawX - initialTouchX).toInt()
                        params.y = initialY - (event.rawY - initialTouchY).toInt()
                        try {
                            windowManager.updateViewLayout(container, params)
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to update overlay position: ${e.message}")
                        }
                        return true
                    }
                }
                return false
            }
        })

        container.addView(texture)
        overlayView = container
        textureView = texture

        windowManager.addView(container, params)
    }

    private fun openCamera(surfaceTexture: SurfaceTexture, allowFallback: Boolean = true) {
        try {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                Log.w(TAG, "Camera permission missing during openCamera")
                return
            }

            val targetCameraId = findCameraId(currentFacing)
            if (targetCameraId == -1) {
                throw IllegalStateException("No camera available for facing: $currentFacing")
            }

            cameraId = targetCameraId
            val cam = Camera.open(targetCameraId) ?: run {
                throw IllegalStateException("Failed to open camera ID $targetCameraId ($currentFacing)")
            }
            camera = cam

            val parameters = cam.parameters
            val supportedSizes = parameters.supportedPreviewSizes
            if (!supportedSizes.isNullOrEmpty()) {
                val optimalSize = findOptimalPreviewSize(supportedSizes)
                parameters.setPreviewSize(optimalSize.width, optimalSize.height)
                surfaceTexture.setDefaultBufferSize(optimalSize.width, optimalSize.height)
                Log.d(TAG, "Camera ($currentFacing, ID $targetCameraId) preview size set to: ${optimalSize.width}x${optimalSize.height}")
            }

            // Focus mode
            val supportedFocusModes = parameters.supportedFocusModes
            if (supportedFocusModes?.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE) == true) {
                parameters.focusMode = Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE
            } else if (supportedFocusModes?.contains(Camera.Parameters.FOCUS_MODE_AUTO) == true) {
                parameters.focusMode = Camera.Parameters.FOCUS_MODE_AUTO
            }

            cam.parameters = parameters

            // Display orientation (portrait mode upright)
            val cameraInfo = Camera.CameraInfo()
            Camera.getCameraInfo(targetCameraId, cameraInfo)
            val displayOrientation = if (cameraInfo.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) {
                (360 - cameraInfo.orientation) % 360
            } else {
                (cameraInfo.orientation) % 360
            }
            cam.setDisplayOrientation(displayOrientation)

            cam.setPreviewTexture(surfaceTexture)
            cam.startPreview()
            Log.d(TAG, "Camera preview started successfully for ($currentFacing, ID $targetCameraId)")
            onCameraSwitched?.invoke(currentFacing, null)

        } catch (e: Exception) {
            Log.e(TAG, "Error opening camera for ($currentFacing): ${e.message}", e)
            closeCameraInternal()

            if (allowFallback) {
                val failedFacing = currentFacing
                val fallbackFacing = if (failedFacing == SessionPreferences.CAMERA_FACING_FRONT) {
                    SessionPreferences.CAMERA_FACING_BACK
                } else {
                    SessionPreferences.CAMERA_FACING_FRONT
                }
                currentFacing = fallbackFacing
                SessionPreferences.setCameraFacing(context, fallbackFacing)
                val notice = "Camera ($failedFacing) failed. Automatically switched to ($fallbackFacing)."
                Log.w(TAG, notice)

                mainHandler.post {
                    Toast.makeText(context, notice, Toast.LENGTH_SHORT).show()
                }
                onCameraSwitched?.invoke(fallbackFacing, notice)

                // Attempt fallback to the working camera
                openCamera(surfaceTexture, allowFallback = false)
            } else {
                val failNotice = "Both front and back cameras failed to start: ${e.message}"
                Log.e(TAG, failNotice)
                mainHandler.post {
                    Toast.makeText(context, failNotice, Toast.LENGTH_SHORT).show()
                }
                onCameraSwitched?.invoke(currentFacing, failNotice)
            }
        }
    }

    private fun findCameraId(facingStr: String): Int {
        val targetFacing = if (facingStr == SessionPreferences.CAMERA_FACING_BACK) {
            Camera.CameraInfo.CAMERA_FACING_BACK
        } else {
            Camera.CameraInfo.CAMERA_FACING_FRONT
        }
        val numCameras = Camera.getNumberOfCameras()
        val cameraInfo = Camera.CameraInfo()
        for (i in 0 until numCameras) {
            Camera.getCameraInfo(i, cameraInfo)
            if (cameraInfo.facing == targetFacing) {
                return i
            }
        }
        return if (numCameras > 0) 0 else -1
    }

    private fun findOptimalPreviewSize(choices: List<Camera.Size>): Camera.Size {
        // Preferred standard 4:3 or 16:9 preview resolutions for facecam overlay
        val preferred = choices.firstOrNull { it.width == 640 && it.height == 480 }
            ?: choices.firstOrNull { it.width == 720 && it.height == 480 }
            ?: choices.firstOrNull { it.width == 1280 && it.height == 720 }
            ?: choices.firstOrNull { it.width == 800 && it.height == 480 }
            ?: choices.firstOrNull { it.width == 960 && it.height == 720 }

        if (preferred != null) return preferred

        return choices.minByOrNull { size ->
            val ratio = size.width.toDouble() / size.height.toDouble()
            Math.abs(ratio - (4.0 / 3.0)) * 1000 + Math.abs(size.width - 640)
        } ?: choices[0]
    }

    private fun closeCameraInternal() {
        try {
            camera?.let { cam ->
                cam.stopPreview()
                cam.setPreviewTexture(null)
                cam.release()
            }
        } catch (e: Exception) {
            Log.d(TAG, "Exception closing camera: ${e.message}")
        } finally {
            camera = null
            cameraId = -1
        }
    }

    private fun closeCamera() {
        backgroundHandler?.post {
            closeCameraInternal()
        }
    }

    private fun startBackgroundThread() {
        if (backgroundThread == null) {
            backgroundThread = HandlerThread("FaceCamBackground").apply {
                start()
                backgroundHandler = Handler(looper)
            }
        }
    }

    private fun stopBackgroundThread() {
        backgroundThread?.quitSafely()
        try {
            backgroundThread?.join(500)
            backgroundThread = null
            backgroundHandler = null
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping background thread: ${e.message}")
        }
    }
}
