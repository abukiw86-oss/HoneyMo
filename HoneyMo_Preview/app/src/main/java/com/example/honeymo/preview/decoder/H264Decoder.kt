package com.example.honeymo.preview.decoder

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import android.util.Log
import android.view.Surface
import kotlinx.coroutines.*
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

class H264Decoder(
    private val width: Int,
    private val height: Int,
    private val onFpsUpdated: (Int) -> Unit = {}
) {

    companion object {
        private const val TAG = "H264Decoder"
        private const val MIME_TYPE = MediaFormat.MIMETYPE_VIDEO_AVC // "video/avc"
    }

    private var decoder: MediaCodec? = null
    private val isRunning = AtomicBoolean(false)
    private var outputDrainJob: Job? = null
    private val decoderScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private var frameCount = 0
    private var fpsJob: Job? = null

    fun start(surface: Surface) {
        if (isRunning.get()) {
            stop()
        }

        try {
            Log.d(TAG, "Starting hardware H.264 decoder with surface (${width}x${height})...")
            val format = MediaFormat.createVideoFormat(MIME_TYPE, width, height).apply {
                // Low latency decoding flags on supported Android versions
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    setInteger(MediaFormat.KEY_PRIORITY, 0)
                }
            }

            decoder = MediaCodec.createDecoderByType(MIME_TYPE).apply {
                configure(format, surface, null, 0)
                start()
            }

            isRunning.set(true)
            startOutputDrainLoop()
            startFpsCounter()
            Log.d(TAG, "Hardware H.264 decoder started successfully")

        } catch (e: Exception) {
            Log.e(TAG, "Failed to start MediaCodec decoder: ${e.message}", e)
            stop()
        }
    }

    fun feedFrame(chunk: ByteArray) {
        val dec = decoder ?: return
        if (!isRunning.get()) return

        try {
            val inIndex = dec.dequeueInputBuffer(1_000L) // 1ms non-blocking timeout
            if (inIndex >= 0) {
                val inputBuffer: ByteBuffer? = dec.getInputBuffer(inIndex)
                if (inputBuffer != null) {
                    inputBuffer.clear()
                    inputBuffer.put(chunk)
                    dec.queueInputBuffer(
                        inIndex,
                        0,
                        chunk.size,
                        System.nanoTime() / 1000L,
                        0
                    )
                }
            }
        } catch (e: Exception) {
            if (isRunning.get()) {
                Log.e(TAG, "Error queuing input buffer: ${e.message}")
            }
        }
    }

    private fun startOutputDrainLoop() {
        outputDrainJob = decoderScope.launch(Dispatchers.IO) {
            val bufferInfo = MediaCodec.BufferInfo()
            val dec = decoder ?: return@launch

            while (isActive && isRunning.get()) {
                try {
                    var outIndex = dec.dequeueOutputBuffer(bufferInfo, 2_000L)

                    while (outIndex >= 0 && isActive && isRunning.get()) {
                        // Render directly to Surface with zero delay
                        dec.releaseOutputBuffer(outIndex, true)
                        frameCount++
                        outIndex = dec.dequeueOutputBuffer(bufferInfo, 0L)
                    }

                    if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        Log.d(TAG, "Decoder output format changed: ${dec.outputFormat}")
                    }
                } catch (e: Exception) {
                    if (isRunning.get()) {
                        Log.e(TAG, "Error in output drain loop: ${e.message}")
                    }
                    break
                }
            }
        }
    }

    private fun startFpsCounter() {
        fpsJob = decoderScope.launch {
            while (isActive && isRunning.get()) {
                delay(1000)
                onFpsUpdated(frameCount)
                frameCount = 0
            }
        }
    }

    fun stop() {
        if (!isRunning.getAndSet(false)) return
        Log.d(TAG, "Stopping H264Decoder...")

        outputDrainJob?.cancel()
        outputDrainJob = null

        fpsJob?.cancel()
        fpsJob = null

        try {
            decoder?.stop()
            decoder?.release()
            decoder = null
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing MediaCodec: ${e.message}")
        }
    }

    fun release() {
        stop()
        decoderScope.cancel()
    }
}
