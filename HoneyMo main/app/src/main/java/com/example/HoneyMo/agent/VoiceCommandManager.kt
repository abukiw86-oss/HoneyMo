package com.example.HoneyMo.agent

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import com.example.HoneyMo.network.StreamWebSocketClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.sqrt

class VoiceCommandManager(
    private val context: Context,
    private val wsClient: StreamWebSocketClient
) {
    companion object {
        private const val TAG = "VoiceCommandManager"
        private const val SAMPLE_RATE = 44100
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var audioRecord: AudioRecord? = null
    
    private val _isListening = MutableStateFlow(false)
    val isListening: StateFlow<Boolean> = _isListening.asStateFlow()

    @SuppressLint("MissingPermission")
    fun startListening() {
        if (_isListening.value) return
        
        val bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            CHANNEL_CONFIG,
            AUDIO_FORMAT,
            bufferSize
        )

        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord init failed")
            return
        }

        audioRecord?.startRecording()
        _isListening.value = true
        wsClient.sendVoiceStart()

        scope.launch {
            val chunk = ByteArray(1024)
            var silenceFrames = 0
            val maxSilenceFrames = (1.5 * SAMPLE_RATE / (1024 / 2)).toInt()

            while (isActive && _isListening.value) {
                val read = audioRecord?.read(chunk, 0, chunk.size) ?: 0
                if (read > 0) {
                    val pcmBytes = chunk.copyOf(read)
                    wsClient.sendVoiceChunk(pcmBytes)

                    var sum = 0.0
                    for (i in 0 until read step 2) {
                        val sample = (chunk[i].toInt() and 0xFF) or (chunk[i + 1].toInt() shl 8)
                        val shortVal = sample.toShort()
                        sum += shortVal * shortVal
                    }
                    val rms = sqrt(sum / (read / 2))
                    
                    if (rms < 200) {
                        silenceFrames++
                        if (silenceFrames >= maxSilenceFrames) {
                            Log.d(TAG, "Silence detected, auto-stopping")
                            stopListening()
                            break
                        }
                    } else {
                        silenceFrames = 0
                    }
                }
            }
        }
    }

    fun stopListening() {
        if (!_isListening.value) return
        _isListening.value = false
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
        wsClient.sendVoiceEnd()
    }
}
