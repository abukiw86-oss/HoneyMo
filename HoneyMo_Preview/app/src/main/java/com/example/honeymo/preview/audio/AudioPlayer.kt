package com.example.honeymo.preview.audio

import android.media.*
import android.util.Log
import kotlinx.coroutines.*
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

class AudioPlayer {

    companion object {
        private const val TAG = "AudioPlayer"
        private const val SAMPLE_RATE = 44100
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_OUT_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val MIME_TYPE = MediaFormat.MIMETYPE_AUDIO_AAC
    }

    private var decoder: MediaCodec? = null
    private var audioTrack: AudioTrack? = null
    private val isRunning = AtomicBoolean(false)
    private var isMuted = false
    private var outputDrainJob: Job? = null
    private val playerScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    fun start() {
        if (isRunning.get()) return

        try {
            val minBufSize = AudioTrack.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
            val trackBufSize = maxOf(minBufSize * 2, 8192)

            val audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()

            val formatAudioTrack = AudioFormat.Builder()
                .setEncoding(AUDIO_FORMAT)
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(CHANNEL_CONFIG)
                .build()

            audioTrack = AudioTrack.Builder()
                .setAudioAttributes(audioAttributes)
                .setAudioFormat(formatAudioTrack)
                .setBufferSizeInBytes(trackBufSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()

            audioTrack?.play()
            if (isMuted) {
                audioTrack?.setVolume(0f)
            }

            // AAC LC decoder format
            val format = MediaFormat.createAudioFormat(MIME_TYPE, SAMPLE_RATE, 1).apply {
                // csd-0 for AAC-LC 44.1kHz mono (AudioSpecificConfig: profile=2, freq=4, chan=1 -> 0x12, 0x08)
                val csd0 = byteArrayOf(0x12.toByte(), 0x08.toByte())
                setByteBuffer("csd-0", ByteBuffer.wrap(csd0))
            }

            decoder = MediaCodec.createDecoderByType(MIME_TYPE).apply {
                configure(format, null, null, 0)
                start()
            }

            isRunning.set(true)
            startDrainLoop()
            Log.d(TAG, "AudioPlayer started (44.1kHz mono AAC decoder + AudioTrack)")

        } catch (e: Exception) {
            Log.e(TAG, "Failed to start AudioPlayer: ${e.message}", e)
            stop()
        }
    }

    fun feedAdtsFrame(chunk: ByteArray) {
        val dec = decoder ?: return
        if (!isRunning.get()) return

        try {
            val inIndex = dec.dequeueInputBuffer(5_000L) // 5ms timeout
            if (inIndex >= 0) {
                val inputBuf: ByteBuffer? = dec.getInputBuffer(inIndex)
                if (inputBuf != null) {
                    inputBuf.clear()
                    inputBuf.put(chunk)
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
                Log.e(TAG, "Error feeding audio chunk to decoder: ${e.message}")
            }
        }
    }

    private fun startDrainLoop() {
        outputDrainJob = playerScope.launch {
            val bufferInfo = MediaCodec.BufferInfo()
            val dec = decoder ?: return@launch

            while (isActive && isRunning.get()) {
                try {
                    var outIndex = dec.dequeueOutputBuffer(bufferInfo, 2_000L)
                    while (outIndex >= 0 && isActive && isRunning.get()) {
                        val outputBuffer: ByteBuffer? = dec.getOutputBuffer(outIndex)
                        if (outputBuffer != null && bufferInfo.size > 0) {
                            outputBuffer.position(bufferInfo.offset)
                            outputBuffer.limit(bufferInfo.offset + bufferInfo.size)

                            val pcm = ByteArray(bufferInfo.size)
                            outputBuffer.get(pcm)

                            if (!isMuted) {
                                audioTrack?.write(pcm, 0, pcm.size)
                            }
                        }
                        dec.releaseOutputBuffer(outIndex, false)
                        outIndex = dec.dequeueOutputBuffer(bufferInfo, 0L)
                    }
                } catch (e: Exception) {
                    if (isRunning.get()) {
                        Log.e(TAG, "Error in audio drain loop: ${e.message}")
                    }
                    break
                }
            }
        }
    }

    fun setMuted(muted: Boolean) {
        isMuted = muted
        try {
            audioTrack?.setVolume(if (muted) 0f else 1f)
        } catch (e: Exception) {}
    }

    fun isMuted(): Boolean = isMuted

    fun stop() {
        if (!isRunning.getAndSet(false)) return
        Log.d(TAG, "Stopping AudioPlayer...")

        outputDrainJob?.cancel()
        outputDrainJob = null

        try {
            audioTrack?.pause()
            audioTrack?.flush()
            audioTrack?.stop()
            audioTrack?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing AudioTrack: ${e.message}")
        }
        audioTrack = null

        try {
            decoder?.stop()
            decoder?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing audio decoder: ${e.message}")
        }
        decoder = null
    }

    fun release() {
        stop()
        playerScope.cancel()
    }
}
