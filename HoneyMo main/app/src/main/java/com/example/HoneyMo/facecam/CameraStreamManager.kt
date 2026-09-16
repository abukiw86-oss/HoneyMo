package com.example.HoneyMo.facecam

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.hardware.Camera
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.example.HoneyMo.util.SessionPreferences
import java.util.concurrent.atomic.AtomicBoolean

/**
 * CameraStreamManager captures camera frames (front selfie or back main)
 * directly into the OpenGL SurfaceTexture provided by StreamFrameCompositor.
 *
 * It requires ONLY standard Manifest.permission.CAMERA runtime permission.
 * No SYSTEM_ALERT_WINDOW / "Appear on top" permission is ever needed.
 * Includes automatic hardware fallback if either camera lens fails.
 */
@Suppress("DEPRECATION")
class CameraStreamManager(
    private val context: Context,
    private val surfaceTexture: SurfaceTexture
) {

    companion object {
        private const val TAG = "CameraStreamManager"
    }

    private var camera: Camera? = null
    var cameraId: Int = -1
        private set

    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val isRunning = AtomicBoolean(false)

    var currentFacing: String = SessionPreferences.getCameraFacing(context)
        private set

    var onCameraSwitched: ((facing: String, notice: String?) -> Unit)? = null

    fun isRunning(): Boolean = isRunning.get()

    fun start() {
        if (isRunning.get()) return

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "Cannot start Camera: CAMERA permission not granted")
            return
        }

        try {
            startBackgroundThread()
            isRunning.set(true)
            backgroundHandler?.post {
                openCamera(surfaceTexture, allowFallback = true)
            }
            Log.d(TAG, "CameraStreamManager started for facing: $currentFacing")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start CameraStreamManager: ${e.message}", e)
            stop()
        }
    }

    fun stop() {
        if (!isRunning.getAndSet(false)) return

        try {
            backgroundHandler?.post {
                closeCameraInternal()
            }
            stopBackgroundThread()
            Log.d(TAG, "CameraStreamManager stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping CameraStreamManager: ${e.message}")
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
        Log.d(TAG, "switchCamera requested: target=$newFacing")

        if (isRunning.get()) {
            backgroundHandler?.post {
                closeCameraInternal()
                openCamera(surfaceTexture, allowFallback = true)
            }
        } else {
            onCameraSwitched?.invoke(currentFacing, null)
        }
    }

    fun findCameraId(facingStr: String): Int {
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

    private fun openCamera(st: SurfaceTexture, allowFallback: Boolean = true) {
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
            val defaultSize = parameters.previewSize
            val supportedSizes = parameters.supportedPreviewSizes
            Log.d(TAG, "Camera ($currentFacing, ID $targetCameraId) default size: ${defaultSize.width}x${defaultSize.height}, supported: " + supportedSizes?.joinToString { "${it.width}x${it.height}" })

            if (!supportedSizes.isNullOrEmpty()) {
                val optimalSize = findOptimalPreviewSize(supportedSizes)
                parameters.setPreviewSize(optimalSize.width, optimalSize.height)
                st.setDefaultBufferSize(optimalSize.width, optimalSize.height)
                Log.d(TAG, "Camera ($currentFacing, ID $targetCameraId) preview size set to: ${optimalSize.width}x${optimalSize.height}")
            } else {
                st.setDefaultBufferSize(defaultSize.width, defaultSize.height)
            }

            // Focus mode
            val supportedFocusModes = parameters.supportedFocusModes
            if (supportedFocusModes?.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE) == true) {
                parameters.focusMode = Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE
            } else if (supportedFocusModes?.contains(Camera.Parameters.FOCUS_MODE_AUTO) == true) {
                parameters.focusMode = Camera.Parameters.FOCUS_MODE_AUTO
            }

            cam.parameters = parameters

            // Set preview texture and start capture
            cam.setPreviewTexture(st)
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

                // Attempt fallback to the other camera
                openCamera(st, allowFallback = false)
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

    private fun findOptimalPreviewSize(choices: List<Camera.Size>): Camera.Size {
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

    private fun startBackgroundThread() {
        if (backgroundThread == null) {
            backgroundThread = HandlerThread("CameraStreamBg").apply {
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
