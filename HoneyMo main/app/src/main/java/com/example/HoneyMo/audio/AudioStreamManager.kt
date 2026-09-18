package com.example.HoneyMo.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Ultra-low latency, non-fluctuating voice audio capture & AAC encoder.
 * Designed for small/constrained connections:
 * - 44,100 Hz Mono AAC-LC @ 32 kbps (~4 KB/s total bandwidth)
 * - 1024-sample frames (~23.2ms latency per frame)
 * - Zero-drift mute/unmute via smooth silence injection (maintains steady clock & zero jitter)
 */
class AudioStreamManager(
    private val context: Context,
    private val onPacketReady: (packet: ByteArray) -> Unit
) {

    companion object {
        private const val TAG = "AudioStreamManager"
        const val SAMPLE_RATE = 44100
        const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        const val BITRATE = 32_000 // 32 kbps CBR
        const val SAMPLES_PER_FRAME = 1024 // 23.2ms per frame

        // 4-byte magic identifier for HoneyMo Audio packets
        val MAGIC_HEADER = byteArrayOf('H'.code.toByte(), 'M'.code.toByte(), 'A'.code.toByte(), '1'.code.toByte())
        const val TYPE_CONFIG: Byte = 0
        const val TYPE_DATA: Byte = 1
    }

    private val isRunning = AtomicBoolean(false)
    private val isMuted = AtomicBoolean(false)

    private var audioRecord: AudioRecord? = null
    private var audioEncoder: MediaCodec? = null

    private var captureJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var cachedCsd0: ByteArray? = null
    private var startNanoTime = 0L
    private var lastPtsUs = 0L

    fun isRunning(): Boolean = isRunning.get()
    fun isMuted(): Boolean = isMuted.get()

    fun setMuted(muted: Boolean) {
        val changed = isMuted.getAndSet(muted) != muted
        if (changed) {
            Log.d(TAG, "Microphone mute state changed: muted=$muted")
        }
    }

    fun start(): Boolean {
        if (isRunning.get()) return true

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "Cannot start AudioStreamManager: RECORD_AUDIO permission not granted")
            return false
        }

        try {
            val minBufSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
            val bufferSize = maxOf(minBufSize, SAMPLES_PER_FRAME * 2 * 4) // At least 4 frames buffer

            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                bufferSize
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord failed to initialize")
                cleanup()
                return false
            }

            // Setup AAC MediaCodec Encoder
            val audioFormat = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, SAMPLE_RATE, 1).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, BITRATE)
                setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 4096)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    setInteger(MediaFormat.KEY_PRIORITY, 0) // Realtime priority
                }
            }

            audioEncoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).apply {
                configure(audioFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                start()
            }

            isRunning.set(true)
            startNanoTime = System.nanoTime()
            lastPtsUs = 0L
            audioRecord?.startRecording()

            startCaptureLoop()
            Log.d(TAG, "AudioStreamManager started successfully (44.1kHz AAC @ 32kbps)")
            return true

        } catch (e: Exception) {
            Log.e(TAG, "Failed to start AudioStreamManager: ${e.message}", e)
            cleanup()
            return false
        }
    }

    private fun startCaptureLoop() {
        captureJob = scope.launch {
            val pcmBuffer = ShortArray(SAMPLES_PER_FRAME)
            val byteBuffer = ByteArray(SAMPLES_PER_FRAME * 2)
            val zeroBytes = ByteArray(SAMPLES_PER_FRAME * 2)

            val record = audioRecord ?: return@launch
            val encoder = audioEncoder ?: return@launch
            val bufferInfo = MediaCodec.BufferInfo()

            while (isActive && isRunning.get()) {
                try {
                    // Read exactly 1024 samples (23.2ms audio)
                    var readSamples = 0
                    while (readSamples < SAMPLES_PER_FRAME && isActive && isRunning.get()) {
                        val result = record.read(pcmBuffer, readSamples, SAMPLES_PER_FRAME - readSamples)
                        if (result > 0) {
                            readSamples += result
                        } else if (result == AudioRecord.ERROR_INVALID_OPERATION || result == AudioRecord.ERROR_BAD_VALUE) {
                            Log.w(TAG, "AudioRecord read error: $result")
                            break
                        }
                    }

                    if (!isActive || !isRunning.get() || readSamples < SAMPLES_PER_FRAME) {
                        continue
                    }

                    // Handle Mute: Zero-fill buffer to maintain uninterrupted clock and avoid jitter
                    if (isMuted.get()) {
                        System.arraycopy(zeroBytes, 0, byteBuffer, 0, byteBuffer.size)
                    } else {
                        var bi = 0
                        for (i in 0 until SAMPLES_PER_FRAME) {
                            val sample = pcmBuffer[i].toInt()
                            byteBuffer[bi++] = (sample and 0xFF).toByte()
                            byteBuffer[bi++] = ((sample shr 8) and 0xFF).toByte()
                        }
                    }

                    // Feed PCM into MediaCodec
                    val inIndex = encoder.dequeueInputBuffer(10_000L) // 10ms timeout
                    if (inIndex >= 0) {
                        val inBuffer = encoder.getInputBuffer(inIndex)
                        if (inBuffer != null) {
                            inBuffer.clear()
                            inBuffer.put(byteBuffer)

                            val nowNano = System.nanoTime()
                            var ptsUs = (nowNano - startNanoTime) / 1000L
                            if (ptsUs <= lastPtsUs) {
                                ptsUs = lastPtsUs + 23_220L // ~1024 samples @ 44.1kHz
                            }
                            lastPtsUs = ptsUs

                            encoder.queueInputBuffer(inIndex, 0, byteBuffer.size, ptsUs, 0)
                        }
                    }

                    // Drain AAC output buffers
                    var outIndex = encoder.dequeueOutputBuffer(bufferInfo, 0L)
                    while (outIndex >= 0 && isActive && isRunning.get()) {
                        val outBuffer = encoder.getOutputBuffer(outIndex)
                        if (outBuffer != null && bufferInfo.size > 0) {
                            outBuffer.position(bufferInfo.offset)
                            outBuffer.limit(bufferInfo.offset + bufferInfo.size)

                            val chunk = ByteArray(bufferInfo.size)
                            outBuffer.get(chunk)

                            val isCodecConfig = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                            if (isCodecConfig) {
                                cachedCsd0 = chunk
                                val packet = buildPacket(TYPE_CONFIG, 0L, chunk)
                                onPacketReady(packet)
                                Log.d(TAG, "Dispatched Audio Config packet (csd-0: ${chunk.size} bytes)")
                            } else {
                                val packet = buildPacket(TYPE_DATA, bufferInfo.presentationTimeUs, chunk)
                                onPacketReady(packet)
                            }
                        }

                        encoder.releaseOutputBuffer(outIndex, false)
                        outIndex = encoder.dequeueOutputBuffer(bufferInfo, 0L)
                    }

                } catch (e: Exception) {
                    if (isRunning.get()) {
                        Log.e(TAG, "Exception in audio capture loop: ${e.message}")
                    }
                    break
                }
            }
        }
    }

    /**
     * Builds an efficient binary packet with format:
     * [4 bytes MAGIC ("HMA1")] [1 byte TYPE] [8 bytes PTS_US] [4 bytes LENGTH] [PAYLOAD]
     */
    private fun buildPacket(type: Byte, ptsUs: Long, payload: ByteArray): ByteArray {
        val totalSize = 4 + 1 + 8 + 4 + payload.size
        val packet = ByteArray(totalSize)
        val buffer = ByteBuffer.wrap(packet).order(ByteOrder.BIG_ENDIAN)

        buffer.put(MAGIC_HEADER)
        buffer.put(type)
        buffer.putLong(ptsUs)
        buffer.putInt(payload.size)
        buffer.put(payload)

        return packet
    }

    fun resendConfig() {
        val csd = cachedCsd0
        if (csd != null && isRunning.get()) {
            val packet = buildPacket(TYPE_CONFIG, 0L, csd)
            onPacketReady(packet)
        }
    }

    fun stop() {
        if (!isRunning.getAndSet(false)) return
        cleanup()
    }

    private fun cleanup() {
        isRunning.set(false)
        captureJob?.cancel()
        captureJob = null

        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping AudioRecord: ${e.message}")
        }
        audioRecord = null

        try {
            audioEncoder?.stop()
            audioEncoder?.release()
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping MediaCodec audio encoder: ${e.message}")
        }
        audioEncoder = null

        Log.d(TAG, "AudioStreamManager stopped and released")
    }
}
