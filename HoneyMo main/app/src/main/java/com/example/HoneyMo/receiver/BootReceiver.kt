package com.example.HoneyMo.receiver

import android.app.ActivityOptions
import android.app.KeyguardManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.HoneyMo.MainActivity
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
                    "Screen Recording Recovery",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "Required to resume screen recording after device restart"
                    enableLights(true)
                    enableVibration(true)
                    val alertSound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                    setSound(
                        alertSound,
                        AudioAttributes.Builder()
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .setUsage(AudioAttributes.USAGE_NOTIFICATION_COMMUNICATION_INSTANT)
                            .build()
                    )
                }
                val nm = context.getSystemService(NotificationManager::class.java)
                nm?.createNotificationChannel(channel)
            }
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        Log.d(TAG, "BootReceiver received broadcast action: $action")

        val wasRecording = SessionPreferences.wasRecordingActive(context)
        Log.d(TAG, "wasRecordingActive: $wasRecording (action=$action)")

        if (!wasRecording) {
            return
        }

        val km = context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        val isLocked = km?.isKeyguardLocked == true
        Log.d(TAG, "Device lock state: isLocked=$isLocked, action=$action")

        createRecoveryNotificationChannel(context)

        // When the user unlocks the phone (ACTION_USER_PRESENT) or if the phone booted without a lock:
        // Launch MainActivity directly so the recovery dialogue appears immediately!
        if (Intent.ACTION_USER_PRESENT == action || !isLocked) {
            Log.d(TAG, "Device is unlocked (action=$action). Launching MainActivity dialogue...")
            launchRecoveryActivity(context)
        } else {
            // Device is locked: post high-priority heads-up / lockscreen notification
            // When user unlocks the phone, ACTION_USER_PRESENT will immediately trigger launchRecoveryActivity
            Log.d(TAG, "Device is locked at boot. Posting notification; awaiting USER_PRESENT unlock.")
            postRecoveryNotification(context)
        }
    }

    private fun getRecoveryIntent(context: Context): Intent {
        return Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
            putExtra("EXTRA_BOOT_LAUNCH", true)
            putExtra("EXTRA_INTERRUPTED_SESSION", true)
        }
    }

    private fun postRecoveryNotification(context: Context) {
        val directIntent = getRecoveryIntent(context)
        val pendingIntent = PendingIntent.getActivity(
            context,
            RECOVERY_NOTIFICATION_ID,
            directIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, RECOVERY_CHANNEL_ID)
            .setContentTitle("Resume Screen Recording")
            .setContentText("Screen recording was interrupted. Tap to resume recording.")
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
    }

    private fun launchRecoveryActivity(context: Context) {
        postRecoveryNotification(context)

        val directIntent = getRecoveryIntent(context)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                val options = ActivityOptions.makeBasic()
                options.setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
                context.startActivity(directIntent, options.toBundle())
            } else {
                context.startActivity(directIntent)
            }
            Log.d(TAG, "Direct activity launch attempted from BootReceiver")
        } catch (e: Exception) {
            Log.w(TAG, "Direct activity launch error: ${e.message}")
        }
    }
}
