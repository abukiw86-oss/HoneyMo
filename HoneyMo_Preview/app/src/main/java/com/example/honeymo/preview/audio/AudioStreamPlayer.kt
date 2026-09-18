package com.example.honeymo.preview.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import android.util.Log
import kotlinx.coroutines.*
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Ultra-low latency voice audio decoder & player for HoneyMo Preview.
 * - Hardware AAC-LC decoder (44.1 kHz Mono)
 * - Low-latency AudioTrack output
 * - Instant Mute/Unmute via zero-latency volume scaling without pipeline stall
 */
class AudioStreamPlayer {

    companion object {
        private const val TAG = "AudioStreamPlayer"
        const val SAMPLE_RATE = 44100
        const val CHANNEL_CONFIG = AudioFormat.CHANNEL_OUT_MONO
        const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    }

    private var audioTrack: AudioTrack? = null
    private var audioDecoder: MediaCodec? = null
    private val isRunning = AtomicBoolean(false)
    private val isMuted = AtomicBoolean(false)

    private var cachedCsd0: ByteArray? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val bufferInfo = MediaCodec.BufferInfo()

    init {
        initAudioTrack()
    }

    private fun initAudioTrack() {
        try {
            val minBufferSize = AudioTrack.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
            val bufferSize = maxOf(minBufferSize, 4096)

            val attributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()

            val format = AudioFormat.Builder()
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(CHANNEL_CONFIG)
                .setEncoding(AUDIO_FORMAT)
                .build()

            val track = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                AudioTrack.Builder()
                    .setAudioAttributes(attributes)
                    .setAudioFormat(format)
                    .setBufferSizeInBytes(bufferSize)
                    .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()
            } else {
                @Suppress("DEPRECATION")
                AudioTrack(
                    attributes,
                    format,
                    bufferSize,
                    AudioTrack.MODE_STREAM,
                    android.media.AudioManager.AUDIO_SESSION_ID_GENERATE
                )
            }

            track.play()
            if (isMuted.get()) {
                track.setVolume(0f)
            } else {
                track.setVolume(1f)
            }
            audioTrack = track
            Log.d(TAG, "AudioTrack initialized and playing (low-latency 44.1kHz mono)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize AudioTrack: ${e.message}", e)
        }
    }

    private fun initDecoder(csd0: ByteArray?) {
        try {
            audioDecoder?.stop()
            audioDecoder?.release()
        } catch (e: Exception) {}

        try {
            val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, SAMPLE_RATE, 1).apply {
                if (csd0 != null) {
                    setByteBuffer("csd-0", ByteBuffer.wrap(csd0))
                }
            }

            val decoder = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).apply {
                configure(format, null, null, 0)
                start()
            }
            audioDecoder = decoder
            isRunning.set(true)
            Log.d(TAG, "AAC Audio Decoder initialized (csd0: ${csd0?.size ?: 0} bytes)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize AAC audio decoder: ${e.message}", e)
        }
    }

    fun setMuted(muted: Boolean) {
        isMuted.set(muted)
        try {
            audioTrack?.setVolume(if (muted) 0f else 1f)
            Log.d(TAG, "Audio player muted state set to: $muted")
        } catch (e: Exception) {
            Log.w(TAG, "Error setting AudioTrack volume: ${e.message}")
        }
    }

    fun isMuted(): Boolean = isMuted.get()

    fun onAudioReceived(isConfig: Boolean, ptsUs: Long, chunk: ByteArray) {
        if (isConfig) {
            cachedCsd0 = chunk
            initDecoder(chunk)
            return
        }

        if (audioDecoder == null) {
            initDecoder(cachedCsd0)
        }

        val decoder = audioDecoder ?: return

        try {
            // Queue into decoder
            val inIndex = decoder.dequeueInputBuffer(4000L)
            if (inIndex >= 0) {
                val inBuffer = decoder.getInputBuffer(inIndex)
                if (inBuffer != null) {
                    inBuffer.clear()
                    inBuffer.put(chunk)
                    decoder.queueInputBuffer(inIndex, 0, chunk.size, ptsUs, 0)
                }
            }

            // Drain decoded PCM
            var outIndex = decoder.dequeueOutputBuffer(bufferInfo, 0L)
            while (outIndex >= 0) {
                val outBuffer = decoder.getOutputBuffer(outIndex)
                if (outBuffer != null && bufferInfo.size > 0) {
                    outBuffer.position(bufferInfo.offset)
                    outBuffer.limit(bufferInfo.offset + bufferInfo.size)

                    val pcmBytes = ByteArray(bufferInfo.size)
                    outBuffer.get(pcmBytes)

                    audioTrack?.write(pcmBytes, 0, pcmBytes.size)
                }

                decoder.releaseOutputBuffer(outIndex, false)
                outIndex = decoder.dequeueOutputBuffer(bufferInfo, 0L)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error processing audio frame: ${e.message}")
        }
    }

    fun release() {
        isRunning.set(false)
        scope.cancel()

        try {
            audioDecoder?.stop()
            audioDecoder?.release()
        } catch (e: Exception) {}
        audioDecoder = null

        try {
            audioTrack?.stop()
            audioTrack?.release()
        } catch (e: Exception) {}
        audioTrack = null

        Log.d(TAG, "AudioStreamPlayer released")
    }
}
