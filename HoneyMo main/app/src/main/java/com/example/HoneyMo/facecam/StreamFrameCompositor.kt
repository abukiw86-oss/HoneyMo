package com.example.HoneyMo.facecam

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.Matrix
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import com.example.HoneyMo.util.SessionPreferences
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * StreamFrameCompositor composites the screen capture (from MediaProjection)
 * and the selfie / camera feed (from Camera HAL) directly on the GPU using
 * OpenGL ES 2.0 onto the MediaCodec input surface.
 *
 * This completely eliminates the need for SYSTEM_ALERT_WINDOW ("Appear on top" permission)
 * because no floating WindowManager view is required on the user's phone screen.
 * The stream received by the preview/viewers displays the screen with the camera
 * overlay composited at 1/4th screen width in the bottom-left corner.
 */
class StreamFrameCompositor(
    private val encoderInputSurface: Surface,
    private val surfaceWidth: Int,
    private val surfaceHeight: Int,
    private val targetFps: Int = 30
) {

    companion object {
        private const val TAG = "StreamCompositor"

        private const val VERTEX_SHADER_OES = """
            uniform mat4 uMVPMatrix;
            uniform mat4 uTexMatrix;
            attribute vec2 aPosition;
            attribute vec2 aTexCoord;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = uMVPMatrix * vec4(aPosition, 0.0, 1.0);
                vTexCoord = (uTexMatrix * vec4(aTexCoord, 0.0, 1.0)).xy;
            }
        """

        private const val FRAGMENT_SHADER_OES = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            uniform samplerExternalOES uTexture;
            varying vec2 vTexCoord;
            void main() {
                gl_FragColor = texture2D(uTexture, vTexCoord);
            }
        """

        private const val VERTEX_SHADER_COLOR = """
            uniform mat4 uMVPMatrix;
            attribute vec2 aPosition;
            void main() {
                gl_Position = uMVPMatrix * vec4(aPosition, 0.0, 1.0);
            }
        """

        private const val FRAGMENT_SHADER_COLOR = """
            precision mediump float;
            uniform vec4 uColor;
            void main() {
                gl_FragColor = uColor;
            }
        """
    }

    private val isRunning = AtomicBoolean(false)
    val isCameraActive = AtomicBoolean(true)

    // GL Thread
    private var glThread: HandlerThread? = null
    private var glHandler: Handler? = null

    // EGL Objects
    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE

    // Textures & Surfaces
    private var screenTexId: Int = 0
    private var cameraTexId: Int = 0

    private var screenSurfaceTexture: SurfaceTexture? = null
    private var _screenSurface: Surface? = null
    val screenSurface: Surface
        get() = _screenSurface ?: throw IllegalStateException("Screen Surface not initialized")

    private var _cameraSurfaceTexture: SurfaceTexture? = null
    val cameraSurfaceTexture: SurfaceTexture
        get() = _cameraSurfaceTexture ?: throw IllegalStateException("Camera SurfaceTexture not initialized")

    // Shader Programs
    private var texProgram: Int = 0
    private var uMVPLoc: Int = 0
    private var uTexMatrixLoc: Int = 0
    private var aPositionLoc: Int = 0
    private var aTexCoordLoc: Int = 0
    private var uTextureLoc: Int = 0

    private var colorProgram: Int = 0
    private var uColorMVPLoc: Int = 0
    private var aColorPositionLoc: Int = 0
    private var uColorLoc: Int = 0

    // Buffers & Matrices
    private lateinit var vertexBuffer: FloatBuffer
    private lateinit var texCoordBuffer: FloatBuffer

    private val screenTexMatrix = FloatArray(16)
    private val cameraTexMatrix = FloatArray(16)
    private val identityMVP = FloatArray(16)
    private val cameraMVP = FloatArray(16)

    // Sleek border color for camera PIP (HoneyMo Cyan / Teal accent #00D8F6)
    private val pipBorderColor = floatArrayOf(0.0f, 0.847f, 0.965f, 1.0f)

    // Frame synchronization
    private val hasNewScreenFrame = AtomicBoolean(false)
    private val hasNewCameraFrame = AtomicBoolean(false)
    private var hasRenderedFirstScreenFrame = false
    private var hasReceivedCameraFrame = false
    private var lastRenderTimeMs: Long = 0L
    private var lastPresentationTimeNs: Long = 0L
    private val frameIntervalMs = (1000L / targetFps).coerceAtLeast(16L)

    private val tickerRunnable = object : Runnable {
        override fun run() {
            if (!isRunning.get()) return

            val newScreen = hasNewScreenFrame.get()
            val newCamera = hasNewCameraFrame.get()

            if (newScreen || (newCamera && isCameraActive.get()) || (hasRenderedFirstScreenFrame && isCameraActive.get())) {
                renderFrame()
            }

            glHandler?.postDelayed(this, frameIntervalMs)
        }
    }

    init {
        Matrix.setIdentityM(identityMVP, 0)
        Matrix.setIdentityM(cameraMVP, 0)
        Matrix.setIdentityM(screenTexMatrix, 0)
        Matrix.setIdentityM(cameraTexMatrix, 0)

        setupBuffers()

        val initLatch = CountDownLatch(1)
        var initError: Throwable? = null

        val thread = HandlerThread("StreamCompositorGL").apply {
            start()
        }
        glThread = thread
        val handler = Handler(thread.looper)
        glHandler = handler

        handler.post {
            try {
                initGL()
                isRunning.set(true)
                handler.post(tickerRunnable)
                Log.d(TAG, "StreamFrameCompositor initialized ($surfaceWidth x $surfaceHeight @ $targetFps fps)")
            } catch (e: Throwable) {
                Log.e(TAG, "Failed to initialize StreamFrameCompositor: ${e.message}", e)
                initError = e
            } finally {
                initLatch.countDown()
            }
        }

        try {
            if (!initLatch.await(5, TimeUnit.SECONDS)) {
                throw RuntimeException("StreamFrameCompositor initialization timed out after 5s")
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw RuntimeException("StreamFrameCompositor initialization interrupted", e)
        }

        initError?.let {
            release()
            throw RuntimeException("StreamFrameCompositor GL initialization failed", it)
        }
    }

    private fun setupBuffers() {
        val quadVertices = floatArrayOf(
            -1.0f, -1.0f,
             1.0f, -1.0f,
            -1.0f,  1.0f,
             1.0f,  1.0f
        )
        vertexBuffer = ByteBuffer.allocateDirect(quadVertices.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply {
                put(quadVertices)
                position(0)
            }

        val quadTexCoords = floatArrayOf(
            0.0f, 0.0f,
            1.0f, 0.0f,
            0.0f, 1.0f,
            1.0f, 1.0f
        )
        texCoordBuffer = ByteBuffer.allocateDirect(quadTexCoords.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply {
                put(quadTexCoords)
                position(0)
            }
    }

    private fun initGL() {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (eglDisplay == EGL14.EGL_NO_DISPLAY) {
            throw RuntimeException("eglGetDisplay failed: ${EGL14.eglGetError()}")
        }

        val version = IntArray(2)
        if (!EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) {
            throw RuntimeException("eglInitialize failed: ${EGL14.eglGetError()}")
        }

        val configAttribs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGLExt.EGL_RECORDABLE_ANDROID, 1,
            EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfigs = IntArray(1)
        if (!EGL14.eglChooseConfig(eglDisplay, configAttribs, 0, configs, 0, 1, numConfigs, 0) || numConfigs[0] == 0) {
            throw RuntimeException("eglChooseConfig failed: ${EGL14.eglGetError()}")
        }
        val eglConfig = configs[0] ?: throw RuntimeException("No valid EGLConfig returned")

        val contextAttribs = intArrayOf(
            EGL14.EGL_CONTEXT_CLIENT_VERSION, 2,
            EGL14.EGL_NONE
        )
        eglContext = EGL14.eglCreateContext(eglDisplay, eglConfig, EGL14.EGL_NO_CONTEXT, contextAttribs, 0)
        if (eglContext == EGL14.EGL_NO_CONTEXT) {
            throw RuntimeException("eglCreateContext failed: ${EGL14.eglGetError()}")
        }

        val surfaceAttribs = intArrayOf(EGL14.EGL_NONE)
        eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, eglConfig, encoderInputSurface, surfaceAttribs, 0)
        if (eglSurface == EGL14.EGL_NO_SURFACE) {
            throw RuntimeException("eglCreateWindowSurface failed: ${EGL14.eglGetError()}")
        }

        if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
            throw RuntimeException("eglMakeCurrent failed: ${EGL14.eglGetError()}")
        }

        // Compile shaders
        texProgram = createProgram(VERTEX_SHADER_OES, FRAGMENT_SHADER_OES)
        uMVPLoc = GLES20.glGetUniformLocation(texProgram, "uMVPMatrix")
        uTexMatrixLoc = GLES20.glGetUniformLocation(texProgram, "uTexMatrix")
        aPositionLoc = GLES20.glGetAttribLocation(texProgram, "aPosition")
        aTexCoordLoc = GLES20.glGetAttribLocation(texProgram, "aTexCoord")
        uTextureLoc = GLES20.glGetUniformLocation(texProgram, "uTexture")

        colorProgram = createProgram(VERTEX_SHADER_COLOR, FRAGMENT_SHADER_COLOR)
        uColorMVPLoc = GLES20.glGetUniformLocation(colorProgram, "uMVPMatrix")
        aColorPositionLoc = GLES20.glGetAttribLocation(colorProgram, "aPosition")
        uColorLoc = GLES20.glGetUniformLocation(colorProgram, "uColor")

        // Create External OES Textures
        val textures = IntArray(2)
        GLES20.glGenTextures(2, textures, 0)
        screenTexId = textures[0]
        cameraTexId = textures[1]

        for (id in textures) {
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, id)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        }
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0)

        // Screen SurfaceTexture & Surface
        val screenSt = SurfaceTexture(screenTexId)
        screenSt.setDefaultBufferSize(surfaceWidth, surfaceHeight)
        screenSt.setOnFrameAvailableListener({
            hasNewScreenFrame.set(true)
            val now = SystemClock.elapsedRealtime()
            if (now - lastRenderTimeMs >= frameIntervalMs / 2) {
                glHandler?.post { renderFrame() }
            }
        }, glHandler)
        screenSurfaceTexture = screenSt
        _screenSurface = Surface(screenSt)

        // Camera SurfaceTexture
        val cameraSt = SurfaceTexture(cameraTexId)
        cameraSt.setDefaultBufferSize(640, 480)
        cameraSt.setOnFrameAvailableListener({
            hasNewCameraFrame.set(true)
        }, glHandler)
        _cameraSurfaceTexture = cameraSt
    }

    fun setCameraActive(active: Boolean) {
        isCameraActive.set(active)
        Log.d(TAG, "setCameraActive: $active")
        glHandler?.post {
            if (!active) {
                hasReceivedCameraFrame = false
            }
            renderFrame()
        }
    }

    fun setCameraTransform(facing: String, sensorOrientation: Int) {
        glHandler?.post {
            Matrix.setIdentityM(cameraMVP, 0)
            val isFront = (facing == SessionPreferences.CAMERA_FACING_FRONT)

            if (isFront) {
                Matrix.scaleM(cameraMVP, 0, -1f, 1f, 1f)
            }

            val rotationDegrees = if (isFront) {
                (360 - sensorOrientation) % 360
            } else {
                sensorOrientation % 360
            }

            Matrix.rotateM(cameraMVP, 0, rotationDegrees.toFloat(), 0f, 0f, 1f)
            Log.d(TAG, "Camera transform configured: facing=$facing, sensorOrientation=$sensorOrientation, appliedRotation=$rotationDegrees, mirror=$isFront")
        }
    }

    private fun renderFrame() {
        if (!isRunning.get() || eglDisplay == EGL14.EGL_NO_DISPLAY || eglSurface == EGL14.EGL_NO_SURFACE) {
            return
        }

        try {
            if (hasNewScreenFrame.getAndSet(false)) {
                try {
                    screenSurfaceTexture?.updateTexImage()
                    screenSurfaceTexture?.getTransformMatrix(screenTexMatrix)
                    hasRenderedFirstScreenFrame = true
                } catch (e: Exception) {
                    Log.w(TAG, "Error updating screen texture: ${e.message}")
                }
            }

            if (isCameraActive.get() && hasNewCameraFrame.getAndSet(false)) {
                try {
                    cameraSurfaceTexture?.updateTexImage()
                    cameraSurfaceTexture?.getTransformMatrix(cameraTexMatrix)
                    hasReceivedCameraFrame = true
                } catch (e: Exception) {
                    Log.w(TAG, "Error updating camera texture: ${e.message}")
                }
            }

            // Clear full viewport to black
            GLES20.glClearColor(0.0f, 0.0f, 0.0f, 1.0f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

            // 1. Draw Full Screen Frame
            if (hasRenderedFirstScreenFrame) {
                GLES20.glViewport(0, 0, surfaceWidth, surfaceHeight)
                drawTexture(screenTexId, screenTexMatrix, identityMVP)
            }

            // 2. Draw Camera PIP (1/4th screen width, bottom-left corner)
            if (isCameraActive.get() && hasReceivedCameraFrame) {
                val pipWidth = (surfaceWidth / 4).coerceAtLeast(120)
                val pipHeight = ((pipWidth * 4) / 3).coerceAtLeast(160)
                val margin = 24
                val borderWidth = 4

                // Draw PIP Border / Backdrop
                GLES20.glViewport(
                    (margin - borderWidth).coerceAtLeast(0),
                    (margin - borderWidth).coerceAtLeast(0),
                    pipWidth + 2 * borderWidth,
                    pipHeight + 2 * borderWidth
                )
                drawColorQuad(pipBorderColor)

                // Draw Camera Preview Texture
                GLES20.glViewport(margin, margin, pipWidth, pipHeight)
                drawTexture(cameraTexId, cameraTexMatrix, cameraMVP)
            }

            // 3. Set Monotonic Presentation Timestamp & Swap Buffers
            var nowNs = System.nanoTime()
            if (nowNs <= lastPresentationTimeNs) {
                nowNs = lastPresentationTimeNs + 1_000_000L // Ensure strictly ascending timestamp
            }
            lastPresentationTimeNs = nowNs
            lastRenderTimeMs = SystemClock.elapsedRealtime()

            EGLExt.eglPresentationTimeANDROID(eglDisplay, eglSurface, nowNs)
            if (!EGL14.eglSwapBuffers(eglDisplay, eglSurface)) {
                val err = EGL14.eglGetError()
                Log.w(TAG, "eglSwapBuffers returned false (EGL error: $err)")
            }

        } catch (e: Exception) {
            Log.e(TAG, "Exception during frame render: ${e.message}", e)
        }
    }

    private fun drawTexture(textureId: Int, texMatrix: FloatArray, mvpMatrix: FloatArray) {
        GLES20.glUseProgram(texProgram)

        GLES20.glUniformMatrix4fv(uMVPLoc, 1, false, mvpMatrix, 0)
        GLES20.glUniformMatrix4fv(uTexMatrixLoc, 1, false, texMatrix, 0)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glUniform1i(uTextureLoc, 0)

        GLES20.glEnableVertexAttribArray(aPositionLoc)
        GLES20.glVertexAttribPointer(aPositionLoc, 2, GLES20.GL_FLOAT, false, 0, vertexBuffer)

        GLES20.glEnableVertexAttribArray(aTexCoordLoc)
        GLES20.glVertexAttribPointer(aTexCoordLoc, 2, GLES20.GL_FLOAT, false, 0, texCoordBuffer)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        GLES20.glDisableVertexAttribArray(aPositionLoc)
        GLES20.glDisableVertexAttribArray(aTexCoordLoc)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0)
    }

    private fun drawColorQuad(color: FloatArray) {
        GLES20.glUseProgram(colorProgram)

        GLES20.glUniformMatrix4fv(uColorMVPLoc, 1, false, identityMVP, 0)
        GLES20.glUniform4fv(uColorLoc, 1, color, 0)

        GLES20.glEnableVertexAttribArray(aColorPositionLoc)
        GLES20.glVertexAttribPointer(aColorPositionLoc, 2, GLES20.GL_FLOAT, false, 0, vertexBuffer)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        GLES20.glDisableVertexAttribArray(aColorPositionLoc)
    }

    private fun createProgram(vertexCode: String, fragmentCode: String): Int {
        val vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, vertexCode)
        val fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentCode)
        val program = GLES20.glCreateProgram()
        if (program == 0) {
            throw RuntimeException("glCreateProgram failed")
        }
        GLES20.glAttachShader(program, vertexShader)
        GLES20.glAttachShader(program, fragmentShader)
        GLES20.glLinkProgram(program)

        val linkStatus = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linkStatus, 0)
        if (linkStatus[0] == 0) {
            val log = GLES20.glGetProgramInfoLog(program)
            GLES20.glDeleteProgram(program)
            throw RuntimeException("Shader program linking failed: $log")
        }
        GLES20.glDeleteShader(vertexShader)
        GLES20.glDeleteShader(fragmentShader)
        return program
    }

    private fun loadShader(type: Int, code: String): Int {
        val shader = GLES20.glCreateShader(type)
        if (shader == 0) {
            throw RuntimeException("glCreateShader failed for type $type")
        }
        GLES20.glShaderSource(shader, code.trimIndent())
        GLES20.glCompileShader(shader)

        val compileStatus = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compileStatus, 0)
        if (compileStatus[0] == 0) {
            val log = GLES20.glGetShaderInfoLog(shader)
            GLES20.glDeleteShader(shader)
            throw RuntimeException("Shader compilation failed: $log")
        }
        return shader
    }

    fun release() {
        if (!isRunning.getAndSet(false)) return
        Log.d(TAG, "Releasing StreamFrameCompositor...")

        val releaseLatch = CountDownLatch(1)
        glHandler?.post {
            try {
                if (texProgram != 0) {
                    GLES20.glDeleteProgram(texProgram)
                    texProgram = 0
                }
                if (colorProgram != 0) {
                    GLES20.glDeleteProgram(colorProgram)
                    colorProgram = 0
                }

                val textures = intArrayOf(screenTexId, cameraTexId)
                GLES20.glDeleteTextures(2, textures, 0)

                screenSurfaceTexture?.release()
                screenSurfaceTexture = null
                _screenSurface?.release()
                _screenSurface = null

                cameraSurfaceTexture.release()
                _cameraSurfaceTexture = null

                if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
                    EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                    if (eglSurface != EGL14.EGL_NO_SURFACE) {
                        EGL14.eglDestroySurface(eglDisplay, eglSurface)
                        eglSurface = EGL14.EGL_NO_SURFACE
                    }
                    if (eglContext != EGL14.EGL_NO_CONTEXT) {
                        EGL14.eglDestroyContext(eglDisplay, eglContext)
                        eglContext = EGL14.EGL_NO_CONTEXT
                    }
                    EGL14.eglTerminate(eglDisplay)
                    eglDisplay = EGL14.EGL_NO_DISPLAY
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error releasing GL resources: ${e.message}", e)
            } finally {
                releaseLatch.countDown()
            }
        }

        try {
            releaseLatch.await(1, TimeUnit.SECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }

        glThread?.quitSafely()
        try {
            glThread?.join(500)
        } catch (e: Exception) {
            Log.e(TAG, "Error joining GL thread: ${e.message}")
        }
        glThread = null
        glHandler = null
        Log.d(TAG, "StreamFrameCompositor released successfully.")
    }
}
