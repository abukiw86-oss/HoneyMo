package com.example.HoneyMo.audio

import android.annotation.SuppressLint
import android.media.*
import android.os.Process
import android.util.Log
import kotlinx.coroutines.*
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

class AudioCaptureEncoder(
    private val onAdtsPacketAvailable: (ByteArray) -> Unit
) {

    companion object {
        private const val TAG = "AudioCaptureEncoder"
        private const val SAMPLE_RATE = 44100
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val BIT_RATE = 48000 // 48 kbps mono AAC
        private const val MIME_TYPE = MediaFormat.MIMETYPE_AUDIO_AAC
    }

    private var audioRecord: AudioRecord? = null
    private var encoder: MediaCodec? = null
    private val isCapturing = AtomicBoolean(false)
    private var recordJob: Job? = null
    private var drainJob: Job? = null
    private val audioScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        if (isCapturing.get()) return true

        val minBufSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        if (minBufSize <= 0) {
            Log.e(TAG, "Invalid minBufferSize: $minBufSize")
            return false
        }
        val bufferSize = maxOf(minBufSize * 2, 4096)

        try {
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

            // Configure AAC MediaCodec encoder
            val format = MediaFormat.createAudioFormat(MIME_TYPE, SAMPLE_RATE, 1).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, bufferSize)
            }

            encoder = MediaCodec.createEncoderByType(MIME_TYPE).apply {
                configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                start()
            }

            audioRecord?.startRecording()
            isCapturing.set(true)

            startRecordingLoop(bufferSize)
            startDrainLoop()

            Log.d(TAG, "Audio capture and AAC encoder started successfully (44.1kHz, 48kbps mono)")
            return true

        } catch (e: Exception) {
            Log.e(TAG, "Error starting AudioCaptureEncoder: ${e.message}", e)
            cleanup()
            return false
        }
    }

    private fun startRecordingLoop(bufferSize: Int) {
        recordJob = audioScope.launch {
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
            val pcmBuffer = ByteArray(bufferSize / 2)
            val record = audioRecord ?: return@launch
            val enc = encoder ?: return@launch

            while (isActive && isCapturing.get()) {
                val readBytes = record.read(pcmBuffer, 0, pcmBuffer.size)
                if (readBytes > 0) {
                    try {
                        val inputIndex = enc.dequeueInputBuffer(10_000L) // 10ms timeout
                        if (inputIndex >= 0) {
                            val inputBuf: ByteBuffer? = enc.getInputBuffer(inputIndex)
                            if (inputBuf != null) {
                                inputBuf.clear()
                                inputBuf.put(pcmBuffer, 0, readBytes)
                                enc.queueInputBuffer(
                                    inputIndex,
                                    0,
                                    readBytes,
                                    System.nanoTime() / 1000L,
                                    0
                                )
                            }
                        }
                    } catch (e: Exception) {
                        if (isCapturing.get()) {
                            Log.e(TAG, "Error feeding PCM into audio encoder: ${e.message}")
                        }
                        break
                    }
                }
            }
        }
    }

    private fun startDrainLoop() {
        drainJob = audioScope.launch {
            val bufferInfo = MediaCodec.BufferInfo()
            val enc = encoder ?: return@launch

            while (isActive && isCapturing.get()) {
                try {
                    var outIndex = enc.dequeueOutputBuffer(bufferInfo, 2_000L)
                    while (outIndex >= 0 && isActive && isCapturing.get()) {
                        val outputBuffer: ByteBuffer? = enc.getOutputBuffer(outIndex)
                        if (outputBuffer != null && bufferInfo.size > 0) {
                            val isConfig = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                            if (!isConfig) {
                                outputBuffer.position(bufferInfo.offset)
                                outputBuffer.limit(bufferInfo.offset + bufferInfo.size)

                                // Create ADTS packet (7 bytes header + raw AAC frame)
                                val outPacketSize = bufferInfo.size + 7
                                val outPacket = ByteArray(outPacketSize)
                                addAdtsHeader(outPacket, outPacketSize)
                                outputBuffer.get(outPacket, 7, bufferInfo.size)

                                onAdtsPacketAvailable(outPacket)
                            }
                        }
                        enc.releaseOutputBuffer(outIndex, false)
                        outIndex = enc.dequeueOutputBuffer(bufferInfo, 0L)
                    }
                } catch (e: Exception) {
                    if (isCapturing.get()) {
                        Log.e(TAG, "Error draining audio encoder: ${e.message}")
                    }
                    break
                }
            }
        }
    }

    /**
     * Appends a standard 7-byte ADTS header for AAC-LC at 44.1kHz mono.
     * ADTS packets start with 0xFF 0xF9 (syncword).
     */
    private fun addAdtsHeader(packet: ByteArray, packetLen: Int) {
        val profile = 2 // AAC LC
        val freqIdx = 4 // 44.1KHz
        val chanCfg = 1 // 1 Channel (Mono)

        packet[0] = 0xFF.toByte()
        packet[1] = 0xF9.toByte()
        packet[2] = (((profile - 1) shl 6) + (freqIdx shl 2) + (chanCfg shr 2)).toByte()
        packet[3] = (((chanCfg and 3) shl 6) + (packetLen shr 11)).toByte()
        packet[4] = ((packetLen and 0x7FF) shr 3).toByte()
        packet[5] = (((packetLen and 7) shl 5) + 0x1F).toByte()
        packet[6] = 0xFC.toByte()
    }

    fun stop() {
        if (!isCapturing.getAndSet(false)) return
        Log.d(TAG, "Stopping audio capture and encoder...")

        recordJob?.cancel()
        recordJob = null
        drainJob?.cancel()
        drainJob = null

        cleanup()
    }

    private fun cleanup() {
        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing AudioRecord: ${e.message}")
        }
        audioRecord = null

        try {
            encoder?.stop()
            encoder?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing audio encoder: ${e.message}")
        }
        encoder = null
    }

    fun release() {
        stop()
        audioScope.cancel()
    }
}
