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

        manager.createNotificationChannels(listOf(messages, system))
    }
}
