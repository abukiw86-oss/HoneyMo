package com.example.honeymo.preview.util

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import android.view.PixelCopy
import android.view.SurfaceView
import java.io.File
import java.io.FileOutputStream

object ScreenshotHelper {

    private const val TAG = "ScreenshotHelper"

    fun captureSurface(
        surfaceView: SurfaceView,
        context: Context,
        onSuccess: (fileName: String, uri: Uri?) -> Unit,
        onError: (String) -> Unit
    ) {
        val surface = surfaceView.holder.surface
        if (surface == null || !surface.isValid) {
            onError("Video stream is not ready yet. Please wait for frames to display.")
            return
        }

        val width = surfaceView.width
        val height = surfaceView.height
        if (width <= 0 || height <= 0) {
            onError("Video display size is zero. Please wait a moment.")
            return
        }

        try {
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

            PixelCopy.request(surfaceView, bitmap, { copyResult ->
                if (copyResult == PixelCopy.SUCCESS) {
                    saveBitmap(context, bitmap, onSuccess, onError)
                } else {
                    val reason = when (copyResult) {
                        PixelCopy.ERROR_SOURCE_NO_DATA -> "No video data available yet"
                        PixelCopy.ERROR_SOURCE_INVALID -> "Surface is invalid"
                        PixelCopy.ERROR_DESTINATION_INVALID -> "Destination bitmap invalid"
                        PixelCopy.ERROR_TIMEOUT -> "Capture timed out"
                        else -> "Capture failed (code $copyResult)"
                    }
                    Log.e(TAG, "PixelCopy error: $reason")
                    onError(reason)
                }
            }, Handler(Looper.getMainLooper()))
        } catch (e: Exception) {
            Log.e(TAG, "Exception during PixelCopy.request: ${e.message}", e)
            onError(e.message ?: "Screenshot capture error")
        }
    }

    private fun saveBitmap(
        context: Context,
        bitmap: Bitmap,
        onSuccess: (fileName: String, uri: Uri?) -> Unit,
        onError: (String) -> Unit
    ) {
        val fileName = "HoneyMo_Screenshot_${System.currentTimeMillis()}.jpg"

        // Strategy 1: Modern MediaStore insert
        try {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/HoneyMo")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
            }

            val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            if (uri != null) {
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    values.clear()
                    values.put(MediaStore.Images.Media.IS_PENDING, 0)
                    context.contentResolver.update(uri, values, null, null)
                }

                Log.d(TAG, "Screenshot saved via MediaStore: $uri")
                onSuccess(fileName, uri)
                return
            }
        } catch (e: Exception) {
            Log.w(TAG, "MediaStore save failed, falling back to direct file write: ${e.message}")
        }

        // Strategy 2: Direct public Pictures directory write
        try {
            val picturesDir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                "HoneyMo"
            ).apply { if (!exists()) mkdirs() }

            val file = File(picturesDir, fileName)
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
            }

            // Trigger MediaScanner so it shows in Gallery immediately
            MediaScannerConnection.scanFile(
                context,
                arrayOf(file.absolutePath),
                arrayOf("image/jpeg")
            ) { _, scannedUri ->
                Log.d(TAG, "Screenshot scanned into media library: $scannedUri")
            }

            onSuccess(fileName, Uri.fromFile(file))
            return
        } catch (e: Exception) {
            Log.w(TAG, "Direct Pictures dir save failed, trying app external files: ${e.message}")
        }

        // Strategy 3: App-specific external pictures directory (never fails permission checks)
        try {
            val appPicturesDir = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES)
                ?: context.filesDir
            val file = File(appPicturesDir, fileName)
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
            }

            MediaScannerConnection.scanFile(
                context,
                arrayOf(file.absolutePath),
                arrayOf("image/jpeg"),
                null
            )

            onSuccess(fileName, Uri.fromFile(file))
        } catch (e: Exception) {
            Log.e(TAG, "All screenshot save strategies failed: ${e.message}", e)
            onError("Failed to save screenshot: ${e.message}")
        }
    }
}
