package com.minichat.app.notifications

import android.Manifest
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.minichat.app.MainActivity
import com.minichat.app.R
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Builds and posts message notifications.
 *
 * Tapping a notification deep links straight into the conversation (`minichat://chat/<id>` is
 * resolved by [MainActivity]); the back stack is preserved with a synthetic parent intent.
 */
@Singleton
class NotificationHelper @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private val manager = NotificationManagerCompat.from(context)

    fun canPostNotifications(): Boolean {
        val permissionGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        return permissionGranted && manager.areNotificationsEnabled()
    }

    fun showMessageNotification(
        conversationId: String,
        senderName: String,
        messagePreview: String,
        showPreview: Boolean,
        soundEnabled: Boolean,
        vibrationEnabled: Boolean,
    ) {
        if (!canPostNotifications()) return

        NotificationChannels.ensureCreated(context)

        val contentIntent = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            data = android.net.Uri.parse("minichat://chat/$conversationId")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }

        val pendingIntent = PendingIntent.getActivity(
            context,
            conversationId.hashCode(),
            contentIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val body = if (showPreview) messagePreview else "You have a new message"

        val builder = NotificationCompat.Builder(context, NotificationChannels.MESSAGES)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(ContextCompat.getColor(context, R.color.minichat_notification_accent))
            .setContentTitle(senderName)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setGroup(GROUP_MESSAGES)
            .setWhen(System.currentTimeMillis())
            .setShowWhen(true)

        if (!soundEnabled) {
            builder.setSilent(true)
        }
        if (!vibrationEnabled) {
            builder.setVibrate(longArrayOf())
        }

        runCatching {
            manager.notify(conversationId.hashCode(), builder.build())
            postGroupSummary()
        }
    }

    private fun postGroupSummary() {
        val summary = NotificationCompat.Builder(context, NotificationChannels.MESSAGES)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("MINI CHAT")
            .setContentText("New messages")
            .setGroup(GROUP_MESSAGES)
            .setGroupSummary(true)
            .setAutoCancel(true)
            .build()
        runCatching { manager.notify(GROUP_SUMMARY_ID, summary) }
    }

    fun clearConversationNotifications(conversationId: String) {
        manager.cancel(conversationId.hashCode())
    }

    fun clearAll() {
        runCatching { manager.cancelAll() }
    }

    fun notifySessionExpired() {
        if (!canPostNotifications()) return
        val builder = NotificationCompat.Builder(context, NotificationChannels.SYSTEM)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Signed out")
            .setContentText("Your session expired. Open MINI CHAT to sign in again.")
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
        runCatching { manager.notify(SESSION_NOTIFICATION_ID, builder.build()) }
    }

    @Suppress("unused")
    private fun systemManager(): NotificationManager? =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager

    private companion object {
        const val GROUP_MESSAGES = "minichat.messages"
        const val GROUP_SUMMARY_ID = 1001
        const val SESSION_NOTIFICATION_ID = 1002
    }
}
