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

    /**
     * The silent, minimum-importance channel the realtime listener runs on.
     * Android requires a foreground service to post a notification; this one is
     * deliberately invisible in practice (no sound, no vibration, no badge).
     */
    const val SERVICE = "realtime_service"

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

        // Minimum importance + no badge: this channel exists only because a
        // foreground service is required to keep the socket alive.
        val service = NotificationChannel(
            SERVICE,
            "Background sync",
            NotificationManager.IMPORTANCE_MIN,
        ).apply {
            description = "Keeps your messages arriving while Mini Chat is closed"
            setShowBadge(false)
            enableVibration(false)
            setSound(null, null)
        }

        manager.createNotificationChannels(listOf(messages, system, service))
    }
}
