package com.minichat.app.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import androidx.core.content.getSystemService
import com.minichat.app.R

/**
 * Notification channels are created once at startup. Users can mute or tune them from Android
 * settings, and MINI CHAT additionally honours the in-app sound / vibration preferences.
 */
object NotificationChannels {

    const val MESSAGES = "messages"
    const val SYSTEM = "system"
    const val CALLS = "calls"
    const val CALL_ONGOING = "calls_ongoing"

    fun ensureCreated(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService<NotificationManager>() ?: return

        val soundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        val audioAttributes = AudioAttributes.Builder()
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .setUsage(AudioAttributes.USAGE_NOTIFICATION_COMMUNICATION_INSTANT)
            .build()

        val messages = NotificationChannel(
            MESSAGES,
            context.getString(R.string.notification_channel_messages),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = context.getString(R.string.notification_channel_messages_description)
            enableLights(true)
            lightColor = 0xFFFFD43B.toInt()
            enableVibration(true)
            setShowBadge(true)
            setSound(soundUri, audioAttributes)
        }

        val system = NotificationChannel(
            SYSTEM,
            context.getString(R.string.notification_channel_system),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = context.getString(R.string.notification_channel_system_description)
            setShowBadge(false)
        }

        val ringtone = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
        val callAttributes = AudioAttributes.Builder()
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
            .build()

        val calls = NotificationChannel(
            CALLS,
            context.getString(R.string.notification_channel_calls),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = context.getString(R.string.notification_channel_calls_description)
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 600, 400, 600)
            setSound(ringtone, callAttributes)
            setShowBadge(true)
            lockscreenVisibility = android.app.Notification.VISIBILITY_PRIVATE
        }

        val ongoing = NotificationChannel(
            CALL_ONGOING,
            context.getString(R.string.notification_channel_calls_ongoing),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = context.getString(R.string.notification_channel_calls_ongoing_description)
            setShowBadge(false)
            setSound(null, null)
        }

        manager.createNotificationChannels(listOf(messages, system, calls, ongoing))
    }
}
