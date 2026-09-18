package com.example.honeymo.preview.record

import android.content.ContentValues
import android.content.Context
import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

class StreamRecorder {

    companion object {
        private const val TAG = "StreamRecorder"
    }

    private var mediaMuxer: MediaMuxer? = null
    private var videoTrackIndex = -1
    private var audioTrackIndex = -1
    private var tempFile: File? = null
    private val isRecording = AtomicBoolean(false)
    private var isMuxerStarted = false

    private var cachedSps: ByteArray? = null
    private var cachedPps: ByteArray? = null
    private var cachedAudioCsd0: ByteArray? = null
    private var width: Int = 544
    private var height: Int = 960

    private var startNanoTime = 0L
    private var lastVideoPtsUs = 0L
    private var lastAudioPtsUs = 0L
    private var videoFrameCount = 0
    private var audioFrameCount = 0

    fun start(
        context: Context,
        videoWidth: Int,
        videoHeight: Int,
        sps: ByteArray? = null,
        pps: ByteArray? = null
    ): Boolean {
        if (isRecording.get()) return false

        width = if (videoWidth > 0) videoWidth else 544
        height = if (videoHeight > 0) videoHeight else 960
        cachedSps = sps
        cachedPps = pps
        isMuxerStarted = false
        videoTrackIndex = -1
        audioTrackIndex = -1
        videoFrameCount = 0
        audioFrameCount = 0
        lastVideoPtsUs = 0L
        lastAudioPtsUs = 0L

        try {
            val outputDir = context.cacheDir
            tempFile = File.createTempFile("honeymo_rec_", ".mp4", outputDir)
            mediaMuxer = MediaMuxer(tempFile!!.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            isRecording.set(true)
            startNanoTime = System.nanoTime()
            Log.d(TAG, "Recording started to temp file: ${tempFile?.absolutePath}")

            // If SPS and PPS are already cached, start muxer track immediately
            if (cachedSps != null && cachedPps != null) {
                initMuxerTracks(cachedSps!!, cachedPps!!)
            }

            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start MediaMuxer: ${e.message}", e)
            cleanup()
            return false
        }
    }

    fun onVideoFrame(chunk: ByteArray) {
        if (!isRecording.get()) return

        if (!isMuxerStarted) {
            val (sps, pps) = extractSpsPps(chunk)
            if (sps != null) cachedSps = sps
            if (pps != null) cachedPps = pps

            if (cachedSps != null && cachedPps != null) {
                initMuxerTracks(cachedSps!!, cachedPps!!)
            } else {
                return
            }
        }

        if (!isMuxerStarted || mediaMuxer == null || videoTrackIndex < 0) return

        val isKeyFrame = isIdrFrame(chunk)
        if (videoFrameCount == 0 && !isKeyFrame) {
            return // Wait for first keyframe
        }

        try {
            val nowNano = System.nanoTime()
            var ptsUs = (nowNano - startNanoTime) / 1000L
            if (ptsUs <= lastVideoPtsUs) {
                ptsUs = lastVideoPtsUs + 1000L
            }
            lastVideoPtsUs = ptsUs

            val bufferInfo = MediaCodec.BufferInfo().apply {
                offset = 0
                size = chunk.size
                presentationTimeUs = ptsUs
                flags = if (isKeyFrame) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
            }

            val buffer = ByteBuffer.wrap(chunk)
            mediaMuxer?.writeSampleData(videoTrackIndex, buffer, bufferInfo)
            videoFrameCount++
        } catch (e: Exception) {
            Log.e(TAG, "Error writing video frame to muxer: ${e.message}")
        }
    }

    fun onAudioConfig(csd0: ByteArray) {
        cachedAudioCsd0 = csd0
        if (isRecording.get() && !isMuxerStarted && cachedSps != null && cachedPps != null) {
            initMuxerTracks(cachedSps!!, cachedPps!!)
        }
    }

    fun onAudioFrame(chunk: ByteArray, ptsUs: Long) {
        if (!isRecording.get()) return
        if (!isMuxerStarted || mediaMuxer == null || audioTrackIndex < 0) return

        try {
            var pts = ptsUs
            if (pts <= 0L) {
                val nowNano = System.nanoTime()
                pts = (nowNano - startNanoTime) / 1000L
            }
            if (pts <= lastAudioPtsUs) {
                pts = lastAudioPtsUs + 23_220L
            }
            lastAudioPtsUs = pts

            val bufferInfo = MediaCodec.BufferInfo().apply {
                offset = 0
                size = chunk.size
                presentationTimeUs = pts
                flags = 0
            }

            val buffer = ByteBuffer.wrap(chunk)
            mediaMuxer?.writeSampleData(audioTrackIndex, buffer, bufferInfo)
            audioFrameCount++
        } catch (e: Exception) {
            Log.w(TAG, "Error writing audio frame to muxer: ${e.message}")
        }
    }

    private fun initMuxerTracks(sps: ByteArray, pps: ByteArray) {
        val muxer = mediaMuxer ?: return
        if (isMuxerStarted) return

        try {
            // Video Track (H.264 AVC)
            val videoFormat = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
                setByteBuffer("csd-0", ByteBuffer.wrap(sps))
                setByteBuffer("csd-1", ByteBuffer.wrap(pps))
            }
            videoTrackIndex = muxer.addTrack(videoFormat)

            // Audio Track (AAC-LC 44.1 kHz Mono)
            val audioCsd = cachedAudioCsd0 ?: byteArrayOf(0x12.toByte(), 0x08.toByte())
            val audioFormat = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, 44100, 1).apply {
                setInteger(MediaFormat.KEY_BIT_RATE, 32000)
                setInteger(MediaFormat.KEY_AAC_PROFILE, android.media.MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setByteBuffer("csd-0", ByteBuffer.wrap(audioCsd))
            }
            audioTrackIndex = muxer.addTrack(audioFormat)

            muxer.start()
            isMuxerStarted = true
            Log.d(TAG, "MediaMuxer started with Video track $videoTrackIndex and Audio track $audioTrackIndex")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize muxer track: ${e.message}", e)
        }
    }

    fun stop(context: Context): Pair<Uri?, String?> {
        if (!isRecording.getAndSet(false)) return Pair(null, null)

        val file = tempFile
        val muxer = mediaMuxer

        try {
            if (isMuxerStarted && muxer != null) {
                muxer.stop()
                muxer.release()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping MediaMuxer: ${e.message}")
        } finally {
            mediaMuxer = null
            isMuxerStarted = false
        }

        if (file == null || !file.exists() || file.length() == 0L || videoFrameCount == 0) {
            file?.delete()
            return Pair(null, null)
        }

        val fileName = "HoneyMo_Recording_${System.currentTimeMillis()}.mp4"

        // Strategy 1: Modern MediaStore (Movies/HoneyMo)
        try {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/HoneyMo")
                    put(MediaStore.Video.Media.IS_PENDING, 1)
                }
            }

            val savedUri = context.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            if (savedUri != null) {
                context.contentResolver.openOutputStream(savedUri)?.use { out ->
                    FileInputStream(file).use { input ->
                        input.copyTo(out)
                    }
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    values.clear()
                    values.put(MediaStore.Video.Media.IS_PENDING, 0)
                    context.contentResolver.update(savedUri, values, null, null)
                }

                Log.d(TAG, "Saved video via MediaStore: $savedUri")
                file.delete()
                return Pair(savedUri, fileName)
            }
        } catch (e: Exception) {
            Log.w(TAG, "MediaStore video save failed, falling back to file write: ${e.message}")
        }

        // Strategy 2: Direct public Movies directory
        try {
            val moviesDir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
                "HoneyMo"
            ).apply { if (!exists()) mkdirs() }

            val destFile = File(moviesDir, fileName)
            FileInputStream(file).use { input ->
                FileOutputStream(destFile).use { out ->
                    input.copyTo(out)
                }
            }

            MediaScannerConnection.scanFile(
                context,
                arrayOf(destFile.absolutePath),
                arrayOf("video/mp4"),
                null
            )

            file.delete()
            return Pair(Uri.fromFile(destFile), fileName)
        } catch (e: Exception) {
            Log.w(TAG, "Direct public Movies save failed, trying app external files: ${e.message}")
        }

        // Strategy 3: App external files dir (always succeeds)
        try {
            val appMoviesDir = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES)
                ?: context.filesDir
            val destFile = File(appMoviesDir, fileName)
            file.copyTo(destFile, overwrite = true)
            file.delete()

            MediaScannerConnection.scanFile(
                context,
                arrayOf(destFile.absolutePath),
                arrayOf("video/mp4"),
                null
            )

            return Pair(Uri.fromFile(destFile), fileName)
        } catch (e: Exception) {
            Log.e(TAG, "All video save strategies failed: ${e.message}", e)
            file.delete()
            return Pair(null, null)
        }
    }

    fun isRecording(): Boolean = isRecording.get()

    fun getDurationSeconds(): Int {
        if (!isRecording.get()) return 0
        return ((System.nanoTime() - startNanoTime) / 1_000_000_000L).toInt()
    }

    private fun cleanup() {
        isRecording.set(false)
        isMuxerStarted = false
        videoTrackIndex = -1
        audioTrackIndex = -1
        try {
            mediaMuxer?.release()
        } catch (e: Exception) {}
        mediaMuxer = null
        tempFile?.delete()
        tempFile = null
    }

    private fun extractSpsPps(chunk: ByteArray): Pair<ByteArray?, ByteArray?> {
        var sps: ByteArray? = null
        var pps: ByteArray? = null
        val nalOffsets = mutableListOf<Int>()
        for (i in 0 until chunk.size - 3) {
            if (chunk[i].toInt() == 0 && chunk[i + 1].toInt() == 0) {
                if (chunk[i + 2].toInt() == 1) {
                    nalOffsets.add(i)
                } else if (i < chunk.size - 4 && chunk[i + 2].toInt() == 0 && chunk[i + 3].toInt() == 1) {
                    nalOffsets.add(i)
                }
            }
        }
        for (idx in nalOffsets.indices) {
            val start = nalOffsets[idx]
            val end = if (idx + 1 < nalOffsets.size) nalOffsets[idx + 1] else chunk.size
            val nalStart = if (chunk[start + 2].toInt() == 1) start + 3 else start + 4
            if (nalStart < end) {
                val nalType = chunk[nalStart].toInt() and 0x1F
                val nalData = chunk.copyOfRange(start, end)
                if (nalType == 7 && sps == null) sps = nalData
                if (nalType == 8 && pps == null) pps = nalData
            }
        }
        return Pair(sps, pps)
    }

    private fun isIdrFrame(chunk: ByteArray): Boolean {
        for (i in 0 until minOf(chunk.size - 4, 64)) {
            if (chunk[i].toInt() == 0 && chunk[i + 1].toInt() == 0) {
                val nalStart = if (chunk[i + 2].toInt() == 1) i + 3 else if (chunk[i + 2].toInt() == 0 && chunk[i + 3].toInt() == 1) i + 4 else -1
                if (nalStart != -1 && nalStart < chunk.size) {
                    val nalType = chunk[nalStart].toInt() and 0x1F
                    if (nalType == 5) return true
                }
            }
        }
        return false
    }
}
